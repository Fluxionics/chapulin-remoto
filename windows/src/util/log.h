#pragma once

// Log a consola (cuando la hay) y a archivo: %TEMP%\chapulin-host.log.
// El host es GUI, asi que el archivo es la via de diagnostico principal.
// Sin acentos a proposito en los mensajes: mas portable.

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <string>

namespace chp {

inline FILE* logFileHandle() {
    static FILE* handle = []() -> FILE* {
        const char* temp = std::getenv("TEMP");
        std::string path = std::string(temp ? temp : ".") + "\\chapulin-host.log";
        FILE* file = nullptr;
#ifdef _MSC_VER
        // _SH_DENYNO: deja que otros procesos lean el log en caliente
        // (PowerShell, diag remoto) mientras el host lo mantiene abierto.
        file = _fsopen(path.c_str(), "a", _SH_DENYNO);
        if (file) setvbuf(file, nullptr, _IONBF, 0);
#else
        file = std::fopen(path.c_str(), "a");
#endif
        return file;
    }();
    return handle;
}

inline void logLine(const char* level, const char* fmt, ...) {
    char message[1024]{};
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);

    std::time_t now = std::time(nullptr);
    std::tm tm{};
    localtime_s(&tm, &now);

    char line[1200];
    std::snprintf(line, sizeof(line), "[%02d:%02d:%02d] %-5s %s\n", tm.tm_hour, tm.tm_min,
                  tm.tm_sec, level, message);

    std::fputs(line, stdout);
    std::fflush(stdout);

    if (FILE* file = logFileHandle()) {
        std::fputs(line, file);
        std::fflush(file);
    }
}

}  // namespace chp

#define LOG_INFO(...) ::chp::logLine("info", __VA_ARGS__)
#define LOG_WARN(...) ::chp::logLine("warn", __VA_ARGS__)
#define LOG_ERR(...)  ::chp::logLine("error", __VA_ARGS__)
