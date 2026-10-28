# macapk-dump

Dump decrypted DEX files from a rooted Android process (MuMu/rooted device).

This is the generalized version of the script used for the official CoolApk
16.6.5 (NetEase Yidun) dump.

## Key trick

Yidun sometimes maps a DEX so that the page begins at `magic + 4`:

```
page:   30 33 37 00 ...          # "037\0" (magic bytes 4..7)
header: dex\n037\0 ...           # real base is 4 bytes BEFORE the page
```

The script recognizes this, reads `file_size` at region offset `0x1C`, prepends
`dex\n`, and recomputes SHA-1/adler32. It also falls back to scanning the DEX
endian tag (`0x12345678`) for a normal or 4-byte-shifted header.

## Usage

```bash
adb root
adb connect 127.0.0.1:26624       # your device
./macapk-dump.py com.coolapk.market ./coolapk-dump
```

Then inspect each `classes*.dex` with:

```bash
python3 - <<'PY'
import struct
for p in ...
    d=open(p,'rb').read()
    print(p, struct.unpack('<I', d[0x60:0x64])[0], 'classes')
PY
```

## Notes

- Needs root (`adb root`) because it reads `/proc/<pid>/mem`.
- No Frida/BlackDex injection: works on packers that detect those tools.
- It skips file-backed regions; for Yidun the decrypted DEXes are anonymous rw-p.
- Some packers split DEXes into more than one page-sized region; every recovered
  region is written as its own `classesN.dex`.
- After dumping, replace the shell `classes.dex`, set the real Application in the
  manifest, remove the packer native libs, sign, and provide the original
  certificate to `macapk-signature-override.sh`.
