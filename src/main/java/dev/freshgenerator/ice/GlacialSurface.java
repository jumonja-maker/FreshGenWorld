package dev.freshgenerator.ice;

import dev.freshgenerator.core.GlacialIce;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import java.util.*;

/** One surface-generation pass, before native features/structures. Writes only
 * the owning chunk; samples adjacent terrain through immutable density fields.
 * No neighbour chunk requests, saved-world edits, fluid ticks or global caches. */
public final class GlacialSurface {
    private GlacialSurface() { }
    private static final ThreadLocal<Boolean> ACTIVE=ThreadLocal.withInitial(()->false);
    private static final Set<String> OCEANS=Set.of("minecraft:frozen_ocean","minecraft:deep_frozen_ocean",
        "minecraft:cold_ocean","minecraft:deep_cold_ocean");
    public static boolean begin(){boolean previous=ACTIVE.get();ACTIVE.set(true);return previous;}
    public static void end(boolean previous){if(previous)ACTIVE.set(true);else ACTIVE.remove();}
    public static boolean active(){return ACTIVE.get();}

    public static void apply(ChunkAccess chunk,RandomState state,MaterialSystem system,
                             BiomeManager biomes,Set<Holder<Biome>> possibleBiomes,NoiseGeneratorSettings settings) {
        var environment=new Environment(state,system,biomes,settings);
        var at=new BlockPos.MutableBlockPos();boolean changed=false;
        int x0=chunk.getPos().getMinBlockX(),z0=chunk.getPos().getMinBlockZ(),sea=system.getSeaLevel();
        for(int z=z0;z<z0+16;z++)for(int x=x0;x<x0+16;x++){
            double h=environment.height(x,z);
            if(h<sea-5){
                var ice=environment.ocean(x,z);
                if(!ice.present())continue;
                for(int y=Math.max(chunk.getMinY(),ice.baseY());y<=Math.min(chunk.getMaxY(),ice.topY());y++){
                    at.set(x,y,z);BlockState old=chunk.getBlockState(at);
                    if(!oceanReplaceable(old))continue;
                    BlockState next=y>ice.topY()-ice.snowDepth()?Blocks.SNOW_BLOCK.defaultBlockState():
                        ice.blue()&&y<sea-4?Blocks.BLUE_ICE.defaultBlockState():Blocks.PACKED_ICE.defaultBlockState();
                    chunk.setBlockState(at,next);changed=true;
                }
            }else if(h>=115){
                var ice=environment.land(x,z);
                if(ice.thickness()==0)continue;
                int bed=(int)Math.floor(h),top=Math.min(chunk.getMaxY()-1,bed+ice.thickness()-ice.crevasseDepth());
                // A glacier cannot bridge an exposed cave mouth or be planted on
                // a floating snow layer. Its basal rock must already exist.
                at.set(x,bed,z);
                if(!basalSolid(chunk.getBlockState(at)))continue;
                // Preserve the existing bed and every cave roof below it.
                // Glacial ice accumulates above rock; it is not a replacement
                // material for the last solid slab over an old cavity.
                int bottom=bed+1;
                for(int y=bottom;y<=top;y++){
                    at.set(x,y,z);BlockState old=chunk.getBlockState(at);
                    // Retain ores and other non-terrain blocks in the foundation.
                    if(!landReplaceable(old))continue;
                    BlockState next=y==top&&ice.moraine()?Blocks.GRAVEL.defaultBlockState():
                        y>top-ice.snowDepth()?Blocks.SNOW_BLOCK.defaultBlockState():
                        y<bed+ice.thickness()/2||ice.crevasseDepth()>0?Blocks.BLUE_ICE.defaultBlockState():Blocks.PACKED_ICE.defaultBlockState();
                    chunk.setBlockState(at,next);changed=true;
                }
            }
        }
        if(changed)Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,Heightmap.Types.OCEAN_FLOOR_WG));
    }
    private static boolean oceanReplaceable(BlockState s){return s.isAir()||s.getFluidState().is(FluidTags.WATER)
        ||s.is(Blocks.ICE)||s.is(Blocks.PACKED_ICE)||s.is(Blocks.BLUE_ICE)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.SNOW);}
    private static boolean ground(BlockState s){return s.is(Blocks.STONE)||s.is(Blocks.DEEPSLATE)||s.is(Blocks.DIRT)
        ||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.GRAVEL)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.POWDER_SNOW)
        ||s.is(Blocks.PACKED_ICE)||s.is(Blocks.BLUE_ICE)||s.is(Blocks.ICE)||s.is(Blocks.CALCITE)
        ||s.is(Blocks.GRANITE)||s.is(Blocks.ANDESITE)||s.is(Blocks.DIORITE)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.PODZOL);}
    private static boolean landReplaceable(BlockState s){return s.isAir()||ground(s)||s.is(Blocks.SNOW);}
    private static boolean basalSolid(BlockState s){return !s.isAir()&&s.getFluidState().isEmpty()&&!s.is(Blocks.SNOW);}
    private static String id(Holder<Biome> b){return b.unwrapKey().map(k->k.identifier().toString()).orElse("");}
    /** Continuous in temperature; independent of the ocean biome ID at the border. */
    public static double oceanColdness(double temperature){return smooth(-.15,-.60,temperature);}
    private static double smooth(double a,double b,double v){double t=Math.clamp((v-a)/(b-a),0,1);return Math.clamp(t*t*t*(t*(t*6-15)+10),0,1);}

    /** Read-only, per-chunk environment, also usable by the separate audit. */
    public static final class Environment {
        private final RandomState state;
        private final int sea;
        private final DensitySampler terrainHeight;
        private final SamplerContext context=SamplerContext.builder().enableCaches().build();
        private final Climate.Sampler climate;
        private final BiomeManager biomes;
        private final Map<Long,Double> heights=new HashMap<>();
        private final Map<Long,Double> broadHeights=new HashMap<>();
        public Environment(RandomState state,MaterialSystem system,BiomeManager biomes,NoiseGeneratorSettings settings){
            this.state=state;this.sea=system.getSeaLevel();this.biomes=biomes;
            // Use the actual four-block terrain envelope. chunkSurfaceLevel is
            // a separate coarse sixteen-block oracle and is not the solid bed.
            terrainHeight=state.getSampler(settings.debugFunctions().functions().stream()
                .filter(f->f.label().equals("FG cave access: surface")).findFirst().orElseThrow().function());
            climate=state.createClimateSampler(context);
        }
        public double height(int x,int z){
            long key=((long)x<<32)^(z&0xffffffffL);
            // Only this chunk and its small sampling halo; never a world cache.
            if(heights.size()>8192)heights.clear();
            return heights.computeIfAbsent(key,k->(double)terrainHeight.sampleValue(context,x,0,z));
        }
        private Holder<Biome> biome(int x,int z,double h){return biomes.getBiome(x,(int)Math.floor(Math.max(sea,h)),z);}
        /** Routed climate at the real ground envelope, shared with surface biome selection. */
        public double surfaceTemperature(int x,int z){
            return climate.temperature().sampleValue(x,(int)Math.floor(height(x,z)),z);
        }
        public GlacialIce.OceanColumn ocean(int x,int z){
            double h=height(x,z);String biome=id(biome(x,z,h));
            double cold=0;
            if(OCEANS.contains(biome)){
                // One continuous climate curve. A per-ID factor cut every iceberg
                // straddling the frozen/cold border into a vertical step. The
                // cold-ocean fringe still fades out well before temperate seas.
                cold=oceanColdness(climate.temperature().sampleValue(x,sea,z));
                cold*=smooth(sea-5,sea-19,h);
            }
            return GlacialIce.ocean(state.seed(),x,z,sea,(int)Math.floor(h),Math.clamp(cold,0,1));
        }
        public GlacialIce.LandColumn land(int x,int z){
            double h=height(x,z);
            // The climate router restores the original cave climate deep under
            // H. Sampling it at Y0 therefore disagreed with the surface biome
            // region. Read the actual surface climate once; its compiler already
            // applies altitude cooling, so a second lapse rate is incorrect.
            double temperature=surfaceTemperature(x,z);
            double cold=smooth(.25,-.50,temperature);
            if(h<115||cold<=.50)return new GlacialIce.LandColumn(0,0,0,false,0);
            double gx=(height(x+16,z)-height(x-16,z))/32,gz=(height(x,z+16)-height(x,z-16))/32;
            double surface=broadHeight(x,z);
            double concavity=(surface-h)/4;
            // Sample upslope relief as the nearby accumulation-area proxy.
            double high=h;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)high=Math.max(high,height(x+dx*48,z+dz*48));
            double supply=smooth(0,45,high-h);
            var profile=GlacialIce.land(state.seed(),x,z,new GlacialIce.LandSite(h,Math.hypot(gx,gz),concavity,cold,supply,gx,gz));
            // The glacier fills the valley toward a broad free surface; its
            // thickness must not become a separate hill on each local low spot.
            int thickness=Math.min(profile.thickness(),Math.max(0,(int)Math.floor(surface+3*profile.coverage()-h)));
            if(thickness==0)return new GlacialIce.LandColumn(0,0,0,false,0);
            int crack=Math.min(profile.crevasseDepth(),Math.max(0,thickness-3));
            int snow=crack>0?0:Math.min(profile.snowDepth(),thickness);
            return new GlacialIce.LandColumn(thickness,snow,crack,profile.moraine(),profile.coverage());
        }
        private double broadHeight(int x,int z){
            int cx=Math.floorDiv(x,32),cz=Math.floorDiv(z,32);double tx=Math.floorMod(x,32)/32.0,tz=Math.floorMod(z,32)/32.0;
            double a=broadCorner(cx,cz),b=broadCorner(cx+1,cz),c=broadCorner(cx,cz+1),d=broadCorner(cx+1,cz+1);
            return (a+(b-a)*tx)*(1-tz)+(c+(d-c)*tx)*tz;
        }
        private double broadCorner(int cx,int cz){
            long key=((long)cx<<32)^(cz&0xffffffffL);
            if(broadHeights.size()>256)broadHeights.clear();
            return broadHeights.computeIfAbsent(key,k->{
                int x=cx*32,z=cz*32;double sum=0;
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)sum+=height(x+dx*24,z+dz*24)*(dx==0?2:1)*(dz==0?2:1);
                return sum/16;
            });
        }
    }
}
