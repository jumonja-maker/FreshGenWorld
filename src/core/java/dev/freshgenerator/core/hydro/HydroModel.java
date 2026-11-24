package dev.freshgenerator.core.hydro;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Drainage network on a jittered 32-block lattice (docs/hydrology-1.0.md, section 3.2).
 * <p>Every value is a pure function of the seed and the input fields; caches only memoize, so the result does
 * not depend on the order or the threads in which chunks are generated. Dependencies run between lattice nodes
 * (never between tiles), and recursive values are computed with explicit stacks.
 * <p>Pipeline: samples -> steepest-descent receivers -> terminal pits -> depressions (Priority-Flood from the
 * pit, fill-spill-merge through mutual spills) -> flow graph (lake interiors drain through one virtual basin
 * node to the rim) -> runoff-weighted accumulation -> channel heads (area vs slope, climate) inherited
 * downstream -> node water levels (monotone, bank-limited, backwater from receiving lakes and the sea). */
public final class HydroModel {
    public static final int SPACING = 32, TILE = 32, JITTER = 9, NODES = TILE * TILE;
    public static final double SEA_LEVEL = 63;
    /** Top water block of the sea (native fluid level 63 fills y < 63). */
    public static final int SEA_WATER = 62;
    public static final double OCEAN_CONTINENTS = -0.16;
    public static final double CELL_KM2 = SPACING * (double) SPACING / 1e6;
    static final int MAX_FLOOD = 24_000;
    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1}, DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    static final byte UNKNOWN = -1, PIT = 8, OCEAN = 9;
    public static final long NONE = Long.MIN_VALUE, OCEAN_ID = Long.MIN_VALUE + 1, BASIN_FLAG = Long.MIN_VALUE + 2;
    static final long DOWN_UNKNOWN = Long.MIN_VALUE + 3;
    public static final int NO_WATER = Integer.MIN_VALUE;

    final BaseTerrain terrain;
    final HydroInputs inputs;
    final long seed;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Flood> raw = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Group> groups = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long, HydroReach> reaches = new ConcurrentHashMap<>();

    public HydroModel(HydroInputs inputs, long seed) {
        this(Objects.requireNonNull(inputs), new BaseTerrain(inputs), seed);
    }
    private HydroModel(HydroInputs inputs, BaseTerrain terrain, long seed) {
        this.inputs = inputs; this.terrain = terrain; this.seed = seed;
    }
    /** An empty model on the same inputs and seed, sharing the exact terrain cache (a pure function of the inputs).
     * Bounded memory replaces a full model by a fresh one: every derived value is recomputed from scratch, as in a new
     * session. Forgetting only part of the derived state is not possible - a depression whose pits lay on kept and
     * forgotten tiles stayed half registered (code audit 24.11 F-1). */
    HydroModel fresh() { return new HydroModel(inputs, terrain, seed); }
    public BaseTerrain terrain() { return terrain; }

    // ---------------------------------------------------------------- lattice

    static long node(int i, int j) { return ((long) i << 32) | (j & 0xffffffffL); }
    static int ni(long key) { return (int) (key >> 32); }
    static int nj(long key) { return (int) key; }
    static long nb(long key, int d) { return node(ni(key) + DX[d], nj(key) + DZ[d]); }

    static final class Tile {
        final int[] x = new int[NODES], z = new int[NODES];
        final float[] h = new float[NODES], c = new float[NODES], t = new float[NODES], m = new float[NODES], e = new float[NODES];
        final byte[] setting = new byte[NODES], recv = new byte[NODES], chan = new byte[NODES];
        final long[] term = new long[NODES], down = new long[NODES];
        /** Bit d: neighbour d drains into this node; -1 unknown. */
        final short[] donorBits = new short[NODES];
        final float[] area = new float[NODES], flow = new float[NODES], water = new float[NODES], recvLevel = new float[NODES], bed = new float[NODES];
        /** Continuous climate parameters every node needs (HydroClimate.params): runoff, channel-head area,
         * width/depth factors and the permanent-water factor. Basin parameters are computed on demand at the
         * deepest node (paramsAt). */
        final float[] runoff = new float[NODES], chanArea = new float[NODES], widthK = new float[NODES], depthK = new float[NODES],
                wetK = new float[NODES];
        Tile() {
            Arrays.fill(recv, UNKNOWN); Arrays.fill(chan, UNKNOWN); Arrays.fill(term, NONE); Arrays.fill(down, DOWN_UNKNOWN);
            Arrays.fill(donorBits, (short) -1);
            Arrays.fill(area, Float.NaN); Arrays.fill(flow, Float.NaN); Arrays.fill(water, Float.NaN);
            Arrays.fill(recvLevel, Float.NaN); Arrays.fill(bed, Float.NaN);
        }
    }

    Tile tile(long key) {
        int ti = Math.floorDiv(ni(key), TILE), tj = Math.floorDiv(nj(key), TILE);
        long tk = hashed(node(ti, tj));
        Tile t = tiles.get(tk);
        if (t == null) t = tiles.computeIfAbsent(tk, k -> sample(ti, tj));
        return t;
    }
    /** Map key of a packed (i, j): mixed bijectively. Long.hashCode of a packed pair is i ^ j, so the tiles of a
     * region collide in a few bins (tree bins on the hottest lookup of the model). */
    static long hashed(long key) { key *= 0x9E3779B97F4A7C15L; return key ^ (key >>> 29); }
    static int idx(long key) { return Math.floorMod(ni(key), TILE) + TILE * Math.floorMod(nj(key), TILE); }

    private Tile sample(int ti, int tj) {
        Tile tile = new Tile();
        for (int b = 0; b < TILE; b++) for (int a = 0; a < TILE; a++) {
            int i = ti * TILE + a, j = tj * TILE + b, k = a + TILE * b;
            int x = i * SPACING + SPACING / 2 + jitter(i, j, 0x51ED), z = j * SPACING + SPACING / 2 + jitter(i, j, 0x7A3B);
            tile.x[k] = x; tile.z[k] = z;
            // Node elevation: a 5-point average (radius 12) - sub-lattice relief does not create false pits.
            // Sampled directly (5 field evaluations, not 20 grid corners): a lattice-level estimate needs no exact
            // block-scale interpolation (block-scale checks use BaseTerrain).
            double h = (inputs.height(x, z) + inputs.height(x + 12, z) + inputs.height(x - 12, z) + inputs.height(x, z + 12) + inputs.height(x, z - 12)) / 5;
            tile.h[k] = (float) h;
            tile.c[k] = (float) inputs.continents(x, z);
            tile.t[k] = (float) inputs.temperature(x, z);
            tile.m[k] = (float) inputs.moisture(x, z);
            tile.e[k] = (float) inputs.erosion(x, z);
            tile.setting[k] = (byte) HydroClimate.setting(tile.t[k], tile.m[k], tile.e[k], h).ordinal();
            HydroClimate.Params p = HydroClimate.params(tile.t[k], tile.m[k], tile.e[k], h);
            tile.runoff[k] = (float) p.runoff(); tile.chanArea[k] = (float) p.channelArea(); tile.widthK[k] = (float) p.widthK();
            tile.depthK[k] = (float) p.depthK(); tile.wetK[k] = (float) p.wetK();
        }
        return tile;
    }
    private int jitter(int i, int j, int salt) {
        long v = hash(seed ^ salt, i, j);
        return (int) Math.round(((v >>> 11) * 0x1.0p-53 - 0.5) * 2 * JITTER);
    }
    static long hash(long seed, int x, int z) {
        long v = seed + x * 0x9E3779B97F4A7C15L + z * 0xD1B54A32D192ED03L;
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L; v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    public double h(long key) { return tile(key).h[idx(key)]; }
    public int x(long key) { return tile(key).x[idx(key)]; }
    public int z(long key) { return tile(key).z[idx(key)]; }
    public HydroClimate.Setting setting(long key) { return HydroClimate.Setting.values()[tile(key).setting[idx(key)]]; }
    /** Continuous channel width and depth factors of a node. */
    double widthK(long key) { return tile(key).widthK[idx(key)]; }
    double depthK(long key) { return tile(key).depthK[idx(key)]; }
    /** Full climate parameters of a node (basin rules: aridity, lake depths). */
    HydroClimate.Params paramsAt(long key) { Tile t = tile(key); int k = idx(key); return HydroClimate.params(t.t[k], t.m[k], t.e[k], t.h[k]); }
    double moisture(long key) { return tile(key).m[idx(key)]; }
    double dist(long a, long b) { return Math.hypot(x(a) - x(b), z(a) - z(b)); }
    boolean ocean(long key) { Tile t = tile(key); int k = idx(key); return t.h[k] < SEA_LEVEL - 0.5 && t.c[k] < OCEAN_CONTINENTS; }

    /** Lattice node whose cell contains a block position (cells are centred on the unjittered points). */
    public static long nodeAt(int x, int z) { return node(Math.floorDiv(x, SPACING), Math.floorDiv(z, SPACING)); }

    // ---------------------------------------------------------------- routing

    byte receiver(long key) {
        Tile t = tile(key); int k = idx(key);
        byte r = t.recv[k];
        if (r != UNKNOWN) return r;
        if (ocean(key)) r = OCEAN;
        else {
            double hk = t.h[k], best = 0; int bd = -1, flat = -1; long flatKey = key;
            for (int d = 0; d < 8; d++) {
                long m = nb(key, d);
                double dh = hk - h(m);
                if (dh == 0 && m < flatKey) { flatKey = m; flat = d; }
                if (dh <= 0) continue;
                double s = dh / dist(key, m);
                if (s > best) { best = s; bd = d; }
            }
            // Exactly level ground drains toward the smaller key: one pit per flat, never two touching pits.
            r = bd >= 0 ? (byte) bd : flat >= 0 ? (byte) flat : PIT;
        }
        t.recv[k] = r;
        return r;
    }

    /** Pit reached by steepest descent, or OCEAN_ID. */
    long terminal(long key) {
        ArrayList<Long> path = null;
        long cur = key, result;
        while (true) {
            Tile t = tile(cur); int k = idx(cur);
            long known = t.term[k];
            if (known != NONE) { result = known; break; }
            byte r = receiver(cur);
            if (r == OCEAN) { result = OCEAN_ID; t.term[k] = OCEAN_ID; break; }
            if (r == PIT) { result = cur; t.term[k] = cur; break; }
            if (path == null) path = new ArrayList<>();
            path.add(cur);
            cur = nb(cur, r);
            if (path.size() > 100_000) {
                // A flow cycle (code audit 24.11 K-4): never stop the server for it - the water goes to the sea.
                dev.freshgenerator.core.Diagnostics.broken("hydro-descent", "FreshGenerator drainage: descent from node " + ni(key) + "," + nj(key) + " does not terminate");
                result = OCEAN_ID; break;
            }
        }
        if (path != null) for (long p : path) tile(p).term[idx(p)] = result;
        return result;
    }

    // ---------------------------------------------------------------- depressions

    /** Result of a Priority-Flood from a seed region: visited region (all nodes up to the spill level), the
     * rim (last visited node, at the spill level) and the node beyond the rim (lower), OCEAN_ID or NONE. */
    static final class Flood {
        final long[] region, pits, path;
        final double level;
        final long rim, beyond;
        Flood(long[] region, long[] pits, double level, long rim, long beyond, long[] path) {
            this.region = region; this.pits = pits; this.level = level; this.rim = rim; this.beyond = beyond; this.path = path;
        }
        boolean contains(long k) { return Arrays.binarySearch(region, k) >= 0; }
        boolean hasPit(long k) { return Arrays.binarySearch(pits, k) >= 0; }
    }
    private record Entry(double h, long key, long parent) implements Comparable<Entry> {
        public int compareTo(Entry o) { int c = Double.compare(h, o.h); return c != 0 ? c : Long.compare(key, o.key); }
    }

    private Flood flood(long[] seeds, long[] pits, double startLevel, boolean recordPath) {
        HashSet<Long> visited = new HashSet<>();
        HashMap<Long, Long> parent = recordPath ? new HashMap<>() : null;
        PriorityQueue<Entry> heap = new PriorityQueue<>();
        for (long s : seeds) visited.add(s);
        for (long s : seeds) for (int d = 0; d < 8; d++) {
            long m = nb(s, d);
            if (!visited.contains(m)) heap.add(new Entry(h(m), m, s));
        }
        double level = startLevel;
        long rim = seeds[0], beyond = NONE;
        for (long s : seeds) if (h(s) >= h(rim)) rim = s;
        while (!heap.isEmpty()) {
            Entry e = heap.poll();
            if (visited.contains(e.key)) continue;
            if (ocean(e.key) || e.h < level) { beyond = ocean(e.key) ? OCEAN_ID : e.key; rim = e.parent; break; }
            visited.add(e.key);
            if (parent != null) parent.put(e.key, e.parent);
            if (e.h > level) level = e.h;
            rim = e.key;
            if (visited.size() > MAX_FLOOD) { beyond = NONE; break; }
            for (int d = 0; d < 8; d++) {
                long m = nb(e.key, d);
                if (!visited.contains(m)) heap.add(new Entry(h(m), m, e.key));
            }
        }
        long[] region = visited.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] path = null;
        if (parent != null && beyond != NONE) {
            ArrayList<Long> chain = new ArrayList<>();
            for (long c = rim; ; ) { chain.add(c); Long p = parent.get(c); if (p == null) break; c = p; }
            Collections.reverse(chain);
            path = chain.stream().mapToLong(Long::longValue).toArray();
        }
        return new Flood(region, pits.clone(), level, rim, beyond, path);
    }
    private Flood rawFlood(long pit) {
        Flood f = raw.get(pit);
        if (f != null) return f;
        f = flood(new long[]{pit}, new long[]{pit}, h(pit), true);
        Flood prior = raw.putIfAbsent(pit, f);
        return prior != null ? prior : f;
    }

    /** Depression around a pit after fill-spill-merge, with its kind and (lazily) its state and water. */
    public static final class Group {
        public static final int SEA = 0, BASIN = 1, BREACH = 2;
        public static final int UNDECIDED = 0, LAKE = 1, POND = 2, DRY = 3;
        final Flood flood;
        final long[] interior, pits;
        final int kind;
        final long deepest;
        /** Breach routing: deepest pit to rim, and (merged depressions) next hops toward the deepest pit. */
        long[] path;
        HashMap<Long, Long> toward;
        /** Highest level at which the basin holds water on the block scale (ignoring channels), or NO_WATER. */
        int preLevel = NO_WATER;
        volatile int state = UNDECIDED;
        volatile double area = Double.NaN, flow = Double.NaN;
        volatile int water = NO_WATER - 1;
        volatile HydroLakes.Mask mask;
        /** Outlet channel from the shore to the outlet river (lazy; NO_OUTLET when none is needed). */
        volatile HydroOutlet outlet;
        Group(Flood flood, long[] interior, long[] pits, int kind, long deepest) {
            this.flood = flood; this.interior = interior; this.pits = pits; this.kind = kind; this.deepest = deepest;
        }
        boolean isInterior(long k) { return Arrays.binarySearch(interior, k) >= 0; }
        int pathIndex(long k) {
            if (path == null) return -1;
            for (int i = 0; i < path.length; i++) if (path[i] == k) return i;
            return -1;
        }
        /** Ice of the whole lake (0 undecided, ICE_OPEN, ICE_FROZEN), decided once by the game side from the climate
         * of its surface: lake ice is a property of the water body, not of the biome of each column (audit 23.11 B-2:
         * a lake frozen along a straight 400-block biome border). */
        public volatile int ice;
        public static final int ICE_OPEN = 1, ICE_FROZEN = 2;
        /** Lake surface level (top water block), or NO_WATER without a block-scale lake. */
        public int lakeLevel() { HydroLakes.Mask m = mask; return m == null ? NO_WATER : m.level; }
        /** Up to max lake columns spread over the whole lake (packed x << 32 | z, mask order: deterministic). */
        public long[] lakeSamples(int max) { HydroLakes.Mask m = mask; return m == null ? new long[0] : m.samples(max); }
        public int kind() { return kind; }
        public int state() { return state; }
        public double level() { return flood.level; }
        public long rim() { return flood.rim; }
    }

    Group group(long pit) {
        Group g = groups.get(pit);
        if (g != null) return g;
        ArrayList<Flood> chain = new ArrayList<>();
        chain.add(rawFlood(pit));
        Group stopAt = null;
        while (chain.size() < 4096) {
            Flood f = chain.get(chain.size() - 1);
            if (f.beyond == OCEAN_ID || f.beyond == NONE) break;
            long q = terminal(f.beyond);
            if (q == OCEAN_ID) break;
            int at = -1;
            // Spilling back into any depression of the chain (its pit or a hollow nested in it) is a mutual spill.
            for (int i = 0; i < chain.size(); i++) if (chain.get(i).hasPit(q) || chain.get(i).contains(q)) { at = i; break; }
            if (at >= 0) {
                // Mutual spill (shared saddle): fill the union until the next exit.
                TreeSet<Long> region = new TreeSet<>(), pits = new TreeSet<>();
                double level = Double.NEGATIVE_INFINITY;
                for (int i = at; i < chain.size(); i++) {
                    Flood m = chain.get(i);
                    for (long r : m.region) region.add(r);
                    for (long p : m.pits) pits.add(p);
                    level = Math.max(level, m.level);
                }
                Flood merged = flood(region.stream().mapToLong(Long::longValue).toArray(),
                        pits.stream().mapToLong(Long::longValue).toArray(), level, false);
                while (chain.size() > at) chain.remove(chain.size() - 1);
                chain.add(merged);
                continue;
            }
            Group known = groups.get(q);
            if (known != null) { stopAt = known; break; }
            chain.add(rawFlood(q));
        }
        // Fill-spill nesting: a depression whose pit lies inside the next depression's flood is submerged by it
        // and belongs to it (its own routing would run through the outer one and back).
        for (int i = 0; i < chain.size(); i++) {
            Flood f = chain.get(i);
            Flood next = i + 1 < chain.size() ? chain.get(i + 1) : stopAt != null ? stopAt.flood : null;
            boolean nested = false;
            if (next != null) for (long p : f.pits) if (next.contains(p)) { nested = true; break; }
            if (nested) continue; // its pits are registered with the outer depression
            long[] pits = pitsIn(f);
            PitSet id = new PitSet(pits);
            Group made = byPits.get(id);
            if (made == null) { made = makeGroup(f, pits); Group prior = byPits.putIfAbsent(id, made); if (prior != null) made = prior; }
            for (long p : made.pits) groups.putIfAbsent(p, made);
        }
        Group result = groups.get(pit);
        if (result == null && stopAt != null) { groups.putIfAbsent(pit, stopAt); result = groups.get(pit); }
        if (result == null) {
            // Defensive: never leave a pit without a depression (its own flood).
            Flood f = rawFlood(pit);
            long[] pits = pitsIn(f);
            PitSet id = new PitSet(pits);
            Group made = byPits.computeIfAbsent(id, k -> makeGroup(f, pits));
            groups.putIfAbsent(pit, made);
            result = groups.get(pit);
        }
        return result;
    }
    /** One group object per depression, identified by its set of pits. */
    private record PitSet(long[] pits) {
        @Override public boolean equals(Object o) { return o instanceof PitSet p && Arrays.equals(pits, p.pits); }
        @Override public int hashCode() { return Arrays.hashCode(pits); }
    }
    private final ConcurrentHashMap<PitSet, Group> byPits = new ConcurrentHashMap<>();
    /** Every pit inside a flood region (nested hollows included). */
    private long[] pitsIn(Flood f) {
        TreeSet<Long> pits = new TreeSet<>();
        for (long p : f.pits) pits.add(p);
        for (long k : f.region) if (receiver(k) == PIT) pits.add(k);
        return pits.stream().mapToLong(Long::longValue).toArray();
    }
    private Group makeGroup(Flood f, long[] pits) {
        long deepest = pits[0];
        for (long p : pits) if (h(p) < h(deepest) || (h(p) == h(deepest) && p < deepest)) deepest = p;
        long[] interior = Arrays.stream(f.region).filter(k -> h(k) < f.level).toArray();
        HydroClimate.Params cp = paramsAt(deepest);
        boolean arid = cp.arid() >= 0.5;
        int kind, pre = NO_WATER;
        if (f.beyond == OCEAN_ID && f.level <= SEA_LEVEL) kind = Group.SEA;
        // Search budget exhausted: the outlet is unknown. The basin stays without a water body (DRY, see decide)
        // instead of a lake whose level and outlet were never found.
        else if (f.beyond == NONE) kind = Group.BASIN;
        // Flash floods pass shallow desert hollows; only deep closed basins end in playas - and only when the hollow
        // is closed on the block scale too (otherwise the floods pass through it).
        else if (arid) {
            if (f.level - h(deepest) < 4) kind = Group.BREACH;
            else {
                HydroLakes.Mask pm = HydroLakes.preCheck(this, f, interior, deepest, (int) Math.ceil(f.level) - 1);
                pre = pm == null ? NO_WATER : pm.level;
                kind = pre != NO_WATER && pre >= Math.floor(h(deepest)) + 1 ? Group.BASIN : Group.BREACH;
            }
        }
        else if (f.level - h(deepest) < cp.lakeDepth()) kind = Group.BREACH;
        else {
            // Does the hollow hold water on the block scale? A lattice pit between sub-lattice outlets does not:
            // the water passes through as a river instead of ending in a lake without water.
            HydroLakes.Mask pm = HydroLakes.preCheck(this, f, interior, deepest, (int) Math.ceil(f.level) - 1);
            pre = pm == null ? NO_WATER : pm.level;
            // An open lake needs real depth on the block scale: a hollow whose watertight water would be a sheet a
            // block or less deep (flat ground, Y64-66) is passed by its river instead of drowned into a "lake" of
            // islands and lily pads. Lake districts (tundra, taiga, wetlands) accept shallower lakes (audit 23.11
            // water B-2/B-3).
            boolean deep = pm != null && pm.depthMax >= cp.lakeMax() && pm.depthMean >= cp.lakeMean() && pm.deepShare() >= cp.lakeShare();
            kind = deep && pre >= Math.floor(h(deepest)) + 1 ? Group.BASIN : Group.BREACH;
        }
        Group g = new Group(f, interior, pits, kind, deepest);
        g.preLevel = pre;
        if (kind == Group.BREACH) route(g);
        return g;
    }

    /** Breach routing: a Priority-Flood from the deepest pit over the depression gives every node a next hop toward
     * it; the river climbs from the pit along the reverse chain from the rim (and carves through). */
    private void route(Group g) {
        HashSet<Long> region = new HashSet<>();
        for (long k : g.flood.region) region.add(k);
        HashMap<Long, Long> parent = new HashMap<>();
        PriorityQueue<Entry> heap = new PriorityQueue<>();
        heap.add(new Entry(h(g.deepest), g.deepest, NONE));
        HashSet<Long> seen = new HashSet<>();
        while (!heap.isEmpty()) {
            Entry e = heap.poll();
            if (!seen.add(e.key)) continue;
            if (e.parent != NONE) parent.put(e.key, e.parent);
            for (int d = 0; d < 8; d++) {
                long m = nb(e.key, d);
                if (region.contains(m) && !seen.contains(m)) heap.add(new Entry(h(m), m, e.key));
            }
        }
        ArrayList<Long> chain = new ArrayList<>();
        for (long c = g.flood.rim; ; ) { chain.add(c); Long p = parent.get(c); if (p == null) break; c = p; }
        Collections.reverse(chain);
        g.path = chain.stream().mapToLong(Long::longValue).toArray();
        if (g.pits.length > 1) g.toward = parent;
    }

    // ---------------------------------------------------------------- flow graph

    /** Downstream target: a lattice node, OCEAN_ID, NONE, or BASIN_FLAG (into the basin of terminal(key)). */
    long down(long key) {
        Tile tile = tile(key); int k = idx(key);
        long known = tile.down[k];
        if (known != DOWN_UNKNOWN) return known;
        long d = computeDown(key);
        tile.down[k] = d;
        return d;
    }
    private long computeDown(long key) {
        byte r = receiver(key);
        if (r == OCEAN) return OCEAN_ID;
        long t = terminal(key);
        if (t != OCEAN_ID) {
            Group g = group(t);
            if (g == null) return r < 8 ? nb(key, r) : NONE;
            switch (g.kind) {
                case Group.SEA -> { if (g.flood.contains(key)) return OCEAN_ID; }
                case Group.BASIN -> {
                    if (g.isInterior(key)) return BASIN_FLAG;
                    if (key == g.flood.rim && r != PIT) return g.flood.beyond;
                }
                case Group.BREACH -> {
                    int i = g.pathIndex(key);
                    if (i >= 0) return i + 1 < g.path.length ? g.path[i + 1] : g.flood.beyond;
                    if (g.toward != null && g.flood.contains(key)) { Long next = g.toward.get(key); if (next != null) return next; }
                }
                default -> { }
            }
        }
        if (r == PIT) return NONE;
        return nb(key, r);
    }
    /** Basin a node drains into (for BASIN_FLAG), else null. */
    Group basinOf(long key) {
        long t = terminal(key);
        return t == OCEAN_ID ? null : group(t);
    }

    private final ConcurrentHashMap<Long, Group[]> rimDonors = new ConcurrentHashMap<>();
    private static final Group[] NO_GROUPS = new Group[0];

    /** Upstream neighbours draining into a lattice node: lattice donors, then lakes whose rim it is. */
    Object[] donors(long key) {
        Tile tile = tile(key); int k = idx(key);
        short bits = tile.donorBits[k];
        if (bits < 0) {
            bits = 0;
            for (int d = 0; d < 8; d++) if (down(nb(key, d)) == key) bits |= (short) (1 << d);
            Group[] lakes = rimLakes(key);
            if (lakes.length > 0) { rimDonors.put(key, lakes); bits |= 0x100; }
            tile.donorBits[k] = bits;
        }
        Group[] lakes = NO_GROUPS;
        if ((bits & 0x100) != 0) {
            lakes = rimDonors.get(key);
            if (lakes == null) lakes = rimLakes(key); // entry not yet visible to this thread
        }
        Object[] out = new Object[Integer.bitCount(bits & 0xFF) + lakes.length];
        int n = 0;
        for (int d = 0; d < 8; d++) if ((bits & (1 << d)) != 0) out[n++] = nb(key, d);
        for (Group g : lakes) out[n++] = g;
        return out;
    }
    /** Lakes whose rim is this node (deterministic order). */
    private Group[] rimLakes(long key) {
        ArrayList<Group> lakes = null;
        for (int d = -1; d < 8; d++) {
            long m = d < 0 ? key : nb(key, d);
            long t = terminal(m);
            if (t == OCEAN_ID) continue;
            Group g = group(t);
            if (g.kind == Group.BASIN && g.flood.rim == key) {
                if (lakes == null) lakes = new ArrayList<>(1);
                if (!lakes.contains(g)) lakes.add(g);
            }
        }
        return lakes == null ? NO_GROUPS : lakes.toArray(NO_GROUPS);
    }
    Object[] donors(Group g) {
        ArrayList<Object> list = new ArrayList<>();
        for (long u : g.interior) for (int d = 0; d < 8; d++) {
            long v = nb(u, d);
            if (!g.isInterior(v) && down(v) == u) list.add(v);
        }
        return list.toArray();
    }

    // ---------------------------------------------------------------- accumulation

    private static final class Frame {
        final long key; final Group g; Object[] donors; int next; double area, flow;
        Frame(long key, Group g) { this.key = key; this.g = g; }
    }
    private boolean computed(Object o) {
        if (o instanceof Group g) return !Double.isNaN(g.flow);
        long k = (Long) o; return !Float.isNaN(tile(k).flow[idx(k)]);
    }
    /** Catchment area (km²) of a node. */
    public double area(long key) {
        // A lazily computed value is read again until it is there: a concurrent trim can drop the tile between
        // the computation and the read (the new tile starts empty) - audit 22.11 FG3-01.
        for (int attempt = 0; ; attempt++) {
            accumulate(key);
            float v = tile(key).area[idx(key)];
            if (!Float.isNaN(v)) return v;
            if (attempt >= RETRIES) { retriesExhausted(key); return v; }
        }
    }
    static final int RETRIES = 16;
    /** A lazy value still missing after RETRIES reads (code audit 24.11 K-10: it used to come back silently as NaN or
     * "no channel"). With whole-model renewal nothing drops a tile under a running computation any more. */
    private static void retriesExhausted(long key) {
        dev.freshgenerator.core.Diagnostics.broken("hydro-retries", "FreshGenerator drainage: a lazy value of node " + ni(key) + "," + nj(key) + " is missing after " + RETRIES + " reads");
    }
    /** Runoff-weighted catchment ("effective area", km²) of a node. */
    public double flow(long key) {
        for (int attempt = 0; ; attempt++) {
            accumulate(key);
            float v = tile(key).flow[idx(key)];
            if (!Float.isNaN(v)) return v;
            if (attempt >= RETRIES) { retriesExhausted(key); return v; }
        }
    }

    private void accumulate(long start) {
        if (computed(start)) return;
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        HashSet<Object> onStack = new HashSet<>();
        stack.push(new Frame(start, null)); onStack.add(start);
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            if (f.donors == null) {
                if (f.g != null) {
                    f.donors = donors(f.g);
                    for (long u : f.g.interior) { f.area += CELL_KM2; f.flow += CELL_KM2 * runoff(u); }
                } else {
                    f.donors = donors(f.key);
                    f.area = CELL_KM2; f.flow = CELL_KM2 * runoff(f.key);
                }
            }
            boolean pushed = false;
            while (f.next < f.donors.length) {
                Object d = f.donors[f.next];
                if (computed(d)) {
                    if (d instanceof Group g) {
                        if (g.state == Group.LAKE) { f.area += g.area; f.flow += g.flow; }
                    } else {
                        long k = (Long) d; Tile t = tile(k); f.area += t.area[idx(k)];
                        // Transmission losses of dry land (the donor's own aridity over the link to this node).
                        f.flow += t.flow[idx(k)] * (f.g != null ? 1 : HydroClimate.transmission(t.runoff[idx(k)], dist(k, f.key)));
                    }
                    f.next++;
                    continue;
                }
                if (onStack.contains(d)) { f.next++; continue; }
                stack.push(d instanceof Group g ? new Frame(0, g) : new Frame((Long) d, null));
                onStack.add(d);
                pushed = true;
                break;
            }
            if (pushed) continue;
            stack.pop();
            if (f.g != null) {
                onStack.remove(f.g);
                decide(f.g, f.area, f.flow);
            } else {
                onStack.remove(f.key);
                Tile t = tile(f.key); int k = idx(f.key);
                t.area[k] = (float) f.area; t.flow[k] = (float) f.flow;
            }
        }
    }
    private void decide(Group g, double area, double flow) {
        HydroClimate.Params cp = paramsAt(g.deepest);
        // Continuous lake rules: the inflow a basin needs to hold a through-flow lake grows with aridity (3.0 in
        // deserts, 1.5 in savannas, 0 in humid land); below it, arid basins dry up and others keep a pond.
        double need = 3.0 * cp.arid() + 1.5 * cp.semiArid();
        int state;
        // A water body needs a level proven watertight on the block scale before any river reads its state:
        // without one the basin is dry (no lake without water that still feeds a river).
        if (g.flood.beyond == NONE || g.preLevel == NO_WATER) state = Group.DRY;
        else if (flow >= need) state = Group.LAKE;
        else state = cp.arid() >= 0.5 ? Group.DRY : Group.POND;
        g.area = area;
        g.state = state;
        g.flow = flow; // published last: computed() reads flow
    }
    double runoff(long key) {
        Tile t = tile(key); int k = idx(key);
        return t.runoff[k];
    }

    /** Basin accumulation and state (forces the computation through any rim node's donors). */
    Group decided(Group g) {
        if (Double.isNaN(g.flow)) {
            accumulate(g.flood.rim);
            if (Double.isNaN(g.flow)) accumulateGroup(g);
        }
        return g;
    }
    private void accumulateGroup(Group g) {
        // The rim may drain elsewhere; compute the basin directly through its own donors.
        double area = 0, flow = 0;
        for (long u : g.interior) { area += CELL_KM2; flow += CELL_KM2 * runoff(u); }
        for (Object d : donors(g)) {
            if (d instanceof Group dg) { decided(dg); if (dg.state == Group.LAKE) { area += dg.area; flow += dg.flow; } }
            else { long k = (Long) d; area += area(k); flow += flow(k); }
        }
        if (Double.isNaN(g.flow)) decide(g, area, flow);
    }

    // ---------------------------------------------------------------- channels

    /** Slope from a node to its downstream lattice node (0.02 into basins or the sea). */
    double slope(long key) {
        long d = down(key);
        if (d == OCEAN_ID || d == NONE || d == BASIN_FLAG) return 0.02;
        return Math.max(0, h(key) - h(d)) / Math.max(1, dist(key, d));
    }

    /** 0 none, 1 dry channel, 2 wet channel. */
    public int channel(long key) {
        for (int attempt = 0; ; attempt++) {
            int c = channelOnce(key);
            if (c != UNKNOWN) return Math.max(0, c);
            if (attempt >= RETRIES) { retriesExhausted(key); return 0; }
        }
    }
    private int channelOnce(long key) {
        Tile t = tile(key); int k = idx(key);
        byte c = t.chan[k];
        if (c != UNKNOWN) return c;
        ArrayDeque<Long> stack = new ArrayDeque<>();
        HashSet<Long> onStack = new HashSet<>();
        stack.push(key); onStack.add(key);
        while (!stack.isEmpty()) {
            long n = stack.peek();
            Tile tn = tile(n); int kn = idx(n);
            if (tn.chan[kn] != UNKNOWN) { onStack.remove(stack.pop()); continue; }
            double a = area(n);
            if (a < HydroClimate.MIN_CHANNEL_AREA) { tn.chan[kn] = 0; onStack.remove(stack.pop()); continue; }
            Object[] ds = donors(n);
            boolean pending = false;
            for (Object d : ds) if (d instanceof Long dk && tile(dk).chan[idx(dk)] == UNKNOWN && !onStack.contains(dk)
                    && area(dk) >= HydroClimate.MIN_CHANNEL_AREA) {
                stack.push(dk); onStack.add(dk); pending = true; break;
            }
            if (pending) continue;
            onStack.remove(stack.pop());
            boolean channel = a >= HydroClimate.channelArea(tn.chanArea[kn], slope(n), h(n)), wetDonor = false;
            for (Object d : ds) {
                if (d instanceof Long dk) {
                    byte dc = tile(dk).chan[idx(dk)];
                    if (dc > 0) channel = true;
                    if (dc == 2) wetDonor = true;
                } else if (d instanceof Group g) {
                    // State only: the lake's block-level fill needs channel geometry (no recursion).
                    decided(g);
                    if (g.state == Group.LAKE) { channel = true; wetDonor = true; }
                }
            }
            byte r = 0;
            // Permanent water: from WET_FLOW scaled by the climate (savannas need far more catchment than forests,
            // mountains far less - audit 23.11 water B-6/B-7).
            if (channel) r = (byte) (wetDonor || flow(n) >= HydroClimate.wetFlow(tn.wetK[kn], slope(n), h(n)) ? 2 : 1);
            tn.chan[kn] = r;
        }
        return tile(key).chan[idx(key)];
    }

    /** Main (largest) channel donor of a node, or NONE. */
    long mainDonor(long key) {
        long best = NONE; double ba = -1;
        for (Object d : donors(key)) if (d instanceof Long dk && channel(dk) > 0) {
            double a = area(dk);
            if (a > ba || (a == ba && dk < best)) { ba = a; best = dk; }
        }
        return best;
    }

    /** Channel path point of a node: the node position pulled toward its main donor and downstream node
     * (1-2-1 smoothing), shared by every reach that touches the node. */
    double[] pathPoint(long key) {
        double x = x(key), z = z(key);
        if (channel(key) == 0) return new double[]{x, z};
        long md = mainDonor(key), dn = down(key);
        if (dn == OCEAN_ID || dn == BASIN_FLAG) { byte r = receiver(key); dn = r >= 0 && r < 8 ? nb(key, r) : NONE; }
        if (md == NONE || dn == NONE) return new double[]{x, z};
        return new double[]{(x(md) + 2 * x + x(dn)) / 4, (z(md) + 2 * z + z(dn)) / 4};
    }

    /** Wet channel donors of a node other than the given one (siblings at a confluence). */
    long[] wetSiblings(long key, long except) {
        return Arrays.stream(donors(key)).filter(d -> d instanceof Long dk && dk != except && channel(dk) == 2)
                .mapToLong(d -> (Long) d).toArray();
    }

    // ---------------------------------------------------------------- water levels

    /** Level of the first water body downstream (sea, lake, pond), or -infinity. */
    double receivingLevel(long key) {
        Tile t = tile(key); int k = idx(key);
        if (!Float.isNaN(t.recvLevel[k])) return t.recvLevel[k];
        ArrayList<Long> path = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        long cur = key; double result;
        while (true) {
            Tile tc = tile(cur); int kc = idx(cur);
            if (!Float.isNaN(tc.recvLevel[kc])) { result = tc.recvLevel[kc]; break; }
            if (!seen.add(cur)) { result = Double.NEGATIVE_INFINITY; break; } // defensive: cyclic routing
            path.add(cur);
            long d = down(cur);
            if (d == OCEAN_ID) { result = SEA_WATER; break; }
            if (d == NONE) { result = Double.NEGATIVE_INFINITY; break; }
            if (d == BASIN_FLAG) {
                Group g = basinOf(cur);
                int w = g == null ? NO_WATER : water(g);
                result = w == NO_WATER ? Double.NEGATIVE_INFINITY : w;
                break;
            }
            cur = d;
            if (path.size() > 200_000) {
                dev.freshgenerator.core.Diagnostics.broken("hydro-flowpath", "FreshGenerator drainage: flow path from node " + ni(key) + "," + nj(key) + " does not terminate");
                result = Double.NEGATIVE_INFINITY; break;
            }
        }
        for (long p : path) tile(p).recvLevel[idx(p)] = (float) result;
        return result;
    }

    /** Integer top water block of a lake or pond, or NO_WATER (dry basins, sea groups, breaches). */
    public int water(Group g) {
        int w = g.water;
        if (w != NO_WATER - 1) return w;
        decided(g);
        int result = NO_WATER;
        HydroLakes.Mask mask = null;
        if (g.kind == Group.BASIN && (g.state == Group.LAKE || g.state == Group.POND)) {
            double bottom = h(g.deepest);
            int target = g.state == Group.LAKE ? (int) Math.ceil(g.flood.level) - 1
                    : (int) Math.floor(bottom + 0.55 * (g.flood.level - bottom));
            if (g.preLevel != NO_WATER) target = Math.min(target, g.preLevel);
            mask = HydroLakes.contain(this, g, target);
            if (mask != null) result = mask.level();
        }
        g.mask = mask;
        g.water = result;
        return result;
    }

    /** Outlet channel of a lake whose block-scale shore lies away from the start of its outlet river, or null. */
    public HydroOutlet outlet(Group g) {
        HydroOutlet o = g.outlet;
        if (o == null) {
            o = water(g) == NO_WATER || g.state != Group.LAKE ? null : HydroOutlet.build(this, g);
            g.outlet = o = o == null ? HydroOutlet.NONE : o;
        }
        return o == HydroOutlet.NONE ? null : o;
    }

    /** Outlet channel of a lake whose rim is this node (the lake that overflows into the river starting here). */
    HydroOutlet outletInto(long key) {
        for (Object d : donors(key)) if (d instanceof Group g && decided(g).state == Group.LAKE) { HydroOutlet o = outlet(g); if (o != null) return o; }
        return null;
    }

    /** Float water level of a wet channel node: not above the ground minus a block, not above any upstream
     * level or bank ceiling, not below the receiving water body. */
    public double water(long key) {
        for (int attempt = 0; ; attempt++) {
            double v = waterOnce(key);
            if (!Double.isNaN(v)) return v;
            if (attempt >= RETRIES) { retriesExhausted(key); return v; }
        }
    }
    private double waterOnce(long key) {
        Tile t = tile(key); int k = idx(key);
        if (!Float.isNaN(t.water[k])) return t.water[k];
        ArrayDeque<Long> stack = new ArrayDeque<>();
        HashSet<Long> onStack = new HashSet<>();
        stack.push(key); onStack.add(key);
        while (!stack.isEmpty()) {
            long n = stack.peek();
            Tile tn = tile(n); int kn = idx(n);
            if (!Float.isNaN(tn.water[kn])) { onStack.remove(stack.pop()); continue; }
            Object[] ds = donors(n);
            boolean pending = false;
            for (Object d : ds) if (d instanceof Long dk && channel(dk) == 2 && Float.isNaN(tile(dk).water[idx(dk)]) && !onStack.contains(dk)) {
                stack.push(dk); onStack.add(dk); pending = true; break;
            }
            if (pending) continue;
            onStack.remove(stack.pop());
            double w = h(n) - 1;
            for (Object d : ds) {
                if (d instanceof Long dk) {
                    if (channel(dk) != 2 || Float.isNaN(tile(dk).water[idx(dk)])) continue;
                    HydroReach rr = reach(dk);
                    w = Math.min(w, Math.min(tile(dk).water[idx(dk)], rr == null ? Double.POSITIVE_INFINITY : rr.bankCeiling()));
                } else if (d instanceof Group g) {
                    int lw = water(g);
                    if (g.state == Group.LAKE && lw != NO_WATER) w = Math.min(w, lw);
                }
            }
            // The outgoing reach starts at this level: its own first bank is a ceiling too, so the reaches above
            // end exactly where it begins (no unplanned step at the node).
            HydroReach own = reach(n);
            if (own != null && own.wet) w = Math.min(w, own.startCeiling());
            w = Math.max(w, receivingLevel(n));
            tn.water[kn] = (float) w;
        }
        return tile(key).water[idx(key)];
    }

    /** Bed of a dry channel node (monotone downstream). */
    public double dryBed(long key) {
        for (int attempt = 0; ; attempt++) {
            double v = dryBedOnce(key);
            if (!Double.isNaN(v)) return v;
            if (attempt >= RETRIES) { retriesExhausted(key); return v; }
        }
    }
    private double dryBedOnce(long key) {
        Tile t = tile(key); int k = idx(key);
        if (!Float.isNaN(t.bed[k])) return t.bed[k];
        ArrayDeque<Long> stack = new ArrayDeque<>();
        HashSet<Long> onStack = new HashSet<>();
        stack.push(key); onStack.add(key);
        while (!stack.isEmpty()) {
            long n = stack.peek();
            Tile tn = tile(n); int kn = idx(n);
            if (!Float.isNaN(tn.bed[kn])) { onStack.remove(stack.pop()); continue; }
            Object[] ds = donors(n);
            boolean pending = false;
            for (Object d : ds) if (d instanceof Long dk && channel(dk) == 1 && Float.isNaN(tile(dk).bed[idx(dk)]) && !onStack.contains(dk)) {
                stack.push(dk); onStack.add(dk); pending = true; break;
            }
            if (pending) continue;
            onStack.remove(stack.pop());
            double b = h(n) - HydroClimate.dryDepth(area(n));
            for (Object d : ds) if (d instanceof Long dk && channel(dk) == 1 && !Float.isNaN(tile(dk).bed[idx(dk)])) b = Math.min(b, tile(dk).bed[idx(dk)]);
            tn.bed[kn] = (float) b;
        }
        return tile(key).bed[idx(key)];
    }

    // ---------------------------------------------------------------- geometry

    /** Reach from a channel node to its downstream node (null when it has none). */
    public HydroReach reach(long key) {
        HydroReach r = reaches.get(key);
        if (r != null) return r == HydroReach.ABSENT ? null : r;
        HydroReach made = HydroReach.build(this, key);
        HydroReach prior = reaches.putIfAbsent(key, made == null ? HydroReach.ABSENT : made);
        HydroReach result = prior != null ? prior : made == null ? HydroReach.ABSENT : made;
        return result == HydroReach.ABSENT ? null : result;
    }

    /** Groups (lakes/ponds with water) whose region touches the lattice nodes in a box. */
    List<Group> lakesNear(int i0, int j0, int i1, int j1) {
        LinkedHashSet<Group> out = new LinkedHashSet<>();
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            long n = node(i, j);
            if (ocean(n)) continue;
            long t = terminal(n);
            if (t == OCEAN_ID) continue;
            Group g = group(t);
            if (g.kind != Group.BASIN || !g.flood.contains(n)) continue;
            out.add(g);
        }
        ArrayList<Group> withWater = new ArrayList<>();
        for (Group g : out) if (water(g) != NO_WATER) withWater.add(g);
        return withWater;
    }

    /** Debug description of the lattice node containing a block position. */
    public String describe(int bx, int bz) {
        long k = nodeAt(bx, bz);
        StringBuilder sb = new StringBuilder();
        sb.append("node ").append(ni(k)).append(',').append(nj(k)).append(" at ").append(x(k)).append(',').append(z(k))
          .append(" h=").append(String.format("%.2f", h(k))).append(" setting=").append(setting(k)).append(" ocean=").append(ocean(k));
        byte r = receiver(k);
        sb.append(" recv=").append(r);
        long t = terminal(k);
        if (t == OCEAN_ID) sb.append(" terminal=OCEAN");
        else {
            Group g = group(t);
            decided(g);
            sb.append(" terminal=").append(ni(t)).append(',').append(nj(t)).append(" group kind=").append(g.kind).append(" state=").append(g.state)
              .append(" level=").append(String.format("%.2f", g.flood.level)).append(" region=").append(g.flood.region.length)
              .append(" interior=").append(g.interior.length).append(" pre=").append(g.preLevel).append(" inGroup=").append(g.flood.contains(k));
        }
        long d = down(k);
        sb.append(" down=").append(d == OCEAN_ID ? "OCEAN" : d == NONE ? "NONE" : d == BASIN_FLAG ? "BASIN" : (ni(d) + "," + nj(d)));
        sb.append(String.format(" area=%.3f flow=%.3f slope=%.4f channel=%d", area(k), flow(k), slope(k), channel(k)));
        if (channel(k) == 2) sb.append(String.format(" water=%.2f recv=%.2f", water(k), receivingLevel(k)));
        sb.append(" chain:");
        long c = k;
        for (int i = 0; i < 24; i++) {
            long dn = down(c);
            if (dn == OCEAN_ID || dn == NONE || dn == BASIN_FLAG) { sb.append(" -> ").append(dn == OCEAN_ID ? "OCEAN" : dn == NONE ? "NONE" : "BASIN"); break; }
            c = dn;
            sb.append(String.format(" -> %d,%d(h%.1f c%d a%.2f%s)", ni(c), nj(c), h(c), channel(c), area(c), reach(c) == null ? " noreach" : ""));
        }
        return sb.toString();
    }

    /** Audit: flow cycles among the lattice nodes of a box (each reported once, with a description). */
    public List<String> cycles(int i0, int j0, int i1, int j1, int limit) {
        ArrayList<String> out = new ArrayList<>();
        HashSet<Long> reported = new HashSet<>();
        for (int j = j0; j <= j1 && out.size() < limit; j++) for (int i = i0; i <= i1 && out.size() < limit; i++) {
            LinkedHashMap<Long, Integer> walk = new LinkedHashMap<>();
            long c = node(i, j);
            for (int step = 0; step < 20_000; step++) {
                if (walk.containsKey(c)) {
                    if (reported.add(c)) {
                        StringBuilder sb = new StringBuilder("CYCLE");
                        boolean in = false;
                        for (long n : walk.keySet()) {
                            if (n == c) in = true;
                            if (!in) continue;
                            reported.add(n);
                            long t = terminal(n);
                            sb.append(String.format(" [%d,%d h%.2f r%d t%s", ni(n), nj(n), h(n), receiver(n), t == OCEAN_ID ? "OCEAN" : ni(t) + "," + nj(t)));
                            if (t != OCEAN_ID) { Group g = group(t); sb.append(String.format(" g%d/%d rim%d,%d lvl%.2f pits%d reg%d", g.kind, g.state, ni(g.flood.rim), nj(g.flood.rim), g.flood.level, g.flood.pits.length, g.flood.region.length)); }
                            sb.append("]");
                        }
                        out.add(sb.toString());
                    }
                    break;
                }
                walk.put(c, step);
                long d = down(c);
                if (d == OCEAN_ID || d == NONE || d == BASIN_FLAG) break;
                c = d;
            }
        }
        return out;
    }

    /** Audit: consistency counters over the lattice nodes of a block box (junction levels, waterfall drops,
     * lakes without water, lake outlets away from the water, longest wet chain upstream). */
    public Map<String, Number> integrity(int x0, int z0, int x1, int z1) {
        TreeMap<String, Number> out = new TreeMap<>();
        int junctions = 0, step1 = 0, step3 = 0, maxStep = 0, falls = 0, flatFalls = 0, chain = 0, nan = 0, knickOver = 0;
        LinkedHashSet<Group> basins = new LinkedHashSet<>();
        for (int j = Math.floorDiv(z0, SPACING); j <= Math.floorDiv(z1, SPACING); j++)
            for (int i = Math.floorDiv(x0, SPACING); i <= Math.floorDiv(x1, SPACING); i++) {
                long n = node(i, j);
                if (!ocean(n)) { long t = terminal(n); if (t != OCEAN_ID) { Group g = group(t); if (g.kind == Group.BASIN) basins.add(g); } }
                if (channel(n) != 2) continue;
                HydroReach r = reach(n);
                if (r == null || !r.wet) continue;
                HydroReach.Profile p = r.profile(this);
                for (int b = 0; b < p.water.length; b++) if (Double.isNaN(p.bed[b])) nan++;
                if (r.end == HydroReach.INTO_CHANNEL && channel(r.to) == 2) {
                    HydroReach dn = reach(r.to);
                    if (dn != null && dn.wet) {
                        int d = p.water[p.water.length - 1] - dn.profile(this).water[0];
                        junctions++;
                        if (d != 0) step1++;
                        if (Math.abs(d) >= 3) step3++;
                        if (Math.abs(d) > maxStep) {
                            out.put("junction_max_x", x(r.to)); out.put("junction_max_z", z(r.to));
                            out.put("junction_max_node_water", water(r.to)); out.put("junction_max_start_ceiling", dn.startCeiling());
                            out.put("junction_max_receiving", receivingLevel(r.to)); out.put("junction_max_down_end", dn.end);
                            out.put("junction_max_up_last", p.water[p.water.length - 1]); out.put("junction_max_down_first", dn.profile(this).water[0]);
                        }
                        maxStep = Math.max(maxStep, Math.abs(d));
                    }
                }
                for (int fi = 0; fi < p.fallArcs.length; fi++) {
                    double arc = p.fallArcs[fi];
                    falls++;
                    int a = Math.max(0, Math.min(p.water.length - 1, (int) Math.ceil(arc) - 1)), b = Math.min(p.water.length - 1, (int) Math.ceil(arc) + 1);
                    if (p.water[a] - p.water[b] < HydroReach.MIN_FALL) flatFalls++;
                    // A knickpoint keeps its capped height (bank ceilings below the lip may add a block or two).
                    if (fi < p.knick.length && p.knick[fi]
                            && p.fallTop[fi] - p.poolLevel[fi] > HydroReach.KNICK_BASE + HydroReach.KNICK_WIDTH * Math.max(r.w0, r.w1) + 2) knickOver++;
                }
                int len = 0;
                for (long c = n; len < 100_000; len++) { long md = mainDonor(c); if (md == NONE || channel(md) != 2) break; c = md; }
                chain = Math.max(chain, len);
            }
        int lakes = 0, ghosts = 0, ghostOutlets = 0, detached = 0, outlets = 0, budget = 0, backwater = 0;
        for (Group g : basins) {
            decided(g);
            if (g.flood.beyond == NONE) budget++;
            if (g.state != Group.LAKE && g.state != Group.POND) continue;
            int w = water(g);
            if (w == NO_WATER) {
                ghosts++;
                if (g.state == Group.LAKE && channel(g.flood.rim) == 2) ghostOutlets++;
                continue;
            }
            lakes++;
            if (g.state == Group.LAKE && channel(g.flood.rim) == 2) {
                HydroOutlet o = outlet(g);
                if (o != null) {
                    if (outlets < 4) {
                        out.put("outlet" + outlets + "_x", (int) ((o.ax + o.bx) / 2)); out.put("outlet" + outlets + "_z", (int) ((o.az + o.bz) / 2));
                        out.put("outlet" + outlets + "_length", (int) o.length);
                    }
                    outlets++;
                }
                HydroReach outReach = reach(g.flood.rim);
                // The real start of the outlet river (bends move it off the node), where the outlet channel joins it.
                double[] p = outReach != null ? new double[]{outReach.px[0], outReach.pz[0]} : pathPoint(g.flood.rim);
                boolean held = outReach != null && outReach.wet && outReach.core(this).water[0] > g.mask.level();
                if (o == null && held) backwater++;
                else if (o == null && nearestMask(g.mask, p[0], p[1], 24) > 24) detached++;
            }
        }
        out.put("lake_outlet_channels", outlets); out.put("budget_basins", budget); out.put("lake_outlets_backwater", backwater);
        out.put("junctions", junctions); out.put("junction_steps", step1); out.put("junction_steps_3plus", step3); out.put("junction_max_step", maxStep);
        out.put("falls", falls); out.put("falls_without_drop", flatFalls); out.put("longest_wet_chain", chain); out.put("nan_bed", nan);
        out.put("knick_falls_over_cap", knickOver);
        out.put("lakes", lakes); out.put("lakes_without_water", ghosts); out.put("dry_lakes_feeding_rivers", ghostOutlets);
        out.put("lake_outlets_detached", detached);
        return out;
    }
    private static double nearestMask(HydroLakes.Mask mask, double x, double z, int radius) {
        double best = Double.POSITIVE_INFINITY;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++)
            if (mask.contains(cx + dx, cz + dz)) best = Math.min(best, Math.hypot(dx, dz));
        return best;
    }

    public int tilesCached() { return tiles.size(); }
}
