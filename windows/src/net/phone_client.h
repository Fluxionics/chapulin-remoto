#pragma once

#include <winsock2.h>
#include <ws2tcpip.h>
#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "net/protocol.h"

namespace chp {
namespace client {

class PhoneClient {
public:
    using FrameCallback = std::function<void(const std::vector<uint8_t>& jpeg, uint16_t w, uint16_t h)>;
    using DisconnectCallback = std::function<void(const std::string& reason)>;

    PhoneClient();
    ~PhoneClient();

    bool connect(const std::string& ip, uint16_t port, const std::string& pin, std::string& error);
    void disconnect();
    bool isConnected() const;

    void setFrameCallback(FrameCallback cb);
    void setDisconnectCallback(DisconnectCallback cb);

    bool sendInput(const proto::InputEvent& event);
    bool sendQuality(uint16_t maxWidth, uint8_t quality, uint8_t fps);

private:
    void readLoop();
    bool sendPacket(proto::PacketType type, uint32_t seq, const void* payload, uint32_t len);
    static bool sendAll(SOCKET sock, const void* data, size_t size);
    static bool recvAll(SOCKET sock, void* data, size_t size);

    SOCKET sock_ = INVALID_SOCKET;
    std::thread reader_;
    std::mutex sendMutex_;
    std::atomic<bool> connected_{false};
    std::atomic<bool> stopping_{false};
    uint32_t sendSeq_ = 0;

    FrameCallback frameCallback_;
    DisconnectCallback disconnectCallback_;
};

}
}
