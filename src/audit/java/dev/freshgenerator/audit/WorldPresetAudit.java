package dev.freshgenerator.audit;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;
import java.util.*;

/** Test-only new-world/default and saved-generator round-trip check. */
public final class WorldPresetAudit implements ModInitializer {
    private int shutdown=-1;
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_WORLD_PRESET_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try{
                var level=server.overworld();var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
                String actual=generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
                String expected=Objects.requireNonNull(System.getenv("FG_EXPECT_GENERATOR"));
                var nativeDefault=(NoiseBasedChunkGenerator)WorldPresets.createNormalWorldDimensions(server.registryAccess()).overworld();
                String template=nativeDefault.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
                String expectedTemplate=Objects.requireNonNull(System.getenv("FG_EXPECT_DEFAULT_TEMPLATE"));
                var choices=server.registryAccess().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresetTags.NORMAL)
                    .stream().map(h->h.unwrapKey().orElseThrow().identifier().toString()).toList();
                var result=Map.of("seed",level.getSeed(),"actual_settings",actual,"expected_settings",expected,
                    "default_template_settings",template,"expected_default_template",expectedTemplate,"normal_choices",choices,
                    "scope","Actual loaded generator and the same native default factory used by CreateWorldScreen. Only owned new/copied lab worlds.");
                Files.writeString(Path.of("world-preset.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
                if(!actual.equals(expected))throw new AssertionError("Expected "+expected+", loaded "+actual);
                if(!template.equals(expectedTemplate))throw new AssertionError("Unexpected native default factory: "+template);
                if("true".equals(System.getenv("FG_NO_EXTRA_FRESH_CHOICE"))&&choices.contains("freshgenerator:world"))
                    throw new AssertionError("FreshGenerator remains an extra selector entry");
                System.out.println("WORLD_PRESET_PASS "+actual);
            }catch(Throwable error){System.err.println("WORLD_PRESET_FAIL");error.printStackTrace();}
            finally{shutdown=40;}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
}
