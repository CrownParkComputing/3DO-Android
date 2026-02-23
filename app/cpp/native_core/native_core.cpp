/**
 * 4DO Native Core - FourdoCore Implementation
 *
 * Integrates all native subsystem components (Bios, Nvram, CdromInterface,
 * InputSystem) with the libopera hardware-emulation backend.
 *
 * libopera is used internally for the actual 3DO hardware emulation.
 * Individual components will be replaced with pure C++ implementations
 * once each phase of the NATIVE_REWRITE_PLAN is complete.
 */

#include "native_core.h"

#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <time.h>

// -----------------------------------------------------------------------
// libopera C interface (hardware-emulation backend)
// -----------------------------------------------------------------------
extern "C" {
    // Memory
    int  opera_mem_init(int mem_cfg);
    int  opera_mem_cfg(void);
    void opera_mem_destroy(void);
    void opera_mem_rom1_clear(void);
    void opera_mem_rom1_byteswap32_if_le(void);
    extern uint8_t* ROM1;
    extern uint8_t* NVRAM;

    // NVRAM
    bool opera_nvram_initialized(void* buf, const int bufsize);
    void opera_nvram_init(void* buf, const int bufsize);
#ifndef NVRAM_SIZE
#define NVRAM_SIZE (1024 * 32)
#endif

    // CD-ROM callbacks
    typedef uint32_t (*opera_cdrom_get_size_cb_t)(void);
    typedef void     (*opera_cdrom_set_sector_cb_t)(uint32_t sector);
    typedef void     (*opera_cdrom_read_sector_cb_t)(void* buf);
    void opera_cdrom_set_callbacks(opera_cdrom_get_size_cb_t get_size,
                                   opera_cdrom_set_sector_cb_t set_sector,
                                   opera_cdrom_read_sector_cb_t read_sector);

    // Video
    int opera_vdlp_set_video_buffer(void* buf);

    // DSP / Audio
    uint32_t opera_dsp_loop(void);

    // PBUS (controller)
#include "libopera/opera_pbus.h"

    // XBUS (CD-ROM attachment)
    typedef void* (*opera_xbus_device)(int, void*);
    void opera_xbus_init(opera_xbus_device zero_dev);
    void opera_xbus_destroy(void);
    int  opera_xbus_attach(opera_xbus_device dev);
    void opera_xbus_device_load(int dev, const char* name);
    void opera_xbus_device_eject(int dev);
    void* xbus_cdrom_plugin(int proc, void* data);

    // 3DO core
    typedef void* (*opera_ext_interface_t)(int, void*);
    int  opera_3do_init(opera_ext_interface_t callback);
    void opera_3do_destroy(void);
    void opera_3do_process_frame(void);
}

// Input state from input_handler.cpp
extern "C" bool android_input_get_state(int button);
extern "C" void android_input_reset_state();

// Renderer (unified_renderer.cpp)
extern "C" void render_frame(const void* pixels, int width, int height);

namespace fourdo {
namespace core {

// -----------------------------------------------------------------------
// Button indices – kept in sync with input_handler.cpp / EmulatorActivity
// -----------------------------------------------------------------------
enum InputButton {
    BUTTON_A = 0, BUTTON_B, BUTTON_C,
    BUTTON_PLAY_PAUSE, BUTTON_STOP,
    BUTTON_DPAD_UP, BUTTON_DPAD_DOWN, BUTTON_DPAD_LEFT, BUTTON_DPAD_RIGHT,
    BUTTON_L1, BUTTON_R1,
};

// Memory configuration constants (matching emulator_core.cpp)
static const int DRAM_VRAM_UNSET_CFG = 0x00;
static const int DRAM_VRAM_STOCK_CFG = 0x21;
static const size_t ROM1_SIZE_BYTES   = 1024 * 1024;

// -----------------------------------------------------------------------
// Monotonic clock helper
// -----------------------------------------------------------------------
static inline int64_t monotonic_us() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000LL +
           static_cast<int64_t>(ts.tv_nsec / 1000);
}

// -----------------------------------------------------------------------
// libopera CD-ROM callbacks (forwarded to FourdoCore's CdromInterface)
// -----------------------------------------------------------------------
uint32_t FourdoCore::opera_cdrom_get_size_cb()               { return instance().m_cdrom.get_size(); }
void     FourdoCore::opera_cdrom_set_sector_cb(uint32_t s)   { instance().m_cdrom.set_sector(s); }
void     FourdoCore::opera_cdrom_read_sector_cb(void* buf)   { instance().m_cdrom.read_sector(buf); }
void     FourdoCore::opera_push_audio(uint32_t packed)       { instance().push_audio_sample(packed); }

// libopera callback
static void* opera_callback(int command, void* /*data*/) {
    if (command == 2) { // Audio sample ready
        uint32_t sample = opera_dsp_loop();
        FourdoCore::opera_push_audio(sample);
    }
    return nullptr;
}

// -----------------------------------------------------------------------
// Singleton
// -----------------------------------------------------------------------
FourdoCore& FourdoCore::instance() {
    static FourdoCore s_instance;
    return s_instance;
}

FourdoCore::FourdoCore() = default;
FourdoCore::~FourdoCore() { shutdown(); }

// -----------------------------------------------------------------------
// init
// -----------------------------------------------------------------------
int FourdoCore::init(const std::string& game_path, const std::string& bios_path) {
    LOGD("FourdoCore::init game=%s bios=%s",
         game_path.empty() ? "(none)" : game_path.c_str(),
         bios_path.empty() ? "(none)" : bios_path.c_str());

    if (m_initialized.load(std::memory_order_acquire)) {
        LOGD("Already initialised");
        return 0;
    }

    m_game_path = game_path;
    m_bios_path = bios_path;

    // Allocate video buffer
    m_video_buffer = static_cast<u8*>(malloc(FB_BYTES));
    if (!m_video_buffer) {
        LOGE("Failed to allocate video buffer");
        return -1;
    }
    memset(m_video_buffer, 0, FB_BYTES);

    // Load BIOS using native Bios class
    if (!bios_path.empty() && !m_bios.load(bios_path)) {
        LOGE("Bios::load failed for: %s", bios_path.c_str());
        // Continue – libopera may still work with whatever is in ROM1
    }

    // Open CD image using native CdromInterface class
    if (!game_path.empty()) {
        if (!m_cdrom.load_disc(game_path)) {
            LOGE("CdromInterface::load_disc failed for: %s", game_path.c_str());
        }
    }

    // Start emulator thread
    m_thread_running.store(true, std::memory_order_release);
    m_paused.store(false, std::memory_order_release);
    m_init_state = 0;

    if (pthread_create(&m_thread, nullptr, thread_entry, this) != 0) {
        LOGE("Failed to create emulator thread");
        free(m_video_buffer);
        m_video_buffer = nullptr;
        m_thread_running.store(false, std::memory_order_release);
        return -1;
    }

    // Wait up to 2 s for thread to signal init result
    for (int i = 0; i < 100 && m_init_state == 0; ++i) {
        usleep(20000);
    }

    if (m_init_state != 1) {
        LOGE("Emulator init timed out or failed (state=%d)", m_init_state);
        return -1;
    }

    LOGD("FourdoCore initialised successfully");
    return 0;
}

// -----------------------------------------------------------------------
// shutdown
// -----------------------------------------------------------------------
void FourdoCore::shutdown() {
    LOGD("FourdoCore::shutdown");

    m_thread_running.store(false, std::memory_order_release);
    m_paused.store(false, std::memory_order_release);

    if (m_thread) {
        pthread_join(m_thread, nullptr);
        m_thread = 0;
    }

    m_initialized.store(false, std::memory_order_release);
    m_init_state = 0;
    android_input_reset_state();

    m_cdrom.eject();

    if (m_video_buffer) {
        free(m_video_buffer);
        m_video_buffer = nullptr;
    }

    // Reset audio ring
    m_audio_write_pos.store(0, std::memory_order_relaxed);
    m_audio_read_pos.store(0, std::memory_order_relaxed);

    LOGD("FourdoCore shutdown complete");
}

// -----------------------------------------------------------------------
// pause / resume / toggle / reset
// -----------------------------------------------------------------------
void FourdoCore::pause()        { m_paused.store(true,  std::memory_order_release); }
void FourdoCore::resume()       { m_paused.store(false, std::memory_order_release); }
void FourdoCore::toggle_pause() { m_paused.fetch_xor(1, std::memory_order_acq_rel); }
void FourdoCore::reset()        { m_reset_requested.store(true, std::memory_order_release); }

// -----------------------------------------------------------------------
// load_cd
// -----------------------------------------------------------------------
bool FourdoCore::load_cd(const std::string& path) {
    if (path.empty()) {
        m_cdrom.eject();
        m_game_path.clear();
        return true;
    }

    bool ok = m_cdrom.load_disc(path);
    if (ok) {
        m_game_path = path;
        if (m_initialized.load(std::memory_order_acquire)) {
            bool was_paused = m_paused.load(std::memory_order_acquire);
            m_paused.store(true, std::memory_order_release);
            usleep(30000);
            opera_xbus_device_eject(0);
            usleep(30000);
            opera_xbus_device_load(0, path.c_str());
            usleep(30000);
            m_paused.store(was_paused, std::memory_order_release);
        }
    }
    return ok;
}

// -----------------------------------------------------------------------
// drain_audio
// -----------------------------------------------------------------------
int FourdoCore::drain_audio(u32* out_buffer, int max_frames) {
    if (!out_buffer || max_frames <= 0) return 0;

    u32 rp = m_audio_read_pos.load(std::memory_order_relaxed);
    u32 wp = m_audio_write_pos.load(std::memory_order_acquire);

    u32 avail = wp - rp;
    if (avail == 0) return 0;

    int count = (avail < static_cast<u32>(max_frames))
                ? static_cast<int>(avail) : max_frames;
    for (int i = 0; i < count; ++i) {
        out_buffer[i] = m_audio_ring[(rp + i) & AUDIO_RING_MASK];
    }
    std::atomic_thread_fence(std::memory_order_acquire);
    m_audio_read_pos.store(rp + count, std::memory_order_release);
    return count;
}

// -----------------------------------------------------------------------
// NVRAM access
// -----------------------------------------------------------------------
u8* FourdoCore::nvram_data(size_t* out_size) {
    if (!m_initialized.load(std::memory_order_acquire) || !NVRAM) {
        if (out_size) *out_size = 0;
        return nullptr;
    }
    if (out_size) *out_size = NVRAM_SIZE;
    return NVRAM;
}

bool FourdoCore::nvram_set(const u8* data, size_t size) {
    if (!m_initialized.load(std::memory_order_acquire) || !NVRAM || !data) {
        return false;
    }
    size_t copy = (size < NVRAM_SIZE) ? size : NVRAM_SIZE;
    memcpy(NVRAM, data, copy);
    return true;
}

// -----------------------------------------------------------------------
// status
// -----------------------------------------------------------------------
const char* FourdoCore::status() const {
    if (!m_initialized.load(std::memory_order_acquire)) return "Not Running";
    return m_paused.load(std::memory_order_acquire) ? "Paused" : "Running";
}

// -----------------------------------------------------------------------
// Audio ring-buffer helpers
// -----------------------------------------------------------------------
void FourdoCore::push_audio_sample(u32 packed) {
    u32 wp = m_audio_write_pos.load(std::memory_order_relaxed);
    u32 rp = m_audio_read_pos.load(std::memory_order_acquire);
    if ((wp - rp) >= AUDIO_RING_SIZE) {
        m_audio_read_pos.fetch_add(1, std::memory_order_acq_rel); // drop oldest
    }
    m_audio_ring[wp & AUDIO_RING_MASK] = packed;
    std::atomic_thread_fence(std::memory_order_release);
    m_audio_write_pos.store(wp + 1, std::memory_order_release);
}

u32 FourdoCore::audio_available() const {
    return m_audio_write_pos.load(std::memory_order_acquire) -
           m_audio_read_pos.load(std::memory_order_acquire);
}

// -----------------------------------------------------------------------
// Thread entry
// -----------------------------------------------------------------------
void* FourdoCore::thread_entry(void* arg) {
    static_cast<FourdoCore*>(arg)->emulator_loop();
    return nullptr;
}

// -----------------------------------------------------------------------
// Input helper: build pbus state from Android input system
// -----------------------------------------------------------------------
static void update_pbus_input() {
    opera_pbus_joypad_t joypad = {0};
    joypad.u  = android_input_get_state(BUTTON_DPAD_UP)    ? 1 : 0;
    joypad.d  = android_input_get_state(BUTTON_DPAD_DOWN)  ? 1 : 0;
    joypad.l  = android_input_get_state(BUTTON_DPAD_LEFT)  ? 1 : 0;
    joypad.r  = android_input_get_state(BUTTON_DPAD_RIGHT) ? 1 : 0;
    joypad.p  = android_input_get_state(BUTTON_PLAY_PAUSE) ? 1 : 0;
    joypad.a  = android_input_get_state(BUTTON_A)          ? 1 : 0;
    joypad.b  = android_input_get_state(BUTTON_B)          ? 1 : 0;
    joypad.c  = android_input_get_state(BUTTON_C)          ? 1 : 0;
    joypad.x  = android_input_get_state(BUTTON_STOP)       ? 1 : 0;
    joypad.lt = android_input_get_state(BUTTON_L1)         ? 1 : 0;
    joypad.rt = android_input_get_state(BUTTON_R1)         ? 1 : 0;
    opera_pbus_reset();
    opera_pbus_add_joypad(&joypad);
}

// -----------------------------------------------------------------------
// Main emulator loop (runs on emulator thread)
// -----------------------------------------------------------------------
void FourdoCore::emulator_loop() {
    LOGD("Emulator thread started");

    // Set video buffer
    if (opera_vdlp_set_video_buffer(m_video_buffer) != 0) {
        LOGE("opera_vdlp_set_video_buffer failed");
        m_init_state = -1;
        return;
    }

    // Register libopera CD-ROM callbacks (routed through CdromInterface)
    opera_cdrom_set_callbacks(opera_cdrom_get_size_cb,
                              opera_cdrom_set_sector_cb,
                              opera_cdrom_read_sector_cb);

    // Initialise memory if needed
    if (opera_mem_cfg() == DRAM_VRAM_UNSET_CFG) {
        opera_mem_init(DRAM_VRAM_STOCK_CFG);
    }

    // Copy BIOS into ROM1 (use native Bios class data when available)
    if (!m_bios_path.empty() && ROM1 != nullptr) {
        const u8* bios_data = m_bios.is_loaded() ? m_bios.data() : nullptr;
        size_t    bios_size  = m_bios.is_loaded() ? Bios::size()  : 0;

        if (bios_data && bios_size > 0 && bios_size <= ROM1_SIZE_BYTES) {
            opera_mem_rom1_clear();
            memcpy(ROM1, bios_data, bios_size);
            opera_mem_rom1_byteswap32_if_le();
            LOGD("BIOS loaded into ROM1 from native Bios class (%zu bytes)", bios_size);
        } else {
            LOGE("Native Bios class did not load; ROM1 left uninitialised");
        }
    }

    // Initialise libopera core
    if (opera_3do_init(opera_callback) != 0) {
        LOGE("opera_3do_init failed");
        m_init_state = -1;
        return;
    }

    // Format NVRAM if needed
    if (NVRAM != nullptr && !opera_nvram_initialized(NVRAM, NVRAM_SIZE)) {
        opera_nvram_init(NVRAM, NVRAM_SIZE);
        LOGD("NVRAM formatted");
    }

    // Load CD image via XBUS
    if (!m_game_path.empty()) {
        opera_xbus_device_load(0, m_game_path.c_str());
        LOGD("Game loaded: %s", m_game_path.c_str());
    }

    m_initialized.store(true, std::memory_order_release);
    m_init_state = 1;

    // ----------------------------------------------------------------
    // Main loop
    // ----------------------------------------------------------------
    int render_skip_level   = 0;
    int render_skip_counter = 0;
    int64_t frame_ema_us    = 16667;

    while (m_thread_running.load(std::memory_order_acquire)) {
        int64_t t0 = monotonic_us();

        // Hard reset
        if (m_reset_requested.load(std::memory_order_acquire)) {
            m_reset_requested.store(false, std::memory_order_release);
            do_reset();
        }

        if (m_paused.load(std::memory_order_acquire)) {
            usleep(5000);
            continue;
        }

        update_pbus_input();
        opera_3do_process_frame();

        bool should_render = true;
        if (render_skip_level > 0) {
            should_render = ((render_skip_counter % (render_skip_level + 1)) == 0);
            ++render_skip_counter;
        } else {
            render_skip_counter = 0;
        }

        if (should_render) {
            render_frame(m_video_buffer, FB_WIDTH, FB_HEIGHT);
        }

        // Audio-driven pacing
        u32 buffered = audio_available();
        int64_t elapsed = monotonic_us() - t0;
        int64_t target  = 16667;
        if      (buffered > 40000) target = 20000;
        else if (buffered > 20000) target = 18000;
        else if (buffered > 8000)  target = 16667;
        else if (buffered > 3000)  target = 14000;
        else                       target = 0;

        if (elapsed < target) usleep(static_cast<useconds_t>(target - elapsed));

        frame_ema_us = (frame_ema_us * 7 + elapsed) / 8;
        if (frame_ema_us > 23000 && render_skip_level < 2) ++render_skip_level;
        else if (frame_ema_us < 17000 && render_skip_level > 0) --render_skip_level;
    }

    // Cleanup
    opera_3do_destroy();
    opera_mem_destroy();
    m_initialized.store(false, std::memory_order_release);
    m_cdrom.eject();

    LOGD("Emulator thread exiting");
}

// -----------------------------------------------------------------------
// Hard reset helper
// -----------------------------------------------------------------------
void FourdoCore::do_reset() {
    LOGD("Hard reset");
    opera_3do_destroy();
    opera_mem_destroy();

    if (opera_3do_init(opera_callback) != 0) {
        LOGE("opera_3do_init failed during reset");
        return;
    }
    if (NVRAM && !opera_nvram_initialized(NVRAM, NVRAM_SIZE)) {
        opera_nvram_init(NVRAM, NVRAM_SIZE);
    }
    if (!m_game_path.empty()) {
        opera_xbus_device_load(0, m_game_path.c_str());
    }
    LOGD("Hard reset complete");
}

} // namespace core
} // namespace fourdo

// -----------------------------------------------------------------------
// C-linkage emulator API
//
// These functions expose FourdoCore to jni_bridge.cpp using the same
// signatures that emulator_core.cpp previously provided, allowing
// emulator_core.cpp to be removed from the build.
// -----------------------------------------------------------------------
extern "C" {

int emulator_init(const char* game_path, const char* bios_path) {
    return fourdo::core::FourdoCore::instance().init(
        game_path ? game_path : "",
        bios_path ? bios_path : "");
}

void emulator_shutdown() {
    fourdo::core::FourdoCore::instance().shutdown();
}

void emulator_pause() {
    fourdo::core::FourdoCore::instance().pause();
}

void emulator_resume() {
    fourdo::core::FourdoCore::instance().resume();
}

void emulator_toggle_pause() {
    fourdo::core::FourdoCore::instance().toggle_pause();
}

void emulator_reset() {
    fourdo::core::FourdoCore::instance().reset();
}

const char* emulator_get_status() {
    return fourdo::core::FourdoCore::instance().status();
}

bool emulator_load_cd(const char* game_path) {
    return fourdo::core::FourdoCore::instance().load_cd(
        game_path ? game_path : "");
}

int emulator_audio_drain(uint32_t* out_buffer, int max_frames) {
    return fourdo::core::FourdoCore::instance().drain_audio(
        reinterpret_cast<u32*>(out_buffer), max_frames);
}

uint8_t* opera_nvram_get_data(size_t* size) {
    return fourdo::core::FourdoCore::instance().nvram_data(size);
}

bool opera_nvram_set_data(const uint8_t* data, size_t size) {
    return fourdo::core::FourdoCore::instance().nvram_set(data, size);
}

} // extern "C"
