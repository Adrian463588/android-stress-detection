package com.stressdetector.mobile.security

/**
 * JNI bindings for low-level C/C++ security checks:
 * - Anti-debugging: TracerPid check in /proc/self/status and ptrace(PTRACE_TRACEME)
 * - Anti-hooking: /proc/self/maps scan for frida, xposed, substrate
 * - Port scan: Frida default TCP port 27042
 */
object AppHardeningNative {
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("secure_file_ops")
            isNativeLoaded = true
        } catch (_: Throwable) {
            isNativeLoaded = false
        }
    }

    fun isLoaded(): Boolean = isNativeLoaded

    fun isDebuggerAttached(): Boolean = if (isNativeLoaded) {
        try {
            isDebuggerAttachedNative()
        } catch (_: Throwable) {
            false
        }
    } else false

    fun isHookFrameworkPresent(): Boolean = if (isNativeLoaded) {
        try {
            isHookFrameworkPresentNative()
        } catch (_: Throwable) {
            false
        }
    } else false

    fun isTracerPidActive(): Boolean = if (isNativeLoaded) {
        try {
            isTracerPidActiveNative()
        } catch (_: Throwable) {
            false
        }
    } else false

    fun isFridaPortActive(port: Int = 27042): Boolean = if (isNativeLoaded) {
        try {
            isFridaPortActiveNative(port)
        } catch (_: Throwable) {
            false
        }
    } else false

    @JvmStatic
    private external fun isDebuggerAttachedNative(): Boolean

    @JvmStatic
    private external fun isHookFrameworkPresentNative(): Boolean

    @JvmStatic
    private external fun isTracerPidActiveNative(): Boolean

    @JvmStatic
    private external fun isFridaPortActiveNative(port: Int): Boolean
}
