# Android 16 framework comparison

The same [probe source](../../debug/fixtures/android_oracle/ContractProbe.java)
ran on Mac.apk's framework JAR and on Robolectric 4.16.1 with
`android-all-instrumented:16-robolectric-13921718-i7` (reported SDK 36).
The reference JAR SHA-256 was
`16f1f751643d1d3d5592008846bbdfc1e57cff15e6ec303d26584de3b6ac25ec`.
Both used OpenJDK 21.0.13 on macOS arm64. Mac.apk used JARS_ID
`995874ea82d2ff6b`. This probe exercises Android 16 framework behavior.
A device build fingerprint, Android VM, platform Looper, graphics, and real Binder
transport were outside the reference environment.

| Family | Mac.apk and Robolectric |
|---|---|
| Bundle / Parcel value round trip | text `ok`, component `Entry`, typed lookup rejects a string |
| Intent URI | `android.intent.action.VIEW`, package `com.example`, `https://example.org/path` |
| IntentFilter MIME / scheme | null-scheme match `6324224`, HTTP rejection `-2` |
| PatternMatcher advanced glob | `true,false,false,true` |

## Re-run

Compile `ContractProbe.java` with the Mac.apk framework JAR, then run it as a
class with that JAR on the classpath. Run the same source from a Robolectric
JUnit 4 test using `@Config(sdk = 36, manifest = Config.NONE)`. Print
`ORACLE sdk=` followed by `Build.VERSION.SDK_INT` in that test, then call
`ContractProbe.run()`. The local Maven setup, downloaded dependencies and raw
reports are under `work/upgrade/ORACLE-001/`.

```bash
python3 debug/fixtures/android_oracle/compare.py \
  work/upgrade/ORACLE-001/mac.log work/upgrade/ORACLE-001/android-final.log
```

The comparator requires exactly the four family names and API 36. All four
matched (rc=0). `--inject-difference` changed one reference record and produced
`DIFF pattern` (rc=1). No values were normalized or omitted.
