#!/bin/bash
#
# Ubuntu 24.04 (Noble) aarch64 Rootfs Preparation Script
# Produces a minified Ubuntu rootfs tarball for Android PRoot sandbox.
#
# Strategy:
#   1. Use Docker (ubuntu:24.04, arm64 platform) as the source.
#   2. Export filesystem, minify (remove docs, locales, etc.), repackage.
#   3. Fall back to downloading a pre-built tarball if Docker unavailable.
#
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DEPS_DIR="$PROJECT_ROOT/deps"
DEVSTACK_DIR="$DEPS_DIR/devstack"
BUILD_DIR="$PROJECT_ROOT/build"

UBUNTU_VERSION="24.04"
UBUNTU_CODENAME="noble"
UBUNTU_ARCH="aarch64"

OUTPUT_TARBALL="ubuntu-noble-aarch64.tar.gz"
OUTPUT_SIZE_MB=512

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ─── Step 1: check disk space ───────────────────────────────────────────────
mkdir -p "$BUILD_DIR"

require_space() {
    local needed_mb=$1
    local check_dir="$BUILD_DIR"
    [ -d "$check_dir" ] || check_dir="$(dirname "$BUILD_DIR")"
    local available_kb=$(df -k "$check_dir" | tail -1 | awk '{print $4}')
    local available_mb=$((available_kb / 1024))
    if [ "$available_mb" -lt "$needed_mb" ]; then
        log_error "Need ${needed_mb}MB free space, only ${available_mb}MB available in $BUILD_DIR"
        exit 1
    fi
}

require_space $OUTPUT_SIZE_MB

# ─── Common finalization applied to either source ───────────────────────────
# Both the Docker and download paths end by calling finalize_rootfs on the
# minified tree, so the devstack markers, apt sources, shell profile, tmp dir
# and (critically) the executable bits on dpkg's interpreter binaries are
# produced identically. Previously these lived only in the download path and
# used a `tar --hard-dereference`, which resolved /usr/bin/perl to a plain
# 0600 file and broke every perl maintainer script with exit 126.
finalize_rootfs() {
    local root="$1"

    log_info "Finalizing rootfs at $root..."

    # Devstack directories used by the sandbox.
    mkdir -p "$root/var/minis/attachments" \
             "$root/var/minis/offloads" \
             "$root/var/minis/workspace" \
             "$root/var/minis/skills" \
             "$root/var/minis/memory" \
             "$root/var/minis/shared" \
             "$root/var/minis/mounts" \
             "$root/opt/bin" \
             "$root/etc/profile.d"
    # A writable /tmp is required because guest TMPDIR points at it (see the
    # Bug-2 fix that stops TMPDIR leaking the host cache path into the guest).
    mkdir -p "$root/tmp"
    chmod 1777 "$root/tmp"

    echo "aarch64" > "$root/.arch"

    # Login-shell profile. JAVA_HOME tracks the distro default-jdk layout
    # (/usr/lib/jvm/default-java is symlinked by devstack-toolchain), and PATH
    # exports it so `java`/`javac` resolve immediately after an install. TMPDIR
    # is pinned to /tmp so mktemp/debconf work without a host bind mount.
    cat > "$root/etc/profile.d/devstack.sh" <<'EOF'
# Devstack profile
export PS1="\[\033[01;32m\]\u@devstack\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "
export HISTFILE=/var/minis/workspace/.bash_history
export TMPDIR=/tmp
export JAVA_HOME=/usr/lib/jvm/default-java
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:/opt/bin:$PATH"
EOF

    # APT sources use plain HTTP. The Ubuntu base ships without a populated
    # ca-certificates bundle and running `update-ca-certificates` requires a
    # working perl/dpkg (the very chain this script hardens), so we sidestep
    # TLS entirely rather than depend on it. Huawei Cloud mirror for China.
    cat > "$root/etc/apt/sources.list" <<'EOF'
deb http://repo.huaweicloud.com/ubuntu/ noble main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-updates main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-backports main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-security main restricted universe multiverse
EOF
    # Clear any cloud-image / security sources that would override the above
    # or switch back to an https URI that the guest cannot verify.
    rm -f "$root/etc/apt/sources.list.d/ubuntu.sources" 2>/dev/null || true

    fix_exec_bits "$root"

    # Hard gate: refuse to emit a rootfs whose critical interpreter is still
    # non-executable. This is the exact regression that produced the exit-126
    # apt wall; failing loudly here beats shipping a broken asset.
    if [ ! -x "$root/usr/bin/perl" ]; then
        log_error "Verification failed: usr/bin/perl is not executable"
        exit 1
    fi
    for chk in bin/sh bin/bash usr/bin/dpkg; do
        if [ -e "$root/$chk" ] && [ ! -x "$root/$chk" ]; then
            log_error "Verification failed: $chk is not executable"
            exit 1
        fi
    done

    log_info "Rootfs finalized and verified."
}

# Restore executable bits on the binaries and maintainer scripts dpkg's
# configure step execs. docker export and tar round-trips have been observed
# to strip +x from /usr/bin/perl (leaving it a 0600 regular file) and from
# debconf's frontend, which makes every perl `#!/usr/bin/perl` postinst die
# with "bad interpreter: Permission denied" (exit 126). bash/dash survived at
# 711 only because they are not the perl chain's dependencies.
fix_exec_bits() {
    local root="$1"

    # 1. The known-critical interpreters and dpkg/apt entrypoints. chmod follows
    #    symlinks, so this repairs a `perl -> perl5.38.2` link just as well as
    #    a dereferenced plain file.
    for b in \
        usr/bin/perl usr/bin/perl5.38.2 \
        bin/sh bin/dash bin/bash \
        usr/bin/dpkg usr/sbin/dpkg dpkg \
        usr/bin/apt usr/bin/apt-get usr/bin/apt-cache \
        usr/share/debconf/frontend usr/share/debconf/confmodule; do
        [ -e "$root/$b" ] && chmod 755 "$root/$b" 2>/dev/null || true
    done

    # 2. Program directories are meant to hold executables. A blanket +x over
    #    bin/sbin trees recovers anything the explicit list missed (perl5.38.2
    #    under a different name, shared dpkg helpers, etc.). Regular files only;
    #    symlinks are left intact so the device-side linker resolution still
    #    works and no dereference happens at pack time.
    find "$root/bin" "$root/sbin" "$root/usr/bin" "$root/usr/sbin" \
         -type f -exec chmod 755 {} + 2>/dev/null || true

    # 3. dpkg maintainer scripts (.postinst/.config/...) are execed by dpkg
    #    through their shebang interpreter but dpkg also stats the +x bit.
    find "$root/var/lib/dpkg/info" -type f \
         \( -name '*.postinst' -o -name '*.preinst' \
            -o -name '*.postrm' -o -name '*.prerm' \
            -o -name '*.config' -o -name '*.templates' \) \
         -exec chmod 755 {} + 2>/dev/null || true
}

# ─── Step 2: Create Ubuntu rootfs via Docker ────────────────────────────────
create_with_docker() {
    log_info "Creating Ubuntu ${UBUNTU_VERSION} ${UBUNTU_ARCH} rootfs via Docker..."

    mkdir -p "$DEVSTACK_DIR"

    docker pull --platform linux/arm64 ubuntu:${UBUNTU_VERSION}
    local container_id=$(docker create --platform linux/arm64 ubuntu:${UBUNTU_VERSION})

    log_info "Exporting filesystem from container $container_id..."
    docker export "$container_id" > "$DEVSTACK_DIR/ubuntu-raw.tar"
    docker rm "$container_id" >/dev/null 2>&1 || true

    log_info "Minifying rootfs (removing docs, locales, cache, etc.)..."
    mkdir -p "$DEVSTACK_DIR/minified"
    tar -xf "$DEVSTACK_DIR/ubuntu-raw.tar" -C "$DEVSTACK_DIR/minified"

    minify_tree "$DEVSTACK_DIR/minified"

    finalize_rootfs "$DEVSTACK_DIR/minified"

    log_info "Repackaging as tar.gz (preserving symlinks, no dereference)..."
    ( cd "$DEVSTACK_DIR/minified" && tar -czf "$BUILD_DIR/$OUTPUT_TARBALL" . )

    local size_mb=$(du -m "$BUILD_DIR/$OUTPUT_TARBALL" | awk '{print $1}')
    log_info "Output: $BUILD_DIR/$OUTPUT_TARBALL (${size_mb}MB)"

    rm -rf "$DEVSTACK_DIR/ubuntu-raw.tar" "$DEVSTACK_DIR/minified"
    log_info "Devstack rootfs ready: $BUILD_DIR/$OUTPUT_TARBALL"
}

# ─── Shared minify step ──────────────────────────────────────────────────────
minify_tree() {
    local root="$1"
    local removals=(
        "usr/share/doc"
        "usr/share/man"
        "usr/share/locale"
        "usr/share/i18n"
        "var/cache/apt/archives"
        "var/lib/apt/lists"
        "usr/share/info"
        "usr/share/groff"
        "usr/share/lintian"
        "usr/share/linda"
        "usr/lib/python3.12/__pycache__"
        "usr/lib/python3/dist-packages/__pycache__"
        "usr/lib/systemd/system"
        "etc/systemd"
        "usr/lib/firmware"
        "usr/share/fonts"
        "usr/lib/udev"
    )
    for d in "${removals[@]}"; do
        if [ -d "$root/$d" ]; then
            rm -rf "$root/$d"
        fi
    done
    find "$root" -name "*.pyc" -delete 2>/dev/null || true
    find "$root" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
}

# ─── Fallback: Download pre-built tarball ────────────────────────────────────
create_from_download() {
    log_warn "Docker not available. Creating rootfs from official Ubuntu base tarball..."

    mkdir -p "$DEVSTACK_DIR"
    mkdir -p "$BUILD_DIR"

    local url="https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    local temp_tar="$BUILD_DIR/ubuntu-base-24.04.5-base-arm64.tar.gz"
    local tmp_extract="$DEVSTACK_DIR/extract"
    local mini_dir="$DEVSTACK_DIR/minified"

    if ! curl -sI "$url" | head -n1 | grep -q "200"; then
        log_error "Ubuntu base URL not reachable: $url"
        exit 1
    fi

    log_info "Downloading Ubuntu base tarball from $url..."
    if ! curl -L -o "$temp_tar" "$url"; then
        log_error "Failed to download Ubuntu base tarball"
        exit 1
    fi

    log_info "Extracting base tarball..."
    rm -rf "$tmp_extract" "$mini_dir"
    mkdir -p "$tmp_extract" "$mini_dir"
    tar -xf "$temp_tar" -C "$tmp_extract"

    if [ -d "$tmp_extract/ubuntu" ]; then
        mv "$tmp_extract"/* "$mini_dir"/ 2>/dev/null || true
    else
        cp -a "$tmp_extract"/. "$mini_dir"/
    fi

    minify_tree "$mini_dir"
    finalize_rootfs "$mini_dir"

    log_info "Repackaging as tar.gz (preserving symlinks, no dereference)..."
    ( cd "$mini_dir" && tar -czf "$BUILD_DIR/$OUTPUT_TARBALL" . )

    local size_mb=$(du -m "$BUILD_DIR/$OUTPUT_TARBALL" | awk '{print $1}')
    log_info "Output: $BUILD_DIR/$OUTPUT_TARBALL (${size_mb}MB)"

    rm -f "$temp_tar"
    rm -rf "$tmp_extract" "$mini_dir"
    log_info "Devstack rootfs ready: $BUILD_DIR/$OUTPUT_TARBALL"
}

# ─── Main ────────────────────────────────────────────────────────────────────
if [ -f "$BUILD_DIR/$OUTPUT_TARBALL" ]; then
    log_info "Devstack rootfs already exists: $BUILD_DIR/$OUTPUT_TARBALL"
    log_info "Delete it to force regeneration."
    exit 0
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    create_with_docker
else
    create_from_download
fi
