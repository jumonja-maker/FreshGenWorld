package dev.freshgenerator.forest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.freshgenerator.core.ForestEcotone;
import java.util.*;
import net.minecraft.world.level.levelgen.placement.*;

/** Reads actual native count providers, never estimates weighted-list means
 * from only their minimum/maximum. Unknown or complex placements pass through. */
public record NativeForestCount(int modifierIndex,double mean) {
    public static NativeForestCount inspect(List<PlacementModifier> modifiers){
        if(modifiers.size()<2||!(modifiers.get(1) instanceof InSquarePlacement))return null;
        double mean;
        if(modifiers.getFirst() instanceof CountPlacement count){
            var json=CountPlacement.CODEC.codec().encodeStart(JsonOps.INSTANCE,count).getOrThrow();
            mean=mean(json.getAsJsonObject().get("count"));
        }else if(modifiers.getFirst() instanceof RarityFilter rarity)mean=1.0/rarity.chance();
        else return null;
        for(int i=1;i<modifiers.size();i++)if(modifiers.get(i) instanceof RepeatingPlacement||modifiers.get(i) instanceof RarityFilter)return null;
        return Double.isFinite(mean)&&mean>0&&mean<=ForestEcotone.MAX_PROPOSALS?new NativeForestCount(0,mean):null;
    }
    public List<PlacementModifier> proposals(List<PlacementModifier> original,int count){
        if(count<1||count>ForestEcotone.MAX_PROPOSALS)throw new IllegalArgumentException("Unsafe tree budget");
        var changed=new ArrayList<>(original);changed.set(modifierIndex,CountPlacement.of(count));return List.copyOf(changed);
    }
    public static double mean(JsonElement provider){
        if(provider.isJsonPrimitive())return provider.getAsDouble();
        var p=provider.getAsJsonObject();String type=p.get("type").getAsString();
        return switch(type){
            case "minecraft:constant" -> p.get("value").getAsDouble();
            case "minecraft:uniform" -> (p.get("min_inclusive").getAsDouble()+p.get("max_inclusive").getAsDouble())/2;
            case "minecraft:weighted_list" -> {double sum=0,weight=0;for(var entry:p.getAsJsonArray("distribution")){var item=entry.getAsJsonObject();double w=item.get("weight").getAsDouble();sum+=w*mean(item.get("data"));weight+=w;}yield weight>0?sum/weight:Double.NaN;}
            default -> Double.NaN;
        };
    }
}
