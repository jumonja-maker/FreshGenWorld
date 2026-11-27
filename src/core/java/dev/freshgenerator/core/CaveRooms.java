package dev.freshgenerator.core;

/** Great breakdown chambers of the cave system (user request 26.11: "a huge room with a hundred openings in its walls;
 * the generator must surprise, yet follow logic and not look man-made").
 * <p>Natural analogue: the largest cave chambers (Sarawak Chamber, Majlis al Jinn, the Big Room of Carlsbad) form where
 * many conduits meet and the rock between them, weakened by dissolution along joints, collapses (breakdown; Klimchouk
 * 2004, Palmer 2007). The dome rises over a floor of fallen blocks, and the walls stay honeycombed by the conduits the
 * collapse cut through. Here a chamber is an ellipsoid of a few tens of blocks with a flattened floor and a high dome,
 * rubble mounds on the floor; its wall zone ({@link #shell}) carries spongework holes in the density graph, and the
 * cave levels pass through it - the openings are ends of real passages, not painted holes.
 * <p>One candidate per {@link #CELL} cell (a probability per cell, region-weighted by the caller's graph). Pure function
 * of the seed. */
public final class CaveRooms {
    public static final int CELL = 720;
    public static final double CHANCE = 0.32;
    /** Centre height band (absolute Y). */
    public static final double Y_LOW = -96, Y_HIGH = -30;

    public static final class Room {
        public final double x, y, z, rx, rz, up, down, cos, sin; final long seed;
        /** Conduits leaving the walls: start (on the wall), bend point and end, radius - flattened per conduit. */
        final double[] conduits; public final int conduitCount;
        /** Per conduit: its bounding box (both segments, without the radius) - minX, minY, minZ, maxX, maxY, maxZ. */
        final double[] boxes;
        Room(double x, double y, double z, double rx, double rz, double up, double down, double angle, long seed) {
            this.x = x; this.y = y; this.z = z; this.rx = rx; this.rz = rz; this.up = up; this.down = down; this.seed = seed;
            cos = Math.cos(angle); sin = Math.sin(angle);
            // 40-90 conduits: the passages the collapse cut through. They leave the wall at every height of the dome
            // (mostly gently inclined, some steep), bend once and run 18-70 blocks into the rock.
            conduitCount = 40 + (int) (GradientNoise.unit(seed, 31, 0) * 51);
            conduits = new double[conduitCount * 10];
            for (int c = 0; c < conduitCount; c++) {
                double a = GradientNoise.unit(seed, 40 + c, 1) * 2 * Math.PI;
                double h = GradientNoise.unit(seed, 40 + c, 2);
                double elev = (GradientNoise.unit(seed, 40 + c, 3) - 0.35) * (h < 0.85 ? 0.7 : 1.9);
                double wallY = -0.6 + 1.5 * h; // relative height on the wall: floor to high dome
                double ca = Math.cos(a), sa = Math.sin(a);
                double wx = ca * rx, wz = sa * rz, wy = wallY > 0 ? wallY * up : wallY * down * 1.2;
                // The start lies inside the dome (q = 0.9 along its direction): at 97% of the horizontal radius and a
                // height on the wall, the high starts lay outside the ellipsoid and a quarter of the conduits were cut off
                // from the hall (OpenAI 26.11: 100 of 389 ends not reachable from the centre).
                double qs = Math.sqrt(1 + Math.pow(wy / (wy > 0 ? up : down), 2)), in = 0.9 / qs;
                double sx = x + (wx * cos - wz * sin) * in, sz = z + (wx * sin + wz * cos) * in, sy = y + wy * in;
                double dx = (wx * cos - wz * sin), dz = (wx * sin + wz * cos), dl = Math.max(1e-6, Math.hypot(dx, dz));
                dx /= dl; dz /= dl;
                double len = 18 + 52 * GradientNoise.unit(seed, 40 + c, 4), bend = (GradientNoise.unit(seed, 40 + c, 5) - 0.5) * 1.2;
                double mx = sx + dx * len * 0.5, mz = sz + dz * len * 0.5, my = sy + Math.sin(elev) * len * 0.5;
                double bx = dx * Math.cos(bend) - dz * Math.sin(bend), bz = dx * Math.sin(bend) + dz * Math.cos(bend);
                double ex = mx + bx * len * 0.5, ez = mz + bz * len * 0.5, ey = my + Math.sin(elev * 0.6) * len * 0.5;
                // Radius 2.8-4 blocks: the whole cave network is sampled at the corners of 4 x 4 x 4 cells
                // (karst/body) and smoothed between them, and a conduit thinner than half the cell diagonal whose axis ran
                // between the corners vanished - 39% of the conduit ends could not be reached from their chamber, 40% of
                // the axis closed at radius 1.5-2, 3% at 2.5-3, 0.1% from 3 (audit 26.11 Opus N-5, code KO-04).
                double radius = 2.8 + 1.2 * GradientNoise.unit(seed, 40 + c, 6);
                int o = c * 10;
                conduits[o] = sx; conduits[o + 1] = sy; conduits[o + 2] = sz; conduits[o + 3] = mx; conduits[o + 4] = my; conduits[o + 5] = mz;
                conduits[o + 6] = ex; conduits[o + 7] = ey; conduits[o + 8] = ez; conduits[o + 9] = radius;
            }
            boxes = new double[conduitCount * 6];
            for (int c = 0; c < conduitCount; c++) {
                int o = c * 10, b = c * 6;
                boxes[b] = Math.min(conduits[o], Math.min(conduits[o + 3], conduits[o + 6]));
                boxes[b + 1] = Math.min(conduits[o + 1], Math.min(conduits[o + 4], conduits[o + 7]));
                boxes[b + 2] = Math.min(conduits[o + 2], Math.min(conduits[o + 5], conduits[o + 8]));
                boxes[b + 3] = Math.max(conduits[o], Math.max(conduits[o + 3], conduits[o + 6]));
                boxes[b + 4] = Math.max(conduits[o + 1], Math.max(conduits[o + 4], conduits[o + 7]));
                boxes[b + 5] = Math.max(conduits[o + 2], Math.max(conduits[o + 5], conduits[o + 8]));
                double rr = conduits[o + 9] + 1;
                cMinX = Math.min(cMinX, boxes[b] - rr); cMinY = Math.min(cMinY, boxes[b + 1] - rr); cMinZ = Math.min(cMinZ, boxes[b + 2] - rr);
                cMaxX = Math.max(cMaxX, boxes[b + 3] + rr); cMaxY = Math.max(cMaxY, boxes[b + 4] + rr); cMaxZ = Math.max(cMaxZ, boxes[b + 5] + rr);
            }
        }
        /** Box round all conduits of the room, their radius included. */
        private double cMinX = Double.MAX_VALUE, cMinY = Double.MAX_VALUE, cMinZ = Double.MAX_VALUE, cMaxX = -Double.MAX_VALUE, cMaxY = -Double.MAX_VALUE, cMaxZ = -Double.MAX_VALUE;
        boolean inConduitBox(double px, double py, double pz) {
            return px >= cMinX && px <= cMaxX && py >= cMinY && py <= cMaxY && pz >= cMinZ && pz <= cMaxZ;
        }
        /** Normalised ellipsoid distance (1 on the wall), dome above the centre, flattened floor below. */
        public double q(double px, double py, double pz) {
            double dx = px - x, dz = pz - z, u = dx * cos + dz * sin, v = -dx * sin + dz * cos, dy = py - y;
            double ry = dy > 0 ? up : down;
            return Math.sqrt((u / rx) * (u / rx) + (v / rz) * (v / rz) + (dy / ry) * (dy / ry));
        }
        public double reach() { return Math.max(rx, rz) + 75; }
        /** Conduit density: (distance / radius)^2 - 1 to the nearest conduit (negative inside one). */
        public double conduits(double px, double py, double pz) { return conduits(px, py, pz, Double.MAX_VALUE); }
        /** min(bound, conduit density): conduits whose bounding box already lies farther than the bound are skipped (the
         * distance to the box is a lower bound of the distance to the conduit) - the same minimum, a fraction of the work
         * (each sample near a chamber tested 80-180 segments). */
        double conduits(double px, double py, double pz, double bound) {
            double best = bound;
            for (int c = 0; c < conduitCount; c++) {
                int o = c * 10, b = c * 6;
                double r = conduits[o + 9];
                double bx = Math.max(0, Math.max(boxes[b] - px, px - boxes[b + 3]));
                double by = Math.max(0, Math.max(boxes[b + 1] - py, py - boxes[b + 4]));
                double bz = Math.max(0, Math.max(boxes[b + 2] - pz, pz - boxes[b + 5]));
                double low = (bx * bx + by * by + bz * bz) / (r * r) - 1;
                if (low >= best) continue;
                double d = Math.min(segment(px, py, pz, o, o + 3), segment(px, py, pz, o + 3, o + 6)) / r;
                best = Math.min(best, d * d - 1);
            }
            return best;
        }
        private double segment(double px, double py, double pz, int a, int b) {
            double ax = conduits[a], ay = conduits[a + 1], az = conduits[a + 2];
            double vx = conduits[b] - ax, vy = conduits[b + 1] - ay, vz = conduits[b + 2] - az;
            double wx = px - ax, wy = py - ay, wz = pz - az;
            double t = (wx * vx + wy * vy + wz * vz) / Math.max(1e-9, vx * vx + vy * vy + vz * vz);
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            double dx = wx - t * vx, dy = wy - t * vy, dz = wz - t * vz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final long seed;
    private final GradientNoise rubble;
    public CaveRooms(long seed) { this.seed = seed; rubble = new GradientNoise(seed ^ 0x2B992DDFA23249D6L); }

    public Room room(int i, int j) {
        // A sum, not a xor: for odd i and j, (-i)A ^ (-j)B == iA ^ jB (A, B odd), so a quarter of the cells repeated the
        // room of the cell mirrored through the origin (seed 31337: X1212 Z-355 and X-228 Z1085, the same chamber).
        long cs = GradientNoise.mix(seed ^ GradientNoise.mix(i * 0x9E3779B97F4A7C15L + j * 0xC2B2AE3D27D4EB4FL + 0x51ED27L));
        if (GradientNoise.unit(cs, 1, 0) >= CHANCE) return null;
        double x = (i + 0.2 + 0.6 * GradientNoise.unit(cs, 2, 0)) * CELL, z = (j + 0.2 + 0.6 * GradientNoise.unit(cs, 3, 0)) * CELL;
        double y = Y_LOW + (Y_HIGH - Y_LOW) * GradientNoise.unit(cs, 4, 0);
        double s = GradientNoise.unit(cs, 5, 0);
        double rx = 30 + 40 * s, rz = rx * (0.6 + 0.35 * GradientNoise.unit(cs, 6, 0));
        double up = 14 + 24 * GradientNoise.unit(cs, 7, 0) * (0.5 + 0.5 * s), down = 9 + 6 * GradientNoise.unit(cs, 8, 0);
        return new Room(x, y, z, rx, rz, up, down, GradientNoise.unit(cs, 9, 0) * Math.PI, cs);
    }

    /** Whether a chamber dome (its ellipsoid's bounding box, conduits aside) reaches into a block box. */
    public static boolean domeTouches(Room r, double x0, double y0, double z0, double x1, double y1, double z1) {
        double R = Math.max(r.rx, r.rz);
        return r.x + R >= x0 && r.x - R <= x1 && r.z + R >= z0 && r.z - R <= z1 && r.y + r.up >= y0 && r.y - r.down <= y1;
    }
    /** Whether any chamber dome reaches into a block box (rooms looked up through the cache). */
    public boolean domesTouch(double x0, double y0, double z0, double x1, double y1, double z1, java.util.function.BiFunction<Integer, Integer, Room> cache) {
        int i0 = (int) Math.floor((x0 - MAX_RADIUS) / CELL), i1 = (int) Math.floor((x1 + MAX_RADIUS) / CELL);
        int j0 = (int) Math.floor((z0 - MAX_RADIUS) / CELL), j1 = (int) Math.floor((z1 + MAX_RADIUS) / CELL);
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            Room r = cache.apply(i, j);
            if (r != null && domeTouches(r, x0, y0, z0, x1, y1, z1)) return true;
        }
        return false;
    }
    /** Largest horizontal dome radius (rx at s = 1). */
    public static final double MAX_RADIUS = 70;

    /** The nearest room that can reach (x, z), or null. */
    public Room near(double x, double z, java.util.function.BiFunction<Integer, Integer, Room> cache) {
        int i = (int) Math.floor(x / CELL), j = (int) Math.floor(z / CELL);
        Room best = null; double bestD = Double.MAX_VALUE;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Room r = cache.apply(i + di, j + dj);
            if (r == null) continue;
            double d = Math.hypot(x - r.x, z - r.z);
            if (d < r.reach() && d < bestD) { best = r; bestD = d; }
        }
        return best;
    }

    /** Room density at a point: negative inside the chamber (q^2 - 1, raised by rubble mounds on the floor) or inside a
     * conduit leaving its walls, positive outside. */
    public double density(Room r, double x, double y, double z) {
        double q = r.q(x, y, z);
        double d = q * q - 1;
        if (y < r.y) {
            // Breakdown rubble: mounds of fallen blocks raise the floor of the bowl. They are part of the room boundary
            // (the floor rock itself), not blocks laid on it. They raise the room only: a conduit leaving the floor stays
            // open (the rubble filled the low conduits).
            double mound = Math.max(0, rubble.fractal(x, z, 18, 2));
            d += 0.7 * mound * Math.min(1, (r.y - y) / r.down);
        }
        // Conduits are evaluated within the box round all of them, not up to q 4: a conduit running on past q 4 ended
        // in rock there (code audit 27.11 OpenAI K-04: 71 of 23 008 ends solid in the model).
        if (q > 0.85 && (q < 4 || r.inConduitBox(x, y, z))) d = r.conduits(x, y, z, d);
        return d;
    }

    /** Wall zone weight: 1 just outside the wall (q ~ 1.12), fading to 0 at q 0.9 and 1.7 (a honeycombed rock zone a
     * third to a half of the chamber radius thick). */
    public static double shell(Room r, double x, double y, double z) {
        double q = r.q(x, y, z);
        if (q < 0.9 || q > 1.7) return 0;
        return q < 1.12 ? (q - 0.9) / 0.22 : 1 - (q - 1.12) / 0.58;
    }
}
