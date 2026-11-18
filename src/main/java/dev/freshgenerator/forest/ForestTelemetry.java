package dev.freshgenerator.forest;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestComposition;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
/** Disabled in ordinary play. Aggregates only; never stores positions or world references. */
public final class ForestTelemetry {
 private static final boolean ENABLED=Boolean.getBoolean("freshgenerator.forestAudit");
 private static final int FAMILIES=ForestEcology.Family.values().length;
 private static final LongAdder[] ATTEMPTS=new LongAdder[FAMILIES],KEPT=new LongAdder[FAMILIES],OPENINGS=new LongAdder[FAMILIES];
 private static final LongAdder[] VARIANT_ATTEMPTS=new LongAdder[ENABLED?ForestComposition.VARIANTS.size():0],VARIANT_SUCCESS=new LongAdder[ENABLED?ForestComposition.VARIANTS.size():0];
 private static final LongAdder UNDERSTORY_SEEN=ENABLED?new LongAdder():null,UNDERSTORY_KEPT=ENABLED?new LongAdder():null;
 private static final LongAdder EDGE_PLANS=ENABLED?new LongAdder():null,EDGE_PROPOSALS=ENABLED?new LongAdder():null,EDGE_BORROWED=ENABLED?new LongAdder():null;
 private static final LongAdder METADATA_PREPARED=ENABLED?new LongAdder():null;
 static {for(int i=0;i<FAMILIES;i++){ATTEMPTS[i]=new LongAdder();KEPT[i]=new LongAdder();OPENINGS[i]=new LongAdder();}}
 static {if(ENABLED)for(int i=0;i<VARIANT_ATTEMPTS.length;i++){VARIANT_ATTEMPTS[i]=new LongAdder();VARIANT_SUCCESS[i]=new LongAdder();}}
 private ForestTelemetry(){}
 static void record(ForestEcology.Family family,boolean kept,double opening){if(!ENABLED)return;int n=family.ordinal();ATTEMPTS[n].increment();if(kept)KEPT[n].increment();if(opening>.5)OPENINGS[n].increment();}
 static void understory(boolean kept){if(!ENABLED)return;UNDERSTORY_SEEN.increment();if(kept)UNDERSTORY_KEPT.increment();}
 static void edgeBudget(int proposals){if(!ENABLED)return;EDGE_PLANS.increment();EDGE_PROPOSALS.add(proposals);}
 static void edgeBorrowed(){if(ENABLED)EDGE_BORROWED.increment();}
 static void metadataPrepared(){if(ENABLED)METADATA_PREPARED.increment();}
 /** Called after a replacement's native placement returns, not after merely selecting it. */
 public static void variant(PlacedFeature feature,boolean success){
  if(!ENABLED)return;
  String id=feature.feature().unwrapKey().map(k->k.identifier().toString()).orElse("");
  if(!id.startsWith("minecraft:"))return;String name=id.substring("minecraft:".length());
  int index=ForestComposition.VARIANTS.indexOf(name);
  if(index<0)index=ForestComposition.VARIANTS.indexOf(name+"_checked");
  if(index<0)return;
  VARIANT_ATTEMPTS[index].increment();if(success)VARIANT_SUCCESS[index].increment();
 }
 public static Map<String,Long> snapshot(){
  Map<String,Long> out=new TreeMap<>();for(var f:ForestEcology.Family.values()){int n=f.ordinal();out.put(f+"_attempts",ATTEMPTS[n].sum());out.put(f+"_kept",KEPT[n].sum());out.put(f+"_opening_attempts",OPENINGS[n].sum());}
  if(ENABLED){
   out.put("understory_seen",UNDERSTORY_SEEN.sum());out.put("understory_retained",UNDERSTORY_KEPT.sum());
   out.put("ecotone_budget_plans",EDGE_PLANS.sum());out.put("ecotone_budget_proposals",EDGE_PROPOSALS.sum());out.put("ecotone_borrowed_palettes",EDGE_BORROWED.sum());
   out.put("forest_metadata_preparations",METADATA_PREPARED.sum());
   for(int i=0;i<VARIANT_ATTEMPTS.length;i++){String name="variant_"+ForestComposition.VARIANTS.get(i);out.put(name+"_attempts",VARIANT_ATTEMPTS[i].sum());out.put(name+"_success",VARIANT_SUCCESS[i].sum());}
  }
  return out;
 }
}
