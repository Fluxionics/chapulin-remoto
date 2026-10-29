#include "input/injector.h"

#include <windows.h>

namespace chp {
namespace inject {

void moveAbsolute(uint16_t x, uint16_t y) {
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dx = x;
    input.mi.dy = y;
    input.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    SendInput(1, &input, sizeof(INPUT));
}

void button(uint8_t buttonIndex, bool down) {
    DWORD flag = 0;
    switch (buttonIndex) {
        case 1:
            flag = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            break;
        case 2:
            flag = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            break;
        default:
            flag = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            break;
    }

    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dwFlags = flag;
    SendInput(1, &input, sizeof(INPUT));
}

void wheel(int16_t delta) {
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dwFlags = MOUSEEVENTF_WHEEL;
    input.mi.mouseData = static_cast<DWORD>(static_cast<int>(delta));
    SendInput(1, &input, sizeof(INPUT));
}

void key(uint16_t scancode, bool down) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wScan = scancode;
    input.ki.dwFlags = KEYEVENTF_SCANCODE | (down ? 0u : KEYEVENTF_KEYUP);
    SendInput(1, &input, sizeof(INPUT));
}

void text(uint16_t utf16CodeUnit) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wScan = utf16CodeUnit;
    input.ki.dwFlags = KEYEVENTF_UNICODE;
    SendInput(1, &input, sizeof(INPUT));

    INPUT up = input;
    up.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
    SendInput(1, &up, sizeof(INPUT));
}

}  // namespace inject
}  // namespace chp
