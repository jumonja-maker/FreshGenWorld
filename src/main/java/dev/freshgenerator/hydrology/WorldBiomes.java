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
        if (!(generator instanceof NoiseBasedChunkGenerator noise) || HydrologyDensity.field(state, noise.generatorSettings().value()) == null)
            return uncached;
        return new BiomeResolver() {
            @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) { return source.createCachingResolver(state).getNoiseBiome(qx, qy, qz); }
        };
    }
}
