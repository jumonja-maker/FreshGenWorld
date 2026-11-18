package dev.freshgenerator.forest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import dev.freshgenerator.core.ForestGrowth;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Set;

/** Per-FeaturePlacer immutable native variants. No registry mutation, extra placement
 * attempts, external chunk reads, thread-local state or globally cached world objects.
 * The native tree still controls soil, free space, branches, leaves and decorators. */
public final class NativeTreeGrowth {
    private static final int BANDS=15, MAX_ORIGINALS=64, MAX_SELECTOR_DEPTH=4;
    private static final Set<String> TRUNKS=Set.of("minecraft:straight_trunk_placer", "minecraft:fancy_trunk_placer",
        "minecraft:forking_trunk_placer", "minecraft:giant_trunk_placer", "minecraft:mega_jungle_trunk_placer",
        "minecraft:dark_oak_trunk_placer","minecraft:cherry_trunk_placer","minecraft:upwards_branching_trunk_placer",
        "minecraft:poplar_trunk_placer");
    private static final Set<String> FOLIAGE=Set.of("minecraft:blob_foliage_placer", "minecraft:fancy_foliage_placer",
        "minecraft:spruce_foliage_placer", "minecraft:pine_foliage_placer", "minecraft:mega_pine_foliage_placer",
        "minecraft:jungle_foliage_placer", "minecraft:acacia_foliage_placer","minecraft:dark_oak_foliage_placer",
        "minecraft:cherry_foliage_placer","minecraft:random_spread_foliage_placer","minecraft:poplar_foliage_placer");
    private final IdentityHashMap<Feature,Feature[]> features=new IdentityHashMap<>();
    private final IdentityHashMap<PlacedFeature,PlacedFeature[]> placed=new IdentityHashMap<>();
    private final DynamicOps<JsonElement> codecOps;

    public NativeTreeGrowth(){codecOps=JsonOps.INSTANCE;}
    /** Tagged trunk placers (mangrove) need the current registry lookup for codec
     * round trips. The lookup is owned by this short-lived FeaturePlacer adapter. */
    public NativeTreeGrowth(HolderLookup.Provider registries){codecOps=registries.createSerializationContext(JsonOps.INSTANCE);}

    public Feature adapt(Feature original,int sizeBand,int crownBand) {
        check(sizeBand,crownBand);
        return adapt(original,sizeBand,crownBand,0);
    }
    public PlacedFeature adapt(PlacedFeature original,int sizeBand,int crownBand) {
        check(sizeBand,crownBand);
        return adaptPlaced(original,sizeBand,crownBand,0);
    }
    private Feature adapt(Feature original,int sizeBand,int crownBand,int depth) {
        if(sizeBand==2&&crownBand==1||depth>MAX_SELECTOR_DEPTH)return original;
        if(!(original instanceof TreeFeature)&&!(original instanceof RandomSelectorFeature)&&!(original instanceof WeightedRandomSelectorFeature))return original;
        if(features.size()>=MAX_ORIGINALS&&!features.containsKey(original))features.clear();
        Feature[] variants=features.computeIfAbsent(original,k->new Feature[BANDS]);
        int slot=sizeBand*3+crownBand;
        if(variants[slot]!=null)return variants[slot];
        Feature result=original;
        if(original instanceof TreeFeature tree)result=tree(tree,sizeBand,crownBand);
        else if(original instanceof RandomSelectorFeature selector) {
            var choices=new ArrayList<WeightedPlacedFeature>(selector.features().size());
            boolean changed=false;
            for(var choice:selector.features()) {
                var before=choice.feature().value();
                var after=adaptPlaced(before,sizeBand,crownBand,depth+1);
                changed|=before!=after;
                choices.add(before==after?choice:new WeightedPlacedFeature(Holder.direct(after),choice.chance()));
            }
            var before=selector.defaultFeature().value();
            var after=adaptPlaced(before,sizeBand,crownBand,depth+1);
            if(changed||before!=after)result=new RandomSelectorFeature(choices,
                before==after?selector.defaultFeature():Holder.direct(after));
        }
        else if(original instanceof WeightedRandomSelectorFeature weighted) {
            // 26.3 dappled forest (poplars) uses this selector; weights stay native.
            var entries=new ArrayList<Weighted<Holder<PlacedFeature>>>(weighted.features().unwrap().size());boolean changed=false;
            for(var entry:weighted.features().unwrap()){
                var before=entry.value().value();var after=adaptPlaced(before,sizeBand,crownBand,depth+1);
                changed|=before!=after;entries.add(before==after?entry:new Weighted<>(Holder.direct(after),entry.weight()));
            }
            if(changed)result=new WeightedRandomSelectorFeature(WeightedList.of(entries));
        }
        variants[slot]=result;return result;
    }
    private PlacedFeature adaptPlaced(PlacedFeature original,int sizeBand,int crownBand,int depth) {
        if(sizeBand==2&&crownBand==1||depth>MAX_SELECTOR_DEPTH)return original;
        if(placed.size()>=MAX_ORIGINALS&&!placed.containsKey(original))placed.clear();
        PlacedFeature[] variants=placed.computeIfAbsent(original,k->new PlacedFeature[BANDS]);
        int slot=sizeBand*3+crownBand;
        if(variants[slot]!=null)return variants[slot];
        Feature feature=adapt(original.feature().value(),sizeBand,crownBand,depth);
        // Every nested survival or snow filter remains the exact original list.
        variants[slot]=feature==original.feature().value()?original:new PlacedFeature(Holder.direct(feature),original.placement());
        return variants[slot];
    }
    private Feature tree(TreeFeature source,int sizeBand,int crownBand) {
        var trunk=TrunkPlacer.CODEC.encodeStart(codecOps,source.trunkPlacer()).getOrThrow().getAsJsonObject();
        var leaves=FoliagePlacer.CODEC.encodeStart(codecOps,source.foliagePlacer()).getOrThrow().getAsJsonObject();
        if(!reshapePlacers(trunk,leaves,sizeBand,crownBand))return source;
        // A native codec bound we do not know must never abort chunk generation:
        // an unrepresentable resize keeps the exact native tree instead.
        var grownTrunk=TrunkPlacer.CODEC.parse(codecOps,trunk).result().orElse(null);
        var grownLeaves=FoliagePlacer.CODEC.parse(codecOps,leaves).result().orElse(null);
        if(grownTrunk==null||grownLeaves==null)return source;
        return new TreeFeature(source.trunkProvider(),grownTrunk,source.foliageProvider(),grownLeaves,
            source.rootPlacer(),source.minimumSize(),source.decorators(),source.ignoreVines(),source.belowTrunkProvider());
    }

    /** Transform private copies of the native placer definitions. Also used by
     * resource regression tests so all supported native variants are covered. */
    public static boolean reshapePlacers(JsonObject trunk,JsonObject leaves,int sizeBand,int crownBand) {
        check(sizeBand,crownBand);
        String trunkType=trunk.get("type").getAsString(),leafType=leaves.get("type").getAsString();
        // Bushes and specialized trunk/crown pairs keep their own ecology and geometry.
        if(!TRUNKS.contains(trunkType)||!FOLIAGE.contains(leafType))return false;
        double scale=ForestGrowth.scale(sizeBand);
        boolean giant=trunkType.equals("minecraft:giant_trunk_placer")||trunkType.equals("minecraft:mega_jungle_trunk_placer");
        boolean dark=trunkType.equals("minecraft:dark_oak_trunk_placer"),cherry=trunkType.equals("minecraft:cherry_trunk_placer");
        boolean poplar=trunkType.equals("minecraft:poplar_trunk_placer");
        boolean mangrove=trunkType.equals("minecraft:upwards_branching_trunk_placer")&&leafType.equals("minecraft:random_spread_foliage_placer");
        // Giant conifers and cherries hang their crown/branches from the trunk top: a shorter
        // trunk pushes their native outer leaves past the leaf-decay distance (0.9.0).
        if(giant||cherry||poplar)scale=Math.max(1,scale);
                int max=giant?32:trunkType.equals("minecraft:forking_trunk_placer")?14:mangrove?16:20;
        // Cherry branches start as low as four blocks below the top. Keep their
        // attachments above the ground rather than scaling only the trunk number.
        int min=giant?7:cherry?6:dark?5:poplar?5:mangrove?2:3;
        int base=clamp((int)Math.round(trunk.get("base_height").getAsInt()*scale),min,max);
        int a=Math.max(0,(int)Math.round(trunk.get("height_rand_a").getAsInt()*scale));
        int b=Math.max(0,(int)Math.round(trunk.get("height_rand_b").getAsInt()*scale));
        if(base+a+b>max) {
            double fit=max/(double)(base+a+b);
            base=Math.max(min,(int)Math.floor(base*fit));a=(int)Math.floor(a*fit);b=Math.min(max-base-a,(int)Math.floor(b*fit));
        }
        trunk.addProperty("base_height",base);trunk.addProperty("height_rand_a",a);trunk.addProperty("height_rand_b",b);
        // Poplar branches sit below a fixed crown stem; keep it within the scaled trunk.
        if(poplar&&trunk.has("trunk_height_above_branches"))trunk.add("trunk_height_above_branches",
            provider(trunk.get("trunk_height_above_branches"),scale,0,2,Math.max(2,base-2)));
        double width=Math.sqrt(scale)*(crownBand==0?.78:crownBand==2?1.4:1);
        // 0.9.0: a crown may narrow but never widen past its native maximum radius.
        // Leaves more than six leaf steps from a log (LeavesBlock distance 7) decay:
        // widened crowns generated leaves that later vanished in front of the player.
        int nativeRadius=maxValue(leaves.get("radius"));
        double depth=Math.sqrt(scale)*(crownBand==0?.78:crownBand==2?1.3:1);
        if(leafType.equals("minecraft:dark_oak_foliage_placer")) {
            // Zero is the native additive radius on this broad multi-level crown,
            // not a zero-width crown. Generic minimum-radius logic is incorrect.
            leaves.add("radius",provider(leaves.get("radius"),1,0,0,Math.max(0,Math.min(1,nativeRadius))));
        } else if(mangrove) {
            leaves.add("radius",provider(leaves.get("radius"),width,0,2,Math.max(2,Math.min(4,nativeRadius))));
            leaves.add("foliage_height",provider(leaves.get("foliage_height"),depth,0,2,Math.max(2,Math.min(4,maxValue(leaves.get("foliage_height"))))));
            leaves.add("leaf_placement_attempts",provider(leaves.get("leaf_placement_attempts"),Math.min(1,width*width*depth),0,45,180));
        } else if(cherry||poplar) {
            // Cherry crowns hang leaves from their edge: a narrowed crown leaves some of
            // them detached (they decay); poplar crowns depend on their branch logs. Only the
            // trunk follows the site; the crown stays native.
                } else if(leafType.equals("minecraft:mega_pine_foliage_placer")) {
            // Its radius is an additive term on a cone, rather than the cone's full width.
            leaves.add("radius",provider(leaves.get("radius"),1,0,0,Math.max(0,Math.min(2,nativeRadius))));
            // The cone widens downward with its height: a taller crown is a wider crown.
            leaves.add("crown_height",provider(leaves.get("crown_height"),scale*(crownBand==0?.8:crownBand==2?1.2:1),0,3,
                Math.max(3,Math.min(24,maxValue(leaves.get("crown_height"))))));
        } else {
            boolean rounded=leafType.equals("minecraft:blob_foliage_placer")||leafType.equals("minecraft:fancy_foliage_placer");
            // Native rounded crowns subtract from this radius at their outer rows.
            // Below 2, a short tree collapses to a leaf cross with an exposed top log.
            leaves.add("radius",provider(leaves.get("radius"),width,0,rounded?2:1,Math.max(rounded?2:1,Math.min(4,nativeRadius))));
            if(leaves.has("height")) {
                int minimumDepth=rounded?3:cherry?4:2;
                int cap=leafType.equals("minecraft:fancy_foliage_placer")?5:Math.max(minimumDepth,Math.min(6,base));
                // Taller clusters lift their top rows past the leaf-decay distance from the
                // branch ends: crown depth, like radius, stays within the native maximum.
                cap=Math.max(minimumDepth,Math.min(cap,maxValue(leaves.get("height"))));
                leaves.add("height",provider(leaves.get("height"),depth,0,minimumDepth,cap));
                // FancyTrunkPlacer anchors clusters using a fixed five-block span.
                // Retain its native minimum offset even when the crown is shorter.
                if(leafType.equals("minecraft:fancy_foliage_placer"))leaves.add("offset",provider(leaves.get("height"),1,0,4,5));
            }
            if(leafType.equals("minecraft:spruce_foliage_placer")) {
                // Spruce crown depth is tree height minus this bare-trunk length.
                leaves.add("trunk_height",provider(leaves.get("trunk_height"),scale*(crownBand==0?1.5:crownBand==2?.55:1),crownBand==0?1:0,0,Math.max(1,base-2)));
            }
        }
        return true;
    }
    /** Largest value a native int provider can return (primitive, constant, uniform, weighted list). */
    static int maxValue(JsonElement input) {
        if(input==null)return Integer.MAX_VALUE;
        if(input.isJsonPrimitive())return input.getAsInt();
        var o=input.getAsJsonObject();String type=o.get("type").getAsString();
        return switch(type){
            case "minecraft:constant" -> o.get("value").getAsInt();
            case "minecraft:uniform" -> o.get("max_inclusive").getAsInt();
            case "minecraft:weighted_list" -> {int m=Integer.MIN_VALUE;for(var e:o.getAsJsonArray("distribution"))m=Math.max(m,maxValue(e.getAsJsonObject().get("data")));yield m;}
            default -> Integer.MAX_VALUE;
        };
    }
    private static JsonElement provider(JsonElement input,double scale,int add,int min,int max) {
        if(input.isJsonPrimitive())return new JsonPrimitive(clamp((int)Math.round(input.getAsInt()*scale)+add,min,max));
        var value=input.getAsJsonObject().deepCopy();
        String type=value.get("type").getAsString();
        if(type.equals("minecraft:uniform")) {
            int lo=clamp((int)Math.round(value.get("min_inclusive").getAsInt()*scale)+add,min,max);
            int hi=clamp((int)Math.round(value.get("max_inclusive").getAsInt()*scale)+add,min,max);
            value.addProperty("min_inclusive",lo);value.addProperty("max_inclusive",Math.max(lo,hi));
        } else if(type.equals("minecraft:constant")) {
            value.addProperty("value",clamp((int)Math.round(value.get("value").getAsInt()*scale)+add,min,max));
        } else if(type.equals("minecraft:weighted_list")) {
            // Poplar crown radii: scale every candidate, keep the native weights.
            for(var entry:value.getAsJsonArray("distribution"))
                entry.getAsJsonObject().add("data",provider(entry.getAsJsonObject().get("data"),scale,add,min,max));
        }
        return value;
    }
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private static void check(int size,int crown){if(size<0||size>4||crown<0||crown>2)throw new IllegalArgumentException("Invalid tree shape bands");}
}
