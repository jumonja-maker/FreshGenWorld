package dev.freshgenerator.audit;

import com.google.gson.*;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated audit: top-down maps of the drainage model on the real world fields (no chunk generation).
 * FG_HYDRO_MAPS = JSON [{name, x, z, size, step}] (x, z = window centre). Writes hydro-*.png and hydro-maps.json. */
public final class HydroMapAudit implements ModInitializer {
    private static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    private volatile int shutdown = -1;

    @Override public void onInitialize() {
        if (!"true".equals(System.getenv("FG_HYDRO_MAP_AUDIT"))) return;
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            try { run(s); } catch (Throwable e) { System.err.println("HYDRO_MAP_FAIL"); e.printStackTrace(); }
            finally { shutdown = 20; }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> { if (shutdown > 0 && --shutdown == 0) s.halt(false); });
    }

    private void run(MinecraftServer server) throws Exception {
        var level = server.overworld();
        var gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        HydroField field = HydrologyDensity.field(level.getChunkSource().randomState(), gen.generatorSettings().value());
        if (field == null) throw new AssertionError("No hydrology field in this world");
        String probe = System.getenv("FG_HYDRO_PROBE");
        if (probe != null) for (String pt : probe.split(";")) {
            String[] xz = pt.split(",");
            System.out.println("HYDRO_PROBE " + pt + " :: " + field.model().describe(Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())));
        }
        String cyc = System.getenv("FG_HYDRO_CYCLES");
        if (cyc != null) {
            String[] b = cyc.split(",");
            for (String line : field.model().cycles(Integer.parseInt(b[0]), Integer.parseInt(b[1]), Integer.parseInt(b[2]), Integer.parseInt(b[3]), 20))
                System.out.println("HYDRO_" + line);
            System.out.println("HYDRO_CYCLES_DONE");
        }
        JsonArray specs = JsonParser.parseString(Files.readString(Path.of(System.getenv("FG_HYDRO_MAPS")))).getAsJsonArray();
        List<Object> out = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (JsonElement e : specs) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            int size = o.get("size").getAsInt(), step = o.get("step").getAsInt();
            int x0 = o.get("x").getAsInt() - size / 2, z0 = o.get("z").getAsInt() - size / 2, n = size / step;
            long t0 = System.nanoTime();
            double[] t = new double[n * n], base = new double[n * n];
            int[] water = new int[n * n], kind = new int[n * n];
            List<Future<?>> jobs = new ArrayList<>();
            AtomicInteger rows = new AtomicInteger();
            for (int w = 0; w < 4; w++) {
                jobs.add(pool.submit(() -> {
                    HydroField.Column c = new HydroField.Column();
                    for (int b; (b = rows.getAndIncrement()) < n; ) for (int a = 0; a < n; a++) {
                        field.column(x0 + a * step, z0 + b * step, c);
                        int i = a + n * b;
                        t[i] = c.terrain; base[i] = c.base; water[i] = c.effectiveWater(); kind[i] = c.kind;
                    }
                }));
            }
            for (Future<?> j : jobs) j.get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Integer> counts = new TreeMap<>();
            int carved = 0, wetLand = 0;
            double maxCut = 0;
            for (int i = 0; i < n * n; i++) {
                boolean wet = water[i] != HydroModel.NO_WATER && t[i] <= water[i];
                String k = switch (kind[i]) { case HydroField.RIVER -> wet ? "river" : "river_dry_edge"; case HydroField.LAKE -> "lake";
                    case HydroField.POOL -> "pool"; case HydroField.DRY -> "dry_wash"; default -> wet ? "sea" : "land"; };
                counts.merge(k, 1, Integer::sum);
                if (t[i] < base[i] - 0.5) { carved++; maxCut = Math.max(maxCut, base[i] - t[i]); }
                if (wet && t[i] >= 63) wetLand++;
            }
            int[] setting = new int[n * n];
            for (int b = 0; b < n; b++) for (int a = 0; a < n; a++)
                setting[a + n * b] = field.model().setting(HydroModel.nodeAt(x0 + a * step, z0 + b * step)).ordinal();
            // Per climate class: land samples, lake samples and the mean lake depth (water above the ground).
            var classes = dev.freshgenerator.core.hydro.HydroClimate.Setting.values();
            long[] landN = new long[classes.length], lakeN = new long[classes.length];
            double[] lakeDepth = new double[classes.length];
            for (int i = 0; i < n * n; i++) {
                boolean landOrLake = t[i] >= 62.5 || kind[i] == HydroField.LAKE;
                if (!landOrLake) continue;
                landN[setting[i]]++;
                if (kind[i] == HydroField.LAKE && water[i] != HydroModel.NO_WATER) { lakeN[setting[i]]++; lakeDepth[setting[i]] += water[i] + 1 - t[i]; }
            }
            StringBuilder stats = new StringBuilder();
            for (int c = 0; c < classes.length; c++) if (landN[c] > 0)
                stats.append(String.format(" %s land=%d lake%%=%.2f depth=%.2f", classes[c], landN[c], 100.0 * lakeN[c] / landN[c], lakeN[c] == 0 ? 0 : lakeDepth[c] / lakeN[c]));
            System.out.println("HYDRO_CLASSES " + name + stats);
            BufferedImage img = render(t, water, kind, setting, n, step);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int reachCount = 0, wetReaches = 0, falls = 0;
            Map<String, Integer> fallHeights = new TreeMap<>();
            int[] widths = new int[6]; // 3-5, 5-8, 8-13, 13-20, 20-30, 30+ (max width of wet reaches)
            for (var r : field.reachesIn(x0, z0, x0 + size, z0 + size)) {
                if (r.wet) { double mw = r.maxWidth() / 1.22; widths[mw < 5 ? 0 : mw < 8 ? 1 : mw < 13 ? 2 : mw < 20 ? 3 : mw < 30 ? 4 : 5]++; }
                double[] xs = r.xs(), zs = r.zs();
                reachCount++; if (r.wet) wetReaches++;
                g.setColor(r.wet ? new java.awt.Color(20, 60, 255) : new java.awt.Color(255, 140, 0));
                g.setStroke(new java.awt.BasicStroke((float) Math.max(1, r.maxWidth() / step)));
                for (int i = 0; i + 1 < xs.length; i++)
                    g.drawLine((int) ((xs[i] - x0) / step), (int) ((zs[i] - z0) / step), (int) ((xs[i + 1] - x0) / step), (int) ((zs[i + 1] - z0) / step));
                for (int fi = 0; fi < r.falls(field.model()); fi++) {
                    double[] f = r.fallPoint(field.model(), fi); falls++;
                    int fh = r.fallHeight(field.model(), fi);
                    fallHeights.merge(fh >= 12 ? "12+" : fh >= 6 ? "6-11" : "3-5", 1, Integer::sum);
                    g.setColor(java.awt.Color.RED); int px = (int) ((f[0] - x0) / step), pz = (int) ((f[1] - z0) / step);
                    g.fillOval(px - 3, pz - 3, 7, 7);
                }
            }
            g.dispose();
            ImageIO.write(img, "png", Path.of("hydro-" + name + ".png").toFile());
            Map<String, Number> integrity = new TreeMap<>(field.model().integrity(x0, z0, x0 + size, z0 + size));
            axisChecks(field, x0, z0, x0 + size, z0 + size, integrity);
            System.out.println("HYDRO_INTEGRITY " + name + " " + integrity);
            System.out.println("HYDRO_WIDTHS " + name + " 3-5=" + widths[0] + " 5-8=" + widths[1] + " 8-13=" + widths[2] + " 13-20=" + widths[3] + " 20-30=" + widths[4] + " 30+=" + widths[5]);
            counts.put("reaches", reachCount); counts.put("wet_reaches", wetReaches); counts.put("falls", falls);
            fallHeights.forEach((key, v) -> counts.put("fall_" + key, v));
            out.add(Map.of("name", name, "x0", x0, "z0", z0, "size", size, "step", step, "milliseconds", ms, "counts", counts,
                    "carved_columns", carved, "max_cut", maxCut, "inland_water_columns", wetLand));
            System.out.println("HYDRO_MAP " + name + " ms=" + ms + " " + counts);
        }
        pool.shutdown();
        Files.writeString(Path.of("hydro-maps.json"), G.toJson(Map.of("seed", level.getSeed(), "maps", out,
                "model_tiles", field.model().tilesCached())) + "\n");
        System.out.println("HYDRO_MAP_PASS seed=" + level.getSeed());
    }

    private static final int[] TINT = {0xE8F0FF, 0x9FC7A0, 0xFFFFFF, 0x7FB86F, 0x6FA08F, 0x3F9A3F, 0xC8C070, 0xE8D8A0, 0xD08850};
    static BufferedImage render(double[] t, int[] water, int[] kind, int[] setting, int n, int step) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) {
            int i = a + n * b;
            double hx = t[Math.min(n - 1, a + 1) + n * b] - t[Math.max(0, a - 1) + n * b];
            double hz = t[a + n * Math.min(n - 1, b + 1)] - t[a + n * Math.max(0, b - 1)];
            double shade = Math.max(0.35, Math.min(1.3, 0.85 - 0.22 * (hx + hz) / step));
            int rgb;
            boolean wet = water[i] != HydroModel.NO_WATER && t[i] <= water[i];
            if (wet) {
                double depth = Math.min(1, (water[i] - t[i]) / 10);
                int r = 35, g = (int) (115 - 60 * depth), bl = (int) (215 - 60 * depth);
                if (kind[i] == HydroField.POOL) { r = 230; g = 40; bl = 40; }
                else if (kind[i] == HydroField.LAKE) { r = 20; g = (int) (95 - 50 * depth); bl = (int) (200 - 60 * depth); }
                else if (kind[i] == HydroField.RIVER) { r = 30; g = (int) (140 - 50 * depth); bl = 235; }
                else if (t[i] < 63) { r = 25; g = (int) (70 - 30 * depth); bl = (int) (150 - 40 * depth); }
                rgb = (r << 16) | (g << 8) | bl;
            } else if (kind[i] == HydroField.DRY) rgb = 0xD8B070;
            else {
                int tint = TINT[setting[i]];
                double f = Math.max(0, Math.min(1, (t[i] - 63) / 160)) * 0.35;
                int r = (int) ((((tint >> 16) & 255) * (1 - f) + 255 * f) * shade), g = (int) ((((tint >> 8) & 255) * (1 - f) + 255 * f) * shade),
                        bl = (int) (((tint & 255) * (1 - f) + 255 * f) * shade);
                rgb = (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, bl));
            }
            img.setRGB(a, b, rgb);
        }
        return img;
    }

    /** Final columns along every wet reach of a box (public API only, so the same observer runs on older builds):
     * dry columns on the axis of a wet channel, and open edges (a dry neighbour at or below the water). */
    public static void axisChecks(HydroField field, int x0, int z0, int x1, int z1, Map<String, Number> out) {
        int axis = 0, axisDry = 0, edges = 0, open = 0;
        HydroField.Column c = new HydroField.Column(), n = new HydroField.Column();
        HashSet<Long> seen = new HashSet<>();
        int[] nx = {1, -1, 0, 0}, nz = {0, 0, 1, -1};
        for (var r : field.reachesIn(x0, z0, x1, z1)) {
            if (!r.wet) continue;
            double[] xs = r.xs(), zs = r.zs();
            for (int i = 0; i < xs.length; i++) {
                int x = (int) Math.round(xs[i]), z = (int) Math.round(zs[i]);
                if (x < x0 || x > x1 || z < z0 || z > z1 || !seen.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                field.column(x, z, c);
                axis++;
                if (!c.wetIncludingSea()) { if (axisDry++ == 0) { out.put("axis_dry_x", x); out.put("axis_dry_z", z); } continue; }
                int w = c.effectiveWater();
                for (int d = 0; d < 4; d++) {
                    field.column(x + nx[d], z + nz[d], n);
                    edges++;
                    if (!n.wetIncludingSea() && n.terrain <= w && open++ == 0) { out.put("open_edge_x", x + nx[d]); out.put("open_edge_z", z + nz[d]); }
                }
            }
        }
        out.put("axis_samples", axis); out.put("axis_dry", axisDry); out.put("axis_edges", edges); out.put("axis_open_edges", open);
    }
}
