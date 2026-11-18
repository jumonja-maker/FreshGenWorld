package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.LandscapeTerrainProgram;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/** Current landscape layer; old compiler stages remain reproducible and frozen. */
public final class LandscapeTerrainCompiler {
    public static final String P=CoherentSurfaceCompiler.PREFIX,L=P+"landscape/";
    public static final String SETTINGS=SurfaceOnlyCompiler.SETTINGS;
    public static final Set<String> REPLACED=Set.of(path(P+"continents"),path(P+"coherent/height"),
            SETTINGS,"data/freshgenerator/terrain_profile/highlands_v2.json");
    private static final Gson G=new Gson();
    private LandscapeTerrainCompiler(){}

    public static void apply(Map<String,JsonElement> out) {
        Objects.requireNonNull(out);
        Map<String,JsonElement> staged=new TreeMap<>(out);
        applyStaged(staged);
        out.putAll(staged);
    }
    private static void applyStaged(Map<String,JsonElement> out) {
        if(out.containsKey(path(L+"original_continents")))throw new IllegalStateException("Landscape layer already present");
        for(String name:List.of("continents","base_offset","erosion","ridges_folded","coherent/height"))
            Objects.requireNonNull(out.get(path(P+name)),"Missing terrain input "+name);
        if(!new JsonPrimitive(1).equals(out.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION))))
            throw new IllegalStateException("Landscape requires the explicitly disabled cave layer");
        out.put(path(L+"original_continents"),out.get(path(P+"continents")).deepCopy());
        noise(out,"macro_noise",-12,2,1);
        noise(out,"uplift_noise",-10,3,1);
        noise(out,"giant_noise",-11,2,1);
        noise(out,"coast_noise",-9,3,1);
        noise(out,"relief_noise",-7,3,1);
        noise(out,"detail_noise",-5,3,1);
        noise(out,"landform_noise",-11,2,1);
        // The broad perturbation changes continental variety without a second, unrelated
        // coastline mask: biome climate and native splines consume this same c-axis.
        out.put(path(P+"continents"),cache(G.toJsonTree(continentExpression().json())));
        var program=LandscapeTerrainProgram.compile(P);
        for(var e:program.nodes().entrySet())put(out,e.getKey(),cache(G.toJsonTree(e.getValue().json())));
        put(out,"height_excess",cache(max(sub(L+"height_raw",264),0)));
        put(out,"height_limited",cache(max(-48,add(min(L+"height_raw",264),
                mul(46,div(L+"height_excess",add(46,L+"height_excess")))))));
        out.put(path(P+"coherent/height"),cache(object("type","minecraft:interpolated","cell_size_xz",4,
                "cell_size_y",1,"input",L+"height_limited")));
        // SurfaceOnly's density, climate depth, forest oracle and preliminary surface
        // all refer to coherent/height; replace that single authority, not consumers.
        JsonObject settings=out.get(SETTINGS).deepCopy().getAsJsonObject();
        for(String field:List.of("continentality","mountain","giant","coast","coast_rock","height_raw",
                "form_massif","form_ridge","form_plateau","eroded_lowland","valley_floor","footslope","lowland_quiet"))
            settings.getAsJsonArray("debug_functions").add(object("label","FG landscape "+field,"function",L+field));
        out.put(SETTINGS,settings);
        String metadataPath="data/freshgenerator/terrain_profile/highlands_v2.json";
        JsonObject metadata=out.get(metadataPath).deepCopy().getAsJsonObject();
        metadata.addProperty("activeLandscapeAlgorithm",3);
        metadata.addProperty("activeHeightInterpolationBlocks",4);
        metadata.addProperty("activeOceanDeepening",0);
        metadata.addProperty("giantMountains","continuous rare regional uplift; frequency measured per seed, not a per-chunk spawn probability");
        metadata.addProperty("mountainForms","continuous regional massifs, native ridge ranges and partly subdued plateau shoulders");
        metadata.addProperty("lowlandForms","quieter fine relief on eroded low country and native valley floors; intermediate mountain footslope aprons");
        out.put(metadataPath,metadata);
    }
    /** Preserve the native mushroom-island continental tail exactly; introduce broad
     * variation only through a smooth transition into the ordinary ocean domain. */
    public static DensityExpr continentExpression() {
        DensityExpr original=DensityExpr.ref(L+"original_continents");
        DensityExpr weight=NaturalTerrainProgram.smootherstep(-1.05,-.80,original);
        DensityExpr delta=DensityExpr.add(DensityExpr.scale(original,-.07),
                DensityExpr.scale(DensityExpr.ref(L+"macro_noise"),.10));
        return DensityExpr.add(original,DensityExpr.mul(weight,delta));
    }
    private static void noise(Map<String,JsonElement> out,String name,int octave,int count,double scale) {
        String id=L+name;
        String noisePath=VanillaAssets.path("noise",id);
        if(out.containsKey(noisePath))throw new IllegalStateException("Existing landscape noise "+id);
        out.put(noisePath,object("base_octave",octave,"octave_count",count,"base_amplitude",1.0));
        put(out,name,cache(object("type","minecraft:noise","noise",id,"xz_scale",scale,"y_scale",0)));
    }
    private static void put(Map<String,JsonElement> out,String name,JsonElement value) {
        if(out.putIfAbsent(path(L+name),value)!=null)throw new IllegalStateException("Existing landscape node "+name);
    }
    public static String path(String id){return VanillaAssets.path("density_function",id);}
    private static JsonObject object(Object... pairs){JsonObject o=new JsonObject();for(int i=0;i<pairs.length;i+=2)o.add((String)pairs[i],G.toJsonTree(pairs[i+1]));return o;}
    private static JsonObject cache(Object value){return object("type","minecraft:cache","input",value);}
    private static JsonObject bin(String op,Object a,Object b){return object("type","minecraft:"+op,"left",a,"right",b);}
    private static JsonObject add(Object a,Object b){return bin("add",a,b);}
    private static JsonObject sub(Object a,Object b){return bin("sub",a,b);}
    private static JsonObject mul(Object a,Object b){return bin("mul",a,b);}
    private static JsonObject div(Object a,Object b){return bin("div",a,b);}
    private static JsonObject min(Object a,Object b){return bin("min",a,b);}
    private static JsonObject max(Object a,Object b){return bin("max",a,b);}
}
