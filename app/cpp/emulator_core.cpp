#include <android/log.h>
#include <pthread.h>
#include <string>
#include <memory>
#include <cstring>
#include <cctype>
#include <vector>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>

extern "C" {
    int opera_mem_init(int mem_cfg);
    int opera_mem_cfg(void);
    void opera_mem_destroy(void);
    void opera_mem_rom1_clear(void);
    void opera_mem_rom1_byteswap32_if_le(void);
    extern uint8_t *ROM1;
    extern uint8_t *NVRAM;

    // opera_nvram functions
    bool opera_nvram_initialized(void *buf, const int bufsize);
    void opera_nvram_init(void *buf, const int bufsize);

#ifndef NVRAM_SIZE
#define NVRAM_SIZE (1024 * 32)
#endif

    typedef uint32_t (*opera_cdrom_get_size_cb_t)(void);
    typedef void (*opera_cdrom_set_sector_cb_t)(const uint32_t sector_);
    typedef void (*opera_cdrom_read_sector_cb_t)(void *buf_);
    void opera_cdrom_set_callbacks(opera_cdrom_get_size_cb_t get_size_,
                                   opera_cdrom_set_sector_cb_t set_sector_,
                                   opera_cdrom_read_sector_cb_t read_sector_);
    int opera_vdlp_set_video_buffer(void* buf);
    uint32_t opera_dsp_loop(void);
    #include "libopera/opera_pbus.h"
}

extern "C" bool android_input_get_state(int button);
extern "C" void android_input_reset_state();

static const int DRAM_VRAM_UNSET_CFG = 0x00;
static const int DRAM_VRAM_STOCK_CFG = 0x21;
static const size_t ROM1_SIZE_BYTES = 1024 * 1024;

#define LOG_TAG "4DO-EmulatorCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations from libopera
typedef void* (*opera_ext_interface_t)(int, void*);

// XBUS declarations
extern "C" {
    typedef void* (*opera_xbus_device)(int, void*);
    void opera_xbus_init(opera_xbus_device zero_dev);
    void opera_xbus_destroy(void);
    int opera_xbus_attach(opera_xbus_device dev);
    void opera_xbus_device_load(int dev, const char *name);
    void opera_xbus_device_eject(int dev);
    void* xbus_cdrom_plugin(int proc_, void* data_);
}

extern "C" {
    uint32_t opera_3do_state_size(void);
    uint32_t opera_3do_state_save(void *buf, size_t size);
    uint32_t opera_3do_state_load(void const *buf, size_t size);
    int opera_3do_init(opera_ext_interface_t callback);
    void opera_3do_destroy(void);
    void opera_3do_process_frame(void);
}

// SDL functions - implemented in sdl_renderer.cpp
extern "C" {
    int sdl_init();
    void sdl_shutdown();
    void render_frame(const void* pixels, int width, int height);
    void get_screen_size(int* width, int* height);
}

// Emulator state
static bool g_emulator_initialized = false;
static bool g_emulator_paused = false;
static bool g_emulator_reset_requested = false;
static std::string g_game_path;
static std::string g_bios_path;
static pthread_t g_emulator_thread;
static bool g_emulator_thread_running = false;
static int g_emulator_init_state = 0; // 0=pending, 1=success, -1=failed
static int g_cd_fd = -1;
static uint32_t g_cd_sector = 0;
static uint32_t g_cd_total_sectors = 0;
static size_t g_cd_sector_size = 2048;
static size_t g_cd_sector_offset = 0;
static uint8_t* g_cd_image_ram = nullptr;
static size_t g_cd_image_ram_size = 0;
static bool g_cd_use_ram_image = false;
static uint8_t* g_video_buffer = nullptr;
static pthread_mutex_t g_cd_mutex = PTHREAD_MUTEX_INITIALIZER;

// CD sector read-ahead cache
static const size_t CD_CACHE_SECTORS = 512; // Cache 512 sectors (~1MB) for sustained FMV
static const size_t CD_RAM_PRELOAD_LIMIT = 900 * 1024 * 1024; // 900MB safety cap
static uint8_t* g_cd_cache = nullptr;
static uint8_t* g_cd_raw_buf = nullptr;       // temp buffer for raw sector reads (BIN/CUE)
static uint32_t g_cd_cache_start = UINT32_MAX; // first sector in cache
static uint32_t g_cd_cache_count = 0;          // number of valid sectors

static const int FRAMEBUFFER_WIDTH = 320;
static const int FRAMEBUFFER_HEIGHT = 240;
static const size_t FRAMEBUFFER_BYTES = FRAMEBUFFER_WIDTH * FRAMEBUFFER_HEIGHT * 2;

// Lock-free SPSC (single-producer, single-consumer) audio ring buffer.
// Power-of-2 size so we can use masking instead of modulo.
static const size_t AUDIO_RING_SIZE = 131072; // ~3s at 44100 Hz
static const size_t AUDIO_RING_MASK = AUDIO_RING_SIZE - 1;
static uint32_t g_audio_ring[AUDIO_RING_SIZE];
static volatile uint32_t g_audio_write_pos = 0; // written only by producer
static volatile uint32_t g_audio_read_pos  = 0; // written only by consumer

static inline uint32_t audio_ring_available() {
    return g_audio_write_pos - g_audio_read_pos; // works with unsigned wrap
}

static inline int64_t monotonic_us() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000LL + static_cast<int64_t>(ts.tv_nsec / 1000);
}

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
};

static void update_pbus_input() {
    opera_pbus_joypad_t joypad = {0};
    joypad.u = android_input_get_state(BUTTON_DPAD_UP) ? 1 : 0;
    joypad.d = android_input_get_state(BUTTON_DPAD_DOWN) ? 1 : 0;
    joypad.l = android_input_get_state(BUTTON_DPAD_LEFT) ? 1 : 0;
    joypad.r = android_input_get_state(BUTTON_DPAD_RIGHT) ? 1 : 0;
    joypad.p = android_input_get_state(BUTTON_PLAY_PAUSE) ? 1 : 0;
    joypad.a = android_input_get_state(BUTTON_A) ? 1 : 0;
    joypad.b = android_input_get_state(BUTTON_B) ? 1 : 0;
    joypad.c = android_input_get_state(BUTTON_C) ? 1 : 0;
    joypad.x = android_input_get_state(BUTTON_STOP) ? 1 : 0;
    joypad.lt = android_input_get_state(BUTTON_L1) ? 1 : 0;
    joypad.rt = android_input_get_state(BUTTON_R1) ? 1 : 0;

    opera_pbus_reset();
    opera_pbus_add_joypad(&joypad);
}

static std::string trim(const std::string& value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(start, end - start);
}

static std::string strip_quotes(const std::string& value) {
    std::string out = trim(value);
    if (out.size() >= 2 && ((out.front() == '"' && out.back() == '"') || (out.front() == '\'' && out.back() == '\''))) {
        return out.substr(1, out.size() - 2);
    }
    return out;
}

static std::string normalize_path(std::string value) {
    for (char& character : value) {
        if (character == '\\') {
            character = '/';
        }
    }
    return value;
}

static std::string to_lower(std::string value) {
    for (char& character : value) {
        character = static_cast<char>(std::tolower(static_cast<unsigned char>(character)));
    }
    return value;
}

static std::string get_dirname(const std::string& path) {
    size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return "";
    }
    return path.substr(0, slash);
}

static std::string join_path(const std::string& base, const std::string& child) {
    if (child.empty()) {
        return base;
    }
    if (!child.empty() && child[0] == '/') {
        return child;
    }
    if (base.empty()) {
        return child;
    }
    return base + "/" + child;
}

static bool ends_with_ignore_case(const std::string& value, const std::string& suffix) {
    if (suffix.size() > value.size()) {
        return false;
    }
    return to_lower(value.substr(value.size() - suffix.size())) == to_lower(suffix);
}

static std::string parse_cue_image_path(const std::string& cue_path,
                                        size_t* sector_size,
                                        size_t* sector_offset) {
    int cue_fd = open(cue_path.c_str(), O_RDONLY);
    if (cue_fd < 0) {
        return "";
    }

    struct stat stat_buf;
    if (fstat(cue_fd, &stat_buf) != 0 || stat_buf.st_size <= 0) {
        close(cue_fd);
        return "";
    }

    std::string cue_text;
    cue_text.resize(static_cast<size_t>(stat_buf.st_size));
    ssize_t read_count = read(cue_fd, &cue_text[0], cue_text.size());
    close(cue_fd);
    if (read_count <= 0) {
        return "";
    }
    cue_text.resize(static_cast<size_t>(read_count));

    std::string current_file;
    std::string selected_file;
    bool found_data_track = false;
    size_t line_start = 0;
    while (line_start < cue_text.size()) {
        size_t line_end = cue_text.find('\n', line_start);
        if (line_end == std::string::npos) {
            line_end = cue_text.size();
        }
        std::string line = trim(cue_text.substr(line_start, line_end - line_start));
        std::string lower = to_lower(line);

        if (lower.rfind("file", 0) == 0) {
            size_t first_quote = line.find('"');
            size_t second_quote = (first_quote == std::string::npos) ? std::string::npos : line.find('"', first_quote + 1);
            if (first_quote != std::string::npos && second_quote != std::string::npos && second_quote > first_quote) {
                current_file = normalize_path(line.substr(first_quote + 1, second_quote - first_quote - 1));
            } else {
                size_t file_token_end = line.find(' ', 5);
                if (file_token_end != std::string::npos) {
                    std::string maybe_file = trim(line.substr(5, file_token_end - 5));
                    if (!maybe_file.empty()) {
                        current_file = normalize_path(strip_quotes(maybe_file));
                    }
                }
            }

            if (selected_file.empty() && !current_file.empty()) {
                selected_file = current_file;
            }
        }

        if (lower.rfind("track", 0) == 0) {
            if (lower.find("mode1/2352") != std::string::npos) {
                *sector_size = 2352;
                *sector_offset = 16;
                if (!current_file.empty()) {
                    selected_file = current_file;
                }
                found_data_track = true;
            } else if (lower.find("mode2/2352") != std::string::npos) {
                *sector_size = 2352;
                *sector_offset = 24;
                if (!current_file.empty()) {
                    selected_file = current_file;
                }
                found_data_track = true;
            } else if (lower.find("mode1/2048") != std::string::npos) {
                *sector_size = 2048;
                *sector_offset = 0;
                if (!current_file.empty()) {
                    selected_file = current_file;
                }
                found_data_track = true;
            }

            if (found_data_track) {
                break;
            }
        }

        line_start = line_end + 1;
    }

    if (selected_file.empty()) {
        return "";
    }

    return join_path(get_dirname(cue_path), selected_file);
}

static uint32_t read_be32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) |
           (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) |
           static_cast<uint32_t>(data[3]);
}

static void close_cd_image() {
    pthread_mutex_lock(&g_cd_mutex);
    if (g_cd_fd >= 0) {
        close(g_cd_fd);
        g_cd_fd = -1;
    }
    if (g_cd_image_ram != nullptr) {
        free(g_cd_image_ram);
        g_cd_image_ram = nullptr;
    }
    g_cd_image_ram_size = 0;
    g_cd_use_ram_image = false;
    g_cd_sector = 0;
    g_cd_total_sectors = 0;
    g_cd_sector_size = 2048;
    g_cd_sector_offset = 0;
    g_cd_cache_start = UINT32_MAX;
    g_cd_cache_count = 0;
    pthread_mutex_unlock(&g_cd_mutex);
}

static void clear_nvram() {
    LOGD("Clearing NVRAM");
    // Clear NVRAM by resetting the emulator
    if (g_emulator_initialized) {
        opera_3do_destroy();
        opera_mem_destroy();
        g_emulator_initialized = false;
    }
}

static bool open_cd_image(const std::string& game_path) {
    close_cd_image();
    if (game_path.empty()) {
        clear_nvram();
        return false;
    }

    std::string image_path = game_path;
    size_t sector_size = 0;
    size_t sector_offset = 0;

    if (ends_with_ignore_case(game_path, ".cue")) {
        image_path = parse_cue_image_path(game_path, &sector_size, &sector_offset);
        if (image_path.empty()) {
            LOGE("Failed to parse CUE file: %s", game_path.c_str());
            return false;
        }
    }

    int fd = open(image_path.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("Failed to open CD image: %s", image_path.c_str());
        return false;
    }

    struct stat stat_buf;
    if (fstat(fd, &stat_buf) != 0 || stat_buf.st_size <= 0) {
        LOGE("Failed to stat CD image: %s", image_path.c_str());
        close(fd);
        return false;
    }

    if (sector_size == 0) {
        if ((stat_buf.st_size % 2352) == 0) {
            sector_size = 2352;
            sector_offset = 16;
        } else {
            sector_size = 2048;
            sector_offset = 0;
        }
    }

    if (sector_size <= sector_offset) {
        LOGE("Invalid sector layout for image: %s", image_path.c_str());
        close(fd);
        return false;
    }

    uint32_t total_sectors = static_cast<uint32_t>(stat_buf.st_size / static_cast<off_t>(sector_size));
    uint8_t blocks_be[4] = {0};
    off_t block_count_pos = static_cast<off_t>(sector_offset) + 80;
    ssize_t block_read = pread(fd, blocks_be, sizeof(blocks_be), block_count_pos);
    if (block_read == static_cast<ssize_t>(sizeof(blocks_be))) {
        uint32_t header_blocks = read_be32(blocks_be);
        if (header_blocks > 0) {
            total_sectors = header_blocks;
        }
    }

    bool loaded_into_ram = false;
    uint8_t* ram_image = nullptr;
    size_t ram_size = 0;

    if (stat_buf.st_size > 0 &&
        static_cast<uint64_t>(stat_buf.st_size) <= static_cast<uint64_t>(CD_RAM_PRELOAD_LIMIT)) {
        ram_size = static_cast<size_t>(stat_buf.st_size);
        ram_image = static_cast<uint8_t*>(malloc(ram_size));
        if (ram_image != nullptr) {
            size_t total_read = 0;
            while (total_read < ram_size) {
                ssize_t r = pread(fd, ram_image + total_read, ram_size - total_read, static_cast<off_t>(total_read));
                if (r <= 0) {
                    break;
                }
                total_read += static_cast<size_t>(r);
            }
            if (total_read == ram_size) {
                loaded_into_ram = true;
                LOGD("Preloaded CD image into RAM: %s (%zu bytes)", image_path.c_str(), ram_size);
            } else {
                LOGE("CD RAM preload incomplete (%zu/%zu), falling back to file I/O", total_read, ram_size);
                free(ram_image);
                ram_image = nullptr;
                ram_size = 0;
            }
        } else {
            LOGD("CD RAM preload allocation failed (%lld bytes), using file I/O", static_cast<long long>(stat_buf.st_size));
        }
    } else {
        LOGD("CD image too large for RAM preload (%lld bytes > %zu), using file I/O",
             static_cast<long long>(stat_buf.st_size), CD_RAM_PRELOAD_LIMIT);
    }

    pthread_mutex_lock(&g_cd_mutex);
    g_cd_sector_size = sector_size;
    g_cd_sector_offset = sector_offset;
    g_cd_total_sectors = total_sectors;
    g_cd_use_ram_image = loaded_into_ram;
    g_cd_image_ram = ram_image;
    g_cd_image_ram_size = ram_size;
    g_cd_fd = loaded_into_ram ? -1 : fd;
    g_cd_sector = 0;
    pthread_mutex_unlock(&g_cd_mutex);

    if (loaded_into_ram) {
        close(fd);
    } else {
        // Hint the kernel that we'll be reading this file sequentially
        posix_fadvise(fd, 0, stat_buf.st_size, POSIX_FADV_SEQUENTIAL);
    }

    LOGD("Opened CD image: %s (sector_size=%zu offset=%zu sectors=%u)",
         image_path.c_str(),
         g_cd_sector_size,
         g_cd_sector_offset,
         g_cd_total_sectors);
    return true;
}

static void eject_cd_image() {
    LOGD("Ejecting CD image");
    close_cd_image();
    clear_nvram();
}

static uint32_t cdimage_get_size() {
    pthread_mutex_lock(&g_cd_mutex);
    uint32_t size = g_cd_total_sectors;
    pthread_mutex_unlock(&g_cd_mutex);
    return size;
}

static void cdimage_set_sector(const uint32_t sector) {
    pthread_mutex_lock(&g_cd_mutex);
    g_cd_sector = sector;
    pthread_mutex_unlock(&g_cd_mutex);
}

static void cdimage_read_sector(void* buffer) {
    if (buffer == nullptr) {
        return;
    }

    memset(buffer, 0, 2048);
    pthread_mutex_lock(&g_cd_mutex);
    if ((g_cd_fd < 0 && !g_cd_use_ram_image) || g_cd_sector_size <= g_cd_sector_offset) {
        pthread_mutex_unlock(&g_cd_mutex);
        return;
    }

    uint32_t sector = g_cd_sector;

    // RAM-backed path: no storage I/O during streaming
    if (g_cd_use_ram_image && g_cd_image_ram != nullptr) {
        size_t raw_pos = static_cast<size_t>(sector) * g_cd_sector_size + g_cd_sector_offset;
        if (raw_pos + 2048 <= g_cd_image_ram_size) {
            memcpy(buffer, g_cd_image_ram + raw_pos, 2048);
        }
        pthread_mutex_unlock(&g_cd_mutex);
        return;
    }

    // Check if sector is in cache
    if (g_cd_cache != nullptr &&
        sector >= g_cd_cache_start &&
        sector < g_cd_cache_start + g_cd_cache_count) {
        // Cache hit
        uint32_t offset = sector - g_cd_cache_start;
        memcpy(buffer, g_cd_cache + (offset * 2048), 2048);

        // When we're past 75% of the cache, tell the kernel to prefetch
        // the next batch so it's in the page cache by the time we need it.
        if (g_cd_cache_count > 0 && offset >= (g_cd_cache_count * 3 / 4)) {
            uint32_t next_start = g_cd_cache_start + g_cd_cache_count;
            if (next_start < g_cd_total_sectors) {
                uint32_t prefetch_count = CD_CACHE_SECTORS;
                if (next_start + prefetch_count > g_cd_total_sectors)
                    prefetch_count = g_cd_total_sectors - next_start;
                off_t pf_pos = static_cast<off_t>(next_start) * static_cast<off_t>(g_cd_sector_size);
                off_t pf_len = static_cast<off_t>(prefetch_count) * static_cast<off_t>(g_cd_sector_size);
                posix_fadvise(g_cd_fd, pf_pos, pf_len, POSIX_FADV_WILLNEED);
            }
        }

        pthread_mutex_unlock(&g_cd_mutex);
        return;
    }

    // Cache miss - read ahead a batch of sectors
    if (g_cd_cache == nullptr) {
        g_cd_cache = static_cast<uint8_t*>(malloc(CD_CACHE_SECTORS * 2048));
        if (g_cd_cache == nullptr) {
            // Fallback: single sector read
            off_t pos = static_cast<off_t>(sector) * static_cast<off_t>(g_cd_sector_size)
                      + static_cast<off_t>(g_cd_sector_offset);
            pread(g_cd_fd, buffer, 2048, pos);
            pthread_mutex_unlock(&g_cd_mutex);
            return;
        }
    }

    // Determine how many sectors to read ahead
    uint32_t sectors_to_read = CD_CACHE_SECTORS;
    if (sector + sectors_to_read > g_cd_total_sectors) {
        sectors_to_read = g_cd_total_sectors - sector;
    }
    if (sectors_to_read == 0) sectors_to_read = 1;

    // Hint the kernel to read-ahead sequentially from this position
    off_t advise_pos = static_cast<off_t>(sector) * static_cast<off_t>(g_cd_sector_size);
    off_t advise_len = static_cast<off_t>(sectors_to_read) * static_cast<off_t>(g_cd_sector_size);
    posix_fadvise(g_cd_fd, advise_pos, advise_len, POSIX_FADV_SEQUENTIAL);

    // Read all sectors in one big I/O if sector layout is simple (2048-byte sectors, no offset)
    if (g_cd_sector_size == 2048 && g_cd_sector_offset == 0) {
        off_t pos = static_cast<off_t>(sector) * 2048;
        ssize_t total_bytes = static_cast<ssize_t>(sectors_to_read) * 2048;
        ssize_t bytes_read = pread(g_cd_fd, g_cd_cache, total_bytes, pos);
        if (bytes_read > 0) {
            g_cd_cache_start = sector;
            g_cd_cache_count = static_cast<uint32_t>(bytes_read / 2048);
        } else {
            g_cd_cache_start = UINT32_MAX;
            g_cd_cache_count = 0;
        }
    } else {
        // For raw/bin images with sector headers, do ONE bulk pread then
        // strip headers.  This avoids hundreds of individual syscalls that
        // can stall FMV on slow storage.
        ssize_t raw_bytes = static_cast<ssize_t>(sectors_to_read) * static_cast<ssize_t>(g_cd_sector_size);

        // Allocate raw buffer lazily (sized for max cache fill)
        if (g_cd_raw_buf == nullptr) {
            g_cd_raw_buf = static_cast<uint8_t*>(malloc(CD_CACHE_SECTORS * 2352)); // max raw sector size
        }

        if (g_cd_raw_buf != nullptr) {
            off_t pos = static_cast<off_t>(sector) * static_cast<off_t>(g_cd_sector_size);
            ssize_t bytes_read = pread(g_cd_fd, g_cd_raw_buf, raw_bytes, pos);
            if (bytes_read > 0) {
                uint32_t sectors_got = static_cast<uint32_t>(bytes_read / static_cast<ssize_t>(g_cd_sector_size));
                for (uint32_t i = 0; i < sectors_got; i++) {
                    memcpy(g_cd_cache + (i * 2048),
                           g_cd_raw_buf + (i * g_cd_sector_size) + g_cd_sector_offset,
                           2048);
                }
                g_cd_cache_start = sector;
                g_cd_cache_count = sectors_got;
            } else {
                g_cd_cache_start = UINT32_MAX;
                g_cd_cache_count = 0;
            }
        } else {
            // Fallback: individual reads if malloc failed
            uint32_t valid = 0;
            for (uint32_t i = 0; i < sectors_to_read; i++) {
                off_t pos = static_cast<off_t>(sector + i) * static_cast<off_t>(g_cd_sector_size)
                          + static_cast<off_t>(g_cd_sector_offset);
                ssize_t br = pread(g_cd_fd, g_cd_cache + (i * 2048), 2048, pos);
                if (br == 2048) {
                    valid = i + 1;
                } else {
                    break;
                }
            }
            g_cd_cache_start = sector;
            g_cd_cache_count = valid;
        }
    }

    // Copy requested sector from cache
    if (g_cd_cache_count > 0) {
        memcpy(buffer, g_cd_cache, 2048);
    }
    pthread_mutex_unlock(&g_cd_mutex);
}

// Lock-free push: only producer (emulator thread) calls this
static void push_audio_sample(uint32_t packed_sample) {
    uint32_t wp = g_audio_write_pos;
    uint32_t rp = g_audio_read_pos;
    // If buffer is full, overwrite oldest sample and advance read pointer
    // This prevents silent drops that cause crackle - a slight glitch from
    // overwrite is less audible than gaps from dropped samples.
    if ((wp - rp) >= AUDIO_RING_SIZE) {
        // Buffer full - advance read pos to make room (lose oldest sample)
        __sync_fetch_and_add(&g_audio_read_pos, 1);
    }
    g_audio_ring[wp & AUDIO_RING_MASK] = packed_sample;
    __sync_synchronize(); // memory barrier: data written before index update
    g_audio_write_pos = wp + 1;
}

// External interface callback for libopera
static void* opera_callback(int command, void* data) {
    switch (command) {
        case 0: // Get video buffer - request video output
            break;
        case 1: // Video refresh
            break;
        case 2: // Audio
            push_audio_sample(opera_dsp_loop());
            break;
        default:
            LOGD("  -> Unknown command: %d", command);
    }
    return nullptr;
}

// Load file content
static int load_file(const char* path, void** buffer_out, size_t* size_out) {
    if (!path || !buffer_out || !size_out) {
        return -1;
    }
    
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("Failed to open file: %s", path);
        return -1;
    }
    
    struct stat st;
    if (fstat(fd, &st) < 0) {
        LOGE("Failed to stat file: %s", path);
        close(fd);
        return -1;
    }
    
    size_t size = st.st_size;
    void* buffer = malloc(size);
    if (!buffer) {
        LOGE("Failed to allocate memory for file");
        close(fd);
        return -1;
    }
    
    ssize_t read_size = read(fd, buffer, size);
    close(fd);
    
    if (read_size != (ssize_t)size) {
        LOGE("Failed to read file: %s", path);
        free(buffer);
        return -1;
    }
    
    *buffer_out = buffer;
    *size_out = size;
    
    LOGD("Loaded file: %s (%zu bytes)", path, size);
    return 0;
}

// Emulator thread function
static void* emulator_thread_func(void* arg) {
    LOGD("Emulator thread starting...");

    if (g_video_buffer == nullptr) {
        g_video_buffer = static_cast<uint8_t*>(malloc(FRAMEBUFFER_BYTES));
        if (g_video_buffer == nullptr) {
            LOGE("Failed to allocate video buffer");
            g_emulator_init_state = -1;
            return nullptr;
        }
    }
    memset(g_video_buffer, 0, FRAMEBUFFER_BYTES);

    if (opera_vdlp_set_video_buffer(g_video_buffer) != 0) {
        LOGE("Failed to set libopera video buffer");
        g_emulator_init_state = -1;
        return nullptr;
    }

    opera_cdrom_set_callbacks(cdimage_get_size, cdimage_set_sector, cdimage_read_sector);
    if (g_game_path.empty()) {
        close_cd_image();
        LOGD("Starting without disc image (BIOS boot)");
    } else if (!open_cd_image(g_game_path)) {
        LOGE("No valid game image available for path: %s", g_game_path.c_str());
    }

    if (!g_bios_path.empty()) {
        if (opera_mem_cfg() == DRAM_VRAM_UNSET_CFG) {
            LOGD("Initializing memory before BIOS load");
            opera_mem_init(DRAM_VRAM_STOCK_CFG);
        }

        void* bios_buffer = nullptr;
        size_t bios_size = 0;
        if (load_file(g_bios_path.c_str(), &bios_buffer, &bios_size) == 0) {
            if (bios_size > ROM1_SIZE_BYTES) {
                LOGE("BIOS file too large: %zu bytes (max %zu)", bios_size, ROM1_SIZE_BYTES);
            } else if (ROM1 == nullptr) {
                LOGE("ROM1 memory not initialized");
            } else {
                opera_mem_rom1_clear();
                memcpy(ROM1, bios_buffer, bios_size);
                opera_mem_rom1_byteswap32_if_le();
                LOGD("Loaded BIOS into ROM1: %s (%zu bytes)", g_bios_path.c_str(), bios_size);
            }

            free(bios_buffer);
        } else {
            LOGE("Failed to load BIOS from path: %s", g_bios_path.c_str());
        }
    } else {
        LOGD("No BIOS path provided; using default core behavior");
    }
    
    // Try to initialize the emulator
    LOGD("Initializing libopera...");
    int init_result = opera_3do_init(opera_callback);
    if (init_result != 0) {
        LOGE("Failed to initialize 3DO emulator: %d", init_result);
        g_emulator_init_state = -1;
    } else {
        LOGD("libopera initialized successfully!");
        g_emulator_initialized = true;
        g_emulator_init_state = 1;

        // Initialize NVRAM if not already formatted
        if (NVRAM != nullptr && !opera_nvram_initialized(NVRAM, NVRAM_SIZE)) {
            LOGD("NVRAM not initialized, formatting...");
            opera_nvram_init(NVRAM, NVRAM_SIZE);
            LOGD("NVRAM formatted successfully");
        }

        // Load the game if path is provided
        if (!g_game_path.empty()) {
            LOGD("Loading game: %s", g_game_path.c_str());
            opera_xbus_device_load(0, g_game_path.c_str());
            LOGD("Game loaded!");
        } else {
            LOGD("No game path specified!");
        }
    }
    
    // Main emulator loop
    int render_skip_level = 0; // 0=render every frame, 1=every 2nd, 2=every 3rd
    int render_skip_counter = 0;
    int64_t frame_time_ema_us = 16667;

    while (g_emulator_thread_running) {
        int64_t frame_start_us = monotonic_us();

        if (g_emulator_reset_requested && g_emulator_initialized) {
            g_emulator_reset_requested = false;

            LOGD("Resetting emulator core...");
            opera_3do_destroy();
            opera_mem_destroy();
            g_emulator_initialized = false;

            int reset_init_result = opera_3do_init(opera_callback);
            if (reset_init_result != 0) {
                LOGE("Failed to reinitialize 3DO emulator during reset: %d", reset_init_result);
            } else {
                g_emulator_initialized = true;

                // Re-initialize NVRAM after reset
                if (NVRAM != nullptr && !opera_nvram_initialized(NVRAM, NVRAM_SIZE)) {
                    LOGD("Re-initializing NVRAM after reset...");
                    opera_nvram_init(NVRAM, NVRAM_SIZE);
                }

                if (!g_game_path.empty()) {
                    opera_xbus_device_load(0, g_game_path.c_str());
                }
                LOGD("Emulator reset completed");
            }
        }

        if (g_emulator_initialized && !g_emulator_paused) {
            update_pbus_input();
            // Process one frame
            opera_3do_process_frame();

            // Adaptive render skipping under heavy load:
            // keep emulation/audio running at speed by dropping visual frames first.
            bool should_render = true;
            if (render_skip_level > 0) {
                should_render = ((render_skip_counter % (render_skip_level + 1)) == 0);
                render_skip_counter++;
            } else {
                render_skip_counter = 0;
            }

            if (should_render) {
                // Render frame (non-blocking: skips if surface is busy)
                render_frame(g_video_buffer, FRAMEBUFFER_WIDTH, FRAMEBUFFER_HEIGHT);
            }
        } else {
            usleep(5000);
            continue;
        }

        // Audio-driven pacing: prioritize smooth audio over exact 60fps.
        // During FMV, the 3DO generates variable frame rates, so we
        // gate primarily on audio buffer fill level.
        uint32_t buffered = audio_ring_available();
        int64_t elapsed_us = monotonic_us() - frame_start_us;
        int64_t target_us;

        if (buffered > 40000) {
            // Audio very far ahead - slow down significantly
            target_us = 20000;
        } else if (buffered > 20000) {
            // Audio well ahead - slow emulator down to let audio drain
            target_us = 18000;
        } else if (buffered > 8000) {
            // Healthy buffer - target ~60fps
            target_us = 16667;
        } else if (buffered > 3000) {
            // Buffer thinning - run a bit faster
            target_us = 14000;
        } else {
            // Buffer nearly empty or during FMV fast-read - run as fast as possible
            target_us = 0;
        }

        if (elapsed_us < target_us) {
            usleep(static_cast<useconds_t>(target_us - elapsed_us));
        }

        // Update moving average frame time and tune render skip level.
        frame_time_ema_us = (frame_time_ema_us * 7 + elapsed_us) / 8;

        int prev_skip = render_skip_level;
        if (frame_time_ema_us > 23000) {
            if (render_skip_level < 2) {
                render_skip_level++;
            }
        } else if (frame_time_ema_us < 17000) {
            if (render_skip_level > 0) {
                render_skip_level--;
            }
        }

        if (prev_skip != render_skip_level) {
            LOGD("Adaptive render skip level: %d (ema=%lldus)",
                 render_skip_level,
                 static_cast<long long>(frame_time_ema_us));
        }
    }
    
    LOGD("Emulator thread loop ended");
    
    // Cleanup
    if (g_emulator_initialized) {
        opera_3do_destroy();
        opera_mem_destroy();
        g_emulator_initialized = false;
    }

    close_cd_image();
    
    LOGD("Emulator thread exiting");
    return nullptr;
}

// JNI-callable functions
extern "C" {

int sdl_init() {
    LOGD("SDL_Init called");
    return 0;
}

void sdl_shutdown() {
    LOGD("SDL_shutdown called");
}

int emulator_init(const char* game_path, const char* bios_path) {
    LOGD("emulator_init called with game path: %s, bios path: %s",
         game_path ? game_path : "null",
         bios_path ? bios_path : "null");
    
    if (g_emulator_initialized) {
        LOGD("Emulator already initialized");
        return 0;
    }
    
    if (game_path && game_path[0] != '\0') {
        g_game_path = game_path;
    } else {
        g_game_path.clear();
    }

    if (bios_path) {
        g_bios_path = bios_path;
    } else {
        g_bios_path.clear();
    }
    
    // Start emulator thread
    g_emulator_thread_running = true;
    g_emulator_paused = false;
    g_emulator_init_state = 0;
    
    int result = pthread_create(&g_emulator_thread, nullptr, emulator_thread_func, nullptr);
    if (result != 0) {
        LOGE("Failed to create emulator thread: %d", result);
        return -1;
    }
    
    // Wait for initialization result (up to 2 seconds)
    for (int i = 0; i < 100 && g_emulator_init_state == 0; ++i) {
        usleep(20000);
    }
    
    LOGD("Emulator initialized");
    return (g_emulator_init_state == 1) ? 0 : -1;
}

void emulator_shutdown() {
    LOGD("emulator_shutdown called");
    
    g_emulator_thread_running = false;
    g_emulator_paused = false;
    
    if (g_emulator_thread) {
        pthread_join(g_emulator_thread, nullptr);
        g_emulator_thread = 0;
    }
    g_emulator_initialized = false;
    g_emulator_init_state = 0;
    android_input_reset_state();

    close_cd_image();

    if (g_cd_cache != nullptr) {
        free(g_cd_cache);
        g_cd_cache = nullptr;
    }

    if (g_cd_raw_buf != nullptr) {
        free(g_cd_raw_buf);
        g_cd_raw_buf = nullptr;
    }

    if (g_video_buffer != nullptr) {
        free(g_video_buffer);
        g_video_buffer = nullptr;
    }

    g_audio_write_pos = 0;
    g_audio_read_pos = 0;
    
    LOGD("Emulator shutdown complete");
}

void emulator_pause() {
    LOGD("emulator_pause called");
    g_emulator_paused = true;
}

void emulator_resume() {
    LOGD("emulator_resume called");
    g_emulator_paused = false;
}

void emulator_toggle_pause() {
    LOGD("emulator_toggle_pause: %s", g_emulator_paused ? "resuming" : "pausing");
    g_emulator_paused = !g_emulator_paused;
}

void emulator_reset() {
    LOGD("emulator_reset called");
    g_emulator_reset_requested = true;
}

const char* emulator_get_status() {
    if (g_emulator_initialized) {
        return g_emulator_paused ? "Paused" : "Running";
    }
    return "Not Running";
}

bool emulator_load_cd(const char* game_path) {
    if (game_path == nullptr || game_path[0] == '\0') {
        LOGE("emulator_load_cd called with empty path");
        return false;
    }

    std::string new_game_path = game_path;
    LOGD("emulator_load_cd requested path: %s", new_game_path.c_str());
    if (!open_cd_image(new_game_path)) {
        LOGE("Failed to open CD image for load: %s", new_game_path.c_str());
        return false;
    }

    g_game_path = new_game_path;

    if (g_emulator_initialized) {
        bool was_paused = g_emulator_paused;
        g_emulator_paused = true;
        usleep(30000);

        LOGD("Ejecting current CD before load");
        opera_xbus_device_eject(0);
        usleep(30000);

        LOGD("Loading CD image at runtime: %s", g_game_path.c_str());
        opera_xbus_device_load(0, g_game_path.c_str());
        usleep(30000);

        g_emulator_paused = was_paused;
    }

    return true;
}

bool emulator_eject_cd() {
    LOGD("Ejecting CD image");
    return emulator_load_cd(nullptr);
}

// Lock-free drain: only consumer (Java audio thread via JNI) calls this
int emulator_audio_drain(uint32_t* out_buffer, int max_frames) {
    if (out_buffer == nullptr || max_frames <= 0) {
        return 0;
    }

    uint32_t rp = g_audio_read_pos;
    uint32_t wp = g_audio_write_pos;
    __sync_synchronize(); // ensure we see latest data

    uint32_t avail = wp - rp;
    if (avail == 0) return 0;

    int count = (avail < (uint32_t)max_frames) ? (int)avail : max_frames;
    for (int i = 0; i < count; i++) {
        out_buffer[i] = g_audio_ring[(rp + i) & AUDIO_RING_MASK];
    }
    __sync_synchronize(); // data read before advancing pointer
    g_audio_read_pos = rp + count;

    return count;
}

// NVRAM data access functions for JNI bridge
uint8_t* opera_nvram_get_data(size_t* size) {
    if (NVRAM == nullptr || !g_emulator_initialized) {
        if (size) *size = 0;
        return nullptr;
    }
    if (size) *size = NVRAM_SIZE;
    return NVRAM;
}

bool opera_nvram_set_data(const uint8_t* data, size_t size) {
    if (NVRAM == nullptr || data == nullptr || !g_emulator_initialized) {
        return false;
    }
    size_t copy_size = (size < (size_t)NVRAM_SIZE) ? size : (size_t)NVRAM_SIZE;
    memcpy(NVRAM, data, copy_size);
    LOGD("NVRAM data loaded: %zu bytes", copy_size);
    return true;
}

} // extern "C"