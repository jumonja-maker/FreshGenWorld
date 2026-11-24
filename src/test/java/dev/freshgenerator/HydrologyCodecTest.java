package dev.freshgenerator;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.freshgenerator.climate.IslandClimateDensity;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Code audit 24.11 HY-04 and the island-climate axis contract: a misspelt output is a load error, not a silent
 * other value, and a height-dependent local field keeps its height axis. */
class HydrologyCodecTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static boolean hydrologyParses(String output) {
        String json = "{\"base\":70,\"continents\":0,\"temperature\":0,\"moisture\":0,\"erosion\":0,\"sea_level\":63"
                + (output == null ? "" : ",\"output\":\"" + output + "\"") + "}";
        return HydrologyDensity.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).isSuccess();
    }
    private static com.mojang.serialization.DataResult<IslandClimateDensity> island(String output, String temperature) {
        String json = "{\"height\":70,\"temperature\":" + temperature + ",\"moisture\":-0.6,\"output\":\"" + output + "\"}";
        return IslandClimateDensity.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test void hydrologyOutputMustBeKnown() {
        assertTrue(hydrologyParses(null), "default output");
        assertTrue(hydrologyParses("terrain"));
        assertTrue(hydrologyParses("dry_banks"));
        for (String typo : new String[]{"dry_bank", "dry_banKs", ""}) assertFalse(hydrologyParses(typo), "accepted output '" + typo + "'");
    }

    @Test void islandClimateOutputMustBeKnown() {
        assertTrue(island("temperature", "0.8").isSuccess());
        assertTrue(island("moisture", "0.8").isSuccess());
        assertFalse(island("moistrue", "0.8").isSuccess(), "a misspelt output gave the temperature");
    }

    @Test void islandClimateKeepsTheHeightAxisOfItsInput() {
        String gradient = "{\"type\":\"minecraft:gradient\",\"axis\":\"y\",\"from_coordinate\":0,\"from_value\":0,\"to_coordinate\":100,\"to_value\":1}";
        IslandClimateDensity vertical = island("temperature", gradient).getOrThrow();
        assertNotEquals(0, vertical.domainAxes() & DensityFunction.AXIS_Y, "a height gradient input is vertical");
        IslandClimateDensity flat = island("temperature", "0.8").getOrThrow();
        assertEquals(0, flat.domainAxes() & DensityFunction.AXIS_Y, "a constant input is horizontal");
    }
}
