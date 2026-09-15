# Changelog

## v1.13

### New Features
- **Devstack (Android) variant** (`app.openminis.devstack` applicationId) — coexists with the public Alpine build on the same device via deterministic abstract socket names and port offsets.
- **Root Passthrough** (`android-root-cli`): privileged su-based command execution inside the PRoot sandbox. Toggle in Settings → Devstack → Root Passthrough. All commands are audited to `files/audit/root-YYYY-MM-DD.log` (120s timeout, exit codes logged).
- **Devstack Toolchain** (`devstack-toolchain`): on-device offline installer for platform-tools (adb/fastboot), OpenJDK, Gradle, and Node.js inside the PRoot sandbox. Free mode on Ubuntu 24.04 base image (bash preinstalled).
- **PRoot native-offload socket** now uses deterministic name `app.openminis.devstack` (configurable via `NATIVE_OFFLOAD_SOCKET_NAME` env var in `deps/build_proot.sh`).

### Android Build Changes
- `applicationId` changed to `app.openminis.devstack`
- `BuildConfig.DEBUG_SERVER_PORT` / `BuildConfig.DEBUG_OFFLOAD_PORT` added (port 6321, offset from public mirror's 5321)
- `SandboxProfile` abstraction: `Alpine` and `Devstack` variants with per-profile rootfs directory, shell, PATH, environment variables, and bind mounts
- `SandboxSettings`: SharedPreferences-based variant switch (set via `SandboxSettings.setVariant(context, "devstack")`)
- `PRootKernel.buildProotCommand()`: devstack uses `/bin/bash -c`, alpine uses `/bin/sh -c`
- `TerminalSession` / `PersistentShell`: default shell reads from `SandboxSettings.currentProfile().defaultShell`
- `OnDemandBash`: devstack shortcut — skips `apt install bash` (preinstalled on Ubuntu 24.04)

### CI/CD
- Added `.github/workflows/build-and-release.yml`: automated build and GitHub Release on tags
