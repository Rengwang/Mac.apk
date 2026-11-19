// media_decode.mm -- real audio DECODE backend for android.media.MediaExtractor
// + MediaCodec, backing the JVM-side stubs with macOS AudioToolbox.
//
// The android.media.* Java surface + state machines, plus PCM OUTPUT (AudioTrack
// /SoundPool/MediaPlayer via audio_backend.mm, OpenSL ES + AAudio via the bionic
// shim) were already real. The gap was DECODE: turning compressed bytes
// (AAC/MP3) into PCM. This file fills it for AUDIO:
//
//   * Demuxer  (backs MediaExtractor): AudioToolbox AudioFile -- open a
//     container, read its COMPRESSED audio access units one at a time + the
//     format (sample rate / channels / the AAC magic cookie aka esds == the
//     Android MediaFormat csd-0).
//   * Decoder  (backs MediaCodec):     AudioToolbox AudioConverter -- decode
//     those compressed access units into interleaved signed-16 PCM.
//
// Both live in AudioToolbox (already linked; no new frameworks). The
// MediaExtractor/MediaCodec buffer-index protocol is implemented in Java
// (MediaExtractor.java / MediaCodec.java); this file is the codec core + the
// NativeMedia JNI surface.
//
// Verbatim-pinned contracts (see docs note + the workflow contract report):
//   AudioFileOpenURL / AudioFileGetProperty(kAudioFilePropertyDataFormat,
//     kAudioFilePropertyMagicCookieData, kAudioFilePropertyPacketSizeUpperBound)
//     / AudioFileReadPacketData -- AudioToolbox/AudioFile.h.
//   AudioConverterNew / AudioConverterSetProperty(
//     kAudioConverterDecompressionMagicCookie) / AudioConverterFillComplexBuffer
//     / AudioConverterReset / AudioConverterDispose -- AudioToolbox/AudioConverter.h.
//   AudioStreamBasicDescription / AudioStreamPacketDescription --
//     CoreAudioTypes/CoreAudioBaseTypes.h.
// Refs: developer.apple.com/documentation/audiotoolbox (AudioFile / AudioConverter),
//   Apple QA1318 (magic-cookie hand-off from the file to the decoder).

#include <AudioToolbox/AudioToolbox.h>
#include <CoreFoundation/CoreFoundation.h>
#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <vector>
#include <deque>

namespace {

// ===================== Demuxer (backs MediaExtractor) =====================

struct Demuxer {
    AudioFileID                 file = nullptr;
    AudioStreamBasicDescription asbd = {};
    std::vector<uint8_t>        cookie;        // esds / AudioSpecificConfig (csd-0); empty for ADTS/MP3
    SInt64                      nextPacket = 0; // read cursor (packet index)
    UInt32                      maxPacketSize = 0;
    UInt64                      packetCount = 0;
    // Pre-read "current" access unit -- MediaExtractor reads the CURRENT sample
    // (readSampleData/getSampleTime) and advance() moves to the next.
    std::vector<uint8_t>        curData;
    SInt64                      curPtsUs = -1;
    bool                        curValid = false;
};

// PTS (microseconds) of the packet at index `pkt`. (AAC: 1024 frames/pkt;
// MP3: 1152.) frames -> us via the sample rate.
static inline SInt64 md_pts_us(const Demuxer* d, SInt64 pkt) {
    double frames = (double)pkt * (double)d->asbd.mFramesPerPacket;
    if (d->asbd.mSampleRate <= 0) return -1;
    return (SInt64)llround(frames * 1.0e6 / d->asbd.mSampleRate);
}

// Read ONE compressed access unit at the cursor into d->curData; advances the
// cursor. Sets curValid=false at EOF.
static void md_fill_current(Demuxer* d) {
    d->curValid = false;
    if (!d->file || d->maxPacketSize == 0) return;
    std::vector<uint8_t> buf(d->maxPacketSize);
    AudioStreamPacketDescription pkt = {};
    UInt32 numBytes   = d->maxPacketSize;
    UInt32 numPackets = 1;
    OSStatus s = AudioFileReadPacketData(
        d->file, false, &numBytes, &pkt, d->nextPacket, &numPackets, buf.data());
    if (s == kAudioFileEndOfFileError || (s == noErr && numPackets == 0)) return; // EOF
    if (s != noErr) return;
    d->curData.assign(buf.data() + pkt.mStartOffset,
                      buf.data() + pkt.mStartOffset + pkt.mDataByteSize);
    d->curPtsUs = md_pts_us(d, d->nextPacket);
    d->nextPacket += numPackets;
    d->curValid = true;
}

}  // namespace

extern "C" {

void* macapk_md_demux_open(const char* path) {
    if (!path || !*path) return nullptr;
    Demuxer* d = new Demuxer();
    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        nullptr, (const UInt8*)path, (CFIndex)strlen(path), false);
    if (!url) { delete d; return nullptr; }
    OSStatus s = AudioFileOpenURL(url, kAudioFileReadPermission, 0, &d->file);
    CFRelease(url);
    if (s != noErr || !d->file) { delete d; return nullptr; }

    UInt32 sz = sizeof(d->asbd);
    if (AudioFileGetProperty(d->file, kAudioFilePropertyDataFormat, &sz, &d->asbd) != noErr) {
        AudioFileClose(d->file); delete d; return nullptr;
    }
    // Magic cookie (csd-0). Absent for raw ADTS/MP3 -> empty, which is fine.
    UInt32 ck = 0, writable = 0;
    if (AudioFileGetPropertyInfo(d->file, kAudioFilePropertyMagicCookieData, &ck, &writable) == noErr
        && ck > 0) {
        d->cookie.resize(ck);
        AudioFileGetProperty(d->file, kAudioFilePropertyMagicCookieData, &ck, d->cookie.data());
        d->cookie.resize(ck);
    }
    UInt32 ubsz = sizeof(d->maxPacketSize);
    if (AudioFileGetProperty(d->file, kAudioFilePropertyPacketSizeUpperBound, &ubsz, &d->maxPacketSize) != noErr
        || d->maxPacketSize == 0) {
        d->maxPacketSize = 8192;  // generous fallback for AAC/MP3
    }
    UInt32 pcsz = sizeof(d->packetCount);
    AudioFileGetProperty(d->file, kAudioFilePropertyAudioDataPacketCount, &pcsz, &d->packetCount);

    md_fill_current(d);  // pre-read the first access unit
    std::fprintf(stderr,
        "[media] demux open: %g Hz, %u ch, fmt=0x%08x, %llu packets, maxpkt=%u, cookie=%zu B\n",
        d->asbd.mSampleRate, (unsigned)d->asbd.mChannelsPerFrame,
        (unsigned)d->asbd.mFormatID, (unsigned long long)d->packetCount,
        (unsigned)d->maxPacketSize, d->cookie.size());
    return d;
}

// 1 if an audio track was found, else 0. (AudioFile exposes a single audio
// stream; container video tracks are not handled by this audio path.)
int macapk_md_demux_track_count(void* h) {
    Demuxer* d = (Demuxer*)h;
    return (d && d->asbd.mSampleRate > 0 && d->asbd.mChannelsPerFrame > 0) ? 1 : 0;
}
int macapk_md_demux_sample_rate(void* h) { Demuxer* d=(Demuxer*)h; return d?(int)d->asbd.mSampleRate:0; }
int macapk_md_demux_channels(void* h)    { Demuxer* d=(Demuxer*)h; return d?(int)d->asbd.mChannelsPerFrame:0; }
// 1=AAC ('aac '), 2=MP3 ('.mp3'), 0=other.
int macapk_md_demux_format_id(void* h) {
    Demuxer* d=(Demuxer*)h; if (!d) return 0;
    if (d->asbd.mFormatID == kAudioFormatMPEG4AAC || d->asbd.mFormatID == kAudioFormatMPEG4AAC_HE) return 1;
    if (d->asbd.mFormatID == kAudioFormatMPEGLayer3) return 2;
    return 0;
}
int64_t macapk_md_demux_duration_us(void* h) {
    Demuxer* d=(Demuxer*)h; if (!d || d->asbd.mSampleRate<=0) return -1;
    double frames = (double)d->packetCount * (double)d->asbd.mFramesPerPacket;
    return (int64_t)llround(frames * 1.0e6 / d->asbd.mSampleRate);
}
int macapk_md_demux_csd0_size(void* h) { Demuxer* d=(Demuxer*)h; return d?(int)d->cookie.size():0; }
int macapk_md_demux_csd0(void* h, void* out, int cap) {
    Demuxer* d=(Demuxer*)h; if (!d) return 0;
    int n = (int)d->cookie.size();
    if (out && cap >= n && n > 0) std::memcpy(out, d->cookie.data(), n);
    return n;
}
int macapk_md_demux_current_size(void* h) { Demuxer* d=(Demuxer*)h; return (d&&d->curValid)?(int)d->curData.size():-1; }
// Copies the current access unit into out(cap); returns its size, or -1 at EOS.
int macapk_md_demux_current(void* h, void* out, int cap) {
    Demuxer* d=(Demuxer*)h; if (!d || !d->curValid) return -1;
    int n = (int)d->curData.size();
    if (out && cap >= n) std::memcpy(out, d->curData.data(), n);
    return n;
}
int64_t macapk_md_demux_sample_time(void* h) { Demuxer* d=(Demuxer*)h; return (d&&d->curValid)?d->curPtsUs:-1; }
// Advance to the next access unit; returns 1 if a new current is available, 0 at EOS.
int macapk_md_demux_advance(void* h) { Demuxer* d=(Demuxer*)h; if(!d) return 0; md_fill_current(d); return d->curValid?1:0; }
void macapk_md_demux_seek(void* h, int64_t us) {
    Demuxer* d=(Demuxer*)h; if(!d || d->asbd.mSampleRate<=0) return;
    double pkt = ((double)us * 1.0e-6) * d->asbd.mSampleRate / (double)d->asbd.mFramesPerPacket;
    SInt64 p = (SInt64)llround(pkt); if (p<0) p=0;
    d->nextPacket = p;
    md_fill_current(d);
}
void macapk_md_demux_close(void* h) {
    Demuxer* d=(Demuxer*)h; if(!d) return;
    if (d->file) AudioFileClose(d->file);
    delete d;
}

// ===================== Decoder (backs MediaCodec) =====================
//
// Widened 2026-08-16 from AAC/MP3-only to every audio format this host's
// AudioToolbox reports it can decode (measured, not assumed -- see
// docs/audiotoolbox-codec-widening_2026-08-16.md for the full
// kAudioFormatProperty_DecodeFormatIDs list and per-format citations) plus
// Vorbis via a vendored software decoder (audio_vorbis_decode.mm), which
// AudioToolbox does not support at all.
//
// formatId is the contract with MediaCodec.java's configure(), which maps
// MediaFormat.KEY_MIME to this integer before calling NativeMedia.
// nativeDecOpen (MediaCodec.java is owned by a different lane; see the
// report for the exact mime -> formatId table it needs to grow into):
//   1  audio/mp4a-latm  AAC LC/HE/HEv2/LD/ELD, self-selected by the
//                       AudioSpecificConfig in the magic cookie   -- existing
//   2  audio/mpeg       MP3                                       -- existing
//   3  audio/opus       kAudioFormatOpus
//   4  audio/flac       kAudioFormatFLAC
//   5  audio/alac       kAudioFormatAppleLossless
//   6  audio/ac3        kAudioFormatAC3
//   7  audio/eac3       kAudioFormatEnhancedAC3
//   8  audio/3gpp       kAudioFormatAMR     (AMR-NB, fixed 8 kHz mono)
//   9  audio/amr-wb     kAudioFormatAMR_WB  (fixed 16 kHz mono)
//   10 audio/g711-alaw  kAudioFormatALaw
//   11 audio/g711-mlaw  kAudioFormatULaw
//   12 audio/raw        PCM passthrough -- bypasses AudioConverter entirely
//   13 audio/vorbis     NOT an AudioConverter format -- see
//                       audio_vorbis_decode.mm for why and how

}  // extern "C"

namespace {

// Sentinel OSStatus the input proc returns when the FIFO is empty mid-stream
// (not EOS): "no data right now", keeps the converter alive (vs noErr+0=EOS).
static const OSStatus kMacApkNoDataNow = (OSStatus)0x6d747279;  // 'mtry'

struct Decoder {
    AudioConverterRef conv = nullptr;
    int sampleRate = 0, channels = 0, formatId = 0;
    // Input FIFO of compressed access units fed via macapk_md_dec_decode. The
    // input callback pulls from here; an empty FIFO that is NOT at EOS returns
    // 0 packets for THIS fill (the converter retains its state for the next
    // call -- standard streaming-decode pattern).
    std::deque<std::vector<uint8_t>> fifo;
    // The access unit currently handed to the converter. It MUST stay alive
    // until the input proc is called AGAIN (Apple: "do not free or alter this
    // buffer until called again") -- the converter reads it after the proc
    // returns. So we keep it here instead of popping inside the proc.
    std::vector<uint8_t> inflight;
    AudioStreamPacketDescription curDesc = {};
    bool eos = false;

    // Byte-constant-bitrate input formats (ALaw/ULaw): the fixed byte size
    // of one decoder packet, so md_input_proc can let AudioConverter compute
    // the packet count itself instead of asserting "this whole access unit
    // is exactly one packet" -- which would be wrong the moment an AU is
    // more than one sample long (Apple's docs: CBR formats do not need
    // explicit packet descriptions). 0 = not CBR, use the existing
    // whole-AU-is-one-packet path every other format here relies on.
    int cbrBytesPerPacket = 0;

    // Non-AudioConverter engines dispatch through these instead of `conv`;
    // exactly one of {conv, vorbisEngine, rawPassthrough} is live per
    // instance (see macapk_md_dec_decode/out_sample_rate/out_channels/close).
    void* vorbisEngine = nullptr;   // formatId==13: an audio_vorbis_dec_* handle
    bool  rawPassthrough = false;   // formatId==12: audio/raw, memcpy through

    // Opus only: samples still to discard from the FRONT of decoder output
    // (RFC 6716 sec 2 / RFC 7845 sec 4.2 pre-skip). Parsed directly from the
    // raw OpusHead (csd-0 bytes[10..11], LE u16) at open time -- see
    // macapk_md_dec_open. The header always carries this value, so Opus
    // decodes correctly with ZERO MediaCodec.java change beyond the mime ->
    // formatId table entry; AOSP's csd-1 override is not honored (rare in
    // practice -- see the report).
    int opusPreSkipRemaining = 0;
};

// Input proc: hand the converter ONE access unit from the FIFO per call; set
// *ioNumberDataPackets=0 when the FIFO is empty (end of input for this fill).
static OSStatus md_input_proc(AudioConverterRef, UInt32* ioNumberDataPackets,
                              AudioBufferList* ioData,
                              AudioStreamPacketDescription* __nullable* __nullable outPktDesc,
                              void* inUserData) {
    Decoder* dec = (Decoder*)inUserData;
    if (dec->fifo.empty()) {
        *ioNumberDataPackets = 0;
        if (outPktDesc) *outPktDesc = nullptr;
        // FIFO empty: at real EOS return noErr+0 packets (the converter then
        // flushes + ends). MID-STREAM (more packets coming later) we must NOT
        // return noErr+0 -- that permanently EOSes the converter. Returning a
        // non-zero status signals "no data right now"; FillComplexBuffer returns
        // it with the partial output, and the converter stays usable for the
        // next call (standard streaming-decode pattern).
        return dec->eos ? noErr : (OSStatus)kMacApkNoDataNow;
    }
    // Move the front packet into `inflight` so it stays alive after we return
    // (the converter reads mData post-return). The PREVIOUS inflight is freed
    // here, which is safe: this proc is only re-entered once the converter has
    // finished with the packet handed out last time.
    dec->inflight = std::move(dec->fifo.front());
    dec->fifo.pop_front();
    ioData->mNumberBuffers = 1;
    ioData->mBuffers[0].mNumberChannels = 0;       // ignored for compressed input
    ioData->mBuffers[0].mDataByteSize = (UInt32)dec->inflight.size();
    ioData->mBuffers[0].mData = dec->inflight.data();
    if (dec->cbrBytesPerPacket > 0) {
        if (outPktDesc) *outPktDesc = nullptr;
        *ioNumberDataPackets = (UInt32)(dec->inflight.size() / (size_t)dec->cbrBytesPerPacket);
    } else {
        if (outPktDesc) {
            dec->curDesc.mStartOffset = 0;
            dec->curDesc.mVariableFramesInPacket = 0;   // whole AU == one packet
            dec->curDesc.mDataByteSize = (UInt32)dec->inflight.size();
            *outPktDesc = &dec->curDesc;
        }
        *ioNumberDataPackets = 1;
    }
    return noErr;
}

// For formatId==13 (Vorbis) ONLY: MediaCodec.java packs csd-0 (identification
// header) and csd-1 (setup header) into one blob as
// [u32 LE len0][bytes0][u32 LE len1][bytes1] before calling nativeDecOpen,
// since Vorbis needs both headers and the existing native method keeps its
// signature (one byte[]) unchanged. Every other formatId keeps today's
// plain, unprefixed csd0 passthrough -- see the report for the exact
// MediaCodec.java diff (this file does not own that source).
static bool parse_vorbis_combined_csd(const uint8_t* blob, int blobLen,
                                      std::vector<uint8_t>* idHdr,
                                      std::vector<uint8_t>* setupHdr) {
    if (!blob || blobLen < 4) return false;
    auto rd32 = [](const uint8_t* p) -> uint32_t {
        return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    };
    int off = 0;
    uint32_t len0 = rd32(blob + off); off += 4;
    if (off + (int)len0 > blobLen) return false;
    idHdr->assign(blob + off, blob + off + len0); off += (int)len0;
    if (off + 4 > blobLen) return false;
    uint32_t len1 = rd32(blob + off); off += 4;
    if (off + (int)len1 > blobLen) return false;
    setupHdr->assign(blob + off, blob + off + len1);
    return true;
}

// ===================== AudioEncoder helpers =====================

static void append_mp4_desc_len(std::vector<uint8_t>& out, size_t len) {
    uint8_t b[4];
    int n = 0;
    do {
        b[n++] = (uint8_t)(len & 0x7F);
        len >>= 7;
    } while (len > 0 && n < 4);
    for (int i = n - 1; i >= 0; i--) {
        out.push_back((uint8_t)(b[i] | (i > 0 ? 0x80 : 0x00)));
    }
}

// Wrap a bare MPEG-4 AudioSpecificConfig (typically 2 bytes for AAC-LC) inside
// an ISO/IEC 14496-1 ES_Descriptor so AudioConverterSetProperty(
// kAudioConverterDecompressionMagicCookie) accepts AOSP csd-0 directly.
static std::vector<uint8_t> wrap_asc_to_esds(const uint8_t* asc, size_t ascLen) {
    std::vector<uint8_t> desc05;
    desc05.push_back(0x05);
    append_mp4_desc_len(desc05, ascLen);
    desc05.insert(desc05.end(), asc, asc + ascLen);

    std::vector<uint8_t> desc04Body = {
        0x40,                   // MPEG-4 Audio
        0x15,                   // AudioStream (5 << 2 | 1)
        0x00, 0x00, 0x00,       // bufferSizeDB
        0x00, 0x00, 0x00, 0x00, // maxBitrate
        0x00, 0x00, 0x00, 0x00, // avgBitrate
    };
    desc04Body.insert(desc04Body.end(), desc05.begin(), desc05.end());

    std::vector<uint8_t> desc04;
    desc04.push_back(0x04);
    append_mp4_desc_len(desc04, desc04Body.size());
    desc04.insert(desc04.end(), desc04Body.begin(), desc04Body.end());

    std::vector<uint8_t> desc06 = { 0x06, 0x01, 0x02 };

    std::vector<uint8_t> desc03Body = { 0x00, 0x00, 0x00 }; // ES_ID=0, flags=0
    desc03Body.insert(desc03Body.end(), desc04.begin(), desc04.end());
    desc03Body.insert(desc03Body.end(), desc06.begin(), desc06.end());

    std::vector<uint8_t> desc03;
    desc03.push_back(0x03);
    append_mp4_desc_len(desc03, desc03Body.size());
    desc03.insert(desc03.end(), desc03Body.begin(), desc03Body.end());
    return desc03;
}

static bool read_mp4_desc_len(const uint8_t* d, size_t n, size_t& ioOff, size_t& outLen) {
    size_t val = 0;
    for (int i = 0; i < 4; i++) {
        if (ioOff >= n) return false;
        uint8_t b = d[ioOff++];
        val = (val << 7) | (size_t)(b & 0x7F);
        if ((b & 0x80) == 0) {
            outLen = val;
            return true;
        }
    }
    return false;
}

// Extract the raw AudioSpecificConfig bytes (tag 0x05 payload) from an
// AudioToolbox ES_Descriptor magic cookie. Returns synthetic 2-byte AAC-LC ASC
// if parsing fails.
static std::vector<uint8_t> extract_asc_from_magic_cookie(const uint8_t* cookie, size_t len,
                                                          int sampleRate, int channels) {
    if (cookie && len >= 2 && cookie[0] != 0x03 && len <= 16) {
        return std::vector<uint8_t>(cookie, cookie + len);
    }
    if (cookie && len >= 8 && cookie[0] == 0x03) {
        size_t off = 1;
        size_t esLen = 0;
        if (read_mp4_desc_len(cookie, len, off, esLen)) {
            size_t esEnd = std::min(len, off + esLen);
            if (off + 3 <= esEnd) {
                uint8_t flags = cookie[off + 2];
                off += 3;
                if ((flags & 0x80) && off + 2 <= esEnd) off += 2; // dependsOn_ES_ID
                if ((flags & 0x40) && off < esEnd) {             // URLstring
                    uint8_t ulen = cookie[off++];
                    off += std::min<size_t>(ulen, esEnd - off);
                }
                if ((flags & 0x20) && off + 2 <= esEnd) off += 2; // OCR_ES_ID
                while (off < esEnd) {
                    uint8_t tag = cookie[off++];
                    size_t dlen = 0;
                    if (!read_mp4_desc_len(cookie, esEnd, off, dlen)) break;
                    size_t dend = std::min(esEnd, off + dlen);
                    if (tag == 0x05 && dend > off) {
                        return std::vector<uint8_t>(cookie + off, cookie + dend);
                    }
                    if (tag == 0x04 && off + 13 <= dend) {
                        size_t sub = off + 13;
                        while (sub < dend) {
                            uint8_t stag = cookie[sub++];
                            size_t slen = 0;
                            if (!read_mp4_desc_len(cookie, dend, sub, slen)) break;
                            size_t send = std::min(dend, sub + slen);
                            if (stag == 0x05 && send > sub) {
                                return std::vector<uint8_t>(cookie + sub, cookie + send);
                            }
                            sub = send;
                        }
                    }
                    off = dend;
                }
            }
        }
    }
    static const int kRates[13] = {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    };
    int srIdx = 4;
    for (int i = 0; i < 13; i++) {
        if (kRates[i] == sampleRate) { srIdx = i; break; }
    }
    int aot = 2; // AAC-LC
    uint8_t b0 = (uint8_t)((aot << 3) | (srIdx >> 1));
    uint8_t b1 = (uint8_t)(((srIdx & 1) << 7) | ((channels & 0x0F) << 3));
    return { b0, b1 };
}

constexpr int kAudioEncFlagEndOfStream = 4; // MediaCodec.BUFFER_FLAG_END_OF_STREAM

struct AudioEncoder {
    std::mutex mu;
    AudioConverterRef conv = nullptr;
    int formatId = 1;
    int sampleRate = 0;
    int channels = 0;
    int frameBytes = 0;
    UInt32 maxPacketBytes = 4096;
    std::vector<uint8_t> csd0;

    std::vector<uint8_t> pcmBuf;
    size_t pcmReadOff = 0;
    std::vector<uint8_t> partialBytes;
    std::vector<uint8_t> inflight;

    struct PtsLandmark {
        int64_t sampleIndex;
        int64_t ptsUs;
    };
    std::vector<PtsLandmark> landmarks;
    int64_t pushedSamples = 0;
    int64_t encodedSamples = 0;

    bool eosSeen = false;
    bool eosEmitted = false;

    struct OutPacket {
        std::vector<uint8_t> data;
        int64_t ptsUs;
        int flags;
    };
    std::deque<OutPacket> outQ;

    int64_t lookupPtsUs(int64_t sampleIdx) const {
        if (landmarks.empty() || sampleRate <= 0) return 0;
        const PtsLandmark* lm = &landmarks.front();
        for (size_t i = 1; i < landmarks.size(); i++) {
            if (landmarks[i].sampleIndex <= sampleIdx) lm = &landmarks[i];
            else break;
        }
        int64_t delta = sampleIdx - lm->sampleIndex;
        return lm->ptsUs + (int64_t)llround((double)delta * 1.0e6 / (double)sampleRate);
    }
};

static OSStatus enc_input_proc(AudioConverterRef, UInt32* ioNumberDataPackets,
                               AudioBufferList* ioData,
                               AudioStreamPacketDescription* __nullable* __nullable outPktDesc,
                               void* inUserData) {
    AudioEncoder* enc = (AudioEncoder*)inUserData;
    if (outPktDesc) *outPktDesc = nullptr;
    size_t availBytes = enc->pcmBuf.size() - enc->pcmReadOff;
    size_t availFrames = enc->frameBytes > 0 ? (availBytes / (size_t)enc->frameBytes) : 0;
    if (availFrames == 0) {
        *ioNumberDataPackets = 0;
        ioData->mNumberBuffers = 1;
        ioData->mBuffers[0].mNumberChannels = (UInt32)enc->channels;
        ioData->mBuffers[0].mDataByteSize = 0;
        ioData->mBuffers[0].mData = nullptr;
        return enc->eosSeen ? noErr : kMacApkNoDataNow;
    }
    UInt32 want = *ioNumberDataPackets;
    UInt32 takeFrames = (want > 0 && availFrames > want) ? want : (UInt32)availFrames;
    size_t takeBytes = (size_t)takeFrames * (size_t)enc->frameBytes;
    enc->inflight.assign(enc->pcmBuf.begin() + enc->pcmReadOff,
                         enc->pcmBuf.begin() + enc->pcmReadOff + takeBytes);
    enc->pcmReadOff += takeBytes;
    if (enc->pcmReadOff == enc->pcmBuf.size()) {
        enc->pcmBuf.clear();
        enc->pcmReadOff = 0;
    } else if (enc->pcmReadOff > 65536) {
        enc->pcmBuf.erase(enc->pcmBuf.begin(), enc->pcmBuf.begin() + enc->pcmReadOff);
        enc->pcmReadOff = 0;
    }
    ioData->mNumberBuffers = 1;
    ioData->mBuffers[0].mNumberChannels = (UInt32)enc->channels;
    ioData->mBuffers[0].mDataByteSize = (UInt32)enc->inflight.size();
    ioData->mBuffers[0].mData = enc->inflight.data();
    *ioNumberDataPackets = takeFrames;
    return noErr;
}

}  // namespace

// audio_vorbis_decode.mm (same executable target, see runtime/CMakeLists.txt;
// forward-declared rather than a header, matching this file's own style for
// the vendored decoders -- audio_backend.mm does the same for
// stb_vorbis_decode_memory).
extern "C" {
void* macapk_vorbis_dec_open(const void* idHdr, int idLen, const void* setupHdr,
                             int setupLen, int* outSampleRate, int* outChannels);
int   macapk_vorbis_dec_decode(void* h, const void* au, int auLen, int eos, void* out, int cap);
int   macapk_vorbis_dec_out_sample_rate(void* h);
int   macapk_vorbis_dec_out_channels(void* h);
void  macapk_vorbis_dec_close(void* h);
}

extern "C" {

void* macapk_md_dec_open(int formatId, int sampleRate, int channels,
                         const void* csd0, int csd0Len) {
    if (sampleRate <= 0 || channels <= 0) return nullptr;

    // ---- Vorbis: not an AudioConverter format at all (macOS AudioToolbox
    // has no public Vorbis decoder -- see audio_vorbis_decode.mm). ----
    if (formatId == 13) {
        std::vector<uint8_t> idHdr, setupHdr;
        if (!parse_vorbis_combined_csd((const uint8_t*)csd0, csd0Len, &idHdr, &setupHdr)) {
            std::fprintf(stderr, "[media] vorbis: malformed combined csd (len=%d)\n", csd0Len);
            return nullptr;
        }
        int outSr = 0, outCh = 0;
        void* v = macapk_vorbis_dec_open(idHdr.data(), (int)idHdr.size(),
                                         setupHdr.data(), (int)setupHdr.size(),
                                         &outSr, &outCh);
        if (!v) return nullptr;
        Decoder* dec = new Decoder();
        dec->vorbisEngine = v;
        dec->formatId = formatId;
        dec->sampleRate = outSr;
        dec->channels = outCh;
        return dec;
    }

    // ---- audio/raw: already PCM, no converter needed. ----
    if (formatId == 12) {
        Decoder* dec = new Decoder();
        dec->rawPassthrough = true;
        dec->formatId = formatId;
        dec->sampleRate = sampleRate;
        dec->channels = channels;
        return dec;
    }

    AudioStreamBasicDescription in = {};
    in.mSampleRate = sampleRate;
    in.mChannelsPerFrame = channels;
    bool wantsCookie = false;
    int cbrBpp = 0;

    switch (formatId) {
        case 2:  // audio/mpeg (MP3)
            in.mFormatID = kAudioFormatMPEGLayer3; in.mFramesPerPacket = 1152;
            break;
        case 3:  // audio/opus -- always operates at 48 kHz internally
                 // (RFC 6716 sec 2); the ID header's own sample-rate field
                 // is informational only per that same spec, so the caller's
                 // declared rate is intentionally NOT used here.
            in.mFormatID = kAudioFormatOpus; in.mSampleRate = 48000; in.mFramesPerPacket = 0;
            break;
        case 4:  // audio/flac -- see flacCookie below for why this is NOT a
                 // verbatim csd-0 passthrough like AAC/ALAC.
            in.mFormatID = kAudioFormatFLAC; wantsCookie = true;
            // AudioConverterNew for kAudioFormatFLAC fails outright (generic
            // 'what'/kAudioConverterErr_UnspecifiedError) unless
            // mFramesPerPacket is the stream's real block size -- unlike
            // every other compressed format here, FLAC does NOT tolerate 0
            // (VBR/unknown) even though md_input_proc already supplies a
            // correct per-AU packet description generically. Confirmed by
            // opening the same file through AudioFile (kAudioFilePropertyDataFormat)
            // as an oracle: it reports mFramesPerPacket=4608 for a stream
            // whose STREAMINFO min_blocksize is also 4608 -- so read it
            // straight out of csd-0 (FLAC spec: min_blocksize is the first
            // 16 bits of STREAMINFO, i.e. csd-0 bytes[8..9] once the 4-byte
            // "fLaC" marker and 4-byte metadata-block header are skipped).
            if (csd0 && csd0Len >= 10) {
                const uint8_t* h3 = (const uint8_t*)csd0;
                in.mFramesPerPacket = ((UInt32)h3[8] << 8) | (UInt32)h3[9];
            }
            break;
        case 5:  // audio/alac -- cookie = csd-0 verbatim (24- or 48-byte
                 // ALACSpecificConfig[+ALACChannelLayoutInfo]; Apple's own
                 // ALACMagicCookieDescription.txt, macosforge/alac, "the
                 // magic cookie is opaque and should be presented to the
                 // decoder exactly as vended by the encoder").
            in.mFormatID = kAudioFormatAppleLossless; in.mFramesPerPacket = 4096; wantsCookie = true;
            break;
        case 6:  // audio/ac3 -- self-describing sync frames (ATSC A/52
                 // bsi header), no cookie.
            in.mFormatID = kAudioFormatAC3; in.mFramesPerPacket = 1536;
            break;
        case 7:  // audio/eac3 -- frame size varies with sub-block count;
                 // rely on the per-AU packet description md_input_proc
                 // already supplies generically, same as FLAC/Opus.
            in.mFormatID = kAudioFormatEnhancedAC3; in.mFramesPerPacket = 0;
            break;
        case 8:  // audio/3gpp (AMR-NB) -- spec-fixed 8 kHz mono, 20 ms/frame
                 // (3GPP TS 26.101); one Android AU is one frame, matching
                 // the generic per-AU-is-one-packet path (frame BYTE size
                 // still varies with the codec mode, so this is not the
                 // byte-CBR case below).
            in.mFormatID = kAudioFormatAMR; in.mSampleRate = 8000; in.mChannelsPerFrame = 1;
            in.mFramesPerPacket = 160;
            break;
        case 9:  // audio/amr-wb -- spec-fixed 16 kHz mono, 20 ms/frame
                 // (3GPP TS 26.201).
            in.mFormatID = kAudioFormatAMR_WB; in.mSampleRate = 16000; in.mChannelsPerFrame = 1;
            in.mFramesPerPacket = 320;
            break;
        case 10: // audio/g711-alaw -- true byte-CBR: 1 byte/channel/frame.
            in.mFormatID = kAudioFormatALaw; in.mFramesPerPacket = 1;
            in.mBytesPerPacket = (UInt32)channels; cbrBpp = channels;
            break;
        case 11: // audio/g711-mlaw
            in.mFormatID = kAudioFormatULaw; in.mFramesPerPacket = 1;
            in.mBytesPerPacket = (UInt32)channels; cbrBpp = channels;
            break;
        case 1:  // audio/mp4a-latm (AAC) -- the converter self-selects
                 // LC/HE/HEv2/LD/ELD from the AudioSpecificConfig in the
                 // magic cookie.
            in.mFormatID = kAudioFormatMPEG4AAC; in.mFramesPerPacket = 1024; wantsCookie = true;
            break;
        default:
            std::fprintf(stderr, "[media] dec_open: unsupported formatId=%d\n", formatId);
            return nullptr;
    }
    // Every other per-packet byte/frame field stays 0 (VBR, unknown);
    // md_input_proc already supplies a correct per-AU
    // AudioStreamPacketDescription generically, which is all any of the
    // non-CBR formats above need (this is exactly the pattern the existing
    // AAC/MP3 code already relied on -- it generalizes as-is).

    AudioStreamBasicDescription out = {};
    out.mSampleRate = in.mSampleRate;            // the (possibly spec-
                                                  // corrected) input rate
                                                  // above, never the raw
                                                  // caller-supplied one.
    out.mFormatID = kAudioFormatLinearPCM;
    out.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    out.mBitsPerChannel = 16;
    out.mChannelsPerFrame = in.mChannelsPerFrame;
    out.mFramesPerPacket = 1;
    out.mBytesPerFrame = 2 * out.mChannelsPerFrame;
    out.mBytesPerPacket = out.mBytesPerFrame;

    AudioConverterRef conv = nullptr;
    OSStatus s = AudioConverterNew(&in, &out, &conv);
    if (s != noErr || !conv) {
        std::fprintf(stderr, "[media] AudioConverterNew failed for formatId=%d: %d\n",
                     formatId, (int)s);
        return nullptr;
    }
    // Magic cookie. AAC (AudioSpecificConfig) and ALAC (ALACSpecificConfig)
    // take csd-0 verbatim -- Android's csd-0 IS the exact byte layout
    // AudioToolbox's magic-cookie property expects for those two, matching
    // how the existing AAC path already worked. MP3/Opus/AC3/EAC3/AMR/ALaw/
    // ULaw take no cookie at all (self-describing bitstream or fixed ASBD).
    //
    // FLAC is the one exception: Android's csd-0 is "fLaC"+STREAMINFO (the
    // raw on-disk .flac file header shape, per AOSP's own CSD table), but
    // AudioConverterSetProperty(kAudioConverterDecompressionMagicCookie)
    // for kAudioFormatFLAC wants Apple's ISOBMFF-derived "dfLa" box shape
    // instead: a 4-byte big-endian total-size prefix, the 4-byte 'dfLa'
    // fourcc, a 4-byte ISO fullbox version/flags (0), then the SAME
    // metadata-block-header+STREAMINFO bytes csd-0 already carries after
    // its own 4-byte "fLaC" marker. Confirmed by opening the same file
    // through AudioFile and reading kAudioFilePropertyMagicCookieData as an
    // oracle: the bytes it returns are byte-for-byte this transform of the
    // csd-0 Android supplies, and AudioConverterSetProperty accepts it
    // (rejects the untransformed csd-0 with a hard AudioConverterNew
    // failure before we would even reach this call).
    std::vector<uint8_t> flacCookie;
    const void* cookiePtr = csd0;
    int cookieLen = csd0Len;
    if (formatId == 1 && csd0 && csd0Len >= 2 && csd0Len < 16 &&
            ((const uint8_t*)csd0)[0] != 0x03) {
        flacCookie = wrap_asc_to_esds((const uint8_t*)csd0, (size_t)csd0Len);
        cookiePtr = flacCookie.data();
        cookieLen = (int)flacCookie.size();
    } else if (formatId == 4 && csd0 && csd0Len > 4) {
        const uint8_t* c = (const uint8_t*)csd0;
        uint32_t rest = (uint32_t)(csd0Len - 4);      // everything after "fLaC"
        uint32_t total = 4 + 4 + 4 + rest;
        flacCookie.push_back((uint8_t)(total >> 24)); flacCookie.push_back((uint8_t)(total >> 16));
        flacCookie.push_back((uint8_t)(total >> 8));  flacCookie.push_back((uint8_t)total);
        flacCookie.push_back('d'); flacCookie.push_back('f'); flacCookie.push_back('L'); flacCookie.push_back('a');
        flacCookie.push_back(0); flacCookie.push_back(0); flacCookie.push_back(0); flacCookie.push_back(0);
        flacCookie.insert(flacCookie.end(), c + 4, c + csd0Len);
        cookiePtr = flacCookie.data();
        cookieLen = (int)flacCookie.size();
    }
    if (wantsCookie && cookiePtr && cookieLen > 0) {
        s = AudioConverterSetProperty(conv, kAudioConverterDecompressionMagicCookie,
                                      (UInt32)cookieLen, cookiePtr);
        if (s != noErr)
            std::fprintf(stderr, "[media] set magic cookie failed (formatId=%d): %d (continuing)\n",
                         formatId, (int)s);
    }
    Decoder* dec = new Decoder();
    dec->conv = conv;
    dec->sampleRate = (int)out.mSampleRate;
    dec->channels = (int)out.mChannelsPerFrame;
    dec->formatId = formatId;
    dec->cbrBytesPerPacket = cbrBpp;

    if (formatId == 3 && csd0 && csd0Len >= 12) {
        const uint8_t* opusHdr = (const uint8_t*)csd0;
        dec->opusPreSkipRemaining = (int)opusHdr[10] | ((int)opusHdr[11] << 8);
    }
    return dec;
}

// Feed ONE compressed access unit (au/auLen; may be empty when eos) and drain
// the PCM the converter produces, into out(cap). Returns PCM bytes written
// (>=0; may be 0 due to decoder priming), or -1 on a hard error.
int macapk_md_dec_decode(void* h, const void* au, int auLen, int eos, void* out, int cap) {
    Decoder* dec = (Decoder*)h;
    if (!dec) return -1;

    if (dec->vorbisEngine)
        return macapk_vorbis_dec_decode(dec->vorbisEngine, au, auLen, eos, out, cap);

    if (dec->rawPassthrough) {
        // audio/raw: the "compressed" access unit already IS S16LE PCM.
        if (!au || auLen <= 0) return 0;
        int n = auLen < cap ? auLen : cap;
        std::memcpy(out, au, (size_t)n);
        return n;
    }

    if (!dec->conv) return -1;
    if (au && auLen > 0)
        dec->fifo.emplace_back((const uint8_t*)au, (const uint8_t*)au + auLen);
    if (eos) dec->eos = true;

    int16_t* dst = (int16_t*)out;
    int capFrames = cap / (int)(2 * dec->channels);
    int producedFrames = 0;

    // Pull until the converter stops producing (FIFO drained for this call).
    while (producedFrames < capFrames) {
        UInt32 want = (UInt32)(capFrames - producedFrames);
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = dec->channels;
        abl.mBuffers[0].mData = dst + (size_t)producedFrames * dec->channels;
        abl.mBuffers[0].mDataByteSize = want * (UInt32)(2 * dec->channels);  // reset EVERY call
        UInt32 gotPackets = want;  // PCM: 1 packet == 1 frame
        OSStatus s = AudioConverterFillComplexBuffer(dec->conv, md_input_proc, dec, &gotPackets, &abl, nullptr);
        producedFrames += (int)gotPackets;
        if (s == kMacApkNoDataNow) break;   // input drained mid-stream; converter still alive
        if (s != noErr && s != kAudioFileEndOfFileError) break;  // real error
        if (gotPackets == 0) break;         // EOS fully drained
    }

    // Opus pre-skip trim: drop leading encoder-priming samples from the
    // FRONT of decoder output, across as many calls as it takes (pre_skip
    // is typically a few hundred samples, well under one decoded frame).
    if (dec->opusPreSkipRemaining > 0 && producedFrames > 0) {
        int drop = dec->opusPreSkipRemaining < producedFrames ? dec->opusPreSkipRemaining : producedFrames;
        int keep = producedFrames - drop;
        if (keep > 0)
            std::memmove(dst, dst + (size_t)drop * dec->channels,
                        (size_t)keep * dec->channels * sizeof(int16_t));
        producedFrames = keep;
        dec->opusPreSkipRemaining -= drop;
    }
    return producedFrames * (int)(2 * dec->channels);
}

int macapk_md_dec_out_sample_rate(void* h) {
    Decoder* d = (Decoder*)h;
    if (!d) return 0;
    if (d->vorbisEngine) return macapk_vorbis_dec_out_sample_rate(d->vorbisEngine);
    return d->sampleRate;
}
int macapk_md_dec_out_channels(void* h) {
    Decoder* d = (Decoder*)h;
    if (!d) return 0;
    if (d->vorbisEngine) return macapk_vorbis_dec_out_channels(d->vorbisEngine);
    return d->channels;
}
void macapk_md_dec_close(void* h) {
    Decoder* d = (Decoder*)h;
    if (!d) return;
    if (d->vorbisEngine) macapk_vorbis_dec_close(d->vorbisEngine);
    if (d->conv) AudioConverterDispose(d->conv);
    delete d;
}

// ===================== AudioEncoder (AudioToolbox AAC-LC) =====================

void* macapk_md_enc_open(int formatId, int sampleRate, int channels, int bitrate) {
    if (formatId != 1 || sampleRate <= 0 || channels < 1 || channels > 8) {
        std::fprintf(stderr, "[media] enc_open: invalid args formatId=%d sr=%d ch=%d\n",
                     formatId, sampleRate, channels);
        return nullptr;
    }

    AudioStreamBasicDescription in = {};
    in.mSampleRate = (Float64)sampleRate;
    in.mFormatID = kAudioFormatLinearPCM;
    in.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    in.mBitsPerChannel = 16;
    in.mChannelsPerFrame = (UInt32)channels;
    in.mFramesPerPacket = 1;
    in.mBytesPerFrame = (UInt32)(2 * channels);
    in.mBytesPerPacket = in.mBytesPerFrame;

    AudioStreamBasicDescription out = {};
    out.mSampleRate = (Float64)sampleRate;
    out.mFormatID = kAudioFormatMPEG4AAC;
    out.mFormatFlags = 0; // AAC-LC
    out.mChannelsPerFrame = (UInt32)channels;
    out.mFramesPerPacket = 1024;

    AudioConverterRef conv = nullptr;
    OSStatus s = AudioConverterNew(&in, &out, &conv);
    if (s != noErr || !conv) {
        std::fprintf(stderr, "[media] enc_open: AudioConverterNew AAC failed (%d) sr=%d ch=%d\n",
                     (int)s, sampleRate, channels);
        return nullptr;
    }

    UInt32 reqBitrate = (bitrate > 0) ? (UInt32)bitrate : (UInt32)(channels >= 2 ? 128000 : 64000);
    s = AudioConverterSetProperty(conv, kAudioConverterEncodeBitRate, sizeof(reqBitrate), &reqBitrate);
    if (s != noErr) {
        UInt32 propSz = 0;
        if (AudioConverterGetPropertyInfo(conv, kAudioConverterApplicableEncodeBitRates, &propSz, nullptr) == noErr
                && propSz >= sizeof(AudioValueRange)) {
            std::vector<AudioValueRange> ranges(propSz / sizeof(AudioValueRange));
            if (AudioConverterGetProperty(conv, kAudioConverterApplicableEncodeBitRates, &propSz, ranges.data()) == noErr
                    && !ranges.empty()) {
                UInt32 best = reqBitrate;
                double bestDiff = 1.0e18;
                for (const auto& r : ranges) {
                    double cand = std::max(r.mMinimum, std::min(r.mMaximum, (double)reqBitrate));
                    double diff = std::abs(cand - (double)reqBitrate);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = (UInt32)llround(cand);
                    }
                }
                AudioConverterSetProperty(conv, kAudioConverterEncodeBitRate, sizeof(best), &best);
            }
        }
    }

    UInt32 maxPkt = 4096;
    UInt32 maxPktSz = sizeof(maxPkt);
    if (AudioConverterGetProperty(conv, kAudioConverterPropertyMaximumOutputPacketSize, &maxPktSz, &maxPkt) != noErr
            || maxPkt < 1024) {
        maxPkt = 4096;
    }

    std::vector<uint8_t> cookie;
    UInt32 cookieSz = 0;
    Boolean writable = false;
    if (AudioConverterGetPropertyInfo(conv, kAudioConverterCompressionMagicCookie, &cookieSz, &writable) == noErr
            && cookieSz > 0) {
        cookie.resize(cookieSz);
        if (AudioConverterGetProperty(conv, kAudioConverterCompressionMagicCookie, &cookieSz, cookie.data()) == noErr) {
            cookie.resize(cookieSz);
        } else {
            cookie.clear();
        }
    }

    AudioEncoder* enc = new AudioEncoder();
    enc->conv = conv;
    enc->formatId = formatId;
    enc->sampleRate = sampleRate;
    enc->channels = channels;
    enc->frameBytes = 2 * channels;
    enc->maxPacketBytes = maxPkt;
    enc->csd0 = extract_asc_from_magic_cookie(
        cookie.empty() ? nullptr : cookie.data(), cookie.size(), sampleRate, channels);
    return enc;
}

int macapk_md_enc_csd0_size(void* h) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc) return 0;
    std::lock_guard<std::mutex> lk(enc->mu);
    return (int)enc->csd0.size();
}

int macapk_md_enc_csd0(void* h, void* out, int cap) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc || !out || cap <= 0) return 0;
    std::lock_guard<std::mutex> lk(enc->mu);
    int n = (int)enc->csd0.size() < cap ? (int)enc->csd0.size() : cap;
    if (n > 0) std::memcpy(out, enc->csd0.data(), (size_t)n);
    return n;
}

int macapk_md_enc_encode(void* h, const uint8_t* pcm, int offset, int length, int64_t ptsUs, int eos) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc || !enc->conv) return -1;
    std::lock_guard<std::mutex> lk(enc->mu);

    if (pcm && length > 0 && offset >= 0) {
        if (enc->eosSeen) {
            // Post-EOS feed ignored until flush().
            return 0;
        }
        int64_t startSampleIdx = enc->pushedSamples;
        if (enc->landmarks.empty()) {
            enc->landmarks.push_back({ startSampleIdx, ptsUs });
        } else {
            int64_t expectedPts = enc->lookupPtsUs(startSampleIdx);
            if (std::abs(ptsUs - expectedPts) > 50000) {
                enc->landmarks.push_back({ startSampleIdx, ptsUs });
            }
        }
        enc->partialBytes.insert(enc->partialBytes.end(),
                                 pcm + offset, pcm + offset + length);
        size_t fullFrames = enc->partialBytes.size() / (size_t)enc->frameBytes;
        size_t fullBytes = fullFrames * (size_t)enc->frameBytes;
        if (fullBytes > 0) {
            enc->pcmBuf.insert(enc->pcmBuf.end(),
                               enc->partialBytes.begin(),
                               enc->partialBytes.begin() + fullBytes);
            enc->partialBytes.erase(enc->partialBytes.begin(),
                                    enc->partialBytes.begin() + fullBytes);
            enc->pushedSamples += (int64_t)fullFrames;
        }
    }

    if (eos) {
        if (!enc->partialBytes.empty()) {
            enc->pcmBuf.insert(enc->pcmBuf.end(),
                               enc->partialBytes.begin(), enc->partialBytes.end());
            size_t rem = enc->frameBytes - enc->partialBytes.size();
            enc->pcmBuf.insert(enc->pcmBuf.end(), rem, 0);
            enc->partialBytes.clear();
            enc->pushedSamples += 1;
        }
        enc->eosSeen = true;
    }

    while (true) {
        std::vector<uint8_t> pktBuf(enc->maxPacketBytes);
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = (UInt32)enc->channels;
        abl.mBuffers[0].mDataByteSize = (UInt32)pktBuf.size();
        abl.mBuffers[0].mData = pktBuf.data();
        UInt32 ioPackets = 1;
        AudioStreamPacketDescription outDesc = {};
        OSStatus s = AudioConverterFillComplexBuffer(
            enc->conv, enc_input_proc, enc, &ioPackets, &abl, &outDesc);

        if (ioPackets > 0 && abl.mBuffers[0].mDataByteSize > 0) {
            uint32_t offBytes = outDesc.mStartOffset;
            uint32_t szBytes = outDesc.mDataByteSize > 0
                ? outDesc.mDataByteSize : abl.mBuffers[0].mDataByteSize;
            if ((size_t)offBytes + (size_t)szBytes <= pktBuf.size() && szBytes > 0) {
                std::vector<uint8_t> au(pktBuf.begin() + offBytes,
                                        pktBuf.begin() + offBytes + szBytes);
                int64_t pktPts = enc->lookupPtsUs(enc->encodedSamples);
                UInt32 frames = outDesc.mVariableFramesInPacket > 0
                    ? outDesc.mVariableFramesInPacket : 1024;
                enc->encodedSamples += (int64_t)frames;
                enc->outQ.push_back({ std::move(au), pktPts, 0 });
            }
        }
        if (s == kMacApkNoDataNow) break;
        if (s != noErr || ioPackets == 0) break;
    }

    if (eos && !enc->eosEmitted) {
        enc->eosEmitted = true;
        int64_t eosPts = enc->lookupPtsUs(enc->encodedSamples);
        enc->outQ.push_back({ {}, eosPts, kAudioEncFlagEndOfStream });
    }
    return 0;
}

int macapk_md_enc_poll(void* h, std::vector<uint8_t>* outData, int64_t* outPtsUs, int* outFlags) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc) return -1;
    std::lock_guard<std::mutex> lk(enc->mu);
    if (enc->outQ.empty()) return 0;
    AudioEncoder::OutPacket pkt = std::move(enc->outQ.front());
    enc->outQ.pop_front();
    if (outData) *outData = std::move(pkt.data);
    if (outPtsUs) *outPtsUs = pkt.ptsUs;
    if (outFlags) *outFlags = pkt.flags;
    return 1;
}

void macapk_md_enc_flush(void* h) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc) return;
    std::lock_guard<std::mutex> lk(enc->mu);
    enc->pcmBuf.clear();
    enc->pcmReadOff = 0;
    enc->partialBytes.clear();
    enc->inflight.clear();
    enc->landmarks.clear();
    enc->pushedSamples = 0;
    enc->encodedSamples = 0;
    enc->eosSeen = false;
    enc->eosEmitted = false;
    enc->outQ.clear();
    if (enc->conv) AudioConverterReset(enc->conv);
}

void macapk_md_enc_close(void* h) {
    AudioEncoder* enc = (AudioEncoder*)h;
    if (!enc) return;
    {
        std::lock_guard<std::mutex> lk(enc->mu);
        if (enc->conv) {
            AudioConverterDispose(enc->conv);
            enc->conv = nullptr;
        }
    }
    delete enc;
}

}  // extern "C"

// ===================== NativeMedia JNI surface =====================
// Mirrors the audio_backend.mm pattern: own JNINativeMethod[] table + a
// RegisterNativeMediaMethods(JNIEnv*) registrar called from
// native_methods.cpp::RegisterAllNativeMethods at boot.

namespace macapk { namespace media {

extern "C" {

jlong Java_com_macapk_loader_NativeMedia_nativeDemuxOpen(JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return 0;
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    void* h = macapk_md_demux_open(p);
    env->ReleaseStringUTFChars(jpath, p);
    return (jlong)(uintptr_t)h;
}
jint  Java_com_macapk_loader_NativeMedia_nativeDemuxTrackCount(JNIEnv*, jclass, jlong h) { return macapk_md_demux_track_count((void*)(uintptr_t)h); }
jint  Java_com_macapk_loader_NativeMedia_nativeDemuxSampleRate(JNIEnv*, jclass, jlong h) { return macapk_md_demux_sample_rate((void*)(uintptr_t)h); }
jint  Java_com_macapk_loader_NativeMedia_nativeDemuxChannels(JNIEnv*, jclass, jlong h)   { return macapk_md_demux_channels((void*)(uintptr_t)h); }
jint  Java_com_macapk_loader_NativeMedia_nativeDemuxFormatId(JNIEnv*, jclass, jlong h)   { return macapk_md_demux_format_id((void*)(uintptr_t)h); }
jlong Java_com_macapk_loader_NativeMedia_nativeDemuxDurationUs(JNIEnv*, jclass, jlong h) { return macapk_md_demux_duration_us((void*)(uintptr_t)h); }
jbyteArray Java_com_macapk_loader_NativeMedia_nativeDemuxCsd0(JNIEnv* env, jclass, jlong h) {
    int n = macapk_md_demux_csd0_size((void*)(uintptr_t)h);
    if (n <= 0) return nullptr;
    jbyteArray arr = env->NewByteArray(n);
    std::vector<uint8_t> tmp(n);
    macapk_md_demux_csd0((void*)(uintptr_t)h, tmp.data(), n);
    env->SetByteArrayRegion(arr, 0, n, (const jbyte*)tmp.data());
    return arr;
}
jbyteArray Java_com_macapk_loader_NativeMedia_nativeDemuxCurrent(JNIEnv* env, jclass, jlong h) {
    int n = macapk_md_demux_current_size((void*)(uintptr_t)h);
    if (n < 0) return nullptr;
    jbyteArray arr = env->NewByteArray(n);
    if (n > 0) {
        std::vector<uint8_t> tmp(n);
        macapk_md_demux_current((void*)(uintptr_t)h, tmp.data(), n);
        env->SetByteArrayRegion(arr, 0, n, (const jbyte*)tmp.data());
    }
    return arr;
}
jlong Java_com_macapk_loader_NativeMedia_nativeDemuxSampleTime(JNIEnv*, jclass, jlong h) { return macapk_md_demux_sample_time((void*)(uintptr_t)h); }
jint  Java_com_macapk_loader_NativeMedia_nativeDemuxAdvance(JNIEnv*, jclass, jlong h)    { return macapk_md_demux_advance((void*)(uintptr_t)h); }
void  Java_com_macapk_loader_NativeMedia_nativeDemuxSeek(JNIEnv*, jclass, jlong h, jlong us) { macapk_md_demux_seek((void*)(uintptr_t)h, us); }
void  Java_com_macapk_loader_NativeMedia_nativeDemuxClose(JNIEnv*, jclass, jlong h)      { macapk_md_demux_close((void*)(uintptr_t)h); }

jlong Java_com_macapk_loader_NativeMedia_nativeDecOpen(JNIEnv* env, jclass, jint fmt, jint sr, jint ch, jbyteArray csd0) {
    std::vector<uint8_t> c;
    if (csd0) { jsize n = env->GetArrayLength(csd0); c.resize(n); env->GetByteArrayRegion(csd0, 0, n, (jbyte*)c.data()); }
    void* h = macapk_md_dec_open(fmt, sr, ch, c.empty()?nullptr:c.data(), (int)c.size());
    return (jlong)(uintptr_t)h;
}
// Decode one AU (au may be null when eos); returns the PCM bytes, or null on error.
jbyteArray Java_com_macapk_loader_NativeMedia_nativeDecDecode(JNIEnv* env, jclass, jlong h, jbyteArray au, jboolean eos) {
    std::vector<uint8_t> in;
    if (au) { jsize n = env->GetArrayLength(au); in.resize(n); env->GetByteArrayRegion(au, 0, n, (jbyte*)in.data()); }
    int ch = macapk_md_dec_out_channels((void*)(uintptr_t)h);
    if (ch <= 0) return nullptr;
    // One AAC packet -> up to ~2 * 1024 frames after priming; be generous.
    int cap = (int)((in.size() / 1) + 16384) * 2 * ch + 4096;
    std::vector<uint8_t> out(cap);
    int got = macapk_md_dec_decode((void*)(uintptr_t)h, in.empty()?nullptr:in.data(), (int)in.size(),
                                   eos?1:0, out.data(), cap);
    if (got < 0) return nullptr;
    jbyteArray arr = env->NewByteArray(got);
    if (got > 0) env->SetByteArrayRegion(arr, 0, got, (const jbyte*)out.data());
    return arr;
}
jint  Java_com_macapk_loader_NativeMedia_nativeDecOutSampleRate(JNIEnv*, jclass, jlong h) { return macapk_md_dec_out_sample_rate((void*)(uintptr_t)h); }
jint  Java_com_macapk_loader_NativeMedia_nativeDecOutChannels(JNIEnv*, jclass, jlong h)   { return macapk_md_dec_out_channels((void*)(uintptr_t)h); }
void  Java_com_macapk_loader_NativeMedia_nativeDecClose(JNIEnv*, jclass, jlong h)         { macapk_md_dec_close((void*)(uintptr_t)h); }

jlong Java_com_macapk_loader_NativeMedia_nativeAudioEncOpen(JNIEnv*, jclass, jint fmt, jint sr, jint ch, jint bitrate) {
    void* h = macapk_md_enc_open(fmt, sr, ch, bitrate);
    return (jlong)(uintptr_t)h;
}

jbyteArray Java_com_macapk_loader_NativeMedia_nativeAudioEncCsd0(JNIEnv* env, jclass, jlong h) {
    int n = macapk_md_enc_csd0_size((void*)(uintptr_t)h);
    if (n <= 0) return nullptr;
    std::vector<uint8_t> tmp(n);
    macapk_md_enc_csd0((void*)(uintptr_t)h, tmp.data(), n);
    jbyteArray arr = env->NewByteArray(n);
    if (arr) env->SetByteArrayRegion(arr, 0, n, (const jbyte*)tmp.data());
    return arr;
}

jint Java_com_macapk_loader_NativeMedia_nativeAudioEncEncode(JNIEnv* env, jclass, jlong h,
                                                              jbyteArray jpcm, jint offset, jint length,
                                                              jlong ptsUs, jboolean eos) {
    std::vector<uint8_t> in;
    if (jpcm && length > 0 && offset >= 0) {
        jsize arrLen = env->GetArrayLength(jpcm);
        if (offset + length > arrLen) return -1;
        in.resize((size_t)length);
        env->GetByteArrayRegion(jpcm, offset, length, (jbyte*)in.data());
    }
    return macapk_md_enc_encode((void*)(uintptr_t)h,
                                in.empty() ? nullptr : in.data(),
                                0, (int)in.size(), (int64_t)ptsUs, eos ? 1 : 0);
}

jbyteArray Java_com_macapk_loader_NativeMedia_nativeAudioEncPoll(JNIEnv* env, jclass, jlong h, jlongArray jmeta) {
    std::vector<uint8_t> data;
    int64_t ptsUs = 0;
    int flags = 0;
    int got = macapk_md_enc_poll((void*)(uintptr_t)h, &data, &ptsUs, &flags);
    if (got <= 0) return nullptr;
    if (jmeta && env->GetArrayLength(jmeta) >= 2) {
        jlong meta[2] = { (jlong)ptsUs, (jlong)flags };
        env->SetLongArrayRegion(jmeta, 0, 2, meta);
    }
    jbyteArray arr = env->NewByteArray((jsize)data.size());
    if (arr && !data.empty()) {
        env->SetByteArrayRegion(arr, 0, (jsize)data.size(), (const jbyte*)data.data());
    }
    return arr;
}

void Java_com_macapk_loader_NativeMedia_nativeAudioEncFlush(JNIEnv*, jclass, jlong h) {
    macapk_md_enc_flush((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeAudioEncClose(JNIEnv*, jclass, jlong h) {
    macapk_md_enc_close((void*)(uintptr_t)h);
}

}  // extern "C"

static const JNINativeMethod kNativeMediaMethods[] = {
    {(char*)"nativeDemuxOpen",       (char*)"(Ljava/lang/String;)J", (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxOpen},
    {(char*)"nativeDemuxTrackCount", (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxTrackCount},
    {(char*)"nativeDemuxSampleRate", (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxSampleRate},
    {(char*)"nativeDemuxChannels",   (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxChannels},
    {(char*)"nativeDemuxFormatId",   (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxFormatId},
    {(char*)"nativeDemuxDurationUs", (char*)"(J)J",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxDurationUs},
    {(char*)"nativeDemuxCsd0",       (char*)"(J)[B", (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxCsd0},
    {(char*)"nativeDemuxCurrent",    (char*)"(J)[B", (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxCurrent},
    {(char*)"nativeDemuxSampleTime", (char*)"(J)J",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxSampleTime},
    {(char*)"nativeDemuxAdvance",    (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxAdvance},
    {(char*)"nativeDemuxSeek",       (char*)"(JJ)V", (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxSeek},
    {(char*)"nativeDemuxClose",      (char*)"(J)V",  (void*)Java_com_macapk_loader_NativeMedia_nativeDemuxClose},
    {(char*)"nativeDecOpen",         (char*)"(III[B)J", (void*)Java_com_macapk_loader_NativeMedia_nativeDecOpen},
    {(char*)"nativeDecDecode",       (char*)"(J[BZ)[B",  (void*)Java_com_macapk_loader_NativeMedia_nativeDecDecode},
    {(char*)"nativeDecOutSampleRate",(char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDecOutSampleRate},
    {(char*)"nativeDecOutChannels",  (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeMedia_nativeDecOutChannels},
    {(char*)"nativeDecClose",        (char*)"(J)V",  (void*)Java_com_macapk_loader_NativeMedia_nativeDecClose},
    {(char*)"nativeAudioEncOpen",    (char*)"(IIII)J", (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncOpen},
    {(char*)"nativeAudioEncCsd0",    (char*)"(J)[B",   (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncCsd0},
    {(char*)"nativeAudioEncEncode",  (char*)"(J[BIIJZ)I", (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncEncode},
    {(char*)"nativeAudioEncPoll",    (char*)"(J[J)[B", (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncPoll},
    {(char*)"nativeAudioEncFlush",   (char*)"(J)V",    (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncFlush},
    {(char*)"nativeAudioEncClose",   (char*)"(J)V",    (void*)Java_com_macapk_loader_NativeMedia_nativeAudioEncClose},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot.
void RegisterNativeMediaMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeMedia");
    if (!cls) { std::fprintf(stderr, "[macapk] FindClass(NativeMedia) failed\n"); env->ExceptionClear(); return; }
    int count = sizeof(kNativeMediaMethods) / sizeof(kNativeMediaMethods[0]);
    if (env->RegisterNatives(cls, kNativeMediaMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(NativeMedia) failed\n"); env->ExceptionClear();
    } else {
        std::fprintf(stderr, "[I] NativeMedia: registered %d methods\n", count);
    }
    env->DeleteLocalRef(cls);
}

}}  // namespace macapk::media
