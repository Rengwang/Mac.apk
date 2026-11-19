# Mac.apk Static APK & Native Dependency Preflight Scanner (`SCAN-001`)

- **Tool**: `scripts/scan_apk.py` (Scanner `1.0.0`)
- **Tests**: `debug/ScanApkTest.py` (7 self-contained unit/integration tests using synthetic in-memory/temp APKs, AXML, multi-DEX, ELF64/ELF32)
- **Baseline Reports**: [SCAN_REPORT.md](SCAN_REPORT.md) and [scan-report.json](scan-report.json)

## 1. Parser Architecture Comparison & Selection

Before writing `scripts/scan_apk.py`, we evaluated the repository's existing parsers alongside third-party binary analysis libraries and a pure-Python standard library implementation:

| Candidate | Scope & Strengths | Gaps / Constraints in Mac.apk Host Environment | Verdict |
|---|---|---|---|
| **Existing `com/macapk/dex/*` & `loader/*`** (`DexFile.java`, `ClassHierarchyOracle.java`, `BinaryXmlParser.java`, `ElfReader.java`) | Already in repo; `DexFile.java` parses DEX v035–040 containers; `BinaryXmlParser.java` decodes AXML + `WELL_KNOWN_ANDROID_ATTRS`; `ClassHierarchyOracle.java` resolves superclass/interface bits; `ElfReader.java` reads `DT_NEEDED` on ELF64 LE (`p_offset == p_vaddr`) and `readMaxVaddr` on ELF32 LE. | `ClassHierarchyOracle` only checks inheritance headers (no method/overload/field resolution or Dalvik instruction refs); `ElfReader` does not parse `.dynsym`/`.dynstr`/`.gnu.version`/`STB_WEAK`/transitive closure and assumes `p_offset == p_vaddr`; running Java helpers adds JVM startup/classpath coupling. | **Design reference & mirrored rules** (AXML well-known attrs, DEX header bounds), not used directly as scanner runtime. |
| **Androguard** (Apache-2.0) | Mature Python APK/AXML/DEX parser and call-graph engine. | **Not installed** on host (`find_spec('androguard') == None`); recent Androguard (`>= 4.1`) targets Python `>= 3.10`, whereas host `/usr/bin/python3` is `3.9.6`; large transitive dependency tree unsuitable for pinned offline CLI preflight. | **Rejected** for `SCAN-001`. |
| **LIEF** (Apache-2.0) | C++/Python binary parser for ELF/PE/Mach-O symbols, versions, and `DT_NEEDED`. | **Not installed** on host (`find_spec('lief') == None`); native wheel adds platform/ABI packaging dependency; still needs custom APK/AXML/DEX parser anyway. | **Rejected** for `SCAN-001`. |
| **Pure-Python 3.9+ stdlib** (`scripts/scan_apk.py` + `scripts/cap_registry.py`) | Zero external packages; runs directly on host Python `3.9.6`; parses ZIP/AXML/multi-DEX/Dalvik instructions and ELF64/ELF32 (`PT_DYNAMIC` / stripped `.dynsym` / `.gnu.version` / `.gnu.version_r`) in one deterministic process; reuses `cap_registry` `.class` parser to index host JDK `.jmod` files read-only. | Requires maintaining binary format decoders (~2.8 KLOC in one self-contained script), mitigated by targeted unit tests in `debug/ScanApkTest.py`. | **Selected**. |

### Pinned Dependencies & Licenses

| Component | Version / Anchor | License | Role & Network/Execution Guarantee |
|---|---|---|---|
| Host Python (`python3`) | `3.9.6` (stdlib only: `zipfile`, `zlib`, `struct`, `hashlib`, `json`, `resource`, etc.) | PSF License | Sole runtime interpreter; zero third-party PyPI packages. |
| `scripts/cap_registry.py` | `CAP-001` baseline (`SHA-256` `d4419fc1e229c04060e0f77bfde673a0b1fe5d0d0af5628bc673b0ecab4b71a1`) | Repo internal | `.class` parser (`parse_classfile`), deterministic JSON writer (`write_deterministic_json`, `canonical_json_bytes`). |
| `docs/compat/inventory.json` | SHA-256 `aeac3a08f01813669b340e899e4f035cbe04acfc394c3122205dd41be417511a` | Repo internal | `CAP-001` baseline (`6974` classes; `bionic_exports`: `956` `arm64_shim`, `1196` `direct_c_ndk`, `6` `adapters_in_arm64_native`, `28` `fmod_stub`, `4`+`17` per-image adapters, `2321` total nm exports). |
| `docs/compat/capabilities.json` | SHA-256 `b6908a469a9b2dc2d27e908297dcd0a295ec098ba05ac7c9ba93678231d27922` | Repo internal | `28` seed capabilities and `sdk_policy` (`default_sdk=29`, `floor_sdk=29`, `ceiling_sdk=36`, `max_known_sdk=37`). |
| `docs/compat/scenarios.json` | SHA-256 `80e65affd3462aa9f21b33eaf31231adafeb8b568d9faeeebd0211befcecebce` | Repo internal | Maps package names / IDs (`comp-ui-resources`, `comp-surface-media`, `comp-service-ipc-abi`) to scenario IDs. |
| Host JDK `jmods/*.jmod` (optional) | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` | GPLv2 with Classpath Exception | Read-only ZIP class-header indexing of standard `java.*`/`javax.*`/`jdk.*` APIs absent from `android-framework.jar`; `inventory.json` always takes precedence. Disable via `--no-host-jdk`. |

## 2. Safety, Determinism & Non-Execution Rules

`scripts/scan_apk.py` is strictly a **read-only static preflight tool**:

- **Zero code execution**: Never spawns guest JVMs, never loads `.dex` or invokes `<clinit>`, never calls `dlopen`/`LoadLibrary` or runs ELF `.init`/`.init_array`/`JNI_OnLoad`, and never modifies input APKs.
- **Zero network access**: Never fetches external symbols, SDK jars, or Maven metadata.
- **ZIP & parser bounds**:
  - Rejects non-regular files, path-traversal entries (`..`, leading `/`, backslash `\`, null bytes, Windows drive prefixes), and ZIP symlinks (`0xA000` Unix mode).
  - Enforces bounded chunked decompression (stops reading immediately when exceeding cap rather than materializing oversized entries).
  - Default caps (configurable via CLI):
    - `--max-apk-bytes`: `536870912` (512 MiB)
    - `--max-zip-entries`: `50000`
    - `--max-total-uncompressed`: `1073741824` (1 GiB declared uncompressed sum)
    - `--max-manifest-bytes`: `16777216` (16 MiB)
    - `--max-dex-bytes`: `67108864` (64 MiB)
    - `--max-elf-bytes`: `134217728` (128 MiB)
- **Exit codes** (compatibility gaps never trigger non-zero exits):
  - `0`: Scan succeeded cleanly (even when Java/native gaps, weak symbols, or blind spots are reported).
  - `2`: CLI argument error or unreadable/missing input file.
  - `3`: Archive security or size-cap violation (`ArchiveSecurityError`: bad ZIP, zip-slip traversal, symlink entry, size cap exceeded).
  - `4`: Binary format error (`BinaryFormatError`: corrupt AXML manifest, corrupt DEX header/strings/types/methods/bounds, or corrupt ELF header/tables).
  - `5`: Failed to load `inventory.json` / `capabilities.json` / `scenarios.json` (`RegistryLoadError`).
- **Determinism**: All collections are sorted by canonical keys and emitted via `cap_registry.write_deterministic_json()`. Variable runtime measurements (`wall_time_ms`, `peak_rss_bytes`, `peak_rss_mib`) are excluded from canonical JSON reports and written to `--telemetry-out` and `--out-md`.

## 3. CLI Usage

Scan individual APKs:

```bash
python3 scripts/scan_apk.py \
  path/to/app1.apk path/to/app2.apk \
  --out-json docs/compat/scan-report.json \
  --out-md docs/compat/SCAN_REPORT.md \
  --raw-dir work/upgrade/SCAN-001/reports \
  --telemetry-out work/upgrade/SCAN-001/telemetry.json
```

Scan a base APK with split APKs (`--split`) or treat all positional APKs as one split bundle (`--as-split-bundle`):

```bash
python3 scripts/scan_apk.py base.apk --split split_config.arm64_v8a.apk split_config.xxhdpi.apk --out-json report.json
python3 scripts/scan_apk.py base.apk split_a.apk --as-split-bundle --out-json report.json
```

Useful flags:
- `--inventory <path>` (default `docs/compat/inventory.json`)
- `--capabilities <path>` (default `docs/compat/capabilities.json`)
- `--scenarios <path>` (default `docs/compat/scenarios.json`)
- `--jdk-home <dir>` / `--no-host-jdk`
- `--max-apk-bytes`, `--max-zip-entries`, `--max-total-uncompressed`, `--max-manifest-bytes`, `--max-dex-bytes`, `--max-elf-bytes`

## 4. Analysis Semantics & Known Blind Spots

### Java / DEX / Manifest Analysis
1. **Binary `AndroidManifest.xml` (AXML)**: Extracts `package`, `split`, `versionCode`/`versionCodeMajor`, `minSdkVersion`, `targetSdkVersion`, `presented_sdk` (clamped by `capabilities.json` `sdk_policy`), `uses-feature` (including `glEsVersion`), `uses-library`, and components (`activity`, `activity-alias`, `service`, `receiver`, `provider`) including `:process` attributes (`multiprocess_component` -> `IPC-003`).
2. **Multi-DEX (`classes*.dex`)**: Decodes string pool (MUTF-8), type IDs, proto IDs, field IDs, method IDs, class definitions, encoded `class_data_item` methods/fields, and Dalvik bytecode instruction call/field/type references (`invoke-*`, `iget*`/`iput*`/`sget*`/`sput*`, `new-instance`, `check-cast`, `const-class`, etc.).
3. **Class Origin Classification**: Distinguishes `app_owned`, `androidx_bundled` vs `androidx_missing`, `public_sdk`, `hidden_api` (`com/macapk/*`, `android/app/ActivityThread`), `service_property` (`android/os/ServiceManager`, `android/os/SystemProperties`), `jdk_patched`, `host_jdk` vs `host_jdk_unindexed` (unknown), `platform_missing` (`android/*`, `com/android/*`, `dalvik/*`, `libcore/*`), and `unknown_external`.
4. **Hierarchy & Overload Resolution**: Walks `owner -> superclass -> interfaces` across merged `app_classes -> inv_classes -> jdk_classes` using full JVM descriptors (`name + (params)ret` for methods, `name + type` for fields).
   - Inherited methods found in a superclass or interface count as **present** (no false positive).
   - Matching method name with mismatched descriptor is reported as `missing` (`reason: "missing_overload"`) along with observed candidate descriptors on the hierarchy.
   - References whose ancestor chain terminates at an unindexed/external class are conservatively marked **`unknown`** rather than falsely missing.

### Native ELF64 / ELF32 (`.so`) Analysis
1. **Dynamic Symbol Extraction**: Supports both section-header (`SHT_DYNSYM` / `SHT_STRTAB` / `SHT_GNU_VERSYM` / `SHT_GNU_VERNEED`) and **stripped** `PT_DYNAMIC` + `PT_LOAD` (`vaddr -> file offset`) resolution for `DT_NEEDED`, `DT_SONAME`, `DT_SYMTAB`, `DT_STRTAB`, `DT_HASH` / `DT_GNU_HASH`, `DT_VERSYM`, `DT_VERNEED`, `DT_INIT` / `DT_INIT_ARRAY`.
2. **Transitive App-Bundled Closure**: Computes per-ABI `lib/<abi>/*.so` closure across `DT_NEEDED` edges:
   - Missing non-platform `DT_NEEDED` libraries are flagged (`missing_dt_needed`).
   - Symbols exported by the app's own transitive `.so` closure satisfy imports (`resolved_by_app`).
3. **Platform Symbol Matching**: Matches remaining imports against `docs/compat/inventory.json` (`bionic_exports`, including `arm64_shim`, `direct_c_ndk`, `adapters_in_arm64_native`, `fmod_stub`, and `arm64_per_image`):
   - `STB_WEAK` undefined imports are distinguished as `weak_unresolved` (non-fatal weak symbols) instead of hard `missing_symbols`.
   - `EM_AARCH64` / `arm64-v8a` imports absent from Bionic exports are reported as `missing_symbol`; non-arm64 ABI builds without native matching exports are marked `unknown_abi` rather than guessing parity.
4. **Static Blind Spots (Explicitly Marked `unknown`)**:
   - `dynamic_dex_loading` (`dalvik/system/DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader`, `DelegateLastClassLoader`)
   - `java_reflection` (`Class.forName`, `Class.getMethod`/`getDeclaredMethod`, `Method.invoke`, etc.)
   - `dynamic_code_string_hint` (URL or secondary `.dex`/`.jar`/`.apk`/`.so` path strings inside DEX)
   - `embedded_dynamic_payload` (secondary `.dex`/`.jar`/`.apk` outside root `classes*.dex`, or `.so` outside `lib/<abi>/`)
   - `native_dynamic_symbol_lookup` (`dlopen`, `dlsym`, `android_dlopen_ext` imports + `.rodata` hints)
   - `native_dynamic_jni_registration` (`JNI_OnLoad` exports or `RegisterNatives` references)
