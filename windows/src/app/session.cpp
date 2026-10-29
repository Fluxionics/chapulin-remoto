#include "app/session.h"

namespace chp {

bool Session::checkPin(const std::string& candidate) const {
    // Longitud distinta = fallo, pero seguimos comparando para no salir antes.
    unsigned diff = static_cast<unsigned>(candidate.size() ^ pin_.size());
    size_t n = pin_.size() < candidate.size() ? pin_.size() : candidate.size();
    for (size_t i = 0; i < n; ++i) {
        diff |= static_cast<unsigned>(candidate[i] ^ pin_[i]);
    }
    return diff == 0;
}

void Session::setQuality(uint16_t maxWidth, uint8_t quality, uint8_t fps) {
    if (maxWidth >= 320 && maxWidth <= 3840) maxWidth_.store(maxWidth, std::memory_order_relaxed);
    if (quality >= 10 && quality <= 95) quality_.store(quality, std::memory_order_relaxed);
    if (fps >= 1 && fps <= 60) fps_.store(fps, std::memory_order_relaxed);
}

}  // namespace chp
