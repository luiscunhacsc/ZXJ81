package zxj81;

import z80core.NotifyOps;
import z80core.Z80;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

public final class ZX81Machine implements NotifyOps {
    static final int CPU_HZ = 3_250_000;
    static final int FRAME_HZ = 50;
    static final int CYCLES_PER_FRAME = CPU_HZ / FRAME_HZ;
    static final int INPUT_SLICES = 4;

    enum SpeedMode {
        SLOW("SLOW (~0.8 MHz)", CYCLES_PER_FRAME / 4),
        FAST("FAST (3.25 MHz)", CYCLES_PER_FRAME),
        TURBO("TURBO (4x)", CYCLES_PER_FRAME * 4);

        private final String label;
        private final int cyclesPerFrame;

        SpeedMode(String label, int cyclesPerFrame) {
            this.label = label;
            this.cyclesPerFrame = cyclesPerFrame;
        }

        String label() {
            return label;
        }

        int cyclesPerFrame() {
            return cyclesPerFrame;
        }
    }

    private final ZX81Bus bus = new ZX81Bus();
    private final Z80 cpu = new Z80(bus, this);
    private final ZX81Tape tape;
    private final byte[] rom;
    private SpeedMode speedMode = SpeedMode.SLOW;
    private int autoRunCounter;

    ZX81Machine(Path baseDir) throws IOException {
        tape = new ZX81Tape(baseDir);
        rom = loadRom();
        hardReset();
    }

    ZX81Bus bus() {
        return bus;
    }

    Z80 cpu() {
        return cpu;
    }

    ZX81Tape tape() {
        return tape;
    }

    byte[] rom() {
        return rom;
    }

    void hardReset() {
        bus.clearRam();
        bus.installRom(rom);
        bus.clearKeyboard();
        bus.reset();
        cpu.reset();
        autoRunCounter = 0;
    }

    boolean loadTape(Path path, boolean requestAutoRun) {
        hardReset();
        tape.setFilename(path.getFileName().toString());
        boolean loaded = tape.loadSelectedTape() && tape.inject(cpu, bus);
        if (loaded && requestAutoRun) {
            requestAutoRun();
        }
        return loaded;
    }

    void requestAutoRun() {
        autoRunCounter = 25;
    }

    SpeedMode speedMode() {
        return speedMode;
    }

    void setSpeedMode(SpeedMode speedMode) {
        this.speedMode = Objects.requireNonNull(speedMode, "speedMode");
    }

    void runFrame() {
        int frameCycles = speedMode.cyclesPerFrame();
        int sliceCycles = frameCycles / INPUT_SLICES;
        int remainder = frameCycles % INPUT_SLICES;

        for (int slice = 0; slice < INPUT_SLICES; slice++) {
            if (bus.isFastMode() && cpu.isHalted()) {
                cpu.triggerNMI();
            }

            runForCycles(sliceCycles + (slice == INPUT_SLICES - 1 ? remainder : 0));
            serviceAutoRun();
        }
    }

    private void runForCycles(int cycles) {
        long target = bus.getTstates() + cycles;
        int guard = 0;
        while (bus.getTstates() < target && guard++ < 1_000_000) {
            cpu.execute();
            tape.handleRomHooks(cpu, bus);
        }
    }

    private void serviceAutoRun() {
        if (autoRunCounter <= 0) {
            return;
        }

        bus.clearKeyboard();
        if (autoRunCounter > 14) {
            bus.press(2, 8);       // R = RUN in K mode.
        } else if (autoRunCounter > 2) {
            bus.press(6, 1);       // ENTER / NEW LINE.
        }
        autoRunCounter--;
    }

    private static byte[] loadRom() throws IOException {
        try (InputStream in = ZX81Machine.class.getResourceAsStream("/zx81.rom")) {
            Objects.requireNonNull(in, "Missing zx81.rom resource");
            byte[] data = in.readAllBytes();
            if (data.length < ZX81Bus.ROM_SIZE) {
                throw new IOException("zx81.rom is too small: " + data.length);
            }
            return data;
        }
    }

    @Override
    public int breakpoint(int address, int opcode) {
        return opcode;
    }

    @Override
    public void execDone() {
    }
}
