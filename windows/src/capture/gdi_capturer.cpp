#include "capture/gdi_capturer.h"

#include <windows.h>

#include "util/log.h"

namespace chp {
namespace {

struct SourceDc {
    HDC dc = nullptr;
    ~SourceDc() {
        if (dc) ReleaseDC(nullptr, dc);
    }
};

}  // namespace

GdiCapturer::~GdiCapturer() { release(); }

void GdiCapturer::release() {
    if (srcDc_) {
        if (oldSrc_) SelectObject(srcDc_, oldSrc_);
        if (srcBitmap_) DeleteObject(srcBitmap_);
        DeleteDC(srcDc_);
    }
    if (dstDc_) {
        if (oldDst_) SelectObject(dstDc_, oldDst_);
        if (dstBitmap_) DeleteObject(dstBitmap_);
        DeleteDC(dstDc_);
    }
    srcDc_ = nullptr;
    dstDc_ = nullptr;
    srcBitmap_ = nullptr;
    dstBitmap_ = nullptr;
    oldSrc_ = nullptr;
    oldDst_ = nullptr;
    dstBits_ = nullptr;
    cachedWidth_ = 0;
    cachedHeight_ = 0;
    cachedSourceWidth_ = 0;
    cachedSourceHeight_ = 0;
}

void GdiCapturer::refreshMetrics() {
    srcWidth_ = GetSystemMetrics(SM_CXSCREEN);
    srcHeight_ = GetSystemMetrics(SM_CYSCREEN);
    if (srcWidth_ <= 0 || srcHeight_ <= 0) {
        srcWidth_ = 1920;
        srcHeight_ = 1080;
    }
}

bool GdiCapturer::grab(std::vector<uint8_t>& out, int outW, int outH) {
    if (outW <= 0 || outH <= 0) return false;

    refreshMetrics();

    // Si cambio la resolucion del escritorio, el bitmap de origen ya no vale.
    if (srcDc_ && (srcWidth_ != cachedSourceWidth_ || srcHeight_ != cachedSourceHeight_)) {
        SelectObject(srcDc_, oldSrc_);
        DeleteObject(srcBitmap_);
        DeleteDC(srcDc_);
        srcDc_ = nullptr;
        srcBitmap_ = nullptr;
        oldSrc_ = nullptr;
    }

    if (!srcDc_) {
        SourceDc screen;
        screen.dc = GetDC(nullptr);
        if (!screen.dc) {
            LOG_ERR("GetDC(NULL) fallo");
            return false;
        }

        srcDc_ = CreateCompatibleDC(screen.dc);
        if (!srcDc_) {
            LOG_ERR("CreateCompatibleDC fallo");
            return false;
        }
        srcBitmap_ = CreateCompatibleBitmap(screen.dc, srcWidth_, srcHeight_);
        if (!srcBitmap_) {
            LOG_ERR("CreateCompatibleBitmap fallo");
            release();
            return false;
        }
        oldSrc_ = SelectObject(srcDc_, srcBitmap_);
        cachedSourceWidth_ = srcWidth_;
        cachedSourceHeight_ = srcHeight_;
    }

    if (!dstDc_ || cachedWidth_ != outW || cachedHeight_ != outH) {
        if (dstDc_) {
            if (oldDst_) SelectObject(dstDc_, oldDst_);
            if (dstBitmap_) DeleteObject(dstBitmap_);
            DeleteDC(dstDc_);
            dstDc_ = nullptr;
            dstBitmap_ = nullptr;
            oldDst_ = nullptr;
            dstBits_ = nullptr;
        }

        SourceDc screen;
        screen.dc = GetDC(nullptr);
        if (!screen.dc) return false;

        dstDc_ = CreateCompatibleDC(screen.dc);
        if (!dstDc_) return false;

        BITMAPINFO info{};
        info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        info.bmiHeader.biWidth = outW;
        info.bmiHeader.biHeight = -outH;  // negativo = fila superior primero
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;

        dstBitmap_ = CreateDIBSection(dstDc_, &info, DIB_RGB_COLORS, &dstBits_, nullptr, 0);
        if (!dstBitmap_ || !dstBits_) {
            release();
            return false;
        }
        oldDst_ = SelectObject(dstDc_, dstBitmap_);
        cachedWidth_ = outW;
        cachedHeight_ = outH;
    }

    SourceDc screen;
    screen.dc = GetDC(nullptr);
    if (!screen.dc) return false;

    if (!BitBlt(srcDc_, 0, 0, srcWidth_, srcHeight_, screen.dc, 0, 0, SRCCOPY | CAPTUREBLT)) {
        LOG_ERR("BitBlt fallo (%lu)", GetLastError());
        return false;
    }

    if (outW == srcWidth_ && outH == srcHeight_) {
        // Copia directa para no perder calidad cuando no hace falta escalar.
        if (!BitBlt(dstDc_, 0, 0, outW, outH, srcDc_, 0, 0, SRCCOPY)) {
            return false;
        }
    } else {
        SetStretchBltMode(dstDc_, HALFTONE);
        SetBrushOrgEx(dstDc_, 0, 0, nullptr);
        if (!StretchBlt(dstDc_, 0, 0, outW, outH, srcDc_, 0, 0, srcWidth_, srcHeight_, SRCCOPY)) {
            LOG_ERR("StretchBlt fallo (%lu)", GetLastError());
            return false;
        }
    }

    const size_t bytes = static_cast<size_t>(outW) * static_cast<size_t>(outH) * 4u;
    const auto* pixels = static_cast<const uint8_t*>(dstBits_);
    out.assign(pixels, pixels + bytes);
    return true;
}

}  // namespace chp
