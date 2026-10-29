#include "app/config.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>

namespace chp {
namespace {

bool parseInt(const char* text, int min, int max, int& out) {
    if (text == nullptr || *text == '\0') return false;
    char* end = nullptr;
    long value = std::strtol(text, &end, 10);
    if (end == text || *end != '\0') return false;
    if (value < min || value > max) return false;
    out = static_cast<int>(value);
    return true;
}

}  // namespace

void printUsage() {
    std::printf(
        "Chapulin Remoto - host de Windows\n"
        "\n"
        "Uso: chapulin-host [opciones]\n"
        "\n"
        "  --port N       puerto TCP (1024-65535, por defecto 47474)\n"
        "  --fps N        frames por segundo (1-60, por defecto 15)\n"
        "  --width N      ancho maximo del frame (320-3840, por defecto 1280)\n"
        "  --quality N    calidad JPEG (10-95, por defecto 70)\n"
        "  --pin NNNNNN   PIN fijo (por defecto se genera uno aleatorio)\n"
        "  --help         muestra esta ayuda\n");
}

bool parseArgs(int argc, char** argv, Config& cfg, std::string& error) {
    for (int i = 1; i < argc; ++i) {
        const char* arg = argv[i];

        auto next = [&](const char* name) -> const char* {
            if (i + 1 >= argc) {
                error = std::string("falta el valor de ") + name;
                return nullptr;
            }
            return argv[++i];
        };

        if (std::strcmp(arg, "--help") == 0 || std::strcmp(arg, "-h") == 0) {
            printUsage();
            std::exit(0);
        } else if (std::strcmp(arg, "--port") == 0) {
            const char* value = next("--port");
            if (!value) return false;
            int port = 0;
            if (!parseInt(value, 1024, 65535, port)) {
                error = "puerto invalido (1024-65535)";
                return false;
            }
            cfg.port = static_cast<uint16_t>(port);
        } else if (std::strcmp(arg, "--fps") == 0) {
            const char* value = next("--fps");
            if (!value) return false;
            if (!parseInt(value, 1, 60, cfg.fps)) {
                error = "fps invalido (1-60)";
                return false;
            }
        } else if (std::strcmp(arg, "--width") == 0) {
            const char* value = next("--width");
            if (!value) return false;
            if (!parseInt(value, 320, 3840, cfg.maxWidth)) {
                error = "ancho invalido (320-3840)";
                return false;
            }
        } else if (std::strcmp(arg, "--quality") == 0) {
            const char* value = next("--quality");
            if (!value) return false;
            if (!parseInt(value, 10, 95, cfg.quality)) {
                error = "calidad invalida (10-95)";
                return false;
            }
        } else if (std::strcmp(arg, "--pin") == 0) {
            const char* value = next("--pin");
            if (!value) return false;
            // Solo digitos, 4-12 caracteres.
            size_t len = std::strlen(value);
            if (len < 4 || len > 12) {
                error = "el PIN debe tener entre 4 y 12 digitos";
                return false;
            }
            for (size_t k = 0; k < len; ++k) {
                if (value[k] < '0' || value[k] > '9') {
                    error = "el PIN solo puede contener digitos";
                    return false;
                }
            }
            cfg.pin = value;
        } else {
            error = std::string("opcion desconocida: ") + arg;
            return false;
        }
    }

    if (cfg.pin.empty()) cfg.pin = generatePin();
    return true;
}

std::string generatePin(int digits) {
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<int> dist(0, 9);

    std::string pin;
    pin.reserve(static_cast<size_t>(digits));
    for (int i = 0; i < digits; ++i) pin.push_back(static_cast<char>('0' + dist(gen)));
    return pin;
}

}  // namespace chp
