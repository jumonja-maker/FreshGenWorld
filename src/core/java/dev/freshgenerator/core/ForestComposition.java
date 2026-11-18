package dev.freshgenerator.core;

import java.util.List;
import java.util.Objects;

/** Chooses existing checked tree variants, never locations or extra attempts.
 * Stand composition is spatially coherent; individual recruitment is seeded.
 * Neighbour influences are limited to compatible ordinary woodland palettes.
 */
public final class ForestComposition {
    private ForestComposition() { }
    public static final List<String> VARIANTS=List.of("oak_bees_0002_leaf_litter",
        "fancy_oak_bees_0002_leaf_litter","birch_bees_0002_leaf_litter","super_birch_bees_0002",
        "spruce_checked","pine_checked","mega_pine_checked","mega_spruce_checked",
        "jungle_tree","mega_jungle_tree_checked","jungle_bush","acacia_checked","oak_checked");

    public static String select(long seed,int x,int z,String biome,String[] neighbours,
                                ForestEcology.Family family,ForestEcology.Site site) {
        Objects.requireNonNull(biome);Objects.requireNonNull(neighbours);
        Objects.requireNonNull(family);Objects.requireNonNull(site);
        String chosen=biome;double roll=unit(seed^0x7D137A31L,x,z),sum=0;
        for(int i=0;i<neighbours.length;i++) {
            String other=neighbours[i];
            if(other==null||other.equals(biome)||!compatible(biome,other,site))continue;
            sum+=i<4?.075:.025;
            if(roll<sum){chosen=other;break;}
        }
        // Preserve the native selector, including its uncommon/fallen variants,
        // for 45% of interior candidates. A selected edge contribution is explicit.
        if(chosen.equals(biome)&&unit(seed^0x81F2A73BL,x,z)<.45)return null;
        var habitat=ForestHabitat.forBiome(biome);
        var ecology=ForestEcology.sample(seed,x,z,family,site,habitat==null?ForestHabitat.WOODLAND:habitat);
        double stand=(ForestEcology.noise(seed^0x4771L,x/83.0,z/83.0)+1)*.5;
        double vigor=(1-ecology.stress())*(.55+.45*ecology.stand());
        double size=unit(seed^0x91BC4A1DL,x,z),species=unit(seed^0x62C31E7FL,x,z);
        boolean big=size<.10+.55*vigor&&site.slope()<.55&&site.convexity()<1.8;
        // A neighbouring old-growth palette contributes its species, not its
        // giant stand architecture. Keep mega conifers inside native old growth.
        boolean oldGrowthConifer=biome.equals("minecraft:old_growth_pine_taiga")
            ||biome.equals("minecraft:old_growth_spruce_taiga");
        return switch(chosen) {
            case "minecraft:forest" -> species<.12+.43*stand
                ? "birch_bees_0002_leaf_litter"
                : big?"fancy_oak_bees_0002_leaf_litter":"oak_bees_0002_leaf_litter";
            case "minecraft:birch_forest" -> size<.06+.12*vigor&&site.slope()<.45
                ?"super_birch_bees_0002":"birch_bees_0002_leaf_litter";
            case "minecraft:old_growth_birch_forest" -> big
                ?"super_birch_bees_0002":"birch_bees_0002_leaf_litter";
            case "minecraft:taiga","minecraft:snowy_taiga" -> species<.15+.65*stand?"pine_checked":"spruce_checked";
            case "minecraft:old_growth_pine_taiga" -> big&&oldGrowthConifer?"mega_pine_checked":species<.65?"pine_checked":"spruce_checked";
            case "minecraft:old_growth_spruce_taiga" -> big&&oldGrowthConifer?"mega_spruce_checked":species<.25?"pine_checked":"spruce_checked";
            case "minecraft:jungle" -> ecology.opening()>.35||vigor<.30?"jungle_bush":big?"mega_jungle_tree_checked":"jungle_tree";
            case "minecraft:sparse_jungle" -> ecology.opening()>.30||vigor<.40?"jungle_bush":"jungle_tree";
            case "minecraft:savanna","minecraft:savanna_plateau","minecraft:windswept_savanna" -> species<.75+.20*(1-stand)?"acacia_checked":"oak_checked";
            default -> null;
        };
    }

    public static boolean compatible(String from,String to,ForestEcology.Site site) {
        int a=woodland(from),b=woodland(to);
        if(a==0||b==0||from.equals(to))return false;
        if(a==b)return true;
        // No broadleaf incursion into snow forest or the cold upper timberline.
        if(from.equals("minecraft:snowy_taiga")||to.equals("minecraft:snowy_taiga"))return false;
        return site.temperature()>-.42&&site.temperature()<.55
            &&site.height()<175+25*site.temperature();
    }
    private static int woodland(String biome) {
        return switch(biome) {
            case "minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest" -> 1;
            case "minecraft:taiga","minecraft:snowy_taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga" -> 2;
            default -> 0;
        };
    }

    /** Existing understory candidates are thinned by light/moisture proxies.
     * Does not read generated tree blocks, so decoration order cannot feed back.
     */
    public static double understory(long seed,int x,int z,ForestEcology.Family family,
                                    ForestEcology.Site site,boolean fernOrShrub) {
        return understory(seed,x,z,family,site,fernOrShrub,ForestHabitat.WOODLAND);
    }
    public static double understory(long seed,int x,int z,ForestEcology.Family family,
                                    ForestEcology.Site site,boolean fernOrShrub,ForestHabitat habitat) {
        var ecology=ForestEcology.sample(seed,x,z,family,site,habitat);
        double wet=ForestEcology.smooth(-.8,.35,site.moisture());
        double patch=.5+.5*ForestEcology.noise(seed^0x4B91L,x/29.0,z/29.0);
        double light=ecology.opening()+.35*site.edge();
        double suitability=fernOrShrub
            ?(.40+.60*wet)*(.68+.25*patch+.07*(1-light))
            :(.60+.40*wet)*(.55+.30*patch+.15*Math.min(1,light));
        if(!fernOrShrub&&(habitat==ForestHabitat.CLOSED_FOREST||habitat==ForestHabitat.TROPICAL_CANOPY))
            suitability*=.62+.38*Math.min(1,light);
        if(fernOrShrub&&habitat.waterTolerant())suitability*=.75+.25*wet;
        // Ferns, shrubs and bamboo follow the treeline; alpine grass does not.
        if(fernOrShrub)suitability*=ForestEcology.altitude(seed,x,z,family,site);
        return Math.max(0,Math.min(1,suitability*(1-.55*ForestEcology.smooth(.65,1.8,site.slope()))));
    }
    public static boolean retainUnderstory(long seed,int x,int z,double probability) {
        if(!Double.isFinite(probability)||probability<0||probability>1)throw new IllegalArgumentException("Invalid probability");
        return unit(seed^0xAE719C51L,x,z)<probability;
    }
    private static double unit(long seed,long x,long z) {
        long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;
        return(n>>>11)*0x1.0p-53;
    }
}
