#include "ui/phone_viewer.h"

#include <winsock2.h>
#include <windows.h>
#include <windowsx.h>
#include <objbase.h>
#include <gdiplus.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "resource.h"

namespace chp {
namespace ui {

namespace {

HWND g_viewerWnd = nullptr;
std::shared_ptr<client::PhoneClient> g_client;
std::string g_currentPhoneIp;
ULONG_PTR g_gdiToken = 0;
bool g_gdiInitialized = false;

std::mutex g_frameMutex;
Gdiplus::Bitmap* g_phoneBitmap = nullptr;
uint16_t g_frameW = 0;
uint16_t g_frameH = 0;
RECT g_screenRect{};
bool g_isMouseDown = false;
int g_hoverButton = -1;

constexpr int kToolbarHeight = 48;

struct NavButton {
    RECT rect;
    std::wstring label;
    uint16_t keyCode;
};

std::vector<NavButton> getButtons(int clientW, int clientH) {
    std::vector<NavButton> buttons;
    int barTop = clientH - kToolbarHeight;
    int btnW = 86;
    int btnH = 34;
    int btnY = barTop + (kToolbarHeight - btnH) / 2;
    int totalW = 4 * btnW + 3 * 10;
    int startX = (clientW - totalW) / 2;

    buttons.push_back({ { startX, btnY, startX + btnW, btnY + btnH }, L"◁ Atrás", 4 });
    buttons.push_back({ { startX + (btnW + 10), btnY, startX + (btnW + 10) + btnW, btnY + btnH }, L"○ Inicio", 3 });
    buttons.push_back({ { startX + 2 * (btnW + 10), btnY, startX + 2 * (btnW + 10) + btnW, btnY + btnH }, L"▢ Tareas", 187 });
    buttons.push_back({ { startX + 3 * (btnW + 10), btnY, startX + 3 * (btnW + 10) + btnW, btnY + btnH }, L"⏻ Power", 26 });

    return buttons;
}

std::wstring toWString(const std::string& str) {
    if (str.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, nullptr, 0);
    if (len <= 0) return L"";
    std::wstring out(static_cast<size_t>(len - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, out.data(), len);
    return out;
}

void triggerKey(uint16_t code) {
    if (!g_client) return;
    proto::InputEvent ev{};
    ev.kind = static_cast<uint8_t>(proto::InputKind::KeyDown);
    ev.key = code;
    g_client->sendInput(ev);

    ev.kind = static_cast<uint8_t>(proto::InputKind::KeyUp);
    g_client->sendInput(ev);
}

LRESULT CALLBACK ViewerWndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_ERASEBKGND:
            return 1;

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hWnd, &ps);
            RECT rc{};
            GetClientRect(hWnd, &rc);
            int width = rc.right - rc.left;
            int height = rc.bottom - rc.top;

            HDC memDC = CreateCompatibleDC(hdc);
            HBITMAP memBmp = CreateCompatibleBitmap(hdc, width, height);
            HGDIOBJ oldBmp = SelectObject(memDC, memBmp);

            Gdiplus::Graphics g(memDC);
            g.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
            g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBilinear);

            Gdiplus::SolidBrush bgBrush(Gdiplus::Color(255, 20, 26, 32));
            g.FillRectangle(&bgBrush, 0, 0, width, height);

            int canvasH = height - kToolbarHeight;
            if (canvasH < 0) canvasH = 0;

            {
                std::lock_guard<std::mutex> lock(g_frameMutex);
                if (g_phoneBitmap && canvasH > 0 && width > 0) {
                    int bmpW = g_frameW > 0 ? g_frameW : static_cast<int>(g_phoneBitmap->GetWidth());
                    int bmpH = g_frameH > 0 ? g_frameH : static_cast<int>(g_phoneBitmap->GetHeight());
                    if (bmpW <= 0) bmpW = 1080;
                    if (bmpH <= 0) bmpH = 2400;

                    double scaleW = static_cast<double>(width) / bmpW;
                    double scaleH = static_cast<double>(canvasH) / bmpH;
                    double scale = (scaleW < scaleH) ? scaleW : scaleH;

                    int destW = static_cast<int>(bmpW * scale);
                    int destH = static_cast<int>(bmpH * scale);
                    int destX = (width - destW) / 2;
                    int destY = (canvasH - destH) / 2;

                    g_screenRect.left = destX;
                    g_screenRect.top = destY;
                    g_screenRect.right = destX + destW;
                    g_screenRect.bottom = destY + destH;

                    g.DrawImage(g_phoneBitmap, destX, destY, destW, destH);
                } else if (canvasH > 0) {
                    g_screenRect = { 0, 0, 0, 0 };
                    Gdiplus::Font font(L"Segoe UI", 12, Gdiplus::FontStyleRegular, Gdiplus::UnitPoint);
                    Gdiplus::SolidBrush textBrush(Gdiplus::Color(255, 156, 204, 101));
                    Gdiplus::StringFormat sf;
                    sf.SetAlignment(Gdiplus::StringAlignmentCenter);
                    sf.SetLineAlignment(Gdiplus::StringAlignmentCenter);

                    std::wstring statusText = L"Conectado a " + toWString(g_currentPhoneIp) + L"\nEsperando transmisión de pantalla...";
                    Gdiplus::RectF layoutRect(0, 0, static_cast<Gdiplus::REAL>(width), static_cast<Gdiplus::REAL>(canvasH));
                    g.DrawString(statusText.c_str(), -1, &font, layoutRect, &sf, &textBrush);
                }
            }

            Gdiplus::SolidBrush barBrush(Gdiplus::Color(255, 28, 36, 44));
            g.FillRectangle(&barBrush, 0, canvasH, width, kToolbarHeight);

            Gdiplus::Pen borderPen(Gdiplus::Color(255, 45, 58, 70), 1.0f);
            g.DrawLine(&borderPen, 0, canvasH, width, canvasH);

            auto buttons = getButtons(width, height);
            Gdiplus::Font btnFont(L"Segoe UI", 10, Gdiplus::FontStyleBold, Gdiplus::UnitPoint);
            Gdiplus::StringFormat btnSf;
            btnSf.SetAlignment(Gdiplus::StringAlignmentCenter);
            btnSf.SetLineAlignment(Gdiplus::StringAlignmentCenter);

            for (size_t i = 0; i < buttons.size(); ++i) {
                const auto& b = buttons[i];
                bool hovered = (static_cast<int>(i) == g_hoverButton);

                Gdiplus::Color fillCol = hovered ? Gdiplus::Color(255, 45, 58, 70) : Gdiplus::Color(255, 34, 44, 54);
                Gdiplus::SolidBrush bBrush(fillCol);

                Gdiplus::RectF bRect(
                    static_cast<Gdiplus::REAL>(b.rect.left),
                    static_cast<Gdiplus::REAL>(b.rect.top),
                    static_cast<Gdiplus::REAL>(b.rect.right - b.rect.left),
                    static_cast<Gdiplus::REAL>(b.rect.bottom - b.rect.top)
                );

                Gdiplus::GraphicsPath path;
                Gdiplus::REAL radius = 6.0f;
                path.AddArc(bRect.X, bRect.Y, radius * 2, radius * 2, 180, 90);
                path.AddArc(bRect.GetRight() - radius * 2, bRect.Y, radius * 2, radius * 2, 270, 90);
                path.AddArc(bRect.GetRight() - radius * 2, bRect.GetBottom() - radius * 2, radius * 2, radius * 2, 0, 90);
                path.AddArc(bRect.X, bRect.GetBottom() - radius * 2, radius * 2, radius * 2, 90, 90);
                path.CloseFigure();

                g.FillPath(&bBrush, &path);

                Gdiplus::Pen bPen(hovered ? Gdiplus::Color(255, 124, 179, 66) : Gdiplus::Color(255, 60, 75, 90), 1.0f);
                g.DrawPath(&bPen, &path);

                Gdiplus::SolidBrush txtBrush(hovered ? Gdiplus::Color(255, 255, 255, 255) : Gdiplus::Color(255, 207, 216, 220));
                g.DrawString(b.label.c_str(), -1, &btnFont, bRect, &btnSf, &txtBrush);
            }

            BitBlt(hdc, 0, 0, width, height, memDC, 0, 0, SRCCOPY);
            SelectObject(memDC, oldBmp);
            DeleteObject(memBmp);
            DeleteDC(memDC);

            EndPaint(hWnd, &ps);
            return 0;
        }

        case WM_MOUSEMOVE: {
            int x = GET_X_LPARAM(lParam);
            int y = GET_Y_LPARAM(lParam);

            RECT rc{};
            GetClientRect(hWnd, &rc);
            auto buttons = getButtons(rc.right - rc.left, rc.bottom - rc.top);
            int newHover = -1;
            for (size_t i = 0; i < buttons.size(); ++i) {
                if (PtInRect(&buttons[i].rect, { x, y })) {
                    newHover = static_cast<int>(i);
                    break;
                }
            }
            if (newHover != g_hoverButton) {
                g_hoverButton = newHover;
                InvalidateRect(hWnd, nullptr, FALSE);
            }

            TRACKMOUSEEVENT tme{ sizeof(TRACKMOUSEEVENT), TME_LEAVE, hWnd, 0 };
            TrackMouseEvent(&tme);

            if (g_isMouseDown && g_client) {
                int w = g_screenRect.right - g_screenRect.left;
                int h = g_screenRect.bottom - g_screenRect.top;
                if (w > 0 && h > 0) {
                    uint16_t normX = static_cast<uint16_t>(std::clamp((x - g_screenRect.left) * 65535LL / w, 0LL, 65535LL));
                    uint16_t normY = static_cast<uint16_t>(std::clamp((y - g_screenRect.top) * 65535LL / h, 0LL, 65535LL));

                    proto::InputEvent ev{};
                    ev.kind = static_cast<uint8_t>(proto::InputKind::Move);
                    ev.button = 0;
                    ev.x = normX;
                    ev.y = normY;
                    g_client->sendInput(ev);
                }
            }
            return 0;
        }

        case WM_MOUSELEAVE:
            if (g_hoverButton != -1) {
                g_hoverButton = -1;
                InvalidateRect(hWnd, nullptr, FALSE);
            }
            return 0;

        case WM_LBUTTONDOWN: {
            SetFocus(hWnd);
            int x = GET_X_LPARAM(lParam);
            int y = GET_Y_LPARAM(lParam);

            RECT rc{};
            GetClientRect(hWnd, &rc);
            auto buttons = getButtons(rc.right - rc.left, rc.bottom - rc.top);
            for (const auto& b : buttons) {
                if (PtInRect(&b.rect, { x, y })) {
                    triggerKey(b.keyCode);
                    return 0;
                }
            }

            if (PtInRect(&g_screenRect, { x, y }) && g_client) {
                int w = g_screenRect.right - g_screenRect.left;
                int h = g_screenRect.bottom - g_screenRect.top;
                if (w > 0 && h > 0) {
                    uint16_t normX = static_cast<uint16_t>(std::clamp((x - g_screenRect.left) * 65535LL / w, 0LL, 65535LL));
                    uint16_t normY = static_cast<uint16_t>(std::clamp((y - g_screenRect.top) * 65535LL / h, 0LL, 65535LL));

                    g_isMouseDown = true;
                    SetCapture(hWnd);

                    proto::InputEvent ev{};
                    ev.kind = static_cast<uint8_t>(proto::InputKind::ButtonDown);
                    ev.button = 0;
                    ev.x = normX;
                    ev.y = normY;
                    g_client->sendInput(ev);
                }
            }
            return 0;
        }

        case WM_LBUTTONUP: {
            if (g_isMouseDown) {
                g_isMouseDown = false;
                ReleaseCapture();

                if (g_client) {
                    int x = GET_X_LPARAM(lParam);
                    int y = GET_Y_LPARAM(lParam);
                    int w = g_screenRect.right - g_screenRect.left;
                    int h = g_screenRect.bottom - g_screenRect.top;
                    if (w > 0 && h > 0) {
                        uint16_t normX = static_cast<uint16_t>(std::clamp((x - g_screenRect.left) * 65535LL / w, 0LL, 65535LL));
                        uint16_t normY = static_cast<uint16_t>(std::clamp((y - g_screenRect.top) * 65535LL / h, 0LL, 65535LL));

                        proto::InputEvent ev{};
                        ev.kind = static_cast<uint8_t>(proto::InputKind::ButtonUp);
                        ev.button = 0;
                        ev.x = normX;
                        ev.y = normY;
                        g_client->sendInput(ev);
                    }
                }
            }
            return 0;
        }

        case WM_RBUTTONUP: {
            triggerKey(4);
            return 0;
        }

        case WM_MOUSEWHEEL: {
            if (g_client) {
                short delta = GET_WHEEL_DELTA_WPARAM(wParam);
                proto::InputEvent ev{};
                ev.kind = static_cast<uint8_t>(proto::InputKind::Wheel);
                ev.delta = delta;
                g_client->sendInput(ev);
            }
            return 0;
        }

        case WM_CHAR: {
            WCHAR ch = static_cast<WCHAR>(wParam);
            if (ch >= 32 && g_client) {
                proto::InputEvent ev{};
                ev.kind = static_cast<uint8_t>(proto::InputKind::Char);
                ev.key = static_cast<uint16_t>(ch);
                g_client->sendInput(ev);
            }
            return 0;
        }

        case WM_KEYDOWN: {
            UINT vk = static_cast<UINT>(wParam);
            uint16_t key = 0;
            if (vk == VK_BACK) key = 67;
            else if (vk == VK_RETURN) key = 66;
            else if (vk == VK_ESCAPE) key = 4;
            else if (vk == VK_TAB) key = 61;
            else if (vk == VK_SPACE) key = 62;
            else if (vk == VK_LEFT) key = 21;
            else if (vk == VK_RIGHT) key = 22;
            else if (vk == VK_UP) key = 19;
            else if (vk == VK_DOWN) key = 20;
            else if (vk == VK_DELETE) key = 112;

            if (key != 0 && g_client) {
                proto::InputEvent ev{};
                ev.kind = static_cast<uint8_t>(proto::InputKind::KeyDown);
                ev.key = key;
                g_client->sendInput(ev);
            }
            return 0;
        }

        case WM_KEYUP: {
            UINT vk = static_cast<UINT>(wParam);
            uint16_t key = 0;
            if (vk == VK_BACK) key = 67;
            else if (vk == VK_RETURN) key = 66;
            else if (vk == VK_ESCAPE) key = 4;
            else if (vk == VK_TAB) key = 61;
            else if (vk == VK_SPACE) key = 62;
            else if (vk == VK_LEFT) key = 21;
            else if (vk == VK_RIGHT) key = 22;
            else if (vk == VK_UP) key = 19;
            else if (vk == VK_DOWN) key = 20;
            else if (vk == VK_DELETE) key = 112;

            if (key != 0 && g_client) {
                proto::InputEvent ev{};
                ev.kind = static_cast<uint8_t>(proto::InputKind::KeyUp);
                ev.key = key;
                g_client->sendInput(ev);
            }
            return 0;
        }

        case WM_CLOSE:
            closePhoneViewer();
            return 0;

        case WM_DESTROY:
            g_viewerWnd = nullptr;
            return 0;

        default:
            return DefWindowProcW(hWnd, msg, wParam, lParam);
    }
}

void onFrameReceived(const std::vector<uint8_t>& jpeg, uint16_t w, uint16_t h) {
    if (jpeg.empty()) return;

    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, jpeg.size());
    if (!hMem) return;

    void* ptr = GlobalLock(hMem);
    if (!ptr) {
        GlobalFree(hMem);
        return;
    }
    std::memcpy(ptr, jpeg.data(), jpeg.size());
    GlobalUnlock(hMem);

    IStream* stream = nullptr;
    if (FAILED(CreateStreamOnHGlobal(hMem, FALSE, &stream))) {
        GlobalFree(hMem);
        return;
    }

    auto* bmp = Gdiplus::Bitmap::FromStream(stream);
    if (bmp && bmp->GetLastStatus() == Gdiplus::Ok) {
        int bmpW = static_cast<int>(bmp->GetWidth());
        int bmpH = static_cast<int>(bmp->GetHeight());
        if (bmpW > 0 && bmpH > 0) {
            Gdiplus::Bitmap* clone = bmp->Clone(0, 0, bmpW, bmpH, PixelFormat32bppARGB);
            delete bmp;
            stream->Release();
            GlobalFree(hMem);

            if (clone && clone->GetLastStatus() == Gdiplus::Ok) {
                std::lock_guard<std::mutex> lock(g_frameMutex);
                delete g_phoneBitmap;
                g_phoneBitmap = clone;
                g_frameW = w;
                g_frameH = h;
                if (g_viewerWnd) {
                    InvalidateRect(g_viewerWnd, nullptr, FALSE);
                }
            } else {
                delete clone;
            }
            return;
        }
    }

    delete bmp;
    stream->Release();
    GlobalFree(hMem);
}

}

void openPhoneViewer(HINSTANCE hInst, std::shared_ptr<client::PhoneClient> client, const std::string& phoneIp) {
    if (!g_gdiInitialized) {
        Gdiplus::GdiplusStartupInput gsi;
        Gdiplus::GdiplusStartup(&g_gdiToken, &gsi, nullptr);
        g_gdiInitialized = true;
    }

    g_client = client;
    g_currentPhoneIp = phoneIp;

    if (g_client) {
        g_client->setFrameCallback([](const std::vector<uint8_t>& jpeg, uint16_t w, uint16_t h) {
            onFrameReceived(jpeg, w, h);
        });
        g_client->setDisconnectCallback([](const std::string&) {
            if (g_viewerWnd) {
                PostMessageW(g_viewerWnd, WM_CLOSE, 0, 0);
            }
        });
    }

    if (g_viewerWnd) {
        ShowWindow(g_viewerWnd, SW_SHOW);
        SetForegroundWindow(g_viewerWnd);
        return;
    }

    const wchar_t* kClassName = L"ChapulinPhoneViewerClass";
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(WNDCLASSEXW);
    wc.style = CS_HREDRAW | CS_VREDRAW | CS_DBLCLKS;
    wc.lpfnWndProc = ViewerWndProc;
    wc.hInstance = hInst;
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(IDI_ICON1));
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = kClassName;
    RegisterClassExW(&wc);

    int winW = 440;
    int winH = 860;
    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int winX = (screenW - winW) / 2;
    int winY = (screenH - winH) / 2;

    std::wstring title = L"Chapulín Remoto - Celular (" + toWString(phoneIp) + L")";

    g_viewerWnd = CreateWindowExW(
        0,
        kClassName,
        title.c_str(),
        WS_OVERLAPPEDWINDOW,
        winX, winY, winW, winH,
        nullptr,
        nullptr,
        hInst,
        nullptr
    );

    if (g_viewerWnd) {
        HICON icon = LoadIconW(hInst, MAKEINTRESOURCEW(IDI_ICON1));
        if (icon) {
            SendMessageW(g_viewerWnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(icon));
            SendMessageW(g_viewerWnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));
        }
        ShowWindow(g_viewerWnd, SW_SHOW);
        UpdateWindow(g_viewerWnd);
        SetForegroundWindow(g_viewerWnd);
    }
}

void closePhoneViewer() {
    if (g_client) {
        g_client->disconnect();
        g_client.reset();
    }
    if (g_viewerWnd) {
        DestroyWindow(g_viewerWnd);
        g_viewerWnd = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(g_frameMutex);
        delete g_phoneBitmap;
        g_phoneBitmap = nullptr;
    }
}

}
}
