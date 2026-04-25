//-----------------------------------------------------------------------------
// Title:        Z80 Core in Java
// Version:      2026 - revised and optimized version
//
// Original base:
//               Java Emulator of a Sinclair ZX Spectrum 48K
//               Class: Z80.java
//               Copyright (c) 2004
//               Original author: Alberto Sánchez Terrén
//
// Subsequent development:
//               Version 2.0
//               Author: José Luis Sánchez Villanueva
//
// Review, adaptation and optimization:
//               Luís Simões da Cunha, 2026
//
// Description:
//               This class implements the internal structure of the Zilog Z80
//               microprocessor and the execution of its instruction set.
//
//               The present core is based on the original code identified
//               above, subsequently developed and improved by José Luis
//               Sánchez Villanueva. In 2026, Luís Simões da Cunha carried out
//               a substantial optimization of the internal organization of the
//               code, taking advantage of the regularities, patterns and
//               symmetries present in the Z80 instruction set.
//
//               The main goal of this reorganization was to make instruction
//               execution and decoding more systematic, compact and easier to
//               maintain, while preserving the functional logic of the original
//               core and its focus on faithful Z80 emulation.
//-----------------------------------------------------------------------------
package z80core;

import java.util.BitSet;

public class Z80 {

    private MemIoOps MemIoImpl;
    private NotifyOps NotifyImpl;
    // Se está ejecutando una instrucción DDXX, EDXX o FDXX 
    // Solo puede (debería) contener uno de 4 valores [0x00, 0xDD, 0xED, 0xFD]
    private int prefixOpcode = 0x00;
    // Subsistema de notificaciones
    private boolean execDone = false;
    // Posiciones de los flags
    private static final int CARRY_MASK = 0x01;
    private static final int ADDSUB_MASK = 0x02;
    private static final int PARITY_MASK = 0x04;
    private static final int OVERFLOW_MASK = 0x04; // alias de PARITY_MASK
    private static final int BIT3_MASK = 0x08;
    private static final int HALFCARRY_MASK = 0x10;
    private static final int BIT5_MASK = 0x20;
    private static final int ZERO_MASK = 0x40;
    private static final int SIGN_MASK = 0x80;
    // Máscaras de conveniencia
    private static final int FLAG_53_MASK = BIT5_MASK | BIT3_MASK;
    private static final int FLAG_SZ_MASK = SIGN_MASK | ZERO_MASK;
    private static final int FLAG_SZHN_MASK = FLAG_SZ_MASK | HALFCARRY_MASK | ADDSUB_MASK;
    private static final int FLAG_SZP_MASK = FLAG_SZ_MASK | PARITY_MASK;
    private static final int FLAG_SZHP_MASK = FLAG_SZP_MASK | HALFCARRY_MASK;
    // Acumulador y resto de registros de 8 bits
    private int regA, regB, regC, regD, regE, regH, regL;
    // Flags sIGN, zERO, 5, hALFCARRY, 3, pARITY y ADDSUB (n)
    private int sz5h3pnFlags;
    // El flag Carry es el único que se trata aparte
    private boolean carryFlag;
    /* Flags para indicar la modificación del registro F en la instrucción actual
     * y en la anterior.
     * Son necesarios para emular el comportamiento de los bits 3 y 5 del
     * registro F con las instrucciones CCF/SCF.
     *
     * http://www.worldofspectrum.org/forums/showthread.php?t=41834
     * http://www.worldofspectrum.org/forums/showthread.php?t=41704
     *
     * Thanks to Patrik Rak for his tests and investigations.
     */
    private boolean flagQ, lastFlagQ;
    // Acumulador alternativo y flags -- 8 bits
    private int regAx;
    private int regFx;
    // Registros alternativos
    private int regBx, regCx, regDx, regEx, regHx, regLx;
    // Registros de propósito específico
    // *PC -- Program Counter -- 16 bits*
    private int regPC;
    // *IX -- Registro de índice -- 16 bits*
    private int regIX;
    // *IY -- Registro de índice -- 16 bits*
    private int regIY;
    // *SP -- Stack Pointer -- 16 bits*
    private int regSP;
    // *I -- Vector de interrupción -- 8 bits*
    private int regI;
    // *R -- Refresco de memoria -- 7 bits*
    private int regR;
    // *R7 -- Refresco de memoria -- 1 bit* (bit superior de R)
    private boolean regRbit7;
    //Flip-flops de interrupción
    private boolean ffIFF1 = false;
    private boolean ffIFF2 = false;
    // EI solo habilita las interrupciones DESPUES de ejecutar la
    // siguiente instrucción (excepto si la siguiente instrucción es un EI...)
    private boolean pendingEI = false;
    // Estado de la línea NMI
    private boolean activeNMI = false;
    // Si está activa la línea INT
    // En el 48 y los +2a/+3 la línea INT se activa durante 32 ciclos de reloj
    // En el 128 y +2, se activa 36 ciclos de reloj
    private boolean activeINT = false;
    // Modos de interrupción
    public enum IntMode { IM0, IM1, IM2 };
    // Modo de interrupción
    private IntMode modeINT = IntMode.IM0;
    // halted == true cuando la CPU está ejecutando un HALT (28/03/2010)
    private boolean halted = false;
    // pinReset == true, se ha producido un reset a través de la patilla
    private boolean pinReset = false;
    /*
     * Registro interno que usa la CPU de la siguiente forma
     *
     * ADD HL,xx      = Valor del registro H antes de la suma
     * LD r,(IX/IY+d) = Byte superior de la suma de IX/IY+d
     * JR d           = Byte superior de la dirección de destino del salto
     *
     * 04/12/2008     No se vayan todavía, aún hay más. Con lo que se ha
     *                implementado hasta ahora parece que funciona. El resto de
     *                la historia está contada en:
     *                http://zx.pk.ru/attachment.php?attachmentid=2989
     *
     * 25/09/2009     Se ha completado la emulación de MEMPTR. A señalar que
     *                no se puede comprobar si MEMPTR se ha emulado bien hasta
     *                que no se emula el comportamiento del registro en las
     *                instrucciones CPI y CPD. Sin ello, todos los tests de
     *                z80tests.tap fallarán aunque se haya emulado bien al
     *                registro en TODAS las otras instrucciones.
     *                Shit yourself, little parrot.
     */
    private int memptr;

    /* Algunos flags se precalculan para un tratamiento más rápido
     * Concretamente, SIGN, ZERO, los bits 3, 5, PARITY y ADDSUB:
     * sz53n_addTable tiene el ADDSUB flag a 0 y paridad sin calcular
     * sz53pn_addTable tiene el ADDSUB flag a 0 y paridad calculada
     * sz53n_subTable tiene el ADDSUB flag a 1 y paridad sin calcular
     * sz53pn_subTable tiene el ADDSUB flag a 1 y paridad calculada
     * El resto de bits están a 0 en las cuatro tablas lo que es
     * importante para muchas operaciones que ponen ciertos flags a 0 por real
     * decreto. Si lo ponen a 1 por el mismo método basta con hacer un OR con
     * la máscara correspondiente.
     */
    private static final int sz53n_addTable[] = new int[256];
    private static final int sz53pn_addTable[] = new int[256];
    private static final int sz53n_subTable[] = new int[256];
    private static final int sz53pn_subTable[] = new int[256];

    static {
        boolean evenBits;

        for (int idx = 0; idx < 256; idx++) {
            if (idx > 0x7f) {
                sz53n_addTable[idx] |= SIGN_MASK;
            }

            evenBits = true;
            for (int mask = 0x01; mask < 0x100; mask <<= 1) {
                if ((idx & mask) != 0) {
                    evenBits = !evenBits;
                }
            }

            sz53n_addTable[idx] |= (idx & FLAG_53_MASK);
            sz53n_subTable[idx] = sz53n_addTable[idx] | ADDSUB_MASK;

            if (evenBits) {
                sz53pn_addTable[idx] = sz53n_addTable[idx] | PARITY_MASK;
                sz53pn_subTable[idx] = sz53n_subTable[idx] | PARITY_MASK;
            } else {
                sz53pn_addTable[idx] = sz53n_addTable[idx];
                sz53pn_subTable[idx] = sz53n_subTable[idx];
            }
        }

        sz53n_addTable[0] |= ZERO_MASK;
        sz53pn_addTable[0] |= ZERO_MASK;
        sz53n_subTable[0] |= ZERO_MASK;
        sz53pn_subTable[0] |= ZERO_MASK;
    }

    // Un true en una dirección indica que se debe notificar que se va a
    // ejecutar la instrucción que está en esa direción.
    private final BitSet breakpointAt = new BitSet(65536);

    // Constructor de la clase
    public Z80(MemIoOps memory, NotifyOps notify) {
        MemIoImpl = memory;
        NotifyImpl = notify;
        execDone = false;
        breakpointAt.clear();
        reset();
    }

    public void setMemIoHandler(MemIoOps memIo) {
        MemIoImpl = memIo;
    }

    public void setNotifyHandler(NotifyOps notify) {
        NotifyImpl = notify;
    }

    // Acceso a registros de 8 bits
    public final int getRegA() {
        return regA;
    }

    public final void setRegA(int value) {
        regA = value & 0xff;
    }

    public final int getRegB() {
        return regB;
    }

    public final void setRegB(int value) {
        regB = value & 0xff;
    }

    public final int getRegC() {
        return regC;
    }

    public final void setRegC(int value) {
        regC = value & 0xff;
    }

    public final int getRegD() {
        return regD;
    }

    public final void setRegD(int value) {
        regD = value & 0xff;
    }

    public final int getRegE() {
        return regE;
    }

    public final void setRegE(int value) {
        regE = value & 0xff;
    }

    public final int getRegH() {
        return regH;
    }

    public final void setRegH(int value) {
        regH = value & 0xff;
    }

    public final int getRegL() {
        return regL;
    }

    public final void setRegL(int value) {
        regL = value & 0xff;
    }

    // Acceso a registros alternativos de 8 bits
    public final int getRegAx() {
        return regAx;
    }

    public final void setRegAx(int value) {
        regAx = value & 0xff;
    }

    public final int getRegFx() {
        return regFx;
    }

    public final void setRegFx(int value) {
        regFx = value & 0xff;
    }

    public final int getRegBx() {
        return regBx;
    }

    public final void setRegBx(int value) {
        regBx = value & 0xff;
    }

    public final int getRegCx() {
        return regCx;
    }

    public final void setRegCx(int value) {
        regCx = value & 0xff;
    }

    public final int getRegDx() {
        return regDx;
    }

    public final void setRegDx(int value) {
        regDx = value & 0xff;
    }

    public final int getRegEx() {
        return regEx;
    }

    public final void setRegEx(int value) {
        regEx = value & 0xff;
    }

    public final int getRegHx() {
        return regHx;
    }

    public final void setRegHx(int value) {
        regHx = value & 0xff;
    }

    public final int getRegLx() {
        return regLx;
    }

    public final void setRegLx(int value) {
        regLx = value & 0xff;
    }

    // Acceso a registros de 16 bits
    public final int getRegAF() {
        return (regA << 8) | (carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags);
    }

    public final void setRegAF(int word) {
        regA = (word >>> 8) & 0xff;

        sz5h3pnFlags = word & 0xfe;
        carryFlag = (word & CARRY_MASK) != 0;
    }

    public final int getRegAFx() {
        return (regAx << 8) | regFx;
    }

    public final void setRegAFx(int word) {
        regAx = (word >>> 8) & 0xff;
        regFx = word & 0xff;
    }

    public final int getRegBC() {
        return (regB << 8) | regC;
    }

    public final void setRegBC(int word) {
        regB = (word >>> 8) & 0xff;
        regC = word & 0xff;
    }

    private void incRegBC() {
        if (++regC < 0x100) {
            return;
        }

        regC = 0;

        if (++regB < 0x100) {
            return;
        }

        regB = 0;
    }

    private void decRegBC() {
        if (--regC >= 0) {
            return;
        }

        regC = 0xff;

        if (--regB >= 0) {
            return;
        }

        regB = 0xff;
    }

    public final int getRegBCx() {
        return (regBx << 8) | regCx;
    }

    public final void setRegBCx(int word) {
        regBx = (word >>> 8) & 0xff;
        regCx = word & 0xff;
    }

    public final int getRegDE() {
        return (regD << 8) | regE;
    }

    public final void setRegDE(int word) {
        regD = (word >>> 8) & 0xff;
        regE = word & 0xff;
    }

    private void incRegDE() {
        if (++regE < 0x100) {
            return;
        }

        regE = 0;

        if (++regD < 0x100) {
            return;
        }

        regD = 0;
    }

    private void decRegDE() {
        if (--regE >= 0) {
            return;
        }

        regE = 0xff;

        if (--regD >= 0) {
            return;
        }

        regD = 0xff;
    }

    public final int getRegDEx() {
        return (regDx << 8) | regEx;
    }

    public final void setRegDEx(int word) {
        regDx = (word >>> 8) & 0xff;
        regEx = word & 0xff;
    }

    public final int getRegHL() {
        return (regH << 8) | regL;
    }

    public final void setRegHL(int word) {
        regH = (word >>> 8) & 0xff;
        regL = word & 0xff;
    }

    /* Las funciones incRegXX y decRegXX están escritas pensando en que
     * puedan aprovechar el camino más corto aunque tengan un poco más de
     * código (al menos en bytecodes lo tienen)
     */
    private void incRegHL() {
        if (++regL < 0x100) {
            return;
        }

        regL = 0;

        if (++regH < 0x100) {
            return;
        }

        regH = 0;
    }

    private void decRegHL() {
        if (--regL >= 0) {
            return;
        }

        regL = 0xff;

        if (--regH >= 0) {
            return;
        }

        regH = 0xff;
    }

    public final int getRegHLx() {
        return (regHx << 8) | regLx;
    }

    public final void setRegHLx(int word) {
        regHx = (word >>> 8) & 0xff;
        regLx = word & 0xff;
    }

    // Acceso a registros de propósito específico
    public final int getRegPC() {
        return regPC;
    }

    public final void setRegPC(int address) {
        regPC = address & 0xffff;
    }

    public final int getRegSP() {
        return regSP;
    }

    public final void setRegSP(int word) {
        regSP = word & 0xffff;
    }

    public final int getRegIX() {
        return regIX;
    }

    public final void setRegIX(int word) {
        regIX = word & 0xffff;
    }

    public final int getRegIY() {
        return regIY;
    }

    public final void setRegIY(int word) {
        regIY = word & 0xffff;
    }

    public final int getRegI() {
        return regI;
    }

    public final void setRegI(int value) {
        regI = value & 0xff;
    }

    public final int getRegR() {
        return regRbit7 ? (regR & 0x7f) | SIGN_MASK : regR & 0x7f;
    }

    public final void setRegR(int value) {
        regR = value & 0x7f;
        regRbit7 = (value > 0x7f);
    }

    public final int getPairIR() {
        if (regRbit7) {
            return (regI << 8) | ((regR & 0x7f) | SIGN_MASK);
        }
        return (regI << 8) | (regR & 0x7f);
    }

    // Acceso al registro oculto MEMPTR
    public final int getMemPtr() {
        return memptr & 0xffff;
    }

    public final void setMemPtr(int word) {
        memptr = word & 0xffff;
    }

    // Acceso a los flags uno a uno
    public final boolean isCarryFlag() {
        return carryFlag;
    }

    public final void setCarryFlag(boolean state) {
        carryFlag = state;
    }

    public final boolean isAddSubFlag() {
        return (sz5h3pnFlags & ADDSUB_MASK) != 0;
    }

    public final void setAddSubFlag(boolean state) {
        if (state) {
            sz5h3pnFlags |= ADDSUB_MASK;
        } else {
            sz5h3pnFlags &= ~ADDSUB_MASK;
        }
    }

    public final boolean isParOverFlag() {
        return (sz5h3pnFlags & PARITY_MASK) != 0;
    }

    public final void setParOverFlag(boolean state) {
        if (state) {
            sz5h3pnFlags |= PARITY_MASK;
        } else {
            sz5h3pnFlags &= ~PARITY_MASK;
        }
    }

    public final boolean isBit3Flag() {
        return (sz5h3pnFlags & BIT3_MASK) != 0;
    }

    public final void setBit3Fag(boolean state) {
        if (state) {
            sz5h3pnFlags |= BIT3_MASK;
        } else {
            sz5h3pnFlags &= ~BIT3_MASK;
        }
    }

    public final boolean isHalfCarryFlag() {
        return (sz5h3pnFlags & HALFCARRY_MASK) != 0;
    }

    public final void setHalfCarryFlag(boolean state) {
        if (state) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        } else {
            sz5h3pnFlags &= ~HALFCARRY_MASK;
        }
    }

    public final boolean isBit5Flag() {
        return (sz5h3pnFlags & BIT5_MASK) != 0;
    }

    public final void setBit5Flag(boolean state) {
        if (state) {
            sz5h3pnFlags |= BIT5_MASK;
        } else {
            sz5h3pnFlags &= ~BIT5_MASK;
        }
    }

    public final boolean isZeroFlag() {
        return (sz5h3pnFlags & ZERO_MASK) != 0;
    }

    public final void setZeroFlag(boolean state) {
        if (state) {
            sz5h3pnFlags |= ZERO_MASK;
        } else {
            sz5h3pnFlags &= ~ZERO_MASK;
        }
    }

    public final boolean isSignFlag() {
        return sz5h3pnFlags >= SIGN_MASK;
    }

    public final void setSignFlag(boolean state) {
        if (state) {
            sz5h3pnFlags |= SIGN_MASK;
        } else {
            sz5h3pnFlags &= ~SIGN_MASK;
        }
    }

    // Acceso a los flags F
    public final int getFlags() {
        return carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags;
    }

    public final void setFlags(int regF) {
        sz5h3pnFlags = regF & 0xfe;

        carryFlag = (regF & CARRY_MASK) != 0;
    }

    // Acceso a los flip-flops de interrupción
    public final boolean isIFF1() {
        return ffIFF1;
    }

    public final void setIFF1(boolean state) {
        ffIFF1 = state;
    }

    public final boolean isIFF2() {
        return ffIFF2;
    }

    public final void setIFF2(boolean state) {
        ffIFF2 = state;
    }

    public final boolean isNMI() {
        return activeNMI;
    }

    public final void setNMI(boolean nmi) {
        activeNMI = nmi;
    }

    // La línea de NMI se activa por impulso, no por nivel
    public final void triggerNMI() {
        activeNMI = true;
    }

    // La línea INT se activa por nivel
    public final boolean isINTLine() {
        return activeINT;
    }

    public final void setINTLine(boolean intLine) {
        activeINT = intLine;
    }

    //Acceso al modo de interrupción
    public final IntMode getIM() {
        return modeINT;
    }

    public final void setIM(IntMode mode) {
        modeINT = mode;
    }

    public final boolean isHalted() {
        return halted;
    }

    public void setHalted(boolean state) {
        halted = state;
    }

    public void setPinReset() {
        pinReset = true;
    }

    public final boolean isPendingEI() {
        return pendingEI;
    }

    public final void setPendingEI(boolean state) {
        pendingEI = state;
    }

    public final Z80State getZ80State() {
        Z80State state = new Z80State();
        state.setRegA(regA);
        state.setRegF(getFlags());
        state.setRegB(regB);
        state.setRegC(regC);
        state.setRegD(regD);
        state.setRegE(regE);
        state.setRegH(regH);
        state.setRegL(regL);
        state.setRegAx(regAx);
        state.setRegFx(regFx);
        state.setRegBx(regBx);
        state.setRegCx(regCx);
        state.setRegDx(regDx);
        state.setRegEx(regEx);
        state.setRegHx(regHx);
        state.setRegLx(regLx);
        state.setRegIX(regIX);
        state.setRegIY(regIY);
        state.setRegSP(regSP);
        state.setRegPC(regPC);
        state.setRegI(regI);
        state.setRegR(getRegR());
        state.setMemPtr(memptr);
        state.setHalted(halted);
        state.setIFF1(ffIFF1);
        state.setIFF2(ffIFF2);
        state.setIM(modeINT);
        state.setINTLine(activeINT);
        state.setPendingEI(pendingEI);
        state.setNMI(activeNMI);
        state.setFlagQ(lastFlagQ);
        return state;
    }

    public final void setZ80State(Z80State state) {
        regA = state.getRegA();
        setFlags(state.getRegF());
        regB = state.getRegB();
        regC = state.getRegC();
        regD = state.getRegD();
        regE = state.getRegE();
        regH = state.getRegH();
        regL = state.getRegL();
        regAx = state.getRegAx();
        regFx = state.getRegFx();
        regBx = state.getRegBx();
        regCx = state.getRegCx();
        regDx = state.getRegDx();
        regEx = state.getRegEx();
        regHx = state.getRegHx();
        regLx = state.getRegLx();
        regIX = state.getRegIX();
        regIY = state.getRegIY();
        regSP = state.getRegSP();
        regPC = state.getRegPC();
        regI = state.getRegI();
        setRegR(state.getRegR());
        memptr = state.getMemPtr();
        halted = state.isHalted();
        ffIFF1 = state.isIFF1();
        ffIFF2 = state.isIFF2();
        modeINT = state.getIM();
        activeINT = state.isINTLine();
        pendingEI = state.isPendingEI();
        activeNMI = state.isNMI();
        flagQ = false;
        lastFlagQ = state.isFlagQ();
    }

    // Reset
    /* Según el documento de Sean Young, que se encuentra en
     * [http://www.myquest.com/z80undocumented], la mejor manera de emular el
     * reset es poniendo PC, IFF1, IFF2, R e IM0 a 0 y todos los demás registros
     * a 0xFFFF.
     *
     * 29/05/2011: cuando la CPU recibe alimentación por primera vez, los
     *             registros PC e IR se inicializan a cero y el resto a 0xFF.
     *             Si se produce un reset a través de la patilla correspondiente,
     *             los registros PC e IR se inicializan a 0 y el resto se preservan.
     *             En cualquier caso, todo parece depender bastante del modelo
     *             concreto de Z80, así que se escoge el comportamiento del
     *             modelo Zilog Z8400APS. Z80A CPU.
     *             http://www.worldofspectrum.org/forums/showthread.php?t=34574
     */
    public final void reset() {
        if (pinReset) {
            pinReset = false;
        } else {
            regA = regAx = 0xff;
            setFlags(0xff);
            regFx = 0xff;
            regB = regBx = 0xff;
            regC = regCx = 0xff;
            regD = regDx = 0xff;
            regE = regEx = 0xff;
            regH = regHx = 0xff;
            regL = regLx = 0xff;

            regIX = regIY = 0xffff;

            regSP = 0xffff;

            memptr = 0xffff;
        }

        regPC = 0;
        regI = regR = 0;
        regRbit7 = false;
        ffIFF1 = false;
        ffIFF2 = false;
        pendingEI = false;
        activeNMI = false;
        activeINT = false;
        halted = false;
        setIM(IntMode.IM0);
        lastFlagQ = false;
        prefixOpcode = 0x00;
    }

    // Rota a la izquierda el valor del argumento
    // El bit 0 y el flag C toman el valor del bit 7 antes de la operación
    private int rlc(int oper8) {
        carryFlag = (oper8 > 0x7f);
        oper8 = (oper8 << 1) & 0xfe;
        if (carryFlag) {
            oper8 |= CARRY_MASK;
        }
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la izquierda el valor del argumento
    // El bit 7 va al carry flag
    // El bit 0 toma el valor del flag C antes de la operación
    private int rl(int oper8) {
        boolean carry = carryFlag;
        carryFlag = (oper8 > 0x7f);
        oper8 = (oper8 << 1) & 0xfe;
        if (carry) {
            oper8 |= CARRY_MASK;
        }
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la izquierda el valor del argumento
    // El bit 7 va al carry flag
    // El bit 0 toma el valor 0
    private int sla(int oper8) {
        carryFlag = (oper8 > 0x7f);
        oper8 = (oper8 << 1) & 0xfe;
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la izquierda el valor del argumento (como sla salvo por el bit 0)
    // El bit 7 va al carry flag
    // El bit 0 toma el valor 1
    // Instrucción indocumentada
    private int sll(int oper8) {
        carryFlag = (oper8 > 0x7f);
        oper8 = ((oper8 << 1) | CARRY_MASK) & 0xff;
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la derecha el valor del argumento
    // El bit 7 y el flag C toman el valor del bit 0 antes de la operación
    private int rrc(int oper8) {
        carryFlag = (oper8 & CARRY_MASK) != 0;
        oper8 >>>= 1;
        if (carryFlag) {
            oper8 |= SIGN_MASK;
        }
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la derecha el valor del argumento
    // El bit 0 va al carry flag
    // El bit 7 toma el valor del flag C antes de la operación
    private int rr(int oper8) {
        boolean carry = carryFlag;
        carryFlag = (oper8 & CARRY_MASK) != 0;
        oper8 >>>= 1;
        if (carry) {
            oper8 |= SIGN_MASK;
        }
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // A = A7 A6 A5 A4 (HL)3 (HL)2 (HL)1 (HL)0
    // (HL) = A3 A2 A1 A0 (HL)7 (HL)6 (HL)5 (HL)4
    // Los bits 3,2,1 y 0 de (HL) se copian a los bits 3,2,1 y 0 de A.
    // Los 4 bits bajos que había en A se copian a los bits 7,6,5 y 4 de (HL).
    // Los 4 bits altos que había en (HL) se copian a los 4 bits bajos de (HL)
    // Los 4 bits superiores de A no se tocan. ¡p'habernos matao!
    private void rrd() {
        int aux = (regA & 0x0f) << 4;
        memptr = getRegHL();
        int memHL = MemIoImpl.peek8(memptr);
        regA = (regA & 0xf0) | (memHL & 0x0f);
        MemIoImpl.addressOnBus(memptr, 4);
        MemIoImpl.poke8(memptr, (memHL >>> 4) | aux);
        sz5h3pnFlags = sz53pn_addTable[regA];
        memptr++;
        flagQ = true;
    }

    // A = A7 A6 A5 A4 (HL)7 (HL)6 (HL)5 (HL)4
    // (HL) = (HL)3 (HL)2 (HL)1 (HL)0 A3 A2 A1 A0
    // Los 4 bits bajos que había en (HL) se copian a los bits altos de (HL).
    // Los 4 bits altos que había en (HL) se copian a los 4 bits bajos de A
    // Los bits 3,2,1 y 0 de A se copian a los bits 3,2,1 y 0 de (HL).
    // Los 4 bits superiores de A no se tocan. ¡p'habernos matao!
    private void rld() {
        int aux = regA & 0x0f;
        memptr = getRegHL();
        int memHL = MemIoImpl.peek8(memptr);
        regA = (regA & 0xf0) | (memHL >>> 4);
        MemIoImpl.addressOnBus(memptr, 4);
        MemIoImpl.poke8(memptr, ((memHL << 4) | aux) & 0xff);
        sz5h3pnFlags = sz53pn_addTable[regA];
        memptr++;
        flagQ = true;
    }

    // Rota a la derecha 1 bit el valor del argumento
    // El bit 0 pasa al carry.
    // El bit 7 conserva el valor que tenga
    private int sra(int oper8) {
        int sign = oper8 & SIGN_MASK;
        carryFlag = (oper8 & CARRY_MASK) != 0;
        oper8 = (oper8 >> 1) | sign;
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    // Rota a la derecha 1 bit el valor del argumento
    // El bit 0 pasa al carry.
    // El bit 7 toma el valor 0
    private int srl(int oper8) {
        carryFlag = (oper8 & CARRY_MASK) != 0;
        oper8 >>>= 1;
        sz5h3pnFlags = sz53pn_addTable[oper8];
        flagQ = true;
        return oper8;
    }

    /*
     * Half-carry flag:
     *
     * FLAG = (A^B^RESULT)&0x10  for any operation
     *
     * Overflow flag:
     *
     * FLAG = ~(A^B)&(B^RESULT)&0x80 for addition [ADD/ADC]
     * FLAG = (A^B)&(A^RESULT)&0x80  for subtraction [SUB/SBC]
     *
     * For INC/DEC, you can use following simplifications:
     *
     * INC:
     * H_FLAG = (RESULT&0x0F)==0x00
     * V_FLAG = RESULT==0x80
     *
     * DEC:
     * H_FLAG = (RESULT&0x0F)==0x0F
     * V_FLAG = RESULT==0x7F
     */
    // Incrementa un valor de 8 bits modificando los flags oportunos
    private int inc8(int oper8) {
        oper8 = (oper8 + 1) & 0xff;

        sz5h3pnFlags = sz53n_addTable[oper8];

        if ((oper8 & 0x0f) == 0) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (oper8 == 0x80) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        flagQ = true;
        return oper8;
    }

    // Decrementa un valor de 8 bits modificando los flags oportunos
    private int dec8(int oper8) {
        oper8 = (oper8 - 1) & 0xff;

        sz5h3pnFlags = sz53n_subTable[oper8];

        if ((oper8 & 0x0f) == 0x0f) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (oper8 == 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        flagQ = true;
        return oper8;
    }

    // Suma de 8 bits afectando a los flags
    private void add(int oper8) {
        int res = regA + oper8;

        carryFlag = res > 0xff;
        res &= 0xff;
        sz5h3pnFlags = sz53n_addTable[res];

        /* El módulo 16 del resultado será menor que el módulo 16 del registro A
         * si ha habido HalfCarry. Sucede lo mismo para todos los métodos suma
         * SIN carry */
        if ((res & 0x0f) < (regA & 0x0f)) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        regA = res;
        flagQ = true;
    }

    // Suma con acarreo de 8 bits
    private void adc(int oper8) {
        int res = regA + oper8;

        if (carryFlag) {
            res++;
        }

        carryFlag = res > 0xff;
        res &= 0xff;
        sz5h3pnFlags = sz53n_addTable[res];

        if (((regA ^ oper8 ^ res) & 0x10) != 0) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        regA = res;
        flagQ = true;
    }

    // Suma dos operandos de 16 bits sin carry afectando a los flags
    private int add16(int reg16, int oper16) {
        oper16 += reg16;

        carryFlag = oper16 > 0xffff;
        sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | ((oper16 >>> 8) & FLAG_53_MASK);
        oper16 &= 0xffff;

        if ((oper16 & 0x0fff) < (reg16 & 0x0fff)) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        memptr = reg16 + 1;
        flagQ = true;
        return oper16;
    }

    // Suma con acarreo de 16 bits
    private void adc16(int reg16) {
        int regHL = getRegHL();
        memptr = regHL + 1;

        int res = regHL + reg16;
        if (carryFlag) {
            res++;
        }

        carryFlag = res > 0xffff;
        res &= 0xffff;
        setRegHL(res);

        sz5h3pnFlags = sz53n_addTable[regH];
        if (res != 0) {
            sz5h3pnFlags &= ~ZERO_MASK;
        }

        if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regHL ^ ~reg16) & (regHL ^ res)) > 0x7fff) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        flagQ = true;
    }

    // Resta de 8 bits
    private void sub(int oper8) {
        int res = regA - oper8;

        carryFlag = res < 0;
        res &= 0xff;
        sz5h3pnFlags = sz53n_subTable[res];

        /* El módulo 16 del resultado será mayor que el módulo 16 del registro A
         * si ha habido HalfCarry. Sucede lo mismo para todos los métodos resta
         * SIN carry, incluido cp */
        if ((res & 0x0f) > (regA & 0x0f)) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        regA = res;
        flagQ = true;
    }

    // Resta con acarreo de 8 bits
    private void sbc(int oper8) {
        int res = regA - oper8;

        if (carryFlag) {
            res--;
        }

        carryFlag = res < 0;
        res &= 0xff;
        sz5h3pnFlags = sz53n_subTable[res];

        if (((regA ^ oper8 ^ res) & 0x10) != 0) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        regA = res;
        flagQ = true;
    }

    // Resta con acarreo de 16 bits
    private void sbc16(int reg16) {
        int regHL = getRegHL();
        memptr = regHL + 1;

        int res = regHL - reg16;
        if (carryFlag) {
            res--;
        }

        carryFlag = res < 0;
        res &= 0xffff;
        setRegHL(res);

        sz5h3pnFlags = sz53n_subTable[regH];
        if (res != 0) {
            sz5h3pnFlags &= ~ZERO_MASK;
        }

        if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regHL ^ reg16) & (regHL ^ res)) > 0x7fff) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }
        flagQ = true;
    }

    // Operación AND lógica
    private void and(int oper8) {
        regA &= oper8;
        carryFlag = false;
        sz5h3pnFlags = sz53pn_addTable[regA] | HALFCARRY_MASK;
        flagQ = true;
    }

    // Operación XOR lógica
    private void xor(int oper8) {
        regA = (regA ^ oper8) & 0xff;
        carryFlag = false;
        sz5h3pnFlags = sz53pn_addTable[regA];
        flagQ = true;
    }

    // Operación OR lógica
    private void or(int oper8) {
        regA = (regA | oper8) & 0xff;
        carryFlag = false;
        sz5h3pnFlags = sz53pn_addTable[regA];
        flagQ = true;
    }

    // Operación de comparación con el registro A
    // es como SUB, pero solo afecta a los flags
    // Los flags SIGN y ZERO se calculan a partir del resultado
    // Los flags 3 y 5 se copian desde el operando (sigh!)
    private void cp(int oper8) {
        int res = regA - (oper8 & 0xff);

        carryFlag = res < 0;
        res &= 0xff;

        sz5h3pnFlags = (sz53n_addTable[oper8] & FLAG_53_MASK)
            | // No necesito preservar H, pero está a 0 en la tabla de todas formas
            (sz53n_subTable[res] & FLAG_SZHN_MASK);

        if ((res & 0x0f) > (regA & 0x0f)) {
            sz5h3pnFlags |= HALFCARRY_MASK;
        }

        if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
            sz5h3pnFlags |= OVERFLOW_MASK;
        }

        flagQ = true;
    }

    // DAA
    private void daa() {
        int suma = 0;
        boolean carry = carryFlag;

        if ((sz5h3pnFlags & HALFCARRY_MASK) != 0 || (regA & 0x0f) > 0x09) {
            suma = 6;
        }

        if (carry || (regA > 0x99)) {
            suma |= 0x60;
        }

        if (regA > 0x99) {
            carry = true;
        }

        if ((sz5h3pnFlags & ADDSUB_MASK) != 0) {
            sub(suma);
            sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_subTable[regA];
        } else {
            add(suma);
            sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_addTable[regA];
        }

        carryFlag = carry;
        // Los add/sub ya ponen el resto de los flags
        flagQ = true;
    }

    // POP
    private int pop() {
        int word = MemIoImpl.peek16(regSP);
        regSP = (regSP + 2) & 0xffff;
        return word;
    }

    // PUSH
    private void push(int word) {
        regSP = (regSP - 1) & 0xffff;
        MemIoImpl.poke8(regSP, word >>> 8);
        regSP = (regSP - 1) & 0xffff;
        MemIoImpl.poke8(regSP, word);
    }

    // LDI
    private void ldi() {
        int work8 = MemIoImpl.peek8(getRegHL());
        int regDE = getRegDE();
        MemIoImpl.poke8(regDE, work8);
        MemIoImpl.addressOnBus(regDE, 2);
        incRegHL();
        incRegDE();
        decRegBC();
        work8 += regA;

        sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZ_MASK) | (work8 & BIT3_MASK);

        if ((work8 & ADDSUB_MASK) != 0) {
            sz5h3pnFlags |= BIT5_MASK;
        }

        if (regC != 0 || regB != 0) {
            sz5h3pnFlags |= PARITY_MASK;
        }
        flagQ = true;
    }

    // LDD
    private void ldd() {
        int work8 = MemIoImpl.peek8(getRegHL());
        int regDE = getRegDE();
        MemIoImpl.poke8(regDE, work8);
        MemIoImpl.addressOnBus(regDE, 2);
        decRegHL();
        decRegDE();
        decRegBC();
        work8 += regA;

        sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZ_MASK) | (work8 & BIT3_MASK);

        if ((work8 & ADDSUB_MASK) != 0) {
            sz5h3pnFlags |= BIT5_MASK;
        }

        if (regC != 0 || regB != 0) {
            sz5h3pnFlags |= PARITY_MASK;
        }
        flagQ = true;
    }

    // CPI
    private void cpi() {
        int regHL = getRegHL();
        int memHL = MemIoImpl.peek8(regHL);
        boolean carry = carryFlag; // lo guardo porque cp lo toca
        cp(memHL);
        carryFlag = carry;
        MemIoImpl.addressOnBus(regHL, 5);
        incRegHL();
        decRegBC();
        memHL = regA - memHL - ((sz5h3pnFlags & HALFCARRY_MASK) != 0 ? 1 : 0);
        sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHN_MASK) | (memHL & BIT3_MASK);

        if ((memHL & ADDSUB_MASK) != 0) {
            sz5h3pnFlags |= BIT5_MASK;
        }

        if (regC != 0 || regB != 0) {
            sz5h3pnFlags |= PARITY_MASK;
        }

        memptr++;
        flagQ = true;
    }

    // CPD
    private void cpd() {
        int regHL = getRegHL();
        int memHL = MemIoImpl.peek8(regHL);
        boolean carry = carryFlag; // lo guardo porque cp lo toca
        cp(memHL);
        carryFlag = carry;
        MemIoImpl.addressOnBus(regHL, 5);
        decRegHL();
        decRegBC();
        memHL = regA - memHL - ((sz5h3pnFlags & HALFCARRY_MASK) != 0 ? 1 : 0);
        sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHN_MASK) | (memHL & BIT3_MASK);

        if ((memHL & ADDSUB_MASK) != 0) {
            sz5h3pnFlags |= BIT5_MASK;
        }

        if (regC != 0 || regB != 0) {
            sz5h3pnFlags |= PARITY_MASK;
        }

        memptr--;
        flagQ = true;
    }

    // INI
    private void ini() {
        memptr = getRegBC();
        MemIoImpl.addressOnBus(getPairIR(), 1);
        int work8 = MemIoImpl.inPort(memptr);
        MemIoImpl.poke8(getRegHL(), work8);

        memptr++;
        regB = (regB - 1) & 0xff;

        incRegHL();

        sz5h3pnFlags = sz53pn_addTable[regB];
        if (work8 > 0x7f) {
            sz5h3pnFlags |= ADDSUB_MASK;
        }

        carryFlag = false;
        int tmp = work8 + ((regC + 1) & 0xff);
        if (tmp > 0xff) {
            sz5h3pnFlags |= HALFCARRY_MASK;
            carryFlag = true;
        }

        if ((sz53pn_addTable[((tmp & 0x07) ^ regB)]
            & PARITY_MASK) == PARITY_MASK) {
            sz5h3pnFlags |= PARITY_MASK;
        } else {
            sz5h3pnFlags &= ~PARITY_MASK;
        }
        flagQ = true;
    }

    // IND
    private void ind() {
        memptr = getRegBC();
        MemIoImpl.addressOnBus(getPairIR(), 1);
        int work8 = MemIoImpl.inPort(memptr);
        MemIoImpl.poke8(getRegHL(), work8);

        memptr--;
        regB = (regB - 1) & 0xff;

        decRegHL();

        sz5h3pnFlags = sz53pn_addTable[regB];
        if (work8 > 0x7f) {
            sz5h3pnFlags |= ADDSUB_MASK;
        }

        carryFlag = false;
        int tmp = work8 + ((regC - 1) & 0xff);
        if (tmp > 0xff) {
            sz5h3pnFlags |= HALFCARRY_MASK;
            carryFlag = true;
        }

        if ((sz53pn_addTable[((tmp & 0x07) ^ regB)]
            & PARITY_MASK) == PARITY_MASK) {
            sz5h3pnFlags |= PARITY_MASK;
        } else {
            sz5h3pnFlags &= ~PARITY_MASK;
        }
        flagQ = true;
    }

    // OUTI
    private void outi() {

        MemIoImpl.addressOnBus(getPairIR(), 1);

        regB = (regB - 1) & 0xff;
        memptr = getRegBC();

        int work8 = MemIoImpl.peek8(getRegHL());
        MemIoImpl.outPort(memptr, work8);
        memptr++;

        incRegHL();

        carryFlag = false;
        if (work8 > 0x7f) {
            sz5h3pnFlags = sz53n_subTable[regB];
        } else {
            sz5h3pnFlags = sz53n_addTable[regB];
        }

        if ((regL + work8) > 0xff) {
            sz5h3pnFlags |= HALFCARRY_MASK;
            carryFlag = true;
        }

        if ((sz53pn_addTable[(((regL + work8) & 0x07) ^ regB)]
            & PARITY_MASK) == PARITY_MASK) {
            sz5h3pnFlags |= PARITY_MASK;
        }
        flagQ = true;
    }

    // OUTD
    private void outd() {

        MemIoImpl.addressOnBus(getPairIR(), 1);

        regB = (regB - 1) & 0xff;
        memptr = getRegBC();

        int work8 = MemIoImpl.peek8(getRegHL());
        MemIoImpl.outPort(memptr, work8);
        memptr--;

        decRegHL();

        carryFlag = false;
        if (work8 > 0x7f) {
            sz5h3pnFlags = sz53n_subTable[regB];
        } else {
            sz5h3pnFlags = sz53n_addTable[regB];
        }

        if ((regL + work8) > 0xff) {
            sz5h3pnFlags |= HALFCARRY_MASK;
            carryFlag = true;
        }

        if ((sz53pn_addTable[(((regL + work8) & 0x07) ^ regB)]
            & PARITY_MASK) == PARITY_MASK) {
            sz5h3pnFlags |= PARITY_MASK;
        }
        flagQ = true;
    }

    // Pone a 1 el Flag Z si el bit b del registro
    // r es igual a 0
    /*
     * En contra de lo que afirma el Z80-Undocumented, los bits 3 y 5 toman
     * SIEMPRE el valor de los bits correspondientes del valor a comparar para
     * las instrucciones BIT n,r. Para BIT n,(HL) toman el valor del registro
     * escondido (memptr), y para las BIT n, (IX/IY+n) toman el valor de los
     * bits superiores de la dirección indicada por IX/IY+n.
     *
     * 04/12/08 Confirmado el comentario anterior:
     *          http://scratchpad.wikia.com/wiki/Z80
     */
    private void bit(int mask, int reg) {
        boolean zeroFlag = (mask & reg) == 0;

        sz5h3pnFlags = (sz53n_addTable[reg] & ~FLAG_SZP_MASK) | HALFCARRY_MASK;

        if (zeroFlag) {
            sz5h3pnFlags |= (PARITY_MASK | ZERO_MASK);
        }

        if (mask == SIGN_MASK && !zeroFlag) {
            sz5h3pnFlags |= SIGN_MASK;
        }
        flagQ = true;
    }

    //Interrupción
    /* Desglose de la interrupción, según el modo:
     * IM0:
     *      M1: 7 T-Estados -> reconocer INT y decSP
     *      M2: 3 T-Estados -> escribir byte alto y decSP
     *      M3: 3 T-Estados -> escribir byte bajo y salto a N
     * IM1:
     *      M1: 7 T-Estados -> reconocer INT y decSP
     *      M2: 3 T-Estados -> escribir byte alto PC y decSP
     *      M3: 3 T-Estados -> escribir byte bajo PC y PC=0x0038
     * IM2:
     *      M1: 7 T-Estados -> reconocer INT y decSP
     *      M2: 3 T-Estados -> escribir byte alto y decSP
     *      M3: 3 T-Estados -> escribir byte bajo
     *      M4: 3 T-Estados -> leer byte bajo del vector de INT
     *      M5: 3 T-Estados -> leer byte alto y saltar a la rutina de INT
     */
    private void interruption() {

        //System.out.println(String.format("INT at %d T-States", tEstados));
//        int tmp = tEstados; // peek8 modifica los tEstados
        lastFlagQ = false;
        // Si estaba en un HALT esperando una INT, lo saca de la espera
        halted = false;

        MemIoImpl.interruptHandlingTime(7);

        regR++;
        ffIFF1 = ffIFF2 = false;
        push(regPC);  // el push a�adir� 6 t-estados (+contended si toca)
        if (modeINT == IntMode.IM2) {
            regPC = MemIoImpl.peek16((regI << 8) | 0xff); // +6 t-estados
        } else {
            regPC = 0x0038;
        }
        memptr = regPC;
        //System.out.println(String.format("Coste INT: %d", tEstados-tmp));
    }

    //Interrupción NMI, no utilizado por ahora
    /* Desglose de ciclos de máquina y T-Estados
     * M1: 5 T-Estados -> extraer opcode (pá ná, es tontería) y decSP
     * M2: 3 T-Estados -> escribe byte alto de PC y decSP
     * M3: 3 T-Estados -> escribe byte bajo de PC y PC=0x0066
     */
    private void nmi() {
        lastFlagQ = false;
        halted = false;
        // Esta lectura consigue dos cosas:
        //      1.- La lectura del opcode del M1 que se descarta
        //      2.- Si estaba en un HALT esperando una INT, lo saca de la espera
        MemIoImpl.fetchOpcode(regPC);
        MemIoImpl.interruptHandlingTime(1);

        regR++;
        ffIFF1 = false;
        push(regPC);  // 3+3 t-estados + contended si procede
        regPC = memptr = 0x0066;
    }

    public final boolean isBreakpoint(int address) {
        return breakpointAt.get(address & 0xffff);
    }

    public final void setBreakpoint(int address, boolean state) {
        breakpointAt.set(address & 0xffff, state);
    }

    public void resetBreakpoints() {
        breakpointAt.clear();
    }

    public boolean isExecDone() {
        return execDone;
    }

    public void setExecDone(boolean state) {
        execDone = state;
    }

    /* Los tEstados transcurridos se calculan teniendo en cuenta el número de
     * ciclos de máquina reales que se ejecutan. Esa es la única forma de poder
     * simular la contended memory del Spectrum.
     */
    public final void execute() {

        int opCode = MemIoImpl.fetchOpcode(regPC);
        regR++;

        if (prefixOpcode == 0 && breakpointAt.get(regPC)) {
            opCode = NotifyImpl.breakpoint(regPC, opCode);
        }

        if (!halted) {
            regPC = (regPC + 1) & 0xffff;

            // El prefijo 0xCB no cuenta para esta guerra.
            // En CBxx todas las xx producen un código válido
            // de instrucción, incluyendo CBCB.
            switch (prefixOpcode) {
                case 0x00:
                    flagQ = pendingEI = false;
                    decodeOpcode(opCode);
                    break;
                case 0xDD:
                    prefixOpcode = 0;
                    regIX = decodeDDFD(opCode, regIX);
                    break;
                case 0xED:
                    prefixOpcode = 0;
                    decodeED(opCode);
                    break;
                case 0xFD:
                    prefixOpcode = 0;
                    regIY = decodeDDFD(opCode, regIY);
                    break;
                default:
                    System.out.println(String.format("ERROR!: prefixOpcode = %02x, opCode = %02x", prefixOpcode, opCode));
            }

            if (prefixOpcode != 0x00) {
                return;
            }

            lastFlagQ = flagQ;

            if (execDone) {
                NotifyImpl.execDone();
            }
        }

        // Primero se comprueba NMI
        // Si se activa NMI no se comprueba INT porque la siguiente
        // instrucción debe ser la de 0x0066.
        if (activeNMI) {
            activeNMI = false;
            nmi();
            return;
        }

        // Ahora se comprueba si hay una INT
        if (ffIFF1 && !pendingEI && MemIoImpl.isActiveINT()) {
            interruption();
        }
    }

    private void decodeOpcode(int opCode) {
        if (decodeRegularOpcode(opCode)) {
            return;
        }

        switch (opCode) {
            case 0x00:       /* NOP */
                break;
            case 0x07: {     /* RLCA */
                carryFlag = (regA > 0x7f);
                regA = (regA << 1) & 0xff;
                if (carryFlag) {
                    regA |= CARRY_MASK;
                }
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
                flagQ = true;
                break;
            }
            case 0x08: {     /* EX AF,AF' */
                int work8 = regA;
                regA = regAx;
                regAx = work8;

                work8 = getFlags();
                setFlags(regFx);
                regFx = work8;
                break;
            }
            case 0x0F: {     /* RRCA */
                carryFlag = (regA & CARRY_MASK) != 0;
                regA >>>= 1;
                if (carryFlag) {
                    regA |= SIGN_MASK;
                }
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
                flagQ = true;
                break;
            }
            case 0x10: {     /* DJNZ e */
                MemIoImpl.addressOnBus(getPairIR(), 1);
                byte offset = (byte) MemIoImpl.peek8(regPC);
                regB--;
                if (regB != 0) {
                    regB &= 0xff;
                    MemIoImpl.addressOnBus(regPC, 5);
                    regPC = memptr = (regPC + offset + 1) & 0xffff;
                } else {
                    regPC = (regPC + 1) & 0xffff;
                }
                break;
            }
            case 0x17: {     /* RLA */
                boolean oldCarry = carryFlag;
                carryFlag = (regA > 0x7f);
                regA = (regA << 1) & 0xff;
                if (oldCarry) {
                    regA |= CARRY_MASK;
                }
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
                flagQ = true;
                break;
            }
            case 0x1F: {     /* RRA */
                boolean oldCarry = carryFlag;
                carryFlag = (regA & CARRY_MASK) != 0;
                regA >>>= 1;
                if (oldCarry) {
                    regA |= SIGN_MASK;
                }
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
                flagQ = true;
                break;
            }
            case 0x22: {     /* LD (nn),HL */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, getRegHL());
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x27:       /* DAA */
                daa();
                break;
            case 0x2A: {     /* LD HL,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                setRegHL(MemIoImpl.peek16(memptr++));
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x2F:       /* CPL */
                regA ^= 0xff;
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | HALFCARRY_MASK
                    | (regA & FLAG_53_MASK) | ADDSUB_MASK;
                flagQ = true;
                break;
            case 0x32: {     /* LD (nn),A */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke8(memptr, regA);
                memptr = (regA << 8) | ((memptr + 1) & 0xff);
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x37: {     /* SCF */
                int regQ = lastFlagQ ? sz5h3pnFlags : 0;
                carryFlag = true;
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
                flagQ = true;
                break;
            }
            case 0x3A: {     /* LD A,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                regA = MemIoImpl.peek8(memptr++);
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x3F: {     /* CCF */
                int regQ = lastFlagQ ? sz5h3pnFlags : 0;
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
                if (carryFlag) {
                    sz5h3pnFlags |= HALFCARRY_MASK;
                }
                carryFlag = !carryFlag;
                flagQ = true;
                break;
            }
            case 0xC3:       /* JP nn */
                memptr = regPC = MemIoImpl.peek16(regPC);
                break;
            case 0xC9:       /* RET */
                regPC = memptr = pop();
                break;
            case 0xCB:       /* CB subset */
                decodeCB();
                break;
            case 0xCD:       /* CALL nn */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.addressOnBus((regPC + 1) & 0xffff, 1);
                push(regPC + 2);
                regPC = memptr;
                break;
            case 0xD3: {     /* OUT (n),A */
                int work8 = MemIoImpl.peek8(regPC);
                memptr = regA << 8;
                MemIoImpl.outPort(memptr | work8, regA);
                memptr |= ((work8 + 1) & 0xff);
                regPC = (regPC + 1) & 0xffff;
                break;
            }
            case 0xD9:       /* EXX */
                exchangeMainAndAlternateRegisters();
                break;
            case 0xDB:       /* IN A,(n) */
                memptr = (regA << 8) | MemIoImpl.peek8(regPC);
                regA = MemIoImpl.inPort(memptr++);
                regPC = (regPC + 1) & 0xffff;
                break;
            case 0xDD:       /* DD subset */
                decodeDD();
                break;
            case 0xE3: {     /* EX (SP),HL */
                int work16 = regH;
                int work8 = regL;
                setRegHL(MemIoImpl.peek16(regSP));
                MemIoImpl.addressOnBus((regSP + 1) & 0xffff, 1);
                MemIoImpl.poke8((regSP + 1) & 0xffff, work16);
                MemIoImpl.poke8(regSP, work8);
                MemIoImpl.addressOnBus(regSP, 2);
                memptr = getRegHL();
                break;
            }
            case 0xE9:       /* JP (HL) */
                regPC = getRegHL();
                break;
            case 0xEB: {     /* EX DE,HL */
                int work8 = regH;
                regH = regD;
                regD = work8;

                work8 = regL;
                regL = regE;
                regE = work8;
                break;
            }
            case 0xED:       /* ED subset */
                decodeEDPrefix();
                break;
            case 0xF3:       /* DI */
                ffIFF1 = ffIFF2 = false;
                break;
            case 0xF9:       /* LD SP,HL */
                MemIoImpl.addressOnBus(getPairIR(), 2);
                regSP = getRegHL();
                break;
            case 0xFB:       /* EI */
                ffIFF1 = ffIFF2 = true;
                pendingEI = true;
                break;
            case 0xFD:       /* FD subset */
                decodeFD();
                break;
            default:
                break;
        }
    }

    private boolean decodeRegularOpcode(int opCode) {
        return decodeRegisterPairOpcode(opCode)
            || decodeDirectAccumulatorPairOpcode(opCode)
            || decodeIncDecRegisterOpcode(opCode)
            || decodeLoadImmediateRegisterOpcode(opCode)
            || decodeRegisterTransferOpcode(opCode)
            || decodeAluRegisterOpcode(opCode)
            || decodeRelativeJumpOpcode(opCode)
            || decodeControlOpcode(opCode);
    }

    private boolean decodeRegisterPairOpcode(int opCode) {
        int pair = (opCode >>> 4) & 0x03;

        if ((opCode & 0xcf) == 0x01) {
            writePair16(pair, MemIoImpl.peek16(regPC));
            regPC = (regPC + 2) & 0xffff;
            return true;
        }

        if ((opCode & 0xcf) == 0x03) {
            MemIoImpl.addressOnBus(getPairIR(), 2);
            writePair16(pair, (readPair16(pair) + 1) & 0xffff);
            return true;
        }

        if ((opCode & 0xcf) == 0x0b) {
            MemIoImpl.addressOnBus(getPairIR(), 2);
            writePair16(pair, (readPair16(pair) - 1) & 0xffff);
            return true;
        }

        if ((opCode & 0xcf) == 0x09) {
            MemIoImpl.addressOnBus(getPairIR(), 7);
            setRegHL(add16(getRegHL(), readPair16(pair)));
            return true;
        }

        return false;
    }

    private boolean decodeDirectAccumulatorPairOpcode(int opCode) {
        if ((opCode & 0xef) == 0x02) {
            int address = readPair16((opCode >>> 4) & 0x01);
            MemIoImpl.poke8(address, regA);
            memptr = (regA << 8) | ((address + 1) & 0xff);
            return true;
        }

        if ((opCode & 0xef) == 0x0a) {
            memptr = readPair16((opCode >>> 4) & 0x01);
            regA = MemIoImpl.peek8(memptr++);
            return true;
        }

        return false;
    }

    private boolean decodeIncDecRegisterOpcode(int opCode) {
        int register = (opCode >>> 3) & 0x07;

        if ((opCode & 0xc7) == 0x04) {
            if (register == 0x06) {
                int address = getRegHL();
                int value = inc8(MemIoImpl.peek8(address));
                MemIoImpl.addressOnBus(address, 1);
                MemIoImpl.poke8(address, value);
            } else {
                writeReg8(register, inc8(readReg8(register)));
            }
            return true;
        }

        if ((opCode & 0xc7) == 0x05) {
            if (register == 0x06) {
                int address = getRegHL();
                int value = dec8(MemIoImpl.peek8(address));
                MemIoImpl.addressOnBus(address, 1);
                MemIoImpl.poke8(address, value);
            } else {
                writeReg8(register, dec8(readReg8(register)));
            }
            return true;
        }

        return false;
    }

    private boolean decodeLoadImmediateRegisterOpcode(int opCode) {
        if ((opCode & 0xc7) != 0x06) {
            return false;
        }

        int register = (opCode >>> 3) & 0x07;
        int value = MemIoImpl.peek8(regPC);
        if (register == 0x06) {
            MemIoImpl.poke8(getRegHL(), value);
        } else {
            writeReg8(register, value);
        }
        regPC = (regPC + 1) & 0xffff;
        return true;
    }

    private boolean decodeRegisterTransferOpcode(int opCode) {
        if (opCode < 0x40 || opCode > 0x7f) {
            return false;
        }

        if (opCode == 0x76) {
            halted = true;
            return true;
        }

        writeReg8((opCode >>> 3) & 0x07, readReg8(opCode & 0x07));
        return true;
    }

    private boolean decodeAluRegisterOpcode(int opCode) {
        if (opCode < 0x80 || opCode > 0xbf) {
            return false;
        }

        executeAlu((opCode >>> 3) & 0x07, readReg8(opCode & 0x07));
        return true;
    }

    private boolean decodeRelativeJumpOpcode(int opCode) {
        if (opCode == 0x18) {
            byte offset = (byte) MemIoImpl.peek8(regPC);
            MemIoImpl.addressOnBus(regPC, 5);
            regPC = memptr = (regPC + offset + 1) & 0xffff;
            return true;
        }

        if ((opCode & 0xe7) == 0x20) {
            byte offset = (byte) MemIoImpl.peek8(regPC);
            if (testCondition((opCode >>> 3) & 0x03)) {
                MemIoImpl.addressOnBus(regPC, 5);
                regPC += offset;
                memptr = regPC + 1;
            }
            regPC = (regPC + 1) & 0xffff;
            return true;
        }

        return false;
    }

    private boolean decodeControlOpcode(int opCode) {
        int condition = (opCode >>> 3) & 0x07;

        if ((opCode & 0xc7) == 0xc0) {
            MemIoImpl.addressOnBus(getPairIR(), 1);
            if (testCondition(condition)) {
                regPC = memptr = pop();
            }
            return true;
        }

        if ((opCode & 0xc7) == 0xc2) {
            memptr = MemIoImpl.peek16(regPC);
            if (testCondition(condition)) {
                regPC = memptr;
            } else {
                regPC = (regPC + 2) & 0xffff;
            }
            return true;
        }

        if ((opCode & 0xc7) == 0xc4) {
            memptr = MemIoImpl.peek16(regPC);
            if (testCondition(condition)) {
                MemIoImpl.addressOnBus((regPC + 1) & 0xffff, 1);
                push(regPC + 2);
                regPC = memptr;
            } else {
                regPC = (regPC + 2) & 0xffff;
            }
            return true;
        }

        if ((opCode & 0xc7) == 0xc7) {
            MemIoImpl.addressOnBus(getPairIR(), 1);
            push(regPC);
            regPC = memptr = opCode & 0x38;
            return true;
        }

        if ((opCode & 0xcf) == 0xc1) {
            writeStackPair16((opCode >>> 4) & 0x03, pop());
            return true;
        }

        if ((opCode & 0xcf) == 0xc5) {
            MemIoImpl.addressOnBus(getPairIR(), 1);
            push(readStackPair16((opCode >>> 4) & 0x03));
            return true;
        }

        if ((opCode & 0xc7) == 0xc6) {
            executeAlu((opCode >>> 3) & 0x07, MemIoImpl.peek8(regPC));
            regPC = (regPC + 1) & 0xffff;
            return true;
        }

        return false;
    }

    private int readReg8(int code) {
        switch (code & 0x07) {
            case 0x00:
                return regB;
            case 0x01:
                return regC;
            case 0x02:
                return regD;
            case 0x03:
                return regE;
            case 0x04:
                return regH;
            case 0x05:
                return regL;
            case 0x06:
                return MemIoImpl.peek8(getRegHL());
            case 0x07:
                return regA;
            default:
                return 0;
        }
    }

    private void writeReg8(int code, int value) {
        value &= 0xff;
        switch (code & 0x07) {
            case 0x00:
                regB = value;
                break;
            case 0x01:
                regC = value;
                break;
            case 0x02:
                regD = value;
                break;
            case 0x03:
                regE = value;
                break;
            case 0x04:
                regH = value;
                break;
            case 0x05:
                regL = value;
                break;
            case 0x06:
                MemIoImpl.poke8(getRegHL(), value);
                break;
            case 0x07:
                regA = value;
                break;
            default:
                break;
        }
    }

    private int readPair16(int code) {
        switch (code & 0x03) {
            case 0x00:
                return getRegBC();
            case 0x01:
                return getRegDE();
            case 0x02:
                return getRegHL();
            case 0x03:
                return regSP;
            default:
                return 0;
        }
    }

    private void writePair16(int code, int value) {
        value &= 0xffff;
        switch (code & 0x03) {
            case 0x00:
                setRegBC(value);
                break;
            case 0x01:
                setRegDE(value);
                break;
            case 0x02:
                setRegHL(value);
                break;
            case 0x03:
                regSP = value;
                break;
            default:
                break;
        }
    }

    private int readStackPair16(int code) {
        switch (code & 0x03) {
            case 0x00:
                return getRegBC();
            case 0x01:
                return getRegDE();
            case 0x02:
                return getRegHL();
            case 0x03:
                return getRegAF();
            default:
                return 0;
        }
    }

    private void writeStackPair16(int code, int value) {
        switch (code & 0x03) {
            case 0x00:
                setRegBC(value);
                break;
            case 0x01:
                setRegDE(value);
                break;
            case 0x02:
                setRegHL(value);
                break;
            case 0x03:
                setRegAF(value);
                break;
            default:
                break;
        }
    }

    private void executeAlu(int operation, int operand) {
        switch (operation & 0x07) {
            case 0x00:
                add(operand);
                break;
            case 0x01:
                adc(operand);
                break;
            case 0x02:
                sub(operand);
                break;
            case 0x03:
                sbc(operand);
                break;
            case 0x04:
                and(operand);
                break;
            case 0x05:
                xor(operand);
                break;
            case 0x06:
                or(operand);
                break;
            case 0x07:
                cp(operand);
                break;
            default:
                break;
        }
    }

    private boolean testCondition(int condition) {
        switch (condition & 0x07) {
            case 0x00:
                return (sz5h3pnFlags & ZERO_MASK) == 0;
            case 0x01:
                return (sz5h3pnFlags & ZERO_MASK) != 0;
            case 0x02:
                return !carryFlag;
            case 0x03:
                return carryFlag;
            case 0x04:
                return (sz5h3pnFlags & PARITY_MASK) == 0;
            case 0x05:
                return (sz5h3pnFlags & PARITY_MASK) != 0;
            case 0x06:
                return sz5h3pnFlags < SIGN_MASK;
            case 0x07:
                return sz5h3pnFlags > 0x7f;
            default:
                return false;
        }
    }

    private void exchangeMainAndAlternateRegisters() {
        int work8 = regB;
        regB = regBx;
        regBx = work8;

        work8 = regC;
        regC = regCx;
        regCx = work8;

        work8 = regD;
        regD = regDx;
        regDx = work8;

        work8 = regE;
        regE = regEx;
        regEx = work8;

        work8 = regH;
        regH = regHx;
        regHx = work8;

        work8 = regL;
        regL = regLx;
        regLx = work8;
    }

    private void decodeDD() {
        int opCode = MemIoImpl.fetchOpcode(regPC);
        regPC = (regPC + 1) & 0xffff;
        regR++;
        regIX = decodeDDFD(opCode, regIX);
    }

    private void decodeFD() {
        int opCode = MemIoImpl.fetchOpcode(regPC);
        regPC = (regPC + 1) & 0xffff;
        regR++;
        regIY = decodeDDFD(opCode, regIY);
    }

    private void decodeEDPrefix() {
        int opCode = MemIoImpl.fetchOpcode(regPC);
        regPC = (regPC + 1) & 0xffff;
        regR++;
        decodeED(opCode);
    }

    //Subconjunto de instrucciones 0xCB
    private void decodeCB() {
        int opCode = MemIoImpl.fetchOpcode(regPC);
        regPC = (regPC + 1) & 0xffff;
        regR++;

        int group = opCode >>> 6;
        int bit = (opCode >>> 3) & 0x07;
        int register = opCode & 0x07;

        if (group == 0) {
            int value;
            if (register == 0x06) {
                int address = getRegHL();
                value = rotateCB(bit, MemIoImpl.peek8(address));
                MemIoImpl.addressOnBus(address, 1);
                MemIoImpl.poke8(address, value);
            } else {
                value = rotateCB(bit, readReg8(register));
                writeReg8(register, value);
            }
            return;
        }

        int mask = 1 << bit;
        if (group == 1) {
            if (register == 0x06) {
                int address = getRegHL();
                bit(mask, MemIoImpl.peek8(address));
                sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
                    | ((memptr >>> 8) & FLAG_53_MASK);
                MemIoImpl.addressOnBus(address, 1);
            } else {
                bit(mask, readReg8(register));
            }
            return;
        }

        if (register == 0x06) {
            int address = getRegHL();
            int value = MemIoImpl.peek8(address);
            value = group == 2 ? value & ~mask : value | mask;
            MemIoImpl.addressOnBus(address, 1);
            MemIoImpl.poke8(address, value);
        } else {
            int value = readReg8(register);
            writeReg8(register, group == 2 ? value & ~mask : value | mask);
        }
    }

    private int rotateCB(int operation, int value) {
        switch (operation & 0x07) {
            case 0x00:
                return rlc(value);
            case 0x01:
                return rrc(value);
            case 0x02:
                return rl(value);
            case 0x03:
                return rr(value);
            case 0x04:
                return sla(value);
            case 0x05:
                return sra(value);
            case 0x06:
                return sll(value);
            case 0x07:
                return srl(value);
            default:
                return value & 0xff;
        }
    }

    //Subconjunto de instrucciones 0xDD / 0xFD
    /*
     * Hay que tener en cuenta el manejo de secuencias códigos DD/FD que no
     * hacen nada. Según el apartado 3.7 del documento
     * [http://www.myquest.nl/z80undocumented/z80-documented-v0.91.pdf]
     * secuencias de códigos como FD DD 00 21 00 10 NOP NOP NOP LD HL,1000h
     * activan IY con el primer FD, IX con el segundo DD y vuelven al
     * registro HL con el código NOP. Es decir, si detrás del código DD/FD no
     * viene una instrucción que maneje el registro HL, el código DD/FD
     * "se olvida" y hay que procesar la instrucción como si nunca se
     * hubiera visto el prefijo (salvo por los 4 t-estados que ha costado).
     * Naturalmente, en una serie repetida de DDFD no hay que comprobar las
     * interrupciones entre cada prefijo.
     */
    private int decodeDDFD(int opCode, int regIXY) {
        int decodedIXY = decodeIndexedRegularOpcode(opCode, regIXY);
        if (decodedIXY >= 0) {
            return decodedIXY;
        }

        switch (opCode) {
            case 0xCB: {
                memptr = (regIXY + (byte) MemIoImpl.peek8(regPC)) & 0xffff;
                regPC = (regPC + 1) & 0xffff;
                opCode = MemIoImpl.peek8(regPC);
                MemIoImpl.addressOnBus(regPC, 2);
                regPC = (regPC + 1) & 0xffff;
                decodeDDFDCB(opCode, memptr);
                break;
            }
            case 0xDD:
                prefixOpcode = 0xDD;
                break;
            case 0xE1:
                regIXY = pop();
                break;
            case 0xE3: {
                int work16 = regIXY;
                regIXY = MemIoImpl.peek16(regSP);
                MemIoImpl.addressOnBus((regSP + 1) & 0xffff, 1);
                MemIoImpl.poke8((regSP + 1) & 0xffff, work16 >>> 8);
                MemIoImpl.poke8(regSP, work16);
                MemIoImpl.addressOnBus(regSP, 2);
                memptr = regIXY;
                break;
            }
            case 0xE5:
                MemIoImpl.addressOnBus(getPairIR(), 1);
                push(regIXY);
                break;
            case 0xE9:
                regPC = regIXY;
                break;
            case 0xED:
                prefixOpcode = 0xED;
                break;
            case 0xF9:
                MemIoImpl.addressOnBus(getPairIR(), 2);
                regSP = regIXY;
                break;
            case 0xFD:
                prefixOpcode = 0xFD;
                break;
            default:
                if (breakpointAt.get(regPC)) {
                    opCode = NotifyImpl.breakpoint(regPC, opCode);
                }
                decodeOpcode(opCode);
                break;
        }
        return regIXY;
    }

    private int decodeIndexedRegularOpcode(int opCode, int regIXY) {
        if ((opCode & 0xcf) == 0x09) {
            int pair = (opCode >>> 4) & 0x03;
            int operand = pair == 0x02 ? regIXY : readPair16(pair);
            MemIoImpl.addressOnBus(getPairIR(), 7);
            return add16(regIXY, operand);
        }

        if ((opCode & 0xc7) == 0x04) {
            int register = (opCode >>> 3) & 0x07;
            if (register == 0x04 || register == 0x05) {
                return writeIndexedReg8(register, regIXY, inc8(readIndexedReg8(register, regIXY)));
            }
            if (register == 0x06) {
                int address = indexedAddress(regIXY, 5);
                int value = MemIoImpl.peek8(address);
                MemIoImpl.addressOnBus(address, 1);
                MemIoImpl.poke8(address, inc8(value));
                return regIXY;
            }
            return -1;
        }

        if ((opCode & 0xc7) == 0x05) {
            int register = (opCode >>> 3) & 0x07;
            if (register == 0x04 || register == 0x05) {
                return writeIndexedReg8(register, regIXY, dec8(readIndexedReg8(register, regIXY)));
            }
            if (register == 0x06) {
                int address = indexedAddress(regIXY, 5);
                int value = MemIoImpl.peek8(address);
                MemIoImpl.addressOnBus(address, 1);
                MemIoImpl.poke8(address, dec8(value));
                return regIXY;
            }
            return -1;
        }

        if ((opCode & 0xc7) == 0x06) {
            int register = (opCode >>> 3) & 0x07;
            if (register == 0x04 || register == 0x05) {
                int value = MemIoImpl.peek8(regPC);
                regPC = (regPC + 1) & 0xffff;
                return writeIndexedReg8(register, regIXY, value);
            }
            if (register == 0x06) {
                memptr = (regIXY + (byte) MemIoImpl.peek8(regPC)) & 0xffff;
                regPC = (regPC + 1) & 0xffff;
                int value = MemIoImpl.peek8(regPC);
                MemIoImpl.addressOnBus(regPC, 2);
                regPC = (regPC + 1) & 0xffff;
                MemIoImpl.poke8(memptr, value);
                return regIXY;
            }
            return -1;
        }

        switch (opCode) {
            case 0x21:
                regIXY = MemIoImpl.peek16(regPC);
                regPC = (regPC + 2) & 0xffff;
                return regIXY;
            case 0x22:
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, regIXY);
                regPC = (regPC + 2) & 0xffff;
                return regIXY;
            case 0x23:
                MemIoImpl.addressOnBus(getPairIR(), 2);
                return (regIXY + 1) & 0xffff;
            case 0x2A:
                memptr = MemIoImpl.peek16(regPC);
                regIXY = MemIoImpl.peek16(memptr++);
                regPC = (regPC + 2) & 0xffff;
                return regIXY;
            case 0x2B:
                MemIoImpl.addressOnBus(getPairIR(), 2);
                return (regIXY - 1) & 0xffff;
            default:
                break;
        }

        if (opCode >= 0x40 && opCode <= 0x7f && opCode != 0x76) {
            return decodeIndexedLoad(opCode, regIXY);
        }

        if (opCode >= 0x80 && opCode <= 0xbf) {
            int source = opCode & 0x07;
            if (source != 0x04 && source != 0x05 && source != 0x06) {
                return -1;
            }
            int operand = source == 0x06
                ? MemIoImpl.peek8(indexedAddress(regIXY, 5))
                : readIndexedReg8(source, regIXY);
            executeAlu((opCode >>> 3) & 0x07, operand);
            return regIXY;
        }

        return -1;
    }

    private int decodeIndexedLoad(int opCode, int regIXY) {
        int destination = (opCode >>> 3) & 0x07;
        int source = opCode & 0x07;
        boolean usesIndexedRegister = destination == 0x04 || destination == 0x05
            || source == 0x04 || source == 0x05;
        boolean usesIndexedAddress = destination == 0x06 || source == 0x06;

        if (!usesIndexedRegister && !usesIndexedAddress) {
            return -1;
        }

        if (destination == 0x06) {
            MemIoImpl.poke8(indexedAddress(regIXY, 5), readReg8(source));
            return regIXY;
        }

        if (source == 0x06) {
            writeReg8(destination, MemIoImpl.peek8(indexedAddress(regIXY, 5)));
            return regIXY;
        }

        int value = readIndexedReg8(source, regIXY);
        return writeIndexedReg8(destination, regIXY, value);
    }

    private int indexedAddress(int regIXY, int busTstates) {
        int displacementAddress = regPC;
        int address = (regIXY + (byte) MemIoImpl.peek8(regPC)) & 0xffff;
        if (busTstates != 0) {
            MemIoImpl.addressOnBus(displacementAddress, busTstates);
        }
        regPC = (regPC + 1) & 0xffff;
        memptr = address;
        return address;
    }

    private int readIndexedReg8(int code, int regIXY) {
        switch (code & 0x07) {
            case 0x04:
                return regIXY >>> 8;
            case 0x05:
                return regIXY & 0xff;
            default:
                return readReg8(code);
        }
    }

    private int writeIndexedReg8(int code, int regIXY, int value) {
        value &= 0xff;
        switch (code & 0x07) {
            case 0x04:
                return (value << 8) | (regIXY & 0x00ff);
            case 0x05:
                return (regIXY & 0xff00) | value;
            default:
                writeReg8(code, value);
                return regIXY;
        }
    }

    // Subconjunto de instrucciones 0xDD/0xFD 0xCB
    private void decodeDDFDCB(int opCode, int address) {
        int group = opCode >>> 6;
        int bit = (opCode >>> 3) & 0x07;
        int register = opCode & 0x07;

        if (group == 0) {
            int value = rotateCB(bit, MemIoImpl.peek8(address));
            MemIoImpl.addressOnBus(address, 1);
            MemIoImpl.poke8(address, value);
            copyToRegister(opCode, value);
            return;
        }

        int mask = 1 << bit;
        if (group == 1) {
            bit(mask, MemIoImpl.peek8(address));
            sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
                | ((address >>> 8) & FLAG_53_MASK);
            MemIoImpl.addressOnBus(address, 1);
            return;
        }

        int value = MemIoImpl.peek8(address);
        value = group == 2 ? value & ~mask : value | mask;
        MemIoImpl.addressOnBus(address, 1);
        MemIoImpl.poke8(address, value);
        if (register != 0x06) {
            copyToRegister(opCode, value);
        }
    }

    private void decodeED(int opCode) {
        switch (opCode) {
            case 0x40: {     /* IN B,(C) */
                memptr = getRegBC();
                regB = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regB];
                flagQ = true;
                break;
            }
            case 0x41: {     /* OUT (C),B */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regB);
                break;
            }
            case 0x42: {     /* SBC HL,BC */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                sbc16(getRegBC());
                break;
            }
            case 0x43: {     /* LD (nn),BC */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, getRegBC());
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x44:
            case 0x4C:
            case 0x54:
            case 0x5C:
            case 0x64:
            case 0x6C:
            case 0x74:
            case 0x7C: {     /* NEG */
                int aux = regA;
                regA = 0;
                sub(aux);
                break;
            }
            case 0x45:
            case 0x4D:       /* RETI */
            case 0x55:
            case 0x5D:
            case 0x65:
            case 0x6D:
            case 0x75:
            case 0x7D: {     /* RETN */
                ffIFF1 = ffIFF2;
                regPC = memptr = pop();
                break;
            }
            case 0x46:
            case 0x4E:
            case 0x66:
            case 0x6E: {     /* IM 0 */
                setIM(IntMode.IM0);
                break;
            }
            case 0x47: {     /* LD I,A */
                /*
                 * El contended-tstate se produce con el contenido de I *antes*
                 * de ser copiado el del registro A. Detalle importante.
                 */
                MemIoImpl.addressOnBus(getPairIR(), 1);
                regI = regA;
                break;
            }
            case 0x48: {     /* IN C,(C) */
                memptr = getRegBC();
                regC = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regC];
                flagQ = true;
                break;
            }
            case 0x49: {     /* OUT (C),C */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regC);
                break;
            }
            case 0x4A: {     /* ADC HL,BC */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                adc16(getRegBC());
                break;
            }
            case 0x4B: {     /* LD BC,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                setRegBC(MemIoImpl.peek16(memptr++));
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x4F: {     /* LD R,A */
                MemIoImpl.addressOnBus(getPairIR(), 1);
                setRegR(regA);
                break;
            }
            case 0x50: {     /* IN D,(C) */
                memptr = getRegBC();
                regD = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regD];
                flagQ = true;
                break;
            }
            case 0x51: {     /* OUT (C),D */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regD);
                break;
            }
            case 0x52: {     /* SBC HL,DE */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                sbc16(getRegDE());
                break;
            }
            case 0x53: {     /* LD (nn),DE */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, getRegDE());
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x56:
            case 0x76: {     /* IM 1 */
                setIM(IntMode.IM1);
                break;
            }
            case 0x57: {     /* LD A,I */
                MemIoImpl.addressOnBus(getPairIR(), 1);
                regA = regI;
                sz5h3pnFlags = sz53n_addTable[regA];
                if (ffIFF2 && !MemIoImpl.isActiveINT()) {
                    sz5h3pnFlags |= PARITY_MASK;
                }
                flagQ = true;
                break;
            }
            case 0x58: {     /* IN E,(C) */
                memptr = getRegBC();
                regE = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regE];
                flagQ = true;
                break;
            }
            case 0x59: {     /* OUT (C),E */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regE);
                break;
            }
            case 0x5A: {     /* ADC HL,DE */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                adc16(getRegDE());
                break;
            }
            case 0x5B: {     /* LD DE,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                setRegDE(MemIoImpl.peek16(memptr++));
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x5E:
            case 0x7E: {     /* IM 2 */
                setIM(IntMode.IM2);
                break;
            }
            case 0x5F: {     /* LD A,R */
                MemIoImpl.addressOnBus(getPairIR(), 1);
                regA = getRegR();
                sz5h3pnFlags = sz53n_addTable[regA];
                if (ffIFF2 && !MemIoImpl.isActiveINT()) {
                    sz5h3pnFlags |= PARITY_MASK;
                }
                flagQ = true;
                break;
            }
            case 0x60: {     /* IN H,(C) */
                memptr = getRegBC();
                regH = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regH];
                flagQ = true;
                break;
            }
            case 0x61: {     /* OUT (C),H */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regH);
                break;
            }
            case 0x62: {     /* SBC HL,HL */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                sbc16(getRegHL());
                break;
            }
            case 0x63: {     /* LD (nn),HL */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, getRegHL());
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x67: {     /* RRD */
                rrd();
                break;
            }
            case 0x68: {     /* IN L,(C) */
                memptr = getRegBC();
                regL = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regL];
                flagQ = true;
                break;
            }
            case 0x69: {     /* OUT (C),L */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regL);
                break;
            }
            case 0x6A: {     /* ADC HL,HL */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                adc16(getRegHL());
                break;
            }
            case 0x6B: {     /* LD HL,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                setRegHL(MemIoImpl.peek16(memptr++));
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x6F: {     /* RLD */
                rld();
                break;
            }
            case 0x70: {     /* IN (C) */
                memptr = getRegBC();
                int inPort = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[inPort];
                flagQ = true;
                break;
            }
            case 0x71: {     /* OUT (C),0 */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, 0x00);
                break;
            }
            case 0x72: {     /* SBC HL,SP */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                sbc16(regSP);
                break;
            }
            case 0x73: {     /* LD (nn),SP */
                memptr = MemIoImpl.peek16(regPC);
                MemIoImpl.poke16(memptr++, regSP);
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0x78: {     /* IN A,(C) */
                memptr = getRegBC();
                regA = MemIoImpl.inPort(memptr++);
                sz5h3pnFlags = sz53pn_addTable[regA];
                flagQ = true;
                break;
            }
            case 0x79: {     /* OUT (C),A */
                memptr = getRegBC();
                MemIoImpl.outPort(memptr++, regA);
                break;
            }
            case 0x7A: {     /* ADC HL,SP */
                MemIoImpl.addressOnBus(getPairIR(), 7);
                adc16(regSP);
                break;
            }
            case 0x7B: {     /* LD SP,(nn) */
                memptr = MemIoImpl.peek16(regPC);
                regSP = MemIoImpl.peek16(memptr++);
                regPC = (regPC + 2) & 0xffff;
                break;
            }
            case 0xA0: {     /* LDI */
                ldi();
                break;
            }
            case 0xA1: {     /* CPI */
                cpi();
                break;
            }
            case 0xA2: {     /* INI */
                ini();
                break;
            }
            case 0xA3: {     /* OUTI */
                outi();
                break;
            }
            case 0xA8: {     /* LDD */
                ldd();
                break;
            }
            case 0xA9: {     /* CPD */
                cpd();
                break;
            }
            case 0xAA: {     /* IND */
                ind();
                break;
            }
            case 0xAB: {     /* OUTD */
                outd();
                break;
            }
            case 0xB0: {     /* LDIR */
                ldi();
                if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegDE() - 1) & 0xffff, 5);
                    sz5h3pnFlags &= ~FLAG_53_MASK;
                    sz5h3pnFlags |= ((regPC >>> 8) & FLAG_53_MASK);
                }
                break;
            }
            case 0xB1: {     /* CPIR */
                cpi();
                if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK
                    && (sz5h3pnFlags & ZERO_MASK) == 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegHL() - 1) & 0xffff, 5);
                    sz5h3pnFlags &= ~FLAG_53_MASK;
                    sz5h3pnFlags |= ((regPC >>> 8) & FLAG_53_MASK);
                }
                break;
            }
            case 0xB2: {     /* INIR */
                ini();
                if (regB != 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegHL() - 1) & 0xffff, 5);
                    adjustINxROUTxRFlags();
                }
                break;
            }
            case 0xB3: {     /* OTIR */
                outi();
                if (regB != 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus(getRegBC(), 5);
                    adjustINxROUTxRFlags();
                }
                break;
            }
            case 0xB8: {     /* LDDR */
                ldd();
                if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegDE() + 1) & 0xffff, 5);
                    sz5h3pnFlags &= ~FLAG_53_MASK;
                    sz5h3pnFlags |= ((regPC >>> 8) & FLAG_53_MASK);
                }
                break;
            }
            case 0xB9: {     /* CPDR */
                cpd();
                if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK
                    && (sz5h3pnFlags & ZERO_MASK) == 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegHL() + 1) & 0xffff, 5);
                    sz5h3pnFlags &= ~FLAG_53_MASK;
                    sz5h3pnFlags |= ((regPC >>> 8) & FLAG_53_MASK);
                }
                break;
            }
            case 0xBA: {     /* INDR */
                ind();
                if (regB != 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus((getRegHL() + 1) & 0xffff, 5);
                    adjustINxROUTxRFlags();
                }
                break;
            }
            case 0xBB: {     /* OTDR */
                outd();
                if (regB != 0) {
                    regPC = (regPC - 2) & 0xffff;
                    memptr = regPC + 1;
                    MemIoImpl.addressOnBus(getRegBC(), 5);
                    adjustINxROUTxRFlags();
                }
                break;
            }
            case 0xDD:
                prefixOpcode = 0xDD;
                break;
            case 0xED:
                prefixOpcode = 0xED;
                break;
            case 0xFD:
                prefixOpcode = 0xFD;
                break;
            default: {
//                System.out.println("Error instrucción ED " + Integer.toHexString(opCode));
                break;
            }
        }
    }

    private void copyToRegister(int opCode, int value)
    {
        switch(opCode & 0x07)
        {
            case 0x00:
                regB = value;
                break;
            case 0x01:
                regC = value;
                break;
            case 0x02:
                regD = value;
                break;
            case 0x03:
                regE = value;
                break;
            case 0x04:
                regH = value;
                break;
            case 0x05:
                regL = value;
                break;
            case 0x07:
                regA = value;
            default:
                break;
        }
    }

    private void adjustINxROUTxRFlags()
    {
        sz5h3pnFlags &= ~FLAG_53_MASK;
        sz5h3pnFlags |= (regPC >>> 8) & FLAG_53_MASK;

        int pf = sz5h3pnFlags & PARITY_MASK;
        if (carryFlag) {
            int addsub = 1 - (sz5h3pnFlags & ADDSUB_MASK);
            pf ^= sz53pn_addTable[(regB + addsub) & 0x07] ^ PARITY_MASK;
            if ((regB & 0x0F) == (addsub != 1 ? 0x00 : 0x0F))
                sz5h3pnFlags |= HALFCARRY_MASK;
            else
                sz5h3pnFlags &= ~HALFCARRY_MASK;
        } else {
            pf ^= sz53pn_addTable[regB & 0x07] ^ PARITY_MASK;
            sz5h3pnFlags &= ~HALFCARRY_MASK;
        }

        if ((pf & PARITY_MASK) == PARITY_MASK)
            sz5h3pnFlags |= PARITY_MASK;
        else
            sz5h3pnFlags &= ~PARITY_MASK;
    }
}
