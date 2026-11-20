package dev.freshgenerator.core;

import static dev.freshgenerator.core.DensityExpr.*;
import static dev.freshgenerator.core.NaturalTerrainProgram.smootherstep;

/** 0.9.1 repairs from the external 0.9.0 audit, as native density expressions over
 * the existing landscape nodes. Historical layers stay byte-identical; this layer
 * replaces only the named nodes in the current world.
 * <ul>
 * <li>A02 giant flanks: the folded ridge field changes about three times faster than
 *     the raw ridge noise, so a 170-block uplift over its former 0.90-wide window rose
 *     by up to 15 blocks per block. Now 30 percent of the uplift is a regional highland
 *     base and the ridge share follows a 1.6-wide window; summits on the main crests
 *     keep their height.</li>
 * <li>A01 coastal walls: the native offset spline jumps from the ocean shelf (C below
 *     -0.18) to full inland height within C~0.02, a 70-90 block wall over 4-6 blocks.
 *     Continentalness serves as a distance-from-sea coordinate: the allowed coastal
 *     height rises with it, steeply on resistant rock (sea cliffs), gently on soft
 *     coasts. It is only a ceiling: it never raises land and never reaches below Y65,
 *     so dry land cannot drown. Inland and offshore it is inactive.</li>
 * </ul> */
public final class TerrainRepair {
    private TerrainRepair() { }
    /** GIANT_BASE of the giant uplift is regional: the whole province rises as a
     * highland (it still vanishes in native river corridors through the mountain
     * mask). The rest follows the ridge field over the widened window. */
    public static final double GIANT_RIDGE_LOW = -.65, GIANT_RIDGE_HIGH = .95, GIANT_BASE = .3;
    public static final double GIANT_VALLEY_LOW = -.95, GIANT_VALLEY_HIGH = -.10;
    /** Below this province noise the giant mask is exactly zero (smootherstep lower edge). */
    public static final double GIANT_NOISE_LOW = .40;
    /** Coastal ceiling: Y65 (two blocks above the sea) up to C=-0.177, where the native
     * spline leaves the ocean shelf, then rising 450 (soft) to 1500 (resistant rock)
     * blocks per unit of continentalness; with the usual coastal C gradient
     * (~0.003 per block) that is about 1.3 to 4.5 blocks per block. The terrain is
     * interpolated on 4-block cells, so the ceiling must already be low where the
     * shelf ends: starting it further seaward left a 30-block wall in one cell. */
    public static final double COAST_CAP_BASE = 65, COAST_CAP_START = -.177, COAST_CAP_SOFT = 450, COAST_CAP_ROCK = 1050;
    /** The ceiling fades out over the ocean shelf (C below -0.25). */
    public static final double COAST_CAP_FADE_LOW = -.25, COAST_CAP_FADE_HIGH = -.21;

    /** The ordinary mountain mask without its narrow valley clearance (land, erosion,
     * province), times a wider valley clearance for the much taller giant uplift:
     * native river corridors (folded ridges below -0.95) stay free, while the
     * corridor sides rise over a broad slope instead of the ordinary 0.52 window. */
    public static DensityExpr giant(String prefix) {
        String l = prefix + "landscape/";
        DensityExpr p = ref(prefix + "ridges_folded");
        DensityExpr region = mul(mul(ref(l + "land"), sub(constant(1), smootherstep(-.72, .18, ref(prefix + "erosion")))),
                add(constant(.62), scale(ref(l + "province"), .38)));
        return mul(mul(region, smootherstep(GIANT_VALLEY_LOW, GIANT_VALLEY_HIGH, p)), mul(smootherstep(GIANT_NOISE_LOW, .72, ref(l + "giant_noise")),
                add(constant(GIANT_BASE), scale(smootherstep(GIANT_RIDGE_LOW, GIANT_RIDGE_HIGH, p), 1 - GIANT_BASE))));
    }
    public static DensityExpr coastCap(String prefix) {
        String l = prefix + "landscape/";
        DensityExpr c = ref(prefix + "continents");
        return add(add(constant(COAST_CAP_BASE), mul(
                new Binary(Op.MAX, constant(0), add(c, constant(-COAST_CAP_START))),
                add(constant(COAST_CAP_SOFT), scale(ref(l + "coast_rock"), COAST_CAP_ROCK)))),
                scale(sub(constant(1), smootherstep(COAST_CAP_FADE_LOW, COAST_CAP_FADE_HIGH, c)), 1000));
    }
    /** Reads the original coastal profile once (repeated heavy references are
     * re-evaluated in the 26.3 graph). */
    public static DensityExpr coastHeight(String prefix) {
        String l = prefix + "landscape/";
        return new Binary(Op.MIN, ref(l + "coast_profile"), ref(l + "coast_cap"));
    }
}
