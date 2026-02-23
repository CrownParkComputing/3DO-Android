/**
 * 4DO Native Core - NVRAM Manager
 * Handles non-volatile RAM (save games, settings)
 */

#ifndef FOURDO_NATIVE_NVRAM_H
#define FOURDO_NATIVE_NVRAM_H

#include "native_types.h"
#include "native_log.h"
#include "native_memory.h"
#include <string>
#include <cstring>
#include <fstream>

namespace fourdo {
namespace core {

/**
 * NVRAM - Non-Volatile RAM
 * 32KB battery-backed memory for game saves and system settings
 * 
 * Memory layout:
 * - 0x0000-0x001F: System configuration
 * - 0x0020-0x01FF: File system header
 * - 0x0200-0x7FFF: User data (game saves)
 */
class Nvram {
    static constexpr u32 NVRAM_SIZE = memory::NVRAM_SIZE;  // 32KB
    static constexpr u32 BLOCK_SIZE = 512;
    static constexpr u32 NUM_BLOCKS = NVRAM_SIZE / BLOCK_SIZE;
    
    MemoryBlock m_data;
    bool m_modified;
    std::string m_save_path;
    
public:
    Nvram() : m_data(NVRAM_SIZE), m_modified(false) {}
    
    /**
     * Initialize NVRAM with default format
     */
    void format() {
        LOGI("Formatting NVRAM...");
        
        // Clear all memory
        MemoryOps::fast_fill(m_data.data(), 0, NVRAM_SIZE);
        
        // Write magic header
        const char magic[] = "3DONVRAM";
        memcpy(m_data.data(), magic, sizeof(magic) - 1);
        
        // Initialize file system header
        u8* fs_header = static_cast<u8*>(m_data.data()) + 0x20;
        
        // Block allocation table - first 2 blocks reserved for system
        fs_header[0] = 0xFF;  // System blocks
        fs_header[1] = 0xFF;  // Root directory
        
        m_modified = true;
        LOGI("NVRAM formatted successfully");
    }
    
    /**
     * Check if NVRAM is properly formatted
     */
    bool is_formatted() const {
        const char magic[] = "3DONVRAM";
        return memcmp(m_data.data(), magic, sizeof(magic) - 1) == 0;
    }
    
    /**
     * Read a byte from NVRAM
     */
    u8 read_byte(u32 addr) const {
        if (addr >= NVRAM_SIZE) {
            LOGW("NVRAM read out of bounds: 0x%08X", addr);
            return 0;
        }
        return m_data[addr];
    }
    
    /**
     * Write a byte to NVRAM
     */
    void write_byte(u32 addr, u8 value) {
        if (addr >= NVRAM_SIZE) {
            LOGW("NVRAM write out of bounds: 0x%08X", addr);
            return;
        }
        m_data[addr] = value;
        m_modified = true;
    }
    
    /**
     * Read 16-bit word from NVRAM (big-endian)
     */
    u16 read_word(u32 addr) const {
        return (read_byte(addr) << 8) | read_byte(addr + 1);
    }
    
    /**
     * Write 16-bit word to NVRAM (big-endian)
     */
    void write_word(u32 addr, u16 value) {
        write_byte(addr, (value >> 8) & 0xFF);
        write_byte(addr + 1, value & 0xFF);
    }
    
    /**
     * Read 32-bit dword from NVRAM (big-endian)
     */
    u32 read_dword(u32 addr) const {
        return (read_byte(addr) << 24) |
               (read_byte(addr + 1) << 16) |
               (read_byte(addr + 2) << 8) |
               read_byte(addr + 3);
    }
    
    /**
     * Write 32-bit dword to NVRAM (big-endian)
     */
    void write_dword(u32 addr, u32 value) {
        write_byte(addr, (value >> 24) & 0xFF);
        write_byte(addr + 1, (value >> 16) & 0xFF);
        write_byte(addr + 2, (value >> 8) & 0xFF);
        write_byte(addr + 3, value & 0xFF);
    }
    
    /**
     * Get raw NVRAM data pointer
     */
    u8* data() { return static_cast<u8*>(m_data.data()); }
    const u8* data() const { return static_cast<const u8*>(m_data.data()); }
    
    /**
     * Load NVRAM from file
     */
    bool load(const std::string& path) {
        m_save_path = path;
        
        std::ifstream file(path, std::ios::binary);
        if (!file) {
            LOGW("NVRAM file not found: %s", path.c_str());
            return false;
        }
        
        file.read(static_cast<char*>(m_data.data()), NVRAM_SIZE);
        if (!file) {
            LOGE("Failed to read NVRAM file: %s", path.c_str());
            return false;
        }
        
        LOGI("NVRAM loaded from: %s", path.c_str());
        m_modified = false;
        return true;
    }
    
    /**
     * Save NVRAM to file
     */
    bool save() {
        if (m_save_path.empty()) {
            LOGW("No save path set for NVRAM");
            return false;
        }
        return save(m_save_path);
    }
    
    /**
     * Save NVRAM to specific file
     */
    bool save(const std::string& path) {
        std::ofstream file(path, std::ios::binary);
        if (!file) {
            LOGE("Failed to open NVRAM file for writing: %s", path.c_str());
            return false;
        }
        
        file.write(static_cast<const char*>(m_data.data()), NVRAM_SIZE);
        if (!file) {
            LOGE("Failed to write NVRAM file: %s", path.c_str());
            return false;
        }
        
        LOGI("NVRAM saved to: %s (%u bytes)", path.c_str(), NVRAM_SIZE);
        m_modified = false;
        return true;
    }
    
    /**
     * Check if NVRAM has been modified since last save
     */
    bool is_modified() const { return m_modified; }
    
    /**
     * Auto-save if modified
     */
    void auto_save() {
        if (m_modified && !m_save_path.empty()) {
            save();
        }
    }
    
    /**
     * Import raw data (for save state restore)
     */
    void import_data(const void* data, size_t size) {
        size_t copy_size = (size < NVRAM_SIZE) ? size : NVRAM_SIZE;
        memcpy(m_data.data(), data, copy_size);
        m_modified = true;
    }
    
    /**
     * Export raw data (for save state)
     */
    void export_data(void* data, size_t size) const {
        size_t copy_size = (size < NVRAM_SIZE) ? size : NVRAM_SIZE;
        memcpy(data, m_data.data(), copy_size);
    }
    
    /**
     * Get total size
     */
    static constexpr u32 size() { return NVRAM_SIZE; }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_NVRAM_H