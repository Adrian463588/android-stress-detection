#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/types.h>

namespace {

// Check if TracerPid in /proc/self/status is non-zero
bool checkTracerPid() {
    FILE* fp = fopen("/proc/self/status", "r");
    if (!fp) return false;

    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = 0;
            if (sscanf(line + 10, "%d", &pid) == 1 && pid > 0) {
                traced = true;
            }
            break;
        }
    }
    fclose(fp);
    return traced;
}

// Test ptrace: if another debugger/instrumentation is attached, PTRACE_TRACEME fails with EPERM
bool checkPtrace() {
    long ret = ptrace(PTRACE_TRACEME, 0, 1, 0);
    if (ret < 0) {
        // Someone is already tracing this process
        return true;
    }
    // Detach cleanly so we don't interfere with future process states
    ptrace(PTRACE_DETACH, 0, 1, 0);
    return false;
}

// Check /proc/self/maps for injected hook libraries (Frida, Xposed, Substrate, etc.)
bool checkMapsForHooks() {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;

    char line[512];
    bool suspicious = false;
    const char* targets[] = {
        "frida-agent",
        "frida-gadget",
        "libfrida",
        "xposed",
        "lsposed",
        "edxposed",
        "substrate",
        "com.saurik.substrate",
        nullptr
    };

    while (fgets(line, sizeof(line), fp)) {
        for (int i = 0; targets[i] != nullptr; ++i) {
            if (strstr(line, targets[i]) != nullptr) {
                suspicious = true;
                break;
            }
        }
        if (suspicious) break;
    }
    fclose(fp);
    return suspicious;
}

// Check /proc/net/tcp for local listening port (e.g. Frida default port 27042 = 0x69A2)
bool checkPortListening(int port) {
    char hexPort[16];
    snprintf(hexPort, sizeof(hexPort), ":%04X", port);

    const char* tcpFiles[] = {"/proc/net/tcp", "/proc/net/tcp6", nullptr};
    for (int f = 0; tcpFiles[f] != nullptr; ++f) {
        FILE* fp = fopen(tcpFiles[f], "r");
        if (!fp) continue;

        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, hexPort) != nullptr) {
                fclose(fp);
                return true;
            }
        }
        fclose(fp);
    }
    return false;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_stressdetector_mobile_security_AppHardeningNative_isDebuggerAttachedNative(
        JNIEnv*, jclass) {
    if (checkTracerPid()) return JNI_TRUE;
    if (checkPtrace()) return JNI_TRUE;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_stressdetector_mobile_security_AppHardeningNative_isHookFrameworkPresentNative(
        JNIEnv*, jclass) {
    if (checkMapsForHooks()) return JNI_TRUE;
    // Standard Frida server port is 27042
    if (checkPortListening(27042)) return JNI_TRUE;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_stressdetector_mobile_security_AppHardeningNative_isTracerPidActiveNative(
        JNIEnv*, jclass) {
    return checkTracerPid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_stressdetector_mobile_security_AppHardeningNative_isFridaPortActiveNative(
        JNIEnv*, jclass, jint port) {
    return checkPortListening(port) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
