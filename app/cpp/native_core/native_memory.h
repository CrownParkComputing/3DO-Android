/**
 * 4DO Native Core - Memory Manager
 * Memory handling with ARM NEON optimizations
 */

#ifndef FOURDO_NATIVE_MEMORY_H
#define FOURDO_NATIVE_MEMORY_H

#include "native_types.h"
#include "native_log.h"
#include <cstring>
#include <memory>

#ifdef __ARM_NEON
#include <arm_neon.h>
#define HAS_NEON 1
#else
#define HAS_NEON 0
#endif

namespace fourdo {
namespace core {

/**
 * Aligned memory allocator for optimized SIMD operations
 */
template<typename T, size_t Alignment = 16>
class AlignedAllocator {
public:
    using value_type = T;
    using pointer = T*;
    using const_pointer = const T*;
    using size_type = size_t;
    
    static pointer allocate(size_type n) {
        void* ptr = nullptr;
        if (posix_memalign(&ptr, Alignment, n * sizeof(T)) != 0) {
            return nullptr;
        }
        return static_cast<pointer>(ptr);
    }
    
    static void deallocate(pointer p, size_type) {
        free(p);
    }
};

/**
 * Memory block with automatic alignment
 */
class MemoryBlock {
    void* m_data;
    size_t m_size;
    
public:
    MemoryBlock() : m_data(nullptr), m_size(0) {}
    
    explicit MemoryBlock(size_t size) : m_data(nullptr), m_size(size) {
        if (size > 0) {
            if (posix_memalign(&m_data, 16, size) != 0) {
                m_data = nullptr;
                m_size = 0;
                LOGE("Failed to allocate %zu bytes", size);
            }
            memset(m_data, 0, m_size);
        }
    }
    
    ~MemoryBlock() {
        if (m_data) {
            free(m_data);
            m_data = nullptr;
        }
    }
    
    // Move semantics
    MemoryBlock(MemoryBlock&& other) noexcept 
        : m_data(other.m_data), m_size(other.m_size) {
        other.m_data = nullptr;
        other.m_size = 0;
    }
    
    MemoryBlock& operator=(MemoryBlock&& other) noexcept {
        if (this != &other) {
            if (m_data) free(m_data);
            m_data = other.m_data;
            m_size = other.m_size;
            other.m_data = nullptr;
            other.m_size = 0;
        }
        return *this;
    }
    
    // No copy
    MemoryBlock(const MemoryBlock&) = delete;
    MemoryBlock& operator=(const MemoryBlock&) = delete;
    
    void* data() { return m_data; }
    const void* data() const { return m_data; }
    size_t size() const { return m_size; }
    bool valid() const { return m_data != nullptr; }
    
    u8& operator[](size_t i) { return static_cast<u8*>(m_data)[i]; }
    const u8& operator[](size_t i) const { return static_cast<const u8*>(m_data)[i]; }
};

/**
 * Fast memory operations with NEON optimizations
 */
class MemoryOps {
public:
    // Fast memory copy with NEON
    static void fast_copy(void* dst, const void* src, size_t size) {
#if HAS_NEON
        if (size >= 128) {
            // Use NEON for large copies
            size_t remaining = size;
            u8* d = static_cast<u8*>(dst);
            const u8* s = static_cast<const u8*>(src);
            
            // Copy 128 bytes at a time using NEON
            while (remaining >= 128) {
                uint8x16_t a = vld1q_u8(s);
                uint8x16_t b = vld1q_u8(s + 16);
                uint8x16_t c = vld1q_u8(s + 32);
                uint8x16_t d2 = vld1q_u8(s + 48);
                uint8x16_t e = vld1q_u8(s + 64);
                uint8x16_t f = vld1q_u8(s + 80);
                uint8x16_t g = vld1q_u8(s + 96);
                uint8x16_t h = vld1q_u8(s + 112);
                
                vst1q_u8(d, a);
                vst1q_u8(d + 16, b);
                vst1q_u8(d + 32, c);
                vst1q_u8(d + 48, d2);
                vst1q_u8(d + 64, e);
                vst1q_u8(d + 80, f);
                vst1q_u8(d + 96, g);
                vst1q_u8(d + 112, h);
                
                d += 128;
                s += 128;
                remaining -= 128;
            }
            
            // Copy remaining bytes
            memcpy(d, s, remaining);
            return;
        }
#endif
        memcpy(dst, src, size);
    }
    
    // Fast memory fill with NEON
    static void fast_fill(void* dst, u8 value, size_t size) {
#if HAS_NEON
        if (size >= 64) {
            u8* d = static_cast<u8*>(dst);
            uint8x16_t fill_val = vdupq_n_u8(value);
            
            while (size >= 64) {
                vst1q_u8(d, fill_val);
                vst1q_u8(d + 16, fill_val);
                vst1q_u8(d + 32, fill_val);
                vst1q_u8(d + 48, fill_val);
                d += 64;
                size -= 64;
            }
            
            while (size >= 16) {
                vst1q_u8(d, fill_val);
                d += 16;
                size -= 16;
            }
            
            memset(d, value, size);
            return;
        }
#endif
        memset(dst, value, size);
    }
    
    // Fast 16-bit swap for endianness conversion
    static void swap16_inplace(u16* data, size_t count) {
#if HAS_NEON
        while (count >= 8) {
            uint16x8_t v = vld1q_u16(data);
            v = vrev16q_u8(vreinterpretq_u8_u16(v));
            v = vreinterpretq_u16_u8(v);
            vst1q_u16(data, v);
            data += 8;
            count -= 8;
        }
#endif
        while (count-- > 0) {
            u16 x = *data;
            *data = (x >> 8) | (x << 8);
            data++;
        }
    }
    
    // Fast 32-bit swap for endianness conversion
    static void swap32_inplace(u32* data, size_t count) {
#if HAS_NEON
        while (count >= 4) {
            uint32x4_t v = vld1q_u32(data);
            v = vrev32q_u8(vreinterpretq_u8_u32(v));
            v = vreinterpretq_u32_u8(v);
            vst1q_u32(data, v);
            data += 4;
            count -= 4;
        }
#endif
        while (count-- > 0) {
            u32 x = *data;
            *data = ((x >> 24) & 0xFF) |
                    ((x >> 8)  & 0xFF00) |
                    ((x << 8)  & 0xFF0000) |
                    ((x << 24) & 0xFF000000);
            data++;
        }
    }
};

/**
 * 3DO Memory Map
 * Handles the memory layout of the 3DO console
 */
class MemoryMap {
public:
    // Memory region addresses
    static constexpr u32 ROM0_BASE = 0x00000000;    // BIOS ROM
    static constexpr u32 ROM0_SIZE = memory::BIOS_SIZE;
    
    static constexpr u32 DRAM_BASE = 0x00100000;    // Main RAM
    static constexpr u32 DRAM_SIZE = memory::DRAM_SIZE;
    
    static constexpr u32 VRAM_BASE = 0x00300000;    // Video RAM
    static constexpr u32 VRAM_SIZE = memory::VRAM_SIZE;
    
    static constexpr u32 NVRAM_BASE = 0x00400000;   // Non-volatile RAM
    static constexpr u32 NVRAM_SIZE = memory::NVRAM_SIZE;
    
    static constexpr u32 IO_BASE = 0x01000000;      // Memory-mapped I/O
    static constexpr u32 IO_SIZE = 0x01000000;
    
    // Decode address to region
    static MemoryRegion decode_region(u32 addr) {
        if (addr >= ROM0_BASE && addr < ROM0_BASE + ROM0_SIZE) {
            return MemoryRegion::ROM0;
        }
        if (addr >= DRAM_BASE && addr < DRAM_BASE + DRAM_SIZE) {
            return MemoryRegion::DRAM;
        }
        if (addr >= VRAM_BASE && addr < VRAM_BASE + VRAM_SIZE) {
            return MemoryRegion::VRAM;
        }
        if (addr >= NVRAM_BASE && addr < NVRAM_BASE + NVRAM_SIZE) {
            return MemoryRegion::NVRAM;
        }
        if (addr >= IO_BASE) {
            return MemoryRegion::IO;
        }
        return MemoryRegion::UNKNOWN;
    }
    
    // Get offset within region
    static u32 region_offset(u32 addr) {
        switch (decode_region(addr)) {
            case MemoryRegion::ROM0:  return addr - ROM0_BASE;
            case MemoryRegion::DRAM:  return addr - DRAM_BASE;
            case MemoryRegion::VRAM:  return addr - VRAM_BASE;
            case MemoryRegion::NVRAM: return addr - NVRAM_BASE;
            case MemoryRegion::IO:    return addr - IO_BASE;
            default: return addr;
        }
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_MEMORY_H