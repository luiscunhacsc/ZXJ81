package zxj81;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

final class ZX81Keyboard {
    private enum Action {
        SHIFT,
        RUBOUT,
        QUOTE,
        LEFT,
        DOWN,
        UP,
        RIGHT,
        GENERIC
    }

    private record KeyScan(int row, int mask) {
    }

    private record Pressed(Action action, KeyScan scan) {
    }

    private final ZX81Bus bus;
    private final Map<Integer, KeyScan> keyMap = new HashMap<>();
    private final Map<Integer, Pressed> pressed = new HashMap<>();

    ZX81Keyboard(ZX81Bus bus) {
        this.bus = bus;
        populateKeyMap();
    }

    void clear() {
        pressed.clear();
        bus.clearKeyboard();
    }

    boolean keyPressed(KeyEvent event) {
        int code = event.getKeyCode();
        if (pressed.containsKey(code)) {
            return false;
        }

        if (code == KeyEvent.VK_SHIFT) {
            bus.press(0, 1);
            pressed.put(code, new Pressed(Action.SHIFT, null));
            return true;
        }
        if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) {
            bus.pressRubout();
            pressed.put(code, new Pressed(Action.RUBOUT, null));
            return true;
        }
        if (code == KeyEvent.VK_QUOTE) {
            bus.pressQuote();
            pressed.put(code, new Pressed(Action.QUOTE, null));
            return true;
        }
        if (code == KeyEvent.VK_LEFT) {
            bus.press(3, 16);
            pressed.put(code, new Pressed(Action.LEFT, null));
            return true;
        }
        if (code == KeyEvent.VK_DOWN) {
            bus.press(4, 16);
            pressed.put(code, new Pressed(Action.DOWN, null));
            return true;
        }
        if (code == KeyEvent.VK_UP) {
            bus.press(4, 8);
            pressed.put(code, new Pressed(Action.UP, null));
            return true;
        }
        if (code == KeyEvent.VK_RIGHT) {
            bus.press(4, 4);
            pressed.put(code, new Pressed(Action.RIGHT, null));
            return true;
        }

        KeyScan scan = keyMap.get(code);
        if (scan == null) {
            return false;
        }
        bus.press(scan.row(), scan.mask());
        pressed.put(code, new Pressed(Action.GENERIC, scan));
        return true;
    }

    boolean keyReleased(KeyEvent event) {
        Pressed state = pressed.remove(event.getKeyCode());
        if (state == null) {
            return false;
        }

        switch (state.action()) {
            case SHIFT -> bus.release(0, 1);
            case RUBOUT -> bus.releaseRubout();
            case QUOTE -> bus.releaseQuote();
            case LEFT -> bus.release(3, 16);
            case DOWN -> bus.release(4, 16);
            case UP -> bus.release(4, 8);
            case RIGHT -> bus.release(4, 4);
            case GENERIC -> bus.release(state.scan().row(), state.scan().mask());
        }
        return true;
    }

    private void map(int keyCode, int row, int mask) {
        keyMap.put(keyCode, new KeyScan(row, mask));
    }

    private void populateKeyMap() {
        map(KeyEvent.VK_Z, 0, 2);
        map(KeyEvent.VK_X, 0, 4);
        map(KeyEvent.VK_C, 0, 8);
        map(KeyEvent.VK_V, 0, 16);

        map(KeyEvent.VK_A, 1, 1);
        map(KeyEvent.VK_S, 1, 2);
        map(KeyEvent.VK_D, 1, 4);
        map(KeyEvent.VK_F, 1, 8);
        map(KeyEvent.VK_G, 1, 16);

        map(KeyEvent.VK_Q, 2, 1);
        map(KeyEvent.VK_W, 2, 2);
        map(KeyEvent.VK_E, 2, 4);
        map(KeyEvent.VK_R, 2, 8);
        map(KeyEvent.VK_T, 2, 16);

        map(KeyEvent.VK_1, 3, 1);
        map(KeyEvent.VK_2, 3, 2);
        map(KeyEvent.VK_3, 3, 4);
        map(KeyEvent.VK_4, 3, 8);
        map(KeyEvent.VK_5, 3, 16);

        map(KeyEvent.VK_0, 4, 1);
        map(KeyEvent.VK_9, 4, 2);
        map(KeyEvent.VK_8, 4, 4);
        map(KeyEvent.VK_7, 4, 8);
        map(KeyEvent.VK_6, 4, 16);

        map(KeyEvent.VK_P, 5, 1);
        map(KeyEvent.VK_O, 5, 2);
        map(KeyEvent.VK_I, 5, 4);
        map(KeyEvent.VK_U, 5, 8);
        map(KeyEvent.VK_Y, 5, 16);

        map(KeyEvent.VK_ENTER, 6, 1);
        map(KeyEvent.VK_L, 6, 2);
        map(KeyEvent.VK_K, 6, 4);
        map(KeyEvent.VK_J, 6, 8);
        map(KeyEvent.VK_H, 6, 16);

        map(KeyEvent.VK_SPACE, 7, 1);
        map(KeyEvent.VK_PERIOD, 7, 2);
        map(KeyEvent.VK_COMMA, 7, 2);
        map(KeyEvent.VK_M, 7, 4);
        map(KeyEvent.VK_N, 7, 8);
        map(KeyEvent.VK_B, 7, 16);
    }
}
