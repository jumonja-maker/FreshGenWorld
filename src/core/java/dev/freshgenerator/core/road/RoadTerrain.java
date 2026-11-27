package dev.freshgenerator.core.road;

/** The ground a road planner reads: the final ground height and the water of one column (in game the drainage
 * field, in tests a synthetic landscape). */
public interface RoadTerrain {
    /** Column kinds. BLOCKED is the planner's own (village buildings), never a terrain answer. */
    int DRY = 0, RIVER = 1, LAKE = 2, SEA = 3, BLOCKED = 4;
    int NO_WATER = Integer.MIN_VALUE;

    void sample(int x, int z, Sample out);

    final class Sample {
        /** Ground height: the solid ground stands below it (the top solid block is ceil(ground) - 1). */
        public double ground;
        /** Top water block of RIVER, LAKE and SEA columns, NO_WATER elsewhere. */
        public int water = NO_WATER;
        public int kind = DRY;
        /** Channel width of a river column (blocks). */
        public double width;
        /** Distance to the nearest river or lake water (blocks, 0 in it); infinite where unknown. A road keeps a few
         * blocks off the bank unless it crosses (audit 25.11 M-1: a path along a creek dipped into it and got a
         * 37-block bridge laid along the stream). */
        public double near = Double.POSITIVE_INFINITY;
    }
}
