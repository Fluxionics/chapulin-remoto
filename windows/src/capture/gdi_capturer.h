#pragma once

#include <windows.h>

#include <cstdint>
#include <vector>

namespace chp {

// Captura del monitor primario con GDI (BitBlt) y escalado opcional.
// Es la via del MVP: sencilla y sin dependencias. En la fase 3 se sustituye
// por Desktop Duplication (DXGI).
class GdiCapturer {
public:
    GdiCapturer() = default;
    ~GdiCapturer();

    GdiCapturer(const GdiCapturer&) = delete;
    GdiCapturer& operator=(const GdiCapturer&) = delete;

    // Ancho y alto del escritorio primario en pixeles fisicos.
    int sourceWidth() const { return srcWidth_; }
    int sourceHeight() const { return srcHeight_; }

    // Refresca las dimensiones del escritorio (por si cambia la resolucion).
    void refreshMetrics();

    // Captura el escritorio y lo deja en `out` como BGRA de outW x outH,
    // fila superior primero. Devuelve false si falla.
    bool grab(std::vector<uint8_t>& out, int outW, int outH);

private:
    void release();

    int srcWidth_ = 0;
    int srcHeight_ = 0;

    HDC srcDc_ = nullptr;
    HDC dstDc_ = nullptr;
    HBITMAP srcBitmap_ = nullptr;
    HBITMAP dstBitmap_ = nullptr;
    HGDIOBJ oldSrc_ = nullptr;
    HGDIOBJ oldDst_ = nullptr;
    void* dstBits_ = nullptr;
    int cachedWidth_ = 0;
    int cachedHeight_ = 0;
    int cachedSourceWidth_ = 0;
    int cachedSourceHeight_ = 0;
};

}  // namespace chp
