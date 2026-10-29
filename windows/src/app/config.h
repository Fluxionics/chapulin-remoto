#pragma once

#include <cstdint>
#include <string>

namespace chp {

struct Config {
    uint16_t port = 47474;
    int fps = 15;          // frames por segundo objetivo
    int maxWidth = 1280;   // ancho maximo del frame enviado
    int quality = 70;      // calidad JPEG 1..95
    std::string pin;       // PIN de 6 digitos
};

// Lee argumentos de linea de comandos. Devuelve false si hay un error de uso.
bool parseArgs(int argc, char** argv, Config& cfg, std::string& error);

void printUsage();

// Genera un PIN numerico de `digits` digitos.
std::string generatePin(int digits = 6);

}  // namespace chp
