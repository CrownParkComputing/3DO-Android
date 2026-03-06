/**
 * 4DO Native Core - Main Emulator Coordinator
 * Integrates all native subsystems into a unified emulation engine.
 *
 * This replaces the previous approach of calling legacy C backend functions
 * directly from emulator_core.cpp.  All hardware-related state is now
 * owned by FourdoCore; the legacy backend is used internally as the hardware-
 * emulation backend until each component is rewritten natively.
 */

#ifndef FOURDO_NATIVE_CORE_H
#define FOURDO_NATIVE_CORE_H

#include "native_types.h"
#include "native_log.h"
#include "native_memory.h"
#include "native_bios.h"
#include "native_nvram.h"
#include "native_cdrom.h"
#include "native_input.h"

#include <string>
#include <atomic>
#include <pthread.h>

namespace fourdo {
namespace core {

/**
 * FourdoCore - Top-level emulator coordinator.
 *
 * Owns all subsystem instances and drives the emulation loop.
 * The JNI bridge (jni_bridge.cpp / emulator_core.cpp) talks to
 * this class rather than to the legacy backend directly.
 */
class FourdoCore {
public:
    // Singleton accessor
    static FourdoCore& instance();

    // Non-copyable / non-movable
    FourdoCore(const FourdoCore&) = delete;
    FourdoCore& operator=(const FourdoCore&) = delete;

    /**
     * Initialize the emulator.
     * @param game_path  Path to the CD image (ISO/BIN/CUE) or empty for BIOS boot.
     * @param bios_path  Path to the BIOS ROM file.
     * @return 0 on success, non-zero on failure.
     */
    int init(const std::string& game_path, const std::string& bios_path);

    /**
     * Shut down the emulator and release all resources.
     */
    void shutdown();

    /** Pause emulation (audio thread keeps running). */
    void pause();

    /** Resume emulation after a pause. */
    void resume();

    /** Toggle pause state. */
    void toggle_pause();

    /** Request a hard reset on the next frame boundary. */
    void reset();

    /**
     * Load (or eject) a CD image at runtime.
     * Pass an empty string to eject the current disc.
     */
    bool load_cd(const std::string& path);

    /**
     * Drain queued audio frames into caller's buffer.
     * @param out_buffer  Output buffer (packed stereo: left in low 16 bits).
     * @param max_frames  Capacity of out_buffer in frames.
     * @return Number of frames actually written.
     */
    int drain_audio(u32* out_buffer, int max_frames);

    /**
     * Get NVRAM raw data pointer and size.
     * Returns nullptr when emulator is not running.
     */
    u8* nvram_data(size_t* out_size);

    /**
     * Replace NVRAM contents.
     * @return true on success.
     */
    bool nvram_set(const u8* data, size_t size);

    /** Human-readable status string. */
    const char* status() const;

    /** True if the emulator has been successfully initialised. */
    bool is_running() const { return m_initialized.load(std::memory_order_acquire); }

    /** True if emulation is currently paused. */
    bool is_paused()  const { return m_paused.load(std::memory_order_acquire); }

    /** Access the input system (thread-safe by design of InputSystem). */
    InputSystem& input() { return m_input; }

    /**
     * Return the number of bytes required for a full save-state buffer.
     * Returns 0 when the emulator is not running.
     */
    size_t state_size() const;

    /**
     * Write the current emulator state into buf (must be >= state_size() bytes).
     * @return true on success.
     */
    bool save_state(void* buf, size_t buf_size);

    /**
     * Restore the emulator state from buf.
     * @return true on success.
     */
    bool load_state(const void* buf, size_t buf_size);

    /**
     * Set the console region. Must be called before init() to take effect.
     * @param region  0 = NTSC (default), 1 = PAL1, 2 = PAL2
     */
    void set_region(int region);

    /**
     * Get the current console region.
     * @return 0 = NTSC, 1 = PAL1, 2 = PAL2
     */
    int get_region() const;

    /**
     * Set CPU speed multiplier (1.0f = default 12.5 MHz).
     * Changes take effect immediately (live adjustment).
     */
    void set_cpu_speed(float multiplier);

    // Static libopera CD-ROM callback trampolines
    static uint32_t opera_cdrom_get_size_cb();
    static void     opera_cdrom_set_sector_cb(uint32_t sector);
    static void     opera_cdrom_read_sector_cb(void* buf);

    // Static libopera audio callback helper
    static void opera_push_audio(uint32_t packed);

private:
    FourdoCore();
    ~FourdoCore();

    // ----------------------------------------------------------------
    // Subsystems (native C++ components)
    // ----------------------------------------------------------------
    Bios          m_bios;
    Nvram         m_nvram;
    CdromInterface m_cdrom;
    InputSystem   m_input;

    // ----------------------------------------------------------------
    // Emulator state
    // ----------------------------------------------------------------
    std::atomic<bool> m_initialized{false};
    std::atomic<bool> m_paused{false};
    std::atomic<bool> m_reset_requested{false};
    std::atomic<bool> m_thread_running{false};

    std::string m_game_path;
    std::string m_bios_path;
    int         m_region{0};  // 0=NTSC (default), 1=PAL1, 2=PAL2

    pthread_t m_thread{};
    int       m_init_state{0}; // 0=pending, 1=ok, -1=failed

    // ----------------------------------------------------------------
    // Video buffer (320×240, RGB565 – owned by this core)
    // ----------------------------------------------------------------
    static constexpr int FB_WIDTH  = 320;
    static constexpr int FB_HEIGHT = 240;
    static constexpr size_t FB_BYTES = FB_WIDTH * FB_HEIGHT * 2;
    u8* m_video_buffer{nullptr};

    // ----------------------------------------------------------------
    // Lock-free SPSC audio ring buffer
    // ----------------------------------------------------------------
    static constexpr size_t AUDIO_RING_SIZE = 131072; // ~3 s at 44100 Hz
    static constexpr size_t AUDIO_RING_MASK = AUDIO_RING_SIZE - 1;
    u32 m_audio_ring[AUDIO_RING_SIZE]{};
    std::atomic<u32> m_audio_write_pos{0};
    std::atomic<u32> m_audio_read_pos{0};

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------
    static void* thread_entry(void* arg);
    void emulator_loop();
    void do_reset();
    void push_audio_sample(u32 packed);
    u32  audio_available() const;
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_CORE_H
