package dev.freshgenerator.cave;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/** 0.9.2 (realism audit A092-07): the one place that resolves the current world's shared density fields
 * from its debug list. The labels are the shared constants of CaveAccessModel (compiled into the pack by
 * CaveAccessCompiler), never literals, and a missing field fails with a clear message. */
public final class DebugFields {
    private DebugFields() { }
    /** The labelled field, or null when the settings do not have it. */
    public static DensityFunction find(NoiseGeneratorSettings settings, String label) {
        return settings.debugFunctions().functions().stream().filter(e -> e.label().equals(label)).findFirst().map(e -> e.function()).orElse(null);
    }
    public static DensityFunction require(NoiseGeneratorSettings settings, String label) {
        return settings.debugFunctions().functions().stream().filter(e -> e.label().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("FreshGenerator field missing from the world settings: " + label)).function();
    }
}
