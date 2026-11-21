package dev.freshgenerator.forest;

import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestComposition;
import dev.freshgenerator.core.ForestGrowth;
import dev.freshgenerator.core.ForestHabitat;
import dev.freshgenerator.core.ForestEcotone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import java.util.*;

/** Read-only environment scoped to ONE native FeaturePlacer. No global world cache,
 * no neighbouring chunk requests, no post-load edits, no candidate retries.
 */
public final class ForestEnvironment {
    public static final String SETTINGS="freshgenerator:highlands_v2";
    public static final List<String> FEATURES=List.of("trees_birch_and_oak_leaf_litter","trees_birch","birch_tall",
        "trees_taiga","trees_old_growth_pine_taiga","trees_old_growth_spruce_taiga","trees_jungle","trees_sparse_jungle",
        "trees_grove","trees_savanna","trees_windswept_savanna","dark_forest_vegetation","pale_garden_vegetation",
        "bamboo_vegetation","trees_dappled_forest","trees_flower_forest","trees_plains","trees_snowy","trees_meadow",
        "trees_swamp","trees_mangrove","trees_cherry","trees_badlands","trees_windswept_forest","trees_windswept_hills");
    public static final List<String> UNDERSTORY=List.of("patch_grass_forest","patch_grass_taiga_2",
        "patch_grass_jungle","patch_grass_savanna","patch_large_fern","patch_bush","bamboo","bamboo_light");
    private static final int[][] CARDINALS={{1,0},{-1,0},{0,1},{0,-1}};
    private final Set<PlacedFeature> features=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PlacedFeature,String> understory=new IdentityHashMap<>();
    private final Map<String,PlacedFeature> variants=new HashMap<>();
    private final Map<PlacedFeature,NativeForestCount> nativeCounts=new IdentityHashMap<>();
    private final Map<String,PlacedFeature> biomeFeatures=new HashMap<>();
    private final Map<String,ForestEcotone.Pool> biomePools=new HashMap<>();
    private final Map<PlacedFeature,List<String>> featureOwners=new IdentityHashMap<>();
    private final Map<PlacedFeature,Integer> proposalBudgets=new IdentityHashMap<>();
    private final Map<Long,ForestEcotone.Pool> poolSamples=new HashMap<>();
    private final Map<Long,List<ForestEcotone.Share>> ecotones=new HashMap<>();
    private final HolderLookup.Provider registries;
    private boolean metadataReady;
    private final NativeTreeGrowth growth;
    private final DensitySampler height,temperature,moisture;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private final BiomeManager biomes;
    private final long seed;
    private final int sea;
    private final Map<Long,Float> heights=new HashMap<>();
    private final Map<Long,ForestEcology.Site> sites=new HashMap<>();
    private final Map<Long,String[]> neighbours=new HashMap<>();
    private final Map<Long,double[]> proximity=new HashMap<>();
    private final WorldGenRegion region;

    private ForestEnvironment(WorldGenRegion region,NoiseBasedChunkGenerator generator) {
        this.region=region;
        var level=region.getLevel();var registry=level.registryAccess();var state=level.getChunkSource().randomState();
        registries=registry;
        growth=new NativeTreeGrowth(registry);
        height=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("coherent/height")).value());
        temperature=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("temperature")).value());
        moisture=sampler(state,registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key("vegetation")).value());
        for(String name:FEATURES) features.add(registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(
                ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+name))).value());
        for(String name:UNDERSTORY)understory.put(registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(
                ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+name))).value(),name);
        for(String name:ForestComposition.VARIANTS)variants.put(name,registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(
                ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+name))).value());
        biomes=level.getBiomeManager().withDifferentSource(generator.getBiomeSource().createCachingResolver(state));
        seed=region.getSeed()^0x6F7265737445434FL;sea=region.getSeaLevel();
    }
    private static DensitySampler sampler(RandomState state,DensityFunction f){return state.getSampler(f);}
    private static ResourceKey<DensityFunction> key(String name){return ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(SETTINGS+"/"+name));}
    public static ForestEnvironment create(WorldGenLevel level,ChunkGenerator generator) {
        if(!(level instanceof WorldGenRegion r)||!(generator instanceof NoiseBasedChunkGenerator g))return null;
        if(!g.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(SETTINGS))return null;
        return new ForestEnvironment(r,g);
    }
    public static ForestEcology.Family family(String id) {
        return ForestHabitat.familyForBiome(id);
    }
    /** Grass/understory passes do not need the native forest count catalog.
     * Prepared at most once in this owned FeaturePlacer environment; registry
     * references and derived data never escape its lifetime. */
    private void ensureMetadata(){
        if(metadataReady)return;
        for(var feature:features){var count=NativeForestCount.inspect(feature.placement());if(count!=null)nativeCounts.put(feature,count);}
        for(var biome:registries.lookupOrThrow(Registries.BIOME).listElements().toList()){
            String id=biome.key().identifier().toString();if(ForestHabitat.forBiome(id)==null)continue;
            var candidates=new ArrayList<PlacedFeature>();
            for(var stage:biome.value().getGenerationSettings().features())for(var feature:stage)if(nativeCounts.containsKey(feature.value()))candidates.add(feature.value());
            // Several independent tree features need their own accounting; do
            // not silently multiply their budgets or reinterpret modded data.
            if(candidates.size()!=1)continue;var feature=candidates.getFirst();
            biomeFeatures.put(id,feature);biomePools.put(id,new ForestEcotone.Pool(id,nativeCounts.get(feature).mean()));
            featureOwners.computeIfAbsent(feature,k->new ArrayList<>()).add(id);
        }
        metadataReady=true;ForestTelemetry.metadataPrepared();
    }
    /** Only the first native count/rarity modifier is replaced. The original
     * PlacedFeature identity remains the biome filter's top feature; all native
     * height, water, survival and space filters retain their original objects.
     * Homogeneous interiors and unknown placement layouts use the exact list. */
    public List<PlacementModifier> modifiers(PlacedFeature feature,List<PlacementModifier> original,BlockPos origin){
        if(!features.contains(feature))return original;
        ensureMetadata();
        proposalBudgets.remove(feature);var count=nativeCounts.get(feature);var owners=featureOwners.get(feature);
        if(count==null||owners==null)return original;
        int x0=Math.floorDiv(origin.getX(),16)*16,z0=Math.floorDiv(origin.getZ(),16)*16;
        var site=cachedSite(origin.getX(),origin.getZ(),height(origin.getX(),origin.getZ()));
        boolean transition=false;double maximum=count.mean();
        // Covers the complete support of every candidate in the origin square,
        // including an origin offset inside a chunk. No chunk data is requested.
        for(int dz=-32;dz<=64;dz+=16)for(int dx=-32;dx<=64;dx+=16){
            // A treeless or incompatible neighbour also starts a transition: the
            // ecotone blend then thins recruitment toward it (0.9.0).
            var pool=poolAt(x0+dx,z0+dz);if(pool==null){transition=true;continue;}
            boolean compatible=false;for(String owner:owners)if(ForestEcotone.compatible(owner,pool.biome(),site)){compatible=true;break;}
            if(!compatible){transition=true;continue;}maximum=Math.max(maximum,pool.capacity());
            // Different ecosystems mix even at equal native capacity (forest/cherry 10.1).
            if(Math.abs(pool.capacity()-count.mean())>=1e-9||!owners.contains(pool.biome()))transition=true;
        }
        if(!transition)return original;
        int budget=(int)Math.ceil(maximum);proposalBudgets.put(feature,budget);
        ForestTelemetry.edgeBudget(budget);
        return count.proposals(original,budget);
    }
    public boolean retain(PlacedFeature feature,BlockPos at) {
        if(!features.contains(feature)&&!understory.containsKey(feature))return true;
        // Own audit 21.11: snowy-biome trees ("on snow" placements put soil on snow) grew on glacier ice.
        // Glaciers exist only where the model height is at least 115, so lower candidates skip the block reads.
        if(at.getY()>GLACIER_MIN_Y&&features.contains(feature)&&onGlacier(at))return false;
        String id=biomes.getBiome(at.getX(),at.getY(),at.getZ()).unwrapKey().map(k->k.identifier().toString()).orElse("");
        var family=family(id);int x=at.getX(),z=at.getZ();float h=height(x,z);
        boolean budget=proposalBudgets.containsKey(feature);
        // Do not reinterpret underground decoration or elevated structures as forest floor.
        // An enlarged edge budget is never passed through unthinned: shared features
        // such as trees_snowy also serve excluded biomes like ice spikes (0.9.0).
        if(family==null||!natural(at,h))return !budget||compensate(feature,x,z);
        if(understory.containsKey(feature)) {
            // Interpolate cached environmental corners; individual herbs retain
            // their native positions and do not reveal 16-block cell boundaries.
            String name=understory.get(feature);
            double p=understoryProbability(x,z,family,name.contains("fern")||name.contains("bush")||name.contains("bamboo"),ForestHabitat.forBiome(id));
            boolean kept=ForestComposition.retainUnderstory(seed,x,z,p);
            ForestTelemetry.understory(kept);return kept;
        }
        var site=cachedSite(x,z,h);
        var fields=proposalBudgets.containsKey(feature)&&biomePools.containsKey(id)
            ?ForestEcotone.recruitment(seed,x,z,site,mix(x,z,id,site),proposalBudgets.get(feature))
            :ForestEcology.sample(seed,x,z,family,site,ForestHabitat.forBiome(id));
        boolean retain=ForestEcology.retain(seed,x,z,fields);
        if(budget&&!biomePools.containsKey(id))retain=retain&&compensate(feature,x,z);
        ForestTelemetry.record(family,retain,fields.opening());return retain;
    }
    public PlacedFeature replacement(PlacedFeature feature,BlockPos at) {
        if(!features.contains(feature))return null;
        int x=at.getX(),z=at.getZ();float h=height(x,z);
        if(!natural(at,h))return null;
        String id=biomes.getBiome(x,at.getY(),z).unwrapKey().map(k->k.identifier().toString()).orElse("");
        var family=family(id);if(family==null)return null;
        var site=cachedSite(x,z,h);
        String name=ForestComposition.select(seed,x,z,id,neighbours.get(packed(x,z)),family,site);
        return name==null?null:variants.get(name);
    }
    /** Extra edge recruitment uses the contributing native palette, rather than
     * multiplying the open biome's own rare native form into an artificial grove. */
    public PlacedFeature ecotoneFeature(PlacedFeature outer,BlockPos at){
        if(!proposalBudgets.containsKey(outer))return null;
        int x=at.getX(),z=at.getZ();float h=height(x,z);if(!natural(at,h))return null;
        String id=biomes.getBiome(x,at.getY(),z).unwrapKey().map(k->k.identifier().toString()).orElse("");
        if(!biomePools.containsKey(id))return null;var site=cachedSite(x,z,h);
        String chosen=ForestEcotone.palette(seed,x,z,site,mix(x,z,id,site));if(chosen.equals(id))return null;
        if(!ForestEcotone.mayBorrowPalette(id,chosen))return null;
        PlacedFeature donor;
        if((chosen.equals("minecraft:old_growth_pine_taiga")||chosen.equals("minecraft:old_growth_spruce_taiga"))
                &&!id.equals("minecraft:old_growth_pine_taiga")&&!id.equals("minecraft:old_growth_spruce_taiga")){
            String variant=ForestComposition.select(seed,x,z,"minecraft:taiga",new String[0],ForestEcology.Family.CONIFER,site);
            donor=variants.get(variant==null?"spruce_checked":variant);
        }else if((chosen.equals("minecraft:jungle")||chosen.equals("minecraft:bamboo_jungle"))
                &&ForestHabitat.forBiome(id)!=ForestHabitat.TROPICAL_CANOPY){
            String variant=ForestComposition.select(seed,x,z,"minecraft:sparse_jungle",new String[0],ForestEcology.Family.TROPICAL,site);
            donor=variants.get(variant==null?"jungle_tree":variant);
        }else donor=biomeFeatures.get(chosen);
        var palette=donor==null?null:NativeForestPalette.atSourcePosition(donor);
        if(palette!=null)ForestTelemetry.edgeBorrowed();
        return palette;
    }
    /** Same environmental profile for both selected variants and the retained native
     * selector. Random selectors are copied with their existing weights/filters. */
    public Feature grow(PlacedFeature outer,Feature feature,BlockPos at) {
        if(!features.contains(outer))return feature;
        var shape=growthAt(outer,at);
        return shape==null?feature:growth.adapt(feature,shape.sizeBand(),shape.crownBand());
    }
    public PlacedFeature grow(PlacedFeature replacement,BlockPos at) {
        var shape=growthAt(at);
        return shape==null?replacement:growth.adapt(replacement,shape.sizeBand(),shape.crownBand());
    }
    public PlacedFeature grow(PlacedFeature outer,PlacedFeature replacement,BlockPos at){
        var shape=growthAt(outer,at);return shape==null?replacement:growth.adapt(replacement,shape.sizeBand(),shape.crownBand());
    }
    public ForestGrowth.Shape growthAt(BlockPos at) {
        return growthAt(null,at);
    }
    private ForestGrowth.Shape growthAt(PlacedFeature outer,BlockPos at){
        int x=at.getX(),z=at.getZ();float h=height(x,z);
        if(!natural(at,h))return null;
        String id=biomes.getBiome(x,at.getY(),z).unwrapKey().map(k->k.identifier().toString()).orElse("");
        var family=family(id);if(family==null)return null;
        var site=cachedSite(x,z,h);
        var profile=outer!=null&&proposalBudgets.containsKey(outer)&&biomePools.containsKey(id)
            ?ForestEcotone.growth(seed,x,z,site,mix(x,z,id,site))
            :ForestGrowth.sample(seed,x,z,family,site,ForestHabitat.forBiome(id));
        return ForestGrowth.shape(seed,x,z,profile);
    }
    private ForestEcotone.Pool poolAt(int x,int z){
        long key=packed(x,z);if(poolSamples.containsKey(key))return poolSamples.get(key);
        String id=biomes.getBiome(x,(int)Math.ceil(height(x,z)),z).unwrapKey().map(k->k.identifier().toString()).orElse("");
        var pool=biomePools.get(id);if(poolSamples.size()>=1024)poolSamples.clear();poolSamples.put(key,pool);return pool;
    }
    private List<ForestEcotone.Share> mix(int x,int z,String id,ForestEcology.Site site){
        long key=packed(x,z);var cached=ecotones.get(key);if(cached!=null)return cached;
        var result=ForestEcotone.blend(x,z,biomePools.get(id),site,this::poolAt);
        if(ecotones.size()>=256)ecotones.clear();ecotones.put(key,result);return result;
    }
    public static boolean hint(String id) {
        return id.contains("tree")||id.contains("birch")||id.contains("taiga")||id.contains("jungle")
            ||id.contains("forest")||id.contains("mangrove")||id.contains("cherry")||id.contains("bamboo")
            ||id.contains("pale_garden_vegetation")||id.contains("swamp_oak")
            ||id.contains("grass")||id.contains("fern")||id.endsWith(":bush");
    }
    private double understoryProbability(int x,int z,ForestEcology.Family family,boolean fern,ForestHabitat habitat) {
        int x0=Math.floorDiv(x,16)*16,z0=Math.floorDiv(z,16)*16;
        double tx=(x-x0)/16.0,tz=(z-z0)/16.0,p=0;
        for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
            double weight=(dx==0?1-tx:tx)*(dz==0?1-tz:tz);if(weight==0)continue;
            int sx=x0+16*dx,sz=z0+16*dz;
            p+=weight*ForestComposition.understory(seed,x,z,family,cachedSite(sx,sz,height(sx,sz)),fern,habitat);
        }
        return Math.max(0,Math.min(1,p));
    }
    private ForestEcology.Site cachedSite(int x,int z,float h) {
        long key=packed(x,z);var value=sites.get(key);if(value!=null)return value;
        if(sites.size()>=256){sites.clear();neighbours.clear();}
        value=site(x,z,h);sites.put(key,value);return value;
    }
    private ForestEcology.Site site(int x,int z,float h) {
        double sx=(height(x+4,z)-height(x-4,z))/8.0,sz=(height(x,z+4)-height(x,z-4))/8.0;
        double neighbour=0,shore=0,edge=0;String[] nearby=new String[8];int index=0;
        for(int[] d:CARDINALS){
            int nx=x+16*d[0],nz=z+16*d[1];float nh=height(nx,nz);neighbour+=nh;
            int fx=x+32*d[0],fz=z+32*d[1];float fh=height(fx,fz);
            nearby[index]=biomeId(nx,nh,nz);nearby[index+4]=biomeId(fx,fh,fz);index++;
        }
        neighbours.put(packed(x,z),nearby);
        // 0.9.0: shore/edge proxies are interpolated between 16-block grid corners.
        // Sampled exactly at the candidate they jumped in steps of .15, changing
        // expected tree acceptance by ~25% across a single block.
        int gx=Math.floorDiv(x,16)*16,gz=Math.floorDiv(z,16)*16;double tx=(x-gx)/16.0,tz=(z-gz)/16.0;
        for(int cx=0;cx<=1;cx++)for(int cz=0;cz<=1;cz++){
            double w=(cx==0?1-tx:tx)*(cz==0?1-tz:tz);if(w==0)continue;
            double[] p=proximity(gx+16*cx,gz+16*cz);shore+=w*p[0];edge+=w*p[1];
        }
        return new ForestEcology.Site(h,sea,Math.hypot(sx,sz),h-neighbour/4,
            temperature.sampleValue(context,x,0,z),moisture.sampleValue(context,x,0,z),shore,edge);
    }
    private String biomeId(int x,float h,int z){return biomes.getBiome(x,(int)Math.ceil(h),z).unwrapKey().map(k->k.identifier().toString()).orElse("");}
    private double[] proximity(int x,int z){
        long key=packed(x,z);var cached=proximity.get(key);if(cached!=null)return cached;
        double shore=0,edge=0;
        for(int[] d:CARDINALS){
            int nx=x+16*d[0],nz=z+16*d[1];float nh=height(nx,nz);String id=biomeId(nx,nh,nz);
            // Stable proxy excludes later feature lakes; cannot depend on decoration order.
            if(nh<sea+1&&(id.contains("ocean")||id.equals("minecraft:river")||id.equals("minecraft:frozen_river")))shore+=.25;
            if(!wooded(id))edge+=.15;
            int fx=x+32*d[0],fz=z+32*d[1];if(!wooded(biomeId(fx,height(fx,fz),fz)))edge+=.10;
        }
        if(proximity.size()>=256)proximity.clear();
        double[] value={Math.min(1,shore),Math.min(1,edge)};proximity.put(key,value);return value;
    }
    /** Forest floor at the estimated envelope, or the actual generated surface here
     * (village grading or glacial ice can move it outside the envelope). */
    /** Land glaciers start at model height 115 (GlacialSurface); their ice is above that. */
    private static final int GLACIER_MIN_Y=115;
    /** Glacier ice (packed or blue ice) under the candidate, through at most its snow and moraine cover. */
    private boolean onGlacier(BlockPos at){
        var p=new BlockPos.MutableBlockPos(at.getX(),at.getY()-1,at.getZ());
        for(int i=0;i<6;i++,p.move(0,-1,0)){
            var s=region.getBlockState(p);
            if(s.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)||s.is(net.minecraft.world.level.block.Blocks.BLUE_ICE))return true;
            if(!(s.isAir()||s.is(net.minecraft.world.level.block.Blocks.SNOW)||s.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
                ||s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)||s.is(net.minecraft.world.level.block.Blocks.GRAVEL)))return false;
        }
        return false;
    }
    private boolean natural(BlockPos at,float h){
        if(at.getY()>=h-6&&at.getY()<=h+8)return true;
        return Math.abs(at.getY()-region.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,at.getX(),at.getZ()))<=1;
    }
    /** Thin an enlarged edge budget back to the native expectation where no ecotone applies. */
    private boolean compensate(PlacedFeature feature,int x,int z){
        var count=nativeCounts.get(feature);Integer budget=proposalBudgets.get(feature);
        if(count==null||budget==null)return true;
        return ForestComposition.retainUnderstory(seed^0x42756467L,x,z,Math.min(1,count.mean()/budget));
    }
    // Unmodified wooded biomes still form a canopy boundary, not an artificial clearing.
    private static boolean wooded(String id) {
        var habitat=ForestHabitat.forBiome(id);
        return habitat!=null&&(!habitat.open()||habitat==ForestHabitat.OPEN_GROVE)&&!id.equals("minecraft:windswept_hills")
            &&!id.equals("minecraft:windswept_gravelly_hills");
    }
    private float height(int x,int z){long k=packed(x,z);Float h=heights.get(k);if(h!=null)return h;
        float value=height.sampleValue(context,x,0,z);if(!Float.isFinite(value))throw new IllegalStateException("Invalid forest height");
        if(heights.size()>=1024)heights.clear();heights.put(k,value);return value;}
    private static long packed(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
