package dev.stefan.acpc.core.keyboard

/**
 * The keys of the CPC keyboard matrix.
 *
 * The keyboard is a 10 line × 8 bit matrix scanned through the PPI (port C
 * bits 0-3 select the line) and read through the AY-3-8912 port A. Each key
 * is identified by its [line] and [bit]. Joystick 0 shares line 9 and
 * joystick 1 shares line 6 with keyboard keys.
 *
 * Reference: the standard CPC keyboard matrix table (line / bit).
 */
enum class CpcKey(val line: Int, val bit: Int, val label: String) {
    // Line 0
    CURSOR_UP(0, 0, "↑"), CURSOR_RIGHT(0, 1, "→"), CURSOR_DOWN(0, 2, "↓"),
    F9(0, 3, "f9"), F6(0, 4, "f6"), F3(0, 5, "f3"), ENTER(0, 6, "ENTER"), F_DOT(0, 7, "f."),
    // Line 1
    CURSOR_LEFT(1, 0, "←"), COPY(1, 1, "COPY"), F7(1, 2, "f7"), F8(1, 3, "f8"),
    F5(1, 4, "f5"), F1(1, 5, "f1"), F2(1, 6, "f2"), F0(1, 7, "f0"),
    // Line 2
    CLR(2, 0, "CLR"), OPEN_BRACKET(2, 1, "["), RETURN(2, 2, "RETURN"), CLOSE_BRACKET(2, 3, "]"),
    F4(2, 4, "f4"), SHIFT(2, 5, "SHIFT"), BACKSLASH(2, 6, "\\"), CONTROL(2, 7, "CTRL"),
    // Line 3
    CARET(3, 0, "^"), MINUS(3, 1, "-"), AT(3, 2, "@"), P(3, 3, "P"),
    SEMICOLON(3, 4, ";"), COLON(3, 5, ":"), SLASH(3, 6, "/"), PERIOD(3, 7, "."),
    // Line 4
    DIGIT_0(4, 0, "0"), DIGIT_9(4, 1, "9"), O(4, 2, "O"), I(4, 3, "I"),
    L(4, 4, "L"), K(4, 5, "K"), M(4, 6, "M"), COMMA(4, 7, ","),
    // Line 5
    DIGIT_8(5, 0, "8"), DIGIT_7(5, 1, "7"), U(5, 2, "U"), Y(5, 3, "Y"),
    H(5, 4, "H"), J(5, 5, "J"), N(5, 6, "N"), SPACE(5, 7, "SPACE"),
    // Line 6 (shared with joystick 1)
    DIGIT_6(6, 0, "6"), DIGIT_5(6, 1, "5"), R(6, 2, "R"), T(6, 3, "T"),
    G(6, 4, "G"), F(6, 5, "F"), B(6, 6, "B"), V(6, 7, "V"),
    // Line 7
    DIGIT_4(7, 0, "4"), DIGIT_3(7, 1, "3"), E(7, 2, "E"), W(7, 3, "W"),
    S(7, 4, "S"), D(7, 5, "D"), C(7, 6, "C"), X(7, 7, "X"),
    // Line 8
    DIGIT_1(8, 0, "1"), DIGIT_2(8, 1, "2"), ESC(8, 2, "ESC"), Q(8, 3, "Q"),
    TAB(8, 4, "TAB"), A(8, 5, "A"), CAPS_LOCK(8, 6, "CAPS"), Z(8, 7, "Z"),
    // Line 9 (shared with joystick 0)
    JOY0_UP(9, 0, "J0↑"), JOY0_DOWN(9, 1, "J0↓"), JOY0_LEFT(9, 2, "J0←"), JOY0_RIGHT(9, 3, "J0→"),
    JOY0_FIRE2(9, 4, "J0 F2"), JOY0_FIRE1(9, 5, "J0 F1"), JOY0_FIRE3(9, 6, "J0 F3"), DEL(9, 7, "DEL");

    companion object {
        /** Joystick 1 is wired on keyboard line 6 (bits 0-5). */
        val JOY1_UP = DIGIT_6
        val JOY1_DOWN = DIGIT_5
        val JOY1_LEFT = R
        val JOY1_RIGHT = T
        val JOY1_FIRE2 = G
        val JOY1_FIRE1 = F

        private val byName: Map<String, CpcKey> = entries.associateBy { it.name }
        fun fromName(name: String): CpcKey? = byName[name]
    }
}
