#include "net/server.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "capture/gdi_capturer.h"
#include "codec/wic_jpeg.h"
#include "input/injector.h"
#include "net/protocol.h"
#include "ui/host_ui.h"
#include "util/log.h"

namespace chp {
namespace {

constexpr size_t kChunk = 1u << 20;  // 1 MiB por llamada a send/recv

bool sendAll(SOCKET socket, const void* data, size_t size) {
    const char* cursor = static_cast<const char*>(data);
    size_t left = size;
    while (left > 0) {
        int chunk = static_cast<int>(std::min(left, kChunk));
        int sent = ::send(socket, cursor, chunk, 0);
        if (sent <= 0) return false;
        cursor += sent;
        left -= static_cast<size_t>(sent);
    }
    return true;
}

bool recvAll(SOCKET socket, void* data, size_t size) {
    char* cursor = static_cast<char*>(data);
    size_t left = size;
    while (left > 0) {
        int chunk = static_cast<int>(std::min(left, kChunk));
        int got = ::recv(socket, cursor, chunk, 0);
        if (got <= 0) return false;
        cursor += got;
        left -= static_cast<size_t>(got);
    }
    return true;
}

bool sendPacket(SOCKET socket, proto::PacketType type, uint32_t seq, const void* payload,
                uint32_t length) {
    proto::Header header{};
    header.magic = proto::kMagic;
    header.version = proto::kVersion;
    header.type = static_cast<uint8_t>(type);
    header.payload_len = length;
    header.seq = seq;

    if (!sendAll(socket, &header, sizeof(header))) return false;
    if (length > 0 && payload != nullptr) return sendAll(socket, payload, length);
    return true;
}

struct Incoming {
    proto::Header header{};
    std::vector<uint8_t> payload;
};

bool readPacket(SOCKET socket, Incoming& packet) {
    if (!recvAll(socket, &packet.header, sizeof(proto::Header))) return false;
    if (packet.header.magic != proto::kMagic) return false;
    if (packet.header.version != proto::kVersion) return false;
    if (packet.header.payload_len > proto::kMaxPayload) return false;

    packet.payload.resize(packet.header.payload_len);
    if (packet.header.payload_len > 0 &&
        !recvAll(socket, packet.payload.data(), packet.payload.size())) {
        return false;
    }
    return true;
}

void applyInput(const proto::InputEvent& event) {
    switch (static_cast<proto::InputKind>(event.kind)) {
        case proto::InputKind::Move:
            inject::moveAbsolute(event.x, event.y);
            break;
        case proto::InputKind::ButtonDown:
            inject::moveAbsolute(event.x, event.y);
            inject::button(event.button, true);
            break;
        case proto::InputKind::ButtonUp:
            inject::moveAbsolute(event.x, event.y);
            inject::button(event.button, false);
            break;
        case proto::InputKind::Wheel:
            inject::wheel(event.delta);
            break;
        case proto::InputKind::KeyDown:
            inject::key(event.key, true);
            break;
        case proto::InputKind::KeyUp:
            inject::key(event.key, false);
            break;
        case proto::InputKind::Char:
            inject::text(event.key);
            break;
        default:
            break;
    }
}

std::string ipToString(uint32_t ipv4) {
    in_addr addr{};
    addr.s_addr = htonl(ipv4);
    char buffer[INET_ADDRSTRLEN]{};
    inet_ntop(AF_INET, &addr, buffer, sizeof(buffer));
    return buffer;
}

std::string computerName() {
    char name[MAX_COMPUTERNAME_LENGTH + 1]{};
    DWORD size = static_cast<DWORD>(sizeof(name));
    if (!GetComputerNameA(name, &size)) return "PC";
    return std::string(name, size);
}

}  // namespace

Server::Server(const Config& config, Session& session) : config_(config), session_(session) {}

Server::~Server() {
    requestStop();
    if (wsaStarted_) WSACleanup();
}

bool Server::start(std::string& error) {
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        error = "WSAStartup fallo";
        return false;
    }
    wsaStarted_ = true;

    SOCKET listenSocket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listenSocket == INVALID_SOCKET) {
        error = "no se pudo crear el socket";
        return false;
    }

    BOOL exclusive = TRUE;
    setsockopt(listenSocket, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
               reinterpret_cast<const char*>(&exclusive), sizeof(exclusive));

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(config_.port);

    if (::bind(listenSocket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) ==
        SOCKET_ERROR) {
        error = "no se pudo abrir el puerto " + std::to_string(config_.port) +
                " (puede estar en uso)";
        closesocket(listenSocket);
        return false;
    }

    if (::listen(listenSocket, SOMAXCONN) == SOCKET_ERROR) {
        error = "listen fallo";
        closesocket(listenSocket);
        return false;
    }

    listenSocket_ = static_cast<uintptr_t>(listenSocket);
    return true;
}

void Server::closeListen() {
    if (listenSocket_ == static_cast<uintptr_t>(~0ull)) return;
    SOCKET socket = static_cast<SOCKET>(listenSocket_);
    listenSocket_ = static_cast<uintptr_t>(~0ull);
    ::shutdown(socket, SD_BOTH);
    closesocket(socket);
}

void Server::requestStop() {
    stopping_.store(true, std::memory_order_relaxed);
    closeListen();
}

void Server::run() {
    while (!stopping_.load(std::memory_order_relaxed)) {
        sockaddr_in peer{};
        int peerSize = static_cast<int>(sizeof(peer));

        SOCKET client =
            ::accept(static_cast<SOCKET>(listenSocket_), reinterpret_cast<sockaddr*>(&peer),
                     &peerSize);
        if (client == INVALID_SOCKET) {
            if (stopping_.load(std::memory_order_relaxed)) break;
            LOG_WARN("accept fallo (%d)", WSAGetLastError());
            continue;
        }

        const uint32_t ip = ntohl(peer.sin_addr.s_addr);
        handleClient(static_cast<uintptr_t>(client), ip);
    }
}

bool Server::handleClient(uintptr_t clientHandle, uint32_t peerIpv4) {
    SOCKET client = static_cast<SOCKET>(clientHandle);
    const std::string peer = ipToString(peerIpv4);

    BOOL noDelay = TRUE;
    setsockopt(client, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay),
               sizeof(noDelay));

    DWORD recvTimeout = 30000;
    DWORD sendTimeout = 5000;
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&recvTimeout),
               sizeof(recvTimeout));
    setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sendTimeout),
               sizeof(sendTimeout));

    auto disconnect = [&](uint8_t reason) {
        sendPacket(client, proto::PacketType::Disconnect, 0, &reason, 1);
    };

    LOG_INFO("Cliente conectado desde %s", peer.c_str());
    ui::updateHostStatus("Cliente conectado, autenticando...", peer, false);

    Incoming packet;
    if (!readPacket(client, packet) || packet.header.type !=
                                          static_cast<uint8_t>(proto::PacketType::Hello)) {
        LOG_WARN("Handshake invalido de %s", peer.c_str());
        disconnect(proto::kProtocolError);
        closesocket(client);
        return false;
    }

    if (packet.payload.size() < sizeof(proto::Handshake)) {
        disconnect(proto::kProtocolError);
        closesocket(client);
        return false;
    }

    proto::Handshake hello{};
    std::memcpy(&hello, packet.payload.data(), sizeof(hello));
    LOG_INFO("Cliente CHP/%u-%u, OS=%u, pantalla %ux%u", hello.proto_min, hello.proto_max,
             hello.client_os, hello.screen_w, hello.screen_h);

    const std::string hostName = computerName();

    proto::HandshakeAck ack{};
    ack.proto = proto::kVersion;
    ack.host_port = config_.port;
    ack.screen_w = static_cast<uint32_t>(GetSystemMetrics(SM_CXSCREEN));
    ack.screen_h = static_cast<uint32_t>(GetSystemMetrics(SM_CYSCREEN));
    ack.monitor_count = 1;
    ack.auth_required = 1;
    ack.name_len = static_cast<uint8_t>(std::min<size_t>(hostName.size(), 63));

    std::vector<uint8_t> ackPayload(sizeof(ack) + ack.name_len);
    std::memcpy(ackPayload.data(), &ack, sizeof(ack));
    std::memcpy(ackPayload.data() + sizeof(ack), hostName.data(), ack.name_len);

    if (!sendPacket(client, proto::PacketType::HelloAck, 0, ackPayload.data(),
                    static_cast<uint32_t>(ackPayload.size()))) {
        closesocket(client);
        return false;
    }

    if (!readPacket(client, packet) ||
        packet.header.type != static_cast<uint8_t>(proto::PacketType::Auth)) {
        disconnect(proto::kProtocolError);
        closesocket(client);
        return false;
    }

    const std::string candidate(packet.payload.begin(), packet.payload.end());
    const bool authenticated = session_.checkPin(candidate);
    const uint8_t result = static_cast<uint8_t>(
        authenticated ? proto::AuthResult::Ok : proto::AuthResult::WrongPin);

    if (!sendPacket(client, proto::PacketType::AuthResult, 0, &result, 1)) {
        closesocket(client);
        return false;
    }

    if (!authenticated) {
        LOG_WARN("PIN incorrecto desde %s", peer.c_str());
        ui::updateHostStatus("PIN incorrecto, esperando conexion valida", "Ningun cliente conectado",
                             false);
        closesocket(client);
        return false;
    }

    LOG_INFO("Sesion ACEPTADA: %s controla este PC.", peer.c_str());
    session_.setActive(true);
    ui::updateHostStatus("Sesion activa - este PC esta siendo controlado", peer, true);

    std::atomic<bool> alive{true};
    std::atomic<uint64_t> inputCount{0};
    std::atomic<bool> firstInputLogged{false};
    std::mutex sendMutex;

    // --- Hilo de recepcion: input, ping, calidad ---
    std::thread receiver([&] {
        Incoming in;
        while (alive.load(std::memory_order_relaxed)) {
            if (!readPacket(client, in)) {
                alive.store(false, std::memory_order_relaxed);
                break;
            }

            switch (static_cast<proto::PacketType>(in.header.type)) {
                case proto::PacketType::Input: {
                    if (in.payload.size() >= sizeof(proto::InputEvent)) {
                        proto::InputEvent event{};
                        std::memcpy(&event, in.payload.data(), sizeof(event));
                        applyInput(event);

                        // Traza del primer evento: confirma que el canal de control
                        // llega y con que coordenadas, sin inundar el log.
                        inputCount.fetch_add(1, std::memory_order_relaxed);
                        if (!firstInputLogged.exchange(true, std::memory_order_relaxed)) {
                            LOG_INFO("Primer input: kind=%u boton=%u x=%u y=%u", event.kind,
                                     event.button, event.x, event.y);
                        }
                    }
                    break;
                }
                case proto::PacketType::Ping: {
                    uint32_t stamp = 0;
                    if (in.payload.size() >= sizeof(stamp)) {
                        std::memcpy(&stamp, in.payload.data(), sizeof(stamp));
                    }
                    std::lock_guard<std::mutex> lock(sendMutex);
                    sendPacket(client, proto::PacketType::Pong, in.header.seq, &stamp,
                               sizeof(stamp));
                    break;
                }
                case proto::PacketType::SetQuality: {
                    if (in.payload.size() >= sizeof(proto::Quality)) {
                        proto::Quality quality{};
                        std::memcpy(&quality, in.payload.data(), sizeof(quality));
                        session_.setQuality(quality.max_width, quality.quality, quality.fps);
                    }
                    break;
                }
                case proto::PacketType::Disconnect:
                    LOG_INFO("El cliente %s se desconecto", peer.c_str());
                    alive.store(false, std::memory_order_relaxed);
                    break;
                default:
                    // Tipo desconocido: se ignora, no rompemos la sesion.
                    break;
            }
        }
    });

    // --- Bucle de captura, codificacion y envio ---
    GdiCapturer capturer;
    JpegEncoder encoder;
    capturer.refreshMetrics();
    encoder.init();

    if (capturer.sourceWidth() < 320) {
        LOG_ERR("No hay escritorio capturable (resolucion %dx%d)", capturer.sourceWidth(),
                capturer.sourceHeight());
        alive.store(false, std::memory_order_relaxed);
    }

    std::vector<uint8_t> bgra;
    std::vector<uint8_t> jpeg;
    std::vector<uint8_t> framePayload;
    uint32_t seq = 1;
    uint64_t lastLogMs = GetTickCount64();
    uint64_t framesSinceLog = 0;

    while (alive.load(std::memory_order_relaxed) && !stopping_.load(std::memory_order_relaxed)) {
        const auto frameStart = std::chrono::steady_clock::now();

        const int maxWidth = static_cast<int>(session_.maxWidth());
        const int srcWidth = capturer.sourceWidth();
        const int srcHeight = capturer.sourceHeight();
        int outWidth = std::min(maxWidth, srcWidth);
        if (outWidth < 2) outWidth = 2;
        int outHeight = std::max(2, srcHeight * outWidth / std::max(1, srcWidth));

        if (!capturer.grab(bgra, outWidth, outHeight) ||
            !encoder.encode(bgra, outWidth, outHeight, session_.quality(), jpeg)) {
            LOG_ERR("Fallo capturando o codificando el frame");
            alive.store(false, std::memory_order_relaxed);
            break;
        }

        proto::FrameInfo info{};
        info.width = static_cast<uint16_t>(outWidth);
        info.height = static_cast<uint16_t>(outHeight);
        info.format = static_cast<uint8_t>(proto::FrameFormat::Jpeg);
        info.monitor = 0;
        info.scale_pct = static_cast<uint16_t>(outWidth * 100 / std::max(1, srcWidth));
        info.ts_ms = static_cast<uint32_t>(GetTickCount() & 0xFFFFFFFFu);

        framePayload.resize(sizeof(info) + jpeg.size());
        std::memcpy(framePayload.data(), &info, sizeof(info));
        std::memcpy(framePayload.data() + sizeof(info), jpeg.data(), jpeg.size());

        {
            std::lock_guard<std::mutex> lock(sendMutex);
            if (!sendPacket(client, proto::PacketType::VideoFrame, seq, framePayload.data(),
                            static_cast<uint32_t>(framePayload.size()))) {
                // Si el cliente ya se desconecto, el fallo es esperado: no alarmamos.
                if (alive.exchange(false)) {
                    LOG_WARN("Envio fallido: desconectando a %s", peer.c_str());
                }
                break;
            }
        }
        ++seq;
        session_.addFrame();
        ++framesSinceLog;

        const uint64_t nowMs = GetTickCount64();
        if (nowMs - lastLogMs >= 5000) {
            const uint64_t inputs = inputCount.exchange(0, std::memory_order_relaxed);
            LOG_INFO("%llu fps, %zu KB/frame, %llu eventos de entrada",
                     static_cast<unsigned long long>(framesSinceLog * 1000 / (nowMs - lastLogMs)),
                     jpeg.size() / 1024, static_cast<unsigned long long>(inputs));
            framesSinceLog = 0;
            lastLogMs = nowMs;
        }

        const int fps = std::max(1, static_cast<int>(session_.fps()));
        const auto budget = std::chrono::milliseconds(1000 / fps);
        const auto elapsed = std::chrono::steady_clock::now() - frameStart;
        if (elapsed < budget) {
            std::this_thread::sleep_for(budget - elapsed);
        }
    }

    alive.store(false, std::memory_order_relaxed);
    ::shutdown(client, SD_BOTH);
    if (receiver.joinable()) receiver.join();
    closesocket(client);

    session_.setActive(false);
    LOG_INFO("Sesion con %s terminada (%llu frames enviados)", peer.c_str(),
             static_cast<unsigned long long>(session_.framesSent()));
    ui::updateHostStatus("Esperando conexiones...", "Ningun cliente conectado", false);
    return true;
}

}  // namespace chp
