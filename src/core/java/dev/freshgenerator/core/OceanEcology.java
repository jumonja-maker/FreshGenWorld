package dev.freshgenerator.core;

/** 0.9.1 underwater community suitability (pure model, no world access). Game-scale
 * readings of Duarte 1991 (seagrass depth limit set by light), Graham et al. 2007 and NOAA
 * (kelp holds on hard ground, forms forests in patches) and Roberts et al. 2006 (warm reefs
 * are shallow; other coral systems are not modelled). Probabilities multiply the native
 * placement attempts; the native survival checks stay in control. */
public final class OceanEcology {
    private OceanEcology() { }
    public enum Community { SEAGRASS, KELP, REEF, NONE }
    public enum Ground { SEDIMENT, ROCK, FINE, OTHER }
    /** Water depth (blocks) where each community starts to thin and where it is gone. */
    public static final double SEAGRASS_FULL = 14, SEAGRASS_ZERO = 26, KELP_FULL = 20, KELP_ZERO = 34, REEF_FULL = 10, REEF_ZERO = 18;

    public static Community community(String configuredFeature) {
        if (configuredFeature == null) return Community.NONE;
        if (configuredFeature.contains("seagrass")) return Community.SEAGRASS;
        if (configuredFeature.equals("minecraft:kelp")) return Community.KELP;
        if (configuredFeature.equals("minecraft:warm_ocean_vegetation") || configuredFeature.equals("minecraft:sea_pickle")) return Community.REEF;
        return Community.NONE;
    }
    /** @param depth water blocks above the floor; @param kelpPatch broad patch field in [-1,1]. */
    public static double suitability(Community c, double depth, Ground ground, double kelpPatch) {
        if (!Double.isFinite(depth) || !Double.isFinite(kelpPatch)) throw new IllegalArgumentException("Non-finite ecology input");
        return switch (c) {
            case SEAGRASS -> light(depth, SEAGRASS_FULL, SEAGRASS_ZERO) * switch (ground) { case SEDIMENT, FINE -> 1.0; case ROCK -> .3; case OTHER -> .6; };
            case KELP -> light(depth, KELP_FULL, KELP_ZERO) * switch (ground) { case ROCK -> 1.0; case SEDIMENT -> .55; case FINE -> .35; case OTHER -> .6; }
                    * EcotoneModel.smooth(-.30, .20, kelpPatch);
            // 0.9.1 audit E2: reef builders settle best on hard ground (Harrington et al. 2004).
            case REEF -> light(depth, REEF_FULL, REEF_ZERO) * switch (ground) { case ROCK -> 1.0; case SEDIMENT -> .6; case FINE -> .25; case OTHER -> .6; };
            case NONE -> 1;
        };
    }
    private static double light(double depth, double full, double zero) { return 1 - EcotoneModel.smooth(full, zero, depth); }
    /** Deterministic per-column decision in [0,1). */
    public static double roll(long seed, int x, int z, int salt) {
        long n = seed + x * 0x9E3779B97F4A7C15L + z * 0xD1B54A32D192ED03L + salt * 0x94D049BB133111EBL;
        n = (n ^ (n >>> 30)) * 0xBF58476D1CE4E5B9L; n = (n ^ (n >>> 27)) * 0x94D049BB133111EBL; n ^= n >>> 31;
        return (n >>> 11) * 0x1.0p-53;
    }
}
