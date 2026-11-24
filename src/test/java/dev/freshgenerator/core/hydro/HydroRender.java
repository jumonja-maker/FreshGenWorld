package dev.freshgenerator.core.hydro;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Top-down debug image of a hydrology field: hillshaded ground, rivers and lakes (blue by depth), dry
 * channels (tan), plunge pools (red). Used by tests and audits to look at the network. */
public final class HydroRender {
    private HydroRender() { }

    public static void render(HydroField field, int x0, int z0, int size, int step, File out) throws IOException {
        int n = size / step;
        double[] t = new double[n * n];
        int[] water = new int[n * n], kind = new int[n * n];
        HydroField.Column c = new HydroField.Column();
        for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) {
            field.column(x0 + a * step, z0 + b * step, c);
            int i = a + n * b;
            t[i] = c.terrain; water[i] = c.effectiveWater(); kind[i] = c.kind;
        }
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) {
            int i = a + n * b;
            double hx = t[Math.min(n - 1, a + 1) + n * b] - t[Math.max(0, a - 1) + n * b];
            double hz = t[a + n * Math.min(n - 1, b + 1)] - t[a + n * Math.max(0, b - 1)];
            double shade = Math.max(0.35, Math.min(1.25, 0.85 - 0.18 * (hx + hz) / step));
            int rgb;
            if (water[i] != HydroModel.NO_WATER && t[i] <= water[i]) {
                double depth = Math.min(1, (water[i] - t[i]) / 8);
                int r = (int) (40 - 25 * depth), g = (int) (110 - 50 * depth), bl = (int) (210 - 40 * depth);
                if (kind[i] == HydroField.POOL) { r = 200; g = 40; bl = 40; }
                else if (kind[i] == HydroField.LAKE) { r = (int) (30 - 20 * depth); g = (int) (80 - 40 * depth); bl = (int) (190 - 50 * depth); }
                rgb = (r << 16) | (g << 8) | bl;
            } else if (kind[i] == HydroField.DRY) {
                rgb = 0xD2B48C;
            } else {
                double h = t[i];
                double f = Math.max(0, Math.min(1, (h - 63) / 120));
                int r = (int) ((70 + 120 * f) * shade), g = (int) ((130 + 60 * f - 40 * f * f) * shade), bl = (int) ((60 + 110 * f) * shade);
                rgb = (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
            }
            img.setRGB(a, b, rgb);
        }
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
