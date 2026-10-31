#include "net/phone_client.h"

#include <algorithm>
#include <cstring>

namespace chp {
namespace client {

namespace {
constexpr size_t kChunk = 1u << 20;
}

PhoneClient::PhoneClient() = default;

PhoneClient::~PhoneClient() {
    disconnect();
}

bool PhoneClient::sendAll(SOCKET sock, const void* data, size_t size) {
    const char* cursor = static_cast<const char*>(data);
    size_t left = size;
    while (left > 0) {
        int chunk = static_cast<int>(std::min(left, kChunk));
        int sent = ::send(sock, cursor, chunk, 0);
        if (sent <= 0) return false;
        cursor += sent;
        left -= static_cast<size_t>(sent);
    }
    return true;
}

bool PhoneClient::recvAll(SOCKET sock, void* data, size_t size) {
    char* cursor = static_cast<char*>(data);
    size_t left = size;
    while (left > 0) {
        int chunk = static_cast<int>(std::min(left, kChunk));
        int got = ::recv(sock, cursor, chunk, 0);
        if (got <= 0) return false;
        cursor += got;
        left -= static_cast<size_t>(got);
    }
    return true;
}

bool PhoneClient::sendPacket(proto::PacketType type, uint32_t seq, const void* payload, uint32_t len) {
    std::lock_guard<std::mutex> lock(sendMutex_);
    if (sock_ == INVALID_SOCKET) return false;

    proto::Header header{};
    header.magic = proto::kMagic;
    header.version = proto::kVersion;
    header.type = static_cast<uint8_t>(type);
    header.payload_len = len;
    header.seq = seq;

    if (!sendAll(sock_, &header, sizeof(header))) return false;
    if (len > 0 && payload != nullptr) {
        return sendAll(sock_, payload, len);
    }
    return true;
}

bool PhoneClient::connect(const std::string& ip, uint16_t port, const std::string& pin, std::string& error) {
    disconnect();

    sock_ = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock_ == INVALID_SOCKET) {
        error = "No se pudo crear el socket";
        return false;
    }

    BOOL noDelay = TRUE;
    setsockopt(sock_, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));

    DWORD timeoutMs = 6000;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeoutMs), sizeof(timeoutMs));
    setsockopt(sock_, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeoutMs), sizeof(timeoutMs));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) <= 0) {
        error = "Dirección IP inválida";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    if (::connect(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
        error = "No se pudo conectar con el celular en " + ip + ":" + std::to_string(port);
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    proto::Handshake handshake{};
    handshake.proto_min = proto::kVersion;
    handshake.proto_max = proto::kVersion;
    handshake.client_os = 1;
    handshake.caps[0] = 1;
    handshake.caps[1] = 0;
    handshake.caps[2] = 0;
    handshake.screen_w = 1080;
    handshake.screen_h = 2400;

    if (!sendPacket(proto::PacketType::Hello, 0, &handshake, sizeof(handshake))) {
        error = "Fallo al enviar HELLO al celular";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    proto::Header ackHeader{};
    if (!recvAll(sock_, &ackHeader, sizeof(ackHeader)) ||
        ackHeader.magic != proto::kMagic ||
        ackHeader.type != static_cast<uint8_t>(proto::PacketType::HelloAck)) {
        error = "Respuesta no reconocida del celular";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    if (ackHeader.payload_len > 0) {
        std::vector<uint8_t> ackPayload(ackHeader.payload_len);
        if (!recvAll(sock_, ackPayload.data(), ackPayload.size())) {
            error = "Error al recibir confirmación del saludo";
            closesocket(sock_);
            sock_ = INVALID_SOCKET;
            return false;
        }
    }

    if (!sendPacket(proto::PacketType::Auth, 0, pin.data(), static_cast<uint32_t>(pin.size()))) {
        error = "Fallo al enviar el PIN";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    proto::Header authHeader{};
    if (!recvAll(sock_, &authHeader, sizeof(authHeader)) ||
        authHeader.magic != proto::kMagic ||
        authHeader.type != static_cast<uint8_t>(proto::PacketType::AuthResult)) {
        error = "Respuesta inesperada a la autenticación";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    std::vector<uint8_t> authPayload(authHeader.payload_len);
    if (authHeader.payload_len > 0) {
        if (!recvAll(sock_, authPayload.data(), authPayload.size())) {
            error = "Error al leer resultado de autenticación";
            closesocket(sock_);
            sock_ = INVALID_SOCKET;
            return false;
        }
    }

    uint8_t res = authPayload.empty() ? 1 : authPayload[0];
    if (res == 1) {
        error = "PIN incorrecto. Verifica el PIN mostrado en tu celular";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    } else if (res == 2) {
        error = "Acceso bloqueado por el celular";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    } else if (res != 0) {
        error = "Fallo de autenticación (código " + std::to_string(res) + ")";
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
        return false;
    }

    DWORD idleTimeout = 15000;
    setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&idleTimeout), sizeof(idleTimeout));

    connected_ = true;
    stopping_ = false;
    reader_ = std::thread(&PhoneClient::readLoop, this);
    return true;
}

void PhoneClient::disconnect() {
    stopping_ = true;
    if (sock_ != INVALID_SOCKET) {
        SOCKET s = sock_;
        sock_ = INVALID_SOCKET;
        ::shutdown(s, SD_BOTH);
        closesocket(s);
    }
    if (reader_.joinable()) {
        reader_.join();
    }
    connected_ = false;
}

bool PhoneClient::isConnected() const {
    return connected_.load();
}

void PhoneClient::setFrameCallback(FrameCallback cb) {
    frameCallback_ = std::move(cb);
}

void PhoneClient::setDisconnectCallback(DisconnectCallback cb) {
    disconnectCallback_ = std::move(cb);
}

bool PhoneClient::sendInput(const proto::InputEvent& event) {
    if (!connected_.load()) return false;
    return sendPacket(proto::PacketType::Input, ++sendSeq_, &event, sizeof(event));
}

bool PhoneClient::sendQuality(uint16_t maxWidth, uint8_t quality, uint8_t fps) {
    if (!connected_.load()) return false;
    proto::Quality q{maxWidth, quality, fps};
    return sendPacket(proto::PacketType::SetQuality, ++sendSeq_, &q, sizeof(q));
}

void PhoneClient::readLoop() {
    while (!stopping_.load()) {
        proto::Header hdr{};
        if (!recvAll(sock_, &hdr, sizeof(hdr))) break;
        if (hdr.magic != proto::kMagic) break;
        if (hdr.payload_len > proto::kMaxPayload) break;

        std::vector<uint8_t> payload(hdr.payload_len);
        if (hdr.payload_len > 0) {
            if (!recvAll(sock_, payload.data(), payload.size())) break;
        }

        auto type = static_cast<proto::PacketType>(hdr.type);
        if (type == proto::PacketType::VideoFrame) {
            if (payload.size() >= sizeof(proto::FrameInfo)) {
                proto::FrameInfo info{};
                std::memcpy(&info, payload.data(), sizeof(info));
                const uint8_t* imgPtr = payload.data() + sizeof(info);
                size_t imgSize = payload.size() - sizeof(info);
                if (imgSize > 0 && frameCallback_) {
                    std::vector<uint8_t> jpeg(imgPtr, imgPtr + imgSize);
                    frameCallback_(jpeg, info.width, info.height);
                }
            }
        } else if (type == proto::PacketType::Ping) {
            sendPacket(proto::PacketType::Pong, 0, payload.data(), static_cast<uint32_t>(payload.size()));
        } else if (type == proto::PacketType::Disconnect) {
            break;
        }
    }

    connected_ = false;
    if (disconnectCallback_ && !stopping_.load()) {
        disconnectCallback_("Conexión finalizada con el celular.");
    }
}

}
}
