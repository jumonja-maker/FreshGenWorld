package dev.freshgenerator.core;

import static dev.freshgenerator.core.DensityExpr.*;
import static dev.freshgenerator.core.NaturalTerrainProgram.smootherstep;

/** 0.8.2: added fine relief may not drown native dry land below the sea surface.
 * Native low ground (valley floors, sea bed, swamp hollows below about H60) keeps
 * its full relief. Dry native ground keeps every upward bump, but its downward
 * excursions softly settle on a low coastal plain one block above the water.
 *
 * Both one-argument curves are emitted as native cubic splines. A spline reads its
 * coordinate once; an arithmetic soft maximum would reference the whole landscape
 * root five times, and those references are re-evaluated (measured: 5.7x slower
 * chunk generation). The guard curve (d+3)^2/12 on [-3,3] is reproduced exactly
 * by the Hermite form and continues linearly (slope 0 below, 1 above): C1, no crease.
 * This is a terrain-envelope rule, not hydrology: no water is placed or removed.
 */
public final class CoastalRelief {
    private CoastalRelief() { }
    /** Spline points {location, value, derivative} of the floor over the unrelieved base. */
    public static final double[][] FLOOR_POINTS = {{60, 20, 1}, {63.5, 63.5, 1}, {64, 64, 0}};
    /** Soft maximum offset g(d), d = unguarded - floor; height = floor + g(d). */
    public static final double[][] GUARD_POINTS = {{-3, 0, 0}, {3, 3, 1}};
    /** Sea level 63 keeps water at Y62; H above 64 leaves the top block at Y64. */
    public static final double DRY_FLOOR = 64, SOFTNESS = 3;
    /** Former 55..73 mask also roughened dry lowland; detail now ends below H60. */
    public static final double SEABED_DETAIL_START = 52, SEABED_DETAIL_END = 60;

    public static DensityExpr seabedMask(DensityExpr nativeHeight) {
        return sub(constant(1), smootherstep(SEABED_DETAIL_START, SEABED_DETAIL_END, nativeHeight));
    }
    /** Exact Minecraft CubicSpline.Multipoint evaluation (linear extension outside). */
    public static double spline(double[][] points, double c) {
        if (!Double.isFinite(c)) throw new IllegalArgumentException("Nonfinite spline coordinate");
        int n = points.length;
        if (c < points[0][0]) return points[0][1] + points[0][2] * (c - points[0][0]);
        if (c >= points[n - 1][0]) return points[n - 1][1] + points[n - 1][2] * (c - points[n - 1][0]);
        int i = 0; while (c >= points[i + 1][0]) i++;
        double x0 = points[i][0], x1 = points[i + 1][0], y0 = points[i][1], y1 = points[i + 1][1];
        double h = x1 - x0, f = (c - x0) / h;
        double a = points[i][2] * h - (y1 - y0), b = -points[i + 1][2] * h + (y1 - y0);
        return y0 + f * (y1 - y0) + f * (1 - f) * (a + f * (b - a));
    }
    public static double floorValue(double base) { return spline(FLOOR_POINTS, base); }
    public static double guardedHeight(double base, double raw) {
        if (!Double.isFinite(raw)) throw new IllegalArgumentException("Nonfinite height");
        double f = floorValue(base);
        return f + spline(GUARD_POINTS, raw - f);
    }
}
