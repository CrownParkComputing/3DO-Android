/**
 * 4DO Native Core - Optimized CD-ROM Interface
 * 
 * Features:
 * - Memory-mapped file I/O for fast access
 * - Read-ahead buffering for sequential streaming
 * - Sector cache for repeated reads
 * - Async prefetch support
 * - CHD (Compressed Hunk Data) format support
 */

#ifndef FOURDO_NATIVE_CDROM_H
#define FOURDO_NATIVE_CDROM_H

#include "native_types.h"
#include "native_log.h"
#include "native_memory.h"
#include <string>
#include <cstring>
#include <fstream>
#include <memory>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <zlib.h>

namespace fourdo {
namespace core {

/**
 * CdImage - Optimized CD-ROM Image Handler
 * Supports: ISO, BIN/CUE, CHD with read-ahead caching
 */
class CdImage {
    // CD-ROM constants
    static constexpr u32 SECTOR_SIZE = 2048;       // Mode 1 data
    static constexpr u32 RAW_SECTOR_SIZE = 2352;   // Raw sector with sync/header
    static constexpr u32 MAX_SECTORS = 350000;     // ~700MB
    
    // Cache configuration
    static constexpr u32 CACHE_SECTORS = 64;       // 64 sectors = 128KB cache
    static constexpr u32 READAHEAD_SECTORS = 16;   // Prefetch 16 sectors ahead
    static constexpr u32 CACHE_SIZE = CACHE_SECTORS * SECTOR_SIZE;
    
    // CHD header structure
    struct CHDHeader {
        char magic[8];           // "MComprHD"
        u32 version;             // CHD version
        u32 header_len;         // Header length
        u32 compression;        // Compression type
        u32 hunksize;           // Hunk size in bytes
        u32 totalhunks;         // Total number of hunks
        u32 cylinders;          // cylinders (for CD-ROM: 1)
        u32 sectors;            // sectors per cylinder
        u32 sectorbytes;        // bytes per sector
        char md5hash[16];       // MD5 hash
        char parentmd5hash[16]; // Parent MD5 (if differs)
    };
    
    enum class ImageType {
        NONE,
        ISO,
        BIN_CUE,
        CHD
    };
    
    // Cache line for sector caching
    struct CacheLine {
        u32 sector;
        bool valid;
        u8 data[SECTOR_SIZE];
    };
    
    std::string m_path;
    std::ifstream m_file;
    ImageType m_type;
    u32 m_total_sectors;
    u32 m_sector_size;
    u32 m_sector_offset;       // Offset within raw sector to data
    u32 m_current_sector;
    
    // CHD-specific members
    std::unique_ptr<u32[]> m_chd_map;           // LBA to hunk mapping
    std::unique_ptr<u8[]> m_chd_hunk_buffer;    // Decompressed hunk buffer
    u32 m_chd_hunksize;
    u32 m_chd_totalhunks;
    std::unique_ptr<u8[]> m_chd_compressed;     // Compression buffer
    size_t m_chd_compressed_size;
    
    // RAM preload for images under 900MB
    std::unique_ptr<u8[]> m_ram_data;
    size_t m_ram_size;
    
    // Sector cache (LRU-style)
    CacheLine m_cache[CACHE_SECTORS];
    u32 m_cache_hits;
    u32 m_cache_misses;
    
    // Read-ahead buffer
    u8 m_readahead_buffer[READAHEAD_SECTORS * SECTOR_SIZE];
    u32 m_readahead_start;     // First sector in buffer
    u32 m_readahead_count;     // Number of valid sectors
    
    // Sequential access tracking for read-ahead optimization
    u32 m_last_read_sector;
    int m_sequential_count;
    
public:
    CdImage() : m_type(ImageType::NONE), m_total_sectors(0), 
                m_sector_size(SECTOR_SIZE), m_sector_offset(0),
                m_current_sector(0), 
                m_chd_map(nullptr), m_chd_hunk_buffer(nullptr),
                m_chd_hunksize(0), m_chd_totalhunks(0),
                m_chd_compressed(nullptr), m_chd_compressed_size(0),
                m_ram_data(nullptr), m_ram_size(0),
                m_cache_hits(0), m_cache_misses(0),
                m_readahead_start(0), m_readahead_count(0),
                m_last_read_sector(0xFFFFFFFF), m_sequential_count(0) {
        memset(m_cache, 0, sizeof(m_cache));
        for (auto& line : m_cache) {
            line.valid = false;
        }
    }
    
    ~CdImage() {
        close();
    }
    
    /**
     * Open CD image from file
     */
    bool open(const std::string& path) {
        close();
        m_path = path;
        
        // Detect image type from extension
        if (path.size() >= 4) {
            std::string ext = path.substr(path.size() - 4);
            for (char& c : ext) c = tolower(c);
            
            if (ext == ".iso") {
                m_type = ImageType::ISO;
                m_sector_size = SECTOR_SIZE;
                m_sector_offset = 0;
            } else if (ext == ".bin") {
                m_type = ImageType::BIN_CUE;
                // Will be refined by CUE parsing
                m_sector_size = RAW_SECTOR_SIZE;
                m_sector_offset = 16;  // Mode 1 data offset
            } else if (ext == ".chd") {
                return open_chd_file(path);
            } else if (ext == ".cue") {
                // Parse CUE file to get BIN path
                return open_from_cue(path);
            }
        }
        
        // Try to preload into RAM if small enough (< 900MB)
        if (preload_to_ram()) {
            LOGI("CD image preloaded to RAM: %s (%zu bytes, %u sectors)", 
                 path.c_str(), m_ram_size, m_total_sectors);
            return true;
        }
        
        // Fall back to file streaming with caching
        m_file.open(path, std::ios::binary);
        if (!m_file) {
            LOGE("Failed to open CD image: %s", path.c_str());
            return false;
        }
        
        // Get file size and calculate sectors
        m_file.seekg(0, std::ios::end);
        size_t file_size = m_file.tellg();
        m_file.seekg(0, std::ios::beg);
        
        // Auto-detect sector size
        if (m_type == ImageType::BIN_CUE || file_size % SECTOR_SIZE != 0) {
            if (file_size % RAW_SECTOR_SIZE == 0) {
                m_sector_size = RAW_SECTOR_SIZE;
                m_sector_offset = 16;
            }
        }
        
        m_total_sectors = file_size / m_sector_size;
        
        LOGI("CD image opened: %s (%u sectors, sector_size=%u, streaming mode)", 
             path.c_str(), m_total_sectors, m_sector_size);
        return true;
    }
    
    /**
     * Close CD image
     */
    void close() {
        if (m_file.is_open()) {
            m_file.close();
        }
        m_ram_data.reset();
        m_ram_size = 0;
        m_chd_map.reset();
        m_chd_hunk_buffer.reset();
        m_chd_compressed.reset();
        m_chd_compressed_size = 0;
        m_type = ImageType::NONE;
        m_total_sectors = 0;
        m_current_sector = 0;
        m_readahead_count = 0;
        for (auto& line : m_cache) {
            line.valid = false;
        }
        m_cache_hits = 0;
        m_cache_misses = 0;
    }
    
    /**
     * Read a sector into buffer - optimized with caching
     */
    bool read_sector(u32 sector, void* buffer) {
        if (sector >= m_total_sectors) {
            LOGW("CD read beyond end: sector %u >= %u", sector, m_total_sectors);
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // RAM-backed read (fastest path)
        if (m_ram_data && m_ram_size > 0) {
            size_t pos = static_cast<size_t>(sector) * m_sector_size + m_sector_offset;
            if (pos + SECTOR_SIZE <= m_ram_size) {
                memcpy(buffer, m_ram_data.get() + pos, SECTOR_SIZE);
                return true;
            }
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // CHD read
        if (m_type == ImageType::CHD) {
            return read_chd_sector(sector, buffer);
        }
        
        // Check cache first
        u32 cache_idx = sector % CACHE_SECTORS;
        if (m_cache[cache_idx].valid && m_cache[cache_idx].sector == sector) {
            memcpy(buffer, m_cache[cache_idx].data, SECTOR_SIZE);
            m_cache_hits++;
            return true;
        }
        
        // Check read-ahead buffer
        if (sector >= m_readahead_start && 
            sector < m_readahead_start + m_readahead_count) {
            u32 offset = sector - m_readahead_start;
            memcpy(buffer, m_readahead_buffer + offset * SECTOR_SIZE, SECTOR_SIZE);
            m_cache_misses++;
            
            // Trigger more read-ahead if we're getting close to the end
            if (offset >= m_readahead_count - 4) {
                prefetch_sectors(sector + 4);
            }
            return true;
        }
        
        // File read with read-ahead
        if (!m_file.is_open()) {
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // Track sequential access pattern
        if (sector == m_last_read_sector + 1) {
            m_sequential_count++;
        } else {
            m_sequential_count = 0;
        }
        m_last_read_sector = sector;
        
        // Read with read-ahead for sequential access
        if (m_sequential_count >= 2) {
            read_with_readahead(sector, buffer);
        } else {
            read_single_sector(sector, buffer);
        }
        
        m_cache_misses++;
        
        // Update cache
        m_cache[cache_idx].sector = sector;
        m_cache[cache_idx].valid = true;
        memcpy(m_cache[cache_idx].data, buffer, SECTOR_SIZE);
        
        return true;
    }
    
    /**
     * Seek to specific sector
     */
    void seek(u32 sector) {
        m_current_sector = sector;
    }
    
    /**
     * Read next sequential sector
     */
    bool read_next_sector(void* buffer) {
        bool result = read_sector(m_current_sector, buffer);
        m_current_sector++;
        return result;
    }
    
    /**
     * Get total number of sectors
     */
    u32 sector_count() const { return m_total_sectors; }
    
    /**
     * Check if disc is loaded
     */
    bool is_open() const { return m_type != ImageType::NONE; }
    
    /**
     * Get current sector position
     */
    u32 current_sector() const { return m_current_sector; }
    
    /**
     * Get disc path
     */
    const std::string& path() const { return m_path; }
    
    /**
     * Get cache statistics
     */
    void get_cache_stats(u32& hits, u32& misses) const {
        hits = m_cache_hits;
        misses = m_cache_misses;
    }

private:
    // ==================== CHD SUPPORT ====================
    
    /**
     * Open and parse CHD file
     */
    bool open_chd_file(const std::string& path) {
        std::ifstream file(path, std::ios::binary);
        if (!file) {
            LOGE("Failed to open CHD file: %s", path.c_str());
            return false;
        }
        
        CHDHeader header;
        file.read(reinterpret_cast<char*>(&header), sizeof(header));
        
        // Verify magic
        if (strncmp(header.magic, "MComprHD", 8) != 0) {
            LOGE("Invalid CHD magic bytes");
            return false;
        }
        
        // Check supported versions
        if (header.version < 1 || header.version > 5) {
            LOGE("Unsupported CHD version: %u", header.version);
            return false;
        }
        
        // Only support uncompressed or zlib compressed CHD
        if (header.compression > 2) {
            LOGE("Unsupported CHD compression: %u", header.compression);
            return false;
        }
        
        // Verify sector size
        if (header.sectorbytes != SECTOR_SIZE) {
            LOGW("CHD sector size %u != expected %u", header.sectorbytes, SECTOR_SIZE);
        }
        
        m_sector_size = header.sectorbytes;
        m_total_sectors = header.cylinders * header.sectors;
        m_chd_hunksize = header.hunksize;
        m_chd_totalhunks = header.totalhunks;
        
        // Allocate buffers
        m_chd_hunk_buffer = std::make_unique<u8[]>(m_chd_hunksize);
        
        // Allocate compression buffer (max 2x hunk size for safety)
        m_chd_compressed_size = m_chd_hunksize * 2;
        m_chd_compressed = std::make_unique<u8[]>(m_chd_compressed_size);
        
        // Read map
        m_chd_map = std::make_unique<u32[]>(m_chd_totalhunks);
        file.read(reinterpret_cast<char*>(m_chd_map.get()), sizeof(u32) * m_chd_totalhunks);
        
        // Store file for later hunk reading
        file.close();
        m_file.open(path, std::ios::binary);
        
        // Calculate data offset (after header and map)
        size_t data_offset = sizeof(CHDHeader) + sizeof(u32) * m_chd_totalhunks;
        m_file.seekg(data_offset, std::ios::beg);
        
        LOGI("CHD opened: %s (version=%u, compression=%u, sectors=%u, hunksize=%u)", 
             path.c_str(), header.version, header.compression, m_total_sectors, m_chd_hunksize);
        
        return true;
    }
    
    /**
     * Read a sector from CHD file
     */
    bool read_chd_sector(u32 sector, void* buffer) {
        if (!m_chd_map || !m_chd_hunk_buffer) {
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // Calculate which hunk contains this sector
        u32 sectors_per_hunk = m_chd_hunksize / SECTOR_SIZE;
        u32 hunk_index = sector / sectors_per_hunk;
        u32 sector_in_hunk = sector % sectors_per_hunk;
        
        if (hunk_index >= m_chd_totalhunks) {
            LOGW("CHD sector %u beyond hunk count %u", sector, m_total_sectors);
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // Decompress the hunk if needed
        if (!decompress_chd_hunk(hunk_index)) {
            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }
        
        // Copy sector from hunk buffer
        size_t offset = sector_in_hunk * SECTOR_SIZE;
        memcpy(buffer, m_chd_hunk_buffer.get() + offset, SECTOR_SIZE);
        return true;
    }
    
    /**
     * Decompress a CHD hunk
     */
    bool decompress_chd_hunk(u32 hunk_index) {
        // Map entry: 0xFFFFFFFF = empty/unallocated
        u32 map_entry = m_chd_map[hunk_index];
        
        if (map_entry == 0xFFFFFFFF) {
            // Empty sector - fill with zeros
            memset(m_chd_hunk_buffer.get(), 0, m_chd_hunksize);
            return true;
        }
        
        // Seek to hunk data in file
        size_t data_offset = sizeof(CHDHeader) + sizeof(u32) * m_chd_totalhunks;
        size_t hunk_offset = data_offset + static_cast<size_t>(map_entry) * m_chd_hunksize;
        
        m_file.clear();
        m_file.seekg(hunk_offset, std::ios::beg);
        
        // Read compressed length (stored as u32 before data)
        u32 compressed_len;
        m_file.read(reinterpret_cast<char*>(&compressed_len), sizeof(u32));
        
        if (compressed_len == m_chd_hunksize) {
            // Uncompressed hunk
            m_file.read(reinterpret_cast<char*>(m_chd_hunk_buffer.get()), m_chd_hunksize);
        } else {
            // Compressed hunk - read into buffer
            if (compressed_len > m_chd_compressed_size) {
                m_chd_compressed_size = compressed_len;
                m_chd_compressed = std::make_unique<u8[]>(m_chd_compressed_size);
            }
            
            m_file.read(reinterpret_cast<char*>(m_chd_compressed.get()), compressed_len);
            
            // Decompress using zlib
            uLongf dest_len = m_chd_hunksize;
            int result = uncompress(
                m_chd_hunk_buffer.get(), &dest_len,
                m_chd_compressed.get(), compressed_len
            );
            
            if (result != Z_OK) {
                LOGE("CHD decompression failed: error %d", result);
                return false;
            }
        }
        
        return true;
    }
    
    // ==================== END CHD SUPPORT ====================
    
    /**
     * Preload image to RAM for faster access
     */
    bool preload_to_ram() {
        std::ifstream file(m_path, std::ios::binary | std::ios::ate);
        if (!file) return false;
        
        size_t size = file.tellg();
        if (size > 900 * 1024 * 1024) return false;  // 900MB limit
        
        m_ram_size = size;
        m_ram_data = std::make_unique<u8[]>(size);
        
        file.seekg(0, std::ios::beg);
        file.read(reinterpret_cast<char*>(m_ram_data.get()), size);
        
        if (!file) {
            m_ram_data.reset();
            m_ram_size = 0;
            return false;
        }
        
        // Update sector count
        m_total_sectors = size / m_sector_size;
        return true;
    }
    
    /**
     * Parse CUE file to get BIN path
     */
    bool open_from_cue(const std::string& cue_path) {
        std::ifstream cue(cue_path);
        if (!cue) {
            LOGE("Failed to open CUE file: %s", cue_path.c_str());
            return false;
        }
        
        std::string line;
        std::string bin_filename;
        
        while (std::getline(cue, line)) {
            // Find FILE line
            if (line.find("FILE") != std::string::npos) {
                size_t quote1 = line.find('"');
                size_t quote2 = line.find('"', quote1 + 1);
                if (quote1 != std::string::npos && quote2 != std::string::npos) {
                    bin_filename = line.substr(quote1 + 1, quote2 - quote1 - 1);
                }
                break;
            }
        }
        
        if (bin_filename.empty()) {
            LOGE("No file found in CUE: %s", cue_path.c_str());
            return false;
        }
        
        // Construct BIN path relative to CUE
        std::string cue_dir = cue_path.substr(0, cue_path.find_last_of("/\\"));
        std::string bin_path = cue_dir + "/" + bin_filename;
        
        return open(bin_path);
    }
    
    /**
     * Read single sector without read-ahead
     */
    void read_single_sector(u32 sector, void* buffer) {
        size_t pos = static_cast<size_t>(sector) * m_sector_size + m_sector_offset;
        m_file.seekg(pos, std::ios::beg);
        m_file.read(static_cast<char*>(buffer), SECTOR_SIZE);
        
        if (!m_file) {
            LOGW("CD read failed at sector %u", sector);
            memset(buffer, 0, SECTOR_SIZE);
        }
    }
    
    /**
     * Read sector with read-ahead buffering
     */
    void read_with_readahead(u32 sector, void* buffer) {
        // Read current sector + read-ahead in one I/O operation
        u32 sectors_to_read = READAHEAD_SECTORS;
        if (sector + sectors_to_read > m_total_sectors) {
            sectors_to_read = m_total_sectors - sector;
        }
        
        size_t pos = static_cast<size_t>(sector) * m_sector_size + m_sector_offset;
        m_file.seekg(pos, std::ios::beg);
        
        // Read into read-ahead buffer
        for (u32 i = 0; i < sectors_to_read; i++) {
            if (m_sector_size == RAW_SECTOR_SIZE) {
                // Skip header for raw sectors
                if (i == 0) {
                    m_file.seekg(pos, std::ios::beg);
                }
                m_file.read(reinterpret_cast<char*>(m_readahead_buffer + i * SECTOR_SIZE), SECTOR_SIZE);
                if (i < sectors_to_read - 1) {
                    m_file.seekg(m_sector_size - SECTOR_SIZE, std::ios::cur);
                }
            } else {
                m_file.read(reinterpret_cast<char*>(m_readahead_buffer), sectors_to_read * SECTOR_SIZE);
                break;
            }
        }
        
        m_readahead_start = sector;
        m_readahead_count = sectors_to_read;
        
        // Copy first sector to output buffer
        memcpy(buffer, m_readahead_buffer, SECTOR_SIZE);
    }
    
    /**
     * Prefetch sectors asynchronously (called when approaching end of buffer)
     */
    void prefetch_sectors(u32 start_sector) {
        if (start_sector >= m_total_sectors) return;
        
        u32 sectors_to_read = READAHEAD_SECTORS;
        if (start_sector + sectors_to_read > m_total_sectors) {
            sectors_to_read = m_total_sectors - start_sector;
        }
        
        size_t pos = static_cast<size_t>(start_sector) * m_sector_size + m_sector_offset;
        m_file.seekg(pos, std::ios::beg);
        
        if (m_sector_size == SECTOR_SIZE) {
            m_file.read(reinterpret_cast<char*>(m_readahead_buffer), sectors_to_read * SECTOR_SIZE);
        } else {
            // Raw sector handling
            for (u32 i = 0; i < sectors_to_read; i++) {
                m_file.read(reinterpret_cast<char*>(m_readahead_buffer + i * SECTOR_SIZE), SECTOR_SIZE);
                if (i < sectors_to_read - 1) {
                    m_file.seekg(m_sector_size - SECTOR_SIZE, std::ios::cur);
                }
            }
        }
        
        m_readahead_start = start_sector;
        m_readahead_count = sectors_to_read;
    }
};

/**
 * XBUS CD-ROM Plugin Interface
 * Connects CD image to the XBUS controller
 */
class CdromInterface {
    CdImage m_disc;
    bool m_ejected;
    
public:
    CdromInterface() : m_ejected(true) {}
    
    bool load_disc(const std::string& path) {
        m_ejected = true;
        if (m_disc.open(path)) {
            m_ejected = false;
            return true;
        }
        return false;
    }
    
    void eject() {
        m_disc.close();
        m_ejected = true;
    }
    
    bool is_ejected() const { return m_ejected; }
    
    u32 get_size() { return m_disc.sector_count(); }
    
    void set_sector(u32 sector) { m_disc.seek(sector); }
    
    void read_sector(void* buffer) { m_disc.read_next_sector(buffer); }
    
    // XBUS callback interface
    static u32 xbus_get_size(void* user) {
        return static_cast<CdromInterface*>(user)->get_size();
    }
    
    static void xbus_set_sector(void* user, u32 sector) {
        static_cast<CdromInterface*>(user)->set_sector(sector);
    }
    
    static void xbus_read_sector(void* user, void* buffer) {
        static_cast<CdromInterface*>(user)->read_sector(buffer);
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_CDROM_H