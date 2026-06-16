/**
 * 3DO Opera Native Core - Audio System
 * High-performance audio output with AAudio (Android O+) and OpenSL ES fallback
 */

#ifndef FOURDO_NATIVE_AUDIO_H
#define FOURDO_NATIVE_AUDIO_H

#include "native_types.h"
#include "native_log.h"
#include <atomic>
#include <cstring>
#include <mutex>

// Conditional AAudio support (Android 8.0+)
#if __ANDROID_API__ >= 26
#include <aaudio/AAudio.h>
#define HAS_AAUDIO 1
#else
#define HAS_AAUDIO 0
#endif

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

namespace fourdo {
namespace core {

/**
 * AudioBuffer - Lock-free ring buffer for audio samples
 */
class AudioRingBuffer {
    static constexpr u32 BUFFER_SIZE = 65536;  // ~0.7s at 44100Hz stereo
    
    i16 m_buffer[BUFFER_SIZE];
    std::atomic<u32> m_write_pos{0};
    std::atomic<u32> m_read_pos{0};
    
public:
    AudioRingBuffer() {
        memset(m_buffer, 0, sizeof(m_buffer));
    }
    
    /**
     * Write samples to buffer (producer thread)
     * Returns number of samples actually written
     */
    u32 write(const i16* samples, u32 count) {
        u32 wp = m_write_pos.load(std::memory_order_relaxed);
        u32 rp = m_read_pos.load(std::memory_order_acquire);
        
        u32 available = BUFFER_SIZE - (wp - rp);
        u32 to_write = (count < available) ? count : available;
        
        for (u32 i = 0; i < to_write; i++) {
            m_buffer[wp & (BUFFER_SIZE - 1)] = samples[i];
            wp++;
        }
        
        m_write_pos.store(wp, std::memory_order_release);
        return to_write;
    }
    
    /**
     * Read samples from buffer (consumer thread)
     * Returns number of samples actually read
     */
    u32 read(i16* samples, u32 count) {
        u32 rp = m_read_pos.load(std::memory_order_relaxed);
        u32 wp = m_write_pos.load(std::memory_order_acquire);
        
        u32 available = wp - rp;
        u32 to_read = (count < available) ? count : available;
        
        for (u32 i = 0; i < to_read; i++) {
            samples[i] = m_buffer[rp & (BUFFER_SIZE - 1)];
            rp++;
        }
        
        m_read_pos.store(rp, std::memory_order_release);
        return to_read;
    }
    
    /**
     * Get number of samples available to read
     */
    u32 available() const {
        return m_write_pos.load(std::memory_order_acquire) - 
               m_read_pos.load(std::memory_order_acquire);
    }
    
    /**
     * Clear the buffer
     */
    void clear() {
        m_write_pos.store(0, std::memory_order_relaxed);
        m_read_pos.store(0, std::memory_order_relaxed);
    }
};

/**
 * AudioSystem - High-performance audio output
 */
class AudioSystem {
    static constexpr u32 SAMPLE_RATE = 44100;
    static constexpr u32 CHANNELS = 2;
    static constexpr u32 FRAMES_PER_BUFFER = 256;
    
    AudioRingBuffer m_ring_buffer;
    std::atomic<bool> m_running{false};
    
#if HAS_AAUDIO
    AAudioStream* m_aaudo_stream = nullptr;
#endif
    
    // OpenSL ES handles
    SLObjectItf m_engine_obj = nullptr;
    SLEngineItf m_engine = nullptr;
    SLObjectItf m_output_mix_obj = nullptr;
    SLObjectItf m_player_obj = nullptr;
    SLBufferQueueItf m_buffer_queue = nullptr;
    SLPlayItf m_player = nullptr;
    
    i16 m_temp_buffer[FRAMES_PER_BUFFER * CHANNELS];
    
public:
    AudioSystem() {
        memset(m_temp_buffer, 0, sizeof(m_temp_buffer));
    }
    
    ~AudioSystem() {
        shutdown();
    }
    
    /**
     * Initialize audio system
     * Tries AAudio first, falls back to OpenSL ES
     */
    bool initialize() {
#if HAS_AAUDIO
        if (try_aaudio()) {
            LOGI("Audio initialized: AAudio (low latency)");
            return true;
        }
#endif
        if (try_opensl()) {
            LOGI("Audio initialized: OpenSL ES");
            return true;
        }
        
        LOGE("Failed to initialize audio system");
        return false;
    }
    
    /**
     * Shutdown audio system
     */
    void shutdown() {
        m_running = false;
        
#if HAS_AAUDIO
        if (m_aaudo_stream) {
            AAudioStream_requestStop(m_aaudo_stream);
            AAudioStream_close(m_aaudo_stream);
            m_aaudo_stream = nullptr;
        }
#endif
        
        if (m_player_obj) {
            (*m_player_obj)->Destroy(m_player_obj);
            m_player_obj = nullptr;
        }
        if (m_output_mix_obj) {
            (*m_output_mix_obj)->Destroy(m_output_mix_obj);
            m_output_mix_obj = nullptr;
        }
        if (m_engine_obj) {
            (*m_engine_obj)->Destroy(m_engine_obj);
            m_engine_obj = nullptr;
        }
        
        LOGI("Audio system shut down");
    }
    
    /**
     * Push audio samples to the output buffer
     * Samples are interleaved stereo (L, R, L, R, ...)
     */
    void push_samples(const i16* samples, u32 frame_count) {
        if (!m_running) return;
        m_ring_buffer.write(samples, frame_count * CHANNELS);
    }
    
    /**
     * Push packed samples (left in low 16 bits, right in high 16 bits)
     * This matches the 3DO DSP output format
     */
    void push_packed_samples(const u32* packed, u32 frame_count) {
        if (!m_running) return;
        
        // Unpack and write to ring buffer
        i16 unpacked[1024 * 2];  // Temp buffer for unpacking
        u32 remaining = frame_count;
        const u32* src = packed;
        
        while (remaining > 0) {
            u32 chunk = (remaining > 1024) ? 1024 : remaining;
            
            for (u32 i = 0; i < chunk; i++) {
                u32 sample = src[i];
                unpacked[i * 2] = static_cast<i16>(sample & 0xFFFF);        // Left
                unpacked[i * 2 + 1] = static_cast<i16>((sample >> 16) & 0xFFFF);  // Right
            }
            
            m_ring_buffer.write(unpacked, chunk * 2);
            src += chunk;
            remaining -= chunk;
        }
    }
    
    /**
     * Get sample rate
     */
    static constexpr u32 sample_rate() { return SAMPLE_RATE; }
    
    /**
     * Get number of channels
     */
    static constexpr u32 channels() { return CHANNELS; }
    
private:
#if HAS_AAUDIO
    /**
     * Try to initialize AAudio (Android 8.0+)
     */
    bool try_aaudio() {
        AAudioStreamBuilder* builder;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK) return false;
        
        // Configure stream
        AAudioStreamBuilder_setSampleRate(builder, SAMPLE_RATE);
        AAudioStreamBuilder_setChannelCount(builder, CHANNELS);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setDataCallback(builder, aaudio_callback, this);
        
        // Open stream
        result = AAudioStreamBuilder_openStream(builder, &m_aaudo_stream);
        AAudioStreamBuilder_delete(builder);
        
        if (result != AAUDIO_OK) {
            LOGW("AAudio stream open failed: %d", result);
            return false;
        }
        
        // Start stream
        result = AAudioStream_requestStart(m_aaudo_stream);
        if (result != AAUDIO_OK) {
            LOGW("AAudio stream start failed: %d", result);
            AAudioStream_close(m_aaudo_stream);
            m_aaudo_stream = nullptr;
            return false;
        }
        
        m_running = true;
        return true;
    }
    
    static aaudio_data_callback_result_t aaudio_callback(
        AAudioStream* stream, void* user_data, void* audio_data, int32_t num_frames) {
        
        AudioSystem* self = static_cast<AudioSystem*>(user_data);
        i16* output = static_cast<i16*>(audio_data);
        
        u32 samples_needed = num_frames * CHANNELS;
        u32 samples_read = self->m_ring_buffer.read(output, samples_needed);
        
        // Fill remaining with silence
        if (samples_read < samples_needed) {
            memset(output + samples_read, 0, (samples_needed - samples_read) * sizeof(i16));
        }
        
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
#endif
    
    /**
     * Initialize OpenSL ES (fallback for older Android)
     */
    bool try_opensl() {
        SLresult result;
        
        // Create engine
        result = slCreateEngine(&m_engine_obj, 0, nullptr, 0, nullptr, nullptr);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_engine_obj)->Realize(m_engine_obj, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_engine_obj)->GetInterface(m_engine_obj, SL_IID_ENGINE, &m_engine);
        if (result != SL_RESULT_SUCCESS) return false;
        
        // Create output mix
        result = (*m_engine)->CreateOutputMix(m_engine, &m_output_mix_obj, 0, nullptr, nullptr);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_output_mix_obj)->Realize(m_output_mix_obj, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;
        
        // Create audio player with buffer queue
        SLDataLocator_BufferQueue buffer_queue = {
            SL_DATALOCATOR_BUFFERQUEUE, 4
        };
        
        SLDataFormat_PCM format = {
            SL_DATAFORMAT_PCM,
            CHANNELS,
            SAMPLE_RATE * 1000,  // Sample rate in mHz
            SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_PCMSAMPLEFORMAT_FIXED_16,
            (CHANNELS == 2) ? (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT) : SL_SPEAKER_FRONT_CENTER,
            SL_BYTEORDER_LITTLEENDIAN
        };
        
        SLDataSource audio_source = { &buffer_queue, &format };
        
        SLDataLocator_OutputMix output_mix = {
            SL_DATALOCATOR_OUTPUTMIX, m_output_mix_obj
        };
        SLDataSink audio_sink = { &output_mix, nullptr };
        
        SLInterfaceID ids[] = { SL_IID_BUFFERQUEUE, SL_IID_PLAY };
        SLboolean req[] = { SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE };
        
        result = (*m_engine)->CreateAudioPlayer(m_engine, &m_player_obj, 
                                                 &audio_source, &audio_sink,
                                                 2, ids, req);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_player_obj)->Realize(m_player_obj, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_player_obj)->GetInterface(m_player_obj, SL_IID_PLAY, &m_player);
        if (result != SL_RESULT_SUCCESS) return false;
        
        result = (*m_player_obj)->GetInterface(m_player_obj, SL_IID_BUFFERQUEUE, &m_buffer_queue);
        if (result != SL_RESULT_SUCCESS) return false;
        
        // Register callback
        result = (*m_buffer_queue)->RegisterCallback(m_buffer_queue, opensl_callback, this);
        if (result != SL_RESULT_SUCCESS) return false;
        
        // Enqueue initial buffers
        for (int i = 0; i < 4; i++) {
            opensl_callback(m_buffer_queue, this);
        }
        
        // Start playback
        result = (*m_player)->SetPlayState(m_player, SL_PLAYSTATE_PLAYING);
        if (result != SL_RESULT_SUCCESS) return false;
        
        m_running = true;
        return true;
    }
    
    static void opensl_callback(SLBufferQueueItf queue, void* user_data) {
        AudioSystem* self = static_cast<AudioSystem*>(user_data);
        
        // Read from ring buffer
        u32 samples_read = self->m_ring_buffer.read(
            self->m_temp_buffer, FRAMES_PER_BUFFER * CHANNELS);
        
        // Fill remaining with silence
        if (samples_read < FRAMES_PER_BUFFER * CHANNELS) {
            memset(self->m_temp_buffer + samples_read, 0, 
                   (FRAMES_PER_BUFFER * CHANNELS - samples_read) * sizeof(i16));
        }
        
        // Enqueue buffer
        (*queue)->Enqueue(queue, self->m_temp_buffer, 
                          FRAMES_PER_BUFFER * CHANNELS * sizeof(i16));
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_AUDIO_H