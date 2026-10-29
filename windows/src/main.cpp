#include <winsock2.h>
#include <ws2tcpip.h>
#include <objbase.h>
#include <windows.h>
#include <shellapi.h>

#include <string>
#include <vector>

#include "app/config.h"
#include "app/session.h"
#include "net/server.h"
#include "ui/host_ui.h"
#include "util/log.h"

namespace {

std::vector<std::string> localIPv4() {
    std::vector<std::string> result;

    char host[256]{};
    if (gethostname(host, sizeof(host)) != 0) {
        result.push_back("127.0.0.1");
        return result;
    }

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    addrinfo* addresses = nullptr;
    if (getaddrinfo(host, nullptr, &hints, &addresses) != 0) {
        result.push_back("127.0.0.1");
        return result;
    }

    for (addrinfo* it = addresses; it != nullptr; it = it->ai_next) {
        auto* addr = reinterpret_cast<sockaddr_in*>(it->ai_addr);
        char buffer[INET_ADDRSTRLEN]{};
        if (inet_ntop(AF_INET, &addr->sin_addr, buffer, sizeof(buffer)) == nullptr) continue;

        const std::string text(buffer);
        if (text.rfind("127.", 0) == 0) continue;

        bool duplicate = false;
        for (const auto& existing : result) {
            if (existing == text) duplicate = true;
        }
        if (!duplicate) result.push_back(text);
    }

    freeaddrinfo(addresses);

    if (result.empty()) result.push_back("127.0.0.1");
    return result;
}

int runHost(int argc, char** argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    if (FAILED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED))) {
        LOG_ERR("CoInitializeEx fallo");
        return 1;
    }

    chp::Config config;
    std::string error;
    if (!chp::parseArgs(argc, argv, config, error)) {
        MessageBoxA(nullptr, error.c_str(), "Chapulin Remoto", MB_OK | MB_ICONERROR);
        CoUninitialize();
        return 2;
    }

    chp::Session session(config.pin);
    session.setQuality(static_cast<uint16_t>(config.maxWidth), static_cast<uint8_t>(config.quality),
                       static_cast<uint8_t>(config.fps));

    chp::Server server(config, session);
    if (!server.start(error)) {
        MessageBoxA(nullptr, error.c_str(), "Chapulin Remoto", MB_OK | MB_ICONERROR);
        CoUninitialize();
        return 3;
    }

    chp::ui::setExitHandler([&server] { server.requestStop(); });
    chp::ui::showHostWindow(config.pin, config.port, localIPv4());
    chp::ui::updateHostStatus("Esperando conexiones...", "Ningun cliente conectado", false);

    LOG_INFO("Host escuchando en el puerto %u (PIN %s)", config.port, config.pin.c_str());

    server.run();

    CoUninitialize();
    return 0;
}

}  // namespace

int APIENTRY WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)lpCmdLine;
    (void)nCmdShow;

    // Entrada GUI: sin ventana de consola. Argumentos en UTF-8 via CommandLineToArgvW.
    int argc = 0;
    LPWSTR* argvW = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::vector<std::string> args;
    args.reserve(argc > 0 ? argc : 1);
    args.push_back("chapulin-host.exe");
    for (int i = 1; i < argc; ++i) {
        int len = WideCharToMultiByte(CP_UTF8, 0, argvW[i], -1, nullptr, 0, nullptr, nullptr);
        std::string s(len - 1, 0);
        WideCharToMultiByte(CP_UTF8, 0, argvW[i], -1, s.data(), len, nullptr, nullptr);
        args.push_back(s);
    }
    LocalFree(argvW);

    std::vector<char*> argvPtrs;
    argvPtrs.reserve(args.size());
    for (auto& a : args) argvPtrs.push_back(a.data());

    return runHost(static_cast<int>(argvPtrs.size()), argvPtrs.data());
}
