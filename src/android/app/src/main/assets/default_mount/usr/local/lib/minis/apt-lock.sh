# Shared guest apt lock. Source, do not execute.
# Order: acquire global lock → then fuser-check → then delete stale dpkg
# locks → then dpkg --configure -a. Reversing any step races.
#
# flock is tried first (util-linux). PRoot may no-op fcntl; fall back to
# POSIX mkdir which is atomic without depending on flock semantics.

MINIS_APT_LOCKFILE="${MINIS_APT_LOCKFILE:-/var/lock/sandbox-apt.lock}"
MINIS_APT_LOCKDIR="${MINIS_APT_LOCKDIR:-/var/lock/sandbox-apt.d}"

minis_clear_stale_dpkg_locks() {
    _lf=""
    for _lf in \
        /var/lib/dpkg/lock-frontend \
        /var/lib/dpkg/lock \
        /var/lib/apt/lists/lock \
        /var/cache/apt/archives/lock
    do
        [ -e "$_lf" ] || continue
        if command -v fuser >/dev/null 2>&1 && fuser "$_lf" >/dev/null 2>&1; then
            echo "minis-apt-lock: $_lf still held, skip" >&2
            continue
        fi
        rm -f "$_lf"
    done
    dpkg --configure -a || true
}

minis_acquire_apt_lock() {
    _timeout="${1:-180}"
    mkdir -p /var/lock /tmp /var/tmp 2>/dev/null || true
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$MINIS_APT_LOCKFILE" 2>/dev/null || true
        if flock -w "$_timeout" 9 2>/dev/null; then
            minis_clear_stale_dpkg_locks
            return 0
        fi
    fi
    _waited=0
    while ! mkdir "$MINIS_APT_LOCKDIR" 2>/dev/null; do
        sleep 1
        _waited=$((_waited + 1))
        if [ "$_waited" -ge "$_timeout" ]; then
            echo "minis-apt-lock: timeout waiting for $MINIS_APT_LOCKDIR" >&2
            return 1
        fi
        if [ -d "$MINIS_APT_LOCKDIR" ]; then
            _mtime=$(stat -c %Y "$MINIS_APT_LOCKDIR" 2>/dev/null || echo 0)
            _now=$(date +%s 2>/dev/null || echo 0)
            _age=$(( _now - _mtime ))
            if [ "$_age" -gt 1800 ]; then
                echo "minis-apt-lock: stale mkdir lock (${_age}s), removing" >&2
                rmdir "$MINIS_APT_LOCKDIR" 2>/dev/null || true
            fi
        fi
    done
    minis_clear_stale_dpkg_locks
    return 0
}

minis_release_apt_lock() {
    flock -u 9 2>/dev/null || true
    exec 9>&- 2>/dev/null || true
    rmdir "$MINIS_APT_LOCKDIR" 2>/dev/null || true
    return 0
}
