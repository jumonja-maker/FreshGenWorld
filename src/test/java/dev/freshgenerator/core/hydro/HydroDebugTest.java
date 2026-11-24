package dev.freshgenerator.core.hydro;

import org.junit.jupiter.api.Test;

class HydroDebugTest {
    @Test void printOutletFloodplain() {
        HydroModel m = new HydroModel(HydroModelTest.pitPlane(0, 0.15), 7);
        HydroField f = new HydroField(m);
        HydroField.Column c = new HydroField.Column();
        // find the outlet river centre around x=700: scan z for the deepest wet column
        int x = 700, zc = 0; double best = 1e9;
        for (int z = -300; z <= 100; z++) { f.column(x, z, c); if (c.wet() && c.terrain < best) { best = c.terrain; zc = z; } }
        System.out.println("OUTLET centre z=" + zc + " terrain " + best);
        for (int off : new int[]{0, 4, 8, 14}) {
            StringBuilder sb = new StringBuilder("ROW off " + off + ":");
            for (int xx = 520; xx <= 760; xx += 2) { f.column(xx, zc + off, c); sb.append(String.format(" %.1f", c.terrain)); }
            System.out.println(sb);
        }
        StringBuilder w = new StringBuilder("WATER:");
        for (int xx = 520; xx <= 760; xx += 2) { f.column(xx, zc, c); w.append(" ").append(c.water); }
        System.out.println(w);
        StringBuilder b = new StringBuilder("BASE off 8:");
        for (int xx = 520; xx <= 760; xx += 2) { f.column(xx, zc + 8, c); b.append(String.format(" %.1f", c.base)); }
        System.out.println(b);
    }
}
