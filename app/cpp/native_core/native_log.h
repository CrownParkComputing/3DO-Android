/**
 * 3DO Opera Native Core - Logging System
 * Android logcat integration with log levels
 */

#ifndef FOURDO_NATIVE_LOG_H
#define FOURDO_NATIVE_LOG_H

#include <android/log.h>
#include <cstdio>
#include <cstring>

namespace fourdo {
namespace core {

// Log levels matching Android logcat
enum class LogLevel {
    Verbose = ANDROID_LOG_VERBOSE,
    Debug = ANDROID_LOG_DEBUG,
    Info = ANDROID_LOG_INFO,
    Warning = ANDROID_LOG_WARN,
    Error = ANDROID_LOG_ERROR,
    Fatal = ANDROID_LOG_FATAL
};

class Logger {
public:
    static constexpr const char* TAG = "3DOOpera-Native";
    
    static void verbose(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_VERBOSE, TAG, format, args);
        va_end(args);
    }
    
    static void debug(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_DEBUG, TAG, format, args);
        va_end(args);
    }
    
    static void info(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_INFO, TAG, format, args);
        va_end(args);
    }
    
    static void warn(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_WARN, TAG, format, args);
        va_end(args);
    }
    
    static void error(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_ERROR, TAG, format, args);
        va_end(args);
    }
    
    static void fatal(const char* format, ...) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(ANDROID_LOG_FATAL, TAG, format, args);
        va_end(args);
    }
    
    // Log hexdump for debugging
    static void hexdump(const void* data, size_t size, const char* label = nullptr) {
        const u8* bytes = static_cast<const u8*>(data);
        char line[128];
        int pos = 0;
        
        if (label) {
            debug("=== %s (%zu bytes) ===", label, size);
        }
        
        for (size_t i = 0; i < size; i += 16) {
            pos = 0;
            pos += sprintf(line + pos, "%04zX: ", i);
            
            // Hex bytes
            for (size_t j = 0; j < 16 && (i + j) < size; j++) {
                pos += sprintf(line + pos, "%02X ", bytes[i + j]);
            }
            
            // ASCII representation
            pos += sprintf(line + pos, " |");
            for (size_t j = 0; j < 16 && (i + j) < size; j++) {
                u8 c = bytes[i + j];
                line[pos++] = (c >= 32 && c < 127) ? c : '.';
            }
            pos += sprintf(line + pos, "|");
            
            debug("%s", line);
        }
    }
};

// Convenience macros
#define LOGV(...) fourdo::core::Logger::verbose(__VA_ARGS__)
#define LOGD(...) fourdo::core::Logger::debug(__VA_ARGS__)
#define LOGI(...) fourdo::core::Logger::info(__VA_ARGS__)
#define LOGW(...) fourdo::core::Logger::warn(__VA_ARGS__)
#define LOGE(...) fourdo::core::Logger::error(__VA_ARGS__)
#define LOGF(...) fourdo::core::Logger::fatal(__VA_ARGS__)

// Scoped performance timer
class ScopedTimer {
    const char* m_label;
    timespec m_start;
    
public:
    ScopedTimer(const char* label) : m_label(label) {
        clock_gettime(CLOCK_MONOTONIC, &m_start);
    }
    
    ~ScopedTimer() {
        timespec end;
        clock_gettime(CLOCK_MONOTONIC, &end);
        long long us = (end.tv_sec - m_start.tv_sec) * 1000000LL +
                       (end.tv_nsec - m_start.tv_nsec) / 1000LL;
        LOGD("[PERF] %s: %lld us", m_label, us);
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_LOG_H