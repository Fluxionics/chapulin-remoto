#include "codec/wic_jpeg.h"

#include <windows.h>
#include <objbase.h>
#include <ocidl.h>
#include <wincodec.h>
#include <wrl/client.h>

#include <cstdio>
#include <string>

#include "util/log.h"

using Microsoft::WRL::ComPtr;

namespace chp {
namespace {

std::string hresultText(HRESULT hr) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "0x%08lX", static_cast<unsigned long>(hr));
    return buffer;
}

}  // namespace

struct JpegEncoder::Impl {
    ComPtr<IWICImagingFactory> factory;
};

JpegEncoder::~JpegEncoder() { shutdown(); }

bool JpegEncoder::init() {
    if (impl_) return true;

    auto* impl = new (std::nothrow) Impl();
    if (!impl) return false;

    HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_PPV_ARGS(&impl->factory));
    if (FAILED(hr)) {
        LOG_ERR("No se pudo crear WIC (%s)", hresultText(hr).c_str());
        delete impl;
        return false;
    }

    impl_ = impl;
    return true;
}

void JpegEncoder::shutdown() {
    delete impl_;
    impl_ = nullptr;
}

bool JpegEncoder::encode(const std::vector<uint8_t>& bgra, int width, int height, int quality,
                         std::vector<uint8_t>& out) {
    if (!impl_ && !init()) return false;
    if (width <= 0 || height <= 0) return false;
    if (bgra.size() < static_cast<size_t>(width) * static_cast<size_t>(height) * 4u) return false;

    const UINT stride = static_cast<UINT>(width) * 4u;

    ComPtr<IWICBitmap> bitmap;
    HRESULT hr = impl_->factory->CreateBitmapFromMemory(
        static_cast<UINT>(width), static_cast<UINT>(height), GUID_WICPixelFormat32bppBGRA, stride,
        static_cast<UINT>(bgra.size()), const_cast<BYTE*>(bgra.data()), &bitmap);
    if (FAILED(hr)) {
        LOG_ERR("WIC CreateBitmapFromMemory fallo (%s)", hresultText(hr).c_str());
        return false;
    }

    ComPtr<IStream> stream;
    hr = CreateStreamOnHGlobal(nullptr, TRUE, &stream);
    if (FAILED(hr)) return false;

    ComPtr<IWICBitmapEncoder> encoder;
    hr = impl_->factory->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, &encoder);
    if (FAILED(hr)) return false;

    hr = encoder->Initialize(stream.Get(), WICBitmapEncoderNoCache);
    if (FAILED(hr)) return false;

    ComPtr<IWICBitmapFrameEncode> frame;
    ComPtr<IPropertyBag2> properties;
    hr = encoder->CreateNewFrame(&frame, &properties);
    if (FAILED(hr)) return false;

    if (properties) {
        PROPBAG2 option{};
        option.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
        VARIANT value;
        VariantInit(&value);
        value.vt = VT_R4;
        value.fltVal = static_cast<float>(quality) / 100.0f;
        properties->Write(1, &option, &value);
        VariantClear(&value);
    }

    hr = frame->Initialize(properties.Get());
    if (FAILED(hr)) return false;

    frame->SetSize(static_cast<UINT>(width), static_cast<UINT>(height));

    WICPixelFormatGUID format = GUID_WICPixelFormat24bppBGR;
    hr = frame->SetPixelFormat(&format);
    if (FAILED(hr)) return false;

    ComPtr<IWICFormatConverter> converter;
    hr = impl_->factory->CreateFormatConverter(&converter);
    if (FAILED(hr)) return false;

    hr = converter->Initialize(bitmap.Get(), format, WICBitmapDitherTypeNone, nullptr, 0.0,
                               WICBitmapPaletteTypeCustom);
    if (FAILED(hr)) return false;

    hr = frame->WriteSource(converter.Get(), nullptr);
    if (FAILED(hr)) return false;

    hr = frame->Commit();
    if (FAILED(hr)) return false;

    hr = encoder->Commit();
    if (FAILED(hr)) return false;

    STATSTG stat{};
    hr = stream->Stat(&stat, STATFLAG_NONAME);
    if (FAILED(hr)) return false;

    const size_t size = static_cast<size_t>(stat.cbSize.QuadPart);
    if (size == 0) return false;

    LARGE_INTEGER zero{};
    stream->Seek(zero, STREAM_SEEK_SET, nullptr);

    out.resize(size);
    ULONG read = 0;
    hr = stream->Read(out.data(), static_cast<ULONG>(size), &read);
    if (FAILED(hr) || read == 0) return false;

    out.resize(read);
    return true;
}

}  // namespace chp
