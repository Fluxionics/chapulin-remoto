#pragma once

#include <atomic>
#include <cstdint>
#include <string>

#include "app/config.h"
#include "app/session.h"

namespace chp {

// Servidor TCP del host. Atiende un cliente cada vez (MVP).
class Server {
public:
    Server(const Config& config, Session& session);
    ~Server();

    Server(const Server&) = delete;
    Server& operator=(const Server&) = delete;

    bool start(std::string& error);
    void run();
    void requestStop();

private:
    bool handleClient(uintptr_t client, uint32_t peerIpv4);
    void closeListen();

    const Config& config_;
    Session& session_;

    uintptr_t listenSocket_ = static_cast<uintptr_t>(~0ull);
    bool wsaStarted_ = false;
    std::atomic<bool> stopping_{false};
};

}  // namespace chp
