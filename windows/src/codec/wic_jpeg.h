#pragma once

#include <cstdint>
#include <vector>

namespace chp {

// Codificador JPEG sobre WIC (Windows Imaging Component).
// WIC viene con Windows: cero dependencias externas.
class JpegEncoder {
public:
    JpegEncoder() = default;
    ~JpegEncoder();

    JpegEncoder(const JpegEncoder&) = delete;
    JpegEncoder& operator=(const JpegEncoder&) = delete;

    bool init();
    void shutdown();

    // `bgra` debe ser outW * outH * 4 bytes, fila superior primero.
    bool encode(const std::vector<uint8_t>& bgra, int width, int height, int quality,
                std::vector<uint8_t>& out);

private:
    struct Impl;
    Impl* impl_ = nullptr;
};

}  // namespace chp
