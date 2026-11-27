package dev.freshgenerator.landform;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Land biomes on the land of volcanic islands (audit 26.11 Opus N-2, code KO-02). The climate the biomes are chosen by
 * does not see the landforms: over an island rising from the sea every biome stayed oceanic - cold_ocean on tuff at
 * Y112, frozen_ocean on a summit at Y144 (seed 7391827364 X-2909 Z-5673; OpenAI random6: seed -125398784 X-5376
 * Z-8960), with ocean grass, sky and mobs. Where an ocean volcano raised the ground above the sea and the source gives
 * an ocean biome, the same climate point is read again as coast (ground up to Y65: beaches by the native table) or as
 * near-inland land: temperature, humidity, erosion, weirdness and depth stay those of the place. One instance per
 * chunk fill (its climate sampler has a caching context, which is not thread-safe). */
public final class IslandBiomes {
    /** Continentalness read for island ground: the native coast band below Y66, near-inland land above. */
    private static final long COAST = Climate.quantizeCoord(-0.15f), INLAND = Climate.quantizeCoord(0.05f);
    private final LandformDensity.World landforms;
    private final MultiNoiseBiomeSource source;
    private final RandomState state;
    private Climate.Sampler climate;

    private IslandBiomes(LandformDensity.World landforms, MultiNoiseBiomeSource source, RandomState state) {
        this.landforms = landforms; this.source = source; this.state = state;
    }

    /** The island biomes of a world, or null where there are no landforms or no multi-noise source. */
    public static IslandBiomes of(RandomState state, NoiseGeneratorSettings settings, BiomeSource source) {
        return of(state, LandformDensity.world(state, settings), source);
    }
    /** The same for a known landform model (null: none). */
    public static IslandBiomes of(RandomState state, LandformDensity.World world, BiomeSource source) {
        if (!(source instanceof MultiNoiseBiomeSource multi) || world == null) return null;
        return new IslandBiomes(world, multi, state);
    }
    /** Whether a volcano reaches (x, z): only there can an ocean biome of the source be island land. */
    public boolean mayBeIsland(int x, int z) { return landforms.oceanIsland(x, z); }

    /** The biome of quart cell (qx, qy, qz) over island ground at height ground, or the given one. A closed bowl below
     * the sea (sea free) reads as land, not as a shore. */
    public Holder<Biome> on(Holder<Biome> biome, int qx, int qy, int qz, double ground, boolean seaFree) {
        // A beach inside a closed bowl is no shore either (a beach patch on the inner wall of a dry caldera).
        boolean sea = biome.is(BiomeTags.IS_OCEAN) || seaFree && biome.is(BiomeTags.IS_BEACH);
        if (!sea || !(seaFree || landforms.oceanIsland((qx << 2) + 2, (qz << 2) + 2))) return biome;
        if (climate == null) climate = state.createClimateSampler(SamplerContext.builder().enableCaches().build());
        Climate.TargetPoint t = climate.sample(qx, qy, qz);
        long c = Math.max(t.continentalness(), !seaFree && ground < 66 ? COAST : INLAND);
        Holder<Biome> land = source.getNoiseBiome(new Climate.TargetPoint(t.temperature(), t.humidity(), c, t.erosion(), t.depth(), t.weirdness()));
        return land.is(BiomeTags.IS_OCEAN) || seaFree && land.is(BiomeTags.IS_BEACH) ? biome : land;
    }
}
