package com.stressdetector.wear

import android.app.Activity
import com.samsung.android.service.health.tracking.HealthTrackerException

/**
 * Bridges a resolvable Samsung Health SDK failure from the service callback to
 * the foreground Activity without retaining an Activity or inventing a
 * second connection state.
 */
object HealthSdkResolutionRegistry {
    private var pending: HealthTrackerException? = null

    @Synchronized
    fun offer(error: HealthTrackerException?): Boolean {
        val resolvable = error != null && runCatching { error.hasResolution() }.getOrDefault(false)
        pending = if (resolvable) error else null
        return resolvable
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    @Synchronized
    fun hasPendingResolution(): Boolean = pending != null

    fun resolve(activity: Activity): Boolean {
        val error = synchronized(this) { pending } ?: return false
        return runCatching {
            error.resolve(activity)
            clear()
            true
        }.getOrDefault(false)
    }
}
