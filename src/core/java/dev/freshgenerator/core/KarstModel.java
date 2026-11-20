package dev.freshgenerator.core;

/** 0.9.0 karst cave model: shared constants for the emitted density graph,
 * tests and audits. Geometry is a game-scale analogue of real karst, not a
 * dissolution simulation:
 * <ul>
 * <li>Three stacked phreatic levels (former water tables). Galleries follow the
 *     zero lines of two shared joint-plan noises, so passages are continuous,
 *     meet at junctions and lie on a similar structural plan at every level.</li>
 * <li>Levels connect through bell-shaped pits sitting on passages and rare great
 *     shafts through all levels.</li>
 * <li>The main level carries vadose keyhole canyons, domed halls with full-height
 *     rock pillars (supported at both ends) and the deep level anastomotic mazes.</li>
 * <li>Rare entrances: collapse dolines over upper galleries, inclined passages that
 *     open as a short roofless gorge, and narrow potholes on high plateaus.</li>
 * </ul>
 * Everything else keeps at least ROOF_COVER blocks of rock above any cave. */
public final class KarstModel {
    private KarstModel() { }
    public static final int SEA = 63;
    /** Pack-to-runtime contract: the compiler writes this marker into the current
     * settings and the carver mixin reads the same constant to turn native carvers
     * off. It is a mode switch, not free diagnostic text; the compiled pack keeps the
     * historical spelling byte-for-byte. */
    public static final String NATIVE_CARVERS_OFF = "FG caves disabled";
    /** Level centre = C + A * levelNoise; half heights in blocks. */
    public static final double UPPER_C = 6, UPPER_A = 6, UPPER_HALF = 3.5;
    public static final double MAIN_C = -24, MAIN_A = 5, MAIN_HALF = 7;
    public static final double DEEP_C = -47, DEEP_A = 3, DEEP_HALF = 5;
    /** Gallery half-width parameter in plan-noise units (scaled by hall swelling). */
    public static final double UPPER_W = .034, MAIN_W = .038, DEEP_W = .038, MAZE_W = .04;
    public static final double ROOF_COVER = 14, FLOOR_Y = -57;
    /** Entrances: lowest allowed terrain (water stays below Y62 near the surface). */
    public static final double ENTRANCE_MIN_H = 73, POTHOLE_MIN_H = 125;
    /** Inclined entrance: gate threshold, flat roofless gorge, blocks of descent per unit of
     * |cross| (~0.9 down per block where the slow cross noise has its usual gradient), half height. */
    public static final double RAMP_GATE = .58, RAMP_FLAT = .05, RAMP_DROP = 300, RAMP_HALF = 6;
    /** Doline: noise threshold (constant with depth, so a bowl always has its shaft). */
    public static final double DOLINE_TOP = .76, DOLINE_FUNNEL = 26;
    public static final double POTHOLE = .78;
    /** 0.9.1 winding pothole. Deep plateau shafts in real karst rarely fall straight:
     * they follow joints, wind and break into short drops separated by ledges. Below
     * the opening the shaft centre travels around an irregular helix (quarter turns
     * of POTHOLE_QUARTERS blocks). Offsets are fractions of the shaft radius on each
     * axis, so consecutive layers always overlap (the shaft stays continuous after the
     * native 4-block interpolation, wherever the noise gradient is), while the axis
     * itself is rock once per turn: no straight vertical fall through the shaft. The
     * lower side of the moving section forms the ledges. */
    /** 0.9.1: shaft radii in blocks around a junction of the two gallery families,
     * measured as signed distances to both upper gallery lines (noise / local gradient).
     * Below DOLINE_TOP - .15 - DOLINE_GATE the gate alone keeps rock, so the block-scaled
     * geometry is evaluated only near doline sites. */
    public static final double DOLINE_R_BLOCKS = 5, DOLINE_GATE = 0;
    /** Minimum |sin| of the angle between the two gallery lines at an entrance junction
     * (about 37 degrees) and the closing weight below it. */
    public static final double JUNCTION_SIN = .6, JUNCTION_K = 20;
    /** Update 19.11: the same limit judged at the junction point itself (about 27 degrees).
     * False crossings of tangent or parallel gallery lines measure |sin| 0.003-0.3 there; a
     * real junction near 0.6 (seed 2026111504, X-3624 Z-652) flipped column by column at the
     * former limit because each column estimates the point slightly differently. */
    public static final double JUNCTION_SIN_AT = .45;
    /** 0.9.1: doline gate noise octave. At -9 (about 512 blocks) one open patch covered
     * several gallery junctions, so dolines came in clusters of four or five; -7 patches
     * hold at most one or two junctions. */
    public static final int DOLINE_OCTAVE = -7;
    public static final double POTHOLE_TOP = 8, POTHOLE_R_BLOCKS = 4.5;
    /** The opening needs the full POTHOLE gate; below it the winding shaft may use the
     * slightly larger POTHOLE_BODY patch of the same 2-D noise, so its sideways swing
     * cannot leave the open patch and close the shaft. (Inside that ring, crossings
     * without an opening become blind avens ending 8 blocks below the surface.) */
    public static final double POTHOLE_BODY = .70;
    /** Below POTHOLE_BODY - POTHOLE_GATE the shaft is closed in either form (the 0.9.0
     * branch keeps its 0.78 gate, at least 0.64 above the 0.3 bell), so the winding
     * geometry is evaluated only where it can open. */
    public static final double POTHOLE_GATE = 0;
    /** Update 19.11 (audit C1): the doline and pothole gates are read once, at the junction
     * point itself (the crossing of both gallery lines, found from the local distances and
     * line directions), so a shaft is open along its whole depth or not at all. The gate noise
     * at a swung section of the shaft used to fall below the threshold and cut it into closed
     * stacked pockets. The junction-scale branch is evaluated where the local gate noise is
     * at least *_NEAR: the noise (octave -7) changes by less than the 0.40 margin over the
     * reach of a shaft (radius plus swing, about 11 blocks). */
    public static final double POTHOLE_NEAR = .30, DOLINE_NEAR = .21;
    /** Origin of the helix phase (phase = POTHOLE_PHASE_TOP - Y): above the build height, so
     * every column of a shaft shares one phase whatever the local surface height. */
    public static final double POTHOLE_PHASE_TOP = 320;
    /** Helix radius as a fraction of the shaft radius: above the 1.14 deep bell radius,
     * so the axis is rock on every turn, while the section still reaches both gallery
     * lines of the junction at the bottom. */
    public static final double POTHOLE_SWING = 1.25;
    public static final double[] POTHOLE_QUARTERS = {8, 9, 7, 8, 10, 8, 9, 7, 9, 8, 9, 10, 8, 10};
    public static final double[] POTHOLE_SWAY = {1, .92, 1, .95, .9, 1, .94};
    public static final double PIT_SMALL = .76, PIT_SMALL_DEEP = .84, PIT_GREAT = .80;
    public static final double HALL_SWELL = .50, PILLAR = .58, MAZE = .45, KEYHOLE = .30;
    /** Wall roughness (3-D noise amplitude) and the cross-noise half width of entrance points. */
    public static final double ROUGHNESS = .25, CROSS_W = .06;
}
