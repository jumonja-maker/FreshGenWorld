package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Opt the current preset into landscape materials; historical layers remain frozen. */
public final class LandscapeSurfaceCompiler {
    private LandscapeSurfaceCompiler() { }
    public static final String ROOT="data/freshgenerator/worldgen/material_rule/highlands_v2/";
    public static final Set<String> BIOME_REPLACED=Set.of(ROOT+"biome_surface.json",ROOT+"under_biome_surface.json");
    public static final String SURFACE=ROOT+"surface.json";
    public static final Set<String> REPLACED=Set.of(ROOT+"biome_surface.json",ROOT+"under_biome_surface.json",SURFACE);
    public static void apply(Map<String,JsonElement> out){
        Map<String,JsonElement> staged=new TreeMap<>();
        for(String path:BIOME_REPLACED){
            JsonObject sequence=Objects.requireNonNull(out.get(path),path).deepCopy().getAsJsonObject();
            JsonObject rule=sequence.getAsJsonArray("sequence").get(0).getAsJsonObject();
            if(!rule.get("type").getAsString().equals("freshgenerator:ecotone_surface")||rule.has("landscape"))
                throw new IllegalStateException("Unexpected surface layer: "+path);
            rule.addProperty("landscape",true);
            rule.addProperty("moisture",EcotoneCompiler.PREFIX+"vegetation");
            rule.addProperty("continentality",EcotoneCompiler.PREFIX+"continents");
            staged.put(path,sequence);
        }
        // Native badlands return their terracotta before reaching either generic
        // biome_surface adapter. Install the existing substrate rule inside the
        // exact wooded-only floor branch, before its unchanged absolute-Y97 gate.
        JsonObject surface=Objects.requireNonNull(out.get(SURFACE),SURFACE).deepCopy().getAsJsonObject();
        JsonObject floor=surface.getAsJsonArray("sequence").get(0).getAsJsonObject();
        if(!"minecraft:condition".equals(floor.get("type").getAsString())
                ||!floor.get("if_true").isJsonPrimitive()||!"minecraft:on_floor".equals(floor.get("if_true").getAsString()))
            throw new IllegalStateException("Unexpected native badlands floor routing");
        JsonObject wooded=floor.getAsJsonObject("then_run").getAsJsonArray("sequence").get(0).getAsJsonObject();
        JsonObject biome=wooded.getAsJsonObject("if_true");
        if(!"minecraft:condition".equals(wooded.get("type").getAsString())
                ||!"minecraft:biome".equals(biome.get("type").getAsString())
                ||!biome.get("biome_is").isJsonPrimitive()||!"minecraft:wooded_badlands".equals(biome.get("biome_is").getAsString()))
            throw new IllegalStateException("Unexpected native wooded badlands routing");
        JsonElement nativeGate=wooded.get("then_run").deepCopy();
        JsonObject gate=nativeGate.getAsJsonObject().getAsJsonObject("if_true");
        if(!"minecraft:y_above".equals(gate.get("type").getAsString())
                ||gate.getAsJsonObject("anchor").get("absolute").getAsInt()!=97)
            throw new IllegalStateException("Unexpected native wooded badlands altitude gate");
        JsonElement soil=staged.get(ROOT+"biome_surface.json").getAsJsonObject().getAsJsonArray("sequence").get(0).deepCopy();
        JsonArray sequence=new JsonArray();sequence.add(soil);sequence.add(nativeGate);
        JsonObject wrapper=new JsonObject();wrapper.addProperty("type","minecraft:sequence");wrapper.add("sequence",sequence);
        wooded.add("then_run",wrapper);staged.put(SURFACE,surface);
        out.putAll(staged);
    }
}
