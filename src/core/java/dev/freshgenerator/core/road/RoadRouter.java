package dev.freshgenerator.core.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/** Plans one road between two villages (user request 24.11).
 * <ul>
 * <li>Least-cost path on an 8-block lattice with 16 headings: length, a cost rising with the grade and a hard grade
 *     limit (a mountain village gets a winding road or none), a turn cost (long legs between few hairpins), a cost for
 *     walking along a bank (a road keeps off a creek unless it crosses it), bridges priced by length and a fixed price
 *     per crossing (roads look for narrow places and cross a river once), lakes, the sea and village buildings
 *     impassable (an island village has no road). Weighted A* inside an ellipse around the two villages.</li>
 * <li>The path is straightened where a straight line costs no more (string pulling), rounded (Chaikin) where the
 *     rounded line stays on passable ground, resampled every block and given a slight seeded meander that fades out
 *     at the water.</li>
 * <li>The finished line is checked block by block (audit 25.11 D-1, K2: the lattice read the ground every 4-9 blocks
 *     and missed steps of two blocks, a ridge one block thick and single water columns): water without a bridge, a
 *     lake, the sea or a building is re-routed around (up to three times, else no road); a step or a stretch steeper
 *     than the line limit is re-routed too, and where it stays the path breaks off there.</li>
 * <li>Along it: a width (1-3 blocks) and a paint density with gaps of 10-30 blocks - a path worn by use, not a built
 *     road - and an axis-aligned bridge, ford or log at every river crossing.</li>
 * </ul>
 * Pure function of the terrain, the ends and the seed. Not thread-safe: one router per route. */
public final class RoadRouter {
    public static final int STEP = 8;
    /** Hard grade between lattice readings (a knight's move still climbs a slope of 0.67 at it: a mountain gets a
     * winding road), and the grade of the finished line over 8 blocks, checked block by block (audit 25.11: the lattice
     * limit alone let finished roads reach 0.44; a path that needs a jump every third block looks built for goats). */
    public static final double MAX_GRADE = 0.30, LINE_GRADE = 0.28;
    /** Difference between the top blocks of two neighbouring points of the finished line a walker climbs (one jump).
     * The tops are counted as blocks (ceil(ground) - 1), not as heights: 64.0 and 65.1 differ by 1.1 but stand on Y63
     * and Y65 (code audit 25.11 OpenAI 2: a two-block step passed a 1.2 limit on the heights). */
    public static final int MAX_STEP = 1;
    public static final int MAX_BRIDGE = 40;
    static final double WEIGHT = 1.25, BRIDGE_COST = 5, CROSSING_COST = 60;
    /** Price of a hairpin (blocks of road): a winding road takes long legs between few turns - a saw-tooth of
     * lattice moves was rounded by the smoothing into a line twice as steep as the limit (unit test). */
    static final double TURN_COST = 40;
    /** A road keeps BANK blocks off river and lake water, at BANK_COST per block of road and block of shortfall. */
    static final double BANK = 3, BANK_COST = 2;
    static final int MAX_EXPANSIONS = 120_000, ATTEMPTS = 3;
    /** Points at each end of the line the line check leaves alone (the street end itself); beyond them the check skips
     * only the village's own pieces (code audits 25.11 OpenAI, Opus KO-04: 16 unchecked points let a ridge of eight
     * blocks or a lake at the exit of a village pass). */
    static final int ENDS = 2;
    private static final int[] MX = {1, 0, -1, 0, 1, 1, -1, -1, 1, 2, -1, -2, 1, 2, -1, -2};
    private static final int[] MZ = {0, 1, 0, -1, 1, -1, 1, -1, 2, 1, 2, 1, -2, -1, -2, -1};
    private static final double INF = Double.POSITIVE_INFINITY;
    /** Sample fields. */
    private static final int H = 0, KIND = 1, WIDTH = 2, GROUND = 3, WATER = 4, NEAR = 5, RAW = 6, VERSION = 7;

    public interface Blocked { boolean test(int x, int z); }
    /** A village end: a street end or street piece, with a preference (lower is preferred). */
    public record Exit(int x, int z, double bias) { }

    private final RoadTerrain terrain;
    private final Blocked blocked;
    /** The pieces of the two villages (streets and buildings): the road runs from their streets, unchecked there. */
    private final Blocked village;
    /** The ground the villages grade around their pieces: no bridge there - the painter draws nothing inside a village,
     * and the graded banks stand blocks off the plan (seed 314159265 X2309 Z4665: a log over a creek at a village edge
     * lost its middle, the banks graded 3-4 blocks up). */
    private final Blocked graded;
    private final HashMap<Long, double[]> cache = new HashMap<>();
    /** Places the line check found unfit: {x, z, radius}; the next attempt routes around them. */
    private final List<int[]> avoid = new ArrayList<>();
    private int version;
    private final RoadTerrain.Sample tmp = new RoadTerrain.Sample();
    /** Statistics of the last plan: lattice nodes expanded, terrain columns read, routing attempts, and why no road
     * came out (audits). */
    public int expanded, sampled, attempts;
    public String outcome = "";

    public RoadRouter(RoadTerrain terrain, Blocked blocked) { this(terrain, blocked, (x, z) -> false, (x, z) -> false); }
    public RoadRouter(RoadTerrain terrain, Blocked blocked, Blocked village, Blocked graded) {
        this.terrain = terrain; this.blocked = blocked; this.village = village; this.graded = graded;
    }
    private static int top(double ground) { return (int) Math.ceil(ground) - 1; }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static double sq(double v) { return v * v; }

    /** {height for grades (the deck over a river), kind, river width, ground, water, distance to water, raw kind,
     * version} of one column; the kind includes buildings and avoided places. */
    double[] at(int x, int z) {
        long k = key(x, z);
        double[] s = cache.get(k);
        if (s == null) {
            terrain.sample(x, z, tmp);
            sampled++;
            s = new double[]{tmp.kind == RoadTerrain.RIVER ? tmp.water + 1 : tmp.ground, 0, tmp.width, tmp.ground, tmp.water, tmp.near, tmp.kind, -1};
            cache.put(k, s);
        }
        if (s[VERSION] != version) {
            int raw = (int) s[RAW];
            s[KIND] = raw != RoadTerrain.LAKE && raw != RoadTerrain.SEA && (blocked.test(x, z) || avoided(x, z)) ? RoadTerrain.BLOCKED : raw;
            s[VERSION] = version;
        }
        return s;
    }
    private boolean avoided(int x, int z) {
        for (int[] a : avoid) if (sq(x - a[0]) + sq(z - a[1]) <= sq(a[2])) return true;
        return false;
    }
    private boolean passable(int i, int j) { return (int) at(i * STEP, j * STEP)[KIND] <= RoadTerrain.RIVER; }

    /** Cost of a straight step between two sampled columns (INF: not passable). */
    static double step(double[] a, double[] b, double len) {
        int kb = (int) b[KIND];
        if (kb >= RoadTerrain.LAKE) return INF;
        if (len <= 0) return 0;
        double g = Math.abs(b[H] - a[H]) / len;
        if (g > MAX_GRADE) return INF;
        double c = len * (1 + 8 * g * g + 40 * sq(Math.max(0, g - 0.15)));
        if (kb == RoadTerrain.RIVER) {
            if (b[WIDTH] > MAX_BRIDGE) return INF;
            c += len * BRIDGE_COST;
            if ((int) a[KIND] != RoadTerrain.RIVER) c += CROSSING_COST;
        } else if (b[NEAR] < BANK) c += len * BANK_COST * (BANK - b[NEAR]);
        return c;
    }
    private double edge(int x0, int z0, int x1, int z1) {
        double[] a = at(x0, z0), m = at((x0 + x1) >> 1, (z0 + z1) >> 1), b = at(x1, z1);
        double half = Math.hypot(x1 - x0, z1 - z0) / 2;
        double c1 = step(a, m, half);
        if (c1 == INF) return INF;
        double c2 = step(m, b, half);
        return c2 == INF ? INF : c1 + c2;
    }

    private static final class Node {
        final int i, j;
        int move = -1;
        double g = INF;
        Node parent;
        Exit exit;
        boolean closed;
        Node(int i, int j) { this.i = i; this.j = j; }
    }
    /** An open entry; a terminal entry ends the search at a goal with its street preference added. */
    private record Open(double f, long key, boolean terminal) implements Comparable<Open> {
        public int compareTo(Open o) {
            int c = Double.compare(f, o.f);
            if (c != 0) return c;
            c = Long.compare(key, o.key);
            return c != 0 ? c : Boolean.compare(terminal, o.terminal);
        }
    }
    /** A lattice path: points (x, z) and the cost reached at each. */
    record Path(List<double[]> points, double[] cost) { }

    private static double heuristic(int x, int z, int[] gx, int[] gz) {
        double best = INF;
        for (int k = 0; k < gx.length; k++) best = Math.min(best, Math.hypot(x - gx[k], z - gz[k]));
        return best;
    }

    /** Cost of a change of heading between two lattice moves (the parent's move; none at a start). */
    static double turn(int from, int to) {
        if (from < 0) return 0;
        double ax = MX[from], az = MZ[from], bx = MX[to], bz = MZ[to];
        double cos = (ax * bx + az * bz) / (Math.hypot(ax, az) * Math.hypot(bx, bz));
        return TURN_COST * Math.pow(Math.max(0, (1 - cos) / 2), 1.5);
    }

    /** Least-cost lattice path from any exit of one village to any exit of the other, inside the ellipse with foci
     * (fx, fz), (tx, tz) and axis sum maxLength; null when there is none. Both ends' street preferences count (audit
     * 25.11 K9: the goal's was ignored and the search stopped at the first goal reached). */
    Path route(List<Exit> from, List<Exit> to, double fx, double fz, double tx, double tz, double maxLength) {
        expanded = 0;
        HashMap<Long, Node> nodes = new HashMap<>();
        HashMap<Long, Exit> goals = new HashMap<>();
        for (Exit e : to) {
            int i = Math.floorDiv(e.x() + STEP / 2, STEP), j = Math.floorDiv(e.z() + STEP / 2, STEP);
            if (!passable(i, j)) continue;
            Exit old = goals.get(key(i, j));
            if (old == null || e.bias() < old.bias()) goals.put(key(i, j), e);
        }
        if (goals.isEmpty()) return null;
        int[] gx = new int[goals.size()], gz = new int[goals.size()];
        int g0 = 0;
        for (long k : goals.keySet().stream().sorted().toList()) { gx[g0] = (int) (k >> 32) * STEP; gz[g0] = (int) k * STEP; g0++; }
        PriorityQueue<Open> open = new PriorityQueue<>();
        for (Exit e : from) {
            int i = Math.floorDiv(e.x() + STEP / 2, STEP), j = Math.floorDiv(e.z() + STEP / 2, STEP);
            if (!passable(i, j)) continue;
            long k = key(i, j);
            Node n = nodes.computeIfAbsent(k, q -> new Node(i, j));
            if (e.bias() < n.g) { n.g = e.bias(); n.exit = e; open.add(new Open(n.g + WEIGHT * heuristic(i * STEP, j * STEP, gx, gz), k, false)); }
        }
        while (!open.isEmpty()) {
            Open o = open.poll();
            Node n = nodes.get(o.key());
            Exit goal = goals.get(o.key());
            if (o.terminal()) return path(n, goal);
            if (n.closed) continue;
            n.closed = true;
            if (goal != null) open.add(new Open(n.g + goal.bias(), o.key(), true));
            if (++expanded > MAX_EXPANSIONS) return null;
            int x = n.i * STEP, z = n.j * STEP;
            for (int m = 0; m < MX.length; m++) {
                int ni = n.i + MX[m], nj = n.j + MZ[m], nx = ni * STEP, nz = nj * STEP;
                if (Math.hypot(nx - fx, nz - fz) + Math.hypot(nx - tx, nz - tz) > maxLength) continue;
                long nk = key(ni, nj);
                Node next = nodes.get(nk);
                if (next != null && next.closed) continue;
                if (!passable(ni, nj)) continue;
                double c = edge(x, z, nx, nz);
                if (c == INF) continue;
                double g = n.g + c + turn(n.move, m);
                if (next == null) { next = new Node(ni, nj); nodes.put(nk, next); }
                if (g < next.g - 1e-9) {
                    next.g = g; next.parent = n; next.exit = null; next.move = m;
                    open.add(new Open(g + WEIGHT * heuristic(nx, nz, gx, gz), nk, false));
                }
            }
        }
        return null;
    }
    private static Path path(Node end, Exit goal) {
        ArrayList<Node> chain = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) chain.add(n);
        java.util.Collections.reverse(chain);
        List<double[]> pts = new ArrayList<>();
        int size = chain.size() + 2;
        double[] cost = new double[size];
        Node first = chain.get(0);
        Exit start = first.exit;
        pts.add(new double[]{start.x() + 0.5, start.z() + 0.5});
        cost[0] = 0;
        for (int k = 0; k < chain.size(); k++) {
            Node n = chain.get(k);
            pts.add(new double[]{n.i * STEP + 0.5, n.j * STEP + 0.5});
            cost[k + 1] = n.g;
        }
        pts.add(new double[]{goal.x() + 0.5, goal.z() + 0.5});
        cost[size - 1] = end.g;
        return new Path(pts, cost);
    }

    /** Cost of a straight segment sampled every ~4 blocks on the 4-block lattice (INF: not passable). */
    private double segment(double[] p, double[] q) {
        double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
        int steps = Math.max(1, (int) Math.ceil(len / 4));
        double total = 0;
        int px = snap(p[0]), pz = snap(p[1]);
        double[] prev = at(px, pz);
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            int x = snap(p[0] + (q[0] - p[0]) * t), z = snap(p[1] + (q[1] - p[1]) * t);
            if (x == px && z == pz) continue;
            double[] cur = at(x, z);
            double c = step(prev, cur, Math.hypot(x - px, z - pz));
            if (c == INF) return INF;
            total += c;
            prev = cur; px = x; pz = z;
        }
        return total;
    }
    private static int snap(double v) { return (int) Math.round(v / 4) * 4; }

    /** Straight runs where a straight line costs no more than the lattice path (switchbacks stay switchbacks). */
    List<double[]> pull(Path path) {
        List<double[]> p = path.points();
        double[] cost = path.cost();
        int n = p.size();
        List<double[]> out = new ArrayList<>();
        out.add(p.get(0));
        int i = 0;
        while (i < n - 1) {
            int best = i + 1;
            for (int j = Math.min(n - 1, i + 12); j > i + 1; j--) {
                double limit = (cost[j] - cost[i]) * 1.03 + 2;
                if (segment(p.get(i), p.get(j)) <= limit) { best = j; break; }
            }
            out.add(p.get(best));
            i = best;
        }
        return out;
    }

    static List<double[]> chaikin(List<double[]> p, int iterations) {
        for (int it = 0; it < iterations && p.size() > 2; it++) {
            List<double[]> q = new ArrayList<>(p.size() * 2);
            q.add(p.get(0));
            for (int i = 0; i + 1 < p.size(); i++) {
                double[] a = p.get(i), b = p.get(i + 1);
                q.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                q.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
            q.add(p.get(p.size() - 1));
            p = q;
        }
        return p;
    }
    /** Whether a polyline stays off lakes, the sea and buildings (every two blocks) and within the grade limit (every
     * four blocks; rounding a switchback shortens it), the ends excepted (the village streets it starts from). */
    private boolean clear(List<double[]> p) {
        double[][] line = resample(p);
        double[] x = line[0], z = line[1];
        int n = x.length;
        double[] h = new double[n];
        boolean[] own = new boolean[n];
        for (int k = 0; k < n; k += 2) {
            int bx = (int) Math.floor(x[k]), bz = (int) Math.floor(z[k]);
            own[k] = k < ENDS || k > n - ENDS - 1 || village.test(bx, bz);
            if (own[k]) continue;
            double[] here = at(bx, bz);
            if ((int) here[KIND] >= RoadTerrain.LAKE) return false;
            h[k] = here[H];
            if (k >= 8 && !own[k - 8] && Math.abs(h[k] - h[k - 8]) > 8 * LINE_GRADE) return false;
        }
        return true;
    }
    static double[][] resample(List<double[]> p) {
        double total = 0;
        for (int i = 0; i + 1 < p.size(); i++) total += Math.hypot(p.get(i + 1)[0] - p.get(i)[0], p.get(i + 1)[1] - p.get(i)[1]);
        int n = Math.max(2, (int) Math.floor(total) + 1);
        double[] x = new double[n], z = new double[n];
        int seg = 0;
        double segStart = 0;
        for (int k = 0; k < n; k++) {
            double s = Math.min(k, total);
            while (seg + 2 < p.size()) {
                double len = Math.hypot(p.get(seg + 1)[0] - p.get(seg)[0], p.get(seg + 1)[1] - p.get(seg)[1]);
                if (segStart + len >= s) break;
                segStart += len; seg++;
            }
            double[] a = p.get(seg), b = p.get(seg + 1);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double t = len <= 0 ? 0 : Math.min(1, (s - segStart) / len);
            x[k] = a[0] + (b[0] - a[0]) * t;
            z[k] = a[1] + (b[1] - a[1]) * t;
        }
        return new double[][]{x, z};
    }

    /** Plans the road; null where none can be built. */
    public RoadLine plan(long id, long seed, List<Exit> from, List<Exit> to, double fx, double fz, double tx, double tz, double maxLength) {
        double span = Math.hypot(tx - fx, tz - fz);
        for (attempts = 1; ; attempts++) {
            Path path = route(from, to, fx, fz, tx, tz, maxLength);
            if (path == null) { outcome = attempts == 1 ? "no lattice path" : "no lattice path round the faults"; return null; }
            List<double[]> pulled = pull(path), smooth = pulled;
            for (int it = 3; it >= 1; it--) {
                List<double[]> c = chaikin(pulled, it);
                if (clear(c)) { smooth = c; break; }
            }
            double[][] base = resample(smooth);
            int n = base[0].length;
            // A slight meander (a worn path never runs dead straight), fading out at the villages and the water;
            // where it would carry the line into a fault it is taken back.
            double[] wobble = new double[n];
            long wob = RoadHash.of(seed, id, 3);
            for (int k = 0; k < n; k++) {
                double near = at((int) Math.floor(base[0][k]), (int) Math.floor(base[1][k]))[NEAR];
                double fade = Math.min(1, Math.min(k, n - 1 - k) / 24.0) * Math.max(0, Math.min(1, (near - 2) / 4));
                wobble[k] = 1.2 * fade * RoadHash.noise(wob, k / 36.0);
            }
            double[][] line = displace(base, wobble);
            List<Bridge> bridges = bridges(line, seed, id);
            line = onDecks(line, bridges);
            List<Integer> faults = faults(line, bridges);
            if (!faults.isEmpty()) {
                for (int f : faults) for (int k = Math.max(0, f - 10); k <= Math.min(n - 1, f + 10); k++) wobble[k] = 0;
                line = displace(base, wobble);
                bridges = bridges(line, seed, id);
                line = onDecks(line, bridges);
                faults = faults(line, bridges);
            }
            boolean hard = false;
            for (int f : faults) if (hard(line, f, bridges)) { hard = true; break; }
            if (faults.isEmpty() || attempts == ATTEMPTS) {
                if (hard) { outcome = "water on the line after " + attempts + " attempts"; return null; }
                outcome = faults.isEmpty() ? "clean" : faults.size() + " rough points left as gaps";
                return profile(id, seed, line[0], line[1], bridges, faults, span);
            }
            // Route around the faults (each place once).
            for (int f : faults) {
                int fxk = (int) Math.floor(line[0][f]), fzk = (int) Math.floor(line[1][f]);
                if (!avoided(fxk, fzk)) avoid.add(new int[]{fxk, fzk, hard(line, f, bridges) ? 5 : 4});
            }
            version++;
        }
    }
    private static double[][] displace(double[][] base, double[] wobble) {
        double[] ox = base[0], oz = base[1];
        int n = ox.length;
        double[] x = ox.clone(), z = oz.clone();
        for (int k = 0; k < n; k++) {
            if (wobble[k] == 0) continue;
            int a = Math.max(0, k - 2), b = Math.min(n - 1, k + 2);
            double tx0 = ox[b] - ox[a], tz0 = oz[b] - oz[a], tl = Math.hypot(tx0, tz0);
            if (tl <= 0) continue;
            x[k] = ox[k] - tz0 / tl * wobble[k];
            z[k] = oz[k] + tx0 / tl * wobble[k];
        }
        return new double[][]{x, z};
    }

    /** The line over a bridge runs on its deck: points within its length and a few blocks of its axis move across onto
     * the deck (code audit 26.11 OpenAI R01: a road meeting an axis-aligned bridge at an angle had its axis over the
     * water beside the deck and was still taken as clean). The painter paints the deck; the walker stays on it. */
    /** Blocks before and after a deck over which the line eases onto it. */
    static final double APPROACH = 4;
    private static double[][] onDecks(double[][] line, List<Bridge> bridges) {
        if (bridges.isEmpty()) return line;
        double[] x = line[0].clone(), z = line[1].clone();
        for (int k = 0; k < x.length; k++) for (Bridge b : bridges) {
            double a = b.alongX ? x[k] : z[k], c = b.alongX ? z[k] : x[k];
            // On the deck fully; over APPROACH blocks before and after it the line eases across onto it (a point just
            // before the head stayed put and the next jumped up to 5 blocks sideways: code audit 27.11 Opus KO-07).
            double out = a < b.start ? b.start - a : a >= b.end + 1 ? a - (b.end + 1) : 0;
            if (out > APPROACH || Math.abs(c - b.centre) > 6) continue;
            double on = Math.max(b.across0, Math.min(b.across1 + 0.99, c)), w = 1 - out / APPROACH;
            double moved = c + (on - c) * w;
            if (b.alongX) z[k] = moved; else x[k] = moved;
        }
        return new double[][]{x, z};
    }

    /** Bridges at river crossings (runs of river columns, two dry columns inside a run allowed). */
    private List<Bridge> bridges(double[][] line, long seed, long id) {
        double[] x = line[0], z = line[1];
        int n = x.length;
        int[] kind = new int[n];
        for (int k = 0; k < n; k++) kind[k] = (int) at((int) Math.floor(x[k]), (int) Math.floor(z[k]))[RAW];
        List<Bridge> bridges = new ArrayList<>();
        for (int i = 0; i < n; ) {
            if (kind[i] != RoadTerrain.RIVER) { i++; continue; }
            int j = i;
            while (j + 1 < n && (kind[j + 1] == RoadTerrain.RIVER || (j + 2 < n && kind[j + 2] == RoadTerrain.RIVER)
                    || (j + 3 < n && kind[j + 3] == RoadTerrain.RIVER))) j++;
            int a = Math.max(0, i - 3), b = Math.min(n - 1, j + 3), mid = (i + j) / 2;
            Bridge br = Bridge.plan(terrain, x[mid], z[mid], x[b] - x[a], z[b] - z[a], RoadHash.of(seed, id, 100 + bridges.size()), MAX_BRIDGE);
            if (br != null && !onGraded(br) && bridges.stream().noneMatch(o -> overlap(o, br))) bridges.add(br);
            i = j + 1;
        }
        return bridges;
    }
    private boolean onGraded(Bridge b) {
        int[] box = b.box();
        for (int x = box[0]; x <= box[2]; x++) for (int z = box[1]; z <= box[3]; z++) if (graded.test(x, z)) return true;
        return false;
    }
    /** Whether a point of the line goes over a bridge: on its deck, or on dry ground within its length and a few
     * blocks of its axis (a road meets an axis-aligned bridge at an angle and comes onto the deck from the bank). A
     * point over water beside the deck is no bridge: the walker is in the water there (code audit 26.11 OpenAI R01: a
     * clean road over a river 8 wide crossed water at (117, 6) and (123, 10) outside the deck of an intact bridge). */
    private static boolean onBridge(List<Bridge> bridges, double x, double z, boolean dry) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (Bridge b : bridges) {
            if (b.contains(bx, bz)) return true;
            int a = b.alongX ? bx : bz, c = b.alongX ? bz : bx;
            if (dry && a >= b.start && a <= b.end && Math.abs(c - b.centre) <= 4) return true;
        }
        return false;
    }
    /** Points of the finished line that a walker could not take: water without a bridge, a lake or the sea (hard), a
     * step, an 8-block stretch steeper than the line limit or a place the road keeps off (soft). The pieces of the two
     * villages are left alone: the painter never paints on them, the line through a village follows its streets. */
    private List<Integer> faults(double[][] line, List<Bridge> bridges) {
        double[] x = line[0], z = line[1];
        int n = x.length;
        List<Integer> out = new ArrayList<>();
        double[] h = new double[n], g = new double[n];
        int[] kind = new int[n];
        boolean[] skip = new boolean[n];
        for (int k = 0; k < n; k++) {
            int bx = (int) Math.floor(x[k]), bz = (int) Math.floor(z[k]);
            double[] s = at(bx, bz);
            kind[k] = (int) s[RAW]; h[k] = s[H]; g[k] = s[GROUND];
            skip[k] = k < ENDS || k >= n - ENDS || onBridge(bridges, x[k], z[k], kind[k] == RoadTerrain.DRY) || village.test(bx, bz);
        }
        for (int k = 1; k < n; k++) {
            if (skip[k]) continue;
            // Water without a bridge, or a place the road keeps off (another village, a structure beside the way).
            int bx = (int) Math.floor(x[k]), bz = (int) Math.floor(z[k]);
            boolean bad = kind[k] != RoadTerrain.DRY || blocked.test(bx, bz);
            if (!bad && kind[k - 1] == RoadTerrain.DRY && !skip[k - 1] && Math.abs(top(g[k]) - top(g[k - 1])) > MAX_STEP) bad = true;
            if (!bad && k >= 8 && !skip[k - 8] && Math.abs(h[k] - h[k - 8]) > 8 * LINE_GRADE) bad = true;
            if (bad) out.add(k);
        }
        return out;
    }
    private boolean hard(double[][] line, int k, List<Bridge> bridges) {
        return (int) at((int) Math.floor(line[0][k]), (int) Math.floor(line[1][k]))[RAW] != RoadTerrain.DRY;
    }

    RoadLine profile(long id, long seed, double[] x, double[] z, List<Bridge> bridges, List<Integer> faults, double span) {
        int n = x.length;
        double[] height = new double[n];
        int[] kind = new int[n];
        for (int k = 0; k < n; k++) {
            double[] s = at((int) Math.floor(x[k]), (int) Math.floor(z[k]));
            kind[k] = (int) s[KIND];
            height[k] = s[H];
        }
        double maxGrade = 0;
        for (int k = 16; k < n; k++) {
            if (kind[k] > RoadTerrain.RIVER || kind[k - 16] > RoadTerrain.RIVER) continue;
            maxGrade = Math.max(maxGrade, Math.abs(height[k] - height[k - 16]) / 16);
        }
        // Width class: steep roads are trails, short roads between near villages often wider.
        double u = RoadHash.unit(seed, id, 11);
        double base = maxGrade > 0.18 ? (u < 0.6 ? 0.55 : 1.0) : span < 650 ? (u < 0.55 ? 1.5 : 1.0) : (u < 0.35 ? 1.5 : u < 0.8 ? 1.0 : 0.55);
        long widthSeed = RoadHash.of(seed, id, 12);
        float[] half = new float[n], density = new float[n];
        for (int k = 0; k < n; k++) half[k] = (float) Math.max(0.5, base * (0.85 + 0.15 * (RoadHash.noise(widthSeed, k / 37.0) + 1)));
        // Paint: sections of a worn path with gaps of 10-30 blocks; always painted at the villages and the bridges;
        // the path breaks off at a step it could not avoid.
        double[] level = new double[n];
        boolean[] gap = new boolean[n];
        double s = 20 + 40 * RoadHash.unit(seed, id, 13);
        int section = 0;
        double sectionLevel = 0.8 + 0.2 * RoadHash.unit(seed, id, 14);
        int from = 0;
        while (s < n - 30) {
            double present = 30 + 110 * RoadHash.unit(seed, id, 1000 + section);
            int to = (int) Math.min(n, s + present);
            for (int k = from; k < to; k++) level[k] = sectionLevel;
            s += present;
            if (RoadHash.unit(seed, id, 2000 + section) < 0.6 && s < n - 30) {
                double gl = 10 + 20 * RoadHash.unit(seed, id, 3000 + section);
                for (int k = (int) s; k < Math.min(n, s + gl); k++) gap[k] = true;
                s += gl;
            }
            section++;
            sectionLevel = 0.75 + 0.25 * RoadHash.unit(seed, id, 4000 + section);
            from = to;
        }
        for (int k = from; k < n; k++) level[k] = sectionLevel;
        boolean[] rough = new boolean[n];
        for (int f : faults) for (int k = Math.max(0, f - 3); k <= Math.min(n - 1, f + 3); k++) rough[k] = true;
        for (int k = 0; k < n; k++) {
            if (rough[k]) continue;
            boolean forced = k < 20 || k > n - 21;
            for (Bridge b : bridges) {
                int[] box = b.box();
                if (x[k] >= box[0] - 8 && x[k] <= box[2] + 8 && z[k] >= box[1] - 8 && z[k] <= box[3] + 8) { forced = true; break; }
            }
            if (gap[k] && !forced) continue;
            // Fade over three blocks into and out of a gap.
            double fade = 1;
            for (int d = 1; d <= 3; d++) {
                if ((k - d >= 0 && (gap[k - d] || rough[k - d])) || (k + d < n && (gap[k + d] || rough[k + d]))) { fade = Math.min(fade, d / 4.0); break; }
            }
            density[k] = (float) (level[k] * (forced ? 1 : fade));
        }
        return new RoadLine(id, x, z, half, density, bridges, span, maxGrade);
    }
    private static boolean overlap(Bridge a, Bridge b) {
        int[] p = a.box(), q = b.box();
        return p[2] + 2 >= q[0] && p[0] - 2 <= q[2] && p[3] + 2 >= q[1] && p[1] - 2 <= q[3];
    }
}
