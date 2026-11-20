package dev.freshgenerator.pack;

import com.google.gson.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/** 0.9.1: fewer underwater structures, adapted to the deeper ocean floor.
 * <ul>
 * <li>Shipwrecks, ocean ruins and monuments use wider spacing (about a third to half of
 *     the native count). Placement stays a native random spread, so /locate and explorer
 *     maps keep pointing at real structures.</li>
 * <li>Monuments keep the native definition. The 0.9.1 beard_box adaptation was withdrawn
 *     (audit R4): over a deep basin it built a thin plate 8-11 blocks above the floor and
 *     replaced the native foundation pillars, which reach the real floor (FULL control:
 *     0/368 grounded pillars with beard_box, 368/368 without), and it lifted a shipwreck into
 *     the monument body.</li>
 * </ul>
 * Structure sets are global data: they apply to every world loaded with the mod. */
public final class OceanStructureCompiler {
    public static final Map<String, int[]> SPACING = Map.of(
            "minecraft:shipwrecks", new int[]{40, 10},
            "minecraft:ocean_ruins", new int[]{36, 12},
            "minecraft:ocean_monuments", new int[]{48, 10});
    private OceanStructureCompiler() { }

    public static void apply(Map<String, JsonElement> output, VanillaAssets vanilla) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        try {
            for (var e : SPACING.entrySet()) {
                String path = VanillaAssets.path("structure_set", e.getKey());
                if (staged.containsKey(path)) throw new IllegalStateException("Ocean structure layer already present: " + e.getKey());
                JsonObject set = vanilla.read(path).deepCopy().getAsJsonObject();
                JsonObject placement = set.getAsJsonObject("placement");
                if (!"minecraft:random_spread".equals(placement.get("type").getAsString())) throw new IllegalStateException("Unexpected placement " + e.getKey());
                placement.addProperty("spacing", e.getValue()[0]);
                placement.addProperty("separation", e.getValue()[1]);
                staged.put(path, set);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        output.putAll(staged);
    }
}
