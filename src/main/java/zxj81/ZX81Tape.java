package zxj81;

import z80core.Z80;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ZX81Tape {
    private static final int PFILE_BASE = 0x4009;
    private static final int ERR_NR = 0x4000;
    private static final int MAIN_LOOP = 0x0207;
    private static final int REPORT_ERROR = 0x0058;
    private static final int SAVE_AFTER_NAME = 0x02F9;
    private static final int LOAD_AFTER_NAME = 0x0343;
    private static final int REPORT_D = 0x0C;
    private static final int REPORT_F = 0x0E;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path baseDir;
    private final Path tapesDir;
    private final Path logFile;
    private byte[] loadedTape = new byte[0];
    private String currentFilename = "";

    ZX81Tape(Path baseDir) {
        this.baseDir = baseDir;
        this.tapesDir = baseDir.resolve("tapes");
        this.logFile = baseDir.resolve("tape_log.txt");
    }

    void setFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        currentFilename = normalizeSnapshotFilename(filename);
        log("SETUP: Filename set to '%s'", currentFilename);
    }

    String currentFilename() {
        return currentFilename;
    }

    List<Path> listTapes() {
        if (!Files.isDirectory(tapesDir)) {
            return List.of();
        }
        try {
            List<Path> tapes = new ArrayList<>();
            try (var stream = Files.list(tapesDir)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".p"))
                    .forEach(tapes::add);
            }
            tapes.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
            return tapes;
        } catch (IOException ex) {
            log("LIST: Failed to list tapes: %s", ex.getMessage());
            return List.of();
        }
    }

    boolean loadSelectedTape() {
        if (currentFilename.isBlank()) {
            log("LOAD: No filename defined.");
            return false;
        }

        Path path = tapesDir.resolve(currentFilename);
        if (!Files.exists(path)) {
            path = baseDir.resolve(currentFilename);
        }

        try {
            loadedTape = Files.readAllBytes(path);
            if (loadedTape.length < 14) {
                log("LOAD: File too small (%d bytes)", loadedTape.length);
                loadedTape = new byte[0];
                return false;
            }
            int eLine = (loadedTape[0x0b] & 0xff) | ((loadedTape[0x0c] & 0xff) << 8);
            int expectedLength = eLine > PFILE_BASE ? eLine - PFILE_BASE : 0;
            log("LOAD: Success. '%s' (%d bytes) ready. E_LINE=%04X expected_len=%d",
                currentFilename, loadedTape.length, eLine, expectedLength);
            return true;
        } catch (IOException ex) {
            log("LOAD: Error opening '%s': %s", path, ex.getMessage());
            loadedTape = new byte[0];
            return false;
        }
    }

    boolean inject(Z80 cpu, ZX81Bus bus) {
        if (loadedTape.length == 0) {
            return false;
        }
        if (PFILE_BASE + loadedTape.length > ZX81Bus.MEMORY_SIZE) {
            log("INJECT: Snapshot too large (%d bytes)", loadedTape.length);
            return false;
        }

        byte[] memory = bus.memory();
        System.arraycopy(loadedTape, 0, memory, PFILE_BASE, loadedTape.length);

        int sp = 0x8000 - 4;
        bus.writeRaw8(0x4000, 0xff);
        bus.writeRaw8(0x4001, 0x80);
        bus.writeRaw16(0x4002, sp);
        bus.writeRaw16(0x4004, sp + 4);
        bus.writeRaw8(0x4006, 0x00);
        bus.writeRaw8(0x4007, 0xfe);
        bus.writeRaw8(0x4008, 0xff);
        bus.writeRaw8(sp, 0x76);
        bus.writeRaw8(sp + 1, 0x06);
        bus.writeRaw8(sp + 2, 0x00);
        bus.writeRaw8(sp + 3, 0x3e);

        cpu.setRegAF(0x0B00);
        cpu.setRegBC(0x0002);
        cpu.setRegDE(0x439B);
        cpu.setRegHL(0x4399);
        cpu.setRegAFx(0xECA9);
        cpu.setRegBCx(0x8102);
        cpu.setRegDEx(0x002B);
        cpu.setRegHLx(0x0000);
        cpu.setRegIX(0x0281);
        cpu.setRegIY(0x4000);
        cpu.setRegI(0x1E);
        cpu.setRegR(0xDD);
        cpu.setIM(Z80.IntMode.IM2);
        cpu.setIFF1(false);
        cpu.setIFF2(false);
        cpu.setPendingEI(false);
        cpu.setRegSP(sp);
        cpu.setRegPC(MAIN_LOOP);
        cpu.setHalted(false);
        log("INJECT: Loaded '%s' into RAM. PC=%04X SP=%04X", currentFilename, MAIN_LOOP, sp);
        return true;
    }

    void handleRomHooks(Z80 cpu, ZX81Bus bus) {
        int pc = cpu.getRegPC();
        if (pc == SAVE_AFTER_NAME) {
            if ((cpu.getFlags() & 1) != 0) {
                return;
            }
            String decoded = extractName(cpu, bus);
            if (decoded == null || decoded.isBlank()) {
                reportError(cpu, bus, REPORT_F);
                return;
            }
            String normalized = normalizeSnapshotFilename(decoded);
            if (!save(normalized, bus)) {
                reportError(cpu, bus, REPORT_D);
                return;
            }
            finishBasicCommand(cpu);
            return;
        }

        if (pc == LOAD_AFTER_NAME) {
            String decoded = extractName(cpu, bus);
            if (decoded == null) {
                reportError(cpu, bus, REPORT_D);
                return;
            }
            if (!decoded.isBlank()) {
                setFilename(decoded);
            } else if (currentFilename.isBlank()) {
                reportError(cpu, bus, REPORT_F);
                return;
            }
            if (!loadSelectedTape() || !inject(cpu, bus)) {
                reportError(cpu, bus, REPORT_D);
            }
        }
    }

    private boolean save(String name, ZX81Bus bus) {
        int eLine = bus.readRaw16(ZX81Bus.SYSVAR_E_LINE);
        if (eLine <= PFILE_BASE) {
            log("SAVE: Invalid E_LINE=%04X", eLine);
            return false;
        }

        int snapshotSize = eLine - PFILE_BASE;
        Path path = tapesDir.resolve(name);
        try {
            Files.createDirectories(tapesDir);
            Files.write(path, java.util.Arrays.copyOfRange(bus.memory(), PFILE_BASE, PFILE_BASE + snapshotSize));
            currentFilename = name;
            log("SAVE: Wrote '%s' (%d bytes)", name, snapshotSize);
            return true;
        } catch (IOException ex) {
            log("SAVE: Error creating '%s': %s", path, ex.getMessage());
            return false;
        }
    }

    private String extractName(Z80 cpu, ZX81Bus bus) {
        if (cpu.getRegB() == 0x00 && cpu.getRegC() == 0xff) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        int address = cpu.getRegDE();
        for (int i = 0; i < 127; i++) {
            int raw = bus.readRaw8(address++);
            boolean last = (raw & 0x80) != 0;
            char ch = zx81CharToAscii(raw);
            if (ch == 0) {
                return null;
            }
            out.append(ch);
            if (last) {
                return out.toString();
            }
        }
        return null;
    }

    private static char zx81CharToAscii(int value) {
        int v = value & 0x7f;
        if (v >= 0x26 && v <= 0x3f) {
            return (char) ('A' + (v - 0x26));
        }
        if (v == 0x1b) {
            return '.';
        }
        if (v >= 0x1c && v <= 0x25) {
            return (char) ('0' + (v - 0x1c));
        }
        return 0;
    }

    private static String normalizeSnapshotFilename(String input) {
        String cleaned = Path.of(input.trim()).getFileName().toString().toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(cleaned.length() + 2);
        boolean sawDot = false;
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.') {
                out.append(ch);
                sawDot |= ch == '.';
            }
        }
        if (out.length() == 0) {
            return "";
        }
        if (!sawDot) {
            out.append(".P");
        }
        return out.toString();
    }

    private void finishBasicCommand(Z80 cpu) {
        cpu.setRegPC(MAIN_LOOP);
        cpu.setHalted(false);
    }

    private void reportError(Z80 cpu, ZX81Bus bus, int reportCode) {
        bus.writeRaw8(ERR_NR, reportCode);
        cpu.setRegL(reportCode);
        cpu.setRegPC(REPORT_ERROR);
        cpu.setHalted(false);
    }

    void log(String format, Object... args) {
        String line = "[" + LocalTime.now().format(LOG_TIME) + "] " + String.format(Locale.ROOT, format, args);
        try {
            Files.writeString(logFile, line + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Tape logging is diagnostic only; emulation should not stop if it fails.
        }
    }
}
