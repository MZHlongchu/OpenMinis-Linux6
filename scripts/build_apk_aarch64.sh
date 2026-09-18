#!/usr/bin/env bash
# One-click aarch64 APK: PRoot + Gradle assembleRelease.
# Signing: if MINIS_UPLOAD_STORE_FILE is set, Gradle uses that keystore;
# otherwise the debug keystore is used (UpdateChecker cannot replace a
# differently-signed install).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
  exit 1
fi

if [[ -x "$ROOT/deps/build_proot.sh" ]]; then
  "$ROOT/deps/build_proot.sh"
fi

if [[ -x "$ROOT/scripts/prepare_android_sandbox.sh" ]]; then
  "$ROOT/scripts/prepare_android_sandbox.sh" || true
fi

GRADLEW="$ROOT/src/android/gradlew"
if [[ ! -x "$GRADLEW" ]]; then
  echo "missing $GRADLEW" >&2
  exit 1
fi
"$GRADLEW" -p "$ROOT/src/android" assembleRelease --no-daemon

OUT="$ROOT/dist"
mkdir -p "$OUT"
APK=$(find "$ROOT/src/android/app/build/outputs/apk/release" -name "*.apk" | head -n 1)
if [[ -n "$APK" ]]; then
  cp -f "$APK" "$OUT/openminis-aarch64.apk"
  echo "APK: $OUT/openminis-aarch64.apk"
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
  NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | tail -n 1 || true)
fi
UNWIND=""
if [[ -n "$NDK" ]]; then
  UNWIND=$(find "$NDK" -path "*aarch64-linux-android*" -name "libunwind.so" 2>/dev/null | head -n 1 || true)
fi
if [[ -n "$UNWIND" ]]; then
  cp -f "$UNWIND" "$OUT/libunwind.so"
  echo "libunwind: $OUT/libunwind.so"
else
  echo "libunwind.so not found in NDK — attach it as a release asset if you ship native crash dumps" >&2
fi
