/**
 * 4DO Native Core - Type Definitions
 * Modern C++17 types for 3DO emulation
 */

#ifndef FOURDO_NATIVE_TYPES_H
#define FOURDO_NATIVE_TYPES_H

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <vector>
#include <array>
#include <memory>
#include <functional>

namespace fourdo {
namespace core {

// Basic integer types
using u8   = uint8_t;
using u16  = uint16_t;
using u32  = uint32_t;
using u64  = uint64_t;
using i8   = int8_t;
using i16  = int16_t;
using i32  = int32_t;
using i64  = int64_t;

// Size types
using usize = size_t;

// 3DO-specific types
using Word = u32;       // 32-bit word (ARM60 native)
using HalfWord = u16;   // 16-bit halfword
using Byte = u8;        // 8-bit byte

// Memory address types
using Address = u32;    // 32-bit address space
using Offset = u32;

// Fixed-point types for 3DO math
using Fixed16 = i16;    // 16-bit fixed point (8.8)
using Fixed32 = i32;    // 32-bit fixed point (16.16)

// Result type for operations
template<typename T>
struct Result {
    bool success;
    T value;
    std::string error;
    
    static Result<T> ok(T val) { return {true, val, ""}; }
    static Result<T> err(const std::string& msg) { return {false, T{}, msg}; }
};

// Simple optional-like type
template<typename T>
struct Optional {
    bool has_value;
    T value;
    
    Optional() : has_value(false) {}
    Optional(T val) : has_value(true), value(val) {}
    
    T or_default(T default_val) const {
        return has_value ? value : default_val;
    }
};

// Memory regions
enum class MemoryRegion {
    ROM0,       // BIOS ROM (1MB)
    ROM1,       // Expansion ROM
    DRAM,       // Main RAM (2MB)
    VRAM,       // Video RAM (1MB)
    NVRAM,      // Non-volatile RAM (32KB)
    IO,         // Memory-mapped I/O
    UNKNOWN
};

// Console regions
enum class Region {
    NTSC,       // North America (60Hz)
    PAL,        // Europe (50Hz)
    AUTO
};

// Emulator state
enum class EmulatorState {
    Uninitialized,
    Running,
    Paused,
    Stopped,
    Error
};

// Frame buffer info
struct FrameBuffer {
    u16* pixels;
    u32 width;
    u32 height;
    u32 pitch;      // bytes per line
    
    FrameBuffer() : pixels(nullptr), width(320), height(240), pitch(640) {}
};

// Audio buffer info
struct AudioBuffer {
    i16* samples;
    u32 frame_count;
    u32 sample_rate;
    u32 channels;
    
    AudioBuffer() : samples(nullptr), frame_count(0), sample_rate(44100), channels(2) {}
};

// CD-ROM sector
struct CdSector {
    static constexpr u32 SIZE = 2048;
    u8 data[SIZE];
    
    CdSector() { memset(data, 0, SIZE); }
};

// Save state header
struct SaveStateHeader {
    static constexpr u32 MAGIC = 0x34444F53; // "4DOS"
    u32 magic;
    u32 version;
    u32 state_size;
    u32 crc32;
    char game_name[64];
    char timestamp[32];
    
    SaveStateHeader() : magic(MAGIC), version(1), state_size(0), crc32(0) {
        memset(game_name, 0, sizeof(game_name));
        memset(timestamp, 0, sizeof(timestamp));
    }
};

// Timing constants
namespace timing {
    constexpr u32 CPU_CLOCK_HZ = 12500000;      // 12.5 MHz ARM60
    constexpr u32 FRAME_RATE_NTSC = 60;
    constexpr u32 FRAME_RATE_PAL = 50;
    constexpr u32 AUDIO_SAMPLE_RATE = 44100;
    constexpr u32 AUDIO_FRAMES_PER_FRAME = 735; // 44100 / 60
}

// Memory size constants
namespace memory {
    constexpr u32 BIOS_SIZE = 1 * 1024 * 1024;      // 1 MB
    constexpr u32 DRAM_SIZE = 2 * 1024 * 1024;      // 2 MB
    constexpr u32 VRAM_SIZE = 1 * 1024 * 1024;      // 1 MB
    constexpr u32 NVRAM_SIZE = 32 * 1024;           // 32 KB
    constexpr u32 CD_SECTOR_SIZE = 2048;
    constexpr u32 CD_MAX_SECTORS = 330000;          // ~650MB
}

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_TYPES_H