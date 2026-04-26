# ZXJ81

ZXJ81 e uma versao Java completa do emulador ZX81, baseada no exemplo
`ZXC81-v9-main`, mas usando o novo core Z80 otimizado por regularidades de
instrucoes.

Attribution: original work by jsanchez; Java port and Z80 core optimization
for ISA regularities by Luís Simões da Cunha, 2026.

## O que inclui

- Core Z80 em `src/main/java/z80core`, mais compacto e com descodificacao
  regular para grupos como `LD r,r`, ALU, `CB`, `DD/FD` e `DD/FD CB`.
- Emulacao ZX81 em `src/main/java/zxj81`:
  - ROM espelhada e mapa ROM/RAM.
  - Teclado matricial ZX81.
  - Video 32x24 a partir de `D_FILE`.
  - Modo FAST/SLOW com NMI.
  - Hooks de LOAD/SAVE compativeis com os ficheiros `.P`.
- ROM em `src/main/resources/zx81.rom`.
- Tapes de exemplo em `tapes`.
- Imagem de referencia do teclado em `assets`.

## Compilar

```powershell
mvn "-Dmaven.repo.local=c:\Users\luisl\Desktop\JavaZX81\.m2\repository" -q -DskipTests compile
```

Smoke test sem abrir a janela:

```powershell
java -cp "target/classes" zxj81.ZX81Smoke
```

## Executar

```powershell
java -cp "target/classes;src/main/resources" zxj81.ZXJ81
```

Tambem podes arrancar diretamente com uma tape:

```powershell
java -cp "target/classes;src/main/resources" zxj81.ZXJ81 tapes\FLIGHT.P
```

## Teclas principais

- `F1`: ajuda rapida
- `F2`: mostra/esconde a imagem do teclado ZX81
- `F5`: abre o seletor de tapes
- `F8`: reset
- `F12`: mostra/esconde a barra de estado, escondida por defeito
- `View > Zoom`: escolhe multiplos exatos de `256x192`, de `1x` a `5x`
- `Backspace`: RUBOUT (`SHIFT+0`)
- Setas: cursores ZX81
- Letras, numeros, espaco e enter: matriz normal do ZX81
