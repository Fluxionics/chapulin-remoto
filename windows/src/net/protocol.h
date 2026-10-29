#pragma once

// Protocolo CHP/1 - definicion de paquetes.
// Ver docs/PROTOCOLO.md para la especificacion completa.

#include <cstddef>
#include <cstdint>

namespace chp {
namespace proto {

constexpr uint16_t kMagic = 0x4843;  // bytes 'C','H' en little-endian
constexpr uint8_t kVersion = 1;
constexpr size_t kHeaderSize = 12;
constexpr uint32_t kMaxPayload = 8u * 1024u * 1024u;
constexpr uint16_t kDefaultPort = 47474;

enum class PacketType : uint8_t {
    Hello = 0x01,
    HelloAck = 0x02,
    Auth = 0x03,
    AuthResult = 0x04,
    VideoFrame = 0x05,
    Input = 0x06,
    Ping = 0x07,
    Pong = 0x08,
    Disconnect = 0x09,
    SetQuality = 0x0A,
};

enum class AuthResult : uint8_t {
    Ok = 0,
    WrongPin = 1,
    Blocked = 2,
};

enum class FrameFormat : uint8_t {
    Jpeg = 1,
    H264Idr = 2,
    H264P = 3,
};

enum class InputKind : uint8_t {
    Move = 0,
    ButtonDown = 1,
    ButtonUp = 2,
    Wheel = 3,
    KeyDown = 4,
    KeyUp = 5,
    Char = 6,
};

enum InputFlags : uint32_t {
    kShift = 1u << 0,
    kCtrl = 1u << 1,
    kAlt = 1u << 2,
    kMeta = 1u << 3,
};

enum DisconnectReason : uint8_t {
    kNormal = 0,
    kProtocolError = 1,
    kAuthFailed = 2,
    kServerBusy = 3,
    kTimeout = 4,
    kHostStopping = 5,
};

#pragma pack(push, 1)

struct Header {
    uint16_t magic;
    uint8_t version;
    uint8_t type;
    uint32_t payload_len;
    uint32_t seq;
};

struct Handshake {
    uint16_t proto_min;
    uint16_t proto_max;
    uint8_t client_os;  // 1=Windows 2=Android 3=Linux
    uint8_t caps[3];
    uint32_t screen_w;
    uint32_t screen_h;
};

struct HandshakeAck {
    uint16_t proto;
    uint16_t host_port;
    uint32_t screen_w;
    uint32_t screen_h;
    uint8_t monitor_count;
    uint8_t auth_required;
    uint8_t name_len;
    uint8_t reserved[9];
    // seguido de `name_len` bytes UTF-8 con el nombre del PC
};

struct FrameInfo {
    uint16_t width;
    uint16_t height;
    uint8_t format;
    uint8_t monitor;
    uint16_t scale_pct;
    uint32_t ts_ms;
};

struct InputEvent {
    uint8_t kind;
    uint8_t button;
    uint16_t x;
    uint16_t y;
    int16_t delta;
    uint16_t key;
    uint32_t flags;
};

struct Quality {
    uint16_t max_width;
    uint8_t quality;
    uint8_t fps;
};

#pragma pack(pop)

static_assert(sizeof(Header) == 12, "Header debe medir 12 bytes");
static_assert(sizeof(Handshake) == 16, "Handshake debe medir 16 bytes");
static_assert(sizeof(HandshakeAck) == 24, "HandshakeAck debe medir 24 bytes");
static_assert(sizeof(FrameInfo) == 12, "FrameInfo debe medir 12 bytes");
static_assert(sizeof(InputEvent) == 14, "InputEvent debe medir 14 bytes");
static_assert(sizeof(Quality) == 4, "Quality debe medir 4 bytes");

}  // namespace proto
}  // namespace chp
