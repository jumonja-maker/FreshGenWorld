package dev.freshgenerator;

import dev.freshgenerator.core.*;
import java.util.*;
import java.util.stream.IntStream;
import java.net.JarURLConnection;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForestEcotoneTest {
    private static final ForestEcology.Site GOOD=new ForestEcology.Site(90,63,.1,-.3,.20,.25,0,.5);
    private static ForestEcotone.Pool pool(String biome,double capacity){return new ForestEcotone.Pool("minecraft:"+biome,capacity);}
    private static double rate(long seed,int x,int z,ForestEcotone.Pool own,ForestEcotone.Source source,double proposals){
        return ForestEcotone.recruitment(seed,x,z,GOOD,ForestEcotone.blend(x,z,own,GOOD,source),proposals).retention();
    }
    @Test void homogeneousInteriorsKeepNativeRecruitmentAndGeometry(){
        for(long seed:new long[]{42,2026111501,-3910524569189800596L})
            for(var own:List.of(pool("forest",10.1),pool("snowy_plains",.1),pool("jungle",50.1),pool("meadow",.01)))
                for(int x=-80;x<=80;x+=7){
                    var mix=ForestEcotone.blend(x,-31,own,GOOD,(xx,zz)->own);
                    assertEquals(List.of(new ForestEcotone.Share(own,1)),mix);
                    var expected=ForestEcology.sample(seed,x,-31,ForestHabitat.familyForBiome(own.biome()),GOOD,ForestHabitat.forBiome(own.biome()));
                    var actual=ForestEcotone.recruitment(seed,x,-31,GOOD,mix,own.capacity());
                    assertEquals(expected.retention(),actual.retention(),1e-15);
                    assertEquals(ForestGrowth.sample(seed,x,-31,ForestHabitat.familyForBiome(own.biome()),GOOD,ForestHabitat.forBiome(own.biome())),
                        ForestEcotone.growth(seed,x,-31,GOOD,mix));
                }
    }
    @Test void nativeHundredfoldCapacityStepsBecomeContinuousExpectedRecruitment(){
        for(var pair:List.of(List.of(pool("forest",10.1),pool("plains",.05)),
                List.of(pool("snowy_taiga",10.1),pool("snowy_plains",.1)),
                List.of(pool("jungle",50.1),pool("sparse_jungle",2.1)),
                List.of(pool("dark_forest",16),pool("flower_forest",6.1)),
                List.of(pool("wooded_badlands",5.1),pool("savanna",1.1)))){
            var forest=pair.getFirst();var open=pair.getLast();ForestEcotone.Source source=(x,z)->x<0?forest:open;
            double proposals=Math.ceil(Math.max(forest.capacity(),open.capacity()));
            for(long seed:new long[]{42,2026111501,2026111504,-3910524569189800596L}){
                for(int x=-64;x<64;x++){
                    double first=rate(seed,x,-19,x<0?forest:open,source,proposals);
                    double second=rate(seed,x+1,-19,x+1<0?forest:open,source,proposals);
                    assertTrue(Math.abs(first-second)<.08,pair+" one-block discontinuity at "+x+": "+first+" / "+second);
                }
                assertTrue(rate(seed,-64,-19,forest,source,proposals)>rate(seed,64,-19,open,source,proposals));
            }
        }
    }
    @Test void rareMeadowFormsDoNotReceiveTheBorrowedForestPopulation(){
        var meadow=pool("meadow",.01);var forest=pool("forest",10.1);
        var mix=List.of(new ForestEcotone.Share(meadow,.5),new ForestEcotone.Share(forest,.5));
        int rare=0;
        for(int z=-10000;z<10000;z++)if(ForestEcotone.palette(2026111501,0,z,GOOD,mix).equals(meadow.biome()))rare++;
        assertTrue(rare<80,"Rare meadow architecture was multiplied into an entire forest: "+rare);
        assertEquals(meadow.biome(),ForestEcotone.palette(42,0,0,GOOD,List.of(new ForestEcotone.Share(meadow,1))));
    }
    @Test void unsupportedAndDifferentWaterRootedNichesRetainTheirOwnPath(){
        assertFalse(ForestEcotone.compatible("minecraft:forest","other:forest",GOOD));
        assertFalse(ForestEcotone.compatible("minecraft:mangrove_swamp","minecraft:jungle",GOOD));
        assertFalse(ForestEcotone.compatible("minecraft:swamp","minecraft:forest",GOOD));
        assertFalse(ForestEcotone.compatible("minecraft:jungle","minecraft:desert",GOOD));
        var dry=new ForestEcology.Site(90,63,.1,0,.8,-.95,0,.5);
        assertFalse(ForestEcotone.compatible("minecraft:jungle","minecraft:savanna",dry));
        var forest=pool("forest",10.1);
        assertEquals(List.of(new ForestEcotone.Share(forest,1)),ForestEcotone.blend(0,0,forest,GOOD,(x,z)->null));
    }
    @Test void mixturesAreIndependentOfOrderThreadsAndSignedGridBoundaries(){
        var forest=pool("forest",10.1);var plain=pool("plains",.05);
        ForestEcotone.Source source=(x,z)->x+z<0?forest:plain;
        var serial=IntStream.range(-257,257).mapToObj(x->ForestEcotone.blend(x,-23,x-23<0?forest:plain,GOOD,source)).toList();
        var parallel=IntStream.range(-257,257).parallel().mapToObj(x->ForestEcotone.blend(x,-23,x-23<0?forest:plain,GOOD,source)).toList();
        assertEquals(serial,parallel);
        for(var shares:serial)assertEquals(1,shares.stream().mapToDouble(ForestEcotone.Share::weight).sum(),1e-12);
    }
    @Test void palePaletteBoundaryGuardCoversEveryActualRegisteredNativePair()throws Exception{
        var connection=(JarURLConnection)getClass().getResource("/data/minecraft/worldgen/biome/forest.json").openConnection();
        var biomes=new ArrayList<String>();
        try(var jar=new ZipFile(Path.of(connection.getJarFileURL().toURI()).toFile())){
            for(var entry:Collections.list(jar.entries())){
                String name=entry.getName(),prefix="data/minecraft/worldgen/biome/";
                if(name.startsWith(prefix)&&name.endsWith(".json"))biomes.add("minecraft:"+name.substring(prefix.length(),name.length()-5));
            }
        }
        assertTrue(biomes.size()>=67);
        for(String own:biomes)for(String donor:biomes){
            boolean paleBoundary=!own.equals(donor)&&(own.equals("minecraft:pale_garden")||donor.equals("minecraft:pale_garden"));
            assertEquals(!paleBoundary,ForestEcotone.mayBorrowPalette(own,donor),own+" / "+donor);
        }
        assertFalse(ForestEcotone.mayBorrowPalette(null,"minecraft:forest"));
        assertFalse(ForestEcotone.mayBorrowPalette("minecraft:forest",null));
    }
    @Test void paleGardenKeepsMixedDensityAndGrowthWhileItsNativePaletteStaysLocal(){
        var pale=pool("pale_garden",16);var forest=pool("forest",10.1);
        assertTrue(ForestEcotone.compatible(pale.biome(),forest.biome(),GOOD));
        var shares=ForestEcotone.blend(0,0,pale,GOOD,(x,z)->x<0?pale:forest);
        assertEquals(2,shares.size());
        var mixed=ForestEcotone.recruitment(42,0,0,GOOD,shares,16);
        var nativePale=ForestEcotone.recruitment(42,0,0,GOOD,List.of(new ForestEcotone.Share(pale,1)),16);
        var nativeForest=ForestEcotone.recruitment(42,0,0,GOOD,List.of(new ForestEcotone.Share(forest,1)),16);
        assertTrue(mixed.retention()>Math.min(nativePale.retention(),nativeForest.retention()));
        assertTrue(mixed.retention()<Math.max(nativePale.retention(),nativeForest.retention()));
        assertNotNull(ForestEcotone.growth(42,0,0,GOOD,shares));
        assertFalse(ForestEcotone.mayBorrowPalette(pale.biome(),forest.biome()));
        assertFalse(ForestEcotone.mayBorrowPalette(forest.biome(),pale.biome()));
        assertTrue(ForestEcotone.mayBorrowPalette(pale.biome(),pale.biome()));
    }
}
