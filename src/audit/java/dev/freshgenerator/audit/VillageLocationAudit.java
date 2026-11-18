package dev.freshgenerator.audit;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Audit-only bounded location discovery, then the existing block observer.
 * Frozen baseline chooses sites before candidate results are known. */
public final class VillageLocationAudit implements ModInitializer {
    private record Location(String id, int chunkX, int chunkZ) { }
    @Override public void onInitialize() {
        if (!"true".equals(System.getenv("FG_VILLAGE_AUDIT"))) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var gson = new Gson();
                var hints = gson.fromJson(Files.readString(Path.of(System.getenv("FG_VILLAGE_HINTS"))), Location[].class);
                var level = server.overworld();
                var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
                var registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                List<Location> selected = new ArrayList<>();
                List<Holder<Structure>> all = new ArrayList<>();
                for (String id : List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"))
                    all.add(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:" + id))));
                for (Location hint : hints) {
                    var preferred = registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(hint.id)));
                    var origin = new BlockPos(hint.chunkX * 16, 80, hint.chunkZ * 16);
                    var found = generator.findNearestMapStructure(level, HolderSet.direct(preferred), origin, 8, false);
                    if (found == null) found = generator.findNearestMapStructure(level, HolderSet.direct(all), origin, 8, false);
                    if (found == null) throw new AssertionError("No village in bounded hint search: " + hint);
                    var location = new Location(found.getSecond().unwrapKey().orElseThrow().identifier().toString(),
                        Math.floorDiv(found.getFirst().getX(), 16), Math.floorDiv(found.getFirst().getZ(), 16));
                    if (selected.contains(location)) throw new AssertionError("Repeated bounded village: " + location);
                    selected.add(location);
                    System.out.println("VILLAGE_RESOLVED " + hint + " -> " + location);
                }
                Files.writeString(Path.of(System.getenv("FG_VILLAGE_LOCATIONS")), gson.toJson(selected) + "\n");
            } catch (Exception e) { throw new IllegalStateException("Village location selection failed", e); }
        });
        new VillageWorldAudit().onInitialize();
    }
}
