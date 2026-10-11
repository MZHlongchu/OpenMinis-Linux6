package com.openminis.app.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * [T-android-dynamic-island] Capability probe for the two "dynamic island"
 * surfaces this app speaks:
 *
 *  1. AOSP Android 16 (Baklava, API 36) "Live Updates" — the promoted-ongoing
 *     ProgressStyle chip. Requires SDK 36+ AND
 *     `NotificationManager.canPostPromotedNotifications()`: that answer
 *     reflects the *system + per-app user grant* (the user can disable Live
 *     Updates for this app from system settings), so it can flip at runtime.
 *     Hence the short-lived [capableCache] TTL and the explicit
 *     [invalidateCapabilityCache] from the settings screen's ON_RESUME
 *     re-probe — cached for the process lifetime, a revoked grant left the
 *     overlay suppressed while the system refused the chip (neither surface
 *     showed), and a later grant stayed invisible until the process died.
 *
 *  2. [T-android-hyperos-island] Xiaomi HyperOS 小米超级岛 — the first-party
 *     `miui.focus.param` protocol (see [HyperOsIsland]). Requires a Xiaomi/
 *     Redmi/POCO host whose OS speaks the OS3 island protocol. The per-app
 *     focus-notification *permission* is a separate slow binder probe, so it
 *     is NOT part of "capable" (the settings toggle stays usable); it gates
 *     [isDynamicIslandActive] via the cached [HyperOsIsland.focusPermissionCached],
 *     refreshed on background threads.
 *
 * The probe is cached only briefly (see [capableCache]): callers re-query it
 * each time the app becomes foreground-visible (spec §3) so a permission the
 * user toggled off in system settings is picked up without needing a process
 * restart.
 *
 * The HyperOS branch is checked FIRST: forked SystemUIs report
 * `canPostPromotedNotifications() == true` even though their renderer is not
 * the AOSP one — the AOSP probe alone would misroute a HyperOS device onto
 * the promoted-ProgressStyle path that [SystemUiHost] exists to avoid.
 *
 * As of 2026-07 the AOSP path only actually returns true on Pixel 6+ hardware
 * running the Android 16 QPR that shipped Live Updates; everywhere else the
 * guards short-circuit and the mutual-exclusion logic degrades cleanly to the
 * existing overlay + plain-notification behavior.
 */
object DynamicIslandSupport {

    private const val TAG = "DynamicIslandSupport"

    /**
     * True when this device can render a dynamic island right now — either
     * the AOSP Live-Updates grant or the HyperOS island protocol. Runtime-safe
     * on all API levels and all OEMs.
     */
    /**
     * [T-android-island-capability-cache] Memoized result of the capability
     * probe below. `null` until first asked.
     *
     * The probe is a binder round trip (`getSystemService` +
     * `canPostPromotedNotifications`). It was being re-asked TWICE per second for as long as a
     * task ran: the chip ticker calls `isPromotedChipActive()` each tick and
     * `buildPromotedNotification()` asks again while building the very
     * notification that tick posts. With decorative animations fixed, those
     * binder calls were the single largest remaining app-side cost in a
     * profile of a running tool (24 samples, the top two app frames).
     *
     * [T-android-island-capability-ttl] It CAN change while the process
     * lives: the answer reflects the per-app Live Updates grant the user
     * toggles in system settings (see the class KDoc). Cached forever, a
     * revoke left applyOverlayState suppressing the floating capsule while the
     * system refused the promoted chip — neither surface showed — and a later
     * grant stayed invisible until the process died. So the cache now expires
     * after [CAPABILITY_TTL_MS] and can be dropped explicitly via
     * [invalidateCapabilityCache] (the settings screen's ON_RESUME re-probe).
     * At 2 asks/second a 5 s TTL still removes ~90% of the binder calls the
     * memoization was introduced to save.
     */
    @Volatile
    private var capableCache: Boolean? = null

    /** `SystemClock.elapsedRealtime()` at which [capableCache] was filled. */
    @Volatile
    private var capableCachedAtMs: Long = 0L

    private const val CAPABILITY_TTL_MS = 5_000L

    /** Forget the cached capability so the next ask re-probes the platform. */
    fun invalidateCapabilityCache() {
        capableCache = null
    }

    private fun cacheCapability(capable: Boolean): Boolean {
        capableCachedAtMs = android.os.SystemClock.elapsedRealtime()
        capableCache = capable
        return capable
    }

    fun isDynamicIslandCapable(context: Context): Boolean {
        // [T-android-hyperos-island] Forked SystemUIs must be probed first:
        // see the class KDoc. HyperOS keeps its own OS3 protocol + focus
        // permission, so the AOSP promoted-ProgressStyle path never applies.
        if (HyperOsIsland.isHyperOsHost()) {
            return HyperOsIsland.isIslandCapable(context)
        }
        // Pre-36 is a property of the OS build and truly cannot flip; no
        // binder call is involved, so nothing to cache.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        capableCache?.let { cached ->
            val age = android.os.SystemClock.elapsedRealtime() - capableCachedAtMs
            if (age in 0 until CAPABILITY_TTL_MS) return cached
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            cacheCapability(nm?.canPostPromotedNotifications() == true)
        } catch (t: Throwable) {
            // Defensive: some early/partial Baklava builds may throw if the
            // feature isn't fully wired. Treat any failure as "not capable"
            // so we fall back to the overlay + plain-notification path.
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            cacheCapability(false)
        }
    }

    /**
     * True when a dynamic island should be the ACTIVE status surface: the
     * device is capable AND the user enabled the toggle. This is the single
     * predicate that (a) selects the promoted / HyperOS-focus notification
     * branch and (b) short-circuits the floating overlay so the two never
     * render at once.
     *
     * HyperOS additionally requires the cached per-app focus-notification
     * permission — without it the island cannot render, so the overlay must
     * stay the status surface (the notification still posts as a quiet row).
     */
    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean {
        if (!userEnabled) return false
        if (HyperOsIsland.isHyperOsHost()) {
            return HyperOsIsland.isIslandCapable(context) &&
                HyperOsIsland.focusPermissionCached()
        }
        return isDynamicIslandCapable(context)
    }

    /**
     * False on HyperOS, ZUI and every other forked SystemUI. Those forks
     * report the Live Updates permission as granted, then re-inflate the
     * promoted template in their own island until SystemUI is killed.
     * Callers must post a static row instead of [isDynamicIslandActive].
     */
    fun allowsLiveNotificationTemplates(): Boolean =
        SystemUiHost.allowsLiveNotificationTemplates()
}
