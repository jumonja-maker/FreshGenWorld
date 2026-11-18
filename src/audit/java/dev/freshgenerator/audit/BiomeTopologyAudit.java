package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded native surface-biome topology observer; never rewrites biome IDs.
 * It separates clipped components from enclosed islands, and ecological small
 * features from ordinary land islands that deserve review. Excluded from release.
 */
public final class BiomeTopologyAudit implements ModInitializer {
    private static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    private static final int SIDE = 192, STEP = 4;
    private static final Set<String> RARE = Set.of("mushroom_fields", "cherry_grove", "flower_forest",
            "sunflower_plains", "ice_spikes", "eroded_badlands", "old_growth_birch_forest",
            "bamboo_jungle", "pale_garden");
    private static final Set<String> ALPINE = Set.of("meadow", "grove", "snowy_slopes", "jagged_peaks",
            "frozen_peaks", "stony_peaks", "windswept_hills", "windswept_forest", "windswept_gravelly_hills");
    private static final Set<String> CAVE = Set.of("lush_caves", "dripstone_caves", "sulfur_caves", "deep_dark");

    public record Component(int id, int cells, int first, int minX, int minZ, int maxX, int maxZ,
                            boolean boundary, Set<Integer> surrounding) { }
    private record Site(int x, int z, int diversity, float height) { }
    private record Review(int x, int z, String biome, int cells, String reason) { }

    /** Four-neighbour connectivity; diagonally touching patches remain distinct.
     * A boundary component is unclassified, never claimed to be a closed island.
     */
    public static List<Component> components(int[] cells, int side) {
        if (side <= 0 || cells.length != Math.multiplyExact(side, side))
            throw new IllegalArgumentException("Invalid biome raster");
        boolean[] seen = new boolean[cells.length];
        List<Component> result = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int first = 0; first < cells.length; first++) {
            if (seen[first]) continue;
            int id = cells[first], count = 0, minX = side, minZ = side, maxX = -1, maxZ = -1;
            boolean boundary = false;
            Set<Integer> surrounding = new TreeSet<>();
            seen[first] = true; queue.add(first);
            while (!queue.isEmpty()) {
                int i = queue.removeFirst(), x = i / side, z = i % side;
                count++; minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                boundary |= x == 0 || z == 0 || x == side - 1 || z == side - 1;
                for (int n : new int[]{x > 0 ? i - side : -1, x + 1 < side ? i + side : -1,
                        z > 0 ? i - 1 : -1, z + 1 < side ? i + 1 : -1}) {
                    if (n < 0) continue;
                    if (cells[n] != id) surrounding.add(cells[n]);
                    else if (!seen[n]) { seen[n] = true; queue.add(n); }
                }
            }
            result.add(new Component(id, count, first, minX, minZ, maxX, maxZ, boundary,
                    Set.copyOf(surrounding)));
        }
        return List.copyOf(result);
    }

    @Override public void onInitialize() {
        if (!"true".equals(System.getenv("FG_BIOME_TOPOLOGY_AUDIT"))) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { inspect(server); }
            catch (Exception e) { throw new IllegalStateException("Biome topology audit failed", e); }
            finally { server.halt(false); }
        });
    }

    private static String id(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().identifier().toString();
    }
    private static String shortId(String biome) { return biome.substring(biome.indexOf(':') + 1); }
    private static boolean water(String biome) {
        String b = shortId(biome);
        return b.contains("ocean") || b.contains("river") || b.contains("beach")
                || b.equals("stony_shore") || b.contains("swamp");
    }
    private static String kind(String biome) {
        String b = shortId(biome);
        if (CAVE.contains(b)) return "surface_cave_biome";
        if (water(biome)) return "water_shore_or_wetland";
        if (RARE.contains(b)) return "intentional_rare_or_variant";
        if (ALPINE.contains(b)) return "altitude_or_relief_variant";
        return "ordinary_land";
    }
    @SuppressWarnings("unchecked")
    private static Climate.ParameterList<Holder<Biome>> parameters(MultiNoiseBiomeSource source) throws Exception {
        var getter = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
        getter.setAccessible(true);
        return (Climate.ParameterList<Holder<Biome>>) getter.invoke(source);
    }
    private static MultiNoiseBiomeSource independent(Climate.ParameterList<Holder<Biome>> parameters) {
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.copyOf(parameters.values())));
    }
    private static long fitness(Climate.ParameterList<Holder<Biome>> parameters, Climate.TargetPoint target, String biome) {
        long value = Long.MAX_VALUE;
        for (var pair : parameters.values()) if (id(pair.getSecond()).equals(biome))
            value = Math.min(value, pair.getFirst().fitness(target));
        return value;
    }
    private static void inspect(MinecraftServer server) throws Exception {
        var level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                || !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource source))
            throw new AssertionError("Expected native multi-noise generator");
        String settings = generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
        if (!settings.equals("freshgenerator:highlands_v2")) throw new AssertionError("Unexpected settings: " + settings);
        var state = level.getChunkSource().randomState();
        var context = SamplerContext.builder().enableCaches().build();
        DensitySampler height = state.getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
                .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
                        Identifier.parse(settings + "/coherent/height"))).value());
        var climate = state.createClimateSampler(context);
        var parameters = parameters(source);
        BiomeResolver resolver = independent(parameters).createResolver(climate);
        List<Site> scout = new ArrayList<>();
        Map<Long, String> coarse = new TreeMap<>();
        for (int x = -16384; x <= 16384; x += 256) for (int z = -16384; z <= 16384; z += 256) {
            int y = (int) Math.ceil(height.sampleValue(context, x, 0, z)) - 1;
            coarse.put(((long) x << 32) | (z & 0xffffffffL), id(resolver.getNoiseBiome(x / 4, Math.floorDiv(y, 4), z / 4)));
        }
        for (int x = -16128; x < 16384; x += 256) for (int z = -16128; z < 16384; z += 256) {
            Set<String> neighbours = new HashSet<>();
            for (int dx : new int[]{-256, 0, 256}) for (int dz : new int[]{-256, 0, 256})
                neighbours.add(coarse.get(((long) (x + dx) << 32) | ((z + dz) & 0xffffffffL)));
            scout.add(new Site(x, z, neighbours.size(), height.sampleValue(context, x, 0, z)));
        }
        scout.sort(Comparator.comparingInt(Site::diversity).reversed().thenComparingDouble(Site::height));
        List<Site> sites = new ArrayList<>();
        for (Site s : scout) if (sites.stream().noneMatch(p -> Math.abs(s.x() - p.x()) < 2048
                && Math.abs(s.z() - p.z()) < 2048)) { sites.add(s); if (sites.size() == 4) break; }
        String override = System.getenv("FG_BIOME_TOPOLOGY_SITES");
        if (override != null) sites = Arrays.asList(G.fromJson(Files.readString(Path.of(override)), Site[].class));
        JsonArray maps = new JsonArray();
        List<Review> reviews = new ArrayList<>();
        long cachedChecks = 0;
        for (Site s : sites) {
            int x0 = Math.floorDiv(s.x() - SIDE * STEP / 2, 4) * 4;
            int z0 = Math.floorDiv(s.z() - SIDE * STEP / 2, 4) * 4;
            List<String> palette = new ArrayList<>();
            Map<String, Integer> names = new TreeMap<>();
            int[] cells = new int[SIDE * SIDE];
            float[] heights = new float[cells.length];
            int caveSurface = 0;
            for (int dx = 0; dx < SIDE; dx++) for (int dz = 0; dz < SIDE; dz++) {
                int i = dx * SIDE + dz, x = x0 + dx * STEP, z = z0 + dz * STEP;
                float h = height.sampleValue(context, x, 0, z); heights[i] = h;
                int qx = Math.floorDiv(x, 4), qy = Math.floorDiv((int) Math.ceil(h) - 1, 4), qz = Math.floorDiv(z, 4);
                String b = id(resolver.getNoiseBiome(qx, qy, qz));
                cells[i] = names.computeIfAbsent(b, key -> { palette.add(key); return palette.size() - 1; });
                if (CAVE.contains(shortId(b))) caveSurface++;
                if (i % 256 == 0) {
                    var uncached = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
                    if (!uncached.sample(qx, qy, qz).equals(climate.sample(qx, qy, qz)))
                        throw new AssertionError("Climate cache mismatch at " + x + "," + z);
                    cachedChecks++;
                }
            }
            var components = components(cells, SIDE);
            JsonArray enclosed = new JsonArray();
            Map<String, Integer> smallKinds = new TreeMap<>();
            for (Component c : components) {
                if (c.boundary()) continue;
                String b = palette.get(c.id());
                if (c.cells() <= 64) smallKinds.merge(kind(b), 1, Integer::sum);
                if (c.cells() > 256) continue;
                JsonObject item = G.toJsonTree(c).getAsJsonObject();
                item.addProperty("biome", b); item.addProperty("classification", kind(b));
                item.addProperty("sampled_area_blocks", c.cells() * STEP * STEP);
                // A land-named biome can lie below sea level in this landscape.
                // This is a biome-ID patch, not proof of a physical land island.
                item.addProperty("first_envelope_height", heights[c.first()]);
                item.addProperty("first_sample_above_sea", heights[c.first()] > level.getSeaLevel());
                item.addProperty("first_x", x0 + (c.first() / SIDE) * STEP);
                item.addProperty("first_z", z0 + (c.first() % SIDE) * STEP);
                List<String> surrounding = c.surrounding().stream().map(palette::get).sorted().toList();
                item.add("surrounding_biomes", G.toJsonTree(surrounding));
                enclosed.add(item);
                if (c.cells() <= 64 && c.surrounding().size() == 1 && kind(b).equals("ordinary_land")
                        && surrounding.stream().noneMatch(BiomeTopologyAudit::water))
                    reviews.add(new Review(x0 + c.first() / SIDE * STEP, z0 + c.first() % SIDE * STEP,
                            b, c.cells(), "small ordinary biome-ID component surrounded by one non-water biome ID; inspect elevation before calling this a dry-land island"));
            }
            JsonObject map = new JsonObject();
            map.addProperty("x", x0); map.addProperty("z", z0); map.addProperty("side", SIDE); map.addProperty("step", STEP);
            map.add("palette", G.toJsonTree(palette)); map.add("cells", G.toJsonTree(cells)); map.add("height", G.toJsonTree(heights));
            map.addProperty("components", components.size());
            map.addProperty("boundary_components_unknown_extent", components.stream().filter(Component::boundary).count());
            map.addProperty("surface_cave_biome_cells", caveSurface);
            map.add("enclosed_components_up_to_4096_blocks", enclosed);
            map.add("small_component_classification", G.toJsonTree(smallKinds));
            maps.add(map);
            System.out.println("BIOME_TOPOLOGY_MAP " + x0 + "," + z0 + " types=" + palette.size()
                    + " components=" + components.size() + " enclosed=" + enclosed.size());
        }
        reviews.sort(Comparator.comparingInt(Review::cells));
        // Include a biome boundary even when no enclosed ordinary islands were found.
        if (reviews.isEmpty()) for (Site s : sites) reviews.add(new Review(s.x(), s.z(), "boundary_control", 0, "regional junction control"));
        JsonArray full = new JsonArray(); int checks = 0, tiedDifferences = 0;
        Set<Long> chunks = new HashSet<>();
        for (Review r : reviews.subList(0, Math.min(4, reviews.size()))) {
            int x0 = Math.floorDiv(r.x() - 16, 16) * 16, z0 = Math.floorDiv(r.z() - 16, 16) * 16;
            for (int cx = Math.floorDiv(x0, 16) - 1; cx <= Math.floorDiv(x0, 16) + 2; cx++)
                for (int cz = Math.floorDiv(z0, 16) - 1; cz <= Math.floorDiv(z0, 16) + 2; cz++) {
                    level.getChunk(cx, cz); chunks.add(((long) cx << 32) | (cz & 0xffffffffL));
                }
            var control = independent(parameters).createResolver(climate);
            Map<String, Integer> stored = new TreeMap<>(); int localTies = 0, borderChecks = 0;
            JsonArray actualCells = new JsonArray();
            for (int dx = 0; dx < 32; dx += 4) for (int dz = 0; dz < 32; dz += 4) {
                int x = x0 + dx, z = z0 + dz;
                int y = (int) Math.ceil(height.sampleValue(context, x, 0, z)) - 1;
                int qx = Math.floorDiv(x, 4), qy = Math.floorDiv(y, 4), qz = Math.floorDiv(z, 4);
                String actual = id(level.getNoiseBiome(qx, qy, qz)), expected = id(control.getNoiseBiome(qx, qy, qz));
                stored.merge(actual, 1, Integer::sum); checks++;
                JsonObject cell = new JsonObject(); cell.addProperty("x", x); cell.addProperty("y", y); cell.addProperty("z", z);
                var cellClimate = climate.sample(qx, qy, qz);
                cell.addProperty("biome", actual); cell.add("native_quantized_climate", G.toJsonTree(cellClimate));
                if (Math.abs(x - r.x()) <= 4 && Math.abs(z - r.z()) <= 4) {
                    long minimum = parameters.values().stream().mapToLong(p -> p.getFirst().fitness(cellClimate)).min().orElseThrow();
                    JsonArray winners = new JsonArray();
                    for (var entry : parameters.values()) if (entry.getFirst().fitness(cellClimate) == minimum) {
                        JsonObject winner = new JsonObject(); winner.addProperty("biome", id(entry.getSecond()));
                        winner.addProperty("fitness", minimum); winner.add("climate_box", G.toJsonTree(entry.getFirst())); winners.add(winner);
                    }
                    cell.add("closest_native_entries", winners);
                }
                actualCells.add(cell);
                if (Math.floorMod(x, 16) == 0 || Math.floorMod(z, 16) == 0) borderChecks++;
                if (!actual.equals(expected)) {
                    var target = climate.sample(qx, qy, qz);
                    long a = fitness(parameters, target, actual), e = fitness(parameters, target, expected);
                    long best = parameters.values().stream().mapToLong(p -> p.getFirst().fitness(target)).min().orElseThrow();
                    if (a != best || e != best) throw new AssertionError("FULL biome mismatches resolver away from equal-distance tie at " + x + "," + z);
                    tiedDifferences++; localTies++;
                }
            }
            JsonObject window = G.toJsonTree(r).getAsJsonObject(); window.add("stored_biomes", G.toJsonTree(stored));
            window.add("actual_cells", actualCells);
            window.addProperty("stored_checks", 64); window.addProperty("chunk_border_checks", borderChecks);
            window.addProperty("equal_distance_tie_choices", localTies); full.add(window);
        }
        JsonObject report = new JsonObject(); report.addProperty("seed", level.getSeed()); report.addProperty("settings", settings);
        report.add("maps", maps); report.add("full_windows", full);
        report.addProperty("cached_uncached_climate_checks", cachedChecks); report.addProperty("full_noise_biome_checks", checks);
        report.addProperty("full_chunks", chunks.size()); report.addProperty("equal_distance_native_tie_choices", tiedDifferences);
        report.addProperty("ordinary_small_islands_for_review", reviews.stream().filter(r -> r.cells() > 0).count());
        report.addProperty("scope", "Four sampled 768x768 surface-noise-biome maps; 4-block cells; clipped components have unknown extent. Land-named biome IDs may be underwater/coastal, so small ordinary components are review candidates, not confirmed dry-land islands or defects. Rare/water/alpine patches are classified, not erased. FULL agreement allows proven native equal-distance ties. Completion is not proof of visual realism or worldwide absence of islands.");
        report.addProperty("completed", true);
        Files.writeString(Path.of("freshgenerator-biome-topology.json"), G.toJson(report) + "\n");
        System.out.println("FRESHGENERATOR_BIOME_TOPOLOGY_PASS seed=" + level.getSeed() + " checks=" + checks);
    }
}
