package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Volcanic vents instead of the native surface lava lakes (user request 26.11): the feature in the mod's namespace (a
 * no-op outside the current world; the mod adds it to deserts and badlands at the lakes step and switches the native
 * surface lake off there). */
public final class VolcanicVentCompiler {
    public static final String VENT = "freshgenerator:volcanic_vent";
    public static final Set<String> FILES = Set.of(VanillaAssets.path("feature", VENT), VanillaAssets.path("placed_feature", VENT));
    private VolcanicVentCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        for (String p : FILES) if (output.containsKey(p)) throw new IllegalStateException("Vent layer already present: " + p);
        JsonObject feature = new JsonObject();
        feature.addProperty("type", VENT);
        JsonObject placed = new JsonObject();
        placed.addProperty("feature", VENT);
        placed.add("placement", new JsonArray());
        output.put(VanillaAssets.path("feature", VENT), feature);
        output.put(VanillaAssets.path("placed_feature", VENT), placed);
    }
}
