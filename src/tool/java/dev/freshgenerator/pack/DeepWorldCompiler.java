package dev.freshgenerator.pack;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** A deeper world (user request 26.11: "make the world 40-60 blocks deeper"): the current world reaches Y-112 instead
 * of Y-64 (48 blocks more, a multiple of 16; 432 blocks of height, the top stays at Y319).
 * <ul>
 * <li>The terrain settings generate from Y-112. Existing saves keep their own dimension type (Y-64): the game clamps
 * the generated range to the save's world, so their new chunks still end at their old bottom.</li>
 * <li>New worlds use the dimension type {@code freshgenerator:overworld}: the native overworld type with the deeper
 * range (every attribute copied from the game's own file). The native overworld type is not overridden: vanilla worlds
 * keep their depth.</li>
 * <li>Ores anchored to the bottom (diamond, lower redstone, lapis, small iron, tuff, geodes) follow the bottom down; the
 * rest keep their absolute heights. The lava level of the aquifers follows the bottom (mixin).</li>
 * </ul> */
public final class DeepWorldCompiler {
    public static final int MIN_Y = -112, HEIGHT = 432;
    public static final String TYPE = "freshgenerator:overworld";
    public static final String TYPE_PATH = "data/freshgenerator/dimension_type/overworld.json";
    private DeepWorldCompiler() { }

    public static void apply(Map<String, JsonElement> output, VanillaAssets vanilla) throws IOException {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(TYPE_PATH)) throw new IllegalStateException("Deep world already present");
        JsonObject type = vanilla.read("data/minecraft/dimension_type/overworld.json").deepCopy().getAsJsonObject();
        if (type.get("min_y").getAsInt() != -64 || type.get("height").getAsInt() != 384) throw new IllegalStateException("Unexpected native overworld type");
        type.addProperty("min_y", MIN_Y);
        type.addProperty("height", HEIGHT);
        type.addProperty("logical_height", HEIGHT);
        staged.put(TYPE_PATH, type);

        JsonObject settings = staged.get(SurfaceOnlyCompiler.SETTINGS).deepCopy().getAsJsonObject();
        JsonObject noise = settings.getAsJsonObject("noise");
        if (noise.get("min_y").getAsInt() != -64 || noise.get("height").getAsInt() != 384) throw new IllegalStateException("Unexpected terrain range");
        noise.addProperty("min_y", MIN_Y);
        noise.addProperty("height", HEIGHT);
        staged.put(SurfaceOnlyCompiler.SETTINGS, settings);

        for (String preset : List.of(WorldgenCatalog.WORLD_ID, WorldgenCatalog.CURRENT_SETTINGS)) {
            String p = VanillaAssets.path("world_preset", preset);
            JsonObject world = staged.get(p).deepCopy().getAsJsonObject();
            JsonObject overworld = world.getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld");
            if (!"minecraft:overworld".equals(overworld.get("type").getAsString())) throw new IllegalStateException("Unexpected overworld type in " + preset);
            overworld.addProperty("type", TYPE);
            staged.put(p, world);
        }
        output.putAll(staged);
    }
}
