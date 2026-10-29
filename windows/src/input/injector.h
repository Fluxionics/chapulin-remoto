#pragma once

#include <cstdint>

namespace chp {

// Inyeccion de eventos de entrada con SendInput.
// Coordenadas normalizadas 0..65535 sobre TODO el escritorio virtual.
namespace inject {

void moveAbsolute(uint16_t x, uint16_t y);
void button(uint8_t button, bool down);  // 0=izq 1=der 2=medio
void wheel(int16_t delta);
void key(uint16_t scancode, bool down);
void text(uint16_t utf16CodeUnit);

}  // namespace inject
}  // namespace chp
