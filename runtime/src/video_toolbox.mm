// video_toolbox.mm -- real VIDEO decode backend for android.media.MediaCodec,
// backed by VideoToolbox, hardware-accelerated wherever this host offers it.
// Pairs with media_decode.mm (the AudioToolbox audio-decode core behind the
// SAME NativeMedia class) -- this file is the video half.
//
// WHY THIS IS A SEPARATE FILE FROM media_video.mm, AND WHAT IT REUSES.
// media_video.mm (v1.0.485, 2026-06-21) already proved the hard, novel part
// of VideoToolbox-on-Mac.apk end to end: VTDecompressionSession -> CVPixelBuffer
// -> CVMetalTextureCache -> a full-screen render pass -> CAMetalLayer. That
// half (macapk_vd_present_create / macapk_vd_present_to_layer, declared
// extern below) is REUSED VERBATIM here, and by CocoaPresentVideoFrame in
// cocoa_bridge.mm which this file calls into -- nothing about compositing a
// decoded frame onto the screen is reimplemented.
//
// What media_video.mm does NOT cover is MediaCodec's actual contract, and
// that mismatch is exactly why this file exists rather than extending that
// one:
//   - media_video.mm DEMUXES a file itself (AVURLAsset -> AVAssetReader) and
//     gets a ready-made CMVideoFormatDescription from the track for free.
//   - android.media.MediaCodec never demuxes anything. The APP (ExoPlayer's
//     own extractors, for a media3 app like PlayTube) demuxes and hands us
//     raw compressed access units one at a time via queueInputBuffer, with
//     the SPS/PPS (or VPS+SPS+PPS) delivered separately as csd-0/csd-1 in the
//     MediaFormat passed to configure(). We have to build our OWN
//     CMVideoFormatDescription from that csd, and wrap each queued access
//     unit into a CMSampleBuffer ourselves. See BuildFormatDescriptionH264 /
//     BuildFormatDescriptionHEVC / AnnexBToAvcc below -- this conversion is
//     cited from source, not guessed (see the comments at each site).
//
// SCOPE: clear (unencrypted) decode only. queueSecureInputBuffer / MediaCrypto
// / MediaDrm are explicitly out of scope for this project (CLAUDE.md: no DRM
// circumvention) and nothing here touches them.
//
// Citations (verified against the local Xcode SDK headers on this machine,
// MacOSX.sdk, per the project's struct/ABI sourcing rule):
//   VideoToolbox/VTDecompressionSession.h  -- Create / DecodeFrame / decode
//     flags / VTIsHardwareDecodeSupported (macos 10.13+).
//   VideoToolbox/VTDecompressionProperties.h -- kVTVideoDecoderSpecification_
//     EnableHardwareAcceleratedVideoDecoder (decoder-spec dict key, request)
//     and kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder
//     (session property, read back to VERIFY rather than assume hw was used).
//   VideoToolbox/VTSession.h -- VTSessionCopyProperty.
//   CoreMedia/CMFormatDescription.h -- kCMVideoCodecType_* FourCCs;
//     CMVideoFormatDescriptionCreateFromH264ParameterSets (>=2 param sets,
//     NAL types 7/8/13, NALUnitHeaderLength 1/2/4) and the HEVC twin
//     (>=3 param sets, NAL types 32/33/34/39/40, + an extensions dict);
//     CMVideoFormatDescriptionCreate (the generic, non-parameter-set path).
//   CoreMedia/CMBlockBuffer.h -- CMBlockBufferCreateWithMemoryBlock (with
//     kCFAllocatorMalloc as blockAllocator, so CoreMedia frees our malloc'd
//     buffer itself -- CFBase.h confirms kCFAllocatorMalloc "uses malloc(),
//     realloc(), and free()").
//   CoreMedia/CMSampleBuffer.h -- CMSampleBufferCreateReady.
//   CoreMedia/CMFormatDescription.h --
//     kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms ("Sample
//     description extension atoms that were not translated into other
//     entries... CFDictionary mapping CFStrings of the four-char-code atom
//     types to... CFData containing the atom payload"). This is CoreMedia's
//     documented way to attach an out-of-band ISO box (e.g. "esds") the way
//     a real MP4 demuxer would; BuildFormatDescriptionMPEG4Part2 below uses
//     it because, unlike AVC/HEVC, there is no CMVideoFormatDescriptionCreate
//     FromMPEG4-style convenience constructor.
//   VideoToolbox/VTErrors.h -- kVTVideoDecoderBadDataErr = -12909 ("c.f.
//     -8969"), kVTCouldNotFindVideoDecoderErr = -12906. Both are quoted
//     verbatim from this machine's SDK; see the two findings below.
//   AOSP frameworks/base media/java/android/media/MediaCodec.java (class
//     javadoc, "Codec-specific Data" table + the adaptive-playback note):
//     csd-0/csd-1 for AVC are SPS/PPS respectively, each individually
//     start-code-prefixed ("\x00\x00\x00\x01"); HEVC's csd-0 alone carries
//     VPS+SPS+PPS, each individually start-code-prefixed; regular (non-CSD)
//     input buffers for AVC/HEVC are documented to admit start codes too
//     ("package the entire new codec-specific configuration data together
//     with the key frame into a single buffer (including any start codes)"),
//     which is the Annex-B byte-stream form every real extractor (including
//     ExoPlayer's) reformats container-native (length-prefixed) samples into
//     before calling MediaCodec, precisely because that is the documented
//     wire format. VideoToolbox, by contrast, wants AVCC (4-byte big-endian
//     length prefixes, no start codes) -- see docs/video-decode-backend_
//     2026-06-21.md, which pinned that conversion for the file-demux path;
//     this file's AnnexBToAvcc now also serves the app-fed path.
//
// VP9/AV1/MPEG-4 Part 2/MPEG-2/H.263 carry no Android-specific start-code
// convention (Android does not reformat their elementary streams the way it
// does AVC/HEVC's), so their access units are passed to VideoToolbox
// untouched; only AVC/HEVC go through AnnexBToAvcc. (This is about the
// PER-FRAME access units only -- MPEG-4 Part 2's one-time CONFIGURATION now
// gets dedicated handling too; see the next paragraph.)
//
// MPEG-4 PART 2 FINDING (2026-08-16): this codec hit the exact same probe
// bug HEVC hit the day before -- a GENERIC, parameter-free
// CMVideoFormatDescriptionCreate(kCMVideoCodecType_MPEG4Video, w, h, NULL,
// ...) makes VTDecompressionSessionCreate fail with OSStatus -12909
// (kVTVideoDecoderBadDataErr, VTErrors.h), which reads like a codec-absence
// error but is actually "the decoder negotiator has no VOL config to
// validate against" -- exactly HEVC's -8971 shape. Standalone verification
// (VTDecompressionSessionCreate + real VTDecompressionSessionDecodeFrame,
// against an ffmpeg-8.1.2-encoded 64x64 clip's real VOP frames) proved: (a)
// wrapping Android's csd-0 bytes BARE in the "esds"
// SampleDescriptionExtensionAtoms entry reproduces the same -12909 --
// CoreMedia wants the FULL esds box, not the bare DecoderSpecificInfo; (b)
// wrapping a synthesized full esds box (see
// BuildEsdsAtomFromDecoderSpecificInfo below) makes session creation
// succeed (noErr) and decodes all 5 real frames with real measured motion
// (meanAbsDelta=8.53 on the same sparse green-channel sample debug/video_
// toolbox_test.mm uses). The synthesized box's TLV shape was cross-checked
// byte-for-byte against the REAL esds box ffmpeg's own MP4 muxer wrote for
// the identical clip (walked out of moov/trak/mdia/minf/stbl/stsd/mp4v/esds
// by hand) -- see BuildEsdsAtomFromDecoderSpecificInfo's own comment for
// the full trace.
// VTIsHardwareDecodeSupported(MPEG4Video) is false on this host (Apple M2,
// macOS 26.5.2): the decode is real but software-only, matching the
// already-established MPEG-2/H.263 rows (supported=yes hw=no), not a new
// hardware capability.
//
// VP9/AV1 FINDING (2026-08-16, re-audit prompted by the HEVC/MPEG-4 bug
// shape): checked whether the SAME probe bug was hiding a software path for
// either. It is not. VTIsHardwareDecodeSupported is false for both on this
// host (Apple M2 -- AV1 hardware decode needs M3+, per Apple's Silicon media
// engine generations). Standalone verification fed real per-frame data
// (libvpx-vp9 and SVT-AV1-encoded clips, extracted via ffmpeg's IVF muxer)
// through THREE trial sessions: VP9 generic, AV1 generic, and AV1 wrapped
// with its real av1C AV1CodecConfigurationRecord (extracted the same way as
// MPEG-4's esds, to rule out the same class of probe bug). All three failed
// identically with OSStatus -12906 (kVTCouldNotFindVideoDecoderErr,
// VTErrors.h) -- a DIFFERENT error than MPEG-4's -12909, and one that did
// NOT change when fed a real, byte-verified config atom. -12906 means what
// it says: no decoder component (hardware or software) is registered for
// these fourCCs on this host at all, not "rejected the config". So VP9/AV1
// staying unreported here is a genuine host capability gap, not a repeat of
// the HEVC/MPEG-4 bug -- rung 1 of CLAUDE.md's decision ladder (route to
// macOS native) is exhausted for these two, honestly. Rung 2 (a clean
// third-party decoder -- e.g. dav1d for AV1, or libvpx's own VP9 decoder,
// already linked into this target for the VP8 lane, matching how
// audio_vorbis_decode.mm vendors stb_vorbis for the one Android audio MIME
// AudioToolbox itself cannot decode) is the option if these codecs are ever
// needed, but is a real vendoring decision for a future session/task, not
// something to force in here. See
// docs/videotoolbox-probe-audit_2026-08-16.md section 3 for the full
// trial-by-trial evidence.
//
// HARDWARE: every session requests hardware decode explicitly
// (kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder) and
// then READS BACK whether it actually got it
// (kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder) rather
// than assuming -- the measured per-codec result is cached and is exactly
// what nativeVideoIsHardware() reports. VP8 has no VideoToolbox decoder at
// any tier on any macOS version (no kCMVideoCodecType_VP8 constant exists in
// CMFormatDescription.h -- checked); it is out of scope for this file by
// construction and is expected to be served by a software (libvpx) path
// elsewhere, reported honestly as non-hardware.

#import <VideoToolbox/VideoToolbox.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <IOSurface/IOSurface.h>
#import <Foundation/Foundation.h>
#include <mach/mach.h>
#include <dlfcn.h>
#include <sys/sysctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <condition_variable>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Reused from media_video.mm (extern "C", so the linkage name is un-namespaced
// regardless of which C++ namespace either side declares it in). We do not
// duplicate the Metal-compositing implementation -- see the file header.
extern "C" void* macapk_vd_present_create(void* mtlDevice);
extern "C" int   macapk_vd_present_to_layer(void* presenter, void* caMetalLayer, void* pixbuf);

// Defined in cocoa_bridge.mm. Lazily creates (or reuses) a dedicated video
// CAMetalLayer on the app's window and presents `pixbuf` (a retained
// CVPixelBufferRef, passed as void* so this header stays Metal/CoreVideo-free
// on that side) onto it. See that file for the Z-order rationale.
extern "C" void CocoaPresentVideoFrame(void* pixbuf, int width, int height);
extern "C" void CocoaPresentVideoFrameToSurface(void* pixbuf, int width, int height, jobject target);
// runtime/src/video_caps_probe.mm (JNI-free so its offline gate can build it alone).
extern "C" int macapk_vtcaps_probe(const char* mime, int* out, int cap);

namespace {

// ---------------------------------------------------------------------------
// MIME <-> CMVideoCodecType. Single source of truth: MediaCodec.java (this
// file's own Java-side caller) and the sibling MediaCodecList/MediaCodecInfo
// lane both learn "what do we support" ONLY through nativeVideoSupportedMimes
// / nativeVideoIsHardware below, which walk this table. Adding a codec here
// is the only place a future session needs to touch to extend both.
//
// FourCCs verified in CoreMedia/CMFormatDescription.h (this machine's SDK):
// kCMVideoCodecType_H264='avc1', _HEVC='hvc1', _VP9='vp09', _AV1='av01',
// _MPEG4Video='mp4v', _MPEG2Video='mp2v', _H263='h263'. AOSP MIME strings
// verified in frameworks/base media/java/android/media/MediaFormat.java.
// VP8 (video/x-vnd.on2.vp8) is deliberately ABSENT: there is no
// kCMVideoCodecType_VP8 in this SDK at all, on any macOS version -- VP8 is
// not a VideoToolbox format. A software path is expected elsewhere.
struct MimeEntry { const char* mime; CMVideoCodecType fourcc; };
const MimeEntry kMimeTable[] = {
    { "video/avc",           kCMVideoCodecType_H264 },
    { "video/hevc",          kCMVideoCodecType_HEVC },
    { "video/x-vnd.on2.vp9", kCMVideoCodecType_VP9 },
    { "video/av01",          kCMVideoCodecType_AV1 },
    { "video/mp4v-es",       kCMVideoCodecType_MPEG4Video },
    { "video/mpeg2",         kCMVideoCodecType_MPEG2Video },
    { "video/3gpp",          kCMVideoCodecType_H263 },
};

bool MimeToFourcc(const std::string& mime, CMVideoCodecType* out) {
    for (const auto& e : kMimeTable) {
        if (mime == e.mime) { *out = e.fourcc; return true; }
    }
    return false;
}

void FourccToChars(CMVideoCodecType fourcc, char out[5]) {
    // FourCC 32-bit multi-char constants ('avc1') pack big-endian-first
    // regardless of host endianness; extract by shift rather than by
    // pointer-casting the int (which would print the bytes reversed on a
    // little-endian host like this one).
    out[0] = (char)((fourcc >> 24) & 0xFF);
    out[1] = (char)((fourcc >> 16) & 0xFF);
    out[2] = (char)((fourcc >> 8)  & 0xFF);
    out[3] = (char)(fourcc & 0xFF);
    out[4] = 0;
}

// ---------------------------------------------------------------------------
// Annex-B parsing. Shared by csd (parameter-set extraction) and per-frame
// access units (Annex-B -> AVCC rewrite for AVC/HEVC).
// ---------------------------------------------------------------------------

struct NalRef { const uint8_t* ptr; size_t len; };

// Finds every NAL unit in an Annex-B buffer, handling both the 3-byte
// (00 00 01) and 4-byte (00 00 00 01) start-code forms (and defensively, any
// longer run of leading zero padding before the 00 00 01 trigger -- real
// streams occasionally have this). A NAL's end is the TRUE start of the next
// start code (the first of its leading zero bytes), so trailing zero padding
// belonging to the *next* start code is never misattributed to this NAL.
std::vector<NalRef> SplitAnnexB(const uint8_t* data, size_t len) {
    std::vector<NalRef> nals;
    if (!data || len < 3) return nals;
    std::vector<size_t> scBegin;   // true start of each start code (incl. leading zeros)
    std::vector<size_t> nalBegin;  // first byte of NAL payload (right after the "01")
    size_t i = 0;
    while (i + 2 < len) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            size_t sc = i;
            while (sc > 0 && data[sc - 1] == 0) sc--;
            scBegin.push_back(sc);
            nalBegin.push_back(i + 3);
            i += 3;
        } else {
            i++;
        }
    }
    for (size_t k = 0; k < nalBegin.size(); k++) {
        size_t begin = nalBegin[k];
        size_t end = (k + 1 < scBegin.size()) ? scBegin[k + 1] : len;
        if (end > begin) nals.push_back({data + begin, end - begin});
    }
    return nals;
}

// AVC (H.264) NAL header is 1 byte; type is the low 5 bits.
inline int H264NalType(const NalRef& n) { return n.len > 0 ? (n.ptr[0] & 0x1F) : -1; }
// HEVC NAL header is 2 bytes; type is bits 1..6 of the first byte
// (forbidden_zero_bit(1) + nal_unit_type(6) + ...).
inline int HevcNalType(const NalRef& n) { return n.len > 0 ? ((n.ptr[0] >> 1) & 0x3F) : -1; }

// Rewrites an Annex-B buffer (arbitrary mix of start-code lengths) into AVCC
// (each NAL prefixed with its own 4-byte big-endian length, no start codes)
// -- the form VTDecompressionSessionDecodeFrame requires. If no start code is
// found at all (a malformed/non-Annex-B input, contrary to the documented
// AOSP contract for avc/hevc), passes the buffer through unchanged rather
// than dropping the frame outright; logged once so a real instance of this is
// visible without spamming every frame.
std::atomic<bool> g_warnedNonAnnexB{false};
std::vector<uint8_t> AnnexBToAvcc(const uint8_t* data, size_t len) {
    std::vector<uint8_t> out;
    auto nals = SplitAnnexB(data, len);
    if (nals.empty()) {
        if (!g_warnedNonAnnexB.exchange(true)) {
            std::fprintf(stderr,
                "[video-toolbox] WARNING: access unit has no Annex-B start code; "
                "passing through unrewritten (will only decode if it happens to "
                "already be AVCC)\n");
        }
        out.assign(data, data + len);
        return out;
    }
    out.reserve(len + 4 * nals.size());
    for (const auto& n : nals) {
        uint8_t lenBytes[4] = {
            (uint8_t)((n.len >> 24) & 0xFF), (uint8_t)((n.len >> 16) & 0xFF),
            (uint8_t)((n.len >> 8) & 0xFF),  (uint8_t)(n.len & 0xFF),
        };
        out.insert(out.end(), lenBytes, lenBytes + 4);
        out.insert(out.end(), n.ptr, n.ptr + n.len);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Format description construction.
// ---------------------------------------------------------------------------

// AOSP CSD table: H.264 AVC csd-0 = SPS, csd-1 = PPS (each individually
// start-code-prefixed). We accept either csd containing more than one NAL
// (some encoders emit SPS-ext, NAL type 13, alongside the SPS) and filter by
// type rather than assuming exactly one NAL per buffer.
// Hex dump of up to `maxBytes` bytes. A failing format-description build is
// RARE (once per codec open) and otherwise unfalsifiable: the only way to tell
// "the app sent avcC" from "the app sent a truncated Annex-B blob" is to look
// at the bytes. Gated to the failure path only, so it never spams.
static std::string HexDump(const uint8_t* d, size_t n, size_t maxBytes) {
    std::string out;
    size_t lim = n < maxBytes ? n : maxBytes;
    char buf[8];
    for (size_t i = 0; i < lim; i++) {
        std::snprintf(buf, sizeof(buf), "%02x ", d[i]);
        out += buf;
    }
    if (n > lim) out += "...";
    return out;
}

// Parses an AVCDecoderConfigurationRecord (ISO/IEC 14496-15 5.2.4.1, the
// "avcC" box payload) into bare parameter-set NALs.
//
// WHY THIS EXISTS. AOSP's ACodec::MakeAVCCodecSpecificData accepts csd-0 in
// EITHER form: a start-code-prefixed Annex-B blob, or an avcC record. Apps
// that carry MP4/FLV sources (ijkplayer with is_avc:1, and therefore Bilibili)
// hand us the avcC -- 43 bytes, no start codes at all. We only ever handled
// the Annex-B form, so SplitAnnexB found nothing usable,
// CMVideoFormatDescriptionCreateFromH264ParameterSets was never reached, and
// macapk_vtb_open returned null. MediaCodec.start() then threw
// "VideoToolbox: failed to open decoder for video/avc" and ijkplayer silently
// fell back to its ffplay SOFTWARE decoder -- whose output never reaches the
// video plane, which is exactly the reported "audio plays, picture is black".
//
// Returns the NALUnitHeaderLength the record declares (1/2/4 bytes).
static bool ParseAvcC(const uint8_t* d, size_t n,
                      std::vector<const uint8_t*>& ptrs,
                      std::vector<size_t>& sizes,
                      int* outNalLenField) {
    if (!d || n < 7) return false;
    if (d[0] != 1) return false;                 // configurationVersion must be 1
    size_t i = 5;
    int numSps = d[5] & 0x1F;
    if (outNalLenField) *outNalLenField = (d[4] & 0x03) + 1;
    i = 6;
    for (int k = 0; k < numSps; k++) {
        if (i + 2 > n) return false;
        size_t len = (size_t(d[i]) << 8) | d[i + 1];
        i += 2;
        if (len == 0 || i + len > n) return false;
        // Only SPS (7) / SPS-ext (13) belong in the SPS slot; anything else
        // would make CoreMedia reject the whole description.
        if ((d[i] & 0x1F) == 7 || (d[i] & 0x1F) == 13) {
            ptrs.push_back(d + i); sizes.push_back(len);
        }
        i += len;
    }
    if (i >= n) return false;
    int numPps = d[i] & 0xFF;
    i += 1;
    for (int k = 0; k < numPps; k++) {
        if (i + 2 > n) return false;
        size_t len = (size_t(d[i]) << 8) | d[i + 1];
        i += 2;
        if (len == 0 || i + len > n) return false;
        if ((d[i] & 0x1F) == 8) {
            ptrs.push_back(d + i); sizes.push_back(len);
        }
        i += len;
    }
    return !ptrs.empty();
}

// Parses an HEVCDecoderConfigurationRecord (ISO/IEC 14496-15 8.3.3.1, "hvcC").
// Same motivation as ParseAvcC: ACodec accepts it, we did not. Parameter sets
// here are grouped in ARRAYS (one per NAL type), not one flat SPS/PPS list.
static bool ParseHvcC(const uint8_t* d, size_t n,
                      std::vector<const uint8_t*>& ptrs,
                      std::vector<size_t>& sizes,
                      int* outNalLenField) {
    if (!d || n < 23) return false;
    if (d[0] != 1) return false;                 // configurationVersion
    if (outNalLenField) *outNalLenField = (d[21] & 0x03) + 1;
    size_t i = 22;
    int numArrays = d[i] & 0xFF;                 // 3 bits reserved + 5 bits count? no: 1 byte count
    i += 1;
    for (int a = 0; a < numArrays; a++) {
        if (i + 3 > n) return false;
        int nalType = d[i] & 0x3F;               // array_completeness(1) + reserved(1) + type(6)
        i += 1;
        int cnt = (int(d[i]) << 8) | d[i + 1];
        i += 2;
        for (int k = 0; k < cnt; k++) {
            if (i + 2 > n) return false;
            size_t len = (size_t(d[i]) << 8) | d[i + 1];
            i += 2;
            if (len == 0 || i + len > n) return false;
            if (nalType == 32 || nalType == 33 || nalType == 34
                || nalType == 39 || nalType == 40) {
                ptrs.push_back(d + i); sizes.push_back(len);
            }
            i += len;
        }
    }
    return !ptrs.empty();
}

bool BuildFormatDescriptionH264(const uint8_t* csd0, int csd0Len,
                                const uint8_t* csd1, int csd1Len,
                                CMVideoFormatDescriptionRef* outDesc) {
    const size_t l0 = csd0Len > 0 ? (size_t)csd0Len : 0;
    const size_t l1 = csd1Len > 0 ? (size_t)csd1Len : 0;
    std::vector<const uint8_t*> ptrs;
    std::vector<size_t> sizes;
    int nalLenField = 4;

    // Form 1: Annex-B (start-code-prefixed).
    //
    // TWO legal layouts exist and BOTH must be accepted:
    //   (a) csd-0 = SPS, csd-1 = PPS   -- the AOSP CSD table's picture, and
    //       what MediaExtractor-produced formats carry;
    //   (b) csd-0 = SPS **and** PPS concatenated, csd-1 absent -- what
    //       ijkplayer hands us for MP4/FLV sources (Bilibili's player).
    //       Observed in the wild: csd0Len=43, csd1Len=0, bytes
    //       `00 00 00 01 27 64 ... 00 00 00 01 28 ee 02 bc b0`.
    // The previous code only pulled SPS (type 7/13) out of csd-0 and PPS
    // (type 8) out of csd-1, so layout (b) yielded ONE parameter set,
    // CMVideoFormatDescriptionCreateFromH264ParameterSets was never reached,
    // macapk_vtb_open returned null, MediaCodec.start() threw
    // "VideoToolbox: failed to open decoder for video/avc", and ijkplayer
    // silently fell back to its ffplay SOFTWARE decoder -- whose output never
    // reaches the video plane. That is the reported "audio plays, picture is
    // black" (BV17ZXsBNERo, and in all likelihood BV1AspE6EEuU too).
    std::vector<const uint8_t*> spsPtrs, ppsPtrs;
    std::vector<size_t> spsSizes, ppsSizes;
    {
        auto a0 = SplitAnnexB(csd0, l0);
        auto a1 = SplitAnnexB(csd1, l1);
        for (const auto& nals : {a0, a1}) {
            for (const auto& n : nals) {
                int t = H264NalType(n);
                if (t == 7 || t == 13) { spsPtrs.push_back(n.ptr); spsSizes.push_back(n.len); }
                else if (t == 8)       { ppsPtrs.push_back(n.ptr); ppsSizes.push_back(n.len); }
            }
        }
    }
    // CoreMedia expects SPS before PPS; emit them in that order regardless of
    // which buffer each came from.
    for (size_t k = 0; k < spsPtrs.size(); k++) { ptrs.push_back(spsPtrs[k]); sizes.push_back(spsSizes[k]); }
    for (size_t k = 0; k < ppsPtrs.size(); k++) { ptrs.push_back(ppsPtrs[k]); sizes.push_back(ppsSizes[k]); }
    bool viaAvcC = false;
    // Form 2: avcC (AVCDecoderConfigurationRecord). No start codes at all, so
    // SplitAnnexB above found 0 -- ACodec accepts this form and so must we.
    if (spsPtrs.empty() || ppsPtrs.empty()) {
        std::vector<const uint8_t*> p2;
        std::vector<size_t> s2;
        int nl2 = 4;
        bool ok2 = l0 > 0 && ParseAvcC(csd0, l0, p2, s2, &nl2);
        if (!ok2) {
            p2.clear(); s2.clear(); nl2 = 4;
            ok2 = l1 > 0 && ParseAvcC(csd1, l1, p2, s2, &nl2);
        }
        bool hasSps = false, hasPps = false;
        for (const uint8_t* nal : p2) {
            hasSps |= (nal[0] & 0x1f) == 7;
            hasPps |= (nal[0] & 0x1f) == 8;
        }
        if (ok2 && hasSps && hasPps) {
            ptrs = p2; sizes = s2; nalLenField = nl2; viaAvcC = true;
        }
    }
    if (!viaAvcC && (spsPtrs.empty() || ppsPtrs.empty())) {
        std::fprintf(stderr,
            "[video-toolbox] H264: csd-0/csd-1 did not yield SPS+PPS (found %zu "
            "usable parameter-set NALs; csd0Len=%d csd1Len=%d)\n",
            ptrs.size(), csd0Len, csd1Len);
        if (l0 > 0) std::fprintf(stderr, "[video-toolbox] H264: csd-0 = %s\n",
                                 HexDump(csd0, l0, 48).c_str());
        if (l1 > 0) std::fprintf(stderr, "[video-toolbox] H264: csd-1 = %s\n",
                                 HexDump(csd1, l1, 48).c_str());
        return false;
    }
    if (viaAvcC) {
        std::fprintf(stderr,
            "[video-toolbox] H264: built format description from avcC "
            "(%zu param-set NALs, NALUnitHeaderLength=%d)\n",
            ptrs.size(), nalLenField);
    }
    OSStatus s = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(),
        4, outDesc);
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreateFromH264ParameterSets "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// AOSP CSD table: H.265 HEVC csd-0 alone carries VPS+SPS+PPS concatenated,
// each individually start-code-prefixed (csd-1 "Not Used"). We split csd-0
// and keep every NAL whose type is one CMVideoFormatDescriptionCreateFrom
// HEVCParameterSets documents as supported (32 VPS / 33 SPS / 34 PPS / 39
// prefix SEI / 40 suffix SEI), requiring at least one of each of VPS/SPS/PPS.
bool BuildFormatDescriptionHEVC(const uint8_t* csd0, int csd0Len,
                                CMVideoFormatDescriptionRef* outDesc) {
    const size_t l0h = csd0Len > 0 ? (size_t)csd0Len : 0;
    std::vector<const uint8_t*> ptrs;
    std::vector<size_t> sizes;
    bool haveVps = false, haveSps = false, havePps = false;
    auto nals = SplitAnnexB(csd0, l0h);
    for (const auto& n : nals) {
        int t = HevcNalType(n);
        if (t == 32 || t == 33 || t == 34 || t == 39 || t == 40) {
            ptrs.push_back(n.ptr); sizes.push_back(n.len);
            if (t == 32) haveVps = true;
            if (t == 33) haveSps = true;
            if (t == 34) havePps = true;
        }
    }
    int nalLenFieldH = 4;
    if (!(haveVps && haveSps && havePps) && l0h > 0) {
        // hvcC fallback (same ACodec-compatibility reason as avcC above).
        std::vector<const uint8_t*> p2;
        std::vector<size_t> s2;
        int nl2 = 4;
        if (ParseHvcC(csd0, l0h, p2, s2, &nl2)) {
            bool v = false, sp = false, pp = false;
            for (size_t k = 0; k < p2.size(); k++) {
                int t = ((p2[k][0] >> 1) & 0x3F);
                if (t == 32) v = true;
                if (t == 33) sp = true;
                if (t == 34) pp = true;
            }
            if (v && sp && pp) {
                ptrs = p2; sizes = s2; nalLenFieldH = nl2;
                haveVps = haveSps = havePps = true;
                std::fprintf(stderr,
                    "[video-toolbox] HEVC: built format description from hvcC "
                    "(%zu param-set NALs, NALUnitHeaderLength=%d)\n",
                    ptrs.size(), nalLenFieldH);
            }
        }
    }
    if (!(haveVps && haveSps && havePps)) {
        std::fprintf(stderr,
            "[video-toolbox] HEVC: csd-0 missing VPS/SPS/PPS (vps=%d sps=%d pps=%d, "
            "%zu param-set NALs kept, csd0Len=%d)\n",
            haveVps, haveSps, havePps, ptrs.size(), csd0Len);
        if (l0h > 0) std::fprintf(stderr, "[video-toolbox] HEVC: csd-0 = %s\n",
                                  HexDump(csd0, l0h, 48).c_str());
        return false;
    }
    OSStatus s = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
        kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(),
        4, /*extensions=*/nullptr, outDesc);
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreateFromHEVCParameterSets "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// Builds an ISO/IEC 14496-1 "esds" box PAYLOAD (FullBox version+flags(4B) +
// an ES_Descriptor TLV tree) wrapping `info` as the innermost
// DecoderSpecificInfo (tag 0x05) payload -- i.e. the box a real MP4 muxer
// writes into a 'mp4v' VisualSampleEntry, built by hand because CoreMedia
// has no CMVideoFormatDescriptionCreateFromMPEG4-style convenience
// constructor the way it does for AVC/HEVC (see
// kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms's citation
// in the file header).
//
// The TLV shape was cross-checked, not guessed: ffmpeg 8.1.2 (libavcodec's
// native `mpeg4` encoder) muxed a 64x64 testsrc2 clip to MP4 on this machine
// on 2026-08-16 (`ffmpeg -f lavfi -i testsrc2=size=64x64:rate=10:duration=0.5
// -c:v mpeg4 -g 10 -pix_fmt yuv420p -f mp4 out.mp4`), and the REAL `esds` box
// its own muxer wrote (walked out of moov/trak/mdia/minf/stbl/stsd/mp4v/esds
// by hand) is byte-for-byte:
//   00 00 00 00                                        version+flags
//   03 80 80 80 4f  00 01 00                           ES_DescrTag(len=79)
//                                                       ES_ID=1 flags=0
//     04 80 80 80 41  20 11  00 00 00  00 03 0d 40  00 02 64 00
//       DecoderConfigDescrTag(len=65): objectTypeIndication=0x20 (MPEG-4
//       Visual), streamType<<2|upStream<<1|reserved=0x11, bufferSizeDB=0,
//       maxBitrate=200000, avgBitrate=156672
//       05 80 80 80 2f  00 00 01 b0 ...                 DecSpecificInfoTag
//                                                        (len=47) = the VOL
//                                                        bytes, IDENTICAL to
//                                                        this clip's ffprobe
//                                                        `extradata`
//     06 80 80 80 01  02                                SLConfigDescrTag
//                                                        (len=1) predefined=2
// ffmpeg pads every descriptor length to the 4-byte form; this function
// emits the shorter, equally spec-legal minimal-length-byte form instead
// (ISO/IEC 14496-1's "expandable class size" explicitly permits either).
// objectTypeIndication/streamType/tag numbers match the real box exactly;
// bufferSizeDB/maxBitrate/avgBitrate are zeroed ("unspecified") rather than
// invented -- also verified (standalone harness, real
// VTDecompressionSessionCreate + VTDecompressionSessionDecodeFrame against
// this exact host's VideoToolbox) to decode all 5 real frames of the source
// clip with real motion, so VideoToolbox's negotiator does not require real
// bitrate hints, only a well-formed DecoderSpecificInfo. See the MPEG-4 PART
// 2 FINDING paragraph in the file header for the -12909 bug this fixes.
std::vector<uint8_t> BuildEsdsAtomFromDecoderSpecificInfo(const uint8_t* info, size_t infoLen) {
    auto appendDescLen = [](std::vector<uint8_t>& out, size_t len) {
        uint8_t b[4]; int n = 0;
        do { b[n++] = (uint8_t)(len & 0x7F); len >>= 7; } while (len > 0 && n < 4);
        for (int i = n - 1; i >= 0; i--) out.push_back(b[i] | (i > 0 ? 0x80 : 0x00));
    };

    std::vector<uint8_t> decSpecific;
    decSpecific.push_back(0x05);  // DecSpecificInfoTag
    appendDescLen(decSpecific, infoLen);
    decSpecific.insert(decSpecific.end(), info, info + infoLen);

    std::vector<uint8_t> slConfig = { 0x06 };  // SLConfigDescrTag
    appendDescLen(slConfig, 1);
    slConfig.push_back(0x02);  // predefined: MP4-file default SL config

    std::vector<uint8_t> decCfgBody = {
        0x20,                     // objectTypeIndication: MPEG-4 Visual
        0x11,                     // streamType(6b)=Visual(4) upStream(1b)=0 reserved(1b)=1
        0x00, 0x00, 0x00,         // bufferSizeDB (unspecified)
        0x00, 0x00, 0x00, 0x00,   // maxBitrate (unspecified)
        0x00, 0x00, 0x00, 0x00,   // avgBitrate (unspecified)
    };
    decCfgBody.insert(decCfgBody.end(), decSpecific.begin(), decSpecific.end());
    std::vector<uint8_t> decCfg;
    decCfg.push_back(0x04);  // DecoderConfigDescrTag
    appendDescLen(decCfg, decCfgBody.size());
    decCfg.insert(decCfg.end(), decCfgBody.begin(), decCfgBody.end());

    std::vector<uint8_t> esBody = { 0x00, 0x00, 0x00 };  // ES_ID=0, flags=0 (no deps/URL/OCR)
    esBody.insert(esBody.end(), decCfg.begin(), decCfg.end());
    esBody.insert(esBody.end(), slConfig.begin(), slConfig.end());
    std::vector<uint8_t> esDescr;
    esDescr.push_back(0x03);  // ES_DescrTag
    appendDescLen(esDescr, esBody.size());
    esDescr.insert(esDescr.end(), esBody.begin(), esBody.end());

    std::vector<uint8_t> box = { 0x00, 0x00, 0x00, 0x00 };  // FullBox version+flags
    box.insert(box.end(), esDescr.begin(), esDescr.end());
    return box;
}

// MPEG-4 Part 2 (video/mp4v-es): builds the trial/real format description
// through the SAME esds-wrapping path real decode uses (mirrors how
// BuildFormatDescriptionH264/HEVC exercise their real parameter-set
// constructors from the probe too -- see BuildProbeFormatDescription).
//
// csd0 IS the VOL configuration verbatim, no further unwrapping needed: the
// AOSP MediaCodec CSD table's cell text for MPEG-4 ("Decoder-specific
// information from ESDS") is IDENTICAL to AAC's, and this file's audio
// sibling (media_decode.mm's magic-cookie comment) already established for
// that exact wording that Android hands csd-0 unwrapped -- confirmed
// independently here too: ffprobe's `extradata` for this codec (the bytes
// MPEG4Extractor places in csd-0) starts directly at the VOL bitstream's own
// native start code (00 00 01 B0, ISO/IEC 14496-2's VisualObjectSequence
// start code) with nothing prepended.
//
// Defensively strips pure leading zero-padding before the first "00 00 01"
// trigger (the same tolerance SplitAnnexB already applies for AVC/HEVC) in
// case a nonstandard encoder pads it; MPEG-4 Visual's start-code shape
// ("00 00 01 <code>") is byte-identical in scanning terms to AVC/HEVC's
// Annex-B trigger, so the same scan applies even though the byte AFTER the
// trigger plays a different role (the start code itself, not a NAL header).
bool BuildFormatDescriptionMPEG4Part2(const uint8_t* csd0, int csd0Len, int width, int height,
                                      CMVideoFormatDescriptionRef* outDesc) {
    size_t len = csd0Len > 0 ? (size_t)csd0Len : 0;
    size_t begin = 0;
    while (begin + 2 < len && !(csd0[begin] == 0 && csd0[begin+1] == 0 && csd0[begin+2] == 1)) {
        if (csd0[begin] != 0) break;  // non-zero junk before any start code -- stop trimming
        begin++;
    }
    if (len < 4 || begin + 2 >= len || !(csd0[begin] == 0 && csd0[begin+1] == 0 && csd0[begin+2] == 1)) {
        std::fprintf(stderr,
            "[video-toolbox] MPEG4: csd-0 did not contain a VOL start code "
            "(csd0Len=%d) -- cannot build esds config\n", csd0Len);
        return false;
    }
    auto esds = BuildEsdsAtomFromDecoderSpecificInfo(csd0 + begin, len - begin);
    OSStatus s;
    @autoreleasepool {
        CFDataRef esdsData = CFDataCreate(kCFAllocatorDefault, esds.data(), (CFIndex)esds.size());
        NSDictionary* atoms = @{ @"esds": (__bridge id)esdsData };
        NSDictionary* exts = @{
            (id)kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: atoms,
        };
        s = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_MPEG4Video,
                                           width, height, (__bridge CFDictionaryRef)exts, outDesc);
        CFRelease(esdsData);
    }
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreate(MPEG4Video, esds) "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// VP9/AV1/MPEG-2/H.263: none of these has a CoreMedia "FromParameterSets"
// (or, like MPEG-4 Part 2 above, hand-built-atom) constructor wired up, and
// Android does not layer a special out-of-band SPS/PPS convention on top of
// any of them the way it does for AVC/HEVC -- their elementary-stream syntax
// is used as-is. The generic constructor plus codecType/width/height is what
// VT's own header recommends for this shape ("CMVideoFormatDescriptionCreate
// ... creates a format description for a video media stream"), and is
// verified (standalone harness, real per-frame decode) to be sufficient for
// MPEG-2/H.263 on this host -- VP9/AV1 have no VideoToolbox decoder
// component here at all (OSStatus -12906, not a probe-shape bug; see the
// VP9/AV1 FINDING paragraph in the file header), so nothing this constructor
// could supply would change their verdict.
bool BuildFormatDescriptionGeneric(CMVideoCodecType fourcc, int width, int height,
                                   CMVideoFormatDescriptionRef* outDesc) {
    OSStatus s = CMVideoFormatDescriptionCreate(
        kCFAllocatorDefault, fourcc, width, height, /*extensions=*/nullptr, outDesc);
    if (s != noErr) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreate(generic, %s, %dx%d) "
            "failed: %d\n", fcc, width, height, (int)s);
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Decoder handle + the async output queue.
// ---------------------------------------------------------------------------

// MediaCodec.BUFFER_FLAG_END_OF_STREAM, mirrored from MediaCodec.java (this
// file has no Java import; the value is a stable part of the public AOSP
// API and is spelled out there too).
constexpr int kFlagEndOfStream = 4;

struct VtDecoder {
    VTDecompressionSessionRef   session = nullptr;
    CMVideoFormatDescriptionRef fmtDesc = nullptr;
    CMVideoCodecType codecType = 0;
    int  width = 0, height = 0;
    int inputNalLengthSize = 4; // avcC/hvcC input; output always uses 4-byte lengths
    bool isAvcOrHevc = false;     // gates the AnnexBToAvcc rewrite on feed
    bool usingHardware = false;

    // VTDecompressionSessionDecodeFrame is called with EnableAsynchronous
    // Decompression | EnableTemporalProcessing (see CreateSessionInternal) so
    // VideoToolbox itself handles B-frame reordering and may deliver frames
    // on an arbitrary VT-internal thread, out of order relative to the
    // Decode calls that produced them and at an unpredictable later time --
    // hence the mutex-guarded queue rather than a same-thread return value.
    std::mutex mu;
    struct OutFrame { CVPixelBufferRef pb; int64_t ptsUs; int flags; };
    std::deque<OutFrame> outQ;

    // Bumped by flush(). A decode callback compares the generation it was
    // launched with against the CURRENT value; a stale one drops its frame
    // instead of enqueueing, matching AOSP's documented flush() contract
    // ("all output buffers are immediately revoked at the point of the
    // flush") -- otherwise a decode that was in flight across a flush could
    // land a frame from the pre-flush stream into the post-flush queue.
    std::atomic<int> generation{0};

    // Strict in-flight flow control: prevents runaway submissions to
    // VTDecompressionSessionDecodeFrame from exploding VTDecoderXPCService memory.
    std::atomic<int> inFlight{0};
    std::condition_variable cvInFlight;
};

// Rewrites an Annex-B or AVCC buffer into AVCC format required by VTDecompressionSessionDecodeFrame,
// stripping in-band parameter sets (SPS/PPS/VPS/AUD) so that the hardware decoder does not reject the
// frame with -12909 or -17696. Updates the decoder's format description if new parameter sets are encountered.
static std::vector<uint8_t> PrepareAvccPayload(VtDecoder* d, const uint8_t* data, size_t len) {
    std::vector<uint8_t> out;
    if (!data || len == 0) return out;

    // Prefer the configured length-prefixed framing when it validates in full;
    // scanning arbitrary compressed bytes for start codes can split AVCC NALs.
    std::vector<NalRef> nals;
    const size_t prefix = static_cast<size_t>(d->inputNalLengthSize);
    size_t off = 0;
    bool valid = prefix >= 1 && prefix <= 4;
    while (valid && off < len) {
        if (len - off < prefix) { valid = false; break; }
        uint32_t nlen = 0;
        for (size_t i = 0; i < prefix; ++i) nlen = (nlen << 8) | data[off + i];
        off += prefix;
        if (nlen == 0 || nlen > len - off) { valid = false; break; }
        nals.push_back({data + off, nlen});
        off += nlen;
    }
    if (!valid || nals.empty()) {
        nals = SplitAnnexB(data, len);
        if (nals.empty()) {
            out.assign(data, data + len);
            return out;
        }
    }

    std::vector<NalRef> vclNals;
    std::vector<NalRef> paramNals;

    for (const auto& n : nals) {
        if (n.len == 0) continue;
        if (d->codecType == kCMVideoCodecType_H264) {
            int t = H264NalType(n);
            if (t == 7 || t == 8) {
                paramNals.push_back(n);
            } else if (t != 9) { // 9 = AUD (access unit delimiter), strip it
                vclNals.push_back(n);
            }
        } else if (d->codecType == kCMVideoCodecType_HEVC) {
            int t = HevcNalType(n);
            if (t == 32 || t == 33 || t == 34) {
                paramNals.push_back(n);
            } else if (t != 35) { // 35 = AUD, strip it
                vclNals.push_back(n);
            }
        } else {
            vclNals.push_back(n);
        }
    }

    // In-band parameter set update
    if (!paramNals.empty()) {
        CMVideoFormatDescriptionRef newFmt = nullptr;
        if (d->codecType == kCMVideoCodecType_H264) {
            std::vector<const uint8_t*> ptrs;
            std::vector<size_t> sizes;
            bool haveSps = false, havePps = false;
            for (const auto& n : paramNals) {
                int t = H264NalType(n);
                if (t == 7) haveSps = true;
                if (t == 8) havePps = true;
                ptrs.push_back(n.ptr);
                sizes.push_back(n.len);
            }
            if (haveSps && havePps) {
                OSStatus s = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(), 4, &newFmt);
                if (s == noErr && newFmt) {
                    if (!d->fmtDesc || !CFEqual(d->fmtDesc, newFmt)) {
                        if (d->session && VTDecompressionSessionCanAcceptFormatDescription(d->session, newFmt)) {
                            if (d->fmtDesc) CFRelease(d->fmtDesc);
                            d->fmtDesc = newFmt;
                        } else {
                            CFRelease(newFmt);
                        }
                    } else {
                        CFRelease(newFmt);
                    }
                }
            }
        } else if (d->codecType == kCMVideoCodecType_HEVC) {
            std::vector<const uint8_t*> ptrs;
            std::vector<size_t> sizes;
            bool haveVps = false, haveSps = false, havePps = false;
            for (const auto& n : paramNals) {
                int t = HevcNalType(n);
                if (t == 32) haveVps = true;
                if (t == 33) haveSps = true;
                if (t == 34) havePps = true;
                ptrs.push_back(n.ptr);
                sizes.push_back(n.len);
            }
            if (haveVps && haveSps && havePps) {
                OSStatus s = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                    kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(), 4, nullptr, &newFmt);
                if (s == noErr && newFmt) {
                    if (!d->fmtDesc || !CFEqual(d->fmtDesc, newFmt)) {
                        if (d->session && VTDecompressionSessionCanAcceptFormatDescription(d->session, newFmt)) {
                            if (d->fmtDesc) CFRelease(d->fmtDesc);
                            d->fmtDesc = newFmt;
                        } else {
                            CFRelease(newFmt);
                        }
                    } else {
                        CFRelease(newFmt);
                    }
                }
            }
        }
    }

    // Assemble clean AVCC sample payload with only VCL / slice NALs
    for (const auto& n : vclNals) {
        uint8_t lenBytes[4] = {
            (uint8_t)((n.len >> 24) & 0xFF),
            (uint8_t)((n.len >> 16) & 0xFF),
            (uint8_t)((n.len >> 8) & 0xFF),
            (uint8_t)(n.len & 0xFF),
        };
        out.insert(out.end(), lenBytes, lenBytes + 4);
        out.insert(out.end(), n.ptr, n.ptr + n.len);
    }
    return out;
}

static std::atomic<int64_t> s_vtb_retained{0};
static std::atomic<int64_t> s_vtb_released{0};
static std::atomic<int64_t> s_vtb_polled{0};
static std::atomic<int64_t> s_vtb_rendered{0};

struct DecodeCtx {
    VtDecoder* dec;
    int generation;
};

void OutputCallback(void* refcon, void* sourceFrameRefCon, OSStatus status,
                    VTDecodeInfoFlags /*infoFlags*/, CVImageBufferRef imageBuffer,
                    CMTime pts, CMTime /*dur*/) {
    DecodeCtx* ctx = (DecodeCtx*)sourceFrameRefCon;
    VtDecoder* d = (VtDecoder*)refcon;
    if (!ctx) return;  // the probe path (below) passes a null refcon/ctx
    if (d) {
        int prev = d->inFlight.fetch_sub(1, std::memory_order_relaxed);
        if (prev <= 0) {
            d->inFlight.store(0, std::memory_order_relaxed);
        }
    }
    if (status == noErr && imageBuffer
            && ctx->generation == d->generation.load(std::memory_order_relaxed)) {
        CVPixelBufferRef pb = (CVPixelBufferRef)CFRetain(imageBuffer);
        s_vtb_retained.fetch_add(1, std::memory_order_relaxed);
        int64_t ptsUs = CMTIME_IS_VALID(pts)
            ? (int64_t)llround(CMTimeGetSeconds(pts) * 1.0e6) : -1;
        std::lock_guard<std::mutex> lk(d->mu);
        // Container safety cap: prevent runaway queue growth if consumer is dead/stalled,
        // avoiding VTDecoderXPCService / kernel_task wired Mach memory exhaustion.
        // Emergency cap only; normal backpressure in dequeueInputBuffer() prevents reaching this.
        constexpr size_t kMaxOutQ = 36;
        while (d->outQ.size() >= kMaxOutQ) {
            VtDecoder::OutFrame old = d->outQ.front();
            d->outQ.pop_front();
            if (old.pb) {
                CFRelease(old.pb);
                s_vtb_released.fetch_add(1, std::memory_order_relaxed);
                static std::atomic<int64_t> s_droppedAtCap{0};
                int64_t drops = s_droppedAtCap.fetch_add(1, std::memory_order_relaxed) + 1;
                if (drops % 30 == 1) {
                    std::fprintf(stderr, "[vtb-stat] WARNING: dropped frame at cap! total_drops=%lld (outQ=%zu)\n",
                                 (long long)drops, d->outQ.size());
                }
            }
        }
        d->outQ.push_back({pb, ptsUs, 0});
        d->cvInFlight.notify_all();
    } else if (status != noErr) {
        static std::atomic<int> s_errCount{0};
        if (s_errCount.fetch_add(1, std::memory_order_relaxed) < 10) {
            std::fprintf(stderr, "[video-toolbox] decode callback error %d\n", (int)status);
        }
        if (d) d->cvInFlight.notify_all();
    } else {
        if (d) d->cvInFlight.notify_all();
    }
    // else: noErr but stale generation (flushed mid-flight), or a dropped
    // frame (imageBuffer null) -- nothing to enqueue either way.
    delete ctx;
}

// Builds the videoDecoderSpecification + destination pixel-buffer attributes
// and creates the session, then reads back whether hardware decode was
// actually granted. `refcon` may be null for the capability-probe path (no
// real decode ever happens through a probe session).
VTDecompressionSessionRef CreateSessionInternal(CMVideoFormatDescriptionRef fmt,
                                                void* refcon, bool* outUsingHw) {
    @autoreleasepool {
        NSDictionary* decoderSpec = @{
            (id)kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: @YES,
        };
        NSDictionary* destAttrs = @{
            (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
            (id)kCVPixelBufferMetalCompatibilityKey: @YES,
            (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        };
        VTDecompressionOutputCallbackRecord rec = { OutputCallback, refcon };
        VTDecompressionSessionRef session = nullptr;
        OSStatus s = VTDecompressionSessionCreate(
            kCFAllocatorDefault, fmt, (__bridge CFDictionaryRef)decoderSpec,
            (__bridge CFDictionaryRef)destAttrs, &rec, &session);
        if (s != noErr || !session) {
            char fcc[5];
            FourccToChars(CMFormatDescriptionGetMediaSubType(fmt), fcc);
            std::fprintf(stderr,
                "[video-toolbox] VTDecompressionSessionCreate(%s) failed: %d\n", fcc, (int)s);
            if (outUsingHw) *outUsingHw = false;
            return nullptr;
        }
        CFBooleanRef hwFlag = nullptr;
        OSStatus ps = VTSessionCopyProperty(
            session, kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
            kCFAllocatorDefault, &hwFlag);
        bool hw = (ps == noErr && hwFlag && CFBooleanGetValue(hwFlag));
        if (hwFlag) CFRelease(hwFlag);
        if (outUsingHw) *outUsingHw = hw;
        return session;
    }
}

// ---------------------------------------------------------------------------
// Capability probing (per-codec, measured, cached once per process).
// ---------------------------------------------------------------------------

struct ProbeResult { bool supported = false; bool hw = false; };
std::mutex g_probeMu;
std::map<CMVideoCodecType, ProbeResult> g_probeCache;
bool g_probeDiskLoaded = false;

std::string VtProbeOsVersion() {
    char buf[128] = {};
    size_t sz = sizeof(buf) - 1;
    if (sysctlbyname("kern.osversion", buf, &sz, nullptr, 0) != 0 || sz == 0) {
        return "unknown";
    }
    std::string out(buf);
    while (!out.empty() && (out.back() == '\0' || out.back() == '\n'
                            || out.back() == '\r' || out.back() == ' ')) {
        out.pop_back();
    }
    return out.empty() ? std::string("unknown") : out;
}

bool VtProbeEnsureDirRecursive(const std::string& dir) {
    if (dir.empty()) return false;
    struct stat st{};
    if (stat(dir.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos && slash > 0) {
        if (!VtProbeEnsureDirRecursive(dir.substr(0, slash))) return false;
    }
    if (mkdir(dir.c_str(), 0755) == 0 || errno == EEXIST) {
        return stat(dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
    }
    return false;
}

std::string VtProbePrimaryPath(std::string* outDir) {
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) return {};
    std::string dir = std::string(home) + "/Library/Caches/Mac.apk";
    if (outDir) *outDir = dir;
    return dir + "/vtprobe_v1.cache";
}

std::string VtProbeFallbackPath() {
    return std::string("/tmp/macapk_vtprobe_v1_") + std::to_string((unsigned long)getuid()) + ".cache";
}

bool ReadVtProbeFile(const std::string& path, const std::string& osver,
                     std::map<CMVideoCodecType, ProbeResult>* out) {
    if (path.empty()) return false;
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return false;
    char line[512];
    bool any = false;
    while (std::fgets(line, sizeof(line), f)) {
        size_t len = std::strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
        char* p1 = std::strchr(line, '\t');
        if (!p1) continue;
        *p1 = '\0';
        if (std::strcmp(line, "v1") != 0) continue;
        char* ver = p1 + 1;
        char* p2 = std::strchr(ver, '\t');
        if (!p2) continue;
        *p2 = '\0';
        if (osver != ver) continue;
        char* fccHex = p2 + 1;
        char* p3 = std::strchr(fccHex, '\t');
        if (!p3) continue;
        *p3 = '\0';
        if (!fccHex[0]) continue;
        char* endHex = nullptr;
        unsigned long rawFcc = std::strtoul(fccHex, &endHex, 16);
        if (endHex == fccHex || *endHex != '\0' || rawFcc == 0) continue;
        char* flagsStr = p3 + 1;
        char* comma = std::strchr(flagsStr, ',');
        if (!comma) continue;
        *comma = '\0';
        int sup = std::atoi(flagsStr);
        int hw = std::atoi(comma + 1);
        ProbeResult pr;
        pr.supported = (sup != 0);
        pr.hw = (hw != 0);
        (*out)[static_cast<CMVideoCodecType>(rawFcc)] = pr;
        any = true;
    }
    std::fclose(f);
    return any;
}

void LoadVtProbeFromDisk(const std::string& osver,
                         std::map<CMVideoCodecType, ProbeResult>* cache) {
    std::string primary = VtProbePrimaryPath(nullptr);
    if (!ReadVtProbeFile(primary, osver, cache)) {
        ReadVtProbeFile(VtProbeFallbackPath(), osver, cache);
    }
}

bool WriteVtProbeFileAtomic(const std::string& path, const std::string& osver,
                            const std::map<CMVideoCodecType, ProbeResult>& cache) {
    std::string tmp = path + ".tmp." + std::to_string((long long)getpid());
    FILE* f = std::fopen(tmp.c_str(), "w");
    if (!f) return false;
    for (const auto& kv : cache) {
        std::fprintf(f, "v1\t%s\t%08x\t%d,%d\n",
                     osver.c_str(),
                     static_cast<unsigned int>(kv.first),
                     kv.second.supported ? 1 : 0,
                     kv.second.hw ? 1 : 0);
    }
    bool writeOk = (std::ferror(f) == 0);
    if (std::fclose(f) != 0) writeOk = false;
    if (!writeOk || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

void SaveVtProbeToDisk(const std::string& osver,
                       const std::map<CMVideoCodecType, ProbeResult>& cache) {
    std::string dir;
    std::string primary = VtProbePrimaryPath(&dir);
    if (!primary.empty() && VtProbeEnsureDirRecursive(dir)) {
        std::map<CMVideoCodecType, ProbeResult> merged;
        ReadVtProbeFile(primary, osver, &merged);
        for (const auto& kv : cache) merged[kv.first] = kv.second;
        if (WriteVtProbeFileAtomic(primary, osver, merged)) return;
    }
    std::string fallback = VtProbeFallbackPath();
    std::map<CMVideoCodecType, ProbeResult> merged;
    ReadVtProbeFile(fallback, osver, &merged);
    for (const auto& kv : cache) merged[kv.first] = kv.second;
    WriteVtProbeFileAtomic(fallback, osver, merged);
}

// Real, spec-compliant AVC/HEVC parameter sets for the capability probe --
// EXTRACTED, not hand-derived: ffmpeg 8.1.2 (libx264/libx265) encoded a
// testsrc2 64x64 clip on this machine on 2026-08-16, `-bsf:v
// h264_mp4toannexb`/`hevc_mp4toannexb` produced the Annex-B elementary
// stream, and a small script (SplitAnnexB's own algorithm, applied
// standalone) pulled out the SPS/PPS (H264) and VPS/SPS/PPS (HEVC) NAL
// payloads verbatim -- reproducible with the same two ffmpeg commands.
//
// WHY THIS EXISTS: measured on this host, VTDecompressionSessionCreate from
// a GENERIC (no parameter sets, codec+dims only) format description succeeds
// for AVC but FAILS for HEVC with OSStatus -8971 (no public symbol found
// across the VideoToolbox/CoreMedia headers on this SDK), even though
// VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC) reports true. The
// generic descriptor is what's inadequate for HEVC, not the codec's
// availability -- HEVC decode genuinely works once the session is created
// from real parameter sets, exactly as MediaCodec.start() does via
// BuildFormatDescriptionHEVC once an app supplies real csd-0. So the probe
// for AVC/HEVC below builds its trial format description through THAT SAME
// function (not the generic one every other codec uses), which makes the
// probe exercise the real code path instead of a similar-but-different one.
// Start codes are 4-byte here to match NALUnitHeaderLength=4 everywhere else
// in this file.
const uint8_t kProbeH264Csd0[] = {  // SPS, start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xd9, 0x04, 0x26, 0xc0,
    0x44, 0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0x50, 0x3c,
    0x48, 0x99, 0x20,
};
const uint8_t kProbeH264Csd1[] = {  // PPS, start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x68, 0xcb, 0x83, 0xcb, 0x20,
};
const uint8_t kProbeHevcCsd0[] = {  // VPS + SPS + PPS, each start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0c, 0x01, 0xff, 0xff, 0x01, 0x60,
    0x00, 0x00, 0x03, 0x00, 0x90, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
    0x1e, 0x95, 0x98, 0x09,
    0x00, 0x00, 0x00, 0x01, 0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x03,
    0x00, 0x90, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00, 0x1e, 0xa0, 0x20,
    0x81, 0x05, 0x96, 0x56, 0x69, 0x24, 0xca, 0xf0, 0x16, 0x80, 0x80, 0x00,
    0x00, 0x03, 0x00, 0x80, 0x00, 0x00, 0x05, 0x04,
    0x00, 0x00, 0x00, 0x01, 0x44, 0x01, 0xc1, 0x72, 0xb4, 0x22, 0x40,
};

// Real MPEG-4 Part 2 VOL configuration for the capability probe -- same
// extraction method as the AVC/HEVC constants above: ffmpeg 8.1.2
// (libavcodec's native `mpeg4` encoder) encoded a testsrc2 64x64 clip on
// this machine on 2026-08-16 (`ffmpeg -f lavfi -i testsrc2=size=64x64:
// rate=10:duration=0.5 -c:v mpeg4 -g 10 -pix_fmt yuv420p -f mp4 out.mp4`),
// and `ffprobe -show_entries stream=extradata` read back the exact bytes
// MPEG4Extractor would place in csd-0 -- reproducible with the same
// commands. See the MPEG-4 PART 2 FINDING paragraph in the file header and
// BuildEsdsAtomFromDecoderSpecificInfo's comment for why a generic,
// parameter-free probe (OSStatus -12909) is not enough for this codec, the
// same way it was not enough for HEVC.
const uint8_t kProbeMpeg4Part2Vol[] = {
    0x00, 0x00, 0x01, 0xb0, 0x01, 0x00, 0x00, 0x01, 0xb5, 0x89, 0x13, 0x00,
    0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x20, 0x00, 0xc4, 0x8d, 0x88, 0x00,
    0x55, 0x02, 0x04, 0x08, 0x14, 0x43, 0x00, 0x00, 0x01, 0xb2, 0x4c, 0x61,
    0x76, 0x63, 0x36, 0x32, 0x2e, 0x32, 0x38, 0x2e, 0x31, 0x30, 0x32,
};

// Builds a trial format description for the probe: AVC/HEVC/MPEG-4 Part 2 go
// through the SAME real-config constructors real decode uses (see the
// comments above); the remaining codecs have no out-of-band config to test,
// so the generic constructor already exercises the real path for them
// (BuildFormatDescriptionGeneric is what macapk_vtb_open falls back to for
// these mimes too).
bool BuildProbeFormatDescription(CMVideoCodecType fourcc, CMVideoFormatDescriptionRef* outDesc) {
    if (fourcc == kCMVideoCodecType_H264) {
        return BuildFormatDescriptionH264(kProbeH264Csd0, sizeof(kProbeH264Csd0),
                                          kProbeH264Csd1, sizeof(kProbeH264Csd1), outDesc);
    }
    if (fourcc == kCMVideoCodecType_HEVC) {
        return BuildFormatDescriptionHEVC(kProbeHevcCsd0, sizeof(kProbeHevcCsd0), outDesc);
    }
    if (fourcc == kCMVideoCodecType_MPEG4Video) {
        return BuildFormatDescriptionMPEG4Part2(kProbeMpeg4Part2Vol, sizeof(kProbeMpeg4Part2Vol),
                                                64, 64, outDesc);
    }
    return BuildFormatDescriptionGeneric(fourcc, 64, 64, outDesc);
}

// Measures BOTH signals rather than trusting either alone: the "theoretical"
// answer (VTIsHardwareDecodeSupported, macOS 10.13+, per its own header "does
// not guarantee that hardware decode resources will be available at all
// times") and the "real" answer (actually standing up a session from a
// REAL trial format description and reading back
// kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder).
ProbeResult ProbeFourccLocked(CMVideoCodecType fourcc) {
    ProbeResult r;
    bool hwTheoretical = VTIsHardwareDecodeSupported(fourcc);
    CMVideoFormatDescriptionRef fmt = nullptr;
    bool descOk = BuildProbeFormatDescription(fourcc, &fmt);
    if (descOk && fmt) {
        bool hw = false;
        VTDecompressionSessionRef sess = CreateSessionInternal(fmt, /*refcon=*/nullptr, &hw);
        if (sess) {
            r.supported = true;
            r.hw = hw || hwTheoretical;
            VTDecompressionSessionInvalidate(sess);
            CFRelease(sess);
        }
        CFRelease(fmt);
    }
    // Belt-and-suspenders for AVC/HEVC specifically (per the owner's review:
    // "session-creation failure with a synthetic descriptor is INCONCLUSIVE,
    // not negative"). These two are the codecs every Apple platform has
    // decoded in hardware for over a decade; if OUR trial session still
    // somehow fails on some future host despite Apple's own capability query
    // saying yes, prefer that platform answer over silently dropping a
    // codec that is very likely to actually work once a real app supplies
    // real csd -- MediaCodec.start() has a loud-failure safety net
    // (CodecException) for the rare case this optimism is wrong, so this
    // cannot regress into a SILENT failure the way the pre-fix probe did.
    if (!r.supported && hwTheoretical
            && (fourcc == kCMVideoCodecType_H264 || fourcc == kCMVideoCodecType_HEVC)) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr,
            "[video-toolbox] probe %s: trial session create failed but "
            "VTIsHardwareDecodeSupported=yes -- trusting the platform capability "
            "query (see kProbeHevcCsd0's comment); MediaCodec.start() will throw "
            "loudly if this turns out to be wrong for a real app\n", fcc);
        r.supported = true;
        r.hw = true;
    }
    char fcc[5]; FourccToChars(fourcc, fcc);
    std::fprintf(stderr,
        "[video-toolbox] probe %s: supported=%s hw=%s (VTIsHardwareDecodeSupported=%s)\n",
        fcc, r.supported ? "yes" : "no", r.hw ? "yes" : "no", hwTheoretical ? "yes" : "no");
    return r;
}

ProbeResult ProbeFourcc(CMVideoCodecType fourcc) {
    const char* diskEnv = std::getenv("MACAPK_VTPROBE_DISK_CACHE");
    const bool useDisk = !(diskEnv && std::strcmp(diskEnv, "0") == 0);
    std::lock_guard<std::mutex> lk(g_probeMu);
    std::string osver;
    if (useDisk && !g_probeDiskLoaded) {
        osver = VtProbeOsVersion();
        LoadVtProbeFromDisk(osver, &g_probeCache);
        g_probeDiskLoaded = true;
        if (!g_probeCache.empty()) {
            std::fprintf(stderr,
                "[video-toolbox] vtprobe: loaded %zu cached codec probes (osv=%s)\n",
                g_probeCache.size(), osver.c_str());
        }
    }
    auto it = g_probeCache.find(fourcc);
    if (it != g_probeCache.end()) return it->second;
    ProbeResult r = ProbeFourccLocked(fourcc);
    g_probeCache[fourcc] = r;
    if (useDisk) {
        if (osver.empty()) osver = VtProbeOsVersion();
        SaveVtProbeToDisk(osver, g_probeCache);
    }
    return r;
}

// ---------------------------------------------------------------------------
// VideoToolbox Encoder (H.264 / HEVC)
// ---------------------------------------------------------------------------

constexpr int kEncFlagKeyFrame    = 1; // MediaCodec.BUFFER_FLAG_KEY_FRAME
constexpr int kEncFlagCodecConfig = 2; // MediaCodec.BUFFER_FLAG_CODEC_CONFIG

constexpr int kColorFmt32BGRA8888           = 15;
constexpr int kColorFmt32ARGB8888           = 16;
constexpr int kColorFmtYUV420Planar         = 19;
constexpr int kColorFmtYUV420PackedPlanar   = 20;
constexpr int kColorFmtYUV420SemiPlanar     = 21;
constexpr int kColorFmtYUV420PackedSemiPlanar = 39;
constexpr int kColorFmt32ABGR8888           = 0x7f00a000;
constexpr int kColorFmtRGBAFlexible         = 0x7f36a888;
constexpr int kColorFmtYUV420Flexible       = 0x7f420888;

const MimeEntry kEncMimeTable[] = {
    { "video/avc",  kCMVideoCodecType_H264 },
    { "video/hevc", kCMVideoCodecType_HEVC },
};

bool EncMimeToFourcc(const std::string& mime, CMVideoCodecType* out) {
    for (const auto& e : kEncMimeTable) {
        if (mime == e.mime) { *out = e.fourcc; return true; }
    }
    return false;
}

std::mutex g_encProbeMu;
std::map<CMVideoCodecType, ProbeResult> g_encProbeCache;

ProbeResult ProbeEncodeFourccLocked(CMVideoCodecType fourcc) {
    ProbeResult r;
    @autoreleasepool {
        NSDictionary* spec = @{
            (id)kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: @YES,
        };
        VTCompressionSessionRef sess = nullptr;
        OSStatus s = VTCompressionSessionCreate(
            kCFAllocatorDefault, 128, 128, fourcc,
            (__bridge CFDictionaryRef)spec,
            nullptr, kCFAllocatorDefault, nullptr, nullptr, &sess);
        if (s != noErr || !sess) {
            s = VTCompressionSessionCreate(
                kCFAllocatorDefault, 128, 128, fourcc,
                nullptr, nullptr, kCFAllocatorDefault, nullptr, nullptr, &sess);
        }
        if (s == noErr && sess) {
            r.supported = true;
            CFBooleanRef hwFlag = nullptr;
            if (VTSessionCopyProperty(
                    sess, kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
                    kCFAllocatorDefault, &hwFlag) == noErr && hwFlag) {
                r.hw = CFBooleanGetValue(hwFlag);
                CFRelease(hwFlag);
            }
            VTCompressionSessionInvalidate(sess);
            CFRelease(sess);
        }
    }
    char fcc[5]; FourccToChars(fourcc, fcc);
    std::fprintf(stderr, "[video-toolbox] encode probe %s: supported=%s hw=%s\n",
                 fcc, r.supported ? "yes" : "no", r.hw ? "yes" : "no");
    return r;
}

ProbeResult ProbeEncodeFourcc(CMVideoCodecType fourcc) {
    std::lock_guard<std::mutex> lk(g_encProbeMu);
    auto it = g_encProbeCache.find(fourcc);
    if (it != g_encProbeCache.end()) return it->second;
    ProbeResult r = ProbeEncodeFourccLocked(fourcc);
    g_encProbeCache[fourcc] = r;
    return r;
}

struct VtEncoder;

struct EncFrameCtx {
    int generation;
    int64_t origPtsUs;
};

struct VtEncoder {
    VTCompressionSessionRef session = nullptr;
    CMVideoCodecType codecType = 0;
    int width = 0;
    int height = 0;
    float fps = 30.0f;
    bool usingHardware = false;

    std::mutex mu;
    struct OutPacket {
        std::vector<uint8_t> data;
        int64_t ptsUs;
        int flags;
    };
    std::deque<OutPacket> outQ;
    std::vector<uint8_t> csd0; // Annex-B: SPS for AVC; VPS+SPS+PPS for HEVC
    std::vector<uint8_t> csd1; // Annex-B: PPS for AVC; empty for HEVC
    bool csdSent = false;
    bool eosEmitted = false;
    int64_t lastInternalPtsUs = INT64_MIN;
    int64_t lastOrigPtsUs = 0;
    int nalLenSize = 4;

    std::atomic<int> generation{0};
    std::atomic<int> inFlight{0};
    std::atomic<bool> forceKeyFrame{false};
    std::atomic<bool> closing{false};
    std::condition_variable cvInFlight;

    std::mutex cbMu;
    JavaVM* jvm = nullptr;
    jobject javaTarget = nullptr; // jweak
    jmethodID onOutputMethod = nullptr;
};

void NotifyJavaOutput(VtEncoder* e) {
    if (!e || e->closing.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> clk(e->cbMu);
    if (e->closing.load(std::memory_order_acquire)
            || !e->jvm || !e->javaTarget || !e->onOutputMethod) {
        return;
    }
    JNIEnv* env = nullptr;
    bool didAttach = false;
    jint gr = e->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (gr == JNI_EDETACHED) {
        if (e->jvm->AttachCurrentThreadAsDaemon((void**)&env, nullptr) == JNI_OK) {
            didAttach = true;
        } else {
            env = nullptr;
        }
    }
    if (env) {
        jobject target = env->NewLocalRef(e->javaTarget);
        if (target && !env->IsSameObject(target, nullptr)) {
            env->CallVoidMethod(target, e->onOutputMethod);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (target) env->DeleteLocalRef(target);
        if (didAttach) {
            e->jvm->DetachCurrentThread();
        }
    }
}

inline void AppendStartCode(std::vector<uint8_t>& dst, const uint8_t* ptr, size_t len) {
    if (!ptr || len == 0) return;
    dst.push_back(0x00);
    dst.push_back(0x00);
    dst.push_back(0x00);
    dst.push_back(0x01);
    dst.insert(dst.end(), ptr, ptr + len);
}

bool ExtractEncoderParamSets(VtEncoder* e, CMFormatDescriptionRef fdesc,
                             std::vector<uint8_t>* outCsd0,
                             std::vector<uint8_t>* outCsd1) {
    if (!e || !fdesc) return false;
    outCsd0->clear();
    outCsd1->clear();
    if (e->codecType == kCMVideoCodecType_H264) {
        size_t psCount = 0;
        int nalHdrLen = 4;
        if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                fdesc, 0, nullptr, nullptr, &psCount, &nalHdrLen) != noErr || psCount == 0) {
            return false;
        }
        if (nalHdrLen >= 1 && nalHdrLen <= 4) e->nalLenSize = nalHdrLen;
        for (size_t i = 0; i < psCount; i++) {
            const uint8_t* ptr = nullptr;
            size_t len = 0;
            if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    fdesc, i, &ptr, &len, nullptr, nullptr) == noErr && ptr && len > 0) {
                int ntype = ptr[0] & 0x1F;
                if (ntype == 8) AppendStartCode(*outCsd1, ptr, len);
                else AppendStartCode(*outCsd0, ptr, len);
            }
        }
        return !outCsd0->empty();
    }
    if (e->codecType == kCMVideoCodecType_HEVC) {
        size_t psCount = 0;
        int nalHdrLen = 4;
        if (CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                fdesc, 0, nullptr, nullptr, &psCount, &nalHdrLen) != noErr || psCount == 0) {
            return false;
        }
        if (nalHdrLen >= 1 && nalHdrLen <= 4) e->nalLenSize = nalHdrLen;
        for (size_t i = 0; i < psCount; i++) {
            const uint8_t* ptr = nullptr;
            size_t len = 0;
            if (CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                    fdesc, i, &ptr, &len, nullptr, nullptr) == noErr && ptr && len > 0) {
                AppendStartCode(*outCsd0, ptr, len);
            }
        }
        return !outCsd0->empty();
    }
    return false;
}

std::vector<uint8_t> AvccBufferToAnnexB(CMBlockBufferRef bb, int nalLenSize) {
    std::vector<uint8_t> out;
    if (!bb) return out;
    size_t totalLen = CMBlockBufferGetDataLength(bb);
    if (totalLen == 0) return out;
    std::vector<uint8_t> raw(totalLen);
    if (CMBlockBufferCopyDataBytes(bb, 0, totalLen, raw.data()) != kCMBlockBufferNoErr) {
        return out;
    }
    int prefix = (nalLenSize >= 1 && nalLenSize <= 4) ? nalLenSize : 4;
    size_t off = 0;
    out.reserve(totalLen + 16);
    while (off + (size_t)prefix <= totalLen) {
        uint32_t nlen = 0;
        for (int i = 0; i < prefix; i++) nlen = (nlen << 8) | raw[off + i];
        off += (size_t)prefix;
        if (nlen == 0 || nlen > totalLen - off) break;
        AppendStartCode(out, raw.data() + off, nlen);
        off += nlen;
    }
    if (out.empty()) {
        out = std::move(raw);
    }
    return out;
}

void EncOutputCallback(void* outputCallbackRefCon, void* sourceFrameRefCon,
                       OSStatus status, VTEncodeInfoFlags /*infoFlags*/,
                       CMSampleBufferRef sampleBuffer) {
    std::unique_ptr<EncFrameCtx> fctx((EncFrameCtx*)sourceFrameRefCon);
    VtEncoder* e = (VtEncoder*)outputCallbackRefCon;
    if (!e) return;

    int prev = e->inFlight.fetch_sub(1, std::memory_order_relaxed);
    if (prev <= 0) e->inFlight.store(0, std::memory_order_relaxed);

    if (status != noErr || !sampleBuffer || !CMSampleBufferDataIsReady(sampleBuffer)) {
        if (status != noErr) {
            static std::atomic<int> s_encErrCount{0};
            if (s_encErrCount.fetch_add(1, std::memory_order_relaxed) < 10) {
                std::fprintf(stderr, "[video-toolbox] encode callback error %d\n", (int)status);
            }
        }
        e->cvInFlight.notify_all();
        return;
    }

    if (e->closing.load(std::memory_order_acquire)
            || (fctx && fctx->generation != e->generation.load(std::memory_order_relaxed))) {
        e->cvInFlight.notify_all();
        return;
    }

    bool isKeyFrame = true;
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, false);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        CFDictionaryRef dict = (CFDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        if (dict) {
            CFBooleanRef notSync = nullptr;
            if (CFDictionaryGetValueIfPresent(dict, kCMSampleAttachmentKey_NotSync,
                                              (const void**)&notSync)
                    && notSync && CFBooleanGetValue(notSync)) {
                isKeyFrame = false;
            }
        }
    }

    int64_t framePtsUs = 0;
    if (fctx) {
        framePtsUs = fctx->origPtsUs;
    } else {
        CMTime pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
        framePtsUs = CMTIME_IS_VALID(pts) ? (int64_t)llround(CMTimeGetSeconds(pts) * 1.0e6) : 0;
    }

    std::vector<uint8_t> newCsd0, newCsd1;
    CMFormatDescriptionRef fdesc = CMSampleBufferGetFormatDescription(sampleBuffer);
    bool haveParams = fdesc ? ExtractEncoderParamSets(e, fdesc, &newCsd0, &newCsd1) : false;

    std::vector<uint8_t> vclAnnexB = AvccBufferToAnnexB(
        CMSampleBufferGetDataBuffer(sampleBuffer), e->nalLenSize);

    bool produced = false;
    {
        std::lock_guard<std::mutex> lk(e->mu);
        if (haveParams && (!e->csdSent || newCsd0 != e->csd0 || newCsd1 != e->csd1)) {
            e->csd0 = std::move(newCsd0);
            e->csd1 = std::move(newCsd1);
            e->csdSent = true;
            std::vector<uint8_t> combined = e->csd0;
            combined.insert(combined.end(), e->csd1.begin(), e->csd1.end());
            e->outQ.push_back({ std::move(combined), framePtsUs, kEncFlagCodecConfig });
            produced = true;
        }
        if (!vclAnnexB.empty()) {
            e->outQ.push_back({ std::move(vclAnnexB), framePtsUs,
                                isKeyFrame ? kEncFlagKeyFrame : 0 });
            produced = true;
        }
        e->cvInFlight.notify_all();
    }
    if (produced) {
        NotifyJavaOutput(e);
    }
}

void AttachYuvColorSpace(CVPixelBufferRef pb, int width, int height) {
    CFStringRef matrix = (width >= 1280 || height >= 720)
        ? kCVImageBufferYCbCrMatrix_ITU_R_709_2
        : kCVImageBufferYCbCrMatrix_ITU_R_601_4;
    CVBufferSetAttachment(pb, kCVImageBufferYCbCrMatrixKey, matrix, kCVAttachmentMode_ShouldPropagate);
}

CVPixelBufferRef BuildPixelBufferFromBytes(int width, int height, int colorFormat,
                                           int stride, int sliceHeight,
                                           const uint8_t* src, size_t srcLen) {
    if (!src || srcLen == 0 || width <= 0 || height <= 0) return nullptr;

    OSType cvFmt = 0;
    bool isPlanarYuv = false;
    bool isSemiPlanarYuv = false;
    bool isRgbaSwapToBgra = false;

    switch (colorFormat) {
        case kColorFmtYUV420Planar:
        case kColorFmtYUV420PackedPlanar:
        case kColorFmtYUV420Flexible:
            cvFmt = kCVPixelFormatType_420YpCbCr8Planar;
            isPlanarYuv = true;
            break;
        case kColorFmtYUV420SemiPlanar:
        case kColorFmtYUV420PackedSemiPlanar:
            cvFmt = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
            isSemiPlanarYuv = true;
            break;
        case kColorFmt32BGRA8888:
            cvFmt = kCVPixelFormatType_32BGRA;
            break;
        case kColorFmt32ARGB8888:
            cvFmt = kCVPixelFormatType_32ARGB;
            break;
        case kColorFmt32ABGR8888:
        case kColorFmtRGBAFlexible:
            cvFmt = kCVPixelFormatType_32BGRA;
            isRgbaSwapToBgra = true;
            break;
        default:
            // Fallback heuristic if caller left colorFormat=0/default
            cvFmt = kCVPixelFormatType_420YpCbCr8Planar;
            isPlanarYuv = true;
            break;
    }

    CVPixelBufferRef pb = nullptr;
    @autoreleasepool {
        NSDictionary* attrs = @{ (id)kCVPixelBufferIOSurfacePropertiesKey: @{} };
        CVReturn r = CVPixelBufferCreate(
            kCFAllocatorDefault, (size_t)width, (size_t)height,
            cvFmt, (__bridge CFDictionaryRef)attrs, &pb);
        if (r != kCVReturnSuccess || !pb) return nullptr;
    }

    CVPixelBufferLockBaseAddress(pb, 0);
    if (isPlanarYuv) {
        size_t yStride = (stride >= width) ? (size_t)stride : (size_t)width;
        size_t sliceH = (sliceHeight >= height) ? (size_t)sliceHeight : (size_t)height;
        size_t cw = (size_t)(width + 1) / 2;
        size_t ch = (size_t)(height + 1) / 2;
        size_t uvStride = (yStride + 1) / 2;
        size_t uvSliceH = (sliceH + 1) / 2;

        uint8_t* dstY = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 0);
        size_t dstYStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0);
        for (int y = 0; y < height; y++) {
            size_t off = (size_t)y * yStride;
            if (off >= srcLen) break;
            size_t cp = std::min((size_t)width, srcLen - off);
            std::memcpy(dstY + (size_t)y * dstYStride, src + off, cp);
        }

        size_t uBase = yStride * sliceH;
        uint8_t* dstU = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 1);
        size_t dstUStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
        for (size_t y = 0; y < ch; y++) {
            size_t off = uBase + y * uvStride;
            if (off >= srcLen) break;
            size_t cp = std::min(cw, srcLen - off);
            std::memcpy(dstU + y * dstUStride, src + off, cp);
        }

        size_t vBase = uBase + uvStride * uvSliceH;
        uint8_t* dstV = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 2);
        size_t dstVStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 2);
        for (size_t y = 0; y < ch; y++) {
            size_t off = vBase + y * uvStride;
            if (off >= srcLen) break;
            size_t cp = std::min(cw, srcLen - off);
            std::memcpy(dstV + y * dstVStride, src + off, cp);
        }
        AttachYuvColorSpace(pb, width, height);
    } else if (isSemiPlanarYuv) {
        size_t yStride = (stride >= width) ? (size_t)stride : (size_t)width;
        size_t sliceH = (sliceHeight >= height) ? (size_t)sliceHeight : (size_t)height;
        size_t ch = (size_t)(height + 1) / 2;
        size_t uvRowBytes = ((size_t)(width + 1) / 2) * 2;
        size_t uvSrcStride = std::max(uvRowBytes, yStride);

        uint8_t* dstY = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 0);
        size_t dstYStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0);
        for (int y = 0; y < height; y++) {
            size_t off = (size_t)y * yStride;
            if (off >= srcLen) break;
            size_t cp = std::min((size_t)width, srcLen - off);
            std::memcpy(dstY + (size_t)y * dstYStride, src + off, cp);
        }

        size_t uvBase = yStride * sliceH;
        uint8_t* dstUV = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 1);
        size_t dstUVStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
        for (size_t y = 0; y < ch; y++) {
            size_t off = uvBase + y * uvSrcStride;
            if (off >= srcLen) break;
            size_t cp = std::min(uvRowBytes, srcLen - off);
            std::memcpy(dstUV + y * dstUVStride, src + off, cp);
        }
        AttachYuvColorSpace(pb, width, height);
    } else {
        size_t rowBytes = (size_t)width * 4;
        size_t srcStride = (stride >= width * 4)
            ? (size_t)stride
            : (stride >= width ? (size_t)stride * 4 : rowBytes);
        uint8_t* dst = (uint8_t*)CVPixelBufferGetBaseAddress(pb);
        size_t dstStride = CVPixelBufferGetBytesPerRow(pb);
        for (int y = 0; y < height; y++) {
            size_t off = (size_t)y * srcStride;
            if (off + rowBytes > srcLen) break;
            const uint8_t* sRow = src + off;
            uint8_t* dRow = dst + (size_t)y * dstStride;
            if (isRgbaSwapToBgra) {
                for (int x = 0; x < width; x++) {
                    dRow[x * 4 + 0] = sRow[x * 4 + 2];
                    dRow[x * 4 + 1] = sRow[x * 4 + 1];
                    dRow[x * 4 + 2] = sRow[x * 4 + 0];
                    dRow[x * 4 + 3] = sRow[x * 4 + 3];
                }
            } else {
                std::memcpy(dRow, sRow, rowBytes);
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(pb, 0);
    return pb;
}

CVPixelBufferRef BuildPixelBufferFromArgb(int width, int height, const uint32_t* argb, size_t numPixels) {
    if (!argb || width <= 0 || height <= 0) return nullptr;
    CVPixelBufferRef pb = nullptr;
    @autoreleasepool {
        NSDictionary* attrs = @{ (id)kCVPixelBufferIOSurfacePropertiesKey: @{} };
        CVReturn r = CVPixelBufferCreate(
            kCFAllocatorDefault, (size_t)width, (size_t)height,
            kCVPixelFormatType_32BGRA, (__bridge CFDictionaryRef)attrs, &pb);
        if (r != kCVReturnSuccess || !pb) return nullptr;
    }
    CVPixelBufferLockBaseAddress(pb, 0);
    uint8_t* dst = (uint8_t*)CVPixelBufferGetBaseAddress(pb);
    size_t dstStride = CVPixelBufferGetBytesPerRow(pb);
    size_t copyRows = std::min((size_t)height, numPixels / (size_t)width);
    size_t rowBytes = (size_t)width * 4;
    for (size_t y = 0; y < copyRows; y++) {
        // Java 0xAARRGGBB in little-endian memory is [B, G, R, A] = kCVPixelFormatType_32BGRA.
        std::memcpy(dst + y * dstStride, argb + y * (size_t)width, rowBytes);
    }
    CVPixelBufferUnlockBaseAddress(pb, 0);
    return pb;
}

int EncodePixelBuffer(VtEncoder* e, CVPixelBufferRef pb, int64_t ptsUs) {
    if (!e || !e->session || !pb) return -1;
    {
        std::unique_lock<std::mutex> lk(e->mu);
        constexpr int kMaxEncInFlight = 8;
        if (e->inFlight.load(std::memory_order_relaxed) >= kMaxEncInFlight) {
            e->cvInFlight.wait_for(lk, std::chrono::milliseconds(100), [&] {
                return e->inFlight.load(std::memory_order_relaxed) < kMaxEncInFlight
                    || e->closing.load(std::memory_order_acquire);
            });
        }
        if (e->closing.load(std::memory_order_acquire)) return -2;
    }

    int64_t internalPtsUs;
    {
        std::lock_guard<std::mutex> lk(e->mu);
        if (e->lastInternalPtsUs == INT64_MIN) {
            internalPtsUs = (ptsUs >= 0) ? ptsUs : 0;
        } else {
            internalPtsUs = (ptsUs > e->lastInternalPtsUs) ? ptsUs : (e->lastInternalPtsUs + 1);
        }
        e->lastInternalPtsUs = internalPtsUs;
        e->lastOrigPtsUs = ptsUs;
    }

    CMTime pts = CMTimeMake(internalPtsUs, 1000000);
    CMTime dur = e->fps > 0.0f
        ? CMTimeMake((int64_t)llround(1.0e6 / (double)e->fps), 1000000)
        : kCMTimeInvalid;

    CFDictionaryRef frameProps = nullptr;
    if (e->forceKeyFrame.exchange(false, std::memory_order_acq_rel)) {
        const void* keys[] = { kVTEncodeFrameOptionKey_ForceKeyFrame };
        const void* vals[] = { kCFBooleanTrue };
        frameProps = CFDictionaryCreate(
            kCFAllocatorDefault, keys, vals, 1,
            &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    }

    EncFrameCtx* ctx = new EncFrameCtx{ e->generation.load(std::memory_order_relaxed), ptsUs };
    VTEncodeInfoFlags flagsOut = 0;
    e->inFlight.fetch_add(1, std::memory_order_relaxed);
    OSStatus es = VTCompressionSessionEncodeFrame(
        e->session, pb, pts, dur, frameProps, ctx, &flagsOut);
    if (frameProps) CFRelease(frameProps);
    if (es != noErr) {
        int prev = e->inFlight.fetch_sub(1, std::memory_order_relaxed);
        if (prev <= 0) e->inFlight.store(0, std::memory_order_relaxed);
        delete ctx;
        std::fprintf(stderr, "[video-toolbox] VTCompressionSessionEncodeFrame failed %d\n", (int)es);
        return -3;
    }
    return 0;
}

}  // namespace

// ---------------------------------------------------------------------------
// C ABI (macapk_vtb_*), mirroring the macapk_vd_* / macapk_md_* convention
// used by media_video.mm / media_decode.mm.
// ---------------------------------------------------------------------------

extern "C" {

// Returns 1 if `mime` is decodable on this host (hardware or software via
// VideoToolbox -- VP8 is never reported here, see the file header), 0
// otherwise. *outHw is set to 1 only when the measured session actually used
// hardware decode.
int macapk_vtb_probe_mime(const char* mimeC, int* outHw) {
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!MimeToFourcc(mime, &fourcc)) { if (outHw) *outHw = 0; return 0; }
    ProbeResult r = ProbeFourcc(fourcc);
    if (outHw) *outHw = r.hw ? 1 : 0;
    return r.supported ? 1 : 0;
}

void* macapk_vtb_open(const char* mimeC, int width, int height,
                      const uint8_t* csd0, int csd0Len,
                      const uint8_t* csd1, int csd1Len,
                      int /*wantSurface*/, int* outHw) {
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!MimeToFourcc(mime, &fourcc)) {
        std::fprintf(stderr, "[video-toolbox] open: unsupported mime '%s'\n", mime.c_str());
        return nullptr;
    }
    bool isAvcHevc = (fourcc == kCMVideoCodecType_H264 || fourcc == kCMVideoCodecType_HEVC);

    CMVideoFormatDescriptionRef fmt = nullptr;
    bool ok;
    if (fourcc == kCMVideoCodecType_H264) {
        ok = BuildFormatDescriptionH264(csd0, csd0Len, csd1, csd1Len, &fmt);
    } else if (fourcc == kCMVideoCodecType_HEVC) {
        ok = BuildFormatDescriptionHEVC(csd0, csd0Len, &fmt);
    } else if (fourcc == kCMVideoCodecType_MPEG4Video) {
        ok = BuildFormatDescriptionMPEG4Part2(csd0, csd0Len, width, height, &fmt);
    } else {
        ok = BuildFormatDescriptionGeneric(fourcc, width, height, &fmt);
    }
    if (!ok || !fmt) return nullptr;

    VtDecoder* d = new VtDecoder();
    d->codecType = fourcc;
    d->isAvcOrHevc = isAvcHevc;
    // Configuration records declare the input prefix width. Annex-B CSD starts
    // with a start code, so it retains the default four-byte length assumption.
    if (csd0 && csd0Len > 0 && csd0[0] == 1) {
        if (fourcc == kCMVideoCodecType_H264 && csd0Len >= 7)
            d->inputNalLengthSize = (csd0[4] & 3) + 1;
        else if (fourcc == kCMVideoCodecType_HEVC && csd0Len >= 23)
            d->inputNalLengthSize = (csd0[21] & 3) + 1;
    }
    d->fmtDesc = fmt;
    CMVideoDimensions dim = CMVideoFormatDescriptionGetDimensions(fmt);
    d->width  = dim.width  > 0 ? dim.width  : width;
    d->height = dim.height > 0 ? dim.height : height;

    bool hw = false;
    d->session = CreateSessionInternal(fmt, /*refcon=*/d, &hw);
    if (!d->session) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr, "[video-toolbox] open: VTDecompressionSessionCreate failed for %s\n", fcc);
        CFRelease(d->fmtDesc);
        delete d;
        return nullptr;
    }
    d->usingHardware = hw;
    if (outHw) *outHw = hw ? 1 : 0;
    std::fprintf(stderr, "[video-toolbox] open: mime=%s %dx%d hw=%s\n",
                mime.c_str(), d->width, d->height, hw ? "YES" : "no (software)");
    return d;
}

// Feeds one compressed access unit (may be null/0-length at eos with nothing
// left to submit). Fire-and-forget: the decoded frame (if any) surfaces later
// through macapk_vtb_poll. Returns 0 on success, negative on error.
int macapk_vtb_decode(void* h, const uint8_t* au, int auLen, int64_t ptsUs, int eos) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d || !d->session) return -1;
    if (au && auLen > 0) {
        {
            std::unique_lock<std::mutex> lk(d->mu);
            constexpr int kMaxInFlight = 16;
            constexpr size_t kMaxQ = 20;
            if (d->inFlight.load(std::memory_order_relaxed) >= kMaxInFlight || d->outQ.size() >= kMaxQ) {
                d->cvInFlight.wait_for(lk, std::chrono::milliseconds(50), [&] {
                    return d->inFlight.load(std::memory_order_relaxed) < kMaxInFlight && d->outQ.size() < kMaxQ;
                });
            }
        }
        std::vector<uint8_t> payload = d->isAvcOrHevc
            ? PrepareAvccPayload(d, au, (size_t)auLen)
            : std::vector<uint8_t>(au, au + auLen);
        if (payload.empty()) {
            return 0;
        }
        void* mem = std::malloc(payload.size());
        if (!mem) return -2;
        std::memcpy(mem, payload.data(), payload.size());
        CMBlockBufferRef bb = nullptr;
        OSStatus s = CMBlockBufferCreateWithMemoryBlock(
            kCFAllocatorDefault, mem, payload.size(), kCFAllocatorMalloc,
            /*customBlockSource=*/nullptr, /*offsetToData=*/0, payload.size(),
            /*flags=*/0, &bb);
        if (s != noErr || !bb) {
            std::free(mem);
            std::fprintf(stderr, "[video-toolbox] CMBlockBufferCreateWithMemoryBlock failed %d\n", (int)s);
            return -3;
        }
        CMSampleTimingInfo timing = {
            kCMTimeInvalid, CMTimeMake(ptsUs, 1000000), kCMTimeInvalid
        };
        size_t sampleSize = payload.size();
        CMSampleBufferRef sbuf = nullptr;
        s = CMSampleBufferCreateReady(kCFAllocatorDefault, bb, d->fmtDesc,
                                      /*numSamples=*/1, /*numSampleTimingEntries=*/1, &timing,
                                      /*numSampleSizeEntries=*/1, &sampleSize, &sbuf);
        CFRelease(bb);
        if (s != noErr || !sbuf) {
            std::fprintf(stderr, "[video-toolbox] CMSampleBufferCreateReady failed %d\n", (int)s);
            return -4;
        }
        DecodeCtx* ctx = new DecodeCtx{ d, d->generation.load(std::memory_order_relaxed) };
        VTDecodeInfoFlags infoOut = 0;
        static const bool sTemporal = []() {
            const char* e = std::getenv("MACAPK_VTB_TEMPORAL_PROCESSING");
            return e && std::strcmp(e, "1") == 0;
        }();
        VTDecodeFrameFlags decodeFlags = kVTDecodeFrame_EnableAsynchronousDecompression;
        if (sTemporal) decodeFlags |= kVTDecodeFrame_EnableTemporalProcessing;
        d->inFlight.fetch_add(1, std::memory_order_relaxed);
        OSStatus ds = VTDecompressionSessionDecodeFrame(
            d->session, sbuf, decodeFlags, ctx, &infoOut);
        CFRelease(sbuf);
        if (ds != noErr) {
            d->inFlight.fetch_sub(1, std::memory_order_relaxed);
            d->cvInFlight.notify_all();
            std::fprintf(stderr, "[video-toolbox] VTDecompressionSessionDecodeFrame failed %d\n", (int)ds);
            delete ctx;
            return -5;
        }
    }
    if (eos) {
        // Block (briefly -- a one-time end-of-stream event, not a hot path)
        // until every in-flight/delayed frame has been through the callback,
        // so the EOS marker we push below is genuinely last in the queue.
        VTDecompressionSessionFinishDelayedFrames(d->session);
        VTDecompressionSessionWaitForAsynchronousFrames(d->session);
        std::lock_guard<std::mutex> lk(d->mu);
        d->outQ.push_back({nullptr, -1, kFlagEndOfStream});
    }
    return 0;
}

// Pops the oldest ready frame. Returns 1 and fills the out-params if one was
// available, 0 if the queue is empty (TRY_AGAIN), -1 if `h` is invalid.
int macapk_vtb_poll(void* h, void** outPixbuf, int64_t* outPtsUs, int* outFlags) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return -1;
    std::lock_guard<std::mutex> lk(d->mu);
    if (d->outQ.empty()) return 0;
    VtDecoder::OutFrame f = d->outQ.front();
    d->outQ.pop_front();
    d->cvInFlight.notify_all();
    s_vtb_polled.fetch_add(1, std::memory_order_relaxed);
    if (outPixbuf) *outPixbuf = f.pb;
    if (outPtsUs)  *outPtsUs  = f.ptsUs;
    if (outFlags)  *outFlags  = f.flags;
    return 1;
}

// Waits briefly on d->cvInFlight when outQ is empty and decode work is in flight,
// then pops the oldest ready frame. Returns 1 if popped, 0 if empty/timeout, -1 on error.
int macapk_vtb_wait_poll(void* h, void** outPixbuf, int64_t* outPtsUs, int* outFlags, int64_t timeoutUs) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return -1;
    std::unique_lock<std::mutex> lk(d->mu);
    if (d->outQ.empty() && timeoutUs != 0 && d->inFlight.load(std::memory_order_relaxed) > 0) {
        int64_t waitUs = (timeoutUs < 0) ? 2000 : std::min<int64_t>(timeoutUs, 2000);
        d->cvInFlight.wait_for(lk, std::chrono::microseconds(waitUs), [&] {
            return !d->outQ.empty() || d->inFlight.load(std::memory_order_relaxed) <= 0;
        });
    }
    if (d->outQ.empty()) return 0;
    VtDecoder::OutFrame f = d->outQ.front();
    d->outQ.pop_front();
    d->cvInFlight.notify_all();
    s_vtb_polled.fetch_add(1, std::memory_order_relaxed);
    if (outPixbuf) *outPixbuf = f.pb;
    if (outPtsUs)  *outPtsUs  = f.ptsUs;
    if (outFlags)  *outFlags  = f.flags;
    return 1;
}

// Returns the current depth of unconsumed frames (ready in outQ + in-flight).
int macapk_vtb_queue_depth(void* h) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return 0;
    std::lock_guard<std::mutex> lk(d->mu);
    return (int)d->outQ.size() + d->inFlight.load(std::memory_order_relaxed);
}

// releaseOutputBuffer(index, render=true): hand the frame to the screen.
// Delegates entirely to CocoaPresentVideoFrame (cocoa_bridge.mm), which
// itself delegates the actual compositing to macapk_vd_present_* -- see the
// file header for why nothing is reimplemented here.
void macapk_vtb_render_frame(void* pixbuf, int width, int height) {
    if (!pixbuf) return;
    s_vtb_rendered.fetch_add(1, std::memory_order_relaxed);
    CocoaPresentVideoFrame(pixbuf, width, height);
}

// releaseOutputBuffer(index, render=false), or the post-render cleanup after
// a true release: return the frame's native memory. MUST be called exactly
// once per frame macapk_vtb_poll ever returned, render or not -- mirrors
// AOSP's "if you don't render the buffer... call ... with render set to
// false to simply return the buffer to the codec" contract.
void macapk_vtb_release_frame(void* pixbuf) {
    if (pixbuf) {
        CFRelease((CVPixelBufferRef)pixbuf);
        int64_t rel = s_vtb_released.fetch_add(1, std::memory_order_relaxed) + 1;
        int64_t ret = s_vtb_retained.load(std::memory_order_relaxed);
        if (rel % 60 == 0) {
            std::fprintf(stderr,
                "[vtb-stat] retained=%lld released=%lld live=%lld polled=%lld rendered=%lld\n",
                (long long)ret, (long long)rel, (long long)(ret - rel),
                (long long)s_vtb_polled.load(std::memory_order_relaxed),
                (long long)s_vtb_rendered.load(std::memory_order_relaxed));
        }
    }
}

// Best-effort raw-pixel readout for the no-Surface decode path (MediaCodec.
// getOutputImage). Copies packed 32BGRA (the destination format every
// session is opened with -- see CreateSessionInternal) into a caller-owned
// buffer. Returns 0 on success, negative on error (including "buffer too
// small", which the caller sizes from a prior call using width/height/stride
// of 0/0/0 -- callers here always pre-size from the known decode dimensions,
// so this is a safety net, not the primary sizing path).
int macapk_vtb_frame_copy_bgra(void* pixbuf, uint8_t* outBuf, int outCap,
                               int* outW, int* outH, int* outStride) {
    CVPixelBufferRef pb = (CVPixelBufferRef)pixbuf;
    if (!pb || !outBuf) return -1;
    CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    size_t w = CVPixelBufferGetWidth(pb);
    size_t h = CVPixelBufferGetHeight(pb);
    size_t stride = CVPixelBufferGetBytesPerRow(pb);
    const uint8_t* base = (const uint8_t*)CVPixelBufferGetBaseAddress(pb);
    size_t need = stride * h;
    if (!base || (size_t)outCap < need) {
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        return -2;
    }
    std::memcpy(outBuf, base, need);
    CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    if (outW) *outW = (int)w;
    if (outH) *outH = (int)h;
    if (outStride) *outStride = (int)stride;
    return 0;
}

// flush(): AOSP "all output buffers are immediately revoked at the point of
// the flush". Bumps the generation counter (so any decode already in flight
// drops its result instead of landing in the fresh queue -- see VtDecoder's
// comment) and drains + releases whatever was already queued.
void macapk_vtb_flush(void* h) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return;
    d->generation.fetch_add(1, std::memory_order_relaxed);
    if (d->session) {
        VTDecompressionSessionFinishDelayedFrames(d->session);
        VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    }
    std::lock_guard<std::mutex> lk(d->mu);
    d->inFlight.store(0, std::memory_order_relaxed);
    while (!d->outQ.empty()) {
        if (d->outQ.front().pb) {
            CFRelease(d->outQ.front().pb);
            s_vtb_released.fetch_add(1, std::memory_order_relaxed);
        }
        d->outQ.pop_front();
    }
    d->cvInFlight.notify_all();
}

void macapk_vtb_close(void* h) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return;
    if (d->session) {
        VTDecompressionSessionWaitForAsynchronousFrames(d->session);
        VTDecompressionSessionInvalidate(d->session);
        CFRelease(d->session);
    }
    if (d->fmtDesc) CFRelease(d->fmtDesc);
    {
        std::lock_guard<std::mutex> lk(d->mu);
        while (!d->outQ.empty()) {
            if (d->outQ.front().pb) {
                CFRelease(d->outQ.front().pb);
                s_vtb_released.fetch_add(1, std::memory_order_relaxed);
            }
            d->outQ.pop_front();
        }
    }
    delete d;
}

int macapk_vtb_width(void* h)  { VtDecoder* d = (VtDecoder*)h; return d ? d->width  : 0; }
int macapk_vtb_height(void* h) { VtDecoder* d = (VtDecoder*)h; return d ? d->height : 0; }

// Diagnostic self-test (MACAPK_VIDEO_TOOLBOX_SELFTEST=<path to a raw Annex-B
// elementary stream>, MACAPK_VIDEO_TOOLBOX_SELFTEST_MIME=video/avc|video/hevc):
// decodes every VCL NAL in the file and presents each frame to the screen
// through the REAL MediaCodec-facing pipeline (macapk_vtb_open/_decode/
// _poll/_render_frame/_release_frame) -- unlike media_video.mm's older
// MACAPK_VIDEO_SELFTEST, which demuxes+decodes+presents through the
// file-based macapk_vd_* engine, this exercises the SAME functions
// android.media.MediaCodec calls end to end (including the on-screen
// composite via CocoaPresentVideoFrame), using a local file instead of a
// live app's network-fetched stream. Verifies the one thing a standalone,
// windowless test (debug/video_toolbox_test.mm) structurally cannot: that a
// real decoded frame actually reaches a real on-screen CAMetalLayer. Gated,
// default off, zero cost when unset. Called once per drawn frame from
// cocoa_bridge.mm's drawInMTKView; feeds one more NAL each call (looping)
// and drains whatever output is ready, so the clip plays continuously for as
// long as the host app's window stays up.
void macapk_vtb_selftest_tick(const char* mimeC, const char* path) {
    static void* dec = nullptr;
    static std::vector<std::vector<uint8_t>> vclNals;
    static size_t nextNal = 0;
    static int64_t pts = 0;
    static int frameW = 0, frameH = 0;
    static bool failedOpen = false;
    if (failedOpen) return;
    if (!dec) {
        FILE* f = std::fopen(path, "rb");
        if (!f) { std::fprintf(stderr, "[video-toolbox] selftest: cannot open %s\n", path); failedOpen = true; return; }
        std::fseek(f, 0, SEEK_END);
        long len = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        std::vector<uint8_t> file(len > 0 ? (size_t)len : 0);
        if (len > 0) { size_t got = std::fread(file.data(), 1, (size_t)len, f); file.resize(got); }
        std::fclose(f);
        if (file.empty()) { failedOpen = true; return; }

        std::string mime = mimeC ? mimeC : "video/avc";
        bool hevc = (mime == "video/hevc");
        auto nals = SplitAnnexB(file.data(), file.size());
        std::vector<uint8_t> csd0, csd1;
        for (const auto& n : nals) {
            int t = hevc ? HevcNalType(n) : H264NalType(n);
            bool isVcl  = hevc ? (t <= 21) : (t == 1 || t == 5);
            bool isCsd0 = hevc ? (t == 32 || t == 33 || t == 34) : (t == 7 || t == 13);
            bool isCsd1 = !hevc && t == 8;
            std::vector<uint8_t> withSc = {0, 0, 0, 1};
            withSc.insert(withSc.end(), n.ptr, n.ptr + n.len);
            if (isCsd0) csd0.insert(csd0.end(), withSc.begin(), withSc.end());
            else if (isCsd1) csd1.insert(csd1.end(), withSc.begin(), withSc.end());
            else if (isVcl) vclNals.push_back(std::move(withSc));
        }
        int hw = 0;
        dec = macapk_vtb_open(mime.c_str(), 0, 0, csd0.data(), (int)csd0.size(),
                              csd1.empty() ? nullptr : csd1.data(), (int)csd1.size(),
                              /*wantSurface=*/1, &hw);
        if (!dec) {
            std::fprintf(stderr, "[video-toolbox] selftest: open(%s) failed\n", mime.c_str());
            failedOpen = true;
            return;
        }
        frameW = macapk_vtb_width(dec);
        frameH = macapk_vtb_height(dec);
        std::fprintf(stderr,
            "[video-toolbox] selftest: opened %s %dx%d hw=%s vclNals=%zu, presenting to screen\n",
            mime.c_str(), frameW, frameH, hw ? "yes" : "no", vclNals.size());
    }
    if (vclNals.empty()) return;
    macapk_vtb_decode(dec, vclNals[nextNal].data(), (int)vclNals[nextNal].size(), pts, 0);
    pts += 100000;
    nextNal = (nextNal + 1) % vclNals.size();
    void* pb = nullptr;
    int64_t fpts = 0;
    int flags = 0;
    while (macapk_vtb_poll(dec, &pb, &fpts, &flags) == 1) {
        if (pb) {
            macapk_vtb_render_frame(pb, frameW, frameH);
            macapk_vtb_release_frame(pb);
        }
    }
}

// ---------------------------------------------------------------------------
// VideoToolbox Encoder C ABI
// ---------------------------------------------------------------------------

int macapk_vtb_enc_probe_mime(const char* mimeC, int* outHw) {
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!EncMimeToFourcc(mime, &fourcc)) {
        if (outHw) *outHw = 0;
        return 0;
    }
    ProbeResult r = ProbeEncodeFourcc(fourcc);
    if (outHw) *outHw = r.hw ? 1 : 0;
    return r.supported ? 1 : 0;
}

void* macapk_vtb_enc_open(const char* mimeC, int width, int height,
                          int bitrate, float frameRate, float iFrameIntervalSec,
                          int profile, int bitrateMode,
                          JavaVM* jvm, jobject callbackTarget, jmethodID onOutputMethod) {
    if (width <= 0 || height <= 0) {
        std::fprintf(stderr, "[video-toolbox] enc_open: invalid dimensions %dx%d\n", width, height);
        return nullptr;
    }
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!EncMimeToFourcc(mime, &fourcc)) {
        std::fprintf(stderr, "[video-toolbox] enc_open: unsupported mime '%s'\n", mime.c_str());
        return nullptr;
    }

    VtEncoder* e = new VtEncoder();
    e->codecType = fourcc;
    e->width = width;
    e->height = height;
    e->fps = (frameRate > 0.0f) ? frameRate : 30.0f;
    e->jvm = jvm;
    e->javaTarget = callbackTarget;
    e->onOutputMethod = onOutputMethod;

    VTCompressionSessionRef session = nullptr;
    bool hw = false;
    @autoreleasepool {
        NSDictionary* spec = @{
            (id)kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: @YES,
        };
        NSDictionary* srcAttrs = @{
            (id)kCVPixelBufferWidthKey: @(width),
            (id)kCVPixelBufferHeightKey: @(height),
            (id)kCVPixelBufferPixelFormatTypeKey: @[
                @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                @(kCVPixelFormatType_420YpCbCr8Planar),
                @(kCVPixelFormatType_32BGRA),
                @(kCVPixelFormatType_32ARGB),
            ],
            (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        };
        OSStatus s = VTCompressionSessionCreate(
            kCFAllocatorDefault, width, height, fourcc,
            (__bridge CFDictionaryRef)spec, (__bridge CFDictionaryRef)srcAttrs,
            kCFAllocatorDefault, EncOutputCallback, e, &session);
        if (s != noErr || !session) {
            s = VTCompressionSessionCreate(
                kCFAllocatorDefault, width, height, fourcc,
                nullptr, (__bridge CFDictionaryRef)srcAttrs,
                kCFAllocatorDefault, EncOutputCallback, e, &session);
        }
        if (s != noErr || !session) {
            char fcc[5]; FourccToChars(fourcc, fcc);
            std::fprintf(stderr,
                "[video-toolbox] enc_open: VTCompressionSessionCreate(%s, %dx%d) failed %d\n",
                fcc, width, height, (int)s);
            delete e;
            return nullptr;
        }

        CFBooleanRef hwFlag = nullptr;
        if (VTSessionCopyProperty(
                session, kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
                kCFAllocatorDefault, &hwFlag) == noErr && hwFlag) {
            hw = CFBooleanGetValue(hwFlag);
            CFRelease(hwFlag);
        }

        auto setIntProp = [&](CFStringRef key, int val) {
            CFNumberRef num = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &val);
            if (num) {
                VTSessionSetProperty(session, key, num);
                CFRelease(num);
            }
        };
        auto setFloatProp = [&](CFStringRef key, float val) {
            CFNumberRef num = CFNumberCreate(kCFAllocatorDefault, kCFNumberFloatType, &val);
            if (num) {
                VTSessionSetProperty(session, key, num);
                CFRelease(num);
            }
        };

        VTSessionSetProperty(session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
        VTSessionSetProperty(session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
        setFloatProp(kVTCompressionPropertyKey_ExpectedFrameRate, e->fps);

        int effBitrate = bitrate > 0
            ? bitrate
            : std::max(500000, (int)std::min<int64_t>(20000000LL, (int64_t)width * (int64_t)height * 4LL));
        setIntProp(kVTCompressionPropertyKey_AverageBitRate, effBitrate);
        if (bitrateMode == 2 && effBitrate > 0) {
            int bytesPerSec = std::max(1024, effBitrate / 8);
            double oneSec = 1.0;
            CFNumberRef bytesNum = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &bytesPerSec);
            CFNumberRef durNum = CFNumberCreate(kCFAllocatorDefault, kCFNumberDoubleType, &oneSec);
            if (bytesNum && durNum) {
                const void* vals[2] = { bytesNum, durNum };
                CFArrayRef arr = CFArrayCreate(kCFAllocatorDefault, vals, 2, &kCFTypeArrayCallBacks);
                if (arr) {
                    VTSessionSetProperty(session, kVTCompressionPropertyKey_DataRateLimits, arr);
                    CFRelease(arr);
                }
            }
            if (bytesNum) CFRelease(bytesNum);
            if (durNum) CFRelease(durNum);
        }

        if (iFrameIntervalSec == 0.0f) {
            setIntProp(kVTCompressionPropertyKey_MaxKeyFrameInterval, 1);
        } else if (iFrameIntervalSec > 0.0f) {
            int gop = std::max(1, (int)llround((double)iFrameIntervalSec * (double)e->fps));
            setIntProp(kVTCompressionPropertyKey_MaxKeyFrameInterval, gop);
            setFloatProp(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, iFrameIntervalSec);
        }

        CFStringRef profLevel = nullptr;
        if (fourcc == kCMVideoCodecType_H264) {
            if (profile == 1 || profile == 65536) {
                profLevel = kVTProfileLevel_H264_Baseline_AutoLevel;
            } else if (profile == 2) {
                profLevel = kVTProfileLevel_H264_Main_AutoLevel;
            } else {
                profLevel = kVTProfileLevel_H264_High_AutoLevel;
            }
        } else if (fourcc == kCMVideoCodecType_HEVC) {
            profLevel = (profile == 2)
                ? kVTProfileLevel_HEVC_Main10_AutoLevel
                : kVTProfileLevel_HEVC_Main_AutoLevel;
        }
        if (profLevel) {
            VTSessionSetProperty(session, kVTCompressionPropertyKey_ProfileLevel, profLevel);
        }

        OSStatus ps = VTCompressionSessionPrepareToEncodeFrames(session);
        if (ps != noErr) {
            std::fprintf(stderr, "[video-toolbox] VTCompressionSessionPrepareToEncodeFrames warning %d\n", (int)ps);
        }
    }

    e->session = session;
    e->usingHardware = hw;
    std::fprintf(stderr, "[video-toolbox] enc_open: mime=%s %dx%d fps=%.1f hw=%s\n",
                 mime.c_str(), width, height, e->fps, hw ? "YES" : "no");
    return e;
}

int macapk_vtb_enc_eos(void* h) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e || !e->session) return -1;
    OSStatus s = VTCompressionSessionCompleteFrames(e->session, kCMTimeInvalid);
    bool emitted = false;
    {
        std::lock_guard<std::mutex> lk(e->mu);
        if (!e->eosEmitted) {
            e->eosEmitted = true;
            e->outQ.push_back({ {}, e->lastOrigPtsUs, kFlagEndOfStream });
            emitted = true;
        }
        e->cvInFlight.notify_all();
    }
    if (emitted) NotifyJavaOutput(e);
    return (s == noErr) ? 0 : -1;
}

int macapk_vtb_enc_encode_buf(void* h, const uint8_t* data, size_t len,
                              int colorFormat, int stride, int sliceHeight,
                              int64_t ptsUs, int eos) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e || !e->session) return -1;
    int rc = 0;
    if (data && len > 0) {
        CVPixelBufferRef pb = BuildPixelBufferFromBytes(
            e->width, e->height, colorFormat, stride, sliceHeight, data, len);
        if (!pb) return -2;
        rc = EncodePixelBuffer(e, pb, ptsUs);
        CVPixelBufferRelease(pb);
        if (rc != 0) return rc;
    }
    if (eos) {
        int erc = macapk_vtb_enc_eos(h);
        if (erc != 0 && rc == 0) rc = erc;
    }
    return rc;
}

int macapk_vtb_enc_encode_argb(void* h, const uint32_t* argb, size_t numPixels, int64_t ptsUs) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e || !e->session || !argb) return -1;
    CVPixelBufferRef pb = BuildPixelBufferFromArgb(e->width, e->height, argb, numPixels);
    if (!pb) return -2;
    int rc = EncodePixelBuffer(e, pb, ptsUs);
    CVPixelBufferRelease(pb);
    return rc;
}

int macapk_vtb_enc_poll(void* h, std::vector<uint8_t>* outData, int64_t* outPtsUs, int* outFlags) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e) return -1;
    std::lock_guard<std::mutex> lk(e->mu);
    if (e->outQ.empty()) return 0;
    VtEncoder::OutPacket pkt = std::move(e->outQ.front());
    e->outQ.pop_front();
    if (outData) *outData = std::move(pkt.data);
    if (outPtsUs) *outPtsUs = pkt.ptsUs;
    if (outFlags) *outFlags = pkt.flags;
    return 1;
}

int macapk_vtb_enc_get_csd0(void* h, std::vector<uint8_t>* out) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e || !out) return 0;
    std::lock_guard<std::mutex> lk(e->mu);
    *out = e->csd0;
    return out->empty() ? 0 : 1;
}

int macapk_vtb_enc_get_csd1(void* h, std::vector<uint8_t>* out) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e || !out) return 0;
    std::lock_guard<std::mutex> lk(e->mu);
    *out = e->csd1;
    return out->empty() ? 0 : 1;
}

void macapk_vtb_enc_request_kf(void* h) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e) return;
    e->forceKeyFrame.store(true, std::memory_order_release);
}

void macapk_vtb_enc_flush(void* h) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e) return;
    e->generation.fetch_add(1, std::memory_order_relaxed);
    if (e->session) {
        VTCompressionSessionCompleteFrames(e->session, kCMTimeInvalid);
    }
    std::lock_guard<std::mutex> lk(e->mu);
    e->outQ.clear();
    e->eosEmitted = false;
    e->csdSent = false;
    e->csd0.clear();
    e->csd1.clear();
    e->lastInternalPtsUs = INT64_MIN;
    e->lastOrigPtsUs = 0;
    e->inFlight.store(0, std::memory_order_relaxed);
    e->forceKeyFrame.store(true, std::memory_order_release);
    e->cvInFlight.notify_all();
}

void macapk_vtb_enc_close(void* h, JNIEnv* env) {
    VtEncoder* e = (VtEncoder*)h;
    if (!e) return;
    e->closing.store(true, std::memory_order_release);
    e->cvInFlight.notify_all();
    if (e->session) {
        VTCompressionSessionCompleteFrames(e->session, kCMTimeInvalid);
        VTCompressionSessionInvalidate(e->session);
        CFRelease(e->session);
        e->session = nullptr;
    }
    {
        std::lock_guard<std::mutex> clk(e->cbMu);
        if (e->javaTarget) {
            if (env) {
                env->DeleteWeakGlobalRef(e->javaTarget);
            } else if (e->jvm) {
                JNIEnv* curEnv = nullptr;
                if (e->jvm->GetEnv((void**)&curEnv, JNI_VERSION_1_6) == JNI_OK && curEnv) {
                    curEnv->DeleteWeakGlobalRef(e->javaTarget);
                }
            }
            e->javaTarget = nullptr;
        }
        e->onOutputMethod = nullptr;
        e->jvm = nullptr;
    }
    delete e;
}

}  // extern "C"

// ---------------------------------------------------------------------------
// JNI surface (com.macapk.loader.NativeMedia's video-side native methods).
// No JNIEXPORT/JNICALL decoration, matching media_decode.mm's style in this
// same package -- these are never auto-resolved (NativeMedia.java's own
// header comment: "the JVM does not auto-resolve Java_* on macOS"), only
// ever reached through the explicit RegisterNatives call below.
// ---------------------------------------------------------------------------

extern "C" {

jobjectArray Java_com_macapk_loader_NativeMedia_nativeVideoSupportedMimes(JNIEnv* env, jclass) {
    std::vector<std::string> mimes;
    for (const auto& e : kMimeTable) {
        if (ProbeFourcc(e.fourcc).supported) mimes.push_back(e.mime);
    }
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)mimes.size(), strCls, nullptr);
    for (size_t i = 0; i < mimes.size(); i++) {
        jstring s = env->NewStringUTF(mimes[i].c_str());
        env->SetObjectArrayElement(arr, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

jboolean Java_com_macapk_loader_NativeMedia_nativeVideoIsHardware(JNIEnv* env, jclass, jstring jmime) {
    if (!jmime) return JNI_FALSE;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    int hw = 0;
    macapk_vtb_probe_mime(mime, &hw);
    env->ReleaseStringUTFChars(jmime, mime);
    return hw ? JNI_TRUE : JNI_FALSE;
}

// The host-probed profile/level table (runtime/src/video_caps_probe.mm):
// [maxWidth, maxHeight, maxMacroblocks, nPairs, profile0, level0, ...] in
// Android CodecProfileLevel constants, or null when no per-profile probe
// exists for `mime` (MediaCodecList then keeps the profile-less entry).
static std::string VtcapsOsVersion() {
    char buf[128] = {};
    size_t sz = sizeof(buf) - 1;
    if (sysctlbyname("kern.osversion", buf, &sz, nullptr, 0) != 0 || sz == 0) {
        return "unknown";
    }
    std::string out(buf);
    while (!out.empty() && (out.back() == '\0' || out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
        out.pop_back();
    }
    return out.empty() ? std::string("unknown") : out;
}

static bool EnsureDirRecursive(const std::string& dir) {
    if (dir.empty()) return false;
    struct stat st{};
    if (stat(dir.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos && slash > 0) {
        if (!EnsureDirRecursive(dir.substr(0, slash))) return false;
    }
    if (mkdir(dir.c_str(), 0755) == 0 || errno == EEXIST) {
        return stat(dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
    }
    return false;
}

static std::string VtcapsPrimaryPath(std::string* outDir) {
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) return {};
    std::string dir = std::string(home) + "/Library/Caches/Mac.apk";
    if (outDir) *outDir = dir;
    return dir + "/vtcaps_v1.cache";
}

static std::string VtcapsFallbackPath() {
    return std::string("/tmp/macapk_vtcaps_v1_") + std::to_string((unsigned long)getuid()) + ".cache";
}

static bool ReadVtcapsFile(const std::string& path, const std::string& osver,
                           std::map<std::string, std::vector<int>>* out) {
    if (path.empty()) return false;
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return false;
    char line[4096];
    bool any = false;
    while (std::fgets(line, sizeof(line), f)) {
        size_t len = std::strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
        char* p1 = std::strchr(line, '\t');
        if (!p1) continue;
        *p1 = '\0';
        if (std::strcmp(line, "v1") != 0) continue;
        char* ver = p1 + 1;
        char* p2 = std::strchr(ver, '\t');
        if (!p2) continue;
        *p2 = '\0';
        if (osver != ver) continue;
        char* mimeStr = p2 + 1;
        char* p3 = std::strchr(mimeStr, '\t');
        if (!p3) continue;
        *p3 = '\0';
        if (!mimeStr[0]) continue;
        std::vector<int> vals;
        char* cur = p3 + 1;
        bool ok = true;
        while (*cur) {
            char* end = nullptr;
            long v = std::strtol(cur, &end, 10);
            if (end == cur) { ok = false; break; }
            vals.push_back((int)v);
            if (*end == ',') cur = end + 1;
            else if (*end == '\0') break;
            else { ok = false; break; }
        }
        if (ok && (int)vals.size() >= 4) {
            (*out)[mimeStr] = std::move(vals);
            any = true;
        }
    }
    std::fclose(f);
    return any;
}

static void LoadVtcapsFromDisk(const std::string& osver, std::map<std::string, std::vector<int>>* cache) {
    std::string primary = VtcapsPrimaryPath(nullptr);
    if (!ReadVtcapsFile(primary, osver, cache)) {
        ReadVtcapsFile(VtcapsFallbackPath(), osver, cache);
    }
}

static bool WriteVtcapsFileAtomic(const std::string& path, const std::string& osver,
                                  const std::map<std::string, std::vector<int>>& cache) {
    std::string tmp = path + ".tmp." + std::to_string((long long)getpid());
    FILE* f = std::fopen(tmp.c_str(), "w");
    if (!f) return false;
    for (const auto& kv : cache) {
        if ((int)kv.second.size() < 4) continue;
        std::fprintf(f, "v1\t%s\t%s\t", osver.c_str(), kv.first.c_str());
        for (size_t i = 0; i < kv.second.size(); ++i) {
            std::fprintf(f, "%s%d", (i == 0 ? "" : ","), kv.second[i]);
        }
        std::fputc('\n', f);
    }
    bool writeOk = (std::ferror(f) == 0);
    if (std::fclose(f) != 0) writeOk = false;
    if (!writeOk || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

static void SaveVtcapsToDisk(const std::string& osver, const std::map<std::string, std::vector<int>>& cache) {
    std::string dir;
    std::string primary = VtcapsPrimaryPath(&dir);
    if (!primary.empty() && EnsureDirRecursive(dir)) {
        std::map<std::string, std::vector<int>> merged = cache;
        ReadVtcapsFile(primary, osver, &merged);
        for (const auto& kv : cache) {
            if ((int)kv.second.size() >= 4) merged[kv.first] = kv.second;
        }
        if (WriteVtcapsFileAtomic(primary, osver, merged)) return;
    }
    std::string fallback = VtcapsFallbackPath();
    std::map<std::string, std::vector<int>> merged = cache;
    ReadVtcapsFile(fallback, osver, &merged);
    for (const auto& kv : cache) {
        if ((int)kv.second.size() >= 4) merged[kv.first] = kv.second;
    }
    WriteVtcapsFileAtomic(fallback, osver, merged);
}

// MediaCodecList caches the registry for the process, so this runs once per
// mime; the one-line summary is the evidence of what the host answered.
jintArray Java_com_macapk_loader_NativeMedia_nativeVideoCapabilities(JNIEnv* env, jclass, jstring jmime) {
    if (!jmime) return nullptr;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    if (!mime) return nullptr;   // OOM: a pending OutOfMemoryError, nothing to release
    std::string key(mime);
    env->ReleaseStringUTFChars(jmime, mime);

    static std::mutex s_capsMu;
    static std::map<std::string, std::vector<int>> s_capsCache;
    static bool s_diskLoaded = false;
    const char* diskEnv = std::getenv("MACAPK_VTCAPS_DISK_CACHE");
    const bool useDisk = !(diskEnv && std::strcmp(diskEnv, "0") == 0);
    const std::string osver = useDisk ? VtcapsOsVersion() : std::string();

    std::vector<int> cached;
    {
        std::lock_guard<std::mutex> lk(s_capsMu);
        if (useDisk && !s_diskLoaded) {
            LoadVtcapsFromDisk(osver, &s_capsCache);
            s_diskLoaded = true;
        }
        auto it = s_capsCache.find(key);
        if (it != s_capsCache.end()) {
            cached = it->second;
            if ((int)cached.size() >= 4 && useDisk) {
                static std::map<std::string, bool> s_loggedHit;
                if (!s_loggedHit[key]) {
                    s_loggedHit[key] = true;
                    std::fprintf(stderr,
                        "[video-toolbox] capabilities %s: max=%dx%d mbs=%d profile/level pairs=%d (cached, osv=%s)\n",
                        key.c_str(), cached[0], cached[1], cached[2], cached[3], osver.c_str());
                }
            }
        }
    }
    if (cached.empty()) {
        int buf[256];
        auto t0 = std::chrono::steady_clock::now();
        int n = macapk_vtcaps_probe(key.c_str(), buf, 256);
        long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t0).count();
        if (n >= 4) {
            std::fprintf(stderr,
                "[video-toolbox] capabilities %s: max=%dx%d mbs=%d profile/level pairs=%d (decode-probed, %ld ms)\n",
                key.c_str(), buf[0], buf[1], buf[2], buf[3], ms);
            cached.assign(buf, buf + n);
        }
        std::lock_guard<std::mutex> lk(s_capsMu);
        s_capsCache[key] = cached;
        if (useDisk && (int)cached.size() >= 4) {
            SaveVtcapsToDisk(osver, s_capsCache);
        }
    }
    if ((int)cached.size() < 4) return nullptr;
    jintArray arr = env->NewIntArray((jsize)cached.size());
    if (arr) env->SetIntArrayRegion(arr, 0, (jsize)cached.size(), cached.data());
    return arr;
}

jlong Java_com_macapk_loader_NativeMedia_nativeVidOpen(JNIEnv* env, jclass, jstring jmime,
                                                        jint width, jint height,
                                                        jbyteArray jcsd0, jbyteArray jcsd1,
                                                        jboolean wantSurface) {
    if (!jmime) return 0;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    std::vector<uint8_t> csd0, csd1;
    if (jcsd0) {
        jsize n = env->GetArrayLength(jcsd0);
        csd0.resize(n);
        if (n > 0) env->GetByteArrayRegion(jcsd0, 0, n, (jbyte*)csd0.data());
    }
    if (jcsd1) {
        jsize n = env->GetArrayLength(jcsd1);
        csd1.resize(n);
        if (n > 0) env->GetByteArrayRegion(jcsd1, 0, n, (jbyte*)csd1.data());
    }
    int hw = 0;
    void* h = macapk_vtb_open(mime, width, height,
                              csd0.empty() ? nullptr : csd0.data(), (int)csd0.size(),
                              csd1.empty() ? nullptr : csd1.data(), (int)csd1.size(),
                              wantSurface ? 1 : 0, &hw);
    env->ReleaseStringUTFChars(jmime, mime);
    return (jlong)(uintptr_t)h;
}

jint Java_com_macapk_loader_NativeMedia_nativeVidDecode(JNIEnv* env, jclass, jlong h,
                                                         jbyteArray jau, jlong ptsUs, jboolean eos) {
    std::vector<uint8_t> au;
    if (jau) {
        jsize n = env->GetArrayLength(jau);
        au.resize(n);
        if (n > 0) env->GetByteArrayRegion(jau, 0, n, (jbyte*)au.data());
    }
    return macapk_vtb_decode((void*)(uintptr_t)h, au.empty() ? nullptr : au.data(),
                             (int)au.size(), ptsUs, eos ? 1 : 0);
}

jlongArray Java_com_macapk_loader_NativeMedia_nativeVidPoll(JNIEnv* env, jclass, jlong h) {
    void* pb = nullptr;
    int64_t pts = 0;
    int flags = 0;
    int got = macapk_vtb_poll((void*)(uintptr_t)h, &pb, &pts, &flags);
    if (got <= 0) return nullptr;
    jlongArray arr = env->NewLongArray(3);
    jlong vals[3] = { (jlong)(uintptr_t)pb, (jlong)pts, (jlong)flags };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

jlongArray Java_com_macapk_loader_NativeMedia_nativeVidWaitPoll(JNIEnv* env, jclass, jlong h, jlong timeoutUs) {
    void* pb = nullptr;
    int64_t pts = 0;
    int flags = 0;
    int got = macapk_vtb_wait_poll((void*)(uintptr_t)h, &pb, &pts, &flags, (int64_t)timeoutUs);
    if (got <= 0) return nullptr;
    jlongArray arr = env->NewLongArray(3);
    jlong vals[3] = { (jlong)(uintptr_t)pb, (jlong)pts, (jlong)flags };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

jint Java_com_macapk_loader_NativeMedia_nativeVidQueueDepth(JNIEnv*, jclass, jlong h) {
    return (jint)macapk_vtb_queue_depth((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeVidRender(JNIEnv*, jclass, jlong pixbuf,
                                                         jint width, jint height) {
    macapk_vtb_render_frame((void*)(uintptr_t)pixbuf, width, height);
}

static void PresentVideoFrameToSurfaceDispatch(void* pixbuf, int width, int height,
                                                jobject target, int64_t presentationTimeNs,
                                                bool hasExplicitTs) {
    if (hasExplicitTs) {
        using PresentAtTimeFn = void (*)(void*, int, int, jobject, int64_t, bool);
        static PresentAtTimeFn s_fn = reinterpret_cast<PresentAtTimeFn>(
            dlsym(RTLD_DEFAULT, "CocoaPresentVideoFrameToSurfaceAtTime"));
        if (s_fn) {
            s_fn(pixbuf, width, height, target, presentationTimeNs, true);
            return;
        }
    }
    CocoaPresentVideoFrameToSurface(pixbuf, width, height, target);
}

void Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface(JNIEnv*, jclass, jlong pixbuf,
                                                                jint width, jint height, jobject target) {
    if (!pixbuf) return;
    s_vtb_rendered.fetch_add(1, std::memory_order_relaxed);
    PresentVideoFrameToSurfaceDispatch((void*)(uintptr_t)pixbuf, width, height, target, 0, false);
}

void Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface__JIILandroid_graphics_SurfaceTexture_2(
        JNIEnv* env, jclass cls, jlong pixbuf, jint width, jint height, jobject target) {
    Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface(env, cls, pixbuf, width, height, target);
}

void Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface__JIILandroid_graphics_SurfaceTexture_2J(
        JNIEnv*, jclass, jlong pixbuf, jint width, jint height, jobject target, jlong presentationTimeNs) {
    if (!pixbuf) return;
    s_vtb_rendered.fetch_add(1, std::memory_order_relaxed);
    PresentVideoFrameToSurfaceDispatch(
        (void*)(uintptr_t)pixbuf, width, height, target, static_cast<int64_t>(presentationTimeNs), true);
}

jint Java_com_macapk_loader_NativeMedia_nativeVidExportIOSurfacePort(JNIEnv*, jclass, jlong pixbuf) {
    if (!pixbuf) return 0;
    CVPixelBufferRef pb = reinterpret_cast<CVPixelBufferRef>(static_cast<uintptr_t>(pixbuf));
    IOSurfaceRef io = CVPixelBufferGetIOSurface(pb);
    if (!io) return 0;
    mach_port_t port = IOSurfaceCreateMachPort(io);
    return (jint)port;
}

static void PresentIOSurfacePortImpl(jint machPort, jint width, jint height,
                                     jobject target, int64_t presentationTimeNs,
                                     bool hasExplicitTs) {
    mach_port_t port = static_cast<mach_port_t>(machPort);
    if (port == MACH_PORT_NULL) return;
    IOSurfaceRef io = IOSurfaceLookupFromMachPort(port);
    mach_port_deallocate(mach_task_self(), port);
    if (!io) return;
    CVPixelBufferRef pb = nullptr;
    @autoreleasepool {
        NSDictionary* attrs = @{ (id)kCVPixelBufferMetalCompatibilityKey: @YES };
        CVPixelBufferCreateWithIOSurface(kCFAllocatorDefault, io,
                                         (__bridge CFDictionaryRef)attrs, &pb);
    }
    CFRelease(io);
    if (!pb) return;
    s_vtb_rendered.fetch_add(1, std::memory_order_relaxed);
    PresentVideoFrameToSurfaceDispatch((void*)pb, width, height, target, presentationTimeNs, hasExplicitTs);
    CFRelease(pb);
}

void Java_com_macapk_loader_NativeMedia_nativeVidPresentIOSurfacePort(JNIEnv*, jclass, jint machPort,
                                                                     jint width, jint height, jobject target) {
    PresentIOSurfacePortImpl(machPort, width, height, target, 0, false);
}

void Java_com_macapk_loader_NativeMedia_nativeVidPresentIOSurfacePort__IIILandroid_graphics_SurfaceTexture_2(
        JNIEnv*, jclass, jint machPort, jint width, jint height, jobject target) {
    PresentIOSurfacePortImpl(machPort, width, height, target, 0, false);
}

void Java_com_macapk_loader_NativeMedia_nativeVidPresentIOSurfacePort__IIILandroid_graphics_SurfaceTexture_2J(
        JNIEnv*, jclass, jint machPort, jint width, jint height, jobject target, jlong presentationTimeNs) {
    PresentIOSurfacePortImpl(machPort, width, height, target, static_cast<int64_t>(presentationTimeNs), true);
}

void Java_com_macapk_loader_NativeMedia_nativeVidReleaseFrame(JNIEnv*, jclass, jlong pixbuf) {
    macapk_vtb_release_frame((void*)(uintptr_t)pixbuf);
}

// outWhs is a caller-allocated int[3]; filled with {width, height, stride} on
// success. outBuf must already be sized >= stride*height (the caller learns
// stride from getOutputFormat()'s KEY_WIDTH/KEY_HEIGHT-derived estimate, or
// simply over-allocates width*height*4 which is >= any BGRA stride).
jint Java_com_macapk_loader_NativeMedia_nativeVidFrameBgra(JNIEnv* env, jclass, jlong pixbuf,
                                                            jbyteArray jout, jintArray jwhs) {
    if (!jout || !jwhs) return -1;
    jsize cap = env->GetArrayLength(jout);
    std::vector<uint8_t> buf(cap);
    int w = 0, h = 0, stride = 0;
    int rc = macapk_vtb_frame_copy_bgra((void*)(uintptr_t)pixbuf, buf.data(), cap, &w, &h, &stride);
    if (rc == 0) {
        env->SetByteArrayRegion(jout, 0, cap, (const jbyte*)buf.data());
        jint whs[3] = { w, h, stride };
        env->SetIntArrayRegion(jwhs, 0, 3, whs);
    }
    return rc;
}

void Java_com_macapk_loader_NativeMedia_nativeVidFlush(JNIEnv*, jclass, jlong h) {
    macapk_vtb_flush((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeVidClose(JNIEnv*, jclass, jlong h) {
    macapk_vtb_close((void*)(uintptr_t)h);
}

jobjectArray Java_com_macapk_loader_NativeMedia_nativeVideoEncodeSupportedMimes(JNIEnv* env, jclass) {
    std::vector<std::string> mimes;
    for (const auto& e : kEncMimeTable) {
        if (ProbeEncodeFourcc(e.fourcc).supported) mimes.push_back(e.mime);
    }
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)mimes.size(), strCls, nullptr);
    for (size_t i = 0; i < mimes.size(); i++) {
        jstring s = env->NewStringUTF(mimes[i].c_str());
        env->SetObjectArrayElement(arr, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

jboolean Java_com_macapk_loader_NativeMedia_nativeVideoEncodeIsHardware(JNIEnv* env, jclass, jstring jmime) {
    if (!jmime) return JNI_FALSE;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    int hw = 0;
    macapk_vtb_enc_probe_mime(mime, &hw);
    env->ReleaseStringUTFChars(jmime, mime);
    return hw ? JNI_TRUE : JNI_FALSE;
}

jlong Java_com_macapk_loader_NativeMedia_nativeVidEncOpen(JNIEnv* env, jclass, jstring jmime,
                                                            jint width, jint height,
                                                            jint bitrate, jfloat frameRate,
                                                            jfloat iFrameIntervalSec,
                                                            jint profile, jint bitrateMode,
                                                            jobject callbackTarget) {
    if (!jmime) return 0;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    if (!mime) return 0;
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    jobject weakTarget = nullptr;
    jmethodID onOutputMethod = nullptr;
    if (callbackTarget) {
        jclass cls = env->GetObjectClass(callbackTarget);
        if (cls) {
            onOutputMethod = env->GetMethodID(cls, "macapkOnNativeOutput", "()V");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                onOutputMethod = nullptr;
            }
            env->DeleteLocalRef(cls);
        }
        if (onOutputMethod) {
            weakTarget = env->NewWeakGlobalRef(callbackTarget);
        }
    }
    void* h = macapk_vtb_enc_open(mime, width, height, bitrate, frameRate, iFrameIntervalSec,
                                  profile, bitrateMode, jvm, weakTarget, onOutputMethod);
    if (!h && weakTarget) {
        env->DeleteWeakGlobalRef(weakTarget);
    }
    env->ReleaseStringUTFChars(jmime, mime);
    return (jlong)(uintptr_t)h;
}

jint Java_com_macapk_loader_NativeMedia_nativeVidEncEncodeBuffer(JNIEnv* env, jclass, jlong h,
                                                                    jobject jdirect, jbyteArray jbytes,
                                                                    jint offset, jint size,
                                                                    jint colorFormat, jint stride, jint sliceHeight,
                                                                    jlong ptsUs, jboolean eos) {
    if (!h) return -1;
    if (jdirect && size > 0) {
        uint8_t* ptr = (uint8_t*)env->GetDirectBufferAddress(jdirect);
        jlong cap = env->GetDirectBufferCapacity(jdirect);
        if (!ptr || offset < 0 || size < 0 || (jlong)offset + (jlong)size > cap) return -2;
        return macapk_vtb_enc_encode_buf((void*)(uintptr_t)h, ptr + offset, (size_t)size,
                                         colorFormat, stride, sliceHeight, (int64_t)ptsUs, eos ? 1 : 0);
    }
    std::vector<uint8_t> tmp;
    if (jbytes && size > 0) {
        jsize arrLen = env->GetArrayLength(jbytes);
        if (offset < 0 || size < 0 || (int64_t)offset + (int64_t)size > (int64_t)arrLen) return -2;
        tmp.resize((size_t)size);
        env->GetByteArrayRegion(jbytes, offset, size, (jbyte*)tmp.data());
    }
    return macapk_vtb_enc_encode_buf((void*)(uintptr_t)h,
                                     tmp.empty() ? nullptr : tmp.data(), tmp.size(),
                                     colorFormat, stride, sliceHeight, (int64_t)ptsUs, eos ? 1 : 0);
}

jint Java_com_macapk_loader_NativeMedia_nativeVidEncEncodeArgb(JNIEnv* env, jclass, jlong h,
                                                                  jintArray jargb, jint width, jint height,
                                                                  jlong ptsUs) {
    if (!h || !jargb || width <= 0 || height <= 0) return -1;
    jsize len = env->GetArrayLength(jargb);
    if (len <= 0) return -2;
    std::vector<uint32_t> tmp((size_t)len);
    env->GetIntArrayRegion(jargb, 0, len, (jint*)tmp.data());
    return macapk_vtb_enc_encode_argb((void*)(uintptr_t)h, tmp.data(), (size_t)len, (int64_t)ptsUs);
}

jint Java_com_macapk_loader_NativeMedia_nativeVidEncEndOfStream(JNIEnv*, jclass, jlong h) {
    return macapk_vtb_enc_eos((void*)(uintptr_t)h);
}

jbyteArray Java_com_macapk_loader_NativeMedia_nativeVidEncPoll(JNIEnv* env, jclass, jlong h, jlongArray jmeta) {
    std::vector<uint8_t> data;
    int64_t ptsUs = 0;
    int flags = 0;
    int got = macapk_vtb_enc_poll((void*)(uintptr_t)h, &data, &ptsUs, &flags);
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

jbyteArray Java_com_macapk_loader_NativeMedia_nativeVidEncCsd0(JNIEnv* env, jclass, jlong h) {
    std::vector<uint8_t> c;
    if (!macapk_vtb_enc_get_csd0((void*)(uintptr_t)h, &c) || c.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)c.size());
    if (arr) env->SetByteArrayRegion(arr, 0, (jsize)c.size(), (const jbyte*)c.data());
    return arr;
}

jbyteArray Java_com_macapk_loader_NativeMedia_nativeVidEncCsd1(JNIEnv* env, jclass, jlong h) {
    std::vector<uint8_t> c;
    if (!macapk_vtb_enc_get_csd1((void*)(uintptr_t)h, &c) || c.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)c.size());
    if (arr) env->SetByteArrayRegion(arr, 0, (jsize)c.size(), (const jbyte*)c.data());
    return arr;
}

void Java_com_macapk_loader_NativeMedia_nativeVidEncRequestKeyFrame(JNIEnv*, jclass, jlong h) {
    macapk_vtb_enc_request_kf((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeVidEncFlush(JNIEnv*, jclass, jlong h) {
    macapk_vtb_enc_flush((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeVidEncClose(JNIEnv* env, jclass, jlong h) {
    macapk_vtb_enc_close((void*)(uintptr_t)h, env);
}

}  // extern "C"

namespace macapk { namespace video {

static const JNINativeMethod kNativeVideoMethods[] = {
    {(char*)"nativeVideoSupportedMimes", (char*)"()[Ljava/lang/String;",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoSupportedMimes},
    {(char*)"nativeVideoIsHardware",     (char*)"(Ljava/lang/String;)Z",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoIsHardware},
    {(char*)"nativeVideoCapabilities",   (char*)"(Ljava/lang/String;)[I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoCapabilities},
    {(char*)"nativeVidOpen",             (char*)"(Ljava/lang/String;II[B[BZ)J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidOpen},
    {(char*)"nativeVidDecode",           (char*)"(J[BJZ)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidDecode},
    {(char*)"nativeVidPoll",             (char*)"(J)[J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidPoll},
    {(char*)"nativeVidWaitPoll",         (char*)"(JJ)[J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidWaitPoll},
    {(char*)"nativeVidQueueDepth",       (char*)"(J)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidQueueDepth},
    {(char*)"nativeVidRender",           (char*)"(JII)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidRender},
    {(char*)"nativeVidRenderToSurface",  (char*)"(JIILandroid/graphics/SurfaceTexture;)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface},
    {(char*)"nativeVidRenderToSurface",  (char*)"(JIILandroid/graphics/SurfaceTexture;J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidRenderToSurface__JIILandroid_graphics_SurfaceTexture_2J},
    {(char*)"nativeVidExportIOSurfacePort", (char*)"(J)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidExportIOSurfacePort},
    {(char*)"nativeVidPresentIOSurfacePort", (char*)"(IIILandroid/graphics/SurfaceTexture;)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidPresentIOSurfacePort},
    {(char*)"nativeVidPresentIOSurfacePort", (char*)"(IIILandroid/graphics/SurfaceTexture;J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidPresentIOSurfacePort__IIILandroid_graphics_SurfaceTexture_2J},
    {(char*)"nativeVidReleaseFrame",     (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidReleaseFrame},
    {(char*)"nativeVidFrameBgra",        (char*)"(J[B[I)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidFrameBgra},
    {(char*)"nativeVidFlush",            (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidFlush},
    {(char*)"nativeVidClose",            (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidClose},
    {(char*)"nativeVideoEncodeSupportedMimes", (char*)"()[Ljava/lang/String;",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoEncodeSupportedMimes},
    {(char*)"nativeVideoEncodeIsHardware",     (char*)"(Ljava/lang/String;)Z",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoEncodeIsHardware},
    {(char*)"nativeVidEncOpen",                (char*)"(Ljava/lang/String;IIIFFIILjava/lang/Object;)J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncOpen},
    {(char*)"nativeVidEncEncodeBuffer",        (char*)"(JLjava/nio/ByteBuffer;[BIIIIIJZ)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncEncodeBuffer},
    {(char*)"nativeVidEncEncodeArgb",          (char*)"(J[IIIJ)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncEncodeArgb},
    {(char*)"nativeVidEncEndOfStream",         (char*)"(J)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncEndOfStream},
    {(char*)"nativeVidEncPoll",                (char*)"(J[J)[B",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncPoll},
    {(char*)"nativeVidEncCsd0",                (char*)"(J)[B",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncCsd0},
    {(char*)"nativeVidEncCsd1",                (char*)"(J)[B",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncCsd1},
    {(char*)"nativeVidEncRequestKeyFrame",     (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncRequestKeyFrame},
    {(char*)"nativeVidEncFlush",               (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncFlush},
    {(char*)"nativeVidEncClose",               (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidEncClose},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot, right
// alongside media::RegisterNativeMediaMethods (media_decode.mm's audio
// registration for the SAME NativeMedia class -- two separate RegisterNatives
// calls against one jclass is the existing, safe pattern this codebase
// already uses for NativeLog's kNativeLogMethods/kNativeQos).
void RegisterNativeVideoMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeMedia");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(NativeMedia) failed (video)\n");
        env->ExceptionClear();
        return;
    }
    int count = sizeof(kNativeVideoMethods) / sizeof(kNativeVideoMethods[0]);
    if (env->RegisterNatives(cls, kNativeVideoMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(NativeMedia video) failed\n");
        env->ExceptionClear();
    } else {
        std::fprintf(stderr, "[I] NativeMedia: registered %d video methods (VideoToolbox)\n", count);
    }
    env->DeleteLocalRef(cls);
}

}}  // namespace macapk::video
