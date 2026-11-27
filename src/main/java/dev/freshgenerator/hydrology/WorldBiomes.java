package dev.freshgenerator.hydrology;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** The level's fallback biome resolver for the current world (audit 24.11 T-2). A biome query in an unloaded chunk
 * (LevelReader.getNoiseBiome -> ServerLevel.getUncachedNoiseBiome) uses the native resolver on
 * SamplerContext.EMPTY_UNCACHED, which disables every cache node of the density graph: in this world's climate graph
 * each lookup recomputed the landscape height once per reference - 110 ms against 0.011 ms in vanilla. The preview
 * of an explorer or treasure map makes 16 384 such lookups: one map stalled the server thread for 13.5 minutes. Each
 * lookup here gets its own caching resolver bound to this level's RandomState (a caching context is not thread-safe):
 * the caches work within the lookup, where the cost was. No resolver is kept per thread - a thread-local value held
 * the climate graph of a left world in the worker threads (code audit 24.11 K-9). Other worlds keep the native
 * resolver. */
public final class WorldBiomes {
    private WorldBiomes() { }

    public static BiomeResolver fallback(BiomeSource source, RandomState state, ChunkGenerator generator, BiomeResolver uncached) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return uncached;
        dev.freshgenerator.core.hydro.HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return uncached;
        var shape = noise.generatorSettings().value().noiseSettings();
        int maxQy = net.minecraft.core.QuartPos.fromBlock(shape.minY() + shape.height() - 1);
        // The river and valley biomes the chunk will store, where the drainage model is built (HydroBiomes.wrapReady).
        Holder<Biome>[] rivers = HydroBiomes.rivers(source);
        var settings = noise.generatorSettings().value();
        // One spare resolver is reused by the next lookup (code audit 26.11 OpenAI FG094-04: a new caching resolver and
        // wrapper per lookup, even for the same position). A lookup takes it, so no two threads share its caching
        // context; a second thread meanwhile makes its own. No thread-local: it dies with this level's fallback.
        return new BiomeResolver() {
            private final java.util.concurrent.atomic.AtomicReference<BiomeResolver> spare = new java.util.concurrent.atomic.AtomicReference<>();
            @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
                BiomeResolver r = spare.getAndSet(null);
                if (r == null) r = HydroBiomes.wrapReady(source.createCachingResolver(state), field, rivers, maxQy,
                        dev.freshgenerator.landform.IslandBiomes.of(state, settings, source));
                try { return r.getNoiseBiome(qx, qy, qz); }
                finally { spare.set(r); }
            }
        };
    }
}
