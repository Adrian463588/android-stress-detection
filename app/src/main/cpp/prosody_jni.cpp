#include <jni.h>
#include <smileapi/SMILEapi.h>
#include <cmath>
#include <memory>
#include <string>
#include <vector>

namespace {
struct Features {
    std::vector<std::string> names;
    std::vector<double> sums;
    std::vector<long> counts;
    long frames = 0;
    long voiced = 0;
    long pitchIndex = -1;
};

bool collect(const float* data, long size, void* parameter) {
    auto& output = *static_cast<Features*>(parameter);
    if (size != static_cast<long>(output.names.size())) return false;
    const bool voiced = output.pitchIndex >= 0 && data[output.pitchIndex] > 0 &&
        std::isfinite(data[output.pitchIndex]);
    ++output.frames;
    if (voiced) ++output.voiced;
    for (long index = 0; index < size; ++index) {
        const auto& name = output.names[index];
        const bool voiceOnly = name.find("F0") != std::string::npos ||
            name.find("jitter") != std::string::npos || name.find("shimmer") != std::string::npos;
        if (std::isfinite(data[index]) && (!voiceOnly || voiced)) {
            output.sums[index] += data[index];
            ++output.counts[index];
        }
    }
    return true;
}

void check(JNIEnv* environment, smileres_t result, smileobj_t* smile) {
    if (result == SMILE_SUCCESS) return;
    const char* message = smile_error_msg(smile);
    environment->ThrowNew(environment->FindClass("java/lang/IllegalStateException"),
        message ? message : "openSMILE feature extraction failed");
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_stressdetector_mobile_audio_OpenSmileProsodyExtractor_extractNative(
    JNIEnv* environment, jobject, jstring configPath, jfloatArray input) {
    const jsize length = environment->GetArrayLength(input);
    if (length < 16000 || length > 320000) {
        environment->ThrowNew(environment->FindClass("java/lang/IllegalArgumentException"),
            "openSMILE requires an observed PCM segment between 1 and 20 seconds");
        return nullptr;
    }
    std::vector<float> pcm(length);
    environment->GetFloatArrayRegion(input, 0, length, pcm.data());
    std::unique_ptr<smileobj_t, decltype(&smile_free)> smile(smile_new(), smile_free);
    if (!smile) {
        environment->ThrowNew(environment->FindClass("java/lang/IllegalStateException"), "openSMILE allocation failed");
        return nullptr;
    }
    const char* path = environment->GetStringUTFChars(configPath, nullptr);
    const auto initialized = smile_initialize(smile.get(), path, 0, nullptr, 0, 0, 0);
    environment->ReleaseStringUTFChars(configPath, path);
    check(environment, initialized, smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    Features output;
    long size = 0;
    check(environment, smile_extsink_get_num_elements(smile.get(), "sink", &size), smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    output.sums.resize(size);
    output.counts.resize(size);
    for (long index = 0; index < size; ++index) {
        const char* name = nullptr;
        check(environment, smile_extsink_get_element_name(smile.get(), "sink", index, &name), smile.get());
        if (environment->ExceptionCheck() || !name) return nullptr;
        output.names.emplace_back(name);
        if (output.names.back().find("F0semitone") != std::string::npos) output.pitchIndex = index;
    }
    check(environment, smile_extsink_set_data_callback(smile.get(), "sink", collect, &output), smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    check(environment, smile_extsource_write_data(smile.get(), "source", pcm.data(), length), smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    check(environment, smile_extsource_set_external_eoi(smile.get(), "source"), smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    check(environment, smile_run(smile.get()), smile.get());
    if (environment->ExceptionCheck()) return nullptr;
    std::vector<std::string> rows = {"frames=" + std::to_string(output.frames), "voiced=" + std::to_string(output.voiced)};
    for (long index = 0; index < size; ++index) {
        if (output.counts[index] > 0) rows.push_back(output.names[index] + "=" +
            std::to_string(output.sums[index] / output.counts[index]));
    }
    auto result = environment->NewObjectArray(rows.size(), environment->FindClass("java/lang/String"), nullptr);
    for (size_t index = 0; index < rows.size(); ++index) {
        jstring row = environment->NewStringUTF(rows[index].c_str());
        environment->SetObjectArrayElement(result, index, row);
        environment->DeleteLocalRef(row);
    }
    return result;
}
