package dev.freshgenerator.volcano;

import com.mojang.serialization.MapCodec;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.volcano.VolcanicVent;
import dev.freshgenerator.hydrology.HydrologyDensity;
import dev.freshgenerator.road.RoadNetwork;
import dev.freshgenerator.village.VillageTerrainPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Rare volcanic vents in place of the native surface lava lakes (user request 26.11; the native lakes are off in the
 * current world, RoadLakeGuard): a lava cone in deserts, a lava pit in badlands (core VolcanicVent). One chance per cell of
 * CELL x CELL chunks, at a chunk the seed picks, on flat dry ground away from water, villages and roads. A no-op outside
 * the current world; underground lava lakes stay native. */
public record VolcanicVents() implements Feature {
    public static final Identifier ID = Identifier.parse("freshgenerator:volcanic_vent");
    public static final MapCodec<VolcanicVents> CODEC = MapCodec.unit(new VolcanicVents());
    /** Badlands: a cell of 32 x 32 chunks (about a quarter of a square kilometre) holds a pit with this chance - if the
     * picked chunk is badlands and its ground fits. */
    static final int CELL = 32;
    static final double CHANCE = 0.12;
    /** Deserts (user request 26.11: "at least 2-3"): cones on their own grid of 16 x 16 chunks. About four in five desert
     * chunks are dry and flat (seed 8675309: 13581 of 17001), so this gives some 1.2 cones per square kilometre of desert -
     * two or three in a desert of the usual size; the picked chunk keeps 5 chunks between the cones of two cells. */
    static final int DESERT_CELL = 16;
    static final double DESERT_CHANCE = 0.10;

    @Override public MapCodec<VolcanicVents> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        RandomState state = level.getLevel().getChunkSource().randomState();
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return false;
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        long desertCell = picked(level.getSeed(), cx, cz, DESERT_CELL, 2, DESERT_CHANCE, 0x76A7C5L);
        long badlandsCell = picked(level.getSeed(), cx, cz, CELL, 1, CHANCE, 0x76A7C4L);
        if (desertCell == 0 && badlandsCell == 0) return false;
        int x0 = (cx << 4) + 8, z0 = (cz << 4) + 8;
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x0, z0) - 1;
        var biome = level.getBiome(new BlockPos(x0, ground + 1, z0));
        VolcanicVent.Kind kind;
        long cell;
        if (desertCell != 0 && biome.is(Biomes.DESERT)) { kind = VolcanicVent.Kind.CONE; cell = desertCell; }
        else if (badlandsCell != 0 && (biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.WOODED_BADLANDS))) {
            kind = VolcanicVent.Kind.PIT; cell = badlandsCell;
        } else return false;
        VolcanicVent vent = new VolcanicVent(kind, cell);
        int r = vent.r;
        // Dry: no river, lake or sea near.
        HydroField.Column c = new HydroField.Column();
        field.column(x0, z0, c);
        if (c.wetDistance < r + 8 || c.terrain < 64) return false;
        // Even ground: within three blocks of the plane fitted through the footprint (a gentle slope is fine: hilly badlands
        // never passed a test against the centre height - code audit 26.11 KO-09), the slope gentle, no water on it.
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int n = 0;
        double sx = 0, sz = 0, sg = 0, sxx = 0, szz = 0, sxz = 0, sxg = 0, szg = 0;
        int[] gs = new int[(2 * r + 1) * (2 * r + 1)];
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (vent.distance(dx, dz) >= 1.05) continue;
            int g = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x0 + dx, z0 + dz) - 1;
            at.set(x0 + dx, g, z0 + dz);
            if (!level.getBlockState(at).getFluidState().isEmpty()) return false;
            gs[(dz + r) * (2 * r + 1) + dx + r] = g;
            n++; sx += dx; sz += dz; sg += g; sxx += dx * dx; szz += dz * dz; sxz += dx * dz; sxg += dx * g; szg += dz * g;
        }
        double[] plane = plane(n, sx, sz, sg, sxx, szz, sxz, sxg, szg);
        // The steepest slope of the plane, whatever its direction: the two axis slopes apart let a plane of 0.49 through
        // diagonally (code audit 27.11 OpenAI K-08).
        if (plane == null || Math.hypot(plane[1], plane[2]) > MAX_SLOPE) return false;
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (vent.distance(dx, dz) >= 1.05) continue;
            if (Math.abs(gs[(dz + r) * (2 * r + 1) + dx + r] - (plane[0] + plane[1] * dx + plane[2] * dz)) > 3) return false;
        }
        if (nearVillageOrRoad(level, noise, state, field, x0, z0, r) || nearStructure(level, x0, z0, r, ground)) return false;
        build(level, vent, cell, x0, z0, ground);
        return true;
    }

    /** Steepest accepted slope of the plane through a vent's footprint (blocks per block). */
    static final double MAX_SLOPE = 0.35;

    /** Least-squares plane g = a + b dx + c dz through the footprint samples: {a, b, c}, or null when degenerate. */
    static double[] plane(int n, double sx, double sz, double sg, double sxx, double szz, double sxz, double sxg, double szg) {
        // Normal equations [n sx sz; sx sxx sxz; sz sxz szz] (a b c) = (sg sxg szg), solved by Cramer's rule.
        double d = det(n, sx, sz, sx, sxx, sxz, sz, sxz, szz);
        if (Math.abs(d) < 1e-9) return null;
        double a = det(sg, sx, sz, sxg, sxx, sxz, szg, sxz, szz) / d;
        double b = det(n, sg, sz, sx, sxg, sxz, sz, szg, szz) / d;
        double c = det(n, sx, sg, sx, sxx, sxg, sz, sxz, szg) / d;
        return new double[]{a, b, c};
    }
    private static double det(double a, double b, double c, double d, double e, double f, double g, double h, double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    /** The cell hash if chunk (cx, cz) is the one its cell of size x size chunks picks and the cell's chance came up, else
     * 0. The chunk is picked at least `edge` chunks in from the cell border: a vent reaches at most a chunk around it. */
    static long picked(long seed, int cx, int cz, int size, int edge, double chance, long salt) {
        int ci = Math.floorDiv(cx, size), cj = Math.floorDiv(cz, size);
        long cell = seed ^ (ci * 0x5DEECE66DL + cj * 0x2545F4914F6CDD1DL) ^ salt;
        if (VolcanicVent.unit(cell, 1) >= chance) return 0;
        int span = size - 2 * edge;
        if (cx != ci * size + edge + (int) (VolcanicVent.unit(cell, 2) * span)
                || cz != cj * size + edge + (int) (VolcanicVent.unit(cell, 3) * span)) return 0;
        return cell == 0 ? 1 : cell;
    }

    /** Lays a vent around (x0, z0) on the ground at its centre (package-private: lab views). */
    static void build(WorldGenLevel level, VolcanicVent vent, long cell, int x0, int z0, int ground) {
        int r = vent.r;
        VolcanicVent.Kind kind = vent.kind;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int code = vent.code(dx, dz);
            if (code == VolcanicVent.NONE) continue;
            int x = x0 + dx, z = z0 + dz;
            int g = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            long salt = 1000L + vent.index(dx, dz);
            double d = vent.distance(dx, dz);
            if (kind == VolcanicVent.Kind.CONE) cone(level, at, vent, code, x, z, g, ground, dx, dz, d, cell, salt);
            else pit(level, at, vent, code, x, z, g, ground, dx, dz, d, cell, salt);
        }
    }

    private static void cone(WorldGenLevel level, BlockPos.MutableBlockPos at, VolcanicVent vent, int code, int x, int z, int g, int ground,
                             int dx, int dz, double d, long cell, long salt) {
        if (code == VolcanicVent.SCATTER) { recolour(level, at, x, g, z, scoria(cell, salt)); return; }
        int t = ground + vent.top(dx, dz);
        for (int y = g; y <= t; y++) set(level, at, x, y, z, y == t ? shell(cell, salt, d) : rock(cell, salt + y));
        if (t < g) recolour(level, at, x, g, z, shell(cell, salt, d));
        if (code == VolcanicVent.CRATER) {
            set(level, at, x, t, z, Blocks.OBSIDIAN.defaultBlockState());
            int lava = ground + vent.lava(dx, dz);
            for (int y = t + 1; y <= lava; y++) set(level, at, x, y, z, Blocks.LAVA.defaultBlockState());
        }
        for (int y = Math.max(t, ground + vent.lava(dx, dz)) + 1; y <= g + 2; y++) set(level, at, x, y, z, Blocks.AIR.defaultBlockState());
    }

    private static void pit(WorldGenLevel level, BlockPos.MutableBlockPos at, VolcanicVent vent, int code, int x, int z, int g, int ground,
                            int dx, int dz, double d, long cell, long salt) {
        int t = ground + vent.top(dx, dz);
        // Where the ground under the rim or the wall lies lower than the planned top (a slope), the column is filled
        // down to the ground: no rim hanging over air (OpenAI 26.11: 27 blocks of a rim over air at X-10968 Z19528), no
        // hollow wall for the lava to leak through.
        for (int y = g + 1; y < t; y++) set(level, at, x, y, z, rock(cell, salt + y));
        if (code == VolcanicVent.RING) {
            if (t > g) set(level, at, x, t, z, scoria(cell, salt).defaultBlockState());
            return;
        }
        for (int y = t + 1; y <= g + 2; y++) set(level, at, x, y, z, Blocks.AIR.defaultBlockState());
        if (code == VolcanicVent.CRATER) {
            set(level, at, x, t, z, Blocks.MAGMA_BLOCK.defaultBlockState());
            int lava = ground + vent.lava(dx, dz);
            for (int y = t + 1; y <= lava; y++) set(level, at, x, y, z, Blocks.LAVA.defaultBlockState());
        } else if (d < 0.8) set(level, at, x, t, z, shell(cell, salt, d));
    }

    /** No vent within reach of a village piece or a road (they are kept off lava as well). */
    private static boolean nearVillageOrRoad(WorldGenLevel level, NoiseBasedChunkGenerator noise, RandomState state, HydroField field, int x0, int z0, int r) {
        if (!level.getLevel().structureManager().shouldGenerateStructures() || !(level instanceof WorldGenRegion region)) return false;
        int reach = r + 16;
        var manager = level.getLevel().structureManager().forWorldGenRegion(region);
        for (var piece : VillageTerrainPlan.villagePieces(region, manager)) {
            var b = piece.getBoundingBox();
            if (b.maxX() >= x0 - reach && b.minX() <= x0 + reach && b.maxZ() >= z0 - reach && b.minZ() <= z0 + reach) return true;
        }
        RoadNetwork roads = RoadNetwork.of(level.getLevel(), noise, state);
        if (roads == null) return false;
        for (int cx = (x0 - reach) >> 4; cx <= (x0 + reach) >> 4; cx++)
            for (int cz = (z0 - reach) >> 4; cz <= (z0 + reach) >> 4; cz++)
                for (RoadNetwork.Road road : roads.roadsAt(state, field, cx, cz))
                    if (road.line().crosses(x0 - reach, z0 - reach, x0 + reach, z0 + reach)) return true;
        return false;
    }

    /** No vent on or beside another surface structure (desert pyramids and wells, trail ruins, outposts; code audit 26.11
     * KO-05): their blocks would replace the cone and stand against the lava of its crater. The feature step may read the
     * starts stored in chunks up to eight out from its own: those are read directly, as the village planner does. No
     * references: two chunks out they are not there yet (that stopped the generation: audit 26.11, seed -1796537974
     * X984 Z1352), and a reference one chunk out may name a start nine chunks out, past the readable ring (code audit
     * 27.11 OpenAI K-01). A structure reaching the vent from a start farther than eight chunks is not seen (rare). */
    private static boolean nearStructure(WorldGenLevel level, int x0, int z0, int r, int ground) {
        if (!level.getLevel().structureManager().shouldGenerateStructures() || !(level instanceof WorldGenRegion region)) return false;
        ChunkPos centre = region.getCenter();
        int reach = r + 6;
        for (int cx = centre.x() - 8; cx <= centre.x() + 8; cx++)
            for (int cz = centre.z() - 8; cz <= centre.z() + 8; cz++) {
                ChunkAccess chunk = region.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false);
                if (chunk == null) continue;
                for (var start : chunk.getAllStarts().values())
                    if (touches(start, x0, z0, reach, ground)) return true;
            }
        return false;
    }
    private static boolean touches(StructureStart start, int x0, int z0, int reach, int ground) {
        if (!start.isValid()) return false;
        for (var piece : start.getPieces()) {
            var b = piece.getBoundingBox();
            if (b.maxX() >= x0 - reach && b.minX() <= x0 + reach && b.maxZ() >= z0 - reach && b.minZ() <= z0 + reach
                    && b.maxY() >= ground - 8 && b.minY() <= ground + 12) return true;
        }
        return false;
    }

    private static BlockState shell(long cell, long salt, double d) {
        double u = VolcanicVent.unit(cell, salt);
        if (d < 0.55 && u < 0.15) return Blocks.MAGMA_BLOCK.defaultBlockState();
        return (u < 0.5 ? Blocks.BASALT : u < 0.75 ? Blocks.BLACKSTONE : u < 0.9 ? Blocks.TUFF : Blocks.SMOOTH_BASALT).defaultBlockState();
    }
    private static BlockState rock(long cell, long salt) {
        return (VolcanicVent.unit(cell, salt) < 0.7 ? Blocks.BASALT : Blocks.BLACKSTONE).defaultBlockState();
    }
    private static Block scoria(long cell, long salt) {
        double u = VolcanicVent.unit(cell, salt + 7);
        return u < 0.45 ? Blocks.BASALT : u < 0.8 ? Blocks.BLACKSTONE : Blocks.TUFF;
    }
    /** The ground's top block takes the vent's colour (sand, terracotta and soil only). */
    private static void recolour(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z, BlockState s) {
        at.set(x, y, z);
        BlockState here = level.getBlockState(at);
        if (here.is(Blocks.SAND) || here.is(Blocks.RED_SAND) || here.is(Blocks.SANDSTONE) || here.is(Blocks.GRASS_BLOCK) || here.is(Blocks.DIRT)
                || here.is(Blocks.COARSE_DIRT) || here.is(Blocks.TERRACOTTA) || here.getBlock().getDescriptionId().endsWith("terracotta"))
            level.setBlock(at, s, 2);
    }
    private static void recolour(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z, Block b) { recolour(level, at, x, y, z, b.defaultBlockState()); }
    private static void set(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z, BlockState s) {
        at.set(x, y, z);
        level.setBlock(at, s, 2);
    }
}
