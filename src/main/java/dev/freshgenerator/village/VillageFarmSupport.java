package dev.freshgenerator.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.HashSet;
import java.util.function.IntPredicate;

/** Foundations under actual native crop columns, after terrain-matching placement.
 * A village street may contain a farm; it must not be flattened as a rigid house.
 * No adjacent columns, irrigation cells, lava, caves or unanchored deep supports. */
public final class VillageFarmSupport {
    public static final int MAX_ANCHOR_DEPTH = 16;
    private VillageFarmSupport() { }

    public static boolean eligibleTemplate(String id) {
        if (id == null) return false;
        for (String village : new String[]{"plains", "desert", "savanna", "snowy", "taiga"})
            if (id.startsWith("minecraft:village/" + village + "/")) return true;
        return false;
    }

    /** Zero means no safe infill. Probe completely before making any writes. */
    public static int supportDepth(boolean cropColumn, int availableDepth, IntPredicate solid, IntPredicate fillable) {
        if (!cropColumn) return 0;
        for (int depth = 1; depth <= Math.min(MAX_ANCHOR_DEPTH, availableDepth); depth++) {
            if (solid.test(depth)) return depth - 1;
            if (!fillable.test(depth)) return 0;
        }
        return 0;
    }

    public static void support(WorldGenRegion region, StructureTemplate template, BlockPos origin,
                               Rotation rotation, BoundingBox writeBounds) {
        var placement = new StructurePlaceSettings().setRotation(rotation);
        var visited = new HashSet<Long>();
        for (var cell : template.filterBlocks(origin, placement, Blocks.FARMLAND)) {
            int x = cell.pos().getX(), z = cell.pos().getZ();
            if (x < writeBounds.minX() || x > writeBounds.maxX() || z < writeBounds.minZ() || z > writeBounds.maxZ()) continue;
            long key = ((long)x << 32) | (z & 0xffffffffL);
            if (!visited.add(key)) continue;
            // Terrain matching changes Y per column. Inspect the final surface,
            // including crop height, rather than the template's unprojected Y.
            int top = Math.min(region.getMaxY(), region.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            for (int y = top; y >= Math.max(region.getMinY() + 1, top - 16); y--) {
                var crop = new BlockPos(x, y, z);
                if (!region.getBlockState(crop).is(Blocks.FARMLAND)) continue;
                int depth = supportDepth(true, y - region.getMinY(),
                    d -> region.getBlockState(crop.below(d)).isSolid(),
                    d -> { var b = region.getBlockState(crop.below(d)); return b.isAir() || b.is(Blocks.WATER); });
                for (int d = 1; d <= depth; d++)
                    region.setBlock(crop.below(d), (d <= 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState(), 2);
                break;
            }
        }
    }
}
