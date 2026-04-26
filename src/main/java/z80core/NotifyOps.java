package z80core;

/**
 *
 * @author jsanchez (original work); Java port and Z80 core optimization for
 * ISA regularities: Luís Simões da Cunha, 2026.
 */
public interface NotifyOps {
    int breakpoint(int address, int opcode);
    void execDone();
}
