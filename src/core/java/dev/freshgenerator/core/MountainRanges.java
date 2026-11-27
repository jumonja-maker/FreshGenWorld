package dev.freshgenerator.core;

/** Mountain ranges of the current world (user request 26.11: "realistic ranges and high mountains instead of single
 * mountains standing in a field"). Shared constants of the emitted density graph and its tests.
 * <p>Measured before the change (12 km windows, eight seeds, builds 0.9.0 and 474b62aa alike, vanilla too): land above
 * Y130 was split into 60-150 separate spots per 100 km2, the largest 0.2-1.7 km2; no connected mountain area of 2 km2
 * or more on any seed. The relief came from the native erosion/ridge knobs, which nothing tied together.
 * <p>Model (a game-scale analogue of orogenic belts, not a tectonic simulation):
 * <ul>
 * <li><b>Axes.</b> A range follows a zero line of a very low frequency noise (wavelength ~8 km), meandering through a
 * domain warp. Zero lines of such a noise are long continuous curves that branch and close only on the scale of many
 * kilometres - the plan of real ranges. The distance to the axis is measured in blocks: |noise| / |gradient|.</li>
 * <li><b>Belts.</b> Only some of the axes are active (a second noise of the same scale): ranges begin, taper and end;
 * between them lie plains and hills. A branch family (its own noise, lower and narrower) adds side ranges.</li>
 * <li><b>Cross section.</b> Height falls from a sharp crest over a concave flank to foothills at 1.5 half-widths;
 * the half-width (750-1300 blocks) narrows where the range dies out.</li>
 * <li><b>Along the crest.</b> Summits and saddles from a slow height field and a peak field; a slow warp of the axis
 * distance gives spurs and re-entrants on the flanks.</li>
 * <li><b>Single mountains.</b> The older erosion/ridge mountain uplift keeps its full height near the belts and falls
 * to 35% away from them: isolated knobs in the plains become hills; snow massifs of the cold cores stay.</li>
 * </ul>
 * The range shape is read from a 32-block lattice with cubic interpolation (freshgenerator:lattice). */
public final class MountainRanges {
    private MountainRanges() { }
    /** Axis noise (octave, octaves) and its domain warp (octave, blocks per unit of noise). */
    public static final int AXIS_OCTAVE = -13, AXIS_OCTAVES = 2, WARP_OCTAVE = -11;
    public static final double WARP = 700;
    /** Branch family: noise octave, height and width relative to the main ranges. */
    public static final int BRANCH_OCTAVE = -12;
    public static final double BRANCH_HEIGHT = 0, BRANCH_WIDTH = .6;
    /** Finite-difference step of the axis gradient (blocks). */
    public static final double GRADIENT_STEP = 48;
    /** Activity: noise octave and the smooth band of the noise over which a range comes to full strength. */
    public static final int ACTIVITY_OCTAVE = -13;
    public static final double ACTIVITY_IN = -.35, ACTIVITY_FULL = .10;
    /** Half-width (blocks): BASE + SPREAD x width field, scaled by (TAPER + (1 - TAPER) x activity). */
    public static final int WIDTH_OCTAVE = -11;
    public static final double WIDTH_BASE = 750, WIDTH_SPREAD = 550, TAPER = .55;
    /** Crest height above the land (blocks): BASE + SPREAD x height field, times the peak factor. */
    public static final int HEIGHT_OCTAVE = -10, PEAK_OCTAVE = -9;
    public static final double HEIGHT_BASE = 65, HEIGHT_SPREAD = 85, PEAK_LOW = .78, PEAK_SPREAD = .34;
    /** Cross profile over distance / half-width: {location, value, derivative}. A small negative slope at the axis
     * keeps a crest instead of a rounded top; foothills end at 1.6 half-widths. */
    public static final double[][] PROFILE = {{0, 1, -1.0}, {.3, .68, -1.1}, {.7, .3, -.75}, {1.1, .08, -.25}, {1.5, 0, 0}};
    /** Spurs and embayments: the distance to the axis is warped, u' = u (1 + SPUR_WARP n) with a slow noise (512-block
     * wavelength). The height stays a monotonic function of u' (the warp gradient stays below that of u), so the flanks
     * get spurs and re-entrants without closed hollows. A multiplicative ridged rib noise and the native cellular ridge
     * field made a hollow at every noise extremum: the drainage model turned them into ponds over the flanks (seed 42:
     * water on 5.9% of the land against 3.2%). Fine roughness comes from the native surface detail. */
    public static final int SPUR_OCTAVE = -9;
    public static final double SPUR_WARP = .20;
    /** Old erosion/ridge mountains: full height within NEAR_FULL half-widths of a range, OLD_FLOOR beyond NEAR_OUT. */
    public static final double OLD_FLOOR = .35, NEAR_FULL = 1.2, NEAR_OUT = 2.6;
    /** Through-gaps (water gaps): a slow noise blob (wavelength ~1 km) lowers the range to nothing in its core. Zero
     * lines of the axis noise often close into rings; a ring range round a lowland made a closed basin that the drainage
     * model filled up to the lowest col, 100 blocks up (seed 31337: water on 15.2% of the land against 5.7% before).
     * Real ranges are cut by through valleys (water gaps, antecedent rivers); here every few kilometres of a belt one
     * opens down to the lowland. */
    public static final int GAP_OCTAVE = -10;
    public static final double GAP_FULL = -.62, GAP_OUT = -.36;
    /** Lattice spacing of the range shape (blocks), read with Catmull-Rom (C1) interpolation. A bilinear 8-block
     * lattice showed its facets as square terraces on the flanks (seed 8675309 X-1580 Z-3680); a 4-block one thrashed
     * the node table (a 256 x 256 direct-mapped window, about 1 km at 4 blocks) under the drainage model, which samples
     * the ground over kilometres: generation in a desert x2.5. */
    public static final int LATTICE = 32;
}
