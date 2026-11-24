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
 * <li>Shipwrecks and ocean ruins keep MONUMENT_EXCLUSION chunks away from every monument
 *     placement (native exclusion_zone, as pillager outposts use for villages). The native
 *     monument pillars reach down below the main box to the floor, so a box test alone let
 *     them stand on a shipwreck's fence (audit R7: 3 of 368 pillars).</li>
 * </ul>
 * Structure sets are global data. The adapted sets therefore live under their own ids (freshgenerator:shipwrecks,
 * ...: native structures, native salts, wider spacing) and StructureSets keeps each world on one family - the
 * current world on these, every other world (a vanilla save opened with the mod, other presets) on the untouched
 * native sets (audit 23.11: the overrides changed new chunks of vanilla worlds too). */
public final class OceanStructureCompiler {
    /** Native set id -> {spacing, separation} of its FreshGenerator counterpart. */
    public static final Map<String, int[]> SPACING = Map.of(
            "minecraft:shipwrecks", new int[]{40, 10},
            "minecraft:ocean_ruins", new int[]{36, 12},
            "minecraft:ocean_monuments", new int[]{48, 10});
    /** Chunks around a monument placement kept free of shipwrecks and ruins (the monument spans +-2 chunks). */
    public static final int MONUMENT_EXCLUSION = 4;
    public static final Set<String> EXCLUDED_NEAR_MONUMENTS = Set.of("minecraft:shipwrecks", "minecraft:ocean_ruins");
    /** The FreshGenerator counterpart of a native set id. */
    public static String adapted(String nativeId) { return "freshgenerator:" + nativeId.substring(nativeId.indexOf(':') + 1); }
    private OceanStructureCompiler() { }

    public static void apply(Map<String, JsonElement> output, VanillaAssets vanilla) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        try {
            for (var e : SPACING.entrySet()) {
                String path = VanillaAssets.path("structure_set", adapted(e.getKey()));
                if (staged.containsKey(path)) throw new IllegalStateException("Ocean structure layer already present: " + e.getKey());
                JsonObject set = vanilla.read(VanillaAssets.path("structure_set", e.getKey())).deepCopy().getAsJsonObject();
                JsonObject placement = set.getAsJsonObject("placement");
                if (!"minecraft:random_spread".equals(placement.get("type").getAsString())) throw new IllegalStateException("Unexpected placement " + e.getKey());
                placement.addProperty("spacing", e.getValue()[0]);
                placement.addProperty("separation", e.getValue()[1]);
                if (EXCLUDED_NEAR_MONUMENTS.contains(e.getKey())) {
                    if (placement.has("exclusion_zone")) throw new IllegalStateException("Unexpected native exclusion zone " + e.getKey());
                    JsonObject zone = new JsonObject();
                    zone.addProperty("chunk_count", MONUMENT_EXCLUSION);
                    zone.addProperty("other_set", adapted("minecraft:ocean_monuments"));
                    placement.add("exclusion_zone", zone);
                }
                staged.put(path, set);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        output.putAll(staged);
    }
}
