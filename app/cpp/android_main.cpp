#include <android/log.h>
#include <android/native_window.h>
#include <android_native_app_glue.h>

#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "4DO-Android-Main"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static struct android_app* g_android_app = nullptr;
static ANativeWindow* g_native_window = nullptr;
static bool g_app_running = false;
static bool g_window_ready = false;
static pthread_t g_emulator_thread;
static bool g_emulator_thread_running = false;

// External references from libopera
typedef void* (*opera_ext_interface_t)(int, void*);

extern "C" {
    uint32_t opera_3do_state_size(void);
    uint32_t opera_3do_state_save(void *buf, size_t size);
    uint32_t opera_3do_state_load(void const *buf, size_t size);
    int opera_3do_init(opera_ext_interface_t callback);
    void opera_3do_destroy(void);
    void opera_3do_process_frame(void);
}

// Emulator callback
static void* emulator_callback(int command, void* data) {
    switch (command) {
        case 0: // Get video buffer
            break;
        case 1: // Video refresh
            break;
        case 2: // Audio
            break;
    }
    return nullptr;
}

// Emulator thread function
static void* emulator_thread_func(void* arg) {
    LOGD("Emulator thread starting...");
    
    // Initialize the emulator
    if (opera_3do_init(emulator_callback) != 0) {
        LOGE("Failed to initialize 3DO emulator");
        return nullptr;
    }
    
    LOGD("3DO emulator initialized");
    
    // Main emulator loop
    while (g_emulator_thread_running) {
        // Process one frame
        opera_3do_process_frame();
        
        // Limit frame rate to ~60fps
        usleep(16667); // ~16.67ms for 60fps
    }
    
    // Cleanup
    opera_3do_destroy();
    LOGD("Emulator thread exiting");
    
    return nullptr;
}

// Handle commands from the system
static void handle_cmd(struct android_app* app, int32_t cmd) {
    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            LOGD("APP_CMD_INIT_WINDOW");
            g_native_window = app->window;
            if (g_native_window != nullptr) {
                g_window_ready = true;
                LOGD("Native window ready");
            }
            break;
            
        case APP_CMD_TERM_WINDOW:
            LOGD("APP_CMD_TERM_WINDOW");
            g_window_ready = false;
            g_native_window = nullptr;
            break;
            
        case APP_CMD_GAINED_FOCUS:
            LOGD("APP_CMD_GAINED_FOCUS");
            break;
            
        case APP_CMD_LOST_FOCUS:
            LOGD("APP_CMD_LOST_FOCUS");
            break;
            
        case APP_CMD_START:
            LOGD("APP_CMD_START");
            break;
            
        case APP_CMD_RESUME:
            LOGD("APP_CMD_RESUME");
            break;
            
        case APP_CMD_PAUSE:
            LOGD("APP_CMD_PAUSE");
            break;
            
        case APP_CMD_STOP:
            LOGD("APP_CMD_STOP");
            break;
            
        case APP_CMD_DESTROY:
            LOGD("APP_CMD_DESTROY");
            g_app_running = false;
            break;
            
        default:
            LOGD("Unknown command: %d", cmd);
            break;
    }
}

// Main entry point
void android_main(struct android_app* state) {
    LOGD("4DO Android emulator starting...");
    
    g_android_app = state;
    g_app_running = true;
    
    // Register command handler
    state->onAppCmd = handle_cmd;
    
    LOGD("Android main initialized, waiting for window...");
    
    // Main loop
    while (g_app_running) {
        int ident;
        int events;
        struct android_poll_source* source;
        
        // Process events
        while ((ident = ALooper_pollAll(0, nullptr, &events, (void**)&source)) >= 0) {
            if (source != nullptr) {
                source->process(state, source);
            }
            
            // Check if app is being destroyed
            if (state->destroyRequested) {
                LOGD("App destroy requested");
                g_app_running = false;
                break;
            }
        }
        
        // If window is ready and we haven't started the emulator thread yet
        if (g_window_ready && !g_emulator_thread_running) {
            LOGD("Starting emulator thread...");
            g_emulator_thread_running = true;
            pthread_create(&g_emulator_thread, nullptr, emulator_thread_func, nullptr);
        }
        
        // Small delay to prevent busy-waiting
        usleep(10000);
    }
    
    // Stop emulator thread
    if (g_emulator_thread_running) {
        LOGD("Stopping emulator thread...");
        g_emulator_thread_running = false;
        pthread_join(g_emulator_thread, nullptr);
    }
    
    LOGD("4DO Android emulator exiting");
}
