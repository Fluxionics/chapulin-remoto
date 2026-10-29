#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace chp {

// Estado de la sesion activa. En el MVP hay un solo cliente como maximo.
class Session {
public:
    explicit Session(std::string pin) : pin_(std::move(pin)) {}

    const std::string& pin() const { return pin_; }

    // Comparacion en tiempo constante: no filtra cuantos digitos acerto.
    bool checkPin(const std::string& candidate) const;

    // Ajustes negociables por el cliente en caliente.
    void setQuality(uint16_t maxWidth, uint8_t quality, uint8_t fps);
    uint16_t maxWidth() const { return maxWidth_.load(std::memory_order_relaxed); }
    uint8_t quality() const { return quality_.load(std::memory_order_relaxed); }
    uint8_t fps() const { return fps_.load(std::memory_order_relaxed); }

    bool active() const { return active_.load(std::memory_order_relaxed); }
    void setActive(bool value) { active_.store(value, std::memory_order_relaxed); }

    uint64_t framesSent() const { return framesSent_.load(std::memory_order_relaxed); }
    void addFrame() { framesSent_.fetch_add(1, std::memory_order_relaxed); }

private:
    std::string pin_;
    std::atomic<uint16_t> maxWidth_{1280};
    std::atomic<uint8_t> quality_{70};
    std::atomic<uint8_t> fps_{15};
    std::atomic<bool> active_{false};
    std::atomic<uint64_t> framesSent_{0};
};

}  // namespace chp
