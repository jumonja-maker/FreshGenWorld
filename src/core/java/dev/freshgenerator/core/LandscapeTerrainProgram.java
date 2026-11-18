package dev.freshgenerator.core;

import java.util.*;
import java.util.function.ToDoubleFunction;
import static dev.freshgenerator.core.DensityExpr.*;
import static dev.freshgenerator.core.NaturalTerrainProgram.smootherstep;

/** Continuous regional landforms, evaluated by native seeded density functions.
 * This is a game-scale geomorphology approximation, not tectonic/erosion simulation.
 * No lake objects, channel paths, per-chunk random choices or terrain stamps exist.
 */
public final class LandscapeTerrainProgram {
    private final String prefix;
    private final Map<String,DensityExpr> nodes;
    private LandscapeTerrainProgram(String prefix,Map<String,DensityExpr> nodes) {
        this.prefix=prefix;this.nodes=Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
    public static LandscapeTerrainProgram compile(String prefix) {
        if(prefix==null||!prefix.matches("[a-z0-9_]+:[a-z0-9_/]+/"))
            throw new IllegalArgumentException("Expected a private density prefix");
        Map<String,DensityExpr> n=new LinkedHashMap<>();
        String l=prefix+"landscape/";
        DensityExpr c=ref(prefix+"continents"),e=ref(prefix+"erosion"),p=ref(prefix+"ridges_folded");
        DensityExpr province=ref(l+"uplift_noise"),relief=ref(l+"relief_noise"),fine=ref(l+"detail_noise");
        n.put("continentality",c);
        // The original native spline already contains coasts and river valleys. Do not
        // add the previous fixed 24-block ocean deepening or blanket mountain uplift.
        n.put("native_height",add(constant(128),scale(ref(prefix+"base_offset"),128)));
        DensityExpr h=ref(l+"native_height");
        n.put("land",smootherstep(-.15,.13,c));
        n.put("valley_clearance",smootherstep(-.90,-.38,p));
        n.put("province",smootherstep(-.45,.45,province));
        n.put("mountain",mul(mul(ref(l+"land"),sub(constant(1),smootherstep(-.72,.18,e))),
                mul(ref(l+"valley_clearance"),add(constant(.62),scale(ref(l+"province"),.38)))));
        // Eroded low country and the existing native valley floor have quieter
        // microrelief. They retain native broad elevation and most rolling relief;
        // this is not a common flat plane or a newly excavated drainage network.
        n.put("eroded_lowland",mul(ref(l+"land"),mul(
                sub(constant(1),smootherstep(95,155,h)),smootherstep(.05,.65,e))));
        n.put("valley_floor",mul(ref(l+"land"),sub(constant(1),smootherstep(-.92,-.52,p))));
        n.put("lowland_quiet",mul(smootherstep(65,80,h),
                new DensityExpr.Binary(DensityExpr.Op.MAX,ref(l+"eroded_lowland"),ref(l+"valley_floor"))));
        // A lower-slope apron fills only the intermediate mountain domain. It
        // vanishes at native valley corridors, coasts and summits, broadening the
        // foothill transition without adding another circular mountain object.
        n.put("footslope",mul(scale(mul(ref(l+"mountain"),sub(constant(1),ref(l+"mountain"))),4),
                mul(mul(ref(l+"valley_clearance"),smootherstep(.10,.30,c)),
                        mul(smootherstep(80,112,h),sub(constant(1),smootherstep(160,220,h))))));
        // Redistribute part of this very landform's ordinary uplift into its
        // footslope: M becomes M + .5*M*(1-M), not a fixed-height ring. A fixed
        // bump fading over a narrow M interval can create an artificial rim.
        n.put("foothill_apron",new DensityExpr.Binary(DensityExpr.Op.MIN,constant(10),
                scale(mul(ref(l+"footslope"),add(scale(add(constant(.45),scale(ref(l+"shoulder"),.55)),18),
                        ref(l+"form_crest"))),.125)));
        n.put("shoulder",smootherstep(-.40,.65,p));
        DensityExpr crest=clamp01(scale(sub(p,constant(.12)),1/.88));
        n.put("crest",mul(mul(crest,crest),crest));
        // Regional form is independent of rare uplift. Non-overlapping smooth
        // selectors form a partition of unity, so neighboring provinces blend
        // instead of placing discrete mountain objects or abrupt plateau rims.
        DensityExpr form=ref(l+"landform_noise");
        n.put("form_massif",sub(constant(1),smootherstep(-.45,-.05,form)));
        n.put("form_plateau",smootherstep(.18,.58,form));
        n.put("form_ridge",sub(sub(constant(1),ref(l+"form_massif")),ref(l+"form_plateau")));
        n.put("massif_shoulder",smootherstep(-.55,.35,p));
        n.put("plateau_core",smootherstep(-.45,.65,p));
        n.put("form_crest",add(add(scale(mul(ref(l+"form_massif"),ref(l+"massif_shoulder")),36),
                scale(mul(ref(l+"form_ridge"),ref(l+"crest")),34)),
                scale(mul(ref(l+"form_plateau"),ref(l+"plateau_core")),46)));
        // A plateau is a broad shoulder with reduced summit relief, not a global
        // height cap. The native profile retains at least 60 percent of its relief.
        // Both masks vanish at valley corridors and offshore before this acts.
        n.put("form_smoothing",mul(ref(l+"mountain"),add(
                scale(mul(ref(l+"form_massif"),ref(l+"massif_shoulder")),.25),
                scale(mul(ref(l+"form_plateau"),ref(l+"plateau_core")),.40))));
        n.put("form_level",add(constant(146),add(scale(ref(l+"province"),32),
                scale(sub(constant(1),smootherstep(-.72,.18,e)),20))));
        n.put("form_adjustment",mul(ref(l+"form_smoothing"),sub(ref(l+"form_level"),h)));
        n.put("form_detail",add(add(scale(ref(l+"form_massif"),.70),
                scale(ref(l+"form_ridge"),1.25)),scale(ref(l+"form_plateau"),.40)));
        // Rare uplift belongs to whole continuous mountain provinces. The crest and
        // foothills still follow the same native ridge field; no circular peak insert.
        n.put("giant",mul(ref(l+"mountain"),mul(smootherstep(.40,.72,ref(l+"giant_noise")),
                smootherstep(-.15,.75,p))));
        n.put("foothill",scale(mul(ref(l+"mountain"),add(constant(.45),scale(ref(l+"shoulder"),.55))),18));
        n.put("uplift",add(add(ref(l+"foothill"),mul(ref(l+"mountain"),ref(l+"form_crest"))),
                scale(ref(l+"giant"),170)));
        // Coastal resistance is shared across a region, independent of block/chunk
        // boundaries. Soft coasts spread their profile; resistant coasts remain steep.
        n.put("coast_rock",mul(smootherstep(-.30,.45,ref(l+"coast_noise")),
                add(constant(.35),scale(sub(constant(1),smootherstep(-.60,.20,e)),.65))));
        n.put("coast",mul(mul(smootherstep(-.50,-.25,c),sub(constant(1),smootherstep(-.08,.18,c))),
                mul(smootherstep(22,50,h),sub(constant(1),smootherstep(95,125,h)))));
        DensityExpr profileScale=add(constant(.62),scale(ref(l+"coast_rock"),.62));
        n.put("coast_height",add(h,mul(ref(l+"coast"),mul(sub(h,constant(63)),sub(profileScale,constant(1))))));
        n.put("rolling_relief",mul(mul(ref(l+"land"),ref(l+"valley_clearance")),
                mul(sub(constant(1),scale(ref(l+"mountain"),.75)),
                mul(mul(relief,add(constant(3),scale(ref(l+"province"),6))),
                        sub(constant(1),scale(ref(l+"lowland_quiet"),.25))))));
        n.put("surface_detail",mul(fine,add(constant(.65),
                mul(mul(ref(l+"mountain"),ref(l+"form_detail")),add(constant(2),scale(ref(l+"shoulder"),5))))));
        n.put("surface_detail_quiet",mul(ref(l+"surface_detail"),sub(constant(1),scale(ref(l+"lowland_quiet"),.65))));
        n.put("seabed_detail",mul(scale(relief,1.8),sub(constant(1),smootherstep(55,73,h))));
        n.put("height_raw",add(add(add(ref(l+"coast_height"),ref(l+"form_adjustment")),ref(l+"uplift")),
                add(ref(l+"foothill_apron"),add(ref(l+"rolling_relief"),add(ref(l+"surface_detail_quiet"),ref(l+"seabed_detail"))))));
        return new LandscapeTerrainProgram(prefix,n);
    }
    public Map<String,DensityExpr> nodes(){return nodes;}
    public float sample(String name,ToDoubleFunction<String> input){return evaluate(name,input,new HashMap<>(),0);}
    private float evaluate(String name,ToDoubleFunction<String> input,Map<String,Float> memo,int depth) {
        if(depth>32)throw new IllegalStateException("Cyclic landscape graph");
        Float cached=memo.get(name);if(cached!=null)return cached;
        DensityExpr node=Objects.requireNonNull(nodes.get(name),"Unknown landscape node: "+name);
        float value=node.evaluate(id->{
            String start=prefix+"landscape/",local=id.startsWith(start)?id.substring(start.length()):"";
            return nodes.containsKey(local)?evaluate(local,input,memo,depth+1):input.applyAsDouble(id);
        });
        memo.put(name,value);return value;
    }
    /** Smooth upper envelope leaves build-height headroom and does not flatten peaks. */
    public static double softHeight(double raw) {
        if(!Double.isFinite(raw))throw new IllegalArgumentException("Nonfinite height");
        double excess=Math.max(0,raw-264);
        return Math.max(-48,Math.min(raw,264)+46*excess/(46+excess));
    }
}
