package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.Fluids;

/** Lake ice as a property of the water body (audit 23.11 B-2). The native top-layer pass freezes each water column
 * by the temperature of its own biome, so a lake crossing a biome border froze on one side of a straight line. Here a
 * lake freezes or stays open as a whole: the native temperature rule (not warm enough to rain at the water surface)
 * is applied to up to SAMPLES columns spread over the lake, and the majority decides once for every chunk. Rivers
 * and the sea keep the native rule. */
public final class HydroIce {
    static final int SAMPLES = 256;
    private static final ThreadLocal<HydroField.Column> COLUMN = ThreadLocal.withInitial(HydroField.Column::new);
    private HydroIce() { }

    /** The freeze decision for a water surface block, given the native one. */
    public static boolean shouldFreeze(WorldGenLevel level, ChunkGenerator generator, BlockPos pos, boolean vanilla) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return vanilla;
        RandomState state = level.getLevel().getChunkSource().randomState();
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return vanilla;
        HydroField.Column c = COLUMN.get();
        field.column(pos.getX(), pos.getZ(), c);
        if (c.kind != HydroField.LAKE || c.water != pos.getY()) return vanilla;
        HydroModel.Group lake = field.lakeAt(pos.getX(), pos.getZ());
        if (lake == null || lake.lakeLevel() != pos.getY()) return vanilla;
        int ice = lake.ice;
        if (ice == 0) lake.ice = ice = decide(lake, generator, state, level.getSeaLevel());
        if (ice == HydroModel.Group.ICE_OPEN) return false;
        if (vanilla) return true;
        // Frozen lake, warm biome here: the native conditions other than temperature (still water source, dark).
        return level.isInsideBuildHeight(pos.getY()) && level.getBrightness(LightLayer.BLOCK, pos) < 10
                && level.getBlockState(pos).getBlock() instanceof LiquidBlock && level.getFluidState(pos).is(Fluids.WATER)
                && level.getFluidState(pos).isSource();
    }

    /** In-game freezing (ServerLevel.tickPrecipitation). The native rule, given the vanilla answer: it may not freeze
     * the surface of a lake decided open at generation - the game freezes water block by block by its biome, so the
     * cold part of an open lake froze later in play, up to a straight edge along the biome border (audit 24.11 L-2:
     * 71 573 blocks of three open lakes on seed 31337). Ice of a lake decided frozen does not melt natively, and
     * rivers and the sea keep the native rule. */
    public static boolean keepsFreezing(ServerLevel level, BlockPos pos, boolean vanilla) {
        if (!vanilla || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)) return vanilla;
        RandomState state = level.getChunkSource().randomState();
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return true;
        // The model lives in memory only: after a restart the first read of a 128-block area built it here, on the
        // server thread (0.56-0.70 s per area beside a cold shore - code audit 24.11 F-2). The area is built in the
        // background; until then this water does not freeze (freezing is a slow random process: a later precipitation
        // event decides).
        int x = pos.getX(), z = pos.getZ();
        if (!(field.ready(x, z) && field.ready(x + 1, z) && field.ready(x - 1, z) && field.ready(x, z + 1) && field.ready(x, z - 1))) {
            prepare(field, x, z);
            return false;
        }
        HydroField.Column c = COLUMN.get();
        field.column(pos.getX(), pos.getZ(), c);
        if (c.kind != HydroField.LAKE || c.water != pos.getY()) return true;
        HydroModel.Group lake = field.lakeAt(pos.getX(), pos.getZ());
        if (lake == null || lake.lakeLevel() != pos.getY()) return true;
        int ice = lake.ice;
        if (ice == 0) lake.ice = ice = decide(lake, noise, state, level.getSeaLevel());
        return ice != HydroModel.Group.ICE_OPEN;
    }

    /** Builds the query tile of a column in the background (one job per tile at a time). */
    static void prepare(HydroField field, int x, int z) {
        long key = (long) System.identityHashCode(field) << 40 ^ ((long) Math.floorDiv(x, HydroField.QUERY) << 20) ^ (Math.floorDiv(z, HydroField.QUERY) & 0xfffffL);
        if (!PENDING.add(key)) return;
        PREPARE.execute(() -> {
            // The whole tile on a 16-block grid (every reach of it reaches a grid point: their influence is 96+ blocks)
            // and the four neighbours of the column, which can lie in the next tile.
            HydroField.Column c = new HydroField.Column();
            int x0 = Math.floorDiv(x, HydroField.QUERY) * HydroField.QUERY, z0 = Math.floorDiv(z, HydroField.QUERY) * HydroField.QUERY;
            try {
                for (int dz = 8; dz < HydroField.QUERY; dz += 16) for (int dx = 8; dx < HydroField.QUERY; dx += 16) field.column(x0 + dx, z0 + dz, c);
                field.column(x, z, c);
            }
            catch (RuntimeException e) { LOG.warn("FreshGenerator: background drainage model failed at {},{}", x, z, e); }
            finally { PENDING.remove(key); }
        });
    }
    private static final java.util.Set<Long> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService PREPARE = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "FreshGenerator drainage (in game)");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("FreshGenerator");

    /** Majority of the native temperature rule over the lake surface (deterministic: mask-ordered samples). */
    static int decide(HydroModel.Group lake, ChunkGenerator generator, RandomState state, int seaLevel) {
        long[] samples = lake.lakeSamples(SAMPLES);
        if (samples.length == 0) return HydroModel.Group.ICE_OPEN;
        // A caching sampler context: the uncached one (createUncachedResolver = SamplerContext.EMPTY_UNCACHED) disables
        // every cache node of the climate graph, so each sample recomputed the landscape height once per reference -
        // 17-36 s per lake on the serial decoration queue (Chunk Atlas profile: 41% of it). Same biomes (2267 samples,
        // seed 42), 2-3 ms per lake.
        BiomeResolver biomes = generator.getBiomeSource().createCachingResolver(state);
        int y = lake.lakeLevel(), cold = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long s : samples) {
            int x = (int) (s >> 32), z = (int) s;
            at.set(x, y, z);
            var biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).value();
            if (!biome.warmEnoughToRain(at, seaLevel)) cold++;
        }
        return 2 * cold >= samples.length ? HydroModel.Group.ICE_FROZEN : HydroModel.Group.ICE_OPEN;
    }
}
