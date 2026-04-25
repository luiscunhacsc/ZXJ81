package zxj81;

import z80core.MemIoOps;

import java.util.Arrays;

final class ZX81Bus extends MemIoOps {
    static final int MEMORY_SIZE = 0x10000;
    static final int ROM_SIZE = 0x2000;
    static final int RAM_START = 0x4000;
    static final int SYSVAR_MODE = 0x4006;
    static final int SYSVAR_D_FILE = 0x400C;
    static final int SYSVAR_E_LINE = 0x4014;
    static final int SYSVAR_LAST_K_ROW = 0x4025;
    static final int SYSVAR_LAST_K_COL = 0x4026;

    private static final int[] KEYBOARD_ROW_PORTS = {0xFE, 0xFD, 0xFB, 0xF7, 0xEF, 0xDF, 0xBF, 0x7F};

    private final byte[] memory = new byte[MEMORY_SIZE];
    private final int[] keyboard = new int[8];
    private long tstates;

    ZX81Bus() {
        super(0, 0);
        clearKeyboard();
    }

    byte[] memory() {
        return memory;
    }

    void installRom(byte[] rom) {
        if (rom.length < ROM_SIZE) {
            throw new IllegalArgumentException("ZX81 ROM must contain at least 8192 bytes");
        }
        System.arraycopy(rom, 0, memory, 0x0000, ROM_SIZE);
        System.arraycopy(rom, 0, memory, 0x2000, ROM_SIZE);
        memory[0x02B5] = (byte) 0xC9;
        memory[0x22B5] = (byte) 0xC9;
    }

    void clearRam() {
        Arrays.fill(memory, RAM_START, MEMORY_SIZE, (byte) 0);
    }

    int readRaw8(int address) {
        return memory[address & 0xffff] & 0xff;
    }

    int readRaw16(int address) {
        int low = readRaw8(address);
        int high = readRaw8(address + 1);
        return low | (high << 8);
    }

    void writeRaw8(int address, int value) {
        memory[address & 0xffff] = (byte) value;
    }

    void writeRaw16(int address, int value) {
        writeRaw8(address, value);
        writeRaw8(address + 1, value >>> 8);
    }

    boolean isFastMode() {
        return readRaw8(SYSVAR_MODE) == 0;
    }

    void clearKeyboard() {
        Arrays.fill(keyboard, 0xff);
        updateKeyboardSystemVars();
    }

    void press(int row, int mask) {
        if (row >= 0 && row < keyboard.length) {
            keyboard[row] &= ~mask;
            updateKeyboardSystemVars();
        }
    }

    void release(int row, int mask) {
        if (row >= 0 && row < keyboard.length) {
            keyboard[row] |= mask;
            updateKeyboardSystemVars();
        }
    }

    void setPressed(int row, int mask, boolean pressed) {
        if (pressed) {
            press(row, mask);
        } else {
            release(row, mask);
        }
    }

    void pressRubout() {
        press(0, 1);
        press(4, 1);
    }

    void releaseRubout() {
        release(0, 1);
        release(4, 1);
    }

    void pressQuote() {
        press(0, 1);
        press(5, 1);
    }

    void releaseQuote() {
        release(0, 1);
        release(5, 1);
    }

    private void updateKeyboardSystemVars() {
        boolean shiftDown = (keyboard[0] & 1) == 0;
        int rowByte = 0xff;
        int colByte = 0xff;
        boolean found = false;

        for (int row = 0; row < 8 && !found; row++) {
            int rowState = keyboard[row] & 0x1f;
            for (int bit = 0; bit < 5; bit++) {
                if ((rowState & (1 << bit)) != 0) {
                    continue;
                }
                if (row == 0 && bit == 0) {
                    continue;
                }
                rowByte = KEYBOARD_ROW_PORTS[row];
                colByte = 0xff & ~(1 << (bit + 1));
                if (shiftDown) {
                    colByte &= ~1;
                }
                found = true;
                break;
            }
        }

        if (!found && shiftDown) {
            rowByte = 0xff;
            colByte = 0xfe;
        }

        writeRaw8(SYSVAR_LAST_K_ROW, rowByte);
        writeRaw8(SYSVAR_LAST_K_COL, colByte);
    }

    @Override
    public int fetchOpcode(int address) {
        tstates += 4;
        return readRaw8(address);
    }

    @Override
    public int peek8(int address) {
        tstates += 3;
        return readRaw8(address);
    }

    @Override
    public void poke8(int address, int value) {
        tstates += 3;
        if ((address & 0xffff) >= RAM_START) {
            writeRaw8(address, value);
        }
    }

    @Override
    public int peek16(int address) {
        int lsb = peek8(address);
        int msb = peek8(address + 1);
        return (msb << 8) | lsb;
    }

    @Override
    public void poke16(int address, int word) {
        poke8(address, word);
        poke8(address + 1, word >>> 8);
    }

    @Override
    public int inPort(int port) {
        tstates += 4;
        if ((port & 0xff) == 0xfe) {
            int rowSelect = port >>> 8;
            int data = 0x1f;
            for (int row = 0; row < 8; row++) {
                if ((rowSelect & (1 << row)) == 0) {
                    data &= keyboard[row] & 0x1f;
                }
            }
            return data;
        }
        return 0xff;
    }

    @Override
    public void outPort(int port, int value) {
        tstates += 4;
    }

    @Override
    public void addressOnBus(int address, int tstates) {
        this.tstates += tstates;
    }

    @Override
    public void interruptHandlingTime(int tstates) {
        this.tstates += tstates;
    }

    @Override
    public long getTstates() {
        return tstates;
    }

    @Override
    public void reset() {
        tstates = 0;
    }
}
