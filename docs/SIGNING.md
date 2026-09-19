# APK signing (Minis Ultra)

`UpdateChecker` (1.17+) installs by replacing the existing package. Android
refuses that when the incoming APK is signed with a different certificate.

Release builds (`assembleRelease`, GitHub Actions `android-apk.yml`) use a
**committed upload keystore** so every cloud and local release APK shares one
identity:

- keystore: `src/android/release.keystore` (PKCS12)
- config: `src/android/signing.properties`
- alias: `openminis-release`
- cert SHA-1: `B4:74:34:85:37:94:EE:01:3D:20:D0:E9:5C:73:81:DB:DC:92:39:19`
- cert SHA-256: `DE:46:32:52:F7:E1:26:7F:7B:8C:1C:E2:00:74:3B:46:7C:41:CC:5A:FA:92:17:B8:BE:BB:51:65:27:23:E3:04`

If those files are missing, Gradle still signs release with the debug
keystore (same as 1.18). Sideload is fine; in-app update against a
differently signed build is not.

## Override (optional)

Environment variables win over `signing.properties`:

```
export MINIS_UPLOAD_STORE_FILE=/path/to/other.jks
export MINIS_UPLOAD_STORE_PASSWORD=...
export MINIS_UPLOAD_KEY_ALIAS=...
export MINIS_UPLOAD_KEY_PASSWORD=...
bash scripts/build_apk_aarch64.sh
```

GitHub Actions secrets `MINIS_UPLOAD_KEYSTORE_BASE64` /
`MINIS_UPLOAD_STORE_PASSWORD` / `MINIS_UPLOAD_KEY_ALIAS` /
`MINIS_UPLOAD_KEY_PASSWORD` also override the committed key, if set.

## libunwind

NDK r28+ no longer ships shared `libunwind.so`. `crash_handler.cpp` still
calls `_Unwind_Backtrace`, so `scripts/build_libunwind_aarch64.sh` cross-
compiles LLVM `libunwind.a` into the NDK sysroot. `scripts/build_apk_aarch64.sh`
and CI run that script before Gradle.

CMake must link the **aarch64** `libunwind.a` by absolute path. Never pass
bare `-lunwind`: NDK llvm prebuilt ships a *host* `libunwind.so` under
`toolchains/llvm/prebuilt/<host>/lib/`, which 1.20-linux CI linked by
mistake (`incompatible with aarch64linux`). Pin `ndkVersion` to the same
r28 folder CI installs. LLVM libunwind.a may embed a pthread dependent-libraries tag; Bionic has no libpthread, so crash_handler links with -Wl,--no-dependent-libraries.

CI also copies `libunwind.a` (and `libunwind.so` if the NDK still has it
under an aarch64 sysroot path) next to the APK as a **release asset**,
not packed into the APK.
