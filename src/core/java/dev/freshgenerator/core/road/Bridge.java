package dev.freshgenerator.core.road;

import java.util.ArrayList;
import java.util.List;

/** A road crossing a river (user request 24.11: bridges of native blocks, of several kinds and in several states -
 * intact, worn, ruined). Axis-aligned (a diagonal deck is a staircase of blocks), planned once from the terrain plan
 * and drawn per column, so a bridge over a chunk border is the same from both sides.
 * <ul>
 * <li>LOG: a log over a creek (up to 3 blocks of water), sometimes broken in the middle.</li>
 * <li>STEPPING: stepping stones over a shallow stream.</li>
 * <li>BOARDWALK: planks at the water's surface on posts (the native village street rule over water, as a crossing).</li>
 * <li>WOOD: a plank deck on log posts with beams, a block above the water, fences or posts as rails.</li>
 * <li>STONE: a deck on pillars with arches, rising over long spans, walls as parapets.</li>
 * </ul>
 * The axis is the one nearest to the normal of the channel (audit 25.11 M-1: following the road's heading, a path
 * along a creek got a stone bridge 37 blocks long laid along the stream). Every block of a worn or ruined bridge hangs
 * on a support - a bank, a standing pillar or post - through the blocks that are left (audit 25.11 M-2: blocks kept
 * one by one left plates hanging over the water). */
public final class Bridge {
    public enum Type { LOG, STEPPING, BOARDWALK, WOOD, STONE }
    public enum State { INTACT, WORN, RUINED }

    /** Block roles. PILLAR, POST_UNDER and RUBBLE run down to the bed (the painter descends through water and air). */
    public static final int DECK = 1, STAIR = 2, RAIL = 3, POST = 4, PILLAR = 5, ARCH = 6, STEP = 7, LOG = 8, SUPPORT = 9,
            BEAM = 10, APPROACH = 11, RUBBLE = 12, POST_UNDER = 13;
    /** Variants: the material as built, aged (mossy, cracked), a slab. */
    public static final int NORMAL = 0, AGED = 1, SLAB = 2;
    /** Rail links: toward -along, toward +along, a post (wall up). */
    public static final int LINK_MINUS = 1, LINK_PLUS = 2, LINK_POST = 4;
    /** Smallest angle between the road and the channel that is a crossing; below it the road follows the stream. */
    static final double MIN_CROSSING_ANGLE = 35;

    public interface Sink {
        /** One block: dir is +1/-1 along the axis (stairs ascend, arches lean toward) or 0. */
        void put(int y, int role, int variant, int dir, int links);
    }

    public final boolean alongX;
    /** Water along the axis (inclusive). */
    public final int lo, hi;
    /** Footprint along the axis (ramps and approaches included) and across it (rails included). */
    public final int start, end, across0, across1;
    /** Deck band across the axis (inclusive) and its centre line. */
    public final int c0, c1, centre;
    /** Top water block and the deck height at the banks. */
    public final int water, deck;
    /** Width of the channel the bridge crosses (blocks, from the terrain). */
    public final double channel;
    public final Type type;
    public final State state;
    public final long seed;
    final int rise, rails;
    final int[] posts;
    /** Ramps from the bank columns outward: {along, y, kind (LANDING, DOWN, FLUSH, UP)}. */
    final List<int[]> ramps = new ArrayList<>();
    /** Plan ground tops along the axis on the centre line, and for every column of the band (c0..c1). */
    final int[] tops;
    private final int[][] sideTops;
    final int logY;
    private final int topsStart;
    /** Lowest bed top under the deck. */
    public final int bed;

    private Bridge(boolean alongX, int lo, int hi, int centre, int water, int deck, int bed, double channel, Type type, State state, long seed,
                   int[][] tops, int topsStart) {
        this.alongX = alongX; this.lo = lo; this.hi = hi; this.centre = centre; this.water = water; this.deck = deck;
        this.channel = channel; this.type = type; this.state = state; this.seed = seed;
        int n = hi - lo + 1;
        int half = halfWidth(type);
        this.c0 = centre - half; this.c1 = centre + half;
        this.rise = type == Type.STONE ? (n >= 20 ? 2 : n >= 11 ? 1 : 0) : 0;
        double u = RoadHash.unit(seed, 7, 0);
        this.rails = switch (type) {
            case WOOD -> u < 0.25 ? 0 : u < 0.5 ? 1 : 2;
            case STONE -> u < 0.3 ? 0 : 2;
            default -> 0;
        };
        // Posts and pillars evenly over the water.
        List<Integer> p = new ArrayList<>();
        if (type == Type.STONE) {
            int count = n >= 8 ? Math.max(1, (int) Math.round(n / 6.0)) : 0;
            for (int k = 1; k <= count; k++) p.add(lo - 1 + (int) Math.round(k * (n + 1) / (double) (count + 1)));
        } else if (type == Type.WOOD) {
            for (int a = lo; a <= hi; a += 4) p.add(a);
            if ((hi - lo) % 4 >= 2) p.add(hi);
        } else if (type == Type.BOARDWALK) {
            for (int a = lo + 1; a <= hi; a += 3) p.add(a);
        }
        this.posts = p.stream().mapToInt(Integer::intValue).toArray();
        this.logY = deck;
        this.sideTops = tops;
        this.tops = tops[half];
        this.topsStart = topsStart;
        this.bed = bed;
        // Ramps at each end, from the bank column outward: a bank a block above the deck gets a stair cut into its edge,
        // a bank at deck height meets the deck flush; a lower bank gets stairs down, a block per column (a landing first
        // where the bank lies two or more below the deck), until the ground meets them.
        int[] rampEnds = new int[2];
        for (int end = 0; end < 2; end++) {
            int dirOut = end == 0 ? -1 : 1, a = end == 0 ? lo - 1 : hi + 1;
            if (type == Type.LOG || type == Type.STEPPING) { rampEnds[end] = a; continue; }
            int t = this.tops[a - topsStart];
            if (t > deck) { ramps.add(new int[]{a, deck + 1, UP}); rampEnds[end] = a + dirOut; continue; }
            if (t == deck) { ramps.add(new int[]{a, deck, FLUSH}); rampEnds[end] = a + dirOut; continue; }
            if (t < deck - 1) { ramps.add(new int[]{a, deck, LANDING}); a += dirOut; }
            for (int h = deck, k = 0; k < MAX_STAIRS && this.tops[a - topsStart] < h; k++, h--, a += dirOut) ramps.add(new int[]{a, h, DOWN});
            rampEnds[end] = a;
        }
        int approach = (type == Type.LOG || type == Type.STEPPING) ? 1 : 2;
        this.start = Math.min(lo - 1, rampEnds[0]) - approach + 1;
        this.end = Math.max(hi + 1, rampEnds[1]) + approach - 1;
        this.across0 = c0;
        this.across1 = c1;
    }

    public int width() { return c1 - c0 + 1; }
    /** Block box of the footprint: {minX, minZ, maxX, maxZ}. */
    public int[] box() { return alongX ? new int[]{start, across0, end, across1} : new int[]{across0, start, across1, end}; }
    public boolean contains(int x, int z) {
        int a = alongX ? x : z, c = alongX ? z : x;
        return a >= start && a <= end && c >= across0 && c <= across1;
    }
    /** Water length under the deck along the axis. */
    public int span() { return hi - lo + 1; }

    /** Plans the crossing of the water met by a road at (x, z) heading (dx, dz); null where no bridge is built: the
     * road follows the stream instead of crossing it, the water is too wide, a lake or the sea, or no water on the
     * axis. */
    public static Bridge plan(RoadTerrain terrain, double x, double z, double dx, double dz, long seed, int maxSpan) {
        RoadTerrain.Sample s = new RoadTerrain.Sample();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        // The channel's direction: principal axis of the river columns within 7 blocks.
        double sx = 0, sz = 0, sxx = 0, szz = 0, sxz = 0;
        int count = 0;
        for (int oz = -7; oz <= 7; oz++) for (int ox = -7; ox <= 7; ox++) {
            if (ox * ox + oz * oz > 49) continue;
            terrain.sample(bx + ox, bz + oz, s);
            if (s.kind != RoadTerrain.RIVER) continue;
            count++; sx += ox; sz += oz; sxx += ox * ox; szz += oz * oz; sxz += ox * oz;
        }
        if (count < 2) return null;
        double mx = sx / count, mz = sz / count;
        double cxx = sxx / count - mx * mx, czz = szz / count - mz * mz, cxz = sxz / count - mx * mz;
        double tr = cxx + czz, disc = Math.sqrt(Math.max(0, tr * tr / 4 - (cxx * czz - cxz * cxz)));
        double l1 = tr / 2 + disc, l2 = tr / 2 - disc;
        boolean alongX;
        boolean channelKnown = l1 > 1.8 * Math.max(l2, 0.05);
        if (channelKnown) {
            double ux, uz;
            if (Math.abs(cxz) > 1e-9) { ux = l1 - czz; uz = cxz; } else if (cxx >= czz) { ux = 1; uz = 0; } else { ux = 0; uz = 1; }
            double ul = Math.hypot(ux, uz);
            ux /= ul; uz /= ul;
            double rl = Math.hypot(dx, dz);
            if (rl > 0 && Math.abs(dx * ux + dz * uz) / rl > Math.cos(Math.toRadians(MIN_CROSSING_ANGLE))) return null;
            // The axis nearest to the channel's normal.
            alongX = Math.abs(uz) >= Math.abs(ux);
        } else alongX = Math.abs(dx) >= Math.abs(dz);
        int a = (int) Math.round(alongX ? x : z), c = (int) Math.round(alongX ? z : x);
        int found = Integer.MIN_VALUE;
        for (int d = 0; d <= 3 && found == Integer.MIN_VALUE; d++) {
            if (wet(terrain, alongX, a + d, c, s) == RoadTerrain.RIVER) found = a + d;
            else if (wet(terrain, alongX, a - d, c, s) == RoadTerrain.RIVER) found = a - d;
        }
        if (found == Integer.MIN_VALUE) return null;
        // The water on the bridge's centre line sets the kind of crossing; the deck band then reaches over all the
        // water under it.
        int cl = found, ch = found, water = Integer.MIN_VALUE;
        while (wet(terrain, alongX, cl - 1, c, s) == RoadTerrain.RIVER) { if (found - cl >= maxSpan) return null; cl--; }
        while (wet(terrain, alongX, ch + 1, c, s) == RoadTerrain.RIVER) { if (ch - found >= maxSpan) return null; ch++; }
        int bed = Integer.MAX_VALUE;
        double channel = 0;
        for (int p = cl; p <= ch; p++) {
            sample(terrain, alongX, p, c, s);
            water = Math.max(water, s.water);
            bed = Math.min(bed, top(s.ground));
            channel = Math.max(channel, s.width);
        }
        int n = ch - cl + 1;
        // A span much longer than the channel is wide runs along the stream, not across it.
        if (channel > 0 && n > channel * 1.6 + 3) return null;
        int depth = water - bed;
        double u = RoadHash.unit(seed, 1, 0), v = RoadHash.unit(seed, 2, 0);
        for (Type type : kinds(n, depth, u)) {
            Bridge b = build(terrain, alongX, found, c, cl, ch, water, bed, channel, type, v, seed, maxSpan, s);
            if (b != null) return b;
        }
        return null;
    }

    /** Kinds of crossing in the order they are tried: the drawn one, then the next ones that can join the banks the
     * first cannot (stepping stones under a bank two blocks high, a log between banks three blocks apart). */
    static List<Type> kinds(int n, int depth, double u) {
        List<Type> out = new ArrayList<>(5);
        if (n <= 3) {
            out.add(u < 0.45 ? Type.LOG : depth <= 1 && u < 0.75 ? Type.STEPPING : Type.BOARDWALK);
            out.add(Type.LOG);
            if (depth <= 1) out.add(Type.STEPPING);
            out.add(Type.BOARDWALK);
            out.add(Type.WOOD);
        } else if (n <= 7) {
            out.add(depth <= 1 && u < 0.2 ? Type.STEPPING : u < 0.45 ? Type.BOARDWALK : Type.WOOD);
            out.add(Type.BOARDWALK);
            out.add(Type.WOOD);
            out.add(Type.STONE);
        } else {
            out.add(u < (n <= 20 ? 0.55 : 0.35) ? Type.WOOD : Type.STONE);
            out.add(Type.WOOD);
            out.add(Type.STONE);
        }
        return out.stream().distinct().toList();
    }

    /** One kind of crossing over the water found on the centre line, or null where it cannot carry the road: wider
     * water than it spans, banks it cannot reach (code audits 25.11 Fable K-01, Opus KO-02: stepping stones and
     * boardwalks lay at the water's surface under banks two blocks higher, a wooden deck four blocks below banks on
     * both sides - seed 314159265 X404 Z-401, seed 27182818 X-2294 Z-1563). An intact or worn bridge is walked from
     * bank to bank before it is accepted. */
    private static Bridge build(RoadTerrain terrain, boolean alongX, int found, int c, int cl, int ch, int water, int bed, double channel,
                                Type type, double v, long seed, int maxSpan, RoadTerrain.Sample s) {
        int half = halfWidth(type), lo = cl, hi = ch;
        while (true) {
            int k = band(terrain, alongX, lo - 1, c, half, s);
            if (k == RoadTerrain.DRY) break;
            if (k != RoadTerrain.RIVER || found - lo >= maxSpan) return null;
            lo--;
        }
        while (true) {
            int k = band(terrain, alongX, hi + 1, c, half, s);
            if (k == RoadTerrain.DRY) break;
            if (k != RoadTerrain.RIVER || hi - found >= maxSpan) return null;
            hi++;
        }
        if (hi - lo + 1 > maxSpan || (channel > 0 && hi - lo + 1 > channel * 1.6 + 3 + 2 * half)) return null;
        for (int p = lo; p <= hi; p++) {
            sample(terrain, alongX, p, c, s);
            if (s.kind == RoadTerrain.RIVER) water = Math.max(water, s.water);
        }
        // Ground tops along the axis for every column of the band: ramps, posts and supports stand on the ground of their
        // own column (code audit 25.11 Fable K-03: the centre line's ground under the side columns left supports above
        // a lower ground).
        int topsStart = lo - 1 - RAMP_REACH, topsEnd = hi + 1 + RAMP_REACH;
        int[][] tops = new int[2 * half + 1][topsEnd - topsStart + 1];
        for (int k = -half; k <= half; k++)
            for (int p = topsStart; p <= topsEnd; p++) {
                sample(terrain, alongX, p, c + k, s);
                tops[k + half][p - topsStart] = s.kind == RoadTerrain.DRY ? top(s.ground) : water;
            }
        State state = switch (type) {
            case LOG -> v < 0.25 ? State.RUINED : State.INTACT;
            case STEPPING -> v < 0.3 ? State.WORN : State.INTACT;
            case BOARDWALK, WOOD -> v < 0.4 ? State.INTACT : v < 0.75 ? State.WORN : State.RUINED;
            case STONE -> v < 0.5 ? State.INTACT : v < 0.85 ? State.WORN : State.RUINED;
        };
        int bankA = tops[half][lo - 1 - topsStart], bankB = tops[half][hi + 1 - topsStart];
        int low = Math.min(bankA, bankB), high = Math.max(bankA, bankB);
        // The deck meets the higher bank within a block (a stair cut into its edge carries the step) and stairs descend
        // to the lower one: never a bank two blocks above the deck.
        int deck;
        switch (type) {
            case STEPPING -> { if (high > water + 2) return null; deck = water; }
            case BOARDWALK -> { deck = Math.max(water, high - 1); if (deck > water + 1) return null; }
            case LOG -> { deck = Math.max(water + 1, high - 1); if (deck > low + 1) return null; }
            case WOOD -> { deck = Math.max(Math.max(water + 1, low), high - 1); if (deck > water + 10) return null; }
            case STONE -> { deck = Math.max(Math.max(water + 2, low), high - 1); if (deck > water + 12) return null; }
            default -> throw new IllegalStateException(type.toString());
        }
        Bridge bridge = new Bridge(alongX, lo, hi, c, water, deck, bed, channel, type, state, seed, tops, topsStart);
        return bridge.state == State.RUINED || bridge.passable() ? bridge : null;
    }
    /** Columns of ground beyond the banks known to a bridge: a landing, MAX_STAIRS stairs, approaches and a margin. */
    static final int RAMP_REACH = 12, MAX_STAIRS = 7;
    /** Ramp kinds: a landing at deck height on a lower bank, a stair down (ascending toward the deck), the deck flush
     * with the bank, a stair up into a bank a block above the deck. */
    static final int LANDING = 0, DOWN = 1, FLUSH = 2, UP = 3;

    /** Whether a walker crosses from bank to bank along the centre line with steps of a block at most (stairs halve
     * them); ruined bridges are not crossings. Stepping stones: the stones at the banks (the stream between is shallow). */
    boolean passable() {
        if (state == State.RUINED) return true;
        if (type == Type.STEPPING)
            return Math.abs(stoneY(lo) - tops[lo - 1 - topsStart]) <= 1 && Math.abs(stoneY(hi) - tops[hi + 1 - topsStart]) <= 1;
        int prev = tops[start - 1 - topsStart];
        for (int a = start; a <= end + 1; a++) {
            int top = a > end ? tops[a - topsStart] : walkTop(a);
            if (Math.abs(top - prev) > 1) return false;
            prev = top;
        }
        return true;
    }
    /** The highest block a walker stands on at one along position of the centre line: the bridge's or the ground. */
    private int walkTop(int a) {
        int[] best = {tops[a - topsStart]};
        boolean dry = a < lo || a > hi;
        if (!dry) best[0] = Integer.MIN_VALUE;
        columnAt(a, centre, (y, role, variant, dir, links) -> {
            if (role == DECK || role == STAIR || role == LOG || role == STEP || role == SUPPORT) best[0] = Math.max(best[0], y);
        });
        return best[0] == Integer.MIN_VALUE ? water - 1 : best[0];
    }
    /** Height of a stepping stone: a block higher next to a bank two blocks above the water (a step out of the stream). */
    private int stoneY(int a) {
        if (a == lo && tops[lo - 1 - topsStart] > water + 1) return water + 1;
        if (a == hi && tops[hi + 1 - topsStart] > water + 1) return water + 1;
        return water;
    }
    /** Ground top of one column of the band at one along position. */
    private int groundAt(int a, int c) { return sideTops[c - c0][a - topsStart]; }

    private static int top(double ground) { return (int) Math.ceil(ground) - 1; }
    private static void sample(RoadTerrain t, boolean alongX, int a, int c, RoadTerrain.Sample s) {
        if (alongX) t.sample(a, c, s); else t.sample(c, a, s);
    }
    private static int wet(RoadTerrain t, boolean alongX, int a, int c, RoadTerrain.Sample s) { sample(t, alongX, a, c, s); return s.kind; }
    /** Deck half width of a kind (deck columns centre - half .. centre + half). */
    static int halfWidth(Type type) { return switch (type) { case STONE -> 2; case WOOD, BOARDWALK -> 1; default -> 0; }; }
    /** The water kind across a deck band at one along position: DRY only when the whole band is dry. */
    private static int band(RoadTerrain t, boolean alongX, int a, int c, int half, RoadTerrain.Sample s) {
        int kind = RoadTerrain.DRY;
        for (int k = -half; k <= half; k++) {
            int w = wet(t, alongX, a, c + k, s);
            if (w == RoadTerrain.LAKE || w == RoadTerrain.SEA) return w;
            if (w == RoadTerrain.RIVER) kind = RoadTerrain.RIVER;
        }
        return kind;
    }

    /** Deck height over one along position (stone decks rise over long spans). */
    int deckAt(int a) {
        if (rise == 0 || a < lo - 1 || a > hi + 1) return deck;
        double t = (a - (lo - 1) + 0.5) / (hi - lo + 3);
        return deck + (int) Math.round(rise * Math.sin(Math.PI * t));
    }
    private boolean nearEnd(int a) { return a <= lo + 1 || a >= hi - 1; }
    private double unit(int a, int c, int role, int y) { return RoadHash.unit(RoadHash.of(seed, a, c * 64L + role, y)); }
    private int aged(int a, int c, int role, int y) {
        double u = unit(a, c, role + 100, y);
        return switch (state) { case INTACT -> u < 0.04 ? AGED : NORMAL; case WORN -> u < 0.3 ? AGED : NORMAL; case RUINED -> u < 0.65 ? AGED : NORMAL; };
    }
    /** Top lost by a post or pillar of a worn or ruined bridge (blocks). */
    private int cut(int a) {
        if (state == State.INTACT) return 0;
        double u = unit(a, 0, 50, 0);
        return state == State.WORN ? (u < 0.2 ? 1 : 0) : (int) (u * 4);
    }
    /** Whether the posts of a wooden bridge stand at one along position (both edges alike). */
    private boolean postStands(int a) { return state != State.RUINED || unit(a, 0, POST, 0) >= 0.15; }
    /** Top of the posts of a wooden bridge at a post position. */
    private int postTop(int a) { return deckAt(a) + (rails > 0 ? 1 : 0) - cut(a); }
    /** Whether something under or beside the deck carries it at one along position: a bank, a standing pillar or post. */
    boolean support(int a) {
        if (a == lo - 1 || a == hi + 1) return true;
        if (!contains(posts, a)) return false;
        return switch (type) {
            case STONE -> cut(a) == 0;
            case WOOD -> postStands(a) && postTop(a) >= deckAt(a);
            case BOARDWALK -> true;
            default -> false;
        };
    }
    /** Cantilever of a ruined deck from a support toward dir (blocks beyond the support). */
    private int arm(int support, int dir) {
        if (support == lo - 1 || support == hi + 1) return 1 + (int) (unit(support, dir, 90, 0) * 3);
        return (int) (unit(support, dir, 91, 0) * 4);
    }
    /** Whether a ruined deck still reaches one along position from a support. */
    private boolean reached(int a) {
        for (int d = 0; d <= 3; d++) {
            if (support(a - d) && d <= arm(a - d, 1)) return true;
            if (support(a + d) && d <= arm(a + d, -1)) return true;
        }
        return false;
    }
    /** Whether the deck block of one column of the band is built in this state. Worn decks lose blocks off the centre
     * line only (the centre line carries the deck from bank to bank); ruined decks keep pieces hanging from supports. */
    boolean deckKept(int a, int c) {
        if (a < lo || a > hi || state == State.INTACT || c == centre) return state != State.RUINED || a < lo || a > hi || reached(a);
        // A block off the centre line holds on to the next block inward (an edge of a five-wide deck needs the one
        // between it and the centre line).
        int inward = c < centre ? c + 1 : c - 1;
        if (state == State.WORN) return nearEnd(a) || (unit(a, c, DECK, 0) >= 0.12 && deckKept(a, inward));
        return reached(a) && unit(a, c, DECK, 1) >= 0.4 && deckKept(a, inward);
    }
    /** Whether a block of a role stands in this state (deterministic per block); decks go by deckKept. */
    boolean keep(int role, int a, int c, int y) {
        if (state == State.INTACT) return true;
        double u = unit(a, c, role, y);
        boolean worn = state == State.WORN;
        return switch (role) {
            case STAIR -> worn ? u >= 0.05 : u >= 0.4;
            case RAIL -> worn ? u >= 0.3 : u >= 0.85;
            case ARCH -> worn ? u >= 0.1 : u >= 0.6;
            case BEAM -> worn ? u >= 0.15 : u >= 0.5;
            case STEP -> u >= (worn ? 0.2 : 0.45);
            default -> true;
        };
    }

    /** The blocks of one column (block coordinates). */
    public void column(int x, int z, Sink out) {
        columnAt(alongX ? x : z, alongX ? z : x, out);
    }
    /** The blocks at one along position and one column across the axis. */
    void columnAt(int a, int c, Sink out) {
        if (a < start || a > end || c < across0 || c > across1) return;
        switch (type) {
            case LOG -> log(a, c, out);
            case STEPPING -> stepping(a, c, out);
            default -> deck(a, c, out);
        }
    }

    private void log(int a, int c, Sink out) {
        if (c != centre || a < lo - 1 || a > hi + 1) return;
        int mid = (lo + hi) / 2;
        if (state == State.RUINED) {
            if (a == mid) return; // broken in the middle; the far half has sunk into the water against its bank
            out.put(a > mid ? logY - 1 : logY, LOG, NORMAL, 0, 0);
            return;
        }
        out.put(logY, LOG, NORMAL, 0, 0);
    }

    private void stepping(int a, int c, Sink out) {
        if (c != centre) return;
        if (a < lo || a > hi) { if (a == lo - 1 || a == hi + 1) out.put(tops[a - topsStart], APPROACH, NORMAL, 0, 0); return; }
        if ((a - lo) % 2 != 0 && a != hi) return;
        // The stones at the banks always stay: the steps out of the stream.
        if (a != lo && a != hi && !keep(STEP, a, c, water)) return;
        int y = stoneY(a);
        out.put(y, STEP, aged(a, c, STEP, y), 0, 0);
    }

    private void deck(int a, int c, Sink out) {
        boolean wood = type == Type.WOOD, stone = type == Type.STONE, edge = c == c0 || c == c1;
        // Ramps and approaches on land; every ramp block stands on the ground of its own column.
        for (int[] r : ramps) {
            if (r[0] != a) continue;
            int y = r[1], up = a < lo ? 1 : -1;
            boolean bank = a == lo - 1 || a == hi + 1;
            switch (r[2]) {
                case LANDING, FLUSH -> out.put(y, DECK, aged(a, c, DECK, y), 0, 0);
                // The stair on the bank column carries the deck's end; on a worn bridge the centre line stays walkable.
                case DOWN -> {
                    if (bank || (state == State.WORN && c == centre) || keep(STAIR, a, c, y)) out.put(y, STAIR, aged(a, c, STAIR, y), up, 0);
                }
                case UP -> out.put(y, STAIR, aged(a, c, STAIR, y), -up, 0);
                default -> { }
            }
            for (int s = groundAt(a, c) + 1; s < y; s++) out.put(s, SUPPORT, aged(a, c, SUPPORT, s), 0, 0);
            return;
        }
        if (a < lo - 1 || a > hi + 1) {
            if (!edge || type == Type.BOARDWALK) out.put(groundAt(a, c), APPROACH, NORMAL, 0, 0);
            return;
        }
        if (a == lo - 1 || a == hi + 1) return; // bank column without a ramp: the bank meets the deck
        int y = deckAt(a);
        boolean post = contains(posts, a);
        boolean kept = deckKept(a, c);
        if (wood && post && edge) {
            // Log posts at the deck edges, from the bed up to a block above the deck.
            if (postStands(a)) out.put(postTop(a), PILLAR, NORMAL, 0, 0);
        } else if (stone && post) {
            out.put(y - 1 - Math.min(cut(a), 1), PILLAR, aged(a, c, PILLAR, y), 0, 0);
            if (kept) out.put(y, DECK, aged(a, c, DECK, y), 0, 0);
        } else {
            int variant = wood && state != State.INTACT && unit(a, c, 77, y) < 0.08 ? SLAB : aged(a, c, DECK, y);
            if (kept) {
                int r0 = deckAt(a - 1), r1 = deckAt(a + 1);
                if (rise > 0 && y > r0 && a > lo - 1) out.put(y, STAIR, variant == SLAB ? NORMAL : variant, 1, 0);
                else if (rise > 0 && y > r1 && a < hi + 1) out.put(y, STAIR, variant == SLAB ? NORMAL : variant, -1, 0);
                else out.put(y, DECK, variant, 0, 0);
                for (int s = deck; s < y; s++) out.put(s, SUPPORT, aged(a, c, SUPPORT, s), 0, 0);
            } else if (stone && state == State.RUINED && unit(a, c, 88, y) < 0.12) out.put(water, RUBBLE, AGED, 0, 0);
            if (type == Type.BOARDWALK && post && edge) out.put(y - 1, POST_UNDER, NORMAL, 0, 0);
            // Beams under the deck between the posts, carried by posts that still reach them.
            if (wood && post && !edge && postStands(a) && postTop(a) >= y - 1 && keep(BEAM, a, c, y)) out.put(y - 1, BEAM, NORMAL, 0, 0);
            // Arches hang under the deck against the pillars and the banks.
            if (stone && kept) {
                boolean nextToPillar = contains(posts, a - 1) || contains(posts, a + 1);
                int dir = contains(posts, a - 1) || a == lo ? -1 : 1;
                if ((nextToPillar || a == lo || a == hi) && y - 1 > water && keep(ARCH, a, c, y)) out.put(y - 1, ARCH, aged(a, c, ARCH, y), dir, 0);
            }
        }
        // Rails along the deck edges, only over a deck that is still there (no fence hanging over a hole).
        if (edge && rails == 2 && !(wood && post) && type != Type.BOARDWALK && kept) {
            int ry = y + 1;
            if (keep(RAIL, a, c, ry)) {
                int links = 0;
                if (a - 1 >= lo && railAt(a - 1, c)) links |= LINK_MINUS;
                if (a + 1 <= hi && railAt(a + 1, c)) links |= LINK_PLUS;
                if (stone && (post || a == lo || a == hi)) links |= LINK_POST;
                out.put(ry, RAIL, aged(a, c, RAIL, ry), 0, links);
            }
        }
    }
    /** Whether a rail (or a wooden post standing as one) is at one along position of an edge. */
    private boolean railAt(int a, int c) {
        if (type == Type.WOOD && contains(posts, a)) return postStands(a) && postTop(a) == deckAt(a) + 1;
        return deckKept(a, c) && keep(RAIL, a, c, deckAt(a) + 1);
    }
    private static boolean contains(int[] list, int v) { for (int x : list) if (x == v) return true; return false; }

    @Override public String toString() {
        return type + "/" + state + (alongX ? " x" : " z") + "[" + lo + ".." + hi + "]@" + centre + " water " + water + " deck " + deck
                + " channel " + Math.round(channel * 10) / 10.0;
    }
}
