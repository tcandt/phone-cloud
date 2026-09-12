package com.android.helper.control;

import java.util.HashMap;
import java.util.Map;

/* JADX INFO: loaded from: classes.dex */
public final class KeyComposition {
    private static final Map<Character, String> COMPOSITION_MAP = createDecompositionMap();
    private static final String KEY_DEAD_ACUTE = "́";
    private static final String KEY_DEAD_CIRCUMFLEX = "̂";
    private static final String KEY_DEAD_GRAVE = "̀";
    private static final String KEY_DEAD_TILDE = "̃";
    private static final String KEY_DEAD_UMLAUT = "̈";

    private KeyComposition() {
    }

    public static String decompose(char c) {
        return COMPOSITION_MAP.get(Character.valueOf(c));
    }

    private static String grave(char c) {
        return KEY_DEAD_GRAVE + c;
    }

    private static String acute(char c) {
        return KEY_DEAD_ACUTE + c;
    }

    private static String circumflex(char c) {
        return KEY_DEAD_CIRCUMFLEX + c;
    }

    private static String tilde(char c) {
        return KEY_DEAD_TILDE + c;
    }

    private static String umlaut(char c) {
        return KEY_DEAD_UMLAUT + c;
    }

    private static Map<Character, String> createDecompositionMap() {
        HashMap map = new HashMap();
        map.put((char) 192, grave('A'));
        map.put((char) 200, grave('E'));
        map.put((char) 204, grave('I'));
        map.put((char) 210, grave('O'));
        map.put((char) 217, grave('U'));
        map.put((char) 224, grave('a'));
        map.put((char) 232, grave('e'));
        map.put((char) 236, grave('i'));
        map.put((char) 242, grave('o'));
        map.put((char) 249, grave('u'));
        map.put((char) 504, grave('N'));
        map.put((char) 505, grave('n'));
        map.put((char) 7808, grave('W'));
        map.put((char) 7809, grave('w'));
        map.put((char) 7922, grave('Y'));
        map.put((char) 7923, grave('y'));
        map.put((char) 193, acute('A'));
        map.put((char) 201, acute('E'));
        map.put((char) 205, acute('I'));
        map.put((char) 211, acute('O'));
        map.put((char) 218, acute('U'));
        map.put((char) 221, acute('Y'));
        map.put((char) 225, acute('a'));
        map.put((char) 233, acute('e'));
        map.put((char) 237, acute('i'));
        map.put((char) 243, acute('o'));
        map.put((char) 250, acute('u'));
        map.put((char) 253, acute('y'));
        map.put((char) 262, acute('C'));
        map.put((char) 263, acute('c'));
        map.put((char) 313, acute('L'));
        map.put((char) 314, acute('l'));
        map.put((char) 323, acute('N'));
        map.put((char) 324, acute('n'));
        map.put((char) 340, acute('R'));
        map.put((char) 341, acute('r'));
        map.put((char) 346, acute('S'));
        map.put((char) 347, acute('s'));
        map.put((char) 377, acute('Z'));
        map.put((char) 378, acute('z'));
        map.put((char) 500, acute('G'));
        map.put((char) 501, acute('g'));
        map.put((char) 7688, acute((char) 199));
        map.put((char) 7689, acute((char) 231));
        map.put((char) 7728, acute('K'));
        map.put((char) 7729, acute('k'));
        map.put((char) 7742, acute('M'));
        map.put((char) 7743, acute('m'));
        map.put((char) 7764, acute('P'));
        map.put((char) 7765, acute('p'));
        map.put((char) 7810, acute('W'));
        map.put((char) 7811, acute('w'));
        map.put((char) 194, circumflex('A'));
        map.put((char) 202, circumflex('E'));
        map.put((char) 206, circumflex('I'));
        map.put((char) 212, circumflex('O'));
        map.put((char) 219, circumflex('U'));
        map.put((char) 226, circumflex('a'));
        map.put((char) 234, circumflex('e'));
        map.put((char) 238, circumflex('i'));
        map.put((char) 244, circumflex('o'));
        map.put((char) 251, circumflex('u'));
        map.put((char) 264, circumflex('C'));
        map.put((char) 265, circumflex('c'));
        map.put((char) 284, circumflex('G'));
        map.put((char) 285, circumflex('g'));
        map.put((char) 292, circumflex('H'));
        map.put((char) 293, circumflex('h'));
        map.put((char) 308, circumflex('J'));
        map.put((char) 309, circumflex('j'));
        map.put((char) 348, circumflex('S'));
        map.put((char) 349, circumflex('s'));
        map.put((char) 372, circumflex('W'));
        map.put((char) 373, circumflex('w'));
        map.put((char) 374, circumflex('Y'));
        map.put((char) 375, circumflex('y'));
        map.put((char) 7824, circumflex('Z'));
        map.put((char) 7825, circumflex('z'));
        map.put((char) 195, tilde('A'));
        map.put((char) 209, tilde('N'));
        map.put((char) 213, tilde('O'));
        map.put((char) 227, tilde('a'));
        map.put((char) 241, tilde('n'));
        map.put((char) 245, tilde('o'));
        map.put((char) 296, tilde('I'));
        map.put((char) 297, tilde('i'));
        map.put((char) 360, tilde('U'));
        map.put((char) 361, tilde('u'));
        map.put((char) 7868, tilde('E'));
        map.put((char) 7869, tilde('e'));
        map.put((char) 7928, tilde('Y'));
        map.put((char) 7929, tilde('y'));
        map.put((char) 196, umlaut('A'));
        map.put((char) 203, umlaut('E'));
        map.put((char) 207, umlaut('I'));
        map.put((char) 214, umlaut('O'));
        map.put((char) 220, umlaut('U'));
        map.put((char) 228, umlaut('a'));
        map.put((char) 235, umlaut('e'));
        map.put((char) 239, umlaut('i'));
        map.put((char) 246, umlaut('o'));
        map.put((char) 252, umlaut('u'));
        map.put((char) 255, umlaut('y'));
        map.put((char) 376, umlaut('Y'));
        map.put((char) 7718, umlaut('H'));
        map.put((char) 7719, umlaut('h'));
        map.put((char) 7812, umlaut('W'));
        map.put((char) 7813, umlaut('w'));
        map.put((char) 7820, umlaut('X'));
        map.put((char) 7821, umlaut('x'));
        map.put((char) 7831, umlaut('t'));
        return map;
    }
}
