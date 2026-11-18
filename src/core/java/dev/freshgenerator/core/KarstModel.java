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
    public static final double DOLINE_TOP = .72, DOLINE_FUNNEL = 26;
    public static final double POTHOLE = .78;
    public static final double PIT_SMALL = .76, PIT_SMALL_DEEP = .84, PIT_GREAT = .80;
    public static final double HALL_SWELL = .50, PILLAR = .58, MAZE = .45, KEYHOLE = .30;
    /** Wall roughness (3-D noise amplitude) and the cross-noise half width of entrance points. */
    public static final double ROUGHNESS = .25, CROSS_W = .06;
}
