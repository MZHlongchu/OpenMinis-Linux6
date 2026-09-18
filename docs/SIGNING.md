# APK signing (Minis Ultra)

`UpdateChecker` (1.17+) installs by replacing the existing package. Android
refuses that when the incoming APK is signed with a different certificate.
Debug-signed `assembleRelease` builds therefore **cannot** overlay a Play or
CI-signed install — uninstall first, or sign with the same upload keystore.

## Local / one-click aarch64

```
export MINIS_UPLOAD_STORE_FILE=/path/to/upload.jks
export MINIS_UPLOAD_STORE_PASSWORD=...
export MINIS_UPLOAD_KEY_ALIAS=...
export MINIS_UPLOAD_KEY_PASSWORD=...
bash scripts/build_apk_aarch64.sh
```

If those env vars are unset, Gradle still signs release with the debug
keystore (same as 1.18). Sideload is fine; in-app update against a
differently signed build is not.

## GitHub Actions

Optional repository secrets:

- `MINIS_UPLOAD_KEYSTORE_BASE64` — base64 of the `.jks` / `.keystore`
- `MINIS_UPLOAD_STORE_PASSWORD`
- `MINIS_UPLOAD_KEY_ALIAS`
- `MINIS_UPLOAD_KEY_PASSWORD`

Without them, rolling `android-latest` stays debug-signed.

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
