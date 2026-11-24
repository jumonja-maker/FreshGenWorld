package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1: fewer, adapted ocean structures; nothing else in structure registries changes. */
class OceanStructureTest {
    static final Set<String> REVIEWED = Set.of(
            "data/freshgenerator/worldgen/structure_set/shipwrecks.json",
            "data/freshgenerator/worldgen/structure_set/ocean_ruins.json",
            "data/freshgenerator/worldgen/structure_set/ocean_monuments.json");

    @Test void onlyTheReviewedOceanStructuresChangeAndTheyBecomeRarer() throws Exception {
        var vanilla = new VanillaAssets();
        var pack = WorldgenCatalog.compile(vanilla);
        var structural = new TreeSet<String>();
        for (String k : pack.keySet()) for (String r : List.of("structure", "structure_set", "biome", "placed_feature", "feature"))
            if (k.contains("/worldgen/" + r + "/") && !HydrologyCompiler.DECOR.contains(k)) structural.add(k);
        assertEquals(REVIEWED, structural);
        // Native 26.3 random-spread values; the native sets themselves stay untouched (audit 23.11).
        Map<String, int[]> nativeSpread = Map.of("shipwrecks", new int[]{24, 4, 165745295}, "ocean_ruins", new int[]{20, 8, 14357621},
                "ocean_monuments", new int[]{32, 5, 10387313});
        for (var e : nativeSpread.entrySet()) {
            assertFalse(pack.containsKey("data/minecraft/worldgen/structure_set/" + e.getKey() + ".json"), e.getKey() + " native set untouched");
            var p = pack.get("data/freshgenerator/worldgen/structure_set/" + e.getKey() + ".json").getAsJsonObject().getAsJsonObject("placement");
            assertTrue(p.get("spacing").getAsInt() > e.getValue()[0], e.getKey() + " spacing");
            assertTrue(p.get("spacing").getAsInt() > p.get("separation").getAsInt(), e.getKey() + " valid spread");
            assertEquals(e.getValue()[2], p.get("salt").getAsInt(), e.getKey() + " salt keeps /locate consistent");
            double ratio = Math.pow(e.getValue()[0] / p.get("spacing").getAsDouble(), 2);
            assertTrue(ratio < .5, e.getKey() + " keeps at most half the native count: " + ratio);
        }
        // Audit R7: native monument pillars reached a shipwreck's fence; wrecks and ruins keep clear of monuments.
        for (String s : List.of("shipwrecks", "ocean_ruins")) {
            var zone = pack.get("data/freshgenerator/worldgen/structure_set/" + s + ".json").getAsJsonObject()
                    .getAsJsonObject("placement").getAsJsonObject("exclusion_zone");
            assertEquals("freshgenerator:ocean_monuments", zone.get("other_set").getAsString(), s);
            assertTrue(zone.get("chunk_count").getAsInt() >= 3 && zone.get("chunk_count").getAsInt() <= 16, s + " covers the monument");
        }
        assertFalse(pack.get("data/freshgenerator/worldgen/structure_set/ocean_monuments.json").getAsJsonObject()
                .getAsJsonObject("placement").has("exclusion_zone"), "monuments keep their native placement");
        // Audit R4: the native monument (foundation pillars to the real floor) is kept.
        assertFalse(pack.containsKey("data/minecraft/worldgen/structure/monument.json"), "monument stays native");
    }
}
