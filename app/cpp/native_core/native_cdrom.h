/**
 * 3DO Opera Native Core - Optimized CD-ROM Interface
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
#include "native_chd_backend.h"
#include <string>
#include <cstring>
#include <fstream>
#include <memory>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <algorithm>
#include <cctype>
#include <array>

extern "C" {
#include <wavpack.h>
}

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
    static constexpr size_t RAM_PRELOAD_LIMIT = 800ULL * 1024ULL * 1024ULL;
    
    // Cache configuration
    static constexpr u32 CACHE_SECTORS = 64;       // 64 sectors = 128KB cache
    static constexpr u32 READAHEAD_SECTORS = 16;   // Prefetch 16 sectors ahead
    static constexpr u32 CACHE_SIZE = CACHE_SECTORS * SECTOR_SIZE;
    
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

public:
    struct TrackMetadata {
        u8 track_number{1};
        u32 start_sector{0};
        u32 sector_count{0};
        bool audio{false};
    };

private:
    struct RamSegment {
        u32 track_number{1};
        u32 start_sector{0};
        u32 sector_count{0};
        u32 sector_size{SECTOR_SIZE};
        u32 sector_offset{0};
        bool readable_data{true};
        bool decodable_audio{false};
        std::vector<u8> data;
    };
    
    std::string m_path;
    std::ifstream m_file;
    ImageType m_type;
    u32 m_total_sectors;
    u32 m_sector_size;
    u32 m_sector_offset;       // Offset within raw sector to data
    u32 m_current_sector;
    
    // CHD-specific members
    LibChdBackend m_chd_backend;
    
    // RAM preload for images under 900MB
    std::unique_ptr<u8[]> m_ram_data;
    size_t m_ram_size;
    std::vector<RamSegment> m_ram_segments;
    std::vector<TrackMetadata> m_tracks;

    struct MemoryReader {
        const u8* data{nullptr};
        size_t size{0};
        size_t position{0};
    };
    
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
                m_sector_offset = 24;  // 3DO CUE/BIN images are usually MODE2/2352
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
            add_single_data_track();
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
                auto_detect_raw_layout_from_file();
            }
        }
        
        m_total_sectors = file_size / m_sector_size;
        add_single_data_track();
        
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
        m_ram_segments.clear();
        m_tracks.clear();
        m_chd_backend.close();
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
        
        // Multi-file CUE RAM-backed read
        if (!m_ram_segments.empty()) {
            for (const RamSegment& segment : m_ram_segments) {
                if (sector < segment.start_sector || sector >= segment.start_sector + segment.sector_count) {
                    continue;
                }

                const u32 local_sector = sector - segment.start_sector;
                if (!segment.readable_data) {
                    memset(buffer, 0, SECTOR_SIZE);
                    return false;
                }

                const size_t pos = static_cast<size_t>(local_sector) * segment.sector_size + segment.sector_offset;
                if (pos + SECTOR_SIZE <= segment.data.size()) {
                    memcpy(buffer, segment.data.data() + pos, SECTOR_SIZE);
                    return true;
                }

                memset(buffer, 0, SECTOR_SIZE);
                return false;
            }

            memset(buffer, 0, SECTOR_SIZE);
            return false;
        }

        // Single-file RAM-backed read (fastest path)
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

    const std::vector<TrackMetadata>& tracks() const { return m_tracks; }
    
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

    bool read_audio_sector(u32 sector, void* buffer, size_t buffer_size) {
        if (!buffer || buffer_size < RAW_SECTOR_SIZE) {
            return false;
        }

        memset(buffer, 0, buffer_size);
        for (const RamSegment& segment : m_ram_segments) {
            if (segment.readable_data || sector < segment.start_sector || sector >= segment.start_sector + segment.sector_count) {
                continue;
            }

                const u32 local_sector = sector - segment.start_sector;
                if (!segment.decodable_audio) {
                    return false;
                }
                const size_t pos = static_cast<size_t>(local_sector) * segment.sector_size + segment.sector_offset;
            if (pos + RAW_SECTOR_SIZE <= segment.data.size()) {
                memcpy(buffer, segment.data.data() + pos, RAW_SECTOR_SIZE);
                return true;
            }
            return false;
        }

        return false;
    }

    const TrackMetadata* find_track(u32 track_number) const {
        for (const TrackMetadata& track : m_tracks) {
            if (track.track_number == track_number) {
                return &track;
            }
        }
        return nullptr;
    }
    
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
        std::vector<u8> chd_data;
        if (read_file_to_vector(path, chd_data, RAM_PRELOAD_LIMIT)) {
            if (m_chd_backend.open_memory(path, std::move(chd_data))) {
                m_type = ImageType::CHD;
                m_sector_size = SECTOR_SIZE;
                m_sector_offset = 0;
                m_total_sectors = m_chd_backend.sector_count();
                add_single_data_track();
                return true;
            }
        }

        if (!m_chd_backend.open(path)) {
            return false;
        }

        m_type = ImageType::CHD;
        m_sector_size = SECTOR_SIZE;
        m_sector_offset = 0;
        m_total_sectors = m_chd_backend.sector_count();
        add_single_data_track();
        return true;
    }
    
    /**
     * Read a sector from CHD file
     */
    bool read_chd_sector(u32 sector, void* buffer) {
        return m_chd_backend.read_sector(sector, buffer);
    }
    
    // ==================== END CHD SUPPORT ====================
    
    /**
     * Preload image to RAM for faster access
     */
    bool preload_to_ram() {
        std::ifstream file(m_path, std::ios::binary | std::ios::ate);
        if (!file) return false;
        
        size_t size = file.tellg();
        if (size > RAM_PRELOAD_LIMIT) return false;
        
        m_ram_size = size;
        m_ram_data = std::make_unique<u8[]>(size);
        
        file.seekg(0, std::ios::beg);
        file.read(reinterpret_cast<char*>(m_ram_data.get()), size);
        
        if (!file) {
            m_ram_data.reset();
            m_ram_size = 0;
            return false;
        }
        
        if (m_type == ImageType::BIN_CUE && m_sector_size == RAW_SECTOR_SIZE) {
            auto_detect_raw_layout_from_memory(m_ram_data.get(), m_ram_size);
        }

        // Update sector count
        m_total_sectors = size / m_sector_size;
        return true;
    }
    
    /**
     * Parse CUE file and preload all referenced files.
     */
    bool open_from_cue(const std::string& cue_path) {
        std::ifstream cue(cue_path);
        if (!cue) {
            LOGE("Failed to open CUE file: %s", cue_path.c_str());
            return false;
        }
        
        std::string line;
        std::vector<RamSegment> segments;
        size_t total_bytes = 0;
        u32 next_sector = 0;
        u8 next_track_number = 1;
        std::string current_file;
        std::string current_file_type;
        u32 current_sector_size = RAW_SECTOR_SIZE;
        u32 current_sector_offset = 24;
        const std::string cue_dir = parent_dir(cue_path);

        while (std::getline(cue, line)) {
            const std::string trimmed = trim(line);
            const std::string upper = to_upper(trimmed);

            if (upper.find("FILE") == 0) {
                parse_cue_file_line(trimmed, current_file, current_file_type);
                continue;
            }

            if (upper.find("TRACK") == 0 && !current_file.empty()) {
                const bool audio_track = upper.find(" AUDIO") != std::string::npos
                        || to_upper(current_file_type).find("WAVE") != std::string::npos
                        || to_upper(current_file_type).find("WAV") != std::string::npos
                        || to_upper(current_file_type).find("WAVPACK") != std::string::npos
                        || ends_with_ignore_case(current_file, ".wav")
                        || ends_with_ignore_case(current_file, ".wv");

                const std::string data_path = join_path(cue_dir, current_file);
                std::vector<u8> data;
                if (total_bytes >= RAM_PRELOAD_LIMIT) {
                    LOGE("CUE referenced files exceed RAM preload limit: %s", cue_path.c_str());
                    return false;
                }
                if (!read_file_to_vector(data_path, data, RAM_PRELOAD_LIMIT - total_bytes)) {
                    LOGE("Failed to preload CUE referenced file: %s", data_path.c_str());
                    return false;
                }

                RamSegment segment;
                segment.track_number = next_track_number++;
                segment.start_sector = next_sector;
                if (audio_track) {
                    size_t audio_offset = 0;
                    size_t audio_bytes = data.size();
                    const bool wavpack = ends_with_ignore_case(current_file, ".wv") || is_wavpack_data(data);
                    const bool wav_pcm = !wavpack && find_wav_audio_payload(data, audio_offset, audio_bytes);
                    segment.sector_size = RAW_SECTOR_SIZE;
                    if (wavpack) {
                        std::vector<u8> decoded_cdda;
                        if (!decode_wavpack_to_cdda(data, decoded_cdda)) {
                            LOGE("Failed to decode WavPack CUE audio file: %s", data_path.c_str());
                            return false;
                        }
                        data = std::move(decoded_cdda);
                        segment.sector_offset = 0;
                        segment.sector_count = static_cast<u32>(data.size() / RAW_SECTOR_SIZE);
                        segment.decodable_audio = true;
                    } else {
                        segment.sector_offset = static_cast<u32>(audio_offset);
                        segment.sector_count = static_cast<u32>(audio_bytes / RAW_SECTOR_SIZE);
                        segment.decodable_audio = wav_pcm;
                    }
                    segment.readable_data = false;
                } else {
                    parse_cue_track_mode(upper, current_sector_size, current_sector_offset);
                    segment.sector_size = current_sector_size;
                    segment.sector_offset = current_sector_offset;
                    segment.sector_count = static_cast<u32>(data.size() / current_sector_size);
                    segment.readable_data = true;
                }
                segment.data = std::move(data);

                if (segment.sector_count == 0) {
                    LOGE("CUE referenced file has no readable sectors: %s", data_path.c_str());
                    return false;
                }

                total_bytes += segment.data.size();
                next_sector += segment.sector_count;

                TrackMetadata track;
                track.track_number = static_cast<u8>(segment.track_number);
                track.start_sector = segment.start_sector;
                track.sector_count = segment.sector_count;
                track.audio = !segment.readable_data;
                m_tracks.push_back(track);

                segments.push_back(std::move(segment));
            }
        }
        
        if (segments.empty()) {
            LOGE("No supported tracks found in CUE: %s", cue_path.c_str());
            return false;
        }

        m_path = cue_path;
        m_type = ImageType::BIN_CUE;
        m_sector_size = segments.front().sector_size;
        m_sector_offset = segments.front().sector_offset;
        m_total_sectors = next_sector;
        m_ram_segments = std::move(segments);

        LOGI("CUE image preloaded to RAM: %s (%zu files, %zu bytes, %u sectors)",
             cue_path.c_str(), m_ram_segments.size(), total_bytes, m_total_sectors);
        return true;
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

    static std::string to_upper(std::string value) {
        for (char& ch : value) {
            ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
        }
        return value;
    }

    static std::string trim(const std::string& value) {
        const size_t start = value.find_first_not_of(" \t\r\n");
        if (start == std::string::npos) {
            return "";
        }
        const size_t end = value.find_last_not_of(" \t\r\n");
        return value.substr(start, end - start + 1);
    }

    static std::string parent_dir(const std::string& path) {
        const size_t slash = path.find_last_of("/\\");
        if (slash == std::string::npos) {
            return ".";
        }
        return path.substr(0, slash);
    }

    static std::string join_path(const std::string& dir, const std::string& file) {
        std::string normalized_file = file;
        std::replace(normalized_file.begin(), normalized_file.end(), '\\', '/');
        if (normalized_file.size() > 0 && normalized_file[0] == '/') {
            return normalized_file;
        }
        if (dir.empty() || dir == ".") {
            return normalized_file;
        }
        return dir + "/" + normalized_file;
    }

    static bool ends_with_ignore_case(const std::string& value, const char* suffix) {
        const size_t suffix_length = std::strlen(suffix);
        if (value.size() < suffix_length) {
            return false;
        }
        std::string tail = value.substr(value.size() - suffix_length);
        return to_upper(tail) == to_upper(std::string(suffix));
    }

    static bool read_file_to_vector(const std::string& path, std::vector<u8>& data, size_t limit) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) {
            return false;
        }

        const size_t size = static_cast<size_t>(file.tellg());
        if (size == 0 || size > limit) {
            LOGE("CD image file is empty or exceeds RAM preload limit: %s (%zu bytes, limit=%zu)",
                 path.c_str(), size, limit);
            return false;
        }

        data.resize(size);
        file.seekg(0, std::ios::beg);
        file.read(reinterpret_cast<char*>(data.data()), size);
        return static_cast<bool>(file);
    }

    static void parse_cue_track_mode(const std::string& upper_line, u32& sector_size, u32& sector_offset) {
        if (upper_line.find("2048") != std::string::npos) {
            sector_size = SECTOR_SIZE;
            sector_offset = 0;
            return;
        }

        sector_size = RAW_SECTOR_SIZE;
        if (upper_line.find("MODE1") != std::string::npos) {
            sector_offset = 16;
        } else {
            sector_offset = 24;
        }
    }

    static bool has_3do_disc_label(const u8* data, size_t size, u32 sector_size, u32 sector_offset) {
        if (!data || sector_size == 0) {
            return false;
        }

        const size_t pos = static_cast<size_t>(DISC_LABEL_OFFSET) * sector_size + sector_offset;
        if (pos + sizeof(DiscLabel) > size) {
            return false;
        }

        const DiscLabel* label = reinterpret_cast<const DiscLabel*>(data + pos);
        if (label->dl_RecordType != DISC_LABEL_RECORD_TYPE
                || label->dl_VolumeStructureVersion != VOLUME_STRUCTURE_OPERA_READONLY) {
            return false;
        }

        for (int index = 0; index < VOLUME_SYNC_BYTE_LEN; ++index) {
            if (label->dl_VolumeSyncBytes[index] != VOLUME_SYNC_BYTE) {
                return false;
            }
        }
        return true;
    }

    static bool has_3do_disc_label_at(const u8* data, size_t size, size_t offset) {
        if (!data || offset + sizeof(DiscLabel) > size) {
            return false;
        }

        const DiscLabel* label = reinterpret_cast<const DiscLabel*>(data + offset);
        if (label->dl_RecordType != DISC_LABEL_RECORD_TYPE
                || label->dl_VolumeStructureVersion != VOLUME_STRUCTURE_OPERA_READONLY) {
            return false;
        }

        for (int index = 0; index < VOLUME_SYNC_BYTE_LEN; ++index) {
            if (label->dl_VolumeSyncBytes[index] != VOLUME_SYNC_BYTE) {
                return false;
            }
        }
        return true;
    }

    void auto_detect_raw_layout_from_memory(const u8* data, size_t size) {
        if (m_sector_size != RAW_SECTOR_SIZE) {
            return;
        }

        if (has_3do_disc_label(data, size, RAW_SECTOR_SIZE, 24)) {
            m_sector_offset = 24;
            return;
        }
        if (has_3do_disc_label(data, size, RAW_SECTOR_SIZE, 16)) {
            m_sector_offset = 16;
        }
    }

    void auto_detect_raw_layout_from_file() {
        if (!m_file.is_open() || m_sector_size != RAW_SECTOR_SIZE) {
            return;
        }

        u8 probe[RAW_SECTOR_SIZE] = {};
        const size_t pos = static_cast<size_t>(DISC_LABEL_OFFSET) * RAW_SECTOR_SIZE;
        m_file.seekg(pos, std::ios::beg);
        m_file.read(reinterpret_cast<char*>(probe), sizeof(probe));
        m_file.clear();
        m_file.seekg(0, std::ios::beg);

        if (has_3do_disc_label_at(probe, sizeof(probe), 24)) {
            m_sector_offset = 24;
        } else if (has_3do_disc_label_at(probe, sizeof(probe), 16)) {
            m_sector_offset = 16;
        }
    }

    static bool find_wav_audio_payload(const std::vector<u8>& data, size_t& offset, size_t& length) {
        if (data.size() < 44 || std::memcmp(data.data(), "RIFF", 4) != 0 || std::memcmp(data.data() + 8, "WAVE", 4) != 0) {
            offset = 0;
            length = data.size();
            return false;
        }

        size_t cursor = 12;
        while (cursor + 8 <= data.size()) {
            uint32_t chunk_size = 0;
            std::memcpy(&chunk_size, data.data() + cursor + 4, sizeof(chunk_size));
            const size_t chunk_data = cursor + 8;
            if (chunk_data + chunk_size > data.size()) {
                break;
            }
            if (std::memcmp(data.data() + cursor, "data", 4) == 0) {
                offset = chunk_data;
                length = chunk_size;
                return true;
            }
            cursor = chunk_data + chunk_size + (chunk_size & 1U);
        }

        offset = 44;
        length = data.size() > offset ? data.size() - offset : 0;
        return false;
    }

    static bool is_wavpack_data(const std::vector<u8>& data) {
        return data.size() >= 4 && std::memcmp(data.data(), "wvpk", 4) == 0;
    }

    static bool parse_cue_file_line(const std::string& line, std::string& path, std::string& type) {
        if (line.size() < 4 || to_upper(line.substr(0, 4)) != "FILE") {
            return false;
        }

        std::string rest = trim(line.substr(4));
        if (rest.empty()) {
            return false;
        }

        if (rest[0] == '"') {
            const size_t quote2 = rest.find('"', 1);
            if (quote2 == std::string::npos || quote2 <= 1) {
                return false;
            }
            path = rest.substr(1, quote2 - 1);
            type = trim(rest.substr(quote2 + 1));
            return !path.empty();
        }

        const size_t last_space = rest.find_last_of(" \t");
        if (last_space == std::string::npos) {
            path = rest;
            type.clear();
        } else {
            path = trim(rest.substr(0, last_space));
            type = trim(rest.substr(last_space + 1));
        }
        return !path.empty();
    }

    static u32 estimate_wavpack_sector_count(const std::vector<u8>& data) {
        if (data.size() >= 16 && std::memcmp(data.data(), "wvpk", 4) == 0) {
            uint32_t samples = 0;
            std::memcpy(&samples, data.data() + 12, sizeof(samples));
            if (samples > 0) {
                return (samples + 587U) / 588U;
            }
        }
        return static_cast<u32>((data.size() + RAW_SECTOR_SIZE - 1) / RAW_SECTOR_SIZE);
    }

    static int32_t wavpack_read_bytes(void* id, void* out, int32_t bytes) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        if (!reader || !out || bytes <= 0) {
            return 0;
        }
        const size_t available = reader->position < reader->size ? reader->size - reader->position : 0;
        const size_t count = std::min(static_cast<size_t>(bytes), available);
        if (count > 0) {
            std::memcpy(out, reader->data + reader->position, count);
            reader->position += count;
        }
        return static_cast<int32_t>(count);
    }

    static int64_t wavpack_get_pos(void* id) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        return reader ? static_cast<int64_t>(reader->position) : -1;
    }

    static int wavpack_set_pos_abs(void* id, int64_t pos) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        if (!reader || pos < 0 || static_cast<uint64_t>(pos) > reader->size) {
            return -1;
        }
        reader->position = static_cast<size_t>(pos);
        return 0;
    }

    static int wavpack_set_pos_rel(void* id, int64_t delta, int mode) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        if (!reader) {
            return -1;
        }
        int64_t base = 0;
        if (mode == SEEK_SET) {
            base = 0;
        } else if (mode == SEEK_CUR) {
            base = static_cast<int64_t>(reader->position);
        } else if (mode == SEEK_END) {
            base = static_cast<int64_t>(reader->size);
        } else {
            return -1;
        }
        return wavpack_set_pos_abs(id, base + delta);
    }

    static int wavpack_push_back_byte(void* id, int) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        if (!reader || reader->position == 0) {
            return EOF;
        }
        reader->position--;
        return 0;
    }

    static int64_t wavpack_get_length(void* id) {
        MemoryReader* reader = static_cast<MemoryReader*>(id);
        return reader ? static_cast<int64_t>(reader->size) : -1;
    }

    static int wavpack_can_seek(void*) {
        return 1;
    }

    static int wavpack_close(void*) {
        return 0;
    }

    static int16_t convert_wavpack_sample_to_s16(int32_t sample, int bytes_per_sample) {
        if (bytes_per_sample <= 1) {
            sample <<= 8;
        } else if (bytes_per_sample > 2) {
            sample >>= (bytes_per_sample - 2) * 8;
        }
        sample = std::max(-32768, std::min(32767, sample));
        return static_cast<int16_t>(sample);
    }

    static bool decode_wavpack_to_cdda(const std::vector<u8>& encoded, std::vector<u8>& out_cdda) {
        MemoryReader reader{encoded.data(), encoded.size(), 0};
        WavpackStreamReader64 callbacks = {};
        callbacks.read_bytes = wavpack_read_bytes;
        callbacks.get_pos = wavpack_get_pos;
        callbacks.set_pos_abs = wavpack_set_pos_abs;
        callbacks.set_pos_rel = wavpack_set_pos_rel;
        callbacks.push_back_byte = wavpack_push_back_byte;
        callbacks.get_length = wavpack_get_length;
        callbacks.can_seek = wavpack_can_seek;
        callbacks.close = wavpack_close;

        char error[128] = {};
        WavpackContext* context = WavpackOpenFileInputEx64(&callbacks, &reader, nullptr, error, OPEN_2CH_MAX, 0);
        if (!context) {
            LOGE("WavPack open failed: %s", error);
            return false;
        }

        const int channels = WavpackGetNumChannels(context);
        const int sample_rate = static_cast<int>(WavpackGetSampleRate(context));
        const int bytes_per_sample = WavpackGetBytesPerSample(context);
        const int64_t total_samples = WavpackGetNumSamples64(context);
        if (channels < 1 || sample_rate != 44100 || bytes_per_sample < 1 || bytes_per_sample > 4 || total_samples <= 0) {
            LOGE("Unsupported WavPack CDDA track: channels=%d sample_rate=%d bytes=%d samples=%lld",
                 channels, sample_rate, bytes_per_sample, static_cast<long long>(total_samples));
            WavpackCloseFile(context);
            return false;
        }

        const size_t total_sectors = static_cast<size_t>((total_samples + 587) / 588);
        out_cdda.assign(total_sectors * RAW_SECTOR_SIZE, 0);

        std::vector<int32_t> samples(588 * channels);
        size_t output_frame = 0;
        while (true) {
            const uint32_t unpacked = WavpackUnpackSamples(context, samples.data(), 588);
            if (unpacked == 0) {
                break;
            }
            for (uint32_t frame = 0; frame < unpacked; ++frame) {
                const int32_t left_sample = samples[frame * channels];
                const int32_t right_sample = channels > 1 ? samples[frame * channels + 1] : left_sample;
                const int16_t left = convert_wavpack_sample_to_s16(left_sample, bytes_per_sample);
                const int16_t right = convert_wavpack_sample_to_s16(right_sample, bytes_per_sample);
                const size_t offset = output_frame * 4;
                out_cdda[offset] = static_cast<u8>(left & 0xFF);
                out_cdda[offset + 1] = static_cast<u8>((left >> 8) & 0xFF);
                out_cdda[offset + 2] = static_cast<u8>(right & 0xFF);
                out_cdda[offset + 3] = static_cast<u8>((right >> 8) & 0xFF);
                output_frame++;
            }
        }

        WavpackCloseFile(context);
        return output_frame > 0;
    }

    void add_single_data_track() {
        m_tracks.clear();
        if (m_total_sectors == 0) {
            return;
        }

        TrackMetadata track;
        track.track_number = 1;
        track.start_sector = 0;
        track.sector_count = m_total_sectors;
        track.audio = false;
        m_tracks.push_back(track);
    }
};

/**
 * XBUS CD-ROM Plugin Interface
 * Connects CD image to the XBUS controller
 */
class CdromInterface {
    CdImage m_disc;
    bool m_ejected;
    bool m_audio_playing;
    bool m_audio_paused;
    u32 m_audio_current_sector;
    u32 m_audio_end_sector;
    u32 m_audio_frame_offset;
    std::array<u8, 2352> m_audio_sector;
    
public:
    CdromInterface() : m_ejected(true), m_audio_playing(false), m_audio_paused(false),
                       m_audio_current_sector(0), m_audio_end_sector(0), m_audio_frame_offset(588) {}
    
    bool load_disc(const std::string& path) {
        m_ejected = true;
        stop_audio();
        if (m_disc.open(path)) {
            m_ejected = false;
            return true;
        }
        return false;
    }
    
    void eject() {
        stop_audio();
        m_disc.close();
        m_ejected = true;
    }
    
    bool is_ejected() const { return m_ejected; }
    
    u32 get_size() {
        u32 sz = m_disc.sector_count();
        LOGI("CdromInterface::get_size() = %u", sz);
        return sz;
    }

    const std::vector<CdImage::TrackMetadata>& tracks() const { return m_disc.tracks(); }

    void set_sector(u32 sector) { m_disc.seek(sector); }

    void read_sector(void* buffer) { m_disc.read_next_sector(buffer); }

    bool read_audio_sector(u32 sector, void* buffer, size_t buffer_size) {
        return m_disc.read_audio_sector(sector, buffer, buffer_size);
    }

    bool play_audio_range(u32 start_sector, u32 end_sector) {
        if (m_ejected || start_sector >= end_sector || end_sector > m_disc.sector_count()) {
            return false;
        }

        m_audio_current_sector = start_sector;
        m_audio_end_sector = end_sector;
        m_audio_frame_offset = 588;
        m_audio_playing = true;
        m_audio_paused = false;
        return true;
    }

    bool play_audio_tracks(u32 start_track, u32 end_track) {
        const CdImage::TrackMetadata* start = m_disc.find_track(start_track);
        const CdImage::TrackMetadata* end = m_disc.find_track(end_track);
        if (!start || !end || !start->audio || !end->audio || end->start_sector < start->start_sector) {
            return false;
        }
        return play_audio_range(start->start_sector, end->start_sector + end->sector_count);
    }

    void pause_audio(bool paused) {
        m_audio_paused = paused;
    }

    void stop_audio() {
        m_audio_playing = false;
        m_audio_paused = false;
        m_audio_current_sector = 0;
        m_audio_end_sector = 0;
        m_audio_frame_offset = 588;
    }

    bool is_audio_playing() const {
        return m_audio_playing && !m_audio_paused;
    }

    int mix_audio(u32* out_buffer, int max_frames) {
        if (!out_buffer || max_frames <= 0 || !m_audio_playing || m_audio_paused) {
            return 0;
        }

        int frames_mixed = 0;
        while (frames_mixed < max_frames && m_audio_playing) {
            if (m_audio_frame_offset >= 588) {
                if (m_audio_current_sector >= m_audio_end_sector ||
                        !m_disc.read_audio_sector(m_audio_current_sector, m_audio_sector.data(), m_audio_sector.size())) {
                    stop_audio();
                    break;
                }
                m_audio_current_sector++;
                m_audio_frame_offset = 0;
            }

            const size_t byte_offset = static_cast<size_t>(m_audio_frame_offset) * 4;
            int16_t cdda_left = static_cast<int16_t>(m_audio_sector[byte_offset] | (m_audio_sector[byte_offset + 1] << 8));
            int16_t cdda_right = static_cast<int16_t>(m_audio_sector[byte_offset + 2] | (m_audio_sector[byte_offset + 3] << 8));

            const u32 existing = out_buffer[frames_mixed];
            int mixed_left = static_cast<int16_t>(existing & 0xFFFF) + cdda_left;
            int mixed_right = static_cast<int16_t>((existing >> 16) & 0xFFFF) + cdda_right;
            mixed_left = std::max(-32768, std::min(32767, mixed_left));
            mixed_right = std::max(-32768, std::min(32767, mixed_right));

            out_buffer[frames_mixed] = (static_cast<u16>(mixed_right) << 16) | static_cast<u16>(mixed_left);
            frames_mixed++;
            m_audio_frame_offset++;
        }

        return frames_mixed;
    }
    
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
