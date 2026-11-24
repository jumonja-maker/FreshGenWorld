package dev.freshgenerator.core.hydro;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** Per-column result of the hydrology: final ground height (base terrain with carved valleys, channels and
 * plunge pools) and the water level there. Reaches and lakes are indexed per 128-block query tile. */
public final class HydroField {
    public static final int QUERY = 128;
    /** Column kinds. */
    public static final int NONE = 0, RIVER = 1, LAKE = 2, DRY = 3, POOL = 4;
    private static final int MARGIN = 8;
    /** The drainage model. Replaced by a fresh one (same inputs and seed) when it grows past MAX_MODEL_TILES: its
     * values are pure functions of the seed only as a whole - forgetting part of the derived state left a depression
     * half registered, and the next query described the same land as two basins with contradicting routes (a flow
     * cycle, five times the catchment, a stream moved over 800 blocks: code audit 24.11 F-1, seed 20261124). Query
     * tiles built from the old model stay valid (the same values). */
    private volatile HydroModel model;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    /** Query tiles being built: one thread builds a tile, the others wait for it (code audit 24.11 F-3: 42% of the
     * building time of a fresh area went into duplicates). */
    private final ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Tile>> building = new ConcurrentHashMap<>();
    /** Query tiles built from the current model. A renewal waits for MIN_BUILDS of them: the catchments one area needs
     * can exceed MAX_MODEL_TILES on their own (2499 tiles for one query on a synthetic endless slope), and renewing on
     * every build recomputed them each time. In a game the limit comes after ~50 000 query tiles. */
    private final java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
    static final int MIN_BUILDS = 1024;

    public HydroField(HydroModel model) { this.model = model; }
    public HydroModel model() { return model; }
    /** Whether the query tile of a column is built (a read that does not compute the model). */
    public boolean ready(int x, int z) { return tiles.containsKey(HydroModel.hashed(HydroModel.node(Math.floorDiv(x, QUERY), Math.floorDiv(z, QUERY)))); }

    static final class Tile {
        volatile long used;
        final HydroReach[] reaches;
        final HydroModel.Group[] lakes;
        final HydroOutlet[] outlets;
        Tile(HydroReach[] reaches, HydroModel.Group[] lakes, HydroOutlet[] outlets) { this.reaches = reaches; this.lakes = lakes; this.outlets = outlets; }
    }

    /** Mutable column result. */
    public static final class Column {
        public double base, terrain;
        public int water = HydroModel.NO_WATER, kind = NONE;
        /** Distance to the nearest channel centreline (blocks), or +inf. */
        public double channelDistance = Double.POSITIVE_INFINITY;
        /** Distance to the nearest wet channel edge or lake (blocks, 0 inside, capped), for dry-bank masks. */
        public double wetDistance = Double.POSITIVE_INFINITY;
        /** Channel width of the river owning the column (0 elsewhere). */
        public double width;
        /** Highest water within 8 blocks upstream of a wet channel column (its water elsewhere, NO_WATER when dry): above
         * the water in a riffle zone, where the flowing water of a step spreads one block above the lower water. */
        public int hold = HydroModel.NO_WATER;
        /** Top water block of the water that sets wetDistance (the nearest river, pool or lake), or NO_WATER: the forest
         * counts its low-bank terms from it (code audit 24.11 F1: eight rays missed the water between them and took the
         * sea level beside a stream at Y75). */
        public int nearWater = HydroModel.NO_WATER;
        /** 0 next to water, 1 from 28 blocks away (cave entrances keep clear of rivers and lakes). */
        public double dryBanks() { return HydroClimate.smooth(12, 28, wetDistance); }
        public boolean wet() { return water != HydroModel.NO_WATER && terrain <= water; }
        /** Top water block including the native sea fill (every column below Y63 holds sea water in game). */
        public int effectiveWater() {
            int sea = terrain < HydroModel.SEA_LEVEL ? HydroModel.SEA_WATER : HydroModel.NO_WATER;
            return wet() ? Math.max(water, sea) : sea;
        }
        public boolean wetIncludingSea() { return effectiveWater() != HydroModel.NO_WATER && terrain <= effectiveWater(); }
    }

    Tile tile(int x, int z) {
        int qx = Math.floorDiv(x, QUERY), qz = Math.floorDiv(z, QUERY);
        long key = HydroModel.hashed(HydroModel.node(qx, qz));
        Tile t = tiles.get(key);
        if (t == null) t = buildOnce(key, qx, qz);
        long now = clock;
        if (t.used != now) t.used = now;
        return t;
    }
    private Tile buildOnce(long key, int qx, int qz) {
        java.util.concurrent.CompletableFuture<Tile> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Tile> other = building.putIfAbsent(key, mine);
        if (other != null) {
            try { return other.join(); }
            catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error r) throw r;
                throw e;
            }
        }
        try {
            Tile t = tiles.get(key);
            if (t == null) {
                t = build(qx, qz);
                builds.incrementAndGet();
                if (tiles.size() > MAX_TILES) trimTiles();
                tiles.put(key, t);
                clock = ticks.incrementAndGet();
            }
            mine.complete(t);
            return t;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            building.remove(key, mine);
        }
    }
    private volatile long clock;
    private final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();

    /** Query tiles kept (128-block tiles: ~1070 km²) and model tiles kept (1 km² each) before the least recently
     * used half is forgotten (audit 23.11 R-1: no more trimming around the last query). */
    static final int MAX_TILES = 1 << 16, MAX_MODEL_TILES = 2048;
    private void trimTiles() {
        synchronized (tiles) {
            if (tiles.size() <= MAX_TILES) return;
            // The oldest half by count: with equal stamps a cut "older than the median" removed nothing (code audit
            // 24.11 HY-03). Whole query tiles are independent and rebuilt identically.
            var entries = new ArrayList<>(tiles.entrySet());
            entries.sort(java.util.Comparator.comparingLong(e -> e.getValue().used));
            for (int i = 0; i < entries.size() / 2; i++) tiles.remove(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    private Tile build(int qx, int qz) {
        // Bounded memory on long explorations (distant-view mods explore far): past ~2000 km² a fresh model.
        HydroModel model = this.model;
        if (model.tilesCached() > MAX_MODEL_TILES && builds.get() >= MIN_BUILDS) model = renew(model);
        int bx0 = qx * QUERY, bz0 = qz * QUERY, bx1 = bx0 + QUERY - 1, bz1 = bz0 + QUERY - 1;
        int i0 = Math.floorDiv(bx0, HydroModel.SPACING) - MARGIN, i1 = Math.floorDiv(bx1, HydroModel.SPACING) + MARGIN;
        int j0 = Math.floorDiv(bz0, HydroModel.SPACING) - MARGIN, j1 = Math.floorDiv(bz1, HydroModel.SPACING) + MARGIN;
        ArrayList<HydroReach> list = new ArrayList<>();
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            long n = HydroModel.node(i, j);
            if (model.channel(n) == 0) continue;
            HydroReach r = model.reach(n);
            if (r == null) continue;
            if (r.maxX < bx0 || r.minX > bx1 || r.maxZ < bz0 || r.minZ > bz1) continue;
            list.add(r);
        }
        ArrayList<HydroModel.Group> lakes = new ArrayList<>();
        ArrayList<HydroOutlet> outlets = new ArrayList<>();
        for (HydroModel.Group g : model.lakesNear(i0 + MARGIN / 2, j0 + MARGIN / 2, i1 - MARGIN / 2, j1 - MARGIN / 2)) {
            // The shore distance reaches DIST_CAP beyond the mask: a lake just outside the tile still guards its bank
            // inside it (audit 23.11: seed 8675309 X1569 Z-4225 saw no lake 19 blocks away - cave mouths allowed).
            int d = HydroLakes.DIST_CAP + 1;
            if (g.mask != null && g.mask.intersects(bx0 - d, bz0 - d, bx1 + d, bz1 + d)) lakes.add(g);
            HydroOutlet o = model.outlet(g);
            if (o != null && o.intersects(bx0, bz0, bx1, bz1)) outlets.add(o);
        }
        return new Tile(list.toArray(new HydroReach[0]), lakes.toArray(new HydroModel.Group[0]), outlets.toArray(new HydroOutlet[0]));
    }
    /** Replaces a full model by a fresh one (once, whichever thread sees it first). */
    HydroModel renew(HydroModel full) {
        synchronized (this) {
            if (model == full) { model = full.fresh(); builds.set(0); }
            return model;
        }
    }

    /** The lake whose block-scale water covers a column, or null. */
    public HydroModel.Group lakeAt(int x, int z) {
        for (HydroModel.Group g : tile(x, z).lakes) if (g.mask.contains(x, z)) return g;
        return null;
    }

    /** A column of a wide river (wet channel at least minWidth wide, over ground not below the sea) whose channel
     * centre lies within radius blocks of (x, z): {x, z, water}, or null. For the biome search, whose probes are
     * farther apart than a river is wide. */
    public double[] wideRiverNear(int x, int z, double radius, double minWidth) {
        double[] pr = new double[2], best = null;
        double bestD = Double.POSITIVE_INFINITY;
        Column c = new Column();
        // The nearest such river, not the first in the tile list (code audit 24.11 K-13); pools of a river count too.
        for (HydroReach r : tile(x, z).reaches) {
            if (!r.wet || r.maxWidth() < minWidth) continue;
            if (x < r.minX + r.influence - radius - r.maxWidth() || x > r.maxX - r.influence + radius + r.maxWidth()
                    || z < r.minZ + r.influence - radius - r.maxWidth() || z > r.maxZ - r.influence + radius + r.maxWidth()) continue;
            r.project(x, z, pr);
            if (pr[0] > radius || pr[0] >= bestD || r.width(pr[1]) < minWidth) continue;
            double[] p = r.pointAt(pr[1]);
            int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
            column(px, pz, c);
            if ((c.kind == RIVER || c.kind == POOL) && c.wet() && c.width >= minWidth && c.base >= HydroModel.SEA_LEVEL) {
                bestD = pr[0];
                best = new double[]{px, pz, c.water};
            }
        }
        return best;
    }

    /** Reaches whose influence box meets a block box (debug and audits). */
    public java.util.List<HydroReach> reachesIn(int x0, int z0, int x1, int z1) {
        java.util.LinkedHashSet<HydroReach> out = new java.util.LinkedHashSet<>();
        for (int qz = Math.floorDiv(z0, QUERY); qz <= Math.floorDiv(z1, QUERY); qz++)
            for (int qx = Math.floorDiv(x0, QUERY); qx <= Math.floorDiv(x1, QUERY); qx++)
                for (HydroReach r : tile(qx * QUERY, qz * QUERY).reaches) out.add(r);
        return new ArrayList<>(out);
    }

    /** Per-thread work arrays of evaluate (not reentrant: evaluating a column never asks the field again). */
    private static final class Scratch {
        final double[] tmp = new double[64 * 6], pr = new double[3], ev = new double[5];
        final HydroReach[] found = new HydroReach[64];
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Per-thread memo of evaluated columns (results are immutable; several terrain consumers and the aquifer
     * ask for the same columns within a chunk). */
    private static final int MEMO = 1 << 12;
    private static final class Memo {
        final long[] key = new long[MEMO];
        final Column[] value = new Column[MEMO];
        Memo() { java.util.Arrays.fill(key, Long.MIN_VALUE); for (int i = 0; i < MEMO; i++) value[i] = new Column(); }
    }
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new), rawMemo = ThreadLocal.withInitial(Memo::new);
    private final ThreadLocal<Column> neighbour = ThreadLocal.withInitial(Column::new);
    private static final int[] NX = {1, -1, 0, 0}, NZ = {0, 0, 1, -1};
    /** Natural bank over river and lake water: blocks above the natural ground, plus per block of local slope. */
    static final double LEVEE = 1.25, LEVEE_SLOPE = 2.0, LEVEE_MAX = 4.0;
    /** Depth below the sea surface over which a river's channel fades out on the sea floor. */
    static final double MOUTH_FADE = 4;
    /** Bank above the water in a riffle zone: just over a block, so the bank's top block is one above the water. */
    static final double RIFFLE_BANK = 1.01;

    /** Evaluate one column (final: carved ground, water, and the containment rule). */
    public void column(int x, int z, Column out) {
        long k = ((long) x << 32) ^ (z & 0xffffffffL);
        int slot = (int) ((k * 0x9E3779B97F4A7C15L) >>> 52) & (MEMO - 1);
        Memo m = memo.get();
        if (m.key[slot] == k) { copy(m.value[slot], out); return; }
        raw(x, z, out);
        if (!out.wetIncludingSea()) {
            // Containment: carving never leaves a dry column below the water beside it. Only carving is undone -
            // never above the natural ground (no artificial banks); a natural low spot stays for the levels to avoid.
            // Beside river and lake water (not the sea: the coast stays native) a natural bank rises above the water:
            // a levee of at most LEVEE blocks over the natural ground on flat land, more on a steep slope where the
            // bank is a rock step anyway (audit 23.11: a stream level 91 beside forest ground 90.26 spilled 193
            // blocks over the bank, seed 120327 X147 Z-1597). One column wide - never a plate.
            Column n = neighbour.get();
            int high = HydroModel.NO_WATER;
            double slope = 0, bank = Double.NEGATIVE_INFINITY;
            for (int d = 0; d < 4; d++) {
                raw(x + NX[d], z + NZ[d], n);
                slope = Math.max(slope, Math.abs(n.base - out.base));
                if (n.wetIncludingSea()) high = Math.max(high, n.effectiveWater());
                // Bank top over river and lake water: level with the water (a shelf), but a block above it in a riffle
                // zone - the flowing water of a step spreads one block above the lower water for up to 8 blocks and
                // ran over shelf-level banks and down the slope beyond (audit 24.11 V-2: seed 42 X250 Z-1560, 358
                // blocks of water left the channels in 15 s; 274 from a step 161 -> 160 over a channel edge at 160).
                // Below a cascade of steps of two blocks and more the spread of the step above lands two blocks over the
                // water: the bank there stands two blocks above it.
                if (n.wet()) bank = Math.max(bank, n.water + (n.hold > n.water ? Math.min(2, n.hold - n.water) + RIFFLE_BANK - 1 : 1.0));
            }
            if (high != HydroModel.NO_WATER) out.terrain = Math.max(out.terrain, Math.min(out.base, high + 1.0));
            if (bank > Double.NEGATIVE_INFINITY)
                out.terrain = Math.max(out.terrain, Math.min(bank, out.base + Math.min(LEVEE_MAX, LEVEE + LEVEE_SLOPE * slope)));
        }
        copy(out, m.value[slot]);
        m.key[slot] = k;
    }
    /** Column before the containment rule (memoized separately). */
    private void raw(int x, int z, Column out) {
        long k = ((long) x << 32) ^ (z & 0xffffffffL);
        int slot = (int) ((k * 0x9E3779B97F4A7C15L) >>> 52) & (MEMO - 1);
        Memo m = rawMemo.get();
        if (m.key[slot] == k) { copy(m.value[slot], out); return; }
        evaluate(x, z, out);
        copy(out, m.value[slot]);
        m.key[slot] = k;
    }
    private static void copy(Column a, Column b) {
        b.base = a.base; b.terrain = a.terrain; b.water = a.water; b.kind = a.kind; b.channelDistance = a.channelDistance; b.wetDistance = a.wetDistance; b.width = a.width;
        b.hold = a.hold; b.nearWater = a.nearWater;
    }

    private void evaluate(int x, int z, Column out) {
        HydroModel model = this.model;
        double h0 = model.terrain.at(x, z);
        out.base = h0; out.terrain = h0; out.water = HydroModel.NO_WATER; out.kind = NONE; out.channelDistance = Double.POSITIVE_INFINITY;
        out.wetDistance = Double.POSITIVE_INFINITY; out.width = 0; out.nearWater = HydroModel.NO_WATER;
        Tile t = tile(x, z);
        Scratch scratch = SCRATCH.get();
        double[] tmp = scratch.tmp, pr = scratch.pr, ev = scratch.ev;
        int count = 0;
        HydroReach[] found = scratch.found;
        for (HydroReach r : t.reaches) {
            if (!r.inBox(x, z)) continue;
            r.project(x, z, pr);
            if (pr[0] > r.influence) continue;
            int slot = count;
            if (count == found.length) {
                // Full (dense confluences): keep the nearest candidates, never drop a near reach for a far one.
                slot = 0;
                for (int q = 1; q < count; q++) if (tmp[q * 2] > tmp[slot * 2]) slot = q;
                if (tmp[slot * 2] <= pr[0]) continue;
            } else count++;
            found[slot] = r; tmp[slot * 2] = pr[0]; tmp[slot * 2 + 1] = pr[1]; tmp[SIDE + slot] = pr[2];
        }
        // nearest first
        for (int a = 1; a < count; a++) for (int b = a; b > 0 && tmp[b * 2] < tmp[(b - 1) * 2]; b--) {
            HydroReach r = found[b]; found[b] = found[b - 1]; found[b - 1] = r;
            double d = tmp[b * 2], s = tmp[b * 2 + 1], sa = tmp[SIDE + b];
            tmp[b * 2] = tmp[(b - 1) * 2]; tmp[b * 2 + 1] = tmp[(b - 1) * 2 + 1]; tmp[SIDE + b] = tmp[SIDE + b - 1];
            tmp[(b - 1) * 2] = d; tmp[(b - 1) * 2 + 1] = s; tmp[SIDE + b - 1] = sa;
        }
        double carve = Double.POSITIVE_INFINITY, ownerCarve = Double.POSITIVE_INFINITY;
        int water = HydroModel.NO_WATER, kind = NONE, hold = HydroModel.NO_WATER;
        // Owner of the column: the channel whose water (or dry bed) it shows. A wet channel always wins over a dry
        // one crossing it (a dry wash cannot remove a river's water); among equals the deeper bed wins.
        boolean owned = false, ownerWet = false;
        int ownerRank = -1;
        for (int q = 0; q < count; q++) {
            HydroReach r = found[q];
            double d = tmp[q * 2], arc = tmp[q * 2 + 1];
            r.evaluate(model, x, z, d, arc, tmp[SIDE + q], ev, r.wet && barsAllowed(found, tmp, count, q));
            double ew = edgeWeight(r, d), zr = edgeFade(ev[0], h0, ew);
            tmp[EW + q] = ew;
            if (r.wet) {
                double wd = Math.max(0, d - r.width(arc) / 2);
                if (wd < out.wetDistance) { out.wetDistance = wd; out.nearWater = r.waterLevel(model, arc); }
            }
            boolean inChannel = ev[2] > 0;
            if (inChannel) out.channelDistance = Math.min(out.channelDistance, d);
            // The carve never stands above the natural ground: far out, a V-valley profile lies hundreds of blocks above
            // it, and the ridge toward a separate valley (combine) rose by that height over 12 blocks - a dry wall of 18
            // blocks between two columns 90 blocks from the river (unit test valleyCarveEndsWithoutWalls).
            tmp[CARVES + q] = Math.min(zr, h0); tmp[FLAGS + q] = inChannel ? 1 : 0;
            // Rank: a wet channel whose water covers this column, then a wet channel (its very edge), then a dry wash.
            int rank = r.wet ? (zr <= ev[1] ? 2 : 1) : 0;
            if (inChannel && (!owned || rank > ownerRank || (rank == ownerRank && zr < ownerCarve))) {
                owned = true; ownerWet = r.wet; ownerRank = rank;
                ownerCarve = zr;
                water = (int) ev[1];
                hold = r.wet ? (int) ev[4] : HydroModel.NO_WATER;
                out.width = r.width(arc);
                kind = r.wet ? (ev[3] > 0 ? POOL : RIVER) : DRY;
                if (!r.wet) water = HydroModel.NO_WATER;
            }
        }
        if (count > 0) {
            // The valleys of the nearest reach and the reaches joined to it carve; a separate river keeps a ridge between
            // the valleys. Within one river strand the choice of the nearest reach blends over SWAP_BLEND blocks
            // (strandCarve). Between the nearest strand and the nearest separate one the blend weighs each by how far
            // it stands from its own influence edge: a separate valley may not carve inside the nearest strand's
            // domain, and that domain ended at the influence radius - a step where the next column no longer saw the
            // reach (unit test valleyCarveEndsWithoutWalls). As the nearest strand fades, the separate valley takes
            // over whatever its distance; at equal distance both weigh the same.
            long own = strand(found, count, 0);
            carve = strandCarve(found, tmp, count, 0, own);
            int m = -1;
            for (int i = 1; i < count && m < 0; i++) if ((own >>> i & 1) == 0) m = i;
            if (m > 0) {
                long other = strand(found, count, m);
                double s0 = groupWeight(found, tmp, count, own), sm = groupWeight(found, tmp, count, other);
                double wm = sm * Math.max(1 - HydroClimate.smooth(0, SWAP_BLEND, tmp[m * 2] - tmp[0]), 1 - s0);
                if (wm > 0 && s0 + wm > 1e-9) carve = (carve * s0 + strandCarve(found, tmp, count, m, other) * wm) / (s0 + wm);
            }
        }
        // Lake outlets: the overflow notch from the shore to the outlet river.
        for (HydroOutlet o : t.outlets) {
            if (!o.inBox(x, z)) continue;
            o.evaluate(x, z, ev);
            if (Double.isInfinite(ev[0])) continue;
            // The notch sides end at the outlet's carving reach: fade the cut over the last blocks (no wall there).
            double xb = ev[3] - o.half, zo = ev[0];
            if (zo < h0) zo = h0 - (h0 - zo) * HydroClimate.smooth(HydroOutlet.INFLUENCE, HydroOutlet.INFLUENCE - OUTLET_FADE, xb);
            carve = Math.min(carve, zo);
            double wd = Math.max(0, ev[3] - o.half);
            if (wd < out.wetDistance) { out.wetDistance = wd; out.nearWater = (int) ev[4]; }
            if (ev[2] > 0) {
                out.channelDistance = Math.min(out.channelDistance, ev[3]);
                int rank = ev[0] <= ev[1] ? 2 : 1;
                if (!owned || rank > ownerRank || (rank == ownerRank && ev[0] < ownerCarve)) {
                    owned = true; ownerWet = true; ownerRank = rank;
                    ownerCarve = ev[0]; water = (int) ev[1]; hold = water; out.width = 2 * o.half; kind = RIVER;
                }
            }
        }
        // A wet channel keeps its own bed: the ridge kept between separate valleys (or a dry wash) never raises the
        // ground inside it above the water (audit 22.11 N-02/H03).
        if (owned && ownerWet) carve = Math.min(carve, ownerCarve);
        double terrain = Math.min(h0, carve);
        // A river mouth fades out on the sea floor: the last reach runs on to its lattice node in the sea, and its bed
        // cut a straight trench across the shallows (seed 42 X2900 Z2482). The cut shrinks to nothing over the first
        // MOUTH_FADE blocks below the sea surface: a submerged mouth bar instead of a canal.
        if (owned && ownerWet && water != HydroModel.NO_WATER && water <= HydroModel.SEA_WATER && h0 < HydroModel.SEA_LEVEL && terrain < h0)
            terrain = h0 - (h0 - terrain) * HydroClimate.smooth(HydroModel.SEA_LEVEL - MOUTH_FADE, HydroModel.SEA_LEVEL, h0);
        // Closed pockets below a held channel's water are ponds at that level (HydroReach.pocketLevel).
        if (!owned) for (int q = 0; q < count; q++) {
            int level = found[q].pocketLevel(model, x, z);
            if (level != HydroModel.NO_WATER && terrain <= level) { water = level; hold = level; kind = POOL; out.wetDistance = 0; out.nearWater = level; break; }
        }
        for (HydroModel.Group g : t.lakes) {
            if (g.mask.contains(x, z)) {
                if (water == HydroModel.NO_WATER || g.mask.level >= water) { water = g.mask.level; hold = water; kind = LAKE; }
                out.wetDistance = 0; out.nearWater = g.mask.level;
                break;
            }
        }
        // Exact (chamfer) distance to every nearby lake: no shore shape slips between probes.
        if (out.wetDistance > 0) for (HydroModel.Group g : t.lakes) {
            double dd = g.mask.distance(x, z);
            if (dd < out.wetDistance) { out.wetDistance = dd; out.nearWater = g.mask.level; }
        }
        out.terrain = terrain;
        out.water = water;
        out.kind = kind;
        out.hold = hold;
    }


    /** Scratch layout of evaluate: distance and arc per candidate at 2q, 2q + 1; carve, in-channel flag, side arc
     * (HydroReach.project) and edge weight at these offsets + q. */
    private static final int CARVES = 128, FLAGS = 192, SIDE = 256, EW = 320;
    static final double SWAP_BLEND = 6, OUTLET_FADE = 16;

    /** A reach's carve faded out toward its influence radius. The reach takes part only within that radius, and its
     * valley profile there can still lie tens of blocks below a hill: the cut ended in a vertical wall exactly at the
     * radius - amphitheatres around reach ends, straight cuts along a reach (audit 24.11 G-1: seed 31337 X-3226 Z2416,
     * 25 blocks between two columns 2 blocks apart). The cut shrinks to nothing over the outer 40% of the carving
     * reach: the valley side steepens into a shoulder that meets the natural slope. */
    static double edgeFade(double zr, double h0, double edgeWeight) {
        return zr >= h0 ? zr : h0 - (h0 - zr) * edgeWeight;
    }
    /** 1 within a reach's carving reach, falling smoothly to 0 over its outer EDGE_FADE share (at the influence
     * radius, where the reach stops being a candidate). */
    static double edgeWeight(HydroReach r, double d) {
        double reach = r.influence - r.maxWidth() / 2 - r.fp, start = r.influence - EDGE_FADE * reach;
        return d <= start ? 1 : HydroClimate.smooth(r.influence, start, d);
    }
    static final double EDGE_FADE = 0.4;
    /** Candidates on the river strand of candidate n (joined through main-stem continuations, HydroReach.sameRiver): one
     * valley. A bit set over the candidate indices (at most 64). */
    private static long strand(HydroReach[] found, int count, int n) {
        long in = 1L << n, frontier = in;
        while (frontier != 0) {
            int j = Long.numberOfTrailingZeros(frontier);
            frontier &= frontier - 1;
            for (int i = 0; i < count; i++)
                if ((in >>> i & 1) == 0 && found[i].sameRiver(found[j])) { in |= 1L << i; frontier |= 1L << i; }
        }
        return in;
    }
    /** Carve of a strand whose nearest candidate is n: combine with n as the nearest reach, blended over SWAP_BLEND
     * blocks with the strand's next nearest candidate (which reaches a reach joins - and so which valleys carve - changes
     * where the nearest reach of the river changes: a wall of 20 blocks at seed 42 X1765 Z-1985, audit 24.11). */
    private static double strandCarve(HydroReach[] found, double[] tmp, int count, int n, long strand) {
        double c = combine(found, tmp, count, n);
        for (int k = n + 1; k < count; k++) {
            if ((strand >>> k & 1) == 0) continue;
            double gap = tmp[k * 2] - tmp[n * 2];
            if (gap >= SWAP_BLEND) break;
            double w = 0.5 + 0.5 * HydroClimate.smooth(0, SWAP_BLEND, gap);
            return combine(found, tmp, count, k) * (1 - w) + c * w;
        }
        return c;
    }
    /** Strongest edge weight among the candidates of a strand. */
    private static double groupWeight(HydroReach[] found, double[] tmp, int count, long strand) {
        double w = 0;
        for (int i = 0; i < count; i++) if ((strand >>> i & 1) != 0) w = Math.max(w, tmp[EW + i]);
        return w;
    }

    /** Carve of the column with candidate n as the nearest reach: the minimum over n, the channels containing the
     * column and the reaches joined to n; then a ridge toward separate reaches about as near as n (full at equal
     * distance, none 12 blocks farther, none at that reach's influence edge). Order-independent among the other
     * reaches. */
    private static double combine(HydroReach[] found, double[] tmp, int count, int n) {
        HydroReach near = found[n];
        double c = tmp[CARVES + n];
        for (int i = 0; i < count; i++) {
            if (i == n) continue;
            if (tmp[FLAGS + i] > 0 || found[i].connected(near)) c = Math.min(c, tmp[CARVES + i]);
        }
        double raise = 0;
        for (int i = 0; i < count; i++) {
            if (i == n || tmp[FLAGS + i] > 0 || found[i].connected(near)) continue;
            double t = Math.max(0, Math.min(1, (tmp[i * 2] - tmp[n * 2]) / 12));
            raise = Math.max(raise, Math.max(0, tmp[CARVES + i] - c) * (1 - t) * tmp[EW + i]);
        }
        return c + raise;
    }

    /** Point bars of a reach only where no other channel's water is within BAR_CLEAR blocks of its edge: a tributary's
     * riffles and a sibling's water stand at other levels than the bar. The same river's next and previous reaches
     * share its water profile and do not count. */
    private static boolean barsAllowed(HydroReach[] found, double[] tmp, int count, int q) {
        HydroReach r = found[q];
        for (int o = 0; o < count; o++) {
            if (o == q) continue;
            HydroReach other = found[o];
            if (!other.wet || r.sameRiver(other)) continue;
            if (tmp[o * 2] <= other.maxWidth() / 2 + BAR_CLEAR) return false;
        }
        return true;
    }
    static final double BAR_CLEAR = HydroReach.REF_BACK + 2;

    /** Smooth minimum (polynomial, k = blend width). */
    static double smin(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }
}
