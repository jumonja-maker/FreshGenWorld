package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/** Native noise caves below the finished landscape. All fields use world coordinates;
 * no chunk-local carve shapes, post-load edits or replacement fluid simulation. */
public final class CaveNetworkCompiler {
    public static final String P=CoherentSurfaceCompiler.PREFIX,N=P+"cave_network/";
    public static final String SETTINGS=SurfaceOnlyCompiler.SETTINGS;
    public static final Set<String> REPLACED=Set.of(SurfaceOnlyCompiler.FINAL_DENSITY,
        SurfaceOnlyCompiler.CAVE_WEIGHT,SETTINGS);
    private static final Gson G=new Gson();
    private CaveNetworkCompiler(){}
    public static void apply(Map<String,JsonElement> output) {
        var out=new TreeMap<>(Objects.requireNonNull(output));
        for(String p:REPLACED)Objects.requireNonNull(out.get(p),"Missing cave input "+p);
        if(!new JsonPrimitive(1).equals(out.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION))))
            throw new IllegalStateException("Independent native carvers must remain disabled");
        JsonObject old=out.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject();
        if(!old.get("left").getAsString().equals(P+"coherent/surface_density")
                ||!old.getAsJsonObject("right").get("type").getAsString().equals("minecraft:beardifier"))
            throw new IllegalStateException("Expected finished solid landscape and native structure fit");
        put(out,"enabled",new JsonPrimitive(1));
        put(out,"mountain_gate",cache(mul(smooth(120,150,P+"coherent/height"),
            smooth(.35,.65,P+"landscape/mountain"))));
        put(out,"entrance_gate",cache(mul(N+"mountain_gate",smooth(.15,.40,P+"cave_access/entrance_noise"))));
        // Broad three-dimensional chambers never enter the structural surface roof.
        // Letting the whole chamber field rise with the entrance gate detaches roofs
        // and cuts thin rock fins even though the surrounding air remains connected.
        put(out,"deep_ceiling",cache(min(sub(P+"coherent/height",40),20)));
        // Gate the footprint, not its vertical ceiling. A ceiling just below H
        // leaves a thin solid cap suspended above an otherwise open access column.
        put(out,"ceiling",cache(P+"coherent/height"));
        put(out,"depth_limit",max(mul(.125,sub("minecraft:y",N+"deep_ceiling")),mul(.125,sub(-54,"minecraft:y"))));
        // Broad chambers retain native columns. Columns never close the linking passages.
        put(out,"chambers",max(add(mul(3,square(noise("minecraft:cave_layer",1,8))),
            add(.12,noise("minecraft:cave_cheese",.80,.55))),
            object("type","minecraft:range_choice","input",P+"caves/pillars","min_inclusive",-1000000,
                "max_exclusive",.03,"when_in_range",-1000000,"when_out_of_range",P+"caves/pillars")));
        put(out,"native_passages",min(P+"caves/entrances",add(P+"caves/spaghetti_2d",P+"caves/spaghetti_roughness_function")));
        addNoise(out,"link_a");addNoise(out,"link_b");
        put(out,"links",sub(max(abs(noise(N+"link_a",1,1.25)),abs(noise(N+"link_b",1,1.25))),.16));
        put(out,"body",object("type","minecraft:interpolated","cell_size_xz",4,"cell_size_y",4,
            "input",min(N+"chambers",min(N+"native_passages",N+"links"))));
        // A persistent narrow mountain access footprint. At Y0 the same two noise
        // fields occur in the deeper width-.16 links, so every width-.11 access
        // column joins that network there. Matching four-block interpolation is
        // essential to this inclusion. A second 3D noise tube could close above
        // the deep roof and strand the apparent entrance in a sealed pocket.
        put(out,"access_footprint",object("type","minecraft:interpolated","cell_size_xz",4,"cell_size_y",4,
            "input",sub(max(abs(noise(N+"link_a",1,0)),abs(noise(N+"link_b",1,0))),.11)));
        put(out,"access_body",max(N+"access_footprint",sub(.5,N+"entrance_gate")));
        put(out,"access_limit",max(mul(.125,sub("minecraft:y",N+"ceiling")),mul(.125,sub(-4,"minecraft:y"))));
        // Limits are applied after interpolation; lowland roofs and the bottom
        // remain sealed. The access branch ends below its guaranteed Y0 junction.
        put(out,"final_body",min(P+"coherent/surface_density",min(max(N+"body",N+"depth_limit"),
            max(N+"access_body",N+"access_limit"))));
        out.put(SurfaceOnlyCompiler.FINAL_DENSITY,add(N+"final_body",old.get("right").deepCopy()));
        out.put(SurfaceOnlyCompiler.CAVE_WEIGHT,new JsonPrimitive(1));
        var settings=out.get(SETTINGS).deepCopy().getAsJsonObject();
        for(String name:List.of("enabled","mountain_gate","entrance_gate","body","depth_limit","final_body"))
            settings.getAsJsonArray("debug_functions").add(object("label","FG cave network "+name,"function",N+name));
        out.put(SETTINGS,settings);output.putAll(out);
    }
    private static void addNoise(Map<String,JsonElement> out,String name){
        String p=VanillaAssets.path("noise",N+name);
        if(out.putIfAbsent(p,object("base_octave",-6,"octave_count",2,"base_amplitude",1.0))!=null)
            throw new IllegalStateException("Existing cave noise: "+name);
    }
    private static String path(String id){return VanillaAssets.path("density_function",id);}
    private static void put(Map<String,JsonElement> out,String n,JsonElement v){
        if(out.putIfAbsent(path(N+n),v)!=null)throw new IllegalStateException("Cave network already defined: "+n);
    }
    private static JsonElement smooth(double a,double b,String v){return G.toJsonTree(NaturalTerrainProgram.smootherstep((float)a,(float)b,DensityExpr.ref(v)).json());}
    private static JsonObject object(Object... pairs){var o=new JsonObject();for(int i=0;i<pairs.length;i+=2)o.add((String)pairs[i],G.toJsonTree(pairs[i+1]));return o;}
    private static JsonObject cache(Object v){return object("type","minecraft:cache","input",v);}
    private static JsonObject noise(String id,double xz,double y){return object("type","minecraft:noise","noise",id,"xz_scale",xz,"y_scale",y);}
    private static JsonObject abs(Object a){return object("type","minecraft:abs","input",a);}
    private static JsonObject square(Object a){return object("type","minecraft:square","input",a);}
    private static JsonObject bin(String op,Object a,Object b){return object("type","minecraft:"+op,"left",a,"right",b);}
    private static JsonObject add(Object a,Object b){return bin("add",a,b);}
    private static JsonObject sub(Object a,Object b){return bin("sub",a,b);}
    private static JsonObject mul(Object a,Object b){return bin("mul",a,b);}
    private static JsonObject min(Object a,Object b){return bin("min",a,b);}
    private static JsonObject max(Object a,Object b){return bin("max",a,b);}
}
