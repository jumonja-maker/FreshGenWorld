package dev.freshgenerator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.freshgenerator.core.ForestComposition;
import dev.freshgenerator.forest.NativeTreeGrowth;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class NativeTreeGrowthTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void allNativeReplacementPlacerPairsDecodeForEveryShape()throws Exception {
        for(String name:ForestComposition.VARIANTS) {
            var source=definition(name);
            for(int size=0;size<5;size++)for(int crown=0;crown<3;crown++) {
                var trunk=source.getAsJsonObject("trunk_placer").deepCopy();
                var leaves=source.getAsJsonObject("foliage_placer").deepCopy();
                NativeTreeGrowth.reshapePlacers(trunk,leaves,size,crown);
                var t=TrunkPlacer.CODEC.parse(JsonOps.INSTANCE,trunk).getOrThrow();
                var f=FoliagePlacer.CODEC.parse(JsonOps.INSTANCE,leaves).getOrThrow();
                assertNotNull(t);assertNotNull(f);
                int maximum=trunk.get("base_height").getAsInt()+trunk.get("height_rand_a").getAsInt()+trunk.get("height_rand_b").getAsInt();
                assertTrue(maximum>=1&&maximum<=32,name+" "+size+" "+crown);
            }
        }
    }
    @Test void resizedTreeKeepsProvidersDecoratorsRootsAndNativeClearance()throws Exception {
        var original=tree("fancy_oak_bees_0002_leaf_litter");var growth=new NativeTreeGrowth();
        var adapted=(TreeFeature)growth.adapt(original,4,2);
        assertNotSame(original,adapted);assertNotSame(original.trunkPlacer(),adapted.trunkPlacer());
        assertNotSame(original.foliagePlacer(),adapted.foliagePlacer());
        assertSame(original.trunkProvider(),adapted.trunkProvider());assertSame(original.foliageProvider(),adapted.foliageProvider());
        assertSame(original.belowTrunkProvider(),adapted.belowTrunkProvider());assertSame(original.minimumSize(),adapted.minimumSize());
        assertSame(original.rootPlacer(),adapted.rootPlacer());assertSame(original.decorators(),adapted.decorators());
        assertEquals(original.ignoreVines(),adapted.ignoreVines());
    }
    @Test void specialNativeTreesDecodeAcrossAllBandsWithoutFlatteningTheirArchitecture()throws Exception {
        // 0.9.0: 26.3 dappled-forest poplars (their codecs reject a crown below the native minimum).
        for(String name:List.of("pale_oak_checked","cherry_checked","mangrove_checked","tall_mangrove_checked","orange_poplar","red_poplar","yellow_poplar")){
            var source=definition(name);
            for(int size=0;size<5;size++)for(int crown=0;crown<3;crown++){
                var trunk=source.getAsJsonObject("trunk_placer").deepCopy();
                var leaves=source.getAsJsonObject("foliage_placer").deepCopy();
                assertTrue(NativeTreeGrowth.reshapePlacers(trunk,leaves,size,crown),name);
                var decodeTrunk=trunk.deepCopy();
                if(name.contains("mangrove")){
                    // Unit bootstrap has no loaded datapack tags. Exercise the
                    // same registry-backed holder-set codec with explicit blocks;
                    // native server tests retain the real named mangrove tag.
                    var blocks=new com.google.gson.JsonArray();blocks.add("minecraft:mangrove_leaves");
                    decodeTrunk.add("can_grow_through",blocks);
                }
                var ops=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).createSerializationContext(JsonOps.INSTANCE);
                assertNotNull(TrunkPlacer.CODEC.parse(ops,decodeTrunk).getOrThrow());
                assertNotNull(FoliagePlacer.CODEC.parse(JsonOps.INSTANCE,leaves).getOrThrow());
                if(name.equals("cherry_checked")){
                    assertTrue(trunk.get("base_height").getAsInt()>=6);
                    assertEquals(source.getAsJsonObject("trunk_placer").get("branch_start_offset_from_top"),trunk.get("branch_start_offset_from_top"));
                    assertTrue(leaves.get("height").getAsInt()>=4);
                }
                if(name.equals("pale_oak_checked"))assertTrue(leaves.get("radius").getAsInt()<=1,"Dark-oak radius is additive");
                if(name.contains("mangrove"))assertEquals(source.getAsJsonObject("trunk_placer").get("can_grow_through"),trunk.get("can_grow_through"));
            }
        }
    }
    @Test void crownBandsNarrowButNeverWidenPastTheNativeCrown()throws Exception {
        // Broad crowns may reach, but not exceed, the native maximum radius and depth:
        // beyond it leaves fall outside the leaf-decay distance (0.9.0).
        var original=tree("birch_bees_0002_leaf_litter");var growth=new NativeTreeGrowth();
        var compact=(TreeFeature)growth.adapt(original,3,0);var broad=(TreeFeature)growth.adapt(original,3,2);
        assertEquals(encoded(compact.trunkPlacer()),encoded(broad.trunkPlacer()));
        var small=encoded(compact.foliagePlacer());var wide=encoded(broad.foliagePlacer());var base=encoded(original.foliagePlacer());
        assertTrue(wide.get("radius").getAsInt()>=small.get("radius").getAsInt());
        assertTrue(wide.get("radius").getAsInt()<=base.get("radius").getAsInt());
        assertTrue(wide.get("height").getAsInt()<=base.get("height").getAsInt());
        var tiny=encoded(((TreeFeature)growth.adapt(original,0,0)).foliagePlacer());
        assertTrue(tiny.get("height").getAsInt()<=base.get("height").getAsInt());
    }
    @Test void environmentBandsChangeNativeHeightDistribution()throws Exception {
        var original=tree("birch_bees_0002_leaf_litter");var growth=new NativeTreeGrowth();
        var small=(TreeFeature)growth.adapt(original,0,1);var tall=(TreeFeature)growth.adapt(original,4,1);
        double smallSum=0,tallSum=0;
        for(int seed=0;seed<1000;seed++) {
            smallSum+=small.trunkPlacer().getTreeHeight(RandomSource.create(seed));
            tallSum+=tall.trunkPlacer().getTreeHeight(RandomSource.create(seed));
        }
        assertTrue(tallSum/1000>smallSum/1000+3);
        assertEquals(5,original.trunkPlacer().getBaseHeight(),"Registry tree was mutated");
    }
    @Test void nativeSelectorRetainsWeightsUnknownBranchesAndPlacementFilters()throws Exception {
        var original=tree("oak_bees_0002_leaf_litter");
        var checked=new PlacedFeature(Holder.direct(original),List.of());
        var untouched=new PlacedFeature(Holder.direct(new NoOpFeature()),List.of());
        var selector=new RandomSelectorFeature(List.of(new WeightedPlacedFeature(Holder.direct(untouched),.0125f),
            new WeightedPlacedFeature(Holder.direct(checked),.2f)),Holder.direct(checked));
        var result=(RandomSelectorFeature)new NativeTreeGrowth().adapt(selector,4,2);
        assertSame(selector.features().get(0),result.features().get(0));
        assertEquals(.2f,result.features().get(1).chance());
        assertEquals(2,result.features().size());
        assertSame(checked.placement(),result.defaultFeature().value().placement());
        assertSame(result.features().get(1).feature().value(),result.defaultFeature().value());
        assertNotSame(original,result.defaultFeature().value().feature().value());
    }
    @Test void neutralShapeAndUnknownFeaturesStayExactIdentity()throws Exception {
        var original=tree("birch_bees_0002_leaf_litter");var placed=new PlacedFeature(Holder.direct(original),List.of());
        var growth=new NativeTreeGrowth();var unknown=new NoOpFeature();
        assertSame(original,growth.adapt(original,2,1));assertSame(placed,growth.adapt(placed,2,1));
        assertSame(unknown,growth.adapt(unknown,4,2));
    }
    @Test void jungleBushRemainsAnUnscaledBush()throws Exception {
        var original=tree("jungle_bush");var growth=new NativeTreeGrowth();
        for(int size=0;size<5;size++)for(int crown=0;crown<3;crown++)assertSame(original,growth.adapt(original,size,crown));
    }
    @Test void repeatedAdaptationReusesImmutableNativeCopies()throws Exception {
        var original=tree("spruce_checked");var growth=new NativeTreeGrowth();
        var a=growth.adapt(original,4,0);var b=growth.adapt(original,0,2);
        assertSame(a,growth.adapt(original,4,0));assertSame(b,growth.adapt(original,0,2));assertNotSame(a,b);
    }
    @Test void depthLimitedResultsDoNotDependOnTheCacheHistory()throws Exception {
        // Updated realism audit UP-F04: a subtree warmed up near the root was read again deeper down, past the depth limit
        // (five default-only selectors around oak_checked: 12 of 63 cases differed from a fresh deep request).
        var oak=tree("oak_checked");
        for(int sharedDepth=1;sharedDepth<=3;sharedDepth++)for(int outerDepth=0;outerDepth<=6;outerDepth++)for(int[] shape:List.of(new int[]{0,0},new int[]{2,0},new int[]{4,2})){
            var shared=new PlacedFeature(Holder.direct(oak),List.of());for(int i=0;i<sharedDepth;i++)shared=wrap(shared);
            var deep=shared;for(int i=0;i<outerDepth;i++)deep=wrap(deep);
            String label="shared "+sharedDepth+" outer "+outerDepth+" shape "+shape[0]+"/"+shape[1];
            var cold=new NativeTreeGrowth();boolean coldDeep=adapted(cold.adapt(deep,shape[0],shape[1]),oak);boolean directAfterDeep=adapted(cold.adapt(shared,shape[0],shape[1]),oak);
            var warmPlaced=new NativeTreeGrowth();boolean freshDirect=adapted(warmPlaced.adapt(shared,shape[0],shape[1]),oak);
            assertEquals(coldDeep,adapted(warmPlaced.adapt(deep,shape[0],shape[1]),oak),"placed warm-up: "+label);
            var warmFeature=new NativeTreeGrowth();warmFeature.adapt(shared.feature().value(),shape[0],shape[1]);
            assertEquals(coldDeep,adapted(warmFeature.adapt(deep,shape[0],shape[1]),oak),"feature warm-up: "+label);
            assertEquals(freshDirect,directAfterDeep,"direct after deep: "+label);
            assertEquals(sharedDepth+outerDepth<=4,coldDeep,"depth limit: "+label);
        }
    }
    private static PlacedFeature wrap(PlacedFeature p){return new PlacedFeature(Holder.direct(new RandomSelectorFeature(List.of(),Holder.direct(p))),List.of());}
    /** Whether the innermost tree of a default-only selector chain was adapted (not the original object). */
    private static boolean adapted(PlacedFeature p,TreeFeature original){
        Feature f=p.feature().value();
        while(f instanceof RandomSelectorFeature r)f=r.defaultFeature().value().feature().value();
        return f!=original;
    }
    @Test void invalidBandsAreRejected() {
        var growth=new NativeTreeGrowth();var original=new NoOpFeature();
        assertThrows(IllegalArgumentException.class,()->growth.adapt(original,-1,1));
        assertThrows(IllegalArgumentException.class,()->growth.adapt(original,5,1));
        assertThrows(IllegalArgumentException.class,()->growth.adapt(original,2,3));
    }
    @Test @SuppressWarnings("unchecked") void smallestBroadleafTreesKeepLayeredCrownsAboveTheirTrunks()throws Exception {
        // Exercise the native branch attachments and leaf placement, not only the
        // adapted numbers. Radius 1/depth 2 previously left four leaves below a bare log.
        for(String name:List.of("birch_bees_0002_leaf_litter","fancy_oak_bees_0002_leaf_litter")) {
            var adapted=(TreeFeature)new NativeTreeGrowth().adapt(tree(name),0,0);
            for(int seed=0;seed<32;seed++) {
                var blocks=new HashMap<BlockPos,BlockState>();var leaves=new HashMap<BlockPos,BlockState>();
                WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class},(proxy,method,args)-> {
                        if(method.getName().equals("isStateAtPosition"))return ((Predicate<BlockState>)args[1]).test(
                            blocks.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState()));
                        if(method.getName().equals("isFluidAtPosition"))return false;
                        throw new AssertionError("Unexpected native world query: "+method.getName());
                    });
                var random=RandomSource.create(seed);int height=adapted.trunkPlacer().getBaseHeight();
                int foliageHeight=adapted.foliagePlacer().foliageHeight(random,height,adapted);
                int radius=adapted.foliagePlacer().foliageRadius(random,height-foliageHeight);
                var attachments=adapted.trunkPlacer().placeTrunk(level,(pos,state)->blocks.put(pos.immutable(),state),
                    random,height,BlockPos.ZERO,adapted);
                var setter=new FoliagePlacer.FoliageSetter() {
                    public void set(BlockPos pos,BlockState state){leaves.put(pos.immutable(),state);blocks.put(pos.immutable(),state);}
                    public boolean isSet(BlockPos pos){return leaves.containsKey(pos);}
                };
                for(var attachment:attachments)adapted.foliagePlacer().createFoliage(level,setter,random,adapted,
                    height,attachment,foliageHeight,radius);
                String at=name+" seed="+seed;
                assertTrue(leaves.size()>=20,at+" sparse crown: "+leaves.size());
                assertTrue(leaves.keySet().stream().map(BlockPos::getY).distinct().count()>=3,at+" flat crown");
                int highestLog=blocks.entrySet().stream().filter(e->e.getValue().is(Blocks.OAK_LOG))
                    .mapToInt(e->e.getKey().getY()).max().orElseThrow();
                int highestLeaf=leaves.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
                assertTrue(highestLeaf>=highestLog,at+" exposed trunk top");
            }
        }
    }
    /** Minecraft decays non-persistent leaves whose six-neighbour leaf path to a log is
     * longer than 6 (LeavesBlock distance 7). A crown widened past that line generates
     * leaves that later vanish in front of the player. */
    @Test @SuppressWarnings("unchecked") void adaptedCrownsStayWithinNativeLeafDecayDistance()throws Exception {
        var names=new ArrayList<>(ForestComposition.VARIANTS);names.remove("jungle_bush");
        names.addAll(List.of("cherry_checked","pale_oak_checked","orange_poplar","red_poplar","yellow_poplar"));
        var failures=new ArrayList<String>();var nativeWorst=new HashMap<String,Double>();
        // Neutral band (2,1) first: some vanilla trees (giant spruce) already have far leaves.
        int[][] bands=new int[15][];bands[0]=new int[]{2,1};int index=1;
        for(int s=0;s<5;s++)for(int c=0;c<3;c++)if(s!=2||c!=1)bands[index++]=new int[]{s,c};
        for(String name:names)for(int[] band:bands){int size=band[0],crown=band[1];
            var json=definition(name);
            var trunkJson=json.getAsJsonObject("trunk_placer").deepCopy();var leafJson=json.getAsJsonObject("foliage_placer").deepCopy();
            NativeTreeGrowth.reshapePlacers(trunkJson,leafJson,size,crown);
            var adapted=new TreeFeature(BlockStateProvider.holderOf(Blocks.OAK_LOG),TrunkPlacer.CODEC.parse(JsonOps.INSTANCE,trunkJson).getOrThrow(),
                BlockStateProvider.holderOf(Blocks.OAK_LEAVES),FoliagePlacer.CODEC.parse(JsonOps.INSTANCE,leafJson).getOrThrow(),
                Optional.empty(),new TwoLayersFeatureSize(1,0,1),List.of(),false,BlockStateProvider.holderOf(Blocks.DIRT));
            for(int seed=0;seed<24;seed++){
                var blocks=new HashMap<BlockPos,BlockState>();var leaves=new HashSet<BlockPos>();
                WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->{
                    if(method.getName().equals("isStateAtPosition"))return ((Predicate<BlockState>)args[1]).test(blocks.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState()));
                    if(method.getName().equals("isFluidAtPosition"))return false;
                    throw new AssertionError("Unexpected native world query: "+method.getName());});
                var random=RandomSource.create(seed);int height=adapted.trunkPlacer().getTreeHeight(random);
                int foliageHeight=adapted.foliagePlacer().foliageHeight(random,height,adapted);
                int radius=adapted.foliagePlacer().foliageRadius(random,height-foliageHeight);
                var attachments=adapted.trunkPlacer().placeTrunk(level,(pos,state)->blocks.put(pos.immutable(),state),random,height,BlockPos.ZERO,adapted);
                var setter=new FoliagePlacer.FoliageSetter(){
                    public void set(BlockPos pos,BlockState state){leaves.add(pos.immutable());blocks.putIfAbsent(pos.immutable(),state);}
                    public boolean isSet(BlockPos pos){return leaves.contains(pos);}};
                for(var attachment:attachments)adapted.foliagePlacer().createFoliage(level,setter,random,adapted,height,attachment,foliageHeight,radius);
                // Breadth-first leaf distance from every log, exactly as LeavesBlock propagates it.
                var distance=new HashMap<BlockPos,Integer>();var queue=new ArrayDeque<BlockPos>();
                blocks.forEach((pos,state)->{if(state.is(Blocks.OAK_LOG)){distance.put(pos,0);queue.add(pos);}});
                while(!queue.isEmpty()){var p=queue.poll();int d=distance.get(p);
                    for(var dir:net.minecraft.core.Direction.values()){var n=p.relative(dir);
                        if(leaves.contains(n)&&!distance.containsKey(n)&&blocks.get(n).is(Blocks.OAK_LEAVES)){distance.put(n,d+1);queue.add(n);}}}
                long decaying=leaves.stream().filter(p->blocks.get(p).is(Blocks.OAK_LEAVES)&&distance.getOrDefault(p,99)>6).count();
                double share=decaying/(double)Math.max(1,leaves.size());
                if(size==2&&crown==1)nativeWorst.merge(name,share,Math::max);
                else if(decaying>0&&share>nativeWorst.getOrDefault(name,0.)+1e-9)
                    failures.add(name+" size="+size+" crown="+crown+" seed="+seed+" decaying="+decaying+"/"+leaves.size()+" native worst="+nativeWorst.getOrDefault(name,0.));
            }
        }
        var byTree=new java.util.TreeMap<String,Integer>();for(String f:failures)byTree.merge(f.substring(0,f.indexOf(" size")),1,Integer::sum);
        assertTrue(failures.isEmpty(),failures.size()+" crowns decay beyond their native tree "+byTree+", e.g. "+failures.subList(0,Math.min(12,failures.size())));
        // Ordinary broadleaf and conifer crowns are decay-free natively and must stay so.
        for(String name:List.of("oak_bees_0002_leaf_litter","birch_bees_0002_leaf_litter","spruce_checked","pine_checked","acacia_checked"))
            assertEquals(0,nativeWorst.get(name),0,name);
    }
    private static JsonObject encoded(TrunkPlacer trunk){return TrunkPlacer.CODEC.encodeStart(JsonOps.INSTANCE,trunk).getOrThrow().getAsJsonObject();}
    private static JsonObject encoded(FoliagePlacer foliage){return FoliagePlacer.CODEC.encodeStart(JsonOps.INSTANCE,foliage).getOrThrow().getAsJsonObject();}
    private static JsonObject definition(String placed)throws Exception {
        var p=resource("placed_feature/"+placed);String id=p.get("feature").getAsString();
        return resource("feature/"+id.substring(id.indexOf(':')+1));
    }
    private static JsonObject resource(String path)throws Exception {
        try(var in=NativeTreeGrowthTest.class.getResourceAsStream("/data/minecraft/worldgen/"+path+".json")) {
            assertNotNull(in,path);return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static TreeFeature tree(String name)throws Exception {
        var json=definition(name);
        return new TreeFeature(BlockStateProvider.holderOf(Blocks.OAK_LOG),
            TrunkPlacer.CODEC.parse(JsonOps.INSTANCE,json.get("trunk_placer")).getOrThrow(),
            BlockStateProvider.holderOf(Blocks.OAK_LEAVES),
            FoliagePlacer.CODEC.parse(JsonOps.INSTANCE,json.get("foliage_placer")).getOrThrow(),
            Optional.empty(),new TwoLayersFeatureSize(1,0,1),List.of(),false,BlockStateProvider.holderOf(Blocks.DIRT));
    }
}
