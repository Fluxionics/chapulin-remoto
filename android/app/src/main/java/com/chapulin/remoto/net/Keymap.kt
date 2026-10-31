package com.chapulin.remoto.net

object Keymap {
    const val EXTENDED = 0x8000

    const val ESC = 0x01
    const val TAB = 0x0F
    const val ENTER = 0x1C
    const val BACKSPACE = 0x0E
    const val SPACE = 0x39
    const val CTRL = 0x1D
    const val ALT = 0x38
    const val SHIFT = 0x2A
    const val WIN = 0x5B or EXTENDED

    const val LEFT = 0x4B or EXTENDED
    const val RIGHT = 0x4D or EXTENDED
    const val UP = 0x48 or EXTENDED
    const val DOWN = 0x50 or EXTENDED
    const val HOME = 0x47 or EXTENDED
    const val END = 0x4F or EXTENDED
    const val PAGE_UP = 0x49 or EXTENDED
    const val PAGE_DOWN = 0x51 or EXTENDED
    const val DELETE = 0x53 or EXTENDED

    const val F1 = 0x3B; const val F2 = 0x3C; const val F3 = 0x3D; const val F4 = 0x3E
    const val F5 = 0x3F; const val F6 = 0x40; const val F7 = 0x41; const val F8 = 0x42
    const val F9 = 0x43; const val F10 = 0x44; const val F11 = 0x57; const val F12 = 0x58

    const val VOL_MUTE = 0x20 or EXTENDED
    const val VOL_DOWN = 0x2E or EXTENDED
    const val VOL_UP = 0x30 or EXTENDED
    const val MEDIA_NEXT = 0x19 or EXTENDED
    const val MEDIA_PREV = 0x10 or EXTENDED
    const val MEDIA_PLAY = 0x22 or EXTENDED

    fun forChar(c: Char): Int? {
        val lower = c.lowercaseChar()
        return when (lower) {
            'a' -> 0x1E; 'b' -> 0x30; 'c' -> 0x2E; 'd' -> 0x20; 'e' -> 0x12
            'f' -> 0x21; 'g' -> 0x22; 'h' -> 0x23; 'i' -> 0x17; 'j' -> 0x24
            'k' -> 0x25; 'l' -> 0x26; 'm' -> 0x32; 'n' -> 0x31; 'o' -> 0x18
            'p' -> 0x19; 'q' -> 0x10; 'r' -> 0x13; 's' -> 0x1F; 't' -> 0x14
            'u' -> 0x16; 'v' -> 0x2F; 'w' -> 0x11; 'x' -> 0x2D; 'y' -> 0x15
            'z' -> 0x2C
            '1' -> 0x02; '2' -> 0x03; '3' -> 0x04; '4' -> 0x05; '5' -> 0x06
            '6' -> 0x07; '7' -> 0x08; '8' -> 0x09; '9' -> 0x0A; '0' -> 0x0B
            ' ' -> SPACE
            '\n', '\r' -> ENTER
            '\b' -> BACKSPACE
            '\t' -> TAB
            else -> null
        }
    }
}
