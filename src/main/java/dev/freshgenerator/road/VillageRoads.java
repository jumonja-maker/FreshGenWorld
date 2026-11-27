package dev.freshgenerator.road;

import com.mojang.serialization.MapCodec;
import dev.freshgenerator.core.Diagnostics;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.util.List;

/** Roads between some villages (user request 24.11), drawn per chunk from the world's road plan (RoadNetwork) at the
 * surface-structure step: after the village pieces, before trees and plants (which do not root in a path). A no-op
 * outside the current world. */
public record VillageRoads() implements Feature {
    public static final Identifier ID = Identifier.parse("freshgenerator:village_roads");
    public static final MapCodec<VillageRoads> CODEC = MapCodec.unit(new VillageRoads());

    @Override public MapCodec<VillageRoads> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        // No roads in a world without structures: they would lead to villages that are not there (audit 25.11 K1).
        if (!level.getLevel().structureManager().shouldGenerateStructures()) return false;
        RandomState state = level.getLevel().getChunkSource().randomState();
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return false;
        RoadNetwork network = RoadNetwork.of(level.getLevel(), noise, state);
        if (network == null) return false;
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        List<RoadNetwork.Road> roads;
        try {
            roads = network.roadsAt(state, field, cx, cz);
        } catch (RuntimeException e) {
            Diagnostics.warnOnce("road-chunk", "FreshGenerator: village roads skipped in a chunk (" + e + ")");
            return false;
        }
        boolean placed = false;
        for (RoadNetwork.Road r : roads) placed |= RoadPainter.paint(level, r, cx, cz);
        return placed;
    }
}
