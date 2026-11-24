package dev.freshgenerator.forest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature;
import net.minecraft.world.level.levelgen.placement.*;

/** A donor palette may change the tree at an already validated source position,
 * never that position or its source-biome identity. Preserve the donor's actual
 * point predicates before its feature can write soil, trunks or foliage. */
public final class NativeForestPalette {
    private NativeForestPalette() { }

    public static PlacedFeature atSourcePosition(PlacedFeature donor) {
        var filters=pointFilters(donor.placement());
        return filters==null||!checkedTrees(donor.feature().value(),hasPredicate(filters),0)
            ?null:new PlacedFeature(donor.feature(),filters);
    }

    /** Unknown layouts decline borrowing rather than guessing which modifiers
     * move/repeat positions or hide additional point conditions. */
    public static List<PlacementModifier> pointFilters(List<PlacementModifier> original) {
        var filters=new ArrayList<PlacementModifier>();
        for(var modifier:original) {
            if(modifier instanceof BlockPredicateFilter||modifier instanceof SurfaceWaterDepthFilter)
                filters.add(modifier);
            else if(!(modifier instanceof CountPlacement||modifier instanceof RarityFilter
                    ||modifier instanceof InSquarePlacement||modifier instanceof HeightmapPlacement
                    ||modifier instanceof BiomeFilter))return null;
        }
        return List.copyOf(filters);
    }

    /** A ground check of the tree at its own position: the native "would survive" of a sapling, or a test of the block
     * below it (grove trees: snow below). Updated realism audit UP-F05: always_true checks nothing; code audit 24.11
     * F2: neither does a test of the position itself - "the block here is air" accepted a donor whose oak then stood
     * on air with a dirt block under it. */
    private static boolean hasPredicate(List<PlacementModifier> filters) {
        return filters.stream().anyMatch(m->m instanceof BlockPredicateFilter f&&groundCheck(f.predicate()));
    }
    private static final java.util.Map<BlockPredicate,Boolean> GROUND=new java.util.concurrent.ConcurrentHashMap<>();
    static boolean groundCheck(BlockPredicate predicate){
        if(predicate instanceof WouldSurvivePredicate)return true;
        // Offsets of the native block tests are not public: read the predicate in its data form (block sets need the
        // registries to encode).
        return GROUND.computeIfAbsent(predicate,p->{
            var json=BlockPredicate.CODEC.encodeStart(Ops.JSON,p);
            if(json.result().isEmpty()){dev.freshgenerator.core.Diagnostics.warnOnce("palette-codec","FreshGenerator forest: a tree placement check could not be read; that donor is not borrowed");return false;}
            return groundCheck(json.result().get());
        });
    }
    private static final class Ops {
        static final com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> JSON=net.minecraft.resources.RegistryOps.create(
            com.mojang.serialization.JsonOps.INSTANCE,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
    }
    private static boolean groundCheck(com.google.gson.JsonElement e){
        if(!e.isJsonObject())return false;
        var o=e.getAsJsonObject();String type=o.has("type")?o.get("type").getAsString():"";
        switch(type){
            case "minecraft:would_survive":return true;
            case "minecraft:matching_blocks","minecraft:matching_block_tag":{
                var offset=o.get("offset");
                return offset!=null&&offset.isJsonArray()&&offset.getAsJsonArray().size()==3&&offset.getAsJsonArray().get(1).getAsInt()<0;
            }
            case "minecraft:all_of":{for(var c:o.getAsJsonArray("predicates"))if(groundCheck(c))return true;return false;}
            case "minecraft:any_of":{var cs=o.getAsJsonArray("predicates");if(cs.isEmpty())return false;for(var c:cs)if(!groundCheck(c))return false;return true;}
            default:return false;
        }
    }

    /** A valid outer layout with no soil predicate must not turn a raw tree into
     * an unchecked palette. Native selectors retain their original nested
     * PlacedFeatures; check that each tree still has its own point predicate or
     * a native root placer. Other native feature types keep their own rules. */
    private static boolean checkedTrees(Feature feature,boolean checked,int depth) {
        if(depth>4)return false;
        if(feature instanceof TreeFeature tree)return checked||tree.rootPlacer().isPresent();
        // Updated realism audit UP-F05: every native selector container is followed the same way (the weighted,
        // boolean and simple selectors were accepted without looking inside).
        var branches=new ArrayList<PlacedFeature>();
        if(feature instanceof RandomSelectorFeature selector) {
            for(var choice:selector.features())branches.add(choice.feature().value());
            branches.add(selector.defaultFeature().value());
        }else if(feature instanceof WeightedRandomSelectorFeature weighted) {
            for(var entry:weighted.features().unwrap())branches.add(entry.value().value());
        }else if(feature instanceof RandomBooleanSelectorFeature choice) {
            branches.add(choice.featureTrue().value());branches.add(choice.featureFalse().value());
        }else if(feature instanceof SimpleRandomSelectorFeature simple) {
            for(var entry:simple.features())branches.add(entry.value());
        }else return true;
        for(var placed:branches)
            if(!checkedTrees(placed.feature().value(),checked||hasPredicate(placed.placement()),depth+1))return false;
        return true;
    }
}
