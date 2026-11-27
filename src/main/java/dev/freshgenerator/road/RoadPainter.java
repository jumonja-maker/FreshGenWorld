package dev.freshgenerator.road;

import dev.freshgenerator.core.road.Bridge;
import dev.freshgenerator.core.road.RoadHash;
import dev.freshgenerator.core.road.RoadLine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.levelgen.Heightmap;

/** Draws roads and bridges into one chunk from their plans (native blocks only).
 * <ul>
 * <li>A path block (dirt_path, the native village street) on grass, dirt, podzol, mud, moss and snow; smooth sandstone on
 *     the sand of deserts (the native desert street); bare rock, gravel and water stay as they are (the path seems to
 *     break off there).</li>
 * <li>Bridges in the wood of the place (spruce in the taiga, acacia in the savanna, ...) or in cobblestone, stone bricks
 *     or sandstone, aged (mossy, cracked) when worn or ruined.</li>
 * </ul>
 * Writes only the columns of its own chunk. */
final class RoadPainter {
    private RoadPainter() { }

    /** Blocks of one bridge. */
    record Materials(Block planks, Block slab, Block stairs, Block fence, Block log, Block stone, Block agedStone,
                     Block stoneStairs, Block agedStairs, Block wall, Block agedWall, Block stoneSlab) {
        static Materials of(Holder<Biome> biome, long seed) {
            String id = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
            String wood = dev.freshgenerator.core.road.RoadMaterials.wood(id), family = dev.freshgenerator.core.road.RoadMaterials.stone(id, seed);
            boolean sand = family.equals("sandstone"), bricks = family.equals("stone_brick");
            String s = family;
            String aged = sand ? "sandstone" : bricks ? "mossy_stone_brick" : "mossy_cobblestone";
            return new Materials(block(wood + "_planks"), block(wood + "_slab"), block(wood + "_stairs"), block(wood + "_fence"),
                    block(wood + "_log"),
                    block(sand ? "sandstone" : bricks ? "stone_bricks" : "cobblestone"),
                    block(sand ? "cut_sandstone" : bricks ? "mossy_stone_bricks" : "mossy_cobblestone"),
                    block(s + "_stairs"), block(aged + "_stairs"), block(s + "_wall"), block(aged + "_wall"), block(s + "_slab"));
        }
        private static Block block(String name) {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name));
            return b == Blocks.AIR ? Blocks.OAK_PLANKS : b;
        }
    }

    /** Draws a road's path and bridges into the chunk (cx, cz). */
    static boolean paint(WorldGenLevel level, RoadNetwork.Road road, int cx, int cz) {
        RoadLine line = road.line();
        int x0 = cx << 4, z0 = cz << 4;
        boolean[] placed = {false};
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        line.cover(x0, z0, (x, z, chance, point) -> {
            if (RoadHash.unit(line.id, x, z) >= chance || road.inVillage(x, z)) return;
            if (path(level, x, z, RoadHash.unit(line.id ^ 0x5A4D, x, z), at)) placed[0] = true;
        });
        for (int k = 0; k < line.bridges.size(); k++) {
            Bridge br = line.bridges.get(k);
            int[] box = br.box();
            if (box[2] < x0 || box[0] > x0 + 15 || box[3] < z0 || box[1] > z0 + 15) continue;
            Materials m = road.materials()[k];
            for (int z = Math.max(z0, box[1]); z <= Math.min(z0 + 15, box[3]); z++)
                for (int x = Math.max(x0, box[0]); x <= Math.min(x0 + 15, box[2]); x++) {
                    if (road.inVillage(x, z)) continue;
                    final int fx = x, fz = z;
                    br.column(x, z, (y, role, variant, dir, links) -> {
                        if (bridgeBlock(level, br, m, fx, y, fz, role, variant, dir, links, at)) placed[0] = true;
                    });
                }
        }
        return placed[0];
    }

    /** A path block on the top of a column; sand only every few blocks (a track, not a pavement). */
    static boolean path(WorldGenLevel level, int x, int z, double roll, BlockPos.MutableBlockPos at) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        at.set(x, top + 1, z);
        if (!level.getBlockState(at).isAir()) return false;
        at.set(x, top, z);
        BlockState s = level.getBlockState(at);
        BlockState out;
        if (soil(s)) out = Blocks.DIRT_PATH.defaultBlockState();
        else if (s.is(Blocks.SAND) && roll < 0.7) out = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        else return false;
        level.setBlock(at, out, 2);
        return true;
    }
    static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.MYCELIUM) || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.PALE_MOSS_BLOCK) || s.is(Blocks.SNOW_BLOCK);
    }
    private static boolean open(BlockState s) {
        return s.isAir() || s.getFluidState().is(FluidTags.WATER) || (s.canBeReplaced() && !s.getFluidState().is(FluidTags.LAVA));
    }
    private static Direction along(Bridge br, int dir) {
        return br.alongX ? (dir > 0 ? Direction.EAST : Direction.WEST) : (dir > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static boolean bridgeBlock(WorldGenLevel level, Bridge br, Materials m, int x, int y, int z, int role, int variant, int dir, int links,
                                       BlockPos.MutableBlockPos at) {
        if (y <= level.getMinY() || y >= level.getMaxY()) return false;
        boolean stone = br.type == Bridge.Type.STONE;
        boolean aged = variant == Bridge.AGED;
        switch (role) {
            case Bridge.APPROACH -> { return path(level, x, z, 0, at); }
            case Bridge.PILLAR, Bridge.POST_UNDER -> {
                BlockState s = role == Bridge.POST_UNDER ? m.fence().defaultBlockState()
                        : stone ? (aged ? m.agedStone() : m.stone()).defaultBlockState()
                        : m.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
                boolean any = false;
                for (int yy = y, n = 0; yy > level.getMinY() && n < 48; yy--, n++) {
                    at.set(x, yy, z);
                    BlockState here = level.getBlockState(at);
                    if (!open(here)) break;
                    BlockState put = s;
                    if (put.hasProperty(BlockStateProperties.WATERLOGGED)) put = put.setValue(BlockStateProperties.WATERLOGGED, here.getFluidState().is(FluidTags.WATER));
                    level.setBlock(at, put, 2);
                    any = true;
                }
                return any;
            }
            case Bridge.RUBBLE -> {
                for (int yy = y, n = 0; yy > level.getMinY() && n < 24; yy--, n++) {
                    at.set(x, yy - 1, z);
                    if (open(level.getBlockState(at))) continue;
                    at.set(x, yy, z);
                    if (!level.getBlockState(at).getFluidState().is(FluidTags.WATER)) return false;
                    level.setBlock(at, m.agedStone().defaultBlockState(), 2);
                    return true;
                }
                return false;
            }
            case Bridge.STEP -> {
                at.set(x, y, z);
                if (!open(level.getBlockState(at))) return false;
                Block b = aged ? Blocks.MOSSY_COBBLESTONE : RoadHash.unit(br.seed, x, z) < 0.5 ? Blocks.COBBLESTONE : Blocks.STONE;
                level.setBlock(at, b.defaultBlockState(), 2);
                // On the bed: fill one block of water below a stone.
                at.set(x, y - 1, z);
                if (level.getBlockState(at).getFluidState().is(FluidTags.WATER)) {
                    at.set(x, y - 2, z);
                    if (!open(level.getBlockState(at))) { at.set(x, y - 1, z); level.setBlock(at, b.defaultBlockState(), 2); }
                }
                return true;
            }
            default -> { }
        }
        at.set(x, y, z);
        BlockState here = level.getBlockState(at);
        // Ramps on land may replace the ground top (a stair up is cut into the edge of a high bank); everything else
        // stands in air or water.
        boolean onLand = role == Bridge.SUPPORT || role == Bridge.DECK || role == Bridge.STAIR;
        if (!open(here) && !(onLand && soil(here))) return false;
        boolean water = here.getFluidState().is(FluidTags.WATER);
        BlockState s = switch (role) {
            case Bridge.DECK -> variant == Bridge.SLAB ? (stone ? m.stoneSlab() : m.slab()).defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    : stone ? (aged ? m.agedStone() : m.stone()).defaultBlockState() : m.planks().defaultBlockState();
            case Bridge.SUPPORT -> stone ? (aged ? m.agedStone() : m.stone()).defaultBlockState() : m.planks().defaultBlockState();
            case Bridge.STAIR -> (stone ? (aged ? m.agedStairs() : m.stoneStairs()) : m.stairs()).defaultBlockState()
                    .setValue(StairBlock.FACING, along(br, dir)).setValue(StairBlock.HALF, Half.BOTTOM);
            case Bridge.ARCH -> (aged ? m.agedStairs() : m.stoneStairs()).defaultBlockState()
                    .setValue(StairBlock.FACING, along(br, dir)).setValue(StairBlock.HALF, Half.TOP);
            case Bridge.LOG -> m.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, br.alongX ? Direction.Axis.X : Direction.Axis.Z);
            case Bridge.BEAM -> m.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, br.alongX ? Direction.Axis.Z : Direction.Axis.X);
            case Bridge.RAIL -> stone ? wall(br, aged ? m.agedWall() : m.wall(), links) : fence(br, m.fence(), links);
            default -> null;
        };
        if (s == null) return false;
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) s = s.setValue(BlockStateProperties.WATERLOGGED, water);
        level.setBlock(at, s, 2);
        return true;
    }
    private static BlockState fence(Bridge br, Block fence, int links) {
        BlockState s = fence.defaultBlockState();
        boolean minus = (links & Bridge.LINK_MINUS) != 0, plus = (links & Bridge.LINK_PLUS) != 0;
        if (br.alongX) s = s.setValue(CrossCollisionBlock.WEST, minus).setValue(CrossCollisionBlock.EAST, plus);
        else s = s.setValue(CrossCollisionBlock.NORTH, minus).setValue(CrossCollisionBlock.SOUTH, plus);
        return s;
    }
    private static BlockState wall(Bridge br, Block wall, int links) {
        BlockState s = wall.defaultBlockState();
        boolean minus = (links & Bridge.LINK_MINUS) != 0, plus = (links & Bridge.LINK_PLUS) != 0;
        WallSide m = minus ? WallSide.LOW : WallSide.NONE, p = plus ? WallSide.LOW : WallSide.NONE;
        if (br.alongX) s = s.setValue(WallBlock.WEST, m).setValue(WallBlock.EAST, p);
        else s = s.setValue(WallBlock.NORTH, m).setValue(WallBlock.SOUTH, p);
        return s.setValue(WallBlock.UP, (links & Bridge.LINK_POST) != 0 || !(minus && plus));
    }
}
