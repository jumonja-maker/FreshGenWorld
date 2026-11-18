package dev.freshgenerator.core;

import java.util.Set;

/** Static ecological setting of the native biome's existing candidates. This is
 * not measured rainfall, fire history, tide, stand age or a new biome selector.
 * Population multipliers can only retain/thin native placement opportunities. */
public enum ForestHabitat {
    WOODLAND, CLOSED_FOREST, GRASSLAND, DRY_SAVANNA, DRY_SCRUB,
    SPARSE_TROPICAL, TROPICAL_CANOPY, WETLAND, MANGROVE, ALPINE_EDGE, COLD_OPEN, OPEN_GROVE;

    /** Explicitly native treeless/special habitats: their native vegetation is
     * preserved, including fungi, cave flora, ocean plants and desert shrubs. */
    public static final Set<String> UNMODIFIED = Set.of(
        "badlands","eroded_badlands","desert","beach","snowy_beach","stony_shore",
        "ocean","deep_ocean","cold_ocean","deep_cold_ocean","frozen_ocean","deep_frozen_ocean",
        "lukewarm_ocean","deep_lukewarm_ocean","warm_ocean","river","frozen_river",
        "frozen_peaks","jagged_peaks","stony_peaks","snowy_slopes","ice_spikes","mushroom_fields",
        "deep_dark","dripstone_caves","lush_caves","sulfur_caves","the_void",
        "basalt_deltas","crimson_forest","nether_wastes","soul_sand_valley","warped_forest",
        "end_barrens","end_highlands","end_midlands","small_end_islands","the_end");

    public static ForestHabitat forBiome(String id) {
        return switch(id) {
            case "minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest",
                 "minecraft:flower_forest","minecraft:dappled_forest","minecraft:taiga","minecraft:snowy_taiga",
                 "minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga" -> WOODLAND;
            case "minecraft:dark_forest","minecraft:pale_garden" -> CLOSED_FOREST;
            case "minecraft:plains","minecraft:sunflower_plains","minecraft:meadow" -> GRASSLAND;
            case "minecraft:savanna","minecraft:savanna_plateau" -> DRY_SAVANNA;
            case "minecraft:wooded_badlands","minecraft:windswept_savanna" -> DRY_SCRUB;
            case "minecraft:jungle","minecraft:bamboo_jungle" -> TROPICAL_CANOPY;
            case "minecraft:sparse_jungle" -> SPARSE_TROPICAL;
            case "minecraft:swamp" -> WETLAND;
            case "minecraft:mangrove_swamp" -> MANGROVE;
            case "minecraft:cherry_grove" -> OPEN_GROVE;
            case "minecraft:grove","minecraft:windswept_forest",
                 "minecraft:windswept_hills","minecraft:windswept_gravelly_hills" -> ALPINE_EDGE;
            case "minecraft:snowy_plains" -> COLD_OPEN;
            default -> null;
        };
    }
    public static ForestEcology.Family familyForBiome(String id) {
        if(forBiome(id)==null)return null;
        return switch(id) {
            case "minecraft:taiga","minecraft:snowy_taiga","minecraft:old_growth_pine_taiga",
                 "minecraft:old_growth_spruce_taiga","minecraft:grove","minecraft:snowy_plains" -> ForestEcology.Family.CONIFER;
            case "minecraft:jungle","minecraft:sparse_jungle","minecraft:bamboo_jungle","minecraft:mangrove_swamp" -> ForestEcology.Family.TROPICAL;
            case "minecraft:savanna","minecraft:savanna_plateau","minecraft:windswept_savanna",
                 "minecraft:wooded_badlands" -> ForestEcology.Family.DRY_WOODLAND;
            default -> ForestEcology.Family.TEMPERATE;
        };
    }
    public boolean waterTolerant(){return this==WETLAND||this==MANGROVE;}
    public boolean open(){return this==GRASSLAND||this==DRY_SAVANNA||this==DRY_SCRUB||this==SPARSE_TROPICAL||this==COLD_OPEN||this==OPEN_GROVE;}
    /** Wetland affinity is a terrain-envelope proxy, not a computed water table.
     * Native soil/water/survival filters still make the final placement decision. */
    public double lowlandAffinity(ForestEcology.Site site) {
        return switch(this){
            case MANGROVE -> 1-.92*ForestEcology.smooth(site.seaLevel()+4,site.seaLevel()+28,site.height());
            case WETLAND -> 1-.80*ForestEcology.smooth(site.seaLevel()+10,site.seaLevel()+54,site.height());
            default -> 1;
        };
    }
}
