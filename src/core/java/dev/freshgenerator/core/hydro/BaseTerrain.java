package dev.freshgenerator.core.hydro;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Terrain height before hydrology at block resolution: the input field sampled on the native 4-block
 * cell grid and interpolated bilinearly. The game's terrain density uses this very function, so the
 * drainage model predicts the generated ground exactly (water containment depends on it). */
public final class BaseTerrain {
    public static final int CELL = 4;
    private static final int CACHE = 1 << 17;
    private record Corner(int x, int z, double h) { }
    private final HydroInputs inputs;
    /** Corners with a value that is not a float (synthetic inputs in tests): hashed record cache. */
    private final AtomicReferenceArray<Corner> corners = new AtomicReferenceArray<>(CACHE);

    /** Corners by grid index, direct-mapped by the low SLOT_BITS of both indices: any window up to
     * 2^SLOT_BITS corners (2048 blocks) on a side - a lake fill box, the query window of a chunk - has no
     * conflicts (the hashed cache lost half of the corners of a lake fill to conflicts and computed them again).
     * One long per slot, written and read atomically: tag (valid bit + high index bits) and the float bits of the
     * height. The game's heights are floats (density samples), so this is exact; other values use the record cache.
     * Tags cover corner indices of +-2^23 (+-33 million blocks, beyond the world border). */
    private static final int SLOT_BITS = 9, SLOT_MASK = (1 << SLOT_BITS) - 1, TAG_BITS = 15, TAG_MASK = (1 << TAG_BITS) - 1;
    private static final int TAG_MIN = -(1 << (TAG_BITS - 1)), TAG_MAX = (1 << (TAG_BITS - 1)) - 1, VALID = 1 << 30;
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);
    private final long[] grid = new long[1 << (2 * SLOT_BITS)];

    public BaseTerrain(HydroInputs inputs) { this.inputs = Objects.requireNonNull(inputs); }

    /** The input field at a grid node (x and z multiples of {@link #CELL}). */
    public double corner(int x, int z) {
        if (((x | z) & (CELL - 1)) == 0) {
            int i = x >> 2, j = z >> 2, ti = i >> SLOT_BITS, tj = j >> SLOT_BITS;
            if (ti >= TAG_MIN && ti <= TAG_MAX && tj >= TAG_MIN && tj <= TAG_MAX) {
                int slot = (i & SLOT_MASK) | (j & SLOT_MASK) << SLOT_BITS;
                int tag = VALID | (ti & TAG_MASK) << TAG_BITS | (tj & TAG_MASK);
                long e = (long) SLOT.getOpaque(grid, slot);
                if ((int) (e >>> 32) == tag) return Float.intBitsToFloat((int) e);
                double h = hashed(x, z);
                float f = (float) h;
                if ((double) f == h) SLOT.setOpaque(grid, slot, (long) tag << 32 | (Float.floatToRawIntBits(f) & 0xffffffffL));
                return h;
            }
        }
        return hashed(x, z);
    }

    private double hashed(int x, int z) {
        int hash = x * 0x9E3779B1 ^ Integer.rotateLeft(z * 0x85EBCA6B, 13);
        int index = (hash ^ (hash >>> 15)) & (CACHE - 1);
        Corner entry = corners.get(index);
        if (entry != null && entry.x == x && entry.z == z) return entry.h;
        double h = inputs.height(x, z);
        if ((double) (float) h != h) corners.set(index, new Corner(x, z, h));
        return h;
    }

    /** Height at a block column. */
    public double at(int x, int z) {
        int x0 = Math.floorDiv(x, CELL) * CELL, z0 = Math.floorDiv(z, CELL) * CELL;
        double tx = (x - x0) / (double) CELL, tz = (z - z0) / (double) CELL;
        double a = corner(x0, z0), b = corner(x0 + CELL, z0), c = corner(x0, z0 + CELL), d = corner(x0 + CELL, z0 + CELL);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    /** Height at an arbitrary point (bilinear in the same grid). */
    public double at(double x, double z) {
        int x0 = (int) Math.floor(x / CELL) * CELL, z0 = (int) Math.floor(z / CELL) * CELL;
        double tx = (x - x0) / CELL, tz = (z - z0) / CELL;
        double a = corner(x0, z0), b = corner(x0 + CELL, z0), c = corner(x0, z0 + CELL), d = corner(x0 + CELL, z0 + CELL);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    public HydroInputs inputs() { return inputs; }
}
