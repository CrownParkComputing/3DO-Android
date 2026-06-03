#ifndef FOURDO_NATIVE_CHD_BACKEND_H
#define FOURDO_NATIVE_CHD_BACKEND_H

#include "native_log.h"
#include "native_types.h"
#include "native_backend_discdata.h"
#include "native_backend_endianness.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include <libchdr/cdrom.h>
#include <libchdr/chd.h>
}

namespace fourdo {
namespace core {

class LibChdBackend {
    struct TrackInfo {
        std::string type;
        u32 start_frame{0};
        u32 frames{0};
        u32 pregap_frames{0};
        u32 postgap_frames{0};
        u32 chd_pad_frames{0};
        u32 start_sector{0};
        u32 sector_count{0};
        u32 data_offset{0};
        bool data_byteswap{false};
        bool supported{false};
    };

    struct LayoutCandidate {
        u32 data_offset{0};
        bool data_byteswap{false};
        const char* description{nullptr};
    };

    struct DiscLabelProbe {
        bool valid{false};
        u32 root_avatar{0};
        u32 volume_block_count{0};
        u32 block_size{0};
        u32 root_block_size{0};
        u32 label_offset{0};
        u8 volume_flags{0};
    };

    chd_file* m_chd{nullptr};
    std::string m_path;
    struct MemoryFile {
        std::vector<u8> data;
        size_t position{0};
    };
    MemoryFile m_memory_file;
    std::unique_ptr<u8[]> m_hunk_buffer;
    u32 m_hunk_bytes{0};
    u32 m_frame_bytes{0};
    u32 m_frames_per_hunk{0};
    u32 m_total_sectors{0};
    u32 m_logical_block_count{0};
    u32 m_cached_hunk{UINT32_MAX};
    std::vector<TrackInfo> m_tracks;

public:
    ~LibChdBackend() {
        close();
    }

    bool open(const std::string& path) {
        close();
        m_path = path;

        chd_error err = chd_open(path.c_str(), CHD_OPEN_READ, nullptr, &m_chd);
        if (err != CHDERR_NONE) {
            LOGE("libchdr failed to open CHD '%s': %s", path.c_str(), chd_error_string(err));
            m_chd = nullptr;
            return false;
        }

        const chd_header* header = chd_get_header(m_chd);
        if (!header) {
            LOGE("libchdr returned no CHD header for: %s", path.c_str());
            close();
            return false;
        }

        if (header->hunkbytes == 0 || header->unitbytes == 0) {
            LOGE("Invalid CHD geometry for '%s': hunkbytes=%u unitbytes=%u",
                path.c_str(), header->hunkbytes, header->unitbytes);
            close();
            return false;
        }

        if (header->hunkbytes % header->unitbytes != 0) {
            LOGE("CHD hunk size is not aligned to frame size for '%s': hunkbytes=%u unitbytes=%u",
                path.c_str(), header->hunkbytes, header->unitbytes);
            close();
            return false;
        }

        if (header->unitbytes < CD_MAX_SECTOR_DATA) {
            LOGE("CHD frame size is too small for CD sectors in '%s': unitbytes=%u",
                path.c_str(), header->unitbytes);
            close();
            return false;
        }

        m_hunk_bytes = header->hunkbytes;
        m_frame_bytes = header->unitbytes;
        m_frames_per_hunk = m_hunk_bytes / m_frame_bytes;
        m_hunk_buffer = std::make_unique<u8[]>(m_hunk_bytes);
        m_cached_hunk = UINT32_MAX;

        load_tracks(*header);
        if (m_total_sectors == 0) {
            LOGE("No readable data tracks found in CHD: %s", path.c_str());
            close();
            return false;
        }

        LOGI("CHD opened via libchdr: %s (version=%u, hunkbytes=%u, unitbytes=%u, sectors=%u, tracks=%zu)",
            path.c_str(), header->version, m_hunk_bytes, m_frame_bytes, m_total_sectors, m_tracks.size());
        return true;
    }

    bool open_memory(const std::string& path, std::vector<u8>&& data) {
        close();
        m_path = path;
        m_memory_file.data = std::move(data);
        m_memory_file.position = 0;

        chd_error err = chd_open_core_file_callbacks(&memory_callbacks(), &m_memory_file, CHD_OPEN_READ, nullptr, &m_chd);
        if (err != CHDERR_NONE) {
            LOGE("libchdr failed to open memory CHD '%s': %s", path.c_str(), chd_error_string(err));
            m_chd = nullptr;
            m_memory_file.data.clear();
            return false;
        }

        const chd_header* header = chd_get_header(m_chd);
        if (!header) {
            LOGE("libchdr returned no CHD header for memory image: %s", path.c_str());
            close();
            return false;
        }

        if (header->hunkbytes == 0 || header->unitbytes == 0 || header->hunkbytes % header->unitbytes != 0
                || header->unitbytes < CD_MAX_SECTOR_DATA) {
            LOGE("Invalid CHD geometry for memory image '%s': hunkbytes=%u unitbytes=%u",
                path.c_str(), header->hunkbytes, header->unitbytes);
            close();
            return false;
        }

        m_hunk_bytes = header->hunkbytes;
        m_frame_bytes = header->unitbytes;
        m_frames_per_hunk = m_hunk_bytes / m_frame_bytes;
        m_hunk_buffer = std::make_unique<u8[]>(m_hunk_bytes);
        m_cached_hunk = UINT32_MAX;

        load_tracks(*header);
        if (m_total_sectors == 0) {
            LOGE("No readable data tracks found in memory CHD: %s", path.c_str());
            close();
            return false;
        }

        LOGI("CHD opened from RAM via libchdr: %s (%zu bytes, sectors=%u, tracks=%zu)",
            path.c_str(), m_memory_file.data.size(), m_total_sectors, m_tracks.size());
        return true;
    }

    void close() {
        if (m_chd) {
            chd_close(m_chd);
            m_chd = nullptr;
        }
        m_path.clear();
        m_memory_file.data.clear();
        m_memory_file.position = 0;
        m_hunk_buffer.reset();
        m_hunk_bytes = 0;
        m_frame_bytes = 0;
        m_frames_per_hunk = 0;
        m_total_sectors = 0;
        m_logical_block_count = 0;
        m_cached_hunk = UINT32_MAX;
        m_tracks.clear();
    }

    bool is_open() const {
        return m_chd != nullptr;
    }

    u32 sector_count() const {
        return m_total_sectors;
    }

    bool read_sector(u32 sector, void* buffer) {
        if (!buffer) {
            return false;
        }
        if (!m_chd || !m_hunk_buffer || sector >= m_total_sectors) {
            std::memset(buffer, 0, SECTOR_SIZE);
            return false;
        }

        const TrackInfo* track = find_track(sector);
        if (!track || !track->supported) {
            LOGW("Unsupported or missing CHD track for sector %u in '%s'", sector, m_path.c_str());
            std::memset(buffer, 0, SECTOR_SIZE);
            return false;
        }

        const u32 frame = track->start_frame + (sector - track->start_sector);
        if (!load_hunk(frame)) {
            std::memset(buffer, 0, SECTOR_SIZE);
            return false;
        }

        const u32 frame_index = frame % m_frames_per_hunk;
        const size_t frame_offset = static_cast<size_t>(frame_index) * m_frame_bytes;
        const size_t data_offset = frame_offset + track->data_offset;
        if (data_offset + SECTOR_SIZE > m_hunk_bytes) {
            LOGE("CHD frame offset overflow for '%s': sector=%u frame=%u offset=%zu hunkbytes=%u",
                m_path.c_str(), sector, frame, data_offset, m_hunk_bytes);
            std::memset(buffer, 0, SECTOR_SIZE);
            return false;
        }

        std::memcpy(buffer, m_hunk_buffer.get() + data_offset, SECTOR_SIZE);
        if (track->data_byteswap) {
            byteswap_pairs(reinterpret_cast<u8*>(buffer), SECTOR_SIZE);
        }

        if (sector < 4 || sector == 16 || sector == 17 || sector == 18 || sector == 19) {
            const u8* d = reinterpret_cast<const u8*>(buffer);
            LOGI("CHD sector %u data[0..15]: %02x %02x %02x %02x %02x %02x %02x %02x  %02x %02x %02x %02x %02x %02x %02x %02x",
                 sector, d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7],
                 d[8], d[9], d[10], d[11], d[12], d[13], d[14], d[15]);
            LOGI("CHD sector %u frame=%u start_frame=%u start_sector=%u data_offset=%zu hunk=%u",
                 sector, frame, track->start_frame, track->start_sector, data_offset, frame / m_frames_per_hunk);
        }

        return true;
    }

private:
    static constexpr u32 SECTOR_SIZE = 2048;
    static constexpr u32 MODE1_RAW_DATA_OFFSET = 16;
    static constexpr u32 MODE2_RAW_DATA_OFFSET = 24;

    static constexpr u32 align_track_frames(u32 frames) {
        return (frames + (CD_TRACK_PADDING - 1)) & ~(CD_TRACK_PADDING - 1);
    }

    static u32 read_be32(const void* ptr) {
        u32 value = 0;
        std::memcpy(&value, ptr, sizeof(value));
        return swap32_if_le(value);
    }

    static int32_t read_be32s(const void* ptr) {
        return static_cast<int32_t>(read_be32(ptr));
    }

    static void byteswap_pairs(u8* data, size_t length) {
        for (size_t index = 0; index + 1 < length; index += 2) {
            std::swap(data[index], data[index + 1]);
        }
    }

    static uint64_t memory_fsize(void* user) {
        const MemoryFile* file = static_cast<const MemoryFile*>(user);
        return file ? static_cast<uint64_t>(file->data.size()) : static_cast<uint64_t>(-1);
    }

    static size_t memory_fread(void* ptr, size_t size, size_t count, void* user) {
        MemoryFile* file = static_cast<MemoryFile*>(user);
        if (!file || !ptr || size == 0 || count == 0) {
            return 0;
        }

        const size_t requested = size * count;
        const size_t available = file->position < file->data.size() ? file->data.size() - file->position : 0;
        const size_t bytes_to_read = std::min(requested, available);
        if (bytes_to_read > 0) {
            std::memcpy(ptr, file->data.data() + file->position, bytes_to_read);
            file->position += bytes_to_read;
        }
        return bytes_to_read / size;
    }

    static int memory_fclose(void*) {
        return 0;
    }

    static int memory_fseek(void* user, int64_t offset, int origin) {
        MemoryFile* file = static_cast<MemoryFile*>(user);
        if (!file) {
            return -1;
        }

        int64_t base = 0;
        if (origin == SEEK_SET) {
            base = 0;
        } else if (origin == SEEK_CUR) {
            base = static_cast<int64_t>(file->position);
        } else if (origin == SEEK_END) {
            base = static_cast<int64_t>(file->data.size());
        } else {
            return -1;
        }

        const int64_t next = base + offset;
        if (next < 0 || static_cast<uint64_t>(next) > file->data.size()) {
            return -1;
        }

        file->position = static_cast<size_t>(next);
        return 0;
    }

    static const core_file_callbacks& memory_callbacks() {
        static const core_file_callbacks callbacks = {
            memory_fsize,
            memory_fread,
            memory_fclose,
            memory_fseek
        };
        return callbacks;
    }

    static bool parse_disc_label_at_offset(const u8* sector, size_t offset, DiscLabelProbe& probe) {
        if (!sector) {
            return false;
        }

        if (offset + sizeof(DiscLabel) > SECTOR_SIZE) {
            return false;
        }

        const DiscLabel* label = reinterpret_cast<const DiscLabel*>(sector + offset);
        if (label->dl_RecordType != DISC_LABEL_RECORD_TYPE) {
            return false;
        }
        if (label->dl_VolumeStructureVersion != VOLUME_STRUCTURE_OPERA_READONLY) {
            return false;
        }
        for (int index = 0; index < VOLUME_SYNC_BYTE_LEN; ++index) {
            if (label->dl_VolumeSyncBytes[index] != VOLUME_SYNC_BYTE) {
                return false;
            }
        }

        probe.block_size = read_be32(&label->dl_VolumeBlockSize);
        probe.root_block_size = read_be32(&label->dl_RootDirectoryBlockSize);
        probe.volume_block_count = read_be32(&label->dl_VolumeBlockCount);
        probe.root_avatar = read_be32(&label->dl_RootDirectoryAvatarList[0]);
        probe.label_offset = static_cast<u32>(offset);
        probe.volume_flags = label->dl_VolumeFlags;

        if (probe.block_size != DISC_BLOCK_SIZE || probe.root_block_size != DISC_BLOCK_SIZE) {
            return false;
        }
        if (probe.volume_block_count == 0 || probe.volume_block_count > DISC_TOTAL_BLOCKS) {
            return false;
        }
        if (probe.root_avatar == 0 || probe.root_avatar >= probe.volume_block_count) {
            return false;
        }

        probe.valid = true;
        return true;
    }

    static bool looks_like_disc_label(const u8* sector, DiscLabelProbe& probe) {
        if (!sector) {
            return false;
        }

        static constexpr size_t offsets[] = {0, DISC_LABEL_OFFSET};
        for (size_t offset : offsets) {
            DiscLabelProbe candidate;
            if (parse_disc_label_at_offset(sector, offset, candidate)) {
                probe = candidate;
                return true;
            }
        }

        return false;
    }

    static bool looks_like_directory_header(const u8* sector) {
        if (!sector) {
            return false;
        }

        const DirectoryHeader* header = reinterpret_cast<const DirectoryHeader*>(sector);
        const int32_t next_block = read_be32s(&header->dh_NextBlock);
        const int32_t prev_block = read_be32s(&header->dh_PrevBlock);
        const u32 first_free = read_be32(&header->dh_FirstFreeByte);
        const u32 first_entry = read_be32(&header->dh_FirstEntryOffset);

        if (first_entry < sizeof(DirectoryHeader) || first_entry > SECTOR_SIZE) {
            return false;
        }
        if (first_free < first_entry || first_free > SECTOR_SIZE) {
            return false;
        }
        if (next_block < -1 || next_block > static_cast<int32_t>(DISC_TOTAL_BLOCKS)) {
            return false;
        }
        if (prev_block < -1 || prev_block > static_cast<int32_t>(DISC_TOTAL_BLOCKS)) {
            return false;
        }

        return true;
    }

    bool extract_sector_for_layout(u32 frame, const LayoutCandidate& candidate, void* buffer) {
        if (!buffer || !load_hunk(frame)) {
            return false;
        }

        const u32 frame_index = frame % m_frames_per_hunk;
        const size_t frame_offset = static_cast<size_t>(frame_index) * m_frame_bytes;
        const size_t data_offset = frame_offset + candidate.data_offset;
        if (data_offset + SECTOR_SIZE > m_hunk_bytes) {
            return false;
        }

        std::memcpy(buffer, m_hunk_buffer.get() + data_offset, SECTOR_SIZE);
        if (candidate.data_byteswap) {
            byteswap_pairs(reinterpret_cast<u8*>(buffer), SECTOR_SIZE);
        }
        return true;
    }

    std::vector<LayoutCandidate> build_layout_candidates(const TrackInfo& track) const {
        std::vector<LayoutCandidate> candidates;
        const auto add_candidate = [&candidates](u32 offset, bool byteswap, const char* description) {
            for (const auto& candidate : candidates) {
                if (candidate.data_offset == offset && candidate.data_byteswap == byteswap) {
                    return;
                }
            }
            candidates.push_back({offset, byteswap, description});
        };

        add_candidate(track.data_offset, track.data_byteswap, "metadata");
        add_candidate(track.data_offset, !track.data_byteswap, "metadata-swapped");
        add_candidate(0, false, "cooked-2048");
        add_candidate(0, true, "cooked-2048-swapped");
        if (m_frame_bytes >= 2352) {
            add_candidate(MODE1_RAW_DATA_OFFSET, false, "mode1-raw");
            add_candidate(MODE1_RAW_DATA_OFFSET, true, "mode1-raw-swapped");
            add_candidate(MODE2_RAW_DATA_OFFSET, false, "mode2-raw");
            add_candidate(MODE2_RAW_DATA_OFFSET, true, "mode2-raw-swapped");
        }

        return candidates;
    }

    void probe_track_layout(TrackInfo& track, u32 track_index) {
        if (!track.supported || track.sector_count == 0) {
            return;
        }

        const auto candidates = build_layout_candidates(track);
        int best_score = -1;
        LayoutCandidate best_candidate{track.data_offset, track.data_byteswap, "existing"};
        DiscLabelProbe best_probe;

        for (const auto& candidate : candidates) {
            u8 sector0[SECTOR_SIZE] = {0};
            if (!extract_sector_for_layout(track.start_frame, candidate, sector0)) {
                continue;
            }

            int score = 0;
            DiscLabelProbe label_probe;
            if (looks_like_disc_label(sector0, label_probe)) {
                score += 100;

                const u32 root_sector = label_probe.root_avatar;
                if (root_sector >= track.start_sector && root_sector < track.start_sector + track.sector_count) {
                    const u32 root_frame = track.start_frame + (root_sector - track.start_sector);
                    u8 root_dir_sector[SECTOR_SIZE] = {0};
                    if (extract_sector_for_layout(root_frame, candidate, root_dir_sector) &&
                        looks_like_directory_header(root_dir_sector)) {
                        score += 100;
                    }
                }
            } else if (std::memcmp(sector0, "\x01ZZZZZ\x01", 8) == 0) {
                score += 50;
            }

            LOGI("CHD track %u probe %s: offset=%u byteswap=%d score=%d root_avatar=%u volume_blocks=%u",
                 track_index + 1,
                 candidate.description ? candidate.description : "unknown",
                 candidate.data_offset,
                 candidate.data_byteswap ? 1 : 0,
                 score,
                 label_probe.root_avatar,
                 label_probe.volume_block_count);
            if (label_probe.valid) {
                LOGI("CHD track %u label probe: label_offset=%u flags=0x%02x block_size=%u root_block_size=%u root_avatar=%u volume_blocks=%u",
                     track_index + 1,
                     label_probe.label_offset,
                     label_probe.volume_flags,
                     label_probe.block_size,
                     label_probe.root_block_size,
                     label_probe.root_avatar,
                     label_probe.volume_block_count);
            }

            if (score > best_score) {
                best_score = score;
                best_candidate = candidate;
                best_probe = label_probe;
            }
        }

        if (best_score >= 100) {
            track.data_offset = best_candidate.data_offset;
            track.data_byteswap = best_candidate.data_byteswap;
            if (best_probe.valid && best_probe.volume_block_count > 0) {
                if (m_logical_block_count == 0 || best_probe.volume_block_count < m_logical_block_count) {
                    m_logical_block_count = best_probe.volume_block_count;
                }
            }
            LOGI("CHD track %u selected layout %s: offset=%u byteswap=%d root_avatar=%u volume_blocks=%u",
                 track_index + 1,
                 best_candidate.description ? best_candidate.description : "unknown",
                 track.data_offset,
                 track.data_byteswap ? 1 : 0,
                  best_probe.root_avatar,
                  best_probe.volume_block_count);
        } else {
            LOGW("CHD track %u could not validate a 3DO disc layout; keeping metadata layout offset=%u byteswap=%d",
                 track_index + 1,
                 track.data_offset,
                 track.data_byteswap ? 1 : 0);
        }
    }

    static std::string normalize_type(const char* value) {
        std::string normalized = value ? value : "";
        std::transform(normalized.begin(), normalized.end(), normalized.begin(), [](unsigned char ch) {
            return static_cast<char>(std::toupper(ch));
        });
        return normalized;
    }

    static bool parse_track_metadata(const char* metadata, TrackInfo& track) {
        int track_number = 0;
        int frames = 0;
        int pregap = 0;
        int postgap = 0;
        int pad = 0;
        char type[64] = {0};
        char subtype[64] = {0};
        char pgtype[64] = {0};
        char pgsub[64] = {0};

        int parsed = std::sscanf(
            metadata,
            "TRACK:%d TYPE:%63s SUBTYPE:%63s FRAMES:%d PREGAP:%d PGTYPE:%63s PGSUB:%63s POSTGAP:%d",
            &track_number,
            type,
            subtype,
            &frames,
            &pregap,
            pgtype,
            pgsub,
            &postgap);
        if (parsed < 4) {
            parsed = std::sscanf(
                metadata,
                "TRACK:%d TYPE:%63s SUBTYPE:%63s FRAMES:%d PAD:%d PREGAP:%d PGTYPE:%63s PGSUB:%63s POSTGAP:%d",
                &track_number,
                type,
                subtype,
                &frames,
                &pad,
                &pregap,
                pgtype,
                pgsub,
                &postgap);
        }
        if (parsed < 4) {
            parsed = std::sscanf(
                metadata,
                "TRACK:%d TYPE:%63s SUBTYPE:%63s FRAMES:%d",
                &track_number,
                type,
                subtype,
                &frames);
        }
        if (parsed < 4 || frames <= 0) {
            return false;
        }

        track.type = normalize_type(type);
        track.frames = static_cast<u32>(frames);
        track.pregap_frames = pregap > 0 ? static_cast<u32>(pregap) : 0;
        track.postgap_frames = postgap > 0 ? static_cast<u32>(postgap) : 0;
        track.chd_pad_frames = pad > 0 ? static_cast<u32>(pad) : 0;
        track.data_offset = 0;
        track.data_byteswap = false;
        track.supported = false;

        if (track.type.find("AUDIO") != std::string::npos) {
            return true;
        }
        if (track.type.find("MODE1_RAW") != std::string::npos) {
            track.data_offset = MODE1_RAW_DATA_OFFSET;
            track.supported = true;
            return true;
        }
        if (track.type.find("MODE1") != std::string::npos) {
            track.data_offset = 0;
            track.supported = true;
            return true;
        }
        if (track.type.find("MODE2_RAW") != std::string::npos) {
            track.data_offset = MODE2_RAW_DATA_OFFSET;
            track.supported = true;
            return true;
        }
        if (track.type.find("MODE2_FORM1") != std::string::npos) {
            // Cooked MODE2 XA Form1: user data at offset 0 if stored as 2048-byte sectors,
            // but offset 24 when the CHD stores raw 2352-byte frames (common for 3DO discs).
            // Actual storage size is determined by m_frame_bytes at load time.
            track.data_offset = 0; // overridden in load_tracks when m_frame_bytes >= 2352
            track.supported = true;
            return true;
        }

        return true;
    }

    bool read_metadata(u32 tag, u32 index, char* buffer, size_t buffer_size) const {
        if (!m_chd || !buffer || buffer_size == 0) {
            return false;
        }
        std::memset(buffer, 0, buffer_size);
        chd_error err = chd_get_metadata(m_chd, tag, index, buffer, static_cast<u32>(buffer_size), nullptr, nullptr, nullptr);
        if (err == CHDERR_NONE) {
            return true;
        }
        return false;
    }

    void load_tracks(const chd_header& header) {
        m_tracks.clear();
        m_total_sectors = 0;
        m_logical_block_count = 0;

        u32 next_frame = 0;
        u32 next_sector = 0;
        bool found_metadata = false;

        for (u32 index = 0; index < CD_MAX_TRACKS; ++index) {
            char metadata[512] = {0};
            bool have_track = read_metadata(CDROM_TRACK_METADATA2_TAG, index, metadata, sizeof(metadata))
                || read_metadata(CDROM_TRACK_METADATA_TAG, index, metadata, sizeof(metadata))
                || read_metadata(GDROM_TRACK_METADATA_TAG, index, metadata, sizeof(metadata));

            if (!have_track) {
                if (found_metadata) {
                    break;
                }
                continue;
            }

            found_metadata = true;
            TrackInfo track;
            if (!parse_track_metadata(metadata, track)) {
                LOGW("Unable to parse CHD track metadata in '%s': %s", m_path.c_str(), metadata);
                continue;
            }

              LOGI("CHD raw track metadata %u: %s",
                  static_cast<unsigned>(m_tracks.size() + 1), metadata);
              LOGI("CHD track %u: type='%s' frames=%u pregap=%u postgap=%u pad=%u data_offset=%u supported=%d",
                  static_cast<unsigned>(m_tracks.size() + 1),
                  track.type.c_str(), track.frames,
                  track.pregap_frames, track.postgap_frames, track.chd_pad_frames,
                  track.data_offset, track.supported ? 1 : 0);

            // When the CHD stores raw CD frames (frame >= 2352 bytes), the data_offset
            // must account for the sector header bytes even if the metadata type string
            // says "MODE2_FORM1" (which normally implies cooked 2048-byte sectors).
            if (track.supported && m_frame_bytes >= 2352) {
                if (track.type.find("MODE2") != std::string::npos) {
                    track.data_offset = MODE2_RAW_DATA_OFFSET;
                } else if (track.type.find("MODE1") != std::string::npos
                           && track.data_offset == 0) {
                    // MODE1 (cooked label) but frame is raw — treat like MODE1_RAW
                    track.data_offset = MODE1_RAW_DATA_OFFSET;
                }
                LOGI("CHD track %u: corrected data_offset to %u for raw frame storage (frame_bytes=%u)",
                     static_cast<unsigned>(m_tracks.size() + 1),
                     track.data_offset, m_frame_bytes);
            }

            track.start_frame = next_frame + track.pregap_frames;

            if (track.supported) {
                track.start_sector = next_sector;
                track.sector_count = track.frames;
                next_sector += track.frames;
            } else {
                LOGW("Ignoring unsupported CHD track type '%s' in '%s'", track.type.c_str(), m_path.c_str());
            }

            const u32 stored_track_frames = track.pregap_frames + track.frames + track.postgap_frames;
            const u32 padded_track_frames = track.chd_pad_frames > 0
                ? stored_track_frames + track.chd_pad_frames
                : align_track_frames(stored_track_frames);
            next_frame += padded_track_frames;

            LOGI("CHD track %u mapping: start_frame=%u start_sector=%u sector_count=%u stored_frames=%u next_frame=%u",
                 static_cast<unsigned>(m_tracks.size() + 1),
                 track.start_frame, track.start_sector, track.sector_count,
                 stored_track_frames, next_frame);

              probe_track_layout(track, static_cast<u32>(m_tracks.size()));

            m_tracks.push_back(track);
        }

        if (!found_metadata) {
            TrackInfo fallback;
            fallback.frames = static_cast<u32>(header.logicalbytes / header.unitbytes);
            fallback.start_frame = 0;
            fallback.start_sector = 0;
            fallback.sector_count = fallback.frames;
            // Raw 2448-byte frames = raw 2352-byte CD sector + 96 subchannel bytes.
            // 3DO discs are MODE2 XA, so use MODE2 offset (24) when frame is raw-sized.
            if (m_frame_bytes >= 2352) {
                fallback.type = "MODE2_RAW";
                fallback.data_offset = MODE2_RAW_DATA_OFFSET;
            } else {
                fallback.type = "MODE1";
                fallback.data_offset = 0;
            }
            fallback.supported = true;
            probe_track_layout(fallback, 0);
            m_tracks.push_back(fallback);
            next_sector = fallback.sector_count;
            LOGW("CHD metadata missing for '%s'; falling back to %s sector extraction (frame_bytes=%u offset=%u)",
                m_path.c_str(), fallback.type.c_str(), m_frame_bytes, fallback.data_offset);
        }

        m_total_sectors = next_sector;
        if (m_logical_block_count > 0 && m_logical_block_count <= next_sector) {
            LOGI("CHD logical block count override: raw_sectors=%u logical_blocks=%u",
                 next_sector, m_logical_block_count);
            m_total_sectors = m_logical_block_count;
        }
    }

    const TrackInfo* find_track(u32 sector) const {
        for (const auto& track : m_tracks) {
            if (!track.supported || track.sector_count == 0) {
                continue;
            }
            if (sector >= track.start_sector && sector < track.start_sector + track.sector_count) {
                return &track;
            }
        }
        return nullptr;
    }

    bool load_hunk(u32 frame) {
        if (!m_chd || !m_hunk_buffer || m_frames_per_hunk == 0) {
            return false;
        }

        const u32 hunk_index = frame / m_frames_per_hunk;
        if (hunk_index == m_cached_hunk) {
            return true;
        }

        chd_error err = chd_read(m_chd, hunk_index, m_hunk_buffer.get());
        if (err != CHDERR_NONE) {
            LOGE("libchdr failed to read hunk %u from '%s': %s",
                hunk_index, m_path.c_str(), chd_error_string(err));
            return false;
        }

        m_cached_hunk = hunk_index;
        return true;
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_CHD_BACKEND_H
