package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.nio.file.*;
import java.util.*;

/** Audit-only karst cave observer (0.9.0). Parts A-D read the final cave density
 * (shape before aquifer fluids, structures and features); part E reads actual FULL
 * blocks. Every number is finite-window evidence at the recorded sampling step. */
public final class KarstCaveAudit implements ModInitializer {
    private static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    private static final String P = "freshgenerator:highlands_v2/", K = P + "karst/";
    private final SamplerContext context = SamplerContext.builder().enableCaches().build();
    private ServerLevel level;
    private DensitySampler height, body, doline, ramp, pothole, lUp, lMain, lDeep;
    private int shutdown = -1;

    @Override public void onInitialize() {
        if (!"true".equals(System.getenv("FG_KARST_AUDIT"))) return;
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { try { run(s); } catch (Throwable e) { System.err.println("KARST_AUDIT_FAIL"); e.printStackTrace(); } finally { shutdown = 20; } });
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (shutdown > 0 && --shutdown == 0) s.halt(false); });
    }
    private DensitySampler sampler(MinecraftServer server, String id) {
        return level.getChunkSource().randomState().getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
            .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id))).value());
    }
    private float v(DensitySampler s, int x, int y, int z) { return s.sampleValue(context, x, y, z); }

    private void run(MinecraftServer server) throws Exception {
        level = server.overworld();
        var gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        if (!gen.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2")) throw new AssertionError("Wrong generator");
        boolean legacy = "true".equals(System.getenv("FG_KARST_LEGACY"));
        height = sampler(server, P + "coherent/height");
        body = sampler(server, legacy ? P + "cave_network/final_body" : K + "final_body");
        var report = new LinkedHashMap<String, Object>(); report.put("seed", level.getSeed()); report.put("legacy_cave_network", legacy);
        if (!legacy) {
            doline = sampler(server, K + "doline"); ramp = sampler(server, K + "ramp"); pothole = sampler(server, K + "pothole");
            lUp = sampler(server, K + "level_upper"); lMain = sampler(server, K + "level_main"); lDeep = sampler(server, K + "level_deep");
            int half = Integer.parseInt(System.getenv().getOrDefault("FG_KARST_HALF", "4096"));
            var census = entrances(half, 8); report.put("entrances", census);
            report.put("levels", levels(1024, 2));
            report.put("pits", pits(2048, 4));
            @SuppressWarnings("unchecked") var mouths = (List<int[]>) census.remove("_mouths");
            report.put("entrance_connectivity", connectivity(mouths));
            // Vertical x-y and z-y sections through the first mouth of each type ('#' rock, '.' open).
            var sections = new LinkedHashMap<String, Object>(); var done = new HashSet<Integer>();
            for (int[] m : mouths) if (done.add(m[2])) {
                var rows = new ArrayList<String>(); int top = (int) Math.floor(v(height, m[0], 0, m[1])) + 4;
                for (int y = top; y >= -60; y -= 2) { var sb = new StringBuilder();
                    for (int d = -80; d <= 80; d += 2) sb.append(v(body, m[0] + d, y, m[1]) < 0 ? (y > v(height, m[0] + d, 0, m[1]) ? ' ' : '.') : '#');
                    sb.append("  "); for (int d = -80; d <= 80; d += 2) sb.append(v(body, m[0], y, m[1] + d) < 0 ? (y > v(height, m[0], 0, m[1] + d) ? ' ' : '.') : '#');
                    rows.add(String.format("%4d %s", y, sb)); }
                sections.put(m[2] + "@" + m[0] + "," + m[1], rows);
                // Open cells of this entrance field (not the whole cave) by depth below the surface.
                var field = m[2] == 1 ? doline : m[2] == 2 ? ramp : pothole; var hist = new TreeMap<Integer, Integer>();
                for (int dx = -160; dx <= 160; dx += 4) for (int dz = -160; dz <= 160; dz += 4) {
                    int x = m[0] + dx, z = m[1] + dz; float hh = v(height, x, 0, z);
                    for (int depth = 0; depth <= 100; depth += 4) if (v(field, x, (int) hh - depth, z) < 0) hist.merge(depth, 1, Integer::sum); }
                sections.put(m[2] + "@" + m[0] + "," + m[1] + " field_open_by_depth", hist.toString());
                if (m[2] == 2) { var line = new ArrayList<String>(); var s = level.getServer();
                    var q = sampler(s, K + "ramp_noise"); var t = sampler(s, K + "ramp_depth"); var c = sampler(s, K + "ramp_centre"); var a = sampler(s, K + "plan_a_upper");
                    for (int d = -160; d <= 160; d += 16) { int x = m[0] + d; line.add(String.format("dx=%d H=%.1f q=%.3f t=%.3f yc=%.1f ga=%.4f", d, v(height, x, 0, m[1]), v(q, x, 0, m[1]), v(t, x, 0, m[1]), v(c, x, 0, m[1]), v(a, x, 0, m[1]))); }
                    sections.put("ramp_line", line); } }
            report.put("mouth_sections", sections);
        }
        String windows = System.getenv("FG_KARST_WINDOWS");
        if (windows != null) report.put("full_fluids", fluids(Path.of(windows)));
        report.put("scope", "A-D: final cave density (pre-fluid shape) at the recorded step; E: actual FULL blocks. Finite windows only.");
        Files.writeString(Path.of("karst-audit.json"), G.toJson(report) + "\n");
        System.out.println("KARST_AUDIT_PASS");
    }

    /** A. Openings 3 blocks below the surface, by entrance type, as connected patches. */
    private Map<String, Object> entrances(int half, int step) {
        int n = 2 * half / step; byte[] type = new byte[n * n]; long columns = 0;
        for (int iz = 0; iz < n; iz++) for (int ix = 0; ix < n; ix++) {
            int x = -half + ix * step, z = -half + iz * step; float h = v(height, x, 0, z); columns++;
            int y = (int) Math.floor(h) - 3; if (v(body, x, y, z) >= 0) continue;
            byte t = v(doline, x, y, z) < 0 ? (byte) 1 : v(ramp, x, y, z) < 0 ? (byte) 2 : v(pothole, x, y, z) < 0 ? (byte) 3 : (byte) 4;
            type[iz * n + ix] = t;
        }
        var counts = new TreeMap<String, Integer>(); var areas = new TreeMap<String, List<Integer>>(); var mouths = new ArrayList<int[]>();
        String[] names = {"", "doline", "inclined", "pothole", "other"}; boolean[] seen = new boolean[n * n];
        for (int i = 0; i < n * n; i++) if (type[i] != 0 && !seen[i]) {
            byte t = type[i]; int size = 0; Deque<Integer> q = new ArrayDeque<>(); q.add(i); seen[i] = true; long sx = 0, sz = 0;
            while (!q.isEmpty()) { int c = q.poll(); size++; sx += c % n; sz += c / n;
                for (int d : new int[]{1, -1, n, -n}) { int o = c + d; if (o < 0 || o >= n * n || seen[o] || type[o] == 0) continue;
                    if ((d == 1 || d == -1) && o / n != c / n) continue; seen[o] = true; q.add(o); } }
            counts.merge(names[t], 1, Integer::sum); areas.computeIfAbsent(names[t], k -> new ArrayList<>()).add(size * step * step);
            if (t != 4 && mouths.stream().filter(mm -> mm[2] == t).count() < 6) mouths.add(new int[]{-half + (int) (sx / size) * step, -half + (int) (sz / size) * step, t});
        }
        double km2 = (2.0 * half) * (2.0 * half) / 1e6;
        var r = new LinkedHashMap<String, Object>(); r.put("area_km2", km2); r.put("step_blocks", step); r.put("columns", columns);
        r.put("patches", counts); var perKm = new TreeMap<String, Double>(); counts.forEach((k, c) -> perKm.put(k, c / km2)); r.put("patches_per_km2", perKm);
        var med = new TreeMap<String, Object>(); areas.forEach((k, l) -> { Collections.sort(l); med.put(k, Map.of("median_m2", l.get(l.size() / 2), "max_m2", l.getLast())); });
        r.put("patch_area", med); r.put("_mouths", mouths); return r;
    }
    /** B. Horizontal slices through each level centre: porosity, continuity, widths. */
    private Map<String, Object> levels(int side, int step) {
        var r = new LinkedHashMap<String, Object>();
        for (var e : Map.of("upper", lUp, "main", lMain, "deep", lDeep).entrySet()) {
            int n = side / step; boolean[] air = new boolean[n * n]; long open = 0; var widths = new ArrayList<Integer>();
            for (int iz = 0; iz < n; iz++) { int run = 0; for (int ix = 0; ix < n; ix++) {
                int x = -side / 2 + ix * step, z = -side / 2 + iz * step; int y = (int) Math.round(v(e.getValue(), x, 0, z));
                boolean a = v(body, x, y, z) < 0; air[iz * n + ix] = a; if (a) { open++; run++; } else if (run > 0) { widths.add(run * step); run = 0; } } }
            int largest = 0; boolean[] seen = new boolean[n * n]; int comps = 0;
            for (int i = 0; i < n * n; i++) if (air[i] && !seen[i]) { comps++; int size = 0; Deque<Integer> q = new ArrayDeque<>(); q.add(i); seen[i] = true;
                while (!q.isEmpty()) { int c = q.poll(); size++; for (int d : new int[]{1, -1, n, -n}) { int o = c + d;
                    if (o < 0 || o >= n * n || seen[o] || !air[o] || ((d == 1 || d == -1) && o / n != c / n)) continue; seen[o] = true; q.add(o); } }
                largest = Math.max(largest, size); }
            Collections.sort(widths);
            r.put(e.getKey(), Map.of("porosity", open / (double) (n * n), "components", comps, "largest_component_share", open == 0 ? 0 : largest / (double) open,
                "width_p50_blocks", widths.isEmpty() ? 0 : widths.get(widths.size() / 2), "width_p90_blocks", widths.isEmpty() ? 0 : widths.get(widths.size() * 9 / 10)));
        }
        return r;
    }
    /** C. Columns open continuously between level centres (pits/shafts). */
    private Map<String, Object> pits(int side, int step) {
        var r = new LinkedHashMap<String, Object>();
        for (String pair : List.of("upper_main", "main_deep", "upper_deep")) {
            int n = side / step; boolean[] open = new boolean[n * n];
            for (int iz = 0; iz < n; iz++) for (int ix = 0; ix < n; ix++) {
                int x = -side / 2 + ix * step, z = -side / 2 + iz * step;
                int top = (int) Math.round(v(pair.startsWith("upper") ? lUp : lMain, x, 0, z)), bottom = (int) Math.round(v(pair.endsWith("deep") ? lDeep : lMain, x, 0, z));
                boolean all = true; for (int y = bottom; y <= top && all; y += 2) all = v(body, x, y, z) < 0; open[iz * n + ix] = all;
            }
            var sizes = new ArrayList<Integer>(); boolean[] seen = new boolean[n * n];
            for (int i = 0; i < n * n; i++) if (open[i] && !seen[i]) { int size = 0; Deque<Integer> q = new ArrayDeque<>(); q.add(i); seen[i] = true;
                while (!q.isEmpty()) { int c = q.poll(); size++; for (int d : new int[]{1, -1, n, -n}) { int o = c + d;
                    if (o < 0 || o >= n * n || seen[o] || !open[o] || ((d == 1 || d == -1) && o / n != c / n)) continue; seen[o] = true; q.add(o); } }
                sizes.add(size * step * step); }
            Collections.sort(sizes); double km2 = side * (double) side / 1e6;
            r.put(pair, Map.of("count", sizes.size(), "per_km2", sizes.size() / km2, "median_area_m2", sizes.isEmpty() ? 0 : sizes.get(sizes.size() / 2),
                "max_area_m2", sizes.isEmpty() ? 0 : sizes.getLast()));
        }
        return r;
    }
    /** D. 3-D flood fill (2-block grid) from each mouth: does open space reach the main level? */
    private List<Object> connectivity(List<int[]> mouths) {
        var out = new ArrayList<Object>(); int box = 224, s = 2, ny = (330 + 64) / s, nx = 2 * box / s;
        for (int[] m : mouths) {
            int x0 = m[0] - box, z0 = m[1] - box; float h = v(height, m[0], 0, m[1]);
            boolean[] seen = new boolean[nx * nx * ny]; Deque<int[]> q = new ArrayDeque<>();
            int minY = 999, visited = 0; boolean reachedMain = false, reachedDeep = false;
            // Patch centroid on the 2-block grid: start from the first open cell below the surface.
            for (int depth = 1; depth <= 12 && q.isEmpty(); depth++) {
                int sy = ((int) Math.floor(h) - depth + 64) / s;
                if (v(body, m[0], sy * s - 64, m[1]) < 0) { q.add(new int[]{nx / 2, sy, nx / 2}); seen[(sy * nx + nx / 2) * nx + nx / 2] = true; }
            }
            while (!q.isEmpty() && visited < 3000000) { int[] c = q.poll(); visited++; int wy = c[1] * s - 64; minY = Math.min(minY, wy);
                int wx = x0 + c[0] * s, wz = z0 + c[2] * s;
                if (wy <= v(lMain, wx, 0, wz) + 2) reachedMain = true; if (wy <= v(lDeep, wx, 0, wz) + 2) reachedDeep = true;
                for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                    int a = c[0] + d[0], b = c[1] + d[1], e = c[2] + d[2]; if (a < 0 || e < 0 || a >= nx || e >= nx || b < 0 || b >= ny) continue;
                    int idx = (b * nx + e) * nx + a; if (seen[idx]) continue; seen[idx] = true;
                    int yy = b * s - 64, xx = x0 + a * s, zz = z0 + e * s; if (yy > v(height, xx, 0, zz) - 1) continue;
                    if (v(body, xx, yy, zz) < 0) q.add(new int[]{a, b, e}); } }
            out.add(Map.of("x", m[0], "z", m[1], "type", new String[]{"", "doline", "inclined", "pothole"}[m[2]], "surface", h,
                "lowest_open_y", minY == 999 ? "no open start cell" : minY, "reached_main_level", reachedMain, "reached_deep_level", reachedDeep, "visited_cells", visited));
        }
        return out;
    }
    /** E. FULL blocks: fluid faces exposed to cave air (hanging water/lava indicators). */
    private List<Object> fluids(Path sitesPath) throws Exception {
        var sites = com.google.gson.JsonParser.parseString(Files.readString(sitesPath)).getAsJsonArray(); var out = new ArrayList<Object>();
        var pos = new BlockPos.MutableBlockPos(); var nb = new BlockPos.MutableBlockPos();
        for (var e : sites) { var s = e.getAsJsonObject(); int cx = s.get("x").getAsInt(), cz = s.get("z").getAsInt(), side = s.get("side").getAsInt();
            int x0 = Math.floorDiv(cx - side / 2, 16) * 16, z0 = Math.floorDiv(cz - side / 2, 16) * 16;
            for (int a = Math.floorDiv(x0, 16) - 1; a <= Math.floorDiv(x0 + side - 1, 16) + 1; a++) for (int b = Math.floorDiv(z0, 16) - 1; b <= Math.floorDiv(z0 + side - 1, 16) + 1; b++) level.getChunk(a, b);
            long caveAir = 0, water = 0, lava = 0, waterOverAir = 0, waterSideAir = 0, lavaOverAir = 0;
            for (int x = x0; x < x0 + side; x++) for (int z = z0; z < z0 + side; z++) { int top = (int) Math.floor(v(height, x, 0, z));
                for (int y = -58; y < top - 2; y++) { pos.set(x, y, z); var st = level.getBlockState(pos);
                    if (st.isAir()) { caveAir++; continue; }
                    boolean w = st.getFluidState().is(FluidTags.WATER), l = st.getFluidState().is(FluidTags.LAVA); if (!w && !l) continue;
                    if (w) water++; else lava++;
                    nb.setWithOffset(pos, Direction.DOWN); boolean below = level.getBlockState(nb).isAir();
                    if (w && below) waterOverAir++; if (l && below) lavaOverAir++;
                    if (w) for (Direction d : Direction.Plane.HORIZONTAL) { nb.setWithOffset(pos, d); if (level.getBlockState(nb).isAir()) { waterSideAir++; break; } } } }
            out.add(Map.of("site", s.toString(), "cave_air", caveAir, "water", water, "lava", lava, "water_over_air", waterOverAir,
                "water_beside_air", waterSideAir, "lava_over_air", lavaOverAir)); }
        return out;
    }
}
