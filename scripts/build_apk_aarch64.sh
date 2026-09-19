#!/usr/bin/env bash
# One-click aarch64 APK: PRoot + libunwind.a + Gradle assembleRelease.
# Signing: Gradle uses src/android/release.keystore via signing.properties
# by default. MINIS_UPLOAD_* env overrides. Debug keystore is last-resort
# (UpdateChecker cannot replace a differently-signed install).
set -euo pipefail
[ -d "${TMPDIR:-}" ] || export TMPDIR=/tmp
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

# Resolve NDK before CMake/Gradle so crash_handler can link _Unwind_*.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  local_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  NDK=$(ls -d "$local_sdk"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ERROR: Android NDK not found (set ANDROID_NDK_HOME or install ndk under ANDROID_HOME)" >&2
  exit 1
fi

if [[ ! -f "$ROOT/scripts/build_libunwind_aarch64.sh" ]]; then
  echo "ERROR: missing $ROOT/scripts/build_libunwind_aarch64.sh" >&2
  exit 1
fi
echo "==> 交叉编译并安装 libunwind.a -> $NDK sysroot"
bash "$ROOT/scripts/build_libunwind_aarch64.sh" "$NDK"

# 本机 NDK 预编译目录名仍是 linux-x86_64，但 clang++ 可能是包装脚本
# （调用系统 clang-18）。包装脚本若未带 -lunwind，链接期找不到刚装的 .a。
ensure_clangxx_lunwind() {
  local ndk="$1"
  local pre="$ndk/toolchains/llvm/prebuilt"
  [[ -d "$pre" ]] || return 0
  local f
  while IFS= read -r -d '' f; do
    [[ -f "$f" && ! -L "$f" ]] || continue
    if ! head -n 1 "$f" | grep -q bash; then
      continue
    fi
    if grep -q -- '-lunwind' "$f"; then
      continue
    fi
    if ! grep -q -- '-rtlib=compiler-rt' "$f"; then
      continue
    fi
    echo "==> 给包装脚本追加 -lunwind: $f"
    sed -i 's/-rtlib=compiler-rt/-rtlib=compiler-rt -lunwind/g' "$f"
  done < <(find "$pre" \( -name 'clang++' -o -name '*-clang++' \) -print0 2>/dev/null)
}
ensure_clangxx_lunwind "$NDK"

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
  echo "APK: $OUT/openminis-aarch64.apk ($(wc -c < "$APK") bytes)"
fi
