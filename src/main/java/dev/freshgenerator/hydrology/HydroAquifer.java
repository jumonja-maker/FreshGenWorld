package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

import java.util.Objects;

/** Rivers and lakes enter the native noise fill at their own levels (open air above the carved ground only);
 * caves and the sea stay native. A column below a higher neighbour by two or more blocks gets the falling
 * water of the waterfall right away (visible before any fluid tick and in distant views); only step lips and
 * curtains are scheduled for fluid updates, flat lake and river surfaces stay still. */
public final class HydroAquifer implements Aquifer {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState FLOWING = WATER.setValue(LiquidBlock.LEVEL, 1);
    private static final BlockState FALLING = WATER.setValue(LiquidBlock.LEVEL, 8);
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState(), CRUST = Blocks.MAGMA_BLOCK.defaultBlockState(),
            AIR = Blocks.AIR.defaultBlockState();
    /** Depth under the predicted ground down to which the open air of a rift stays dry, and the margin beyond its walls
     * (the terrain grid of 4 blocks lowers the ground a little past the wall: 1-block columns of sea water clung there). */
    private static final int RIFT_DRY = 6;
    /** Chamber ellipsoid parameter within which the aquifer keeps the air dry (the dome is q < 1; its rim pockets and
     * the first blocks of its conduits to 1.35 - a lava pocket at q 1.14-1.31 stood against the dome air). */
    private static final double DOME_DRY = 1.35;
    /** Band beyond DOME_DRY where a fluid block touching the dry zone is sealed (|grad q| is at most about 1/9 per block). */
    private static final double DOME_SEAL = 0.15;
    private final dev.freshgenerator.landform.CaveRoomDensity.Model rooms;
    private final int deepLava;
    private dev.freshgenerator.core.CaveRooms.Room room;
    private static final double RIFT_MARGIN = dev.freshgenerator.core.Landforms.RIFT_DRY_MARGIN;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
    private final Aquifer nativeAquifer;
    private final HydroField field;
    /** Landforms of the world (active volcanoes hold a lava lake in their summit crater), or null. */
    private final dev.freshgenerator.landform.LandformDensity.World landforms;
    private int lavaTop = Integer.MIN_VALUE;
    private boolean crust, rift;
    private final HydroField.Column column = new HydroField.Column(), neighbour = new HydroField.Column();
    private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    private int ground, water, curtain, surfaceLevel;
    /** Top of the waterfall a river or lake pours down the wall of a rift in this column, or NO_WATER. */
    private int riftFall = HydroModel.NO_WATER;
    private boolean lowerNeighbour;
    private boolean ours, schedule;

    public HydroAquifer(Aquifer nativeAquifer, HydroField field) { this(nativeAquifer, field, null); }
    public HydroAquifer(Aquifer nativeAquifer, HydroField field, dev.freshgenerator.landform.LandformDensity.World landforms) {
        this(nativeAquifer, field, landforms, null, Integer.MIN_VALUE);
    }
    /** With the chamber model and the top of the deep lava (the native lava level near the bottom of the world). */
    public HydroAquifer(Aquifer nativeAquifer, HydroField field, dev.freshgenerator.landform.LandformDensity.World landforms,
                        dev.freshgenerator.landform.CaveRoomDensity.Model rooms, int deepLava) {
        this.rooms = rooms;
        this.deepLava = deepLava;
        this.nativeAquifer = Objects.requireNonNull(nativeAquifer);
        this.field = Objects.requireNonNull(field);
        this.landforms = landforms;
    }

    private void load(int x, int z) {
        if (x == lastX && z == lastZ) return;
        lastX = x; lastZ = z;
        room = rooms == null ? null : rooms.near(x, z);
        field.column(x, z, column);
        ground = (int) Math.ceil(column.terrain);
        double lava = landforms == null ? Double.NaN : landforms.lavaAt(x, z);
        lavaTop = Double.isNaN(lava) ? Integer.MIN_VALUE : (int) Math.floor(lava);
        crust = lavaTop != Integer.MIN_VALUE && landforms.crustAt(x, z);
        rift = landforms != null && landforms.nearRift(x, z, RIFT_MARGIN);
        // Rivers and lakes pour into a rift: a column of the rift (dry, its ground well below) beside river or lake
        // water outside it carries a waterfall from that water down its wall (the river ended in a pool at the brink).
        riftFall = HydroModel.NO_WATER;
        // Also beside the wall, where the terrain grid lowers the ground a little outside the rift (a column at Y52 beside
        // a river at Y63 spilled into the rift, seed 424242 X-7796 Z-1170).
        if (rift && !column.wet()) {
            for (int d = 0; d < 4; d++) {
                field.column(x + DX[d], z + DZ[d], neighbour);
                // Any river or lake water three blocks or more above this dry column of the rift: its lip (the pool at
                // the brink can lie in the wall band of the rift itself).
                if (!neighbour.wet() || neighbour.water < ground + 3 || neighbour.water <= riftFall) continue;
                riftFall = neighbour.water;
            }
        }
        water = column.wet() ? column.water : HydroModel.NO_WATER;
        // In and beside a rift, and in a crater bowl closed above the sea level, the ground below the sea level is no
        // sea (seaFree; rifts stay away from other low ground): a plunge pool at Y27 counted as sea water at Y62, and
        // the river at Y63 above it got no waterfall (seed 424242 X-7793 Z-1168). Each neighbour by its own rule.
        int self = column.effectiveWater();
        surfaceLevel = Math.max(water, column.effectiveWater());
        curtain = HydroModel.NO_WATER;
        lowerNeighbour = false;
        for (int d = 0; d < 4; d++) {
            field.column(x + DX[d], z + DZ[d], neighbour);
            int w = neighbour.effectiveWater();
            if (w == HydroModel.NO_WATER || !neighbour.wetIncludingSea()) continue;
            if (self != HydroModel.NO_WATER && w >= self + 2 && w > curtain) curtain = w;
            if (water != HydroModel.NO_WATER && w < water) lowerNeighbour = true;
        }
        if (water != HydroModel.NO_WATER && !lowerNeighbour) {
            // a dry neighbour lower than our surface: water will run over the edge (lip)
            for (int d = 0; d < 4 && !lowerNeighbour; d++) {
                field.column(x + DX[d], z + DZ[d], neighbour);
                if (!neighbour.wetIncludingSea() && neighbour.terrain <= water) lowerNeighbour = true;
            }
        }
    }

    @Override
    public BlockState computeSubstance(int x, int y, int z, double density) {
        ours = false; schedule = false;
        if (density <= 0) {
            load(x, z);
            if (y >= ground - 1) {
                // The lava lake of an active summit crater (a sink of the drainage network: no water there).
                // Its surface is partly crusted over: magma plates between glowing cracks.
                if (y <= lavaTop) { ours = true; schedule = false; return y == lavaTop && crust ? CRUST : LAVA; }
                if (water != HydroModel.NO_WATER && y <= water) {
                    ours = true; schedule = y == water && lowerNeighbour;
                    return WATER;
                }
                int surface = surfaceLevel;
                if (curtain != HydroModel.NO_WATER && surface != HydroModel.NO_WATER && y > surface && y <= curtain) {
                    ours = true; schedule = true;
                    return y == curtain ? FLOWING : FALLING;
                }
            }
            // A rift is dry inside: its floor may lie far below the sea level, where the native aquifer filled it with
            // sea water as if it were the sea. The water the drainage model brings in (rivers, falls) is placed above.
            // So is a crater bowl closed above the sea level that the drainage model keeps dry (a desert crater stood
            // full at Y62: audit 26.11 Opus N-9).
            // The great chambers are dry above the deep lava: the native aquifer gave each of its cells its own level
            // and fluid with a barrier of one block between them, and in a dome tens of blocks high that hung a lake of
            // water or lava over open air on a shelf of one block (audit 26.11 Opus N-8: 13 blocks of water over one
            // block of deepslate over 13 blocks of air, seed 7391827364 X1170 Z1094; 12 976 water and 5621 lava cells in
            // one dome, X925 Y-72 Z488). Lava pools on the floor at the deep lava level stay.
            if (room != null && y > deepLava && y < ground - 8) {
                double q = room.q(x, y, z);
                if (q < DOME_DRY) { ours = true; schedule = false; return AIR; }
                // Its border is sealed: the native aquifer cut there gave water or lava standing open against the dry air,
                // and it poured down the dome walls - 772 open sources round one chamber against 63 before, 1586 blocks
                // of water and lava in 15 s (audit 27.11 Opus N-1, code KO-01: seed 7391827364 X1182 Y-40 Z1094). A fluid
                // block with a face on the dry zone becomes rock; fluid farther out keeps its native barriers.
                if (q < DOME_DRY + DOME_SEAL) {
                    BlockState native_ = nativeAquifer.computeSubstance(x, y, z, density);
                    if (native_ != null && !native_.getFluidState().isEmpty() && touchesDryDome(x, y, z)) {
                        ours = true; schedule = false; return null;
                    }
                    return native_;
                }
            }
            if (column.seaFree && y >= ground - RIFT_DRY) {
                if (riftFall != HydroModel.NO_WATER && y >= ground - 1 && y <= riftFall) {
                    ours = true; schedule = true;
                    return y == riftFall ? FLOWING : FALLING;
                }
                ours = true; schedule = false; return AIR;
            }
        }
        return nativeAquifer.computeSubstance(x, y, z, density);
    }

    /** Whether a face neighbour of (x, y, z) lies in the dry zone of the chamber (room set by load). */
    private boolean touchesDryDome(int x, int y, int z) {
        for (int d = 0; d < 6; d++) {
            int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0), nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
            if (ny > deepLava && room.q(nx, ny, nz) < DOME_DRY) return true;
        }
        return false;
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return ours ? schedule : nativeAquifer.shouldScheduleFluidUpdate();
    }
}
