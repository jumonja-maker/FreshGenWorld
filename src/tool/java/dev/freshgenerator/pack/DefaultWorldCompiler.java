package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** The native new-world default uses FreshGenerator without an extra selector.
 * Existing saves persist their own generator settings and are not rewritten. */
public final class DefaultWorldCompiler {
    public static final String NORMAL="data/minecraft/worldgen/world_preset/normal.json";
    private DefaultWorldCompiler() { }
    public static void apply(Map<String,JsonElement> output){
        var world=Objects.requireNonNull(output.get(VanillaAssets.path("world_preset",WorldgenCatalog.WORLD_ID)),"Missing public world");
        if(output.containsKey(NORMAL))throw new IllegalStateException("Default world alias already present");
        var tag=Objects.requireNonNull(output.get(WorldgenCatalog.NORMAL_TAG)).deepCopy().getAsJsonObject();
        if(!tag.getAsJsonArray("values").equals(JsonParser.parseString("[\"freshgenerator:world\"]")))
            throw new IllegalStateException("Expected one public FreshGenerator preset");
        // Keep the native alternatives. FreshGenerator replaces Default itself,
        // so its compatibility key need not appear as another cycling choice.
        tag.addProperty("replace",false);
        tag.add("values",new JsonArray());
        output.put(NORMAL,world.deepCopy());
        output.put(WorldgenCatalog.NORMAL_TAG,tag);
    }
}
