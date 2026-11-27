package dev.freshgenerator.core.road;

/** Which villages a road joins (user request 24.11: roads between villages only, and not between all of them).
 * <ul>
 * <li>Neighbours only: a relative-neighbourhood graph - no road when a third village is closer to both ends (no
 *     parallel roads, no long road past a nearer village).</li>
 * <li>Not farther apart than MAX_SPAN; a road may be up to FACTOR times longer than the straight line.</li>
 * <li>A seeded chance per pair, falling with distance and with the height between the villages: a player meets a road
 *     now and then, about half of the villages stand alone.</li>
 * </ul> */
public final class RoadGraph {
    /** Longest pair: 1300 since 40% of the village cells stay empty (user request 25.11), neighbours stand farther apart. */
    public static final double MAX_SPAN = 1300, FACTOR = 1.5, REACH = 96;
    /** Base chance of a road between two neighbouring villages. */
    public static final double CHANCE = 0.65;
    private RoadGraph() { }

    /** A village site of the plan: its cell key and approximate centre. */
    public record Site(long cell, double x, double z, double y) {
        public double distance(Site o) { return Math.hypot(x - o.x, z - o.z); }
    }

    /** Chance of a road between two villages d blocks apart with dy blocks of height between them. */
    public static double chance(double d, double dy) {
        double c = CHANCE;
        if (d > 500) c *= 1 - 0.4 * Math.min(1, (d - 500) / (MAX_SPAN - 500));
        if (Math.abs(dy) > 40) c *= 0.6;
        return c;
    }
    /** The seeded roll of a pair (order-free). */
    public static boolean rolled(long seed, Site a, Site b) {
        long lo = Math.min(a.cell, b.cell), hi = Math.max(a.cell, b.cell);
        return RoadHash.unit(seed, lo, hi, 0x7A11) < chance(a.distance(b), a.y - b.y);
    }
    /** Axis sum of the corridor ellipse of a pair. */
    public static double corridor(Site a, Site b) { return FACTOR * a.distance(b) + 2 * REACH; }
    /** Whether a point lies within the corridor of a pair (with a margin). */
    public static boolean covers(Site a, Site b, double x, double z, double margin) {
        return Math.hypot(x - a.x, z - a.z) + Math.hypot(x - b.x, z - b.z) <= corridor(a, b) + 2 * margin;
    }
    /** Relative-neighbourhood test against the other sites. */
    public static boolean neighbours(Site a, Site b, Iterable<Site> others) {
        double d = a.distance(b);
        for (Site c : others) {
            if (c.cell == a.cell || c.cell == b.cell) continue;
            if (a.distance(c) < d && b.distance(c) < d) return false;
        }
        return true;
    }
}
