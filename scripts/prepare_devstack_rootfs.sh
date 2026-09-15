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
require_space() {
    local needed_mb=$1
    local available_kb=$(df -k "$BUILD_DIR" | tail -1 | awk '{print $4}')
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
    log_warn "Docker not available. Attempting to download pre-built Ubuntu rootfs..."

    mkdir -p "$DEVSTACK_DIR"

    local url="https://github.com/termux/termux-root-packages/releases/download/ubuntu/ubuntu-noble-aarch64-minimal.tar.gz"

    if curl -L -o "$BUILD_DIR/$OUTPUT_TARBALL" "$url"; then
        log_info "Downloaded pre-built rootfs: $BUILD_DIR/$OUTPUT_TARBALL"
    else
        log_error "Cannot create devstack rootfs. Docker unavailable and download failed."
        log_error "Please install Docker or download Ubuntu ${UBUNTU_VERSION} ${UBUNTU_ARCH} rootfs manually."
        exit 1
    fi
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
