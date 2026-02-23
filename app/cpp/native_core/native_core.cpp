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
#include <cstdarg>
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

    // 3DO state (save/load)
    uint32_t opera_3do_state_size(void);
    uint32_t opera_3do_state_save(void* buf, uint32_t size);
    uint32_t opera_3do_state_load(void const* buf, uint32_t size);

    // libopera logging hook
    typedef void (*opera_log_printf_t)(int level, const char* fmt, ...);
    void opera_log_set_func(void* func);
}

// Input state from input_handler.cpp
extern "C" bool android_input_get_state(int button);
extern "C" void android_input_reset_state();

// Renderer (unified_renderer.cpp)
extern "C" void render_frame(const void* pixels, int width, int height);

// -----------------------------------------------------------------------
// libopera logging bridge – redirect opera_log_printf to Android logcat
// -----------------------------------------------------------------------
#define LOG_TAG_OPERA "libopera"

static void opera_log_to_logcat(int level, const char* fmt, ...) {
    android_LogPriority priority;
    switch (level) {
        case 0:  priority = ANDROID_LOG_DEBUG;   break; // OPERA_LOG_DEBUG
        case 1:  priority = ANDROID_LOG_INFO;    break; // OPERA_LOG_INFO
        case 2:  priority = ANDROID_LOG_WARN;    break; // OPERA_LOG_WARN
        default: priority = ANDROID_LOG_ERROR;   break; // OPERA_LOG_ERROR
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, LOG_TAG_OPERA, fmt, args);
    va_end(args);
}

// -----------------------------------------------------------------------
// Native NVRAM format functions
//
// These implement the same 3DO linked-memory filesystem format as
// opera_nvram.c, allowing that libopera C file to be removed from the
// build.  Structures from discdata.h / linkedmemblock.h are used directly.
// -----------------------------------------------------------------------
#include "libopera/discdata.h"
#include "libopera/linkedmemblock.h"
#include "libopera/endianness.h"

extern "C" {

bool opera_nvram_initialized(void* buf, const int bufsize) {
    (void)bufsize;
    const DiscLabel* dl = static_cast<const DiscLabel*>(buf);
    if (dl->dl_RecordType != DISC_LABEL_RECORD_TYPE)
        return false;
    if (dl->dl_VolumeStructureVersion != VOLUME_STRUCTURE_LINKED_MEM)
        return false;
    for (int i = 0; i < VOLUME_SYNC_BYTE_LEN; ++i) {
        if (dl->dl_VolumeSyncBytes[i] != VOLUME_SYNC_BYTE)
            return false;
    }
    return true;
}

void opera_nvram_init(void* buf, const int bufsize) {
    DiscLabel*      disc_label   = static_cast<DiscLabel*>(buf);
    LinkedMemBlock* anchor_block = reinterpret_cast<LinkedMemBlock*>(&disc_label[1]);
    LinkedMemBlock* free_block   = &anchor_block[1];

    memset(buf, 0, bufsize);

    disc_label->dl_RecordType = DISC_LABEL_RECORD_TYPE;
    memset(disc_label->dl_VolumeSyncBytes, VOLUME_SYNC_BYTE, VOLUME_SYNC_BYTE_LEN);
    disc_label->dl_VolumeStructureVersion = VOLUME_STRUCTURE_LINKED_MEM;
    disc_label->dl_VolumeFlags = 0;
    strncpy(reinterpret_cast<char*>(disc_label->dl_VolumeCommentary),
            "opera formatted", VOLUME_COM_LEN);
    strncpy(reinterpret_cast<char*>(disc_label->dl_VolumeIdentifier),
            "nvram", VOLUME_ID_LEN);
    disc_label->dl_VolumeUniqueIdentifier   = swap32_if_le(NVRAM_VOLUME_UNIQUE_ID); // from discdata.h
    disc_label->dl_VolumeBlockSize          = swap32_if_le(1); // NVRAM_BLOCKSIZE (1 byte/block)
    disc_label->dl_VolumeBlockCount         = swap32_if_le(static_cast<uint32_t>(bufsize));
    disc_label->dl_RootUniqueIdentifier     = swap32_if_le(NVRAM_ROOT_UNIQUE_ID); // from discdata.h
    disc_label->dl_RootDirectoryBlockCount  = 0;
    disc_label->dl_RootDirectoryBlockSize   = swap32_if_le(1); // NVRAM_BLOCKSIZE
    disc_label->dl_RootDirectoryLastAvatarIndex = 0;
    disc_label->dl_RootDirectoryAvatarList[0]   =
        swap32_if_le(static_cast<uint32_t>(sizeof(DiscLabel)));

    anchor_block->fingerprint      = swap32_if_le(FINGERPRINT_ANCHORBLOCK);
    anchor_block->flinkoffset      = swap32_if_le(
        static_cast<uint32_t>(sizeof(DiscLabel) + sizeof(LinkedMemBlock)));
    anchor_block->blinkoffset      = anchor_block->flinkoffset;
    anchor_block->blockcount       = swap32_if_le(
        static_cast<uint32_t>(sizeof(LinkedMemBlock)));
    anchor_block->headerblockcount = anchor_block->blockcount;

    free_block->fingerprint      = swap32_if_le(FINGERPRINT_FREEBLOCK);
    free_block->flinkoffset      = swap32_if_le(
        static_cast<uint32_t>(sizeof(DiscLabel)));
    free_block->blinkoffset      = free_block->flinkoffset;
    free_block->blockcount       = swap32_if_le(
        static_cast<uint32_t>(bufsize) -
        static_cast<uint32_t>(sizeof(DiscLabel)) -
        static_cast<uint32_t>(sizeof(LinkedMemBlock)));
    free_block->headerblockcount = swap32_if_le(
        static_cast<uint32_t>(sizeof(LinkedMemBlock)));
}

} // extern "C" (NVRAM)

// -----------------------------------------------------------------------
// Native PRNG implementations (replaces prng16.c and prng32.c)
// -----------------------------------------------------------------------
extern "C" {

static uint32_t g_prng16_state = 0xDEADBEEFu;
static uint32_t g_prng32_state = 0xDEADBEEFu;

static inline uint32_t hash16(uint32_t input, uint32_t key) {
    uint32_t h = input * key;
    return ((h >> 16) ^ h) & 0xFFFF;
}

void     prng16_seed(uint32_t seed) { g_prng16_state = seed; }
uint32_t prng16(void) { g_prng16_state += 0xFC15u; return hash16(g_prng16_state, 0x02ABu); }

static inline uint32_t splitmix32(uint32_t* v) {
    uint32_t z = (*v += 0x9e3779b9u);
    z = (z ^ (z >> 16)) * 0x85ebca6bu;
    z = (z ^ (z >> 13)) * 0xc2b2ae35u;
    return z ^ (z >> 16);
}

void     prng32_seed(uint32_t seed) { g_prng32_state = seed; }
uint32_t prng32(void) { return splitmix32(&g_prng32_state); }

} // extern "C" (PRNG)

// -----------------------------------------------------------------------
// Native diagnostic port (replaces opera_diag_port.c)
// -----------------------------------------------------------------------
extern "C" {

static uint16_t g_diag_snd0 = 0, g_diag_snd1 = 0;
static uint16_t g_diag_rcv0 = 0, g_diag_rcv1 = 0;
static uint16_t g_diag_get_idx = 16, g_diag_send_idx = 16;

void opera_diag_port_init(const int32_t test_code) {
    g_diag_get_idx  = 16;
    g_diag_send_idx = 16;
    g_diag_snd0 = 0;
    g_diag_snd1 = 0;
    int32_t tc = test_code;
    if (tc >= 0) { tc ^= 0xFF; tc |= 0xA000; }
    else          { tc = 0; }
    g_diag_rcv0 = static_cast<uint16_t>(tc);
    g_diag_rcv1 = static_cast<uint16_t>(tc);
}

void opera_diag_port_send(const uint32_t val) {
    if (g_diag_get_idx != 16) {
        g_diag_get_idx  = 16;
        g_diag_send_idx = 16;
        g_diag_snd0 = 0;
        g_diag_snd1 = 0;
    }
    g_diag_snd0 = static_cast<uint16_t>(g_diag_snd0 | ((val & 1u) << (g_diag_send_idx - 1)));
    g_diag_snd1 = static_cast<uint16_t>(g_diag_snd1 | (((val >> 1u) & 1u) << (g_diag_send_idx - 1)));
    if (--g_diag_send_idx == 0) g_diag_send_idx = 16;
}

uint32_t opera_diag_port_get(void) {
    if (g_diag_send_idx != 16) {
        g_diag_get_idx  = 16;
        g_diag_send_idx = 16;
    }
    uint32_t val  =  (g_diag_rcv0 >> (g_diag_get_idx - 1)) & 0x1u;
    val           |= ((g_diag_rcv1 >> (g_diag_get_idx - 1)) & 0x1u) << 1u;
    if (--g_diag_get_idx == 0) g_diag_get_idx = 16;
    return val;
}

} // extern "C" (diag_port)

// -----------------------------------------------------------------------
// Native clock implementation (replaces opera_clock.c)
// -----------------------------------------------------------------------
extern "C" {

#define NATIVE_DEFAULT_CPU_FREQ  12500000UL
#define NATIVE_SND_FREQ          44100UL
#define NATIVE_NTSC_FIELD_SIZE   263UL
#define NATIVE_PAL_FIELD_SIZE    312UL
#define NATIVE_NTSC_FIELD_RATE   3928227UL   // 16.16 fixed-point
#define NATIVE_PAL_FIELD_RATE    3276800UL
#define NATIVE_DEFAULT_TIMER     0x150UL

typedef struct {
    uint32_t cpu_freq;
    int32_t  dsp_acc;
    int32_t  vdl_acc;
    int32_t  timer_acc;
    uint32_t timer_delay;
    uint32_t field_size;
    uint32_t field_rate;
    int32_t  cycles_per_snd;
    int32_t  cycles_per_scanline;
    int32_t  cycles_per_timer;
} native_clock_t;

static native_clock_t g_clock = {
    /* .cpu_freq   = */ NATIVE_DEFAULT_CPU_FREQ,
    /* .dsp_acc    = */ 0,
    /* .vdl_acc    = */ 0,
    /* .timer_acc  = */ 0,
    /* .timer_delay= */ NATIVE_DEFAULT_TIMER,
    /* .field_size = */ NATIVE_NTSC_FIELD_SIZE,
    /* .field_rate = */ NATIVE_NTSC_FIELD_RATE,
    /* .cycles_per_snd      = */ (int32_t)(((uint64_t)NATIVE_DEFAULT_CPU_FREQ << 16) / NATIVE_SND_FREQ),
    /* .cycles_per_scanline = */ (int32_t)(((uint64_t)NATIVE_DEFAULT_CPU_FREQ << 32) /
                                            (NATIVE_NTSC_FIELD_RATE * NATIVE_NTSC_FIELD_SIZE)),
    /* .cycles_per_timer    = */ (int32_t)(((uint64_t)NATIVE_DEFAULT_CPU_FREQ << 32) /
                                            ((((uint64_t)21000000ULL) << 16) / NATIVE_DEFAULT_TIMER)),
};

static void clock_recalculate(void) {
    g_clock.cycles_per_snd      = (int32_t)(((uint64_t)g_clock.cpu_freq << 16) / NATIVE_SND_FREQ);
    g_clock.cycles_per_scanline = (int32_t)(((uint64_t)g_clock.cpu_freq << 32) /
                                             ((uint64_t)g_clock.field_rate * (uint64_t)g_clock.field_size));
    uint64_t td = g_clock.timer_delay ? g_clock.timer_delay : 1;
    g_clock.cycles_per_timer    = (int32_t)(((uint64_t)g_clock.cpu_freq << 32) /
                                             ((((uint64_t)21000000ULL) << 16) / td));
}

int opera_clock_vdl_queued(void) {
    if (g_clock.vdl_acc >= g_clock.cycles_per_scanline) {
        g_clock.vdl_acc -= g_clock.cycles_per_scanline;
        return 1;
    }
    return 0;
}

int opera_clock_dsp_queued(void) {
    if (g_clock.dsp_acc >= g_clock.cycles_per_snd) {
        g_clock.dsp_acc -= g_clock.cycles_per_snd;
        return 1;
    }
    return 0;
}

int opera_clock_timer_queued(void) {
    if (g_clock.timer_acc >= g_clock.cycles_per_timer) {
        g_clock.timer_acc -= g_clock.cycles_per_timer;
        return 1;
    }
    return 0;
}

void opera_clock_push_cycles(const uint32_t clks) {
    uint32_t clks1616 = clks << 16;
    g_clock.dsp_acc   += (int32_t)clks1616;
    g_clock.vdl_acc   += (int32_t)clks1616;
    g_clock.timer_acc += (int32_t)clks1616;
}

void opera_clock_cpu_set_freq(const uint32_t freq) {
    g_clock.cpu_freq = freq;
    clock_recalculate();
}

void opera_clock_cpu_set_freq_mul(const float mul) {
    opera_clock_cpu_set_freq((uint32_t)(NATIVE_DEFAULT_CPU_FREQ * mul));
}

uint32_t opera_clock_cpu_get_freq(void)         { return g_clock.cpu_freq; }
uint32_t opera_clock_cpu_get_default_freq(void) { return NATIVE_DEFAULT_CPU_FREQ; }

uint64_t opera_clock_cpu_cycles_per_field(void) {
    return ((uint64_t)g_clock.cpu_freq << 32) /
           ((uint64_t)g_clock.field_rate * (uint64_t)g_clock.field_size);
}

void opera_clock_region_set_ntsc(void) {
    g_clock.field_rate = NATIVE_NTSC_FIELD_RATE;
    g_clock.field_size = NATIVE_NTSC_FIELD_SIZE;
    clock_recalculate();
}

void opera_clock_region_set_pal(void) {
    g_clock.field_rate = NATIVE_PAL_FIELD_RATE;
    g_clock.field_size = NATIVE_PAL_FIELD_SIZE;
    clock_recalculate();
}

void opera_clock_timer_set_delay(const uint32_t td) {
    g_clock.timer_delay = td ? td : 1;
    clock_recalculate();
}

} // extern "C" (clock)

// -----------------------------------------------------------------------
// Native region implementation (replaces opera_region.c)
// The region struct mirrors opera_region_i.h (0=NTSC, 1=PAL1, 2=PAL2).
// -----------------------------------------------------------------------
#include "libopera/opera_region_i.h"

extern "C" {

opera_region_t g_REGION = {
    OPERA_REGION_NTSC, 320, 240, 262, 21, 21 + 240, 60
};

void opera_region_set_NTSC(void) {
    g_REGION.region         = OPERA_REGION_NTSC;
    g_REGION.width          = 320;
    g_REGION.height         = 240;
    g_REGION.scanlines      = 262;
    g_REGION.start_scanline = 21;
    g_REGION.end_scanline   = 21 + 240;
    g_REGION.field_rate     = 60;
    opera_clock_region_set_ntsc();
}

void opera_region_set_PAL1(void) {
    g_REGION.region         = OPERA_REGION_PAL1;
    g_REGION.width          = 320;
    g_REGION.height         = 288;
    g_REGION.scanlines      = 312;
    g_REGION.start_scanline = 21;
    g_REGION.end_scanline   = 21 + 288;
    g_REGION.field_rate     = 50;
    opera_clock_region_set_pal();
}

void opera_region_set_PAL2(void) {
    g_REGION.region         = OPERA_REGION_PAL2;
    g_REGION.width          = 384;
    g_REGION.height         = 288;
    g_REGION.scanlines      = 312;
    g_REGION.start_scanline = 22;
    g_REGION.end_scanline   = 22 + 288;
    g_REGION.field_rate     = 50;
    opera_clock_region_set_pal();
}

opera_region_e opera_region_get(void)      { return g_REGION.region; }
uint32_t opera_region_min_width(void)      { return 320; }
uint32_t opera_region_min_height(void)     { return 240; }
uint32_t opera_region_max_width(void)      { return 384; }
uint32_t opera_region_max_height(void)     { return 288; }

} // extern "C" (region)

// -----------------------------------------------------------------------
// Native fixed-point math (replaces opera_fixedpoint_math.c)
// These are ARM60 SWI 0x5xxxx math functions exposed to libopera.
// The header is included inside extern "C" so all declarations and
// definitions share C linkage, avoiding "different language linkage" errors.
// -----------------------------------------------------------------------

extern "C" {

#include "libopera/opera_fixedpoint_math.h"

static int32_t sqrt_frac16_native(int32_t x) {
    // Digit-by-digit integer square root for 16.16 fixed-point.
    // The do-while with count-- iterates 17 times (count: 16 down to 0 inclusive),
    // matching the original opera_fixedpoint_math.c algorithm exactly.
    int32_t root = 0, remHi = 0, remLo = x;
    int count = 16;
    do {
        remHi = (remHi << 16) | (uint32_t)(remLo >> 16);
        remLo <<= 16;
        int32_t testDiv = (root << 1) + 1;
        if (remHi >= testDiv) { remHi -= testDiv; root++; }
    } while (count-- != 0);
    return root;
}

void MulVec3Mat33_F16(vec3f16 dest, vec3f16 vec, mat33f16 mat) {
    vec3f16 tmp;
    tmp[0] = (int32_t)((((int64_t)vec[0]*(int64_t)mat[0][0])+((int64_t)vec[1]*(int64_t)mat[1][0])+((int64_t)vec[2]*(int64_t)mat[2][0]))>>16);
    tmp[1] = (int32_t)((((int64_t)vec[0]*(int64_t)mat[0][1])+((int64_t)vec[1]*(int64_t)mat[1][1])+((int64_t)vec[2]*(int64_t)mat[2][1]))>>16);
    tmp[2] = (int32_t)((((int64_t)vec[0]*(int64_t)mat[0][2])+((int64_t)vec[1]*(int64_t)mat[1][2])+((int64_t)vec[2]*(int64_t)mat[2][2]))>>16);
    dest[0]=tmp[0]; dest[1]=tmp[1]; dest[2]=tmp[2];
}

void MulMat33Mat33_F16(mat33f16 dest, mat33f16 s1, mat33f16 s2) {
    mat33f16 tmp;
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            tmp[r][c] = (int32_t)((((int64_t)s1[r][0]*(int64_t)s2[0][c])+((int64_t)s1[r][1]*(int64_t)s2[1][c])+((int64_t)s1[r][2]*(int64_t)s2[2][c]))>>16);
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            dest[r][c] = tmp[r][c];
}

void MulManyVec3Mat33_F16(vec3f16 *dest, vec3f16 *src, mat33f16 mat, int32_t count) {
    for (int32_t i = 0; i < count; ++i) MulVec3Mat33_F16(dest[i], src[i], mat);
}

void MulManyF16(frac16 *dest, frac16 *src1, frac16 *src2, int32_t count) {
    for (int32_t i = 0; i < count; ++i) dest[i] = (frac16)(((int64_t)src1[i]*(int64_t)src2[i])>>16);
}

void MulScalerF16(frac16 *dest, frac16 *src, frac16 scaler, int32_t count) {
    for (int32_t i = 0; i < count; ++i) dest[i] = (frac16)(((int64_t)src[i]*(int64_t)scaler)>>16);
}

void MulVec4Mat44_F16(vec4f16 dest, vec4f16 vec, mat44f16 mat) {
    vec4f16 tmp;
    for (int c = 0; c < 4; ++c) {
        int64_t s = 0;
        for (int r = 0; r < 4; ++r) s += (int64_t)vec[r]*(int64_t)mat[r][c];
        tmp[c] = (frac16)(s >> 16);
    }
    dest[0]=tmp[0]; dest[1]=tmp[1]; dest[2]=tmp[2]; dest[3]=tmp[3];
}

void MulMat44Mat44_F16(mat44f16 dest, mat44f16 s1, mat44f16 s2) {
    mat44f16 tmp;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c) {
            int64_t s = 0;
            for (int k = 0; k < 4; ++k) s += (int64_t)s1[r][k]*(int64_t)s2[k][c];
            tmp[r][c] = (frac16)(s >> 16);
        }
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            dest[r][c] = tmp[r][c];
}

void MulManyVec4Mat44_F16(vec4f16 *dest, vec4f16 *src, mat44f16 mat, int32_t count) {
    for (int32_t i = 0; i < count; ++i) MulVec4Mat44_F16(dest[i], src[i], mat);
}

void MulObjectVec4Mat44_F16(void *objectlist[], ObjOffset1 *offsetstruct, int32_t count) {
    (void)objectlist; (void)offsetstruct; (void)count;
}

void MulObjectMat44_F16(void *objectlist[], ObjOffset2 *offsetstruct, mat44f16 mat, int32_t count) {
    (void)objectlist; (void)offsetstruct; (void)mat; (void)count;
}

frac16 Dot3_F16(vec3f16 v1, vec3f16 v2) {
    return (frac16)((((int64_t)v1[0]*(int64_t)v2[0])+((int64_t)v1[1]*(int64_t)v2[1])+((int64_t)v1[2]*(int64_t)v2[2]))>>16);
}

frac16 Dot4_F16(vec4f16 v1, vec4f16 v2) {
    return (frac16)((((int64_t)v1[0]*(int64_t)v2[0])+((int64_t)v1[1]*(int64_t)v2[1])+((int64_t)v1[2]*(int64_t)v2[2])+((int64_t)v1[3]*(int64_t)v2[3]))>>16);
}

void Cross3_F16(vec3f16 dest, vec3f16 v1, vec3f16 v2) {
    vec3f16 tmp;
    tmp[0] = (frac16)((((int64_t)v1[1]*(int64_t)v2[2])-((int64_t)v1[2]*(int64_t)v2[1]))>>16);
    tmp[1] = (frac16)((((int64_t)v1[2]*(int64_t)v2[0])-((int64_t)v1[0]*(int64_t)v2[2]))>>16);
    tmp[2] = (frac16)((((int64_t)v1[0]*(int64_t)v2[1])-((int64_t)v1[1]*(int64_t)v2[0]))>>16);
    dest[0]=tmp[0]; dest[1]=tmp[1]; dest[2]=tmp[2];
}

frac16 AbsVec3_F16(vec3f16 vec) {
    frac16 rv = (frac16)((((int64_t)vec[0]*(int64_t)vec[0])+((int64_t)vec[1]*(int64_t)vec[1])+((int64_t)vec[2]*(int64_t)vec[2]))>>16);
    return sqrt_frac16_native(rv);
}

frac16 AbsVec4_F16(vec4f16 vec) {
    frac16 rv = (frac16)((((int64_t)vec[0]*(int64_t)vec[0])+((int64_t)vec[1]*(int64_t)vec[1])+((int64_t)vec[2]*(int64_t)vec[2])+((int64_t)vec[3]*(int64_t)vec[3]))>>16);
    return sqrt_frac16_native(rv);
}

void MulVec3Mat33DivZ_F16(vec3f16 dest, vec3f16 vec, mat33f16 mat, frac16 n) {
    MulVec3Mat33_F16(dest, vec, mat);
    if (dest[2] != 0) {
        int64_t mul = (((int64_t)n << 16) / (int64_t)dest[2]);
        dest[0] = (frac16)((((int64_t)dest[0] * mul) >> 16));
        dest[1] = (frac16)((((int64_t)dest[1] * mul) >> 16));
    }
}

void MulManyVec3Mat33DivZ_F16(vec3f16 *dest, vec3f16 *src, mat33f16 *mat, frac16 n, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) MulVec3Mat33DivZ_F16(dest[i], src[i], *mat, n);
}

} // extern "C" (fixedpoint_math)

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

    // Redirect libopera's internal log output to Android logcat
    opera_log_set_func(reinterpret_cast<void*>(opera_log_to_logcat));

    m_game_path = game_path;
    m_bios_path = bios_path;

    // Apply region setting before libopera core init
    switch (m_region) {
        case 1:  opera_region_set_PAL1(); LOGD("Region: PAL1"); break;
        case 2:  opera_region_set_PAL2(); LOGD("Region: PAL2"); break;
        default: opera_region_set_NTSC(); LOGD("Region: NTSC"); break;
    }

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
void FourdoCore::toggle_pause() {
    bool expected = m_paused.load(std::memory_order_acquire);
    while (!m_paused.compare_exchange_weak(expected, !expected, std::memory_order_acq_rel)) {}
}
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
// region / CPU speed
// -----------------------------------------------------------------------
void FourdoCore::set_region(int region) {
    m_region = region;
    // Apply immediately if already running
    if (m_initialized.load(std::memory_order_acquire)) {
        switch (region) {
            case 1:  opera_region_set_PAL1(); break;
            case 2:  opera_region_set_PAL2(); break;
            default: opera_region_set_NTSC(); break;
        }
        LOGD("Region changed to %d", region);
    }
}

int FourdoCore::get_region() const {
    return m_region;
}

void FourdoCore::set_cpu_speed(float multiplier) {
    if (multiplier <= 0.0f) {
        LOGW("set_cpu_speed: invalid multiplier %.2f, clamping to 1.0", (double)multiplier);
        multiplier = 1.0f;
    }
    opera_clock_cpu_set_freq_mul(multiplier);
    LOGD("CPU speed multiplier set to %.2f", (double)multiplier);
}

// -----------------------------------------------------------------------
// save state
// -----------------------------------------------------------------------
size_t FourdoCore::state_size() const {
    if (!m_initialized.load(std::memory_order_acquire)) return 0;
    return static_cast<size_t>(opera_3do_state_size());
}

bool FourdoCore::save_state(void* buf, size_t buf_size) {
    if (!m_initialized.load(std::memory_order_acquire) || !buf) return false;
    uint32_t needed = opera_3do_state_size();
    if (buf_size < needed) {
        LOGE("save_state: buffer too small (%zu < %u)", buf_size, needed);
        return false;
    }
    bool was_paused = m_paused.load(std::memory_order_acquire);
    m_paused.store(true, std::memory_order_release);
    // Spin-wait until the emulator thread acknowledges the pause (max 200 ms).
    // The loop body matches the 5 ms sleep used in the emulator main loop when paused.
    for (int i = 0; i < 40 && m_initialized.load(std::memory_order_acquire); ++i) {
        usleep(5000);
    }
    uint32_t written = opera_3do_state_save(buf, static_cast<uint32_t>(buf_size));
    m_paused.store(was_paused, std::memory_order_release);
    LOGD("save_state: wrote %u bytes", written);
    return written > 0;
}

bool FourdoCore::load_state(const void* buf, size_t buf_size) {
    if (!m_initialized.load(std::memory_order_acquire) || !buf) return false;
    bool was_paused = m_paused.load(std::memory_order_acquire);
    m_paused.store(true, std::memory_order_release);
    // Spin-wait until the emulator thread acknowledges the pause (max 200 ms).
    for (int i = 0; i < 40 && m_initialized.load(std::memory_order_acquire); ++i) {
        usleep(5000);
    }
    uint32_t read = opera_3do_state_load(buf, static_cast<uint32_t>(buf_size));
    m_paused.store(was_paused, std::memory_order_release);
    LOGD("load_state: loaded %u bytes", read);
    return read > 0;
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
    return fourdo::core::FourdoCore::instance().drain_audio(out_buffer, max_frames);
}

uint8_t* opera_nvram_get_data(size_t* size) {
    return fourdo::core::FourdoCore::instance().nvram_data(size);
}

bool opera_nvram_set_data(const uint8_t* data, size_t size) {
    return fourdo::core::FourdoCore::instance().nvram_set(data, size);
}

size_t emulator_state_size() {
    return fourdo::core::FourdoCore::instance().state_size();
}

bool emulator_save_state(void* buf, size_t buf_size) {
    return fourdo::core::FourdoCore::instance().save_state(buf, buf_size);
}

bool emulator_load_state(const void* buf, size_t buf_size) {
    return fourdo::core::FourdoCore::instance().load_state(buf, buf_size);
}

void emulator_set_region(int region) {
    fourdo::core::FourdoCore::instance().set_region(region);
}

int emulator_get_region() {
    return fourdo::core::FourdoCore::instance().get_region();
}

void emulator_set_cpu_speed(float multiplier) {
    fourdo::core::FourdoCore::instance().set_cpu_speed(multiplier);
}

} // extern "C"
