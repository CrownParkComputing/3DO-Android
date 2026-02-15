#include <jni.h>
#include <android/log.h>
#include "libopera/opera_arm.h"

#define LOG_TAG "4DO-Android-Input"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Input mapping for 3DO controller
enum InputButton {
    BUTTON_A = 0,
    BUTTON_B,
    BUTTON_C,
    BUTTON_PLAY_PAUSE,
    BUTTON_STOP,
    BUTTON_DPAD_UP,
    BUTTON_DPAD_DOWN,
    BUTTON_DPAD_LEFT,
    BUTTON_DPAD_RIGHT,
    BUTTON_L1,
    BUTTON_R1,
    BUTTON_MAX
};

// Input state
static bool inputState[BUTTON_MAX] = {false};

extern "C" bool android_input_get_state(int button) {
    if (button >= 0 && button < BUTTON_MAX) {
        return inputState[button];
    }
    return false;
}

extern "C" void android_input_reset_state() {
    for (int i = 0; i < BUTTON_MAX; ++i) {
        inputState[i] = false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fourdo_android_EmulatorActivity_setInputState(JNIEnv *env, jobject thiz, jint button, jboolean pressed) {
    if (button >= 0 && button < BUTTON_MAX) {
        inputState[button] = pressed;
        LOGD("Input button %d %s", button, pressed ? "pressed" : "released");
    }
}

// Function to get input state for libopera
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdo_android_EmulatorActivity_getInputState(JNIEnv *env, jobject thiz, jint button) {
    if (button >= 0 && button < BUTTON_MAX) {
        return inputState[button];
    }
    return JNI_FALSE;
}

// Map input to libopera controller
void updateLiboperaInput() {
    if (!inputState[BUTTON_A]) {
        // Map to 3DO button A
    }
    if (!inputState[BUTTON_B]) {
        // Map to 3DO button B
    }
    // ... map other buttons
}