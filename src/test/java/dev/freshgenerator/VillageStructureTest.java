package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** User request 24.11: fewer villages, never in clusters; outposts keep clear of the villages the world really has. */
class VillageStructureTest {
    @Test void villagesBecomeRarerWithoutClustersAndKeepTheirNativeStructures() throws Exception {
        var vanilla = new VanillaAssets();
        var pack = WorldgenCatalog.compile(vanilla);
        assertFalse(pack.containsKey("data/minecraft/worldgen/structure_set/villages.json"), "native set untouched");
        JsonObject nativeSet = vanilla.read(VanillaAssets.path("structure_set", "minecraft:villages")).getAsJsonObject();
        JsonObject set = pack.get(VanillaAssets.path("structure_set", VillageStructureCompiler.VILLAGES)).getAsJsonObject();
        assertEquals(nativeSet.get("structures"), set.get("structures"), "the native village structures and weights");
        JsonObject p = set.getAsJsonObject("placement"), n = nativeSet.getAsJsonObject("placement");
        assertEquals(n.get("salt"), p.get("salt"), "salt keeps /locate consistent");
        double ratio = Math.pow(n.get("spacing").getAsDouble() / p.get("spacing").getAsDouble(), 2);
        assertTrue(ratio > .45 && ratio < .65, "about half the native attempts: " + ratio);
        // The closest two villages of neighbouring cells: separation + 1 chunks (native 9 chunks: four could meet at a corner).
        assertTrue((p.get("separation").getAsInt() + 1) * 16 >= 300, "villages at least ~300 blocks apart");
        assertTrue(p.get("spacing").getAsInt() - p.get("separation").getAsInt() >= 20, "villages still placed at random within their cell");
        // User request 25.11: a chance per cell, not a village in every cell.
        assertEquals(VillageStructureCompiler.FREQUENCY, p.get("frequency").getAsDouble(), 1e-9);
        assertTrue(VillageStructureCompiler.FREQUENCY > 0.4 && VillageStructureCompiler.FREQUENCY < 0.8);
        JsonObject outposts = pack.get(VanillaAssets.path("structure_set", VillageStructureCompiler.OUTPOSTS)).getAsJsonObject();
        JsonObject nativeOutposts = vanilla.read(VanillaAssets.path("structure_set", "minecraft:pillager_outposts")).getAsJsonObject();
        assertEquals(VillageStructureCompiler.VILLAGES, outposts.getAsJsonObject("placement").getAsJsonObject("exclusion_zone").get("other_set").getAsString());
        JsonObject a = outposts.deepCopy(), b = nativeOutposts.deepCopy();
        a.getAsJsonObject("placement").remove("exclusion_zone"); b.getAsJsonObject("placement").remove("exclusion_zone");
        assertEquals(b, a, "outposts keep their native spread");
        assertEquals(VillageStructureCompiler.ROADS, pack.get(VanillaAssets.path("feature", VillageStructureCompiler.ROADS)).getAsJsonObject().get("type").getAsString());
        assertEquals(new JsonArray(), pack.get(VanillaAssets.path("placed_feature", VillageStructureCompiler.ROADS)).getAsJsonObject().get("placement"));
    }
}
