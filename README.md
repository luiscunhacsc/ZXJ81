# ZXJ81

ZXJ81 is a complete Java version of the ZX81 emulator, based on the
`ZXC81-v9-main` example, but using the new Z80 core optimized around
instruction-set regularities.

Attribution: original work by jsanchez; Java port and Z80 core optimization
for ISA regularities by Luís Simões da Cunha, 2026.

## What It Includes

- Z80 core in `src/main/java/z80core`, made more compact with regular decoding
  for groups such as `LD r,r`, ALU, `CB`, `DD/FD`, and `DD/FD CB`.
- ZX81 emulation in `src/main/java/zxj81`:
  - Mirrored ROM and ROM/RAM map.
  - ZX81 keyboard matrix.
  - 32x24 video generated from `D_FILE`.
  - FAST/SLOW mode with NMI.
  - LOAD/SAVE hooks compatible with `.P` files.
- ROM in `src/main/resources/zx81.rom`.
- Example tapes in `tapes`.
- Keyboard reference image in `assets`.

## Build

```powershell
mvn "-Dmaven.repo.local=c:\Users\luisl\Desktop\JavaZX81\.m2\repository" -q -DskipTests compile
```

Smoke test without opening the window:

```powershell
java -cp "target/classes" zxj81.ZX81Smoke
```

## Run

```powershell
java -cp "target/classes;src/main/resources" zxj81.ZXJ81
```

You can also start directly with a tape:

```powershell
java -cp "target/classes;src/main/resources" zxj81.ZXJ81 tapes\FLIGHT.P
```

## Main Keys

- `F1`: quick help
- `F2`: show/hide the ZX81 keyboard image
- `F5`: open the tape selector
- `F8`: reset
- `F12`: show/hide the status bar
- `Backspace`: RUBOUT (`SHIFT+0`)
- Arrow keys: ZX81 cursors
- Letters, numbers, space, and enter: normal ZX81 matrix
