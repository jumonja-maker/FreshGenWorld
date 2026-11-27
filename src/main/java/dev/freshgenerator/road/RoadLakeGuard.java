package dev.freshgenerator.road;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** No native lava lake on a village road (code audits 25.11 Fable K-04/M-03, Opus KO-03, OpenAI: paths led straight
 * into lava pools - seed 314159265 X3136..3141 Z-2312, 7 places on 56.7 km of roads; the planner reads the drainage
 * model's ground, and the lakes come from a decoration step before the road is drawn). The whole lake is skipped, as
 * for village plots (VillageLakeGuard), where its bounds (origin + [-8,7] X/Z, [-4,3] Y) reach the surface within MARGIN
 * blocks of a road's line or bridges. Roads are pure functions of the seed: the same answer in any generation order. */
public final class RoadLakeGuard {
    private RoadLakeGuard() { }
    static final int MARGIN = 3;

    /** The native surface lava lake (`lake_lava_surface`) is off in the current world: volcanic vents take its place in
     * deserts and badlands (VolcanicVents, user request 26.11). Lava lakes underground stay native. */
    public static boolean surfaceLakeOff(WorldGenLevel level, ChunkGenerator generator, net.minecraft.world.level.levelgen.placement.PlacedFeature placed) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        String id = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE).getResourceKey(placed)
                .map(k -> k.identifier().toString()).orElse("");
        if (!id.equals("minecraft:lake_lava_surface")) return false;
        return HydrologyDensity.field(level.getLevel().getChunkSource().randomState(), noise.generatorSettings().value()) != null;
    }

    /** Whether a native spring or lava lake at (x, z) would open in the wall of a rift of the current world (within
     * margin blocks beyond its walls; a lava lake reaches 8 blocks round its origin). The wall is a fresh cut 100-200
     * blocks high through the rock: the springs set into it and the lava lakes it laid open poured down it without end
     * (audit 26.11 Opus N-4, code KO-06: 227 blocks of spring water and 26 of lava in 15 s, seed 7391827364 X-8001
     * Z1690; a lava spring at Y13 beside the water of the rift floor, seed 248163264 X-6827 Z5512). Lava lakes deep in
     * the rock elsewhere stay native. */
    public static boolean inRiftWall(WorldGenLevel level, ChunkGenerator generator, BlockPos at, double margin) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        var landforms = dev.freshgenerator.landform.LandformDensity.world(level.getLevel().getChunkSource().randomState(),
                noise.generatorSettings().value());
        return landforms != null && landforms.nearRift(at.getX(), at.getZ(), margin);
    }

    public static boolean permit(WorldGenLevel level, ChunkGenerator generator, BlockPos origin) {
        if (!(level instanceof WorldGenRegion region) || !(generator instanceof NoiseBasedChunkGenerator noise)) return true;
        ServerLevel server = region.getLevel();
        if (!server.structureManager().shouldGenerateStructures()) return true;
        RandomState state = server.getChunkSource().randomState();
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return true;
        HydroField.Column c = new HydroField.Column();
        field.column(origin.getX(), origin.getZ(), c);
        if (origin.getY() + 3 < c.terrain - 8) return true; // a buried lake stays under the road
        RoadNetwork roads = RoadNetwork.of(server, noise, state);
        if (roads == null) return true;
        int x0 = origin.getX() - 8 - MARGIN, x1 = origin.getX() + 7 + MARGIN, z0 = origin.getZ() - 8 - MARGIN, z1 = origin.getZ() + 7 + MARGIN;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
                for (RoadNetwork.Road road : roads.roadsAt(state, field, cx, cz))
                    if (road.line().crosses(x0, z0, x1, z1)) return false;
        return true;
    }
}
