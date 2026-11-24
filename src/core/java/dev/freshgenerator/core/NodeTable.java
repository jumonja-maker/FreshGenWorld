package dev.freshgenerator.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Shared direct-mapped memo of float values on an integer lattice (lattice fields, island offsets).
 * <p>The hot climate path (every height sample of a lake fill reads the climate through the terrain relief) cannot
 * afford a ThreadLocal or a hash map per lookup: an entry is one atomic long - a tag of the high index bits and the
 * float value - in a slot chosen by the low bits, so a lookup is one load and one compare, without locks. Writers
 * race harmlessly: the values are pure functions of the node, a lost or overwritten entry is only recomputed.
 * Indices beyond the tag range (beyond the world border) are not memoized. */
public final class NodeTable {
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);
    private static final int BITS = 8, MASK = (1 << BITS) - 1, TAG_BITS = 13, TAG_MASK = (1 << TAG_BITS) - 1;
    private static final int TAG_MIN = -(1 << (TAG_BITS - 1)), TAG_MAX = (1 << (TAG_BITS - 1)) - 1;
    private static final int VALID = 1 << 30;
    private final long[] slots = new long[1 << (2 * BITS)];

    /** The memoized value of node (i, j), or NaN when it is not in the table. */
    public float get(int i, int j) {
        int ti = i >> BITS, tj = j >> BITS;
        if (ti < TAG_MIN || ti > TAG_MAX || tj < TAG_MIN || tj > TAG_MAX) return Float.NaN;
        long e = (long) SLOT.getOpaque(slots, (i & MASK) | (j & MASK) << BITS);
        return (int) (e >>> 32) == tag(ti, tj) ? Float.intBitsToFloat((int) e) : Float.NaN;
    }
    public void put(int i, int j, float value) {
        int ti = i >> BITS, tj = j >> BITS;
        if (ti < TAG_MIN || ti > TAG_MAX || tj < TAG_MIN || tj > TAG_MAX) return;
        SLOT.setOpaque(slots, (i & MASK) | (j & MASK) << BITS, (long) tag(ti, tj) << 32 | (Float.floatToRawIntBits(value) & 0xffffffffL));
    }
    private static int tag(int ti, int tj) { return VALID | (ti & TAG_MASK) << TAG_BITS | (tj & TAG_MASK); }
}
