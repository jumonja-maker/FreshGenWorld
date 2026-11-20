package dev.freshgenerator.core;

/** 0.9.1 ocean floor provinces (game-scale geomorphology, after the 0.9.1 ocean audit and
 * Harris et al. 2014 "Geomorphology of the oceans"). The native offset spline builds almost
 * the whole ocean from two plateaus (H48.2 shelf, H35.1 deep floor). On the deep floor only,
 * a slow province field now chooses between:
 * <ul>
 * <li>abyssal basins: a broad plain about BASIN blocks lower, with rare narrow trenches;</li>
 * <li>ridge provinces: a mid-ocean ridge network (abs-noise lines) with a central rift;</li>
 * <li>everywhere deep: gentle swell and isolated seamounts that rise toward the light zone.</li>
 * </ul>
 * The shelf stays a quiet sedimentary plain. Nothing is applied on land, at coasts, or on
 * mushroom-island continentalness; the result never rises above CEILING (no new islands).
 * <p>Update 19.11 (0.9.1 audit O1 and own review): seamounts are rounded cones of varying
 * height (a second slow field scales each one, the summit no longer saturates into a top hat),
 * and the open ocean ceiling is soft: heights pass unchanged up to CAP[0], then bend toward
 * CEILING, so different uplifts no longer share one flat cut plane. Trench depth varies along
 * the arc with the same size field.
 * Monuments keep their fixed height: basins stay shallow enough for their beard_box. */
public final class OceanFloor {
    private OceanFloor() { }
    /** Continentalness window of the open ocean (coast above, mushroom islands below). */
    public static final double C_OPEN = -.19, C_FADE = -.24, C_MUSHROOM = -1.0;
    /** Native height window that marks the deep floor (35) versus the shelf (48). */
    public static final double DEEP_LOW = 36, DEEP_HIGH = 46;
    public static final double BASIN = 7, SWELL = 2.5, RIDGE = 11, RIFT = 4, MOUNT = 24, TRENCH = 14;
    /** Asymptote of the soft open-ocean ceiling (sea level 63 stays at least 4 blocks above). */
    public static final double CEILING = 59;
    /** Soft ceiling spline {location, value, derivative}: identity below 50, monotone (Fritsch-Carlson bounds), flat at CEILING. */
    public static final double[][] CAP = {{50, 50, 1}, {57, 56, .5}, {66, 58.4, .07}, {90, CEILING, 0}};
    public static final int PROVINCE_OCTAVE = -10, RIDGE_OCTAVE = -9, MOUNT_OCTAVE = -7, SWELL_OCTAVE = -8, TRENCH_OCTAVE = -9, SIZE_OCTAVE = -8;
    public static final double RIDGE_WIDTH = .22, RIFT_WIDTH = .035, TRENCH_WIDTH = .03, MOUNT_LOW = .6, MOUNT_HIGH = 1.05;
    /** Smallest seamount and trench as a share of the full height/depth (size field at its minimum). */
    public static final double MOUNT_MIN_SHARE = .5, TRENCH_MIN_SHARE = .6;
}
