# 1.36.19-linux Release Notes

## 🚨 Critical User-Reported Fixes

This release addresses three severe usability issues reported by real users:

### Problem 1: Enormous Package List (30+ Minutes on Cellular)
**Before**: `minis-dev-setup` forced installation of ~800MB packages (gcc, ffmpeg, openjdk-21, golang, gradle)  
**After**: Split into lightweight (150MB, 30s) and full (opt-in, 10-20min)

### Problem 2: apt Blocks All Shell Commands
**Before**: apt holds lock indefinitely; all subsequent `shell_execute` timeout  
**After**: 5-minute timeout with clear error; non-apt commands run concurrently

### Problem 3: Cannot Kill Running Script
**Before**: `apt_try` retries 8 times; killing `apt-get` spawns new process  
**After**: SIGTERM handler for clean exit; no retry loops in lightweight version

## ✨ What's New

### Split Developer Setup

**`minis-dev-setup`** (lightweight, auto-run on boot):
- Only essentials: ca-certificates, curl, wget, python3, git, nodejs, psmisc, unzip
- ~150MB download, completes in 30-60 seconds
- 60s lock timeout (fail-fast if apt is busy)
- Non-blocking boot

**`minis-dev-setup-full`** (opt-in, manual run):
- All heavy toolchain: gcc/g++/make/cmake, ffmpeg, openjdk-21, golang, gradle
- ~800MB download, 10-20 minutes
- Run with: `minis-dev-setup-full` or `nohup minis-dev-setup-full &`
- 30-minute timeout, Ctrl+C interruptible

### apt Lock Timeout

**`SandboxResourceGate.kt`**:
```kotlin
withTimeout(5 * 60 * 1000L) { // 5 minutes
    aptMutex.withLock { block() }
}
```

Shell commands no longer hang indefinitely when apt is running. Clear error after 5 minutes:
```
apt/dpkg is busy for 5+ minutes (likely minis-dev-setup).
Kill the apt process and retry.
```

### SIGTERM Handler

Both scripts now handle termination gracefully:
```bash
trap 'minis_release_apt_lock; exit 143' TERM
```

`kill <pid>` releases the lock and exits cleanly (exit code 143).

### Optimized seedNetworkTools

**`RootfsManager.kt`**:
- Early exit if all essentials already present
- Node.js install is best-effort, doesn't block boot
- Clearer logging

## 📊 Performance Impact

| Metric | Before | After |
|--------|--------|-------|
| Boot install time | 10-30 min | 30-60 sec |
| Package size | ~800 MB | ~150 MB (light) / ~800 MB (full) |
| Shell blocking | Indefinite | 5 min timeout |
| Kill behavior | Orphan processes | Clean exit (143) |

## 🔧 Files Changed

- `minis-dev-setup` - Rewritten (56 lines, essentials only)
- `minis-dev-setup-full` - New script (97 lines, full toolchain)
- `SandboxResourceGate.kt` - Added 5-minute timeout for apt lock
- `RootfsManager.kt` - Early exit in seedNetworkTools
- `FIX-DEV-SETUP.md` - Full technical documentation

## 📦 Downloads

- **APK**: [minis-ultra-com.openminis.linux.apk](https://github.com/tall-1997/OpenMinis-Linux/releases/download/1.36.19-linux/minis-ultra-com.openminis.linux.apk)
- **libunwind**: [libunwind.a](https://github.com/tall-1997/OpenMinis-Linux/releases/download/1.36.19-linux/libunwind.a)

## 🧪 Testing

- [x] Lightweight seed completes in <60s on cellular
- [x] Shell commands don't timeout during apt operations
- [x] `kill <pid>` exits cleanly without orphan processes
- [x] Full toolchain installs all packages successfully
- [x] Lock timeout fires after 5 minutes with clear error

## ⚠️ Breaking Changes

**None**. Existing users see faster boot with smaller package set. Full toolchain is still available via `minis-dev-setup-full`.

## 🔗 Links

- **Full changelog**: [RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)
- **Technical details**: [FIX-DEV-SETUP.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/FIX-DEV-SETUP.md)
- **Commit**: [cb9c6ec3](https://github.com/tall-1997/OpenMinis-Linux/commit/cb9c6ec3)

---

**Risk**: 🟢 Low (defensive fixes; users who don't run dev-setup unaffected)  
**Priority**: P0 (critical usability fixes from real user feedback)
