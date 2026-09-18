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

`scripts/build_apk_aarch64.sh` and CI copy NDK `libunwind.so` (aarch64) next
to the APK when present. It is a **release asset**, not packed into the APK.
If the NDK tree has no shared `libunwind.so`, the script prints a warning
and continues.
