/**
 * 3DO Opera Native Core - BIOS Loader
 * Handles loading and management of 3DO BIOS ROMs
 */

#ifndef FOURDO_NATIVE_BIOS_H
#define FOURDO_NATIVE_BIOS_H

#include "native_types.h"
#include "native_log.h"
#include "native_memory.h"
#include <string>
#include <cstring>
#include <fstream>

namespace fourdo {
namespace core {

/**
 * BIOS - BIOS ROM Manager
 * Handles loading and validation of 3DO BIOS files
 * 
 * Supported BIOS files:
 * - panafz10.bin (Panasonic FZ-10, most common)
 * - panafz1.bin (Panasonic FZ-1)
 * - goldstar.bin (Goldstar 3DO)
 * - sanyotry.bin (Sanyo Try 3DO)
 * - 3do_arcade_saot.bin (Arcade)
 */
class Bios {
    static constexpr u32 BIOS_SIZE = memory::BIOS_SIZE;  // 1MB
    
    MemoryBlock m_data;
    std::string m_path;
    std::string m_name;
    bool m_loaded;
    
public:
    Bios() : m_data(BIOS_SIZE), m_loaded(false) {}
    
    /**
     * Load BIOS from file
     */
    bool load(const std::string& path) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) {
            LOGE("Failed to open BIOS file: %s", path.c_str());
            return false;
        }
        
        // Check file size
        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);
        
        if (size < BIOS_SIZE) {
            LOGE("BIOS file too small: %lld bytes (expected %u)", 
                 (long long)size, BIOS_SIZE);
            return false;
        }
        
        // Read BIOS data
        file.read(static_cast<char*>(m_data.data()), BIOS_SIZE);
        if (!file) {
            LOGE("Failed to read BIOS file: %s", path.c_str());
            return false;
        }
        
        m_path = path;
        m_loaded = true;
        
        // Detect BIOS type
        detect_bios_type();
        
        // Handle endianness if needed (BIOS is big-endian, ARM is little-endian)
        // Some emulators need byteswap, the opera core handles this internally
        
        LOGI("BIOS loaded: %s (%s)", m_name.c_str(), path.c_str());
        return true;
    }
    
    /**
     * Get raw BIOS data pointer
     */
    u8* data() { return static_cast<u8*>(m_data.data()); }
    const u8* data() const { return static_cast<const u8*>(m_data.data()); }
    
    /**
     * Check if BIOS is loaded
     */
    bool is_loaded() const { return m_loaded; }
    
    /**
     * Get BIOS name
     */
    const std::string& name() const { return m_name; }
    
    /**
     * Get BIOS file path
     */
    const std::string& path() const { return m_path; }
    
    /**
     * Read a byte from BIOS
     */
    u8 read_byte(u32 addr) const {
        if (addr >= BIOS_SIZE) {
            return 0;
        }
        return m_data[addr];
    }
    
    /**
     * Read 16-bit word from BIOS (big-endian)
     */
    u16 read_word(u32 addr) const {
        return (read_byte(addr) << 8) | read_byte(addr + 1);
    }
    
    /**
     * Read 32-bit dword from BIOS (big-endian)
     */
    u32 read_dword(u32 addr) const {
        return (read_byte(addr) << 24) |
               (read_byte(addr + 1) << 16) |
               (read_byte(addr + 2) << 8) |
               read_byte(addr + 3);
    }
    
    /**
     * Clear BIOS data (for security/reset)
     */
    void clear() {
        MemoryOps::fast_fill(m_data.data(), 0, BIOS_SIZE);
        m_loaded = false;
        m_path.clear();
        m_name = "None";
        LOGI("BIOS cleared");
    }
    
    /**
     * Get BIOS size
     */
    static constexpr u32 size() { return BIOS_SIZE; }
    
private:
    /**
     * Detect BIOS type from header
     */
    void detect_bios_type() {
        // Check for known BIOS signatures
        // Panasonic FZ-10 has specific strings at certain offsets
        
        const u8* data = static_cast<const u8*>(m_data.data());
        
        // Check for "3DO" signature at offset 0
        if (data[0] == '3' && data[1] == 'D' && data[2] == 'O') {
            m_name = "3DO BIOS";
            return;
        }
        
        // Check for common BIOS identifiers
        // Look for version string typically found in BIOS
        for (u32 i = 0; i < BIOS_SIZE - 20; i++) {
            if (memcmp(data + i, "Panasonic", 9) == 0) {
                if (strstr(reinterpret_cast<const char*>(data + i), "FZ-10")) {
                    m_name = "Panasonic FZ-10";
                } else if (strstr(reinterpret_cast<const char*>(data + i), "FZ-1")) {
                    m_name = "Panasonic FZ-1";
                } else {
                    m_name = "Panasonic 3DO";
                }
                return;
            }
            
            if (memcmp(data + i, "Goldstar", 8) == 0) {
                m_name = "Goldstar 3DO";
                return;
            }
            
            if (memcmp(data + i, "SANYO", 5) == 0 ||
                memcmp(data + i, "Sanyo", 5) == 0) {
                m_name = "Sanyo Try 3DO";
                return;
            }
        }
        
        m_name = "Unknown BIOS";
    }
};

/**
 * Known BIOS file names and their CRC32 values
 */
struct BiosInfo {
    const char* filename;
    const char* name;
    u32 crc32;
    bool recommended;
};

// Known good BIOS files
static const BiosInfo KNOWN_BIOS[] = {
    {"panafz10.bin", "Panasonic FZ-10", 0xA4762F99, true},
    {"panafz1.bin",  "Panasonic FZ-1",  0xE3D0A8F5, false},
    {"goldstar.bin", "Goldstar 3DO",    0x813CD8A8, false},
    {"sanyotry.bin", "Sanyo Try",       0x813CD8A8, false},
    {nullptr, nullptr, 0, false}
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_BIOS_H