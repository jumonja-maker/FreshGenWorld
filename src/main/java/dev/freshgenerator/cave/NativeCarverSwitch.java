package dev.freshgenerator.cave;

import dev.freshgenerator.core.CaveAccessModel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** 0.9.2 (audit "build10" T02): whether the current world refuses every native carver.
 * Decided by the value of the marker density function (1 = carvers off), found by its resource key,
 * not by the text of a debug label: renaming the label no longer changes generation and a marker of 0
 * gives the native carvers back. Other worlds always keep their carvers. */
public final class NativeCarverSwitch {
    /** Marker density function of the current world (compiled by SurfaceOnlyCompiler, constant 1). */
    public static final String MARKER = CaveAccessModel.SETTINGS + "/coherent/surface_only/caves_disabled";
    private static final ResourceKey<DensityFunction> KEY = ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(MARKER));
    private NativeCarverSwitch() { }

    public static boolean carversOff(NoiseBasedChunkGenerator generator, RegistryAccess registries, RandomState state) {
        if (!generator.generatorSettings().unwrapKey().map(k -> k.identifier().toString()).orElse("").equals(CaveAccessModel.SETTINGS)) return false;
        return registries.lookupOrThrow(Registries.DENSITY_FUNCTION).get(KEY)
                .map(h -> state.getSampler(h.value()).sampleValue(SamplerContext.builder().build(), 0, 0, 0) >= .5).orElse(false);
    }
}
