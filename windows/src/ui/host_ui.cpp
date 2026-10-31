#include "ui/host_ui.h"

#include <objbase.h>
#include <windowsx.h>
#include <gdiplus.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "net/phone_client.h"
#include "resource.h"
#include "ui/phone_viewer.h"

namespace chp {
namespace ui {
namespace {

constexpr COLORREF kBg = RGB(0x14, 0x1A, 0x20);
constexpr COLORREF kEditBg = RGB(0x22, 0x2C, 0x36);
constexpr COLORREF kGreenPrimary = RGB(0x7C, 0xB3, 0x42);
constexpr COLORREF kGreenPressed = RGB(0x68, 0x9F, 0x38);
constexpr COLORREF kGreenLight = RGB(0x9C, 0xCC, 0x65);
constexpr COLORREF kGreenBright = RGB(0xAE, 0xEA, 0x00);
constexpr COLORREF kBtnSecondary = RGB(0x24, 0x30, 0x3C);
constexpr COLORREF kBtnSecPressed = RGB(0x1A, 0x24, 0x2E);
constexpr COLORREF kBtnBorder = RGB(0x3E, 0x50, 0x60);
constexpr COLORREF kTextLight = RGB(0xEE, 0xF2, 0xF6);
constexpr COLORREF kTextMuted = RGB(0xB0, 0xBE, 0xC5);
constexpr COLORREF kTextDim = RGB(0x78, 0x90, 0x9C);
constexpr UINT kMsgRefresh = WM_APP + 1;
constexpr UINT kMsgOpenViewer = WM_APP + 2;

struct OpenViewerData {
    std::shared_ptr<client::PhoneClient> client;
    std::string ip;
};

HINSTANCE g_hInst = nullptr;
HWND g_hWnd = nullptr;
HICON g_hAppIcon = nullptr;
std::string g_pin;
uint16_t g_port = 47474;
std::vector<std::string> g_ips;
std::string g_status = "Esperando conexiones...";
std::string g_client = "Ningun cliente conectado";
bool g_active = false;
std::function<void()> g_onExit;
std::mutex g_lock;

HBRUSH g_bgBrush = nullptr;
HBRUSH g_editBrush = nullptr;
HFONT g_titleFont = nullptr;
HFONT g_subFont = nullptr;
HFONT g_boldFont = nullptr;
HFONT g_regularFont = nullptr;

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
    if (HWND sWnd = GetDlgItem(hWnd, IDC_STATUS)) InvalidateRect(sWnd, nullptr, TRUE);
    if (HWND cWnd = GetDlgItem(hWnd, IDC_CLIENT)) InvalidateRect(cWnd, nullptr, TRUE);
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

void drawModernButton(const DRAWITEMSTRUCT* pDIS) {
    HDC hdc = pDIS->hDC;
    RECT rc = pDIS->rcItem;
    bool isPressed = (pDIS->itemState & ODS_SELECTED) != 0;
    int id = static_cast<int>(pDIS->CtlID);

    COLORREF fillCol = kBtnSecondary;
    COLORREF borderCol = kBtnBorder;
    COLORREF textCol = kTextLight;

    if (id == IDC_CONNECT_PHONE) {
        fillCol = isPressed ? kGreenPressed : kGreenPrimary;
        borderCol = kGreenLight;
        textCol = RGB(255, 255, 255);
    } else if (id == IDC_COPY_IP || id == IDC_COPY_PIN) {
        fillCol = isPressed ? kBtnSecPressed : kBtnSecondary;
        borderCol = kBtnBorder;
        textCol = kGreenLight;
    } else if (id == IDC_EXIT) {
        fillCol = isPressed ? kBtnSecPressed : kBtnSecondary;
        borderCol = kBtnBorder;
        textCol = kTextMuted;
    }

    HBRUSH fillBrush = CreateSolidBrush(fillCol);
    HPEN borderPen = CreatePen(PS_SOLID, 1, borderCol);
    HGDIOBJ oldBrush = SelectObject(hdc, fillBrush);
    HGDIOBJ oldPen = SelectObject(hdc, borderPen);

    RoundRect(hdc, rc.left, rc.top, rc.right, rc.bottom, 8, 8);

    SelectObject(hdc, oldBrush);
    SelectObject(hdc, oldPen);
    DeleteObject(fillBrush);
    DeleteObject(borderPen);

    wchar_t text[128]{};
    GetWindowTextW(pDIS->hwndItem, text, 128);

    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, textCol);
    HGDIOBJ oldFont = SelectObject(hdc, id == IDC_CONNECT_PHONE ? g_boldFont : g_regularFont);

    RECT textRc = rc;
    if (isPressed) {
        OffsetRect(&textRc, 1, 1);
    }
    DrawTextW(hdc, text, -1, &textRc, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(hdc, oldFont);
}

INT_PTR CALLBACK DialogProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_INITDIALOG: {
            g_hWnd = hWnd;
            g_hAppIcon = LoadIconW(g_hInst, MAKEINTRESOURCEW(IDI_ICON1));
            if (g_hAppIcon) {
                SendMessageW(hWnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(g_hAppIcon));
                SendMessageW(hWnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(g_hAppIcon));
            }

            HWND hPortCtrl = GetDlgItem(hWnd, IDC_PHONE_PORT);
            if (hPortCtrl) SetWindowTextW(hPortCtrl, L"47474");

            refreshValues(hWnd);

            RECT rc{};
            GetWindowRect(hWnd, &rc);
            int width = rc.right - rc.left;
            int height = rc.bottom - rc.top;
            int screenX = GetSystemMetrics(SM_CXSCREEN);
            int screenY = GetSystemMetrics(SM_CYSCREEN);
            SetWindowPos(hWnd, nullptr, (screenX - width) / 2, (screenY - height) / 2, 0, 0,
                         SWP_NOSIZE | SWP_NOZORDER);
            return TRUE;
        }

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hWnd, &ps);
            RECT rc{};
            GetClientRect(hWnd, &rc);

            FillRect(hdc, &rc, g_bgBrush);

            if (g_hAppIcon) {
                DrawIconEx(hdc, 22, 12, g_hAppIcon, 32, 32, 0, nullptr, DI_NORMAL);
            }

            SetBkMode(hdc, TRANSPARENT);

            HGDIOBJ oldFont = SelectObject(hdc, g_titleFont);
            SetTextColor(hdc, RGB(255, 255, 255));
            TextOutW(hdc, 62, 10, L"Chapulín Remoto", 15);

            SelectObject(hdc, g_subFont);
            SetTextColor(hdc, kTextDim);
            TextOutW(hdc, 64, 30, L"Control Remoto PC <-> Celular (LAN)", 35);

            bool active = false;
            {
                std::lock_guard<std::mutex> lock(g_lock);
                active = g_active;
            }

            RECT badgeRc{ rc.right - 110, 14, rc.right - 20, 36 };
            HBRUSH badgeBrush = CreateSolidBrush(active ? RGB(0x1B, 0x38, 0x24) : RGB(0x22, 0x2E, 0x38));
            HPEN badgePen = CreatePen(PS_SOLID, 1, active ? kGreenLight : kBtnBorder);
            HGDIOBJ ob = SelectObject(hdc, badgeBrush);
            HGDIOBJ op = SelectObject(hdc, badgePen);
            RoundRect(hdc, badgeRc.left, badgeRc.top, badgeRc.right, badgeRc.bottom, 12, 12);
            SelectObject(hdc, ob);
            SelectObject(hdc, op);
            DeleteObject(badgeBrush);
            DeleteObject(badgePen);

            SelectObject(hdc, g_boldFont);
            SetTextColor(hdc, active ? kGreenBright : kTextMuted);
            DrawTextW(hdc, active ? L"● Conectado" : L"● Listo", -1, &badgeRc, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

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

        case WM_DRAWITEM: {
            drawModernButton(reinterpret_cast<const DRAWITEMSTRUCT*>(lParam));
            return TRUE;
        }

        case WM_CTLCOLORDLG: {
            return reinterpret_cast<INT_PTR>(g_bgBrush);
        }

        case WM_CTLCOLOREDIT: {
            HDC hdc = reinterpret_cast<HDC>(wParam);
            SetBkMode(hdc, OPAQUE);
            SetBkColor(hdc, kEditBg);
            SetTextColor(hdc, RGB(255, 255, 255));
            return reinterpret_cast<INT_PTR>(g_editBrush);
        }

        case WM_CTLCOLORSTATIC: {
            HDC hdc = reinterpret_cast<HDC>(wParam);
            HWND control = reinterpret_cast<HWND>(lParam);
            const int id = GetDlgCtrlID(control);
            SetBkMode(hdc, TRANSPARENT);

            if (id == IDC_GB_CONN || id == IDC_GB_PHONE || id == IDC_GB_STATE) {
                SetTextColor(hdc, kGreenLight);
            } else if (id == IDC_STATUS) {
                bool active = false;
                {
                    std::lock_guard<std::mutex> lock(g_lock);
                    active = g_active;
                }
                SetTextColor(hdc, active ? kGreenBright : kGreenLight);
            } else if (id == IDC_CLIENT) {
                bool active = false;
                {
                    std::lock_guard<std::mutex> lock(g_lock);
                    active = g_active;
                }
                SetTextColor(hdc, active ? kGreenBright : kTextMuted);
            } else if (id == IDC_IP_LIST || id == IDC_PORT || id == IDC_PIN_VAL) {
                SetTextColor(hdc, kGreenBright);
            } else if (id == IDC_HINT) {
                SetTextColor(hdc, kTextDim);
            } else {
                SetTextColor(hdc, kTextMuted);
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
            if (id == IDC_CONNECT_PHONE) {
                wchar_t phoneIpW[256]{};
                wchar_t phonePortW[32]{};
                wchar_t phonePinW[32]{};

                HWND phoneIpCtrl = GetDlgItem(hWnd, IDC_PHONE_IP);
                HWND phonePortCtrl = GetDlgItem(hWnd, IDC_PHONE_PORT);
                HWND phonePinCtrl = GetDlgItem(hWnd, IDC_PHONE_PIN);

                if (phoneIpCtrl) GetWindowTextW(phoneIpCtrl, phoneIpW, 256);
                if (phonePortCtrl) GetWindowTextW(phonePortCtrl, phonePortW, 32);
                if (phonePinCtrl) GetWindowTextW(phonePinCtrl, phonePinW, 32);

                char phoneIp[256]{};
                char phonePort[32]{};
                char phonePin[32]{};

                WideCharToMultiByte(CP_UTF8, 0, phoneIpW, -1, phoneIp, 256, nullptr, nullptr);
                WideCharToMultiByte(CP_UTF8, 0, phonePortW, -1, phonePort, 32, nullptr, nullptr);
                WideCharToMultiByte(CP_UTF8, 0, phonePinW, -1, phonePin, 32, nullptr, nullptr);

                std::string ipStr(phoneIp);
                std::string portStr(phonePort);
                std::string pinStr(phonePin);

                ipStr.erase(0, ipStr.find_first_not_of(" \t\r\n"));
                ipStr.erase(ipStr.find_last_not_of(" \t\r\n") + 1);

                portStr.erase(0, portStr.find_first_not_of(" \t\r\n"));
                portStr.erase(portStr.find_last_not_of(" \t\r\n") + 1);

                pinStr.erase(0, pinStr.find_first_not_of(" \t\r\n"));
                pinStr.erase(pinStr.find_last_not_of(" \t\r\n") + 1);

                if (ipStr.empty() || portStr.empty() || pinStr.empty()) {
                    MessageBoxW(hWnd, L"Ingresa la IP, Puerto y PIN que muestra la app en tu celular.",
                                L"Chapulín Remoto", MB_OK | MB_ICONWARNING);
                    return TRUE;
                }

                uint16_t portVal = static_cast<uint16_t>(std::atoi(portStr.c_str()));
                if (portVal == 0) portVal = 47474;

                setTextW(hWnd, IDC_CLIENT, "Conectando a " + ipStr + ":" + std::to_string(portVal) + "...");
                if (HWND cWnd = GetDlgItem(hWnd, IDC_CLIENT)) InvalidateRect(cWnd, nullptr, TRUE);

                std::thread([hWnd, ipStr, portVal, pinStr]() {
                    auto client = std::make_shared<client::PhoneClient>();
                    std::string error;
                    if (!client->connect(ipStr, portVal, pinStr, error)) {
                        updateHostStatus(g_status, "Error al conectar con " + ipStr, false);
                        std::wstring msg = L"No se pudo conectar al celular:\n" + widen(error);
                        MessageBoxW(hWnd, msg.c_str(), L"Chapulín Remoto", MB_OK | MB_ICONERROR);
                        return;
                    }

                    updateHostStatus(g_status, "Celular conectado: " + ipStr, true);
                    auto* data = new OpenViewerData{ client, ipStr };
                    PostMessageW(hWnd, kMsgOpenViewer, 0, reinterpret_cast<LPARAM>(data));
                }).detach();

                return TRUE;
            }
            if (id == IDC_EXIT) {
                requestExit();
                return TRUE;
            }
            return FALSE;
        }

        case kMsgOpenViewer: {
            auto* data = reinterpret_cast<OpenViewerData*>(lParam);
            if (data) {
                openPhoneViewer(g_hInst, data->client, data->ip);
                delete data;
            }
            return TRUE;
        }

        case WM_CLOSE:
            closePhoneViewer();
            requestExit();
            return TRUE;

        case WM_DESTROY:
            closePhoneViewer();
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
    g_editBrush = CreateSolidBrush(kEditBg);

    g_titleFont = CreateFontW(-18, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                              OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                              DEFAULT_PITCH, L"Segoe UI");
    g_subFont = CreateFontW(-11, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                            DEFAULT_PITCH, L"Segoe UI");
    g_boldFont = CreateFontW(-13, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                             OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                             DEFAULT_PITCH, L"Segoe UI");
    g_regularFont = CreateFontW(-12, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                               OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                               DEFAULT_PITCH, L"Segoe UI");

    HWND dialog = CreateDialogParamW(g_hInst, MAKEINTRESOURCEW(CAPTION_CHAPULIN), nullptr,
                                     DialogProc, 0);
    if (!dialog) {
        MessageBoxW(nullptr, L"No se pudo crear la ventana principal.", L"Chapulín Remoto",
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

    if (g_bgBrush) DeleteObject(g_bgBrush);
    if (g_editBrush) DeleteObject(g_editBrush);
    if (g_titleFont) DeleteObject(g_titleFont);
    if (g_subFont) DeleteObject(g_subFont);
    if (g_boldFont) DeleteObject(g_boldFont);
    if (g_regularFont) DeleteObject(g_regularFont);
}

}

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

}
}
