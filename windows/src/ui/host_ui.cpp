#include "ui/host_ui.h"

#include <objbase.h>
#include <windowsx.h>

#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "resource.h"

namespace chp {
namespace ui {
namespace {

// Paleta: verde Chapulin #7CB342 sobre azul marino #293B51.
constexpr COLORREF kNavy = RGB(0x29, 0x3B, 0x51);
constexpr COLORREF kGreen = RGB(0x7C, 0xB3, 0x42);
constexpr COLORREF kBg = RGB(0xF4, 0xF6, 0xF8);
constexpr COLORREF kText = RGB(0x1F, 0x29, 0x33);
constexpr COLORREF kMuted = RGB(0x5A, 0x6B, 0x7B);
constexpr UINT kMsgRefresh = WM_APP + 1;
constexpr int kHeaderHeight = 48;

HINSTANCE g_hInst = nullptr;
HWND g_hWnd = nullptr;
std::string g_pin;
uint16_t g_port = 47474;
std::vector<std::string> g_ips;
std::string g_status = "Esperando conexiones...";
std::string g_client = "Ningun cliente conectado";
bool g_active = false;
std::function<void()> g_onExit;
std::mutex g_lock;

HBRUSH g_bgBrush = nullptr;
HBRUSH g_navyBrush = nullptr;
HFONT g_titleFont = nullptr;
HFONT g_subFont = nullptr;

// UTF-8 -> UTF-16 para que "Chapulín" se vea bien (no "ChapulÃ­n").
std::wstring widen(const std::string& utf8) {
    if (utf8.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), -1, nullptr, 0);
    if (len <= 0) return L"";
    std::wstring out(static_cast<size_t>(len - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), -1, out.data(), len);
    return out;
}

void setTextW(HWND parent, int id, const std::string& utf8) {
    HWND control = GetDlgItem(parent, id);
    if (control) SetWindowTextW(control, widen(utf8).c_str());
}

std::string joinedIps() {
    std::lock_guard<std::mutex> lock(g_lock);
    std::string joined;
    for (size_t i = 0; i < g_ips.size(); ++i) {
        if (i > 0) joined += "   |   ";
        joined += g_ips[i];
    }
    if (joined.empty()) joined = "127.0.0.1";
    return joined;
}

void copyToClipboard(HWND owner, const std::string& text) {
    const std::wstring wide = widen(text);
    if (!OpenClipboard(owner)) return;
    EmptyClipboard();
    const size_t bytes = (wide.size() + 1) * sizeof(wchar_t);
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (memory) {
        void* target = GlobalLock(memory);
        if (target) {
            std::memcpy(target, wide.c_str(), bytes);
            GlobalUnlock(memory);
            SetClipboardData(CF_UNICODETEXT, memory);
        }
    }
    CloseClipboard();
}

void refreshValues(HWND hWnd) {
    setTextW(hWnd, IDC_IP_LIST, joinedIps());
    setTextW(hWnd, IDC_PORT, std::to_string(g_port));
    setTextW(hWnd, IDC_PIN_VAL, g_pin);
    std::string status;
    std::string client;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        status = g_status;
        client = g_client;
    }
    setTextW(hWnd, IDC_STATUS, status);
    setTextW(hWnd, IDC_CLIENT, client);
    if (HWND statusWnd = GetDlgItem(hWnd, IDC_STATUS)) InvalidateRect(statusWnd, nullptr, TRUE);
    if (HWND clientWnd = GetDlgItem(hWnd, IDC_CLIENT)) InvalidateRect(clientWnd, nullptr, TRUE);
}

void requestExit() {
    std::function<void()> handler;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        handler = g_onExit;
    }
    if (handler) {
        handler();
    } else if (g_hWnd) {
        DestroyWindow(g_hWnd);
    }
}

INT_PTR CALLBACK DialogProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_INITDIALOG: {
            g_hWnd = hWnd;
            HICON icon = LoadIconW(g_hInst, MAKEINTRESOURCEW(IDI_ICON1));
            if (icon) {
                SendMessageW(hWnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(icon));
                SendMessageW(hWnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));
            }
            refreshValues(hWnd);

            RECT rc{};
            GetWindowRect(hWnd, &rc);
            const int width = rc.right - rc.left;
            const int height = rc.bottom - rc.top;
            const int screenX = GetSystemMetrics(SM_CXSCREEN);
            const int screenY = GetSystemMetrics(SM_CYSCREEN);
            SetWindowPos(hWnd, nullptr, (screenX - width) / 2, (screenY - height) / 2, 0, 0,
                         SWP_NOSIZE | SWP_NOZORDER);
            return TRUE;
        }

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hWnd, &ps);
            RECT rc{};
            GetClientRect(hWnd, &rc);

            // Fondo claro + cabecera azul marino con logo y titulo.
            FillRect(hdc, &rc, g_bgBrush);
            RECT band{0, 0, rc.right, kHeaderHeight};
            FillRect(hdc, &band, g_navyBrush);

            HICON icon = reinterpret_cast<HICON>(SendMessageW(hWnd, WM_GETICON, ICON_BIG, 0));
            if (icon) DrawIconEx(hdc, 14, 9, icon, 30, 30, 0, nullptr, DI_NORMAL);

            SetBkMode(hdc, TRANSPARENT);
            HFONT oldFont = static_cast<HFONT>(SelectObject(hdc, g_titleFont));
            SetTextColor(hdc, RGB(255, 255, 255));
            const std::wstring title = L"Chapul\x00EDn Remoto";
            TextOutW(hdc, 54, 10, title.c_str(), static_cast<int>(title.size()));

            SelectObject(hdc, g_subFont);
            SetTextColor(hdc, RGB(0xC8, 0xD6, 0xE5));
            const std::wstring subtitle = L"Control remoto de escritorio por red local";
            TextOutW(hdc, 54, 30, subtitle.c_str(), static_cast<int>(subtitle.size()));
            SelectObject(hdc, oldFont);

            EndPaint(hWnd, &ps);
            return 0;
        }

        case WM_ERASEBKGND: {
            HDC hdc = reinterpret_cast<HDC>(wParam);
            RECT rc{};
            GetClientRect(hWnd, &rc);
            FillRect(hdc, &rc, g_bgBrush);
            return 1;
        }

        case WM_CTLCOLORSTATIC: {
            HDC hdc = reinterpret_cast<HDC>(wParam);
            HWND control = reinterpret_cast<HWND>(lParam);
            const int id = GetDlgCtrlID(control);
            SetBkMode(hdc, TRANSPARENT);
            if (id == IDC_STATUS) {
                bool active = false;
                {
                    std::lock_guard<std::mutex> lock(g_lock);
                    active = g_active;
                }
                SetTextColor(hdc, active ? kGreen : kText);
            } else if (id == IDC_IP_LIST || id == IDC_PORT || id == IDC_PIN_VAL) {
                SetTextColor(hdc, kNavy);
            } else {
                SetTextColor(hdc, kMuted);
            }
            return reinterpret_cast<INT_PTR>(g_bgBrush);
        }

        case WM_COMMAND: {
            const int id = LOWORD(wParam);
            if (id == IDC_COPY_IP) {
                copyToClipboard(hWnd, joinedIps());
                return TRUE;
            }
            if (id == IDC_COPY_PIN) {
                std::string pin;
                {
                    std::lock_guard<std::mutex> lock(g_lock);
                    pin = g_pin;
                }
                copyToClipboard(hWnd, pin);
                return TRUE;
            }
            if (id == IDC_EXIT) {
                requestExit();
                return TRUE;
            }
            return FALSE;
        }

        case WM_CLOSE:
            requestExit();
            return TRUE;

        case WM_DESTROY:
            g_hWnd = nullptr;
            PostQuitMessage(0);
            return TRUE;

        case kMsgRefresh:
            refreshValues(hWnd);
            return TRUE;

        default:
            return FALSE;
    }
}

void runMessageLoop() {
    g_hInst = GetModuleHandleW(nullptr);
    g_bgBrush = CreateSolidBrush(kBg);
    g_navyBrush = CreateSolidBrush(kNavy);
    g_titleFont = CreateFontW(-20, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                              OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                              DEFAULT_PITCH, L"Segoe UI");
    g_subFont = CreateFontW(-12, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                            DEFAULT_PITCH, L"Segoe UI");

    HWND dialog = CreateDialogParamW(g_hInst, MAKEINTRESOURCEW(CAPTION_CHAPULIN), nullptr,
                                     DialogProc, 0);
    if (!dialog) {
        MessageBoxW(nullptr, L"No se pudo crear la ventana del host.", L"Chapulin Remoto",
                    MB_OK | MB_ICONERROR);
        PostQuitMessage(0);
        return;
    }

    ShowWindow(dialog, SW_SHOW);
    UpdateWindow(dialog);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(dialog, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

}  // namespace

void showHostWindow(const std::string& pin, uint16_t port, const std::vector<std::string>& ips) {
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_pin = pin;
        g_port = port;
        g_ips = ips;
    }
    if (g_hWnd) {
        ShowWindow(g_hWnd, SW_SHOW);
        SetForegroundWindow(g_hWnd);
        PostMessageW(g_hWnd, kMsgRefresh, 0, 0);
        return;
    }
    static std::thread ui([] { runMessageLoop(); });
    ui.detach();
}

void updateHostStatus(const std::string& status, const std::string& client, bool active) {
    HWND target = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_status = status;
        g_client = client;
        g_active = active;
        target = g_hWnd;
    }
    if (target) PostMessageW(target, kMsgRefresh, 0, 0);
}

void setExitHandler(std::function<void()> handler) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_onExit = std::move(handler);
}

}  // namespace ui
}  // namespace chp
