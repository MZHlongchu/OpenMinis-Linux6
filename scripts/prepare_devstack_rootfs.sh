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
UBUNTU_MIRROR="https://archive.ubuntu.com/ubuntu"

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

# ─── Step 2: Create Ubuntu rootfs via Docker ────────────────────────────────
create_with_docker() {
    log_info "Creating Ubuntu ${UBUNTU_VERSION} ${UBUNTU_ARCH} rootfs via Docker..."

    mkdir -p "$DEVSTACK_DIR"

    # Pull the arm64 Ubuntu image
    docker pull --platform linux/arm64 ubuntu:${UBUNTU_VERSION}

    # Create a temporary container
    local container_id=$(docker create --platform linux/arm64 ubuntu:${UBUNTU_VERSION})

    # Export filesystem as tar
    log_info "Exporting filesystem from container $container_id..."
    docker export "$container_id" > "$DEVSTACK_DIR/ubuntu-raw.tar"

    # Remove the container
    docker rm "$container_id" >/dev/null 2>&1 || true

    # ─── Step 3: Minify ─────────────────────────────────────────────────────
    log_info "Minifying rootfs (removing docs, locales, cache, etc.)..."

    mkdir -p "$DEVSTACK_DIR/minified"
    tar -xf "$DEVSTACK_DIR/ubuntu-raw.tar" -C "$DEVSTACK_DIR/minified"

    # Remove large unnecessary directories
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
        if [ -d "$DEVSTACK_DIR/minified/$d" ]; then
            rm -rf "$DEVSTACK_DIR/minified/$d"
        fi
    done

    # Remove *.pyc files
    find "$DEVSTACK_DIR/minified" -name "*.pyc" -delete 2>/dev/null || true
    find "$DEVSTACK_DIR/minified" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true

    # ─── Step 4: Repackage ──────────────────────────────────────────────────
    log_info "Repackaging as tar.gz..."

    cd "$DEVSTACK_DIR/minified"
    tar -czf "$BUILD_DIR/$OUTPUT_TARBALL" .

    local size_mb=$(du -m "$BUILD_DIR/$OUTPUT_TARBALL" | awk '{print $1}')
    log_info "Output: $BUILD_DIR/$OUTPUT_TARBALL (${size_mb}MB)"

    # Cleanup
    rm -rf "$DEVSTACK_DIR/ubuntu-raw.tar" "$DEVSTACK_DIR/minified"

    log_info "Devstack rootfs ready: $BUILD_DIR/$OUTPUT_TARBALL"
}

# ─── Fallback: Download pre-built tarball ────────────────────────────────────
create_from_download() {
    log_warn "Docker not available. Creating rootfs from official Ubuntu base tarball..."

    mkdir -p "$DEVSTACK_DIR"
    mkdir -p "$BUILD_DIR"

    # Use official Ubuntu base arm64 tarball. Verified live at time of writing.
    local url="https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    local temp_tar="$BUILD_DIR/ubuntu-base-24.04.5-base-arm64.tar.gz"
    local tmp_extract="$DEVSTACK_DIR/extract"
    local mini_dir="$DEVSTACK_DIR/minified"

    # Verify URL is reachable
    if ! curl -sI "$url" | head -n1 | grep -q "200"; then
        log_error "Ubuntu base URL not reachable: $url"
        log_error "Cannot create devstack rootfs."
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

    # Move extracted content into minified dir (ubuntu-base tar contains files at root)
    # Some archives may contain a top-level directory; handle both cases
    local has_root_dir=0
    if [ -d "$tmp_extract/ubuntu" ]; then
        mv "$tmp_extract"/* "$mini_dir"/ 2>/dev/null || true
        has_root_dir=1
    else
        cp -a "$tmp_extract"/. "$mini_dir"/
    fi

    # ─── Minify ──────────────────────────────────────────────────────────────
    log_info "Minifying rootfs..."
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
        if [ -d "$mini_dir/$d" ]; then
            rm -rf "$mini_dir/$d"
        fi
    done
    find "$mini_dir" -name "*.pyc" -delete 2>/dev/null || true
    find "$mini_dir" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true

    # ─── Add devstack markers and directories ───────────────────────────────
    log_info "Adding devstack markers and directories..."
    echo "aarch64" > "$mini_dir/.arch"

    mkdir -p "$mini_dir/var/minis/attachments"
    mkdir -p "$mini_dir/var/minis/offloads"
    mkdir -p "$mini_dir/var/minis/workspace"
    mkdir -p "$mini_dir/var/minis/skills"
    mkdir -p "$mini_dir/var/minis/memory"
    mkdir -p "$mini_dir/var/minis/shared"
    mkdir -p "$mini_dir/var/minis/mounts"
    mkdir -p "$mini_dir/opt/bin"

    # Write profile.d/devstack.sh
    mkdir -p "$mini_dir/etc/profile.d"
    cat > "$mini_dir/etc/profile.d/devstack.sh" <<'EOF'
# Devstack profile
export PS1="\[\033[01;32m\]\u@devstack\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "
export HISTFILE=/var/minis/workspace/.bash_history
export JAVA_HOME=/opt/android-sdk/jdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=/opt/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:$PATH
EOF

    # Write /etc/apt/sources.list (Huawei Cloud mirror for China users)
    cat > "$mini_dir/etc/apt/sources.list" <<'EOF'
deb http://repo.huaweicloud.com/ubuntu/ noble main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-updates main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-backports main restricted universe multiverse
deb http://repo.huaweicloud.com/ubuntu/ noble-security main restricted universe multiverse
EOF

    # ─── Repackage ───────────────────────────────────────────────────────────
    log_info "Repackaging as $BUILD_DIR/$OUTPUT_TARBALL..."
    # Use --hard-dereference to avoid proot --link2symlink edge cases
    (cd "$mini_dir" && tar --hard-dereference -czf "$BUILD_DIR/$OUTPUT_TARBALL" .)

    local size_mb=$(du -m "$BUILD_DIR/$OUTPUT_TARBALL" | awk '{print $1}')
    log_info "Output: $BUILD_DIR/$OUTPUT_TARBALL (${size_mb}MB)"

    # Cleanup temp files
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
