package dev.freshgenerator.road;

import dev.freshgenerator.core.road.RoadRouter;
import dev.freshgenerator.hydrology.VillageWater;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import java.util.ArrayList;
import java.util.List;

/** A village as the road planner sees it: its buildings (roads keep off them), its streets and street ends (where a
 * road starts), its centre and its kind. Plain data of the village the world really builds (the same Structure.generate,
 * the same water rule). */
record VillagePlan(int x, int z, int y, String type, int[][] buildings, int[][] streets, List<RoadRouter.Exit> exits) {
    /** Street ends are the preferred starts; other street pieces cost a little more. */
    static final double STREET_BIAS = 12;

    static VillagePlan of(StructureStart start, String structureId) {
        List<int[]> buildings = new ArrayList<>(), streets = new ArrayList<>();
        List<RoadRouter.Exit> exits = new ArrayList<>();
        int y = Integer.MIN_VALUE;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            int[] box = {b.minX(), b.minZ(), b.maxX(), b.maxZ()};
            int kind = VillageWater.kind(piece);
            if (kind == VillageWater.STREET) {
                streets.add(box);
                boolean end = piece instanceof PoolElementStructurePiece p && p.getElement() instanceof SinglePoolElement s
                        && s.getTemplateLocation().getPath().contains("/terminators/");
                exits.add(new RoadRouter.Exit((b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2, end ? 0 : STREET_BIAS));
            } else {
                buildings.add(box);
                if (kind == VillageWater.CENTRE && y == Integer.MIN_VALUE) y = b.minY();
            }
        }
        BoundingBox all = start.getBoundingBox();
        if (y == Integer.MIN_VALUE) y = all.minY();
        int named = structureId.indexOf("village_");
        String type = named >= 0 ? structureId.substring(named + "village_".length()) : structureId; // a datapack's village keeps its id
        return new VillagePlan((all.minX() + all.maxX()) / 2, (all.minZ() + all.maxZ()) / 2, y, type,
                buildings.toArray(new int[0][]), streets.toArray(new int[0][]), List.copyOf(exits));
    }

    /** Whether a column is inside a building (with a margin). */
    boolean building(int x, int z, int margin) {
        for (int[] b : buildings) if (x >= b[0] - margin && x <= b[2] + margin && z >= b[1] - margin && z <= b[3] + margin) return true;
        return false;
    }
    /** Whether a column lies within margin blocks of any piece of the village (its ground is graded there). */
    boolean near(int x, int z, int margin) {
        if (building(x, z, margin)) return true;
        for (int[] b : streets) if (x >= b[0] - margin && x <= b[2] + margin && z >= b[1] - margin && z <= b[3] + margin) return true;
        return false;
    }
    /** Whether a column is inside any piece of the village (roads are not drawn there). */
    boolean inside(int x, int z) {
        if (building(x, z, 1)) return true;
        for (int[] b : streets) if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return true;
        return false;
    }
}
