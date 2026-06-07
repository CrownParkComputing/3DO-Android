#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstdio>
#include <cstdint>
#include <sys/stat.h>

#define LOG_TAG "4DO-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations
extern "C" {
    int emulator_init(const char* game_path, const char* bios_path);
    bool emulator_load_cd(const char* game_path);
    int emulator_audio_drain(uint32_t* out_buffer, int max_frames);
    void emulator_shutdown();
    void emulator_pause();
    void emulator_resume();
    void emulator_toggle_pause();
    void emulator_reset();
    const char* emulator_get_status();
}

// Global state
static std::mutex g_emulator_mutex;
static bool g_emulator_running = false;
static std::string g_current_game_path;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_initEmulator(JNIEnv* env, jobject thiz, jstring gamePath, jstring biosPath) {
    LOGD("Initializing emulator...");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);

    const char* path = nullptr;
    const char* bios = nullptr;
    if (gamePath != nullptr) {
        path = env->GetStringUTFChars(gamePath, nullptr);
        if (path == nullptr) {
            LOGE("Failed to get game path string");
            return JNI_FALSE;
        }
    }

    if (biosPath != nullptr) {
        bios = env->GetStringUTFChars(biosPath, nullptr);
        if (bios == nullptr) {
            if (path != nullptr) {
                env->ReleaseStringUTFChars(gamePath, path);
            }
            LOGE("Failed to get bios path string");
            return JNI_FALSE;
        }
    }

    if (g_emulator_running) {
        bool sameGame = (path != nullptr && g_current_game_path == path);
        if (sameGame) {
            // Same game is already loaded — Activity is being re-created for the same
            // session (e.g. process survived swipe-up). Resume will be triggered by
            // onResume() -> resumeEmulator(); nothing more to do here.
            LOGD("Emulator already running same game — skipping re-init");
            if (path != nullptr) env->ReleaseStringUTFChars(gamePath, path);
            if (bios != nullptr) env->ReleaseStringUTFChars(biosPath, bios);
            return JNI_TRUE;
        }
        // Different game requested: tear down the existing session first so the
        // new game gets a clean emulator_init() call.
        LOGD("Emulator running different game — shutting down before new load");
        emulator_shutdown();
        g_emulator_running = false;
        g_current_game_path.clear();
    }
    
    int result = emulator_init(path, bios);

    if (result == 0) {
        g_emulator_running = true;
        g_current_game_path = (path != nullptr) ? path : "";
        LOGD("Emulator initialized successfully");
    } else {
        LOGE("Emulator initialization failed: %d", result);
    }

    if (path != nullptr) {
        env->ReleaseStringUTFChars(gamePath, path);
    }
    if (bios != nullptr) {
        env->ReleaseStringUTFChars(biosPath, bios);
    }

    return (result == 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_shutdownEmulator(JNIEnv* env, jobject thiz) {
    LOGD("Shutting down emulator...");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);
    
    if (g_emulator_running) {
        emulator_shutdown();
        g_emulator_running = false;
        g_current_game_path.clear();
        LOGD("Emulator shut down");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_pauseEmulator(JNIEnv* env, jobject thiz) {
    LOGD("Pausing emulator");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);
    
    if (g_emulator_running) {
        emulator_pause();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_resumeEmulator(JNIEnv* env, jobject thiz) {
    LOGD("Resuming emulator");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);
    
    if (g_emulator_running) {
        emulator_resume();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_togglePause(JNIEnv* env, jobject thiz) {
    LOGD("Toggling pause");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);
    
    if (g_emulator_running) {
        emulator_toggle_pause();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_resetEmulator(JNIEnv* env, jobject thiz) {
    LOGD("Resetting emulator");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);
    
    if (g_emulator_running) {
        emulator_reset();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_loadCdImage(JNIEnv* env, jobject thiz, jstring gamePath) {
    LOGD("Loading CD image...");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);

    if (!g_emulator_running || gamePath == nullptr) {
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(gamePath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get CD path string");
        return JNI_FALSE;
    }

    bool loaded = emulator_load_cd(path);
    env->ReleaseStringUTFChars(gamePath, path);

    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_EmulatorActivity_drainAudioFrames(JNIEnv* env, jobject thiz, jintArray packedFrames) {
    if (packedFrames == nullptr) {
        return 0;
    }

    jsize max_frames = env->GetArrayLength(packedFrames);
    if (max_frames <= 0) {
        return 0;
    }

    jint* out = env->GetIntArrayElements(packedFrames, nullptr);
    if (out == nullptr) {
        return 0;
    }

    int frames = emulator_audio_drain(reinterpret_cast<uint32_t*>(out), static_cast<int>(max_frames));
    env->ReleaseIntArrayElements(packedFrames, out, 0);
    return static_cast<jint>(frames);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fourdo_android_EmulatorActivity_getStatus(JNIEnv* env, jobject thiz) {
    const char* status = emulator_get_status();
    return env->NewStringUTF(status ? status : "Unknown");
}

// NVRAM data access functions from emulator_core.cpp
extern "C" {
    uint8_t* opera_nvram_get_data(size_t* size);
    bool opera_nvram_set_data(const uint8_t* data, size_t size);
}

// NVRAM save/load implementation
static bool save_nvram_to_file(const char* path) {
    if (!g_emulator_running) {
        LOGE("Cannot save NVRAM when emulator is not running");
        return false;
    }

    uint8_t* nvram_data = nullptr;
    size_t nvram_size = 0;
    nvram_data = opera_nvram_get_data(&nvram_size);

    if (nvram_data == nullptr || nvram_size == 0) {
        LOGE("Failed to get NVRAM data from emulator");
        return false;
    }

    // Create directory if it doesn't exist
    std::string dir_path = path;
    size_t last_slash = dir_path.find_last_of("/\\");
    if (last_slash != std::string::npos) {
        dir_path = dir_path.substr(0, last_slash);
        mkdir(dir_path.c_str(), 0755);
    }

    FILE* file = fopen(path, "wb");
    if (!file) {
        LOGE("Failed to open NVRAM file for writing: %s", path);
        return false;
    }

    if (fwrite(nvram_data, 1, nvram_size, file) != nvram_size) {
        LOGE("Failed to write NVRAM data to file");
        fclose(file);
        return false;
    }

    fclose(file);
    LOGD("NVRAM saved successfully to: %s (%zu bytes)", path, nvram_size);
    return true;
}

static bool load_nvram_from_file(const char* path) {
    if (!g_emulator_running) {
        LOGE("Cannot load NVRAM when emulator is not running");
        return false;
    }

    FILE* file = fopen(path, "rb");
    if (!file) {
        LOGD("No NVRAM file found at: %s (first run)", path);
        return false;
    }

    fseek(file, 0, SEEK_END);
    size_t file_size = ftell(file);
    fseek(file, 0, SEEK_SET);

    if (file_size == 0) {
        LOGE("NVRAM file is empty: %s", path);
        fclose(file);
        return false;
    }

    std::vector<uint8_t> nvram_data(file_size);
    if (fread(nvram_data.data(), 1, file_size, file) != file_size) {
        LOGE("Failed to read NVRAM data from file");
        fclose(file);
        return false;
    }
    fclose(file);

    bool success = opera_nvram_set_data(nvram_data.data(), file_size);

    if (!success) {
        LOGE("Failed to load NVRAM data into emulator");
        return false;
    }

    LOGD("NVRAM loaded successfully from: %s (%zu bytes)", path, file_size);
    return true;
}

// Initialize callback from MainActivity
extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_MainActivity_init(JNIEnv* env, jobject thiz) {
    LOGD("MainActivity init called");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_saveNVRAM(JNIEnv* env, jobject thiz, jstring path) {
    LOGD("Saving NVRAM to file...");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);

    if (path == nullptr) {
        LOGE("NVRAM save path is null");
        return JNI_FALSE;
    }

    const char* c_path = env->GetStringUTFChars(path, nullptr);
    if (c_path == nullptr) {
        LOGE("Failed to get NVRAM save path string");
        return JNI_FALSE;
    }

    bool success = save_nvram_to_file(c_path);
    env->ReleaseStringUTFChars(path, c_path);

    LOGD("NVRAM save %s", success ? "succeeded" : "failed");
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_loadNVRAM(JNIEnv* env, jobject thiz, jstring path) {
    LOGD("Loading NVRAM from file...");
    std::lock_guard<std::mutex> lock(g_emulator_mutex);

    if (path == nullptr) {
        LOGE("NVRAM load path is null");
        return JNI_FALSE;
    }

    const char* c_path = env->GetStringUTFChars(path, nullptr);
    if (c_path == nullptr) {
        LOGE("Failed to get NVRAM load path string");
        return JNI_FALSE;
    }

    bool success = load_nvram_from_file(c_path);
    env->ReleaseStringUTFChars(path, c_path);

    LOGD("NVRAM load %s", success ? "succeeded" : "failed");
    return success ? JNI_TRUE : JNI_FALSE;
}

// Aspect ratio toggle is in unified_renderer.cpp

// -----------------------------------------------------------------------
// Save state JNI bindings
// -----------------------------------------------------------------------

// Forward declarations from native_core.cpp
extern "C" {
    size_t   emulator_state_size(void);
    bool     emulator_save_state(void* buf, size_t buf_size);
    bool     emulator_load_state(const void* buf, size_t buf_size);
    void     emulator_set_region(int region);
    int      emulator_get_region(void);
    void     emulator_set_cpu_speed(float multiplier);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_EmulatorActivity_getStateSize(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(emulator_state_size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_saveState(JNIEnv* env, jobject /*thiz*/,
                                                    jbyteArray buf) {
    if (buf == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(buf);
    jbyte* data = env->GetByteArrayElements(buf, nullptr);
    if (data == nullptr) return JNI_FALSE;
    bool ok = emulator_save_state(data, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buf, data, JNI_COMMIT); // commit written data back to Java
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_loadState(JNIEnv* env, jobject /*thiz*/,
                                                    jbyteArray buf) {
    if (buf == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(buf);
    jbyte* data = env->GetByteArrayElements(buf, nullptr);
    if (data == nullptr) return JNI_FALSE;
    bool ok = emulator_load_state(data, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buf, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// -----------------------------------------------------------------------
// Region and CPU speed JNI bindings
// -----------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setRegion(JNIEnv* /*env*/, jobject /*thiz*/,
                                                    jint region) {
    emulator_set_region(static_cast<int>(region));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fourdo_android_EmulatorActivity_getRegion(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(emulator_get_region());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setCpuSpeed(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jfloat multiplier) {
    emulator_set_cpu_speed(static_cast<float>(multiplier));
}
