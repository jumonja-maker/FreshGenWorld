package dev.freshgenerator.pack;

import com.google.gson.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/** Villages and the roads between them (user request 24.11).
 * <ul>
 * <li>Fewer villages, never in clusters: the native spread (34 chunks, separation 8: four villages could meet within
 *     ~150 blocks at a cell corner - "five villages on one meadow") becomes 46/20 - about 45% fewer attempts and at
 *     least 21 chunks (336 blocks) between two villages. Native village structures, salt and weights: /locate keeps
 *     pointing at real villages.</li>
 * <li>Pillager outposts keep their native spread; their exclusion zone follows the adapted village set (the native one
 *     pointed at the native villages, which the current world no longer has).</li>
 * <li>A feature in the mod's namespace draws the roads (VillageRoads, a no-op outside the current world).</li>
 * </ul>
 * Like the ocean sets, the adapted sets live under their own ids and StructureSets keeps each world on one family. */
public final class VillageStructureCompiler {
    public static final int SPACING = 46, SEPARATION = 20;
    /** Chance that a cell holds a village at all (native frequency, default reduction: a hash of the seed, salt and
     * cell). User request 25.11: on open plains nearly every cell got its village, a visible "every so many cells"
     * rhythm; 40% of the cells stay empty at random - neighbours in adjacent cells next to gaps of two or three. */
    public static final double FREQUENCY = 0.6;
    public static final String VILLAGES = "freshgenerator:villages", OUTPOSTS = "freshgenerator:pillager_outposts",
            ROADS = "freshgenerator:village_roads";
    public static final Set<String> SETS = Set.of(VanillaAssets.path("structure_set", VILLAGES), VanillaAssets.path("structure_set", OUTPOSTS));
    public static final Set<String> FEATURES = Set.of(VanillaAssets.path("feature", ROADS), VanillaAssets.path("placed_feature", ROADS));
    private VillageStructureCompiler() { }

    public static void apply(Map<String, JsonElement> output, VanillaAssets vanilla) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : SETS) if (staged.containsKey(p)) throw new IllegalStateException("Village layer already present: " + p);
        try {
            JsonObject villages = vanilla.read(VanillaAssets.path("structure_set", "minecraft:villages")).deepCopy().getAsJsonObject();
            JsonObject placement = villages.getAsJsonObject("placement");
            if (!"minecraft:random_spread".equals(placement.get("type").getAsString()) || placement.has("exclusion_zone")
                    || placement.has("frequency")) throw new IllegalStateException("Unexpected native village placement");
            placement.addProperty("spacing", SPACING);
            placement.addProperty("separation", SEPARATION);
            placement.addProperty("frequency", FREQUENCY);
            staged.put(VanillaAssets.path("structure_set", VILLAGES), villages);
            JsonObject outposts = vanilla.read(VanillaAssets.path("structure_set", "minecraft:pillager_outposts")).deepCopy().getAsJsonObject();
            JsonObject zone = outposts.getAsJsonObject("placement").getAsJsonObject("exclusion_zone");
            if (zone == null || !"minecraft:villages".equals(zone.get("other_set").getAsString())) throw new IllegalStateException("Unexpected native outpost exclusion");
            zone.addProperty("other_set", VILLAGES);
            staged.put(VanillaAssets.path("structure_set", OUTPOSTS), outposts);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        JsonObject feature = new JsonObject();
        feature.addProperty("type", ROADS);
        staged.put(VanillaAssets.path("feature", ROADS), feature);
        JsonObject placed = new JsonObject();
        placed.addProperty("feature", ROADS);
        placed.add("placement", new JsonArray());
        staged.put(VanillaAssets.path("placed_feature", ROADS), placed);
        output.putAll(staged);
    }
}
