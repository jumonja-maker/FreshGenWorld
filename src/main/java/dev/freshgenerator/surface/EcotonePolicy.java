package dev.freshgenerator.surface;

import java.util.Set;
import net.minecraft.resources.Identifier;

/** Substrate scope. Biome precipitation and native snow palettes stay authoritative. */
public final class EcotonePolicy {
    public static final String SETTINGS="freshgenerator:highlands_v2";
    public static final Identifier SEED_ID=Identifier.parse("freshgenerator:ecotone_surface");
    // Biomes whose base temperature always snows (snowy plains/taiga, grove,
    // snowy slopes, jagged/frozen peaks) never reach this rule: the snow guard
    // returns their native palette first. They are not listed as eligible.
    public static final Set<String> BIOMES=Set.of("minecraft:plains","minecraft:sunflower_plains",
        "minecraft:forest","minecraft:flower_forest","minecraft:birch_forest","minecraft:old_growth_birch_forest",
        "minecraft:dark_forest","minecraft:meadow","minecraft:cherry_grove","minecraft:windswept_forest",
        "minecraft:windswept_hills","minecraft:windswept_gravelly_hills","minecraft:stony_peaks",
        "minecraft:taiga","minecraft:pale_garden","minecraft:dappled_forest");
    /** 0.9.1: one slope-stability policy for every grass-topped land surface. On a
     * near-vertical wall soil does not stay; the native palette remains below that
     * slope. Ocean IDs appear here because the native 4-block biome lattice can lag
     * a steep coastal wall and label a dry cliff "ocean". Beaches, badlands, deserts
     * and snowy palettes keep their own native materials. */
    public static final Set<String> CLIFFS=Set.of("minecraft:plains","minecraft:sunflower_plains",
        "minecraft:forest","minecraft:flower_forest","minecraft:birch_forest","minecraft:old_growth_birch_forest",
        "minecraft:dark_forest","minecraft:pale_garden","minecraft:dappled_forest","minecraft:meadow",
        "minecraft:cherry_grove","minecraft:windswept_forest","minecraft:windswept_hills","minecraft:taiga",
        "minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga","minecraft:river",
        "minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean","minecraft:deep_lukewarm_ocean",
        "minecraft:warm_ocean","minecraft:cold_ocean","minecraft:deep_cold_ocean",
        // 0.9.1 audit B2: tropical and dry grassland families follow the same policy.
        "minecraft:jungle","minecraft:sparse_jungle","minecraft:bamboo_jungle",
        "minecraft:savanna","minecraft:savanna_plateau","minecraft:windswept_savanna");
    /** 0.9.1: the seabed regime (sediment mosaic, talus, exposed rock) for every ocean. */
    public static final Set<String> SEABEDS=Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:cold_ocean",
        "minecraft:deep_cold_ocean","minecraft:frozen_ocean","minecraft:deep_frozen_ocean","minecraft:lukewarm_ocean",
        "minecraft:deep_lukewarm_ocean","minecraft:warm_ocean");
    /** Always-snowing IDs; kept explicit so a test can prove they stay unreachable. */
    public static final Set<String> ALWAYS_SNOW=Set.of("minecraft:snowy_plains","minecraft:snowy_taiga",
        "minecraft:grove","minecraft:snowy_slopes","minecraft:jagged_peaks","minecraft:frozen_peaks");
    // The coastal substrate layer is separate from terrestrial vegetation.
    // Warm coral water, rivers, wetlands and special palettes retain native rules.
    public static final Set<String> COASTS=Set.of("minecraft:beach","minecraft:snowy_beach","minecraft:stony_shore",
        "minecraft:ocean","minecraft:cold_ocean","minecraft:frozen_ocean","minecraft:lukewarm_ocean",
        "minecraft:deep_ocean","minecraft:deep_cold_ocean","minecraft:deep_frozen_ocean","minecraft:deep_lukewarm_ocean");
    public static final Set<String> FOREST_FLOORS=Set.of("minecraft:forest","minecraft:flower_forest",
        "minecraft:birch_forest","minecraft:old_growth_birch_forest","minecraft:taiga",
        "minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga","minecraft:dark_forest");
    // Only the woodland variant gains rooting-soil pockets. Open and eroded
    // badlands retain their native terracotta surface and treeless character.
    public static final Set<String> WOODED_MESAS=Set.of("minecraft:wooded_badlands");
    private EcotonePolicy() { }
}
