package dev.freshgenerator.core.road;

/** Native block families of a bridge by the biome around it (block ids are built by the painter). */
public final class RoadMaterials {
    private RoadMaterials() { }

    /** The wood of a bridge by the biome path (minecraft:&lt;path&gt;). Names that contain another family's word go
     * first: cherry_grove and mangrove_swamp both contain "grove" (audit 25.11 M-3: both got spruce). */
    public static String wood(String biome) {
        if (biome.contains("cherry")) return "cherry";
        if (biome.contains("mangrove")) return "mangrove";
        if (biome.contains("pale_garden")) return "pale_oak";
        if (biome.contains("dark_forest")) return "dark_oak";
        if (biome.contains("birch")) return "birch";
        if (biome.contains("jungle") || biome.contains("bamboo")) return "jungle";
        if (biome.contains("savanna")) return "acacia";
        if (biome.contains("taiga") || biome.contains("snowy") || biome.contains("grove") || biome.contains("frozen")
                || biome.contains("ice") || biome.contains("peaks") || biome.contains("slopes")) return "spruce";
        return "oak";
    }

    /** The stone family: sandstone in deserts and badlands, otherwise cobblestone or stone bricks (seeded). */
    public static String stone(String biome, long seed) {
        if (biome.contains("desert") || biome.contains("badlands")) return "sandstone";
        return RoadHash.unit(seed, 31, 0) < 0.45 ? "stone_brick" : "cobblestone";
    }
}
