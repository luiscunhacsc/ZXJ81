package zxj81;

import java.nio.file.Path;

public final class ZX81Smoke {
    private ZX81Smoke() {
    }

    public static void main(String[] args) throws Exception {
        ZX81Machine machine = new ZX81Machine(Path.of("."));
        machine.hardReset();
        for (int i = 0; i < 20; i++) {
            machine.runFrame();
        }
        System.out.println("ZXJ81 smoke OK");
    }
}
