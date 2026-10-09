#include <jni.h>
#include <cerrno>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/syscall.h>
#include <unistd.h>

// Android forbids app hard links. The kernel move is atomic and must not replace
// an existing target. Unsupported kernels/filesystems fail closed; no fallback.
extern "C" JNIEXPORT jint JNICALL
Java_com_stressdetector_mobile_security_AtomicFilePublisher_renameNew(
        JNIEnv* env, jobject, jstring source, jstring target) {
    const char* source_path = env->GetStringUTFChars(source, nullptr);
    if (source_path == nullptr) return ENOMEM;
    const char* target_path = env->GetStringUTFChars(target, nullptr);
    if (target_path == nullptr) {
        env->ReleaseStringUTFChars(source, source_path);
        return ENOMEM;
    }
    const long result = syscall(__NR_renameat2, AT_FDCWD, source_path,
                                AT_FDCWD, target_path, RENAME_NOREPLACE);
    const int error = result == 0 ? 0 : errno;
    env->ReleaseStringUTFChars(target, target_path);
    env->ReleaseStringUTFChars(source, source_path);
    return error;
}
