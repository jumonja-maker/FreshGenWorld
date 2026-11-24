package dev.freshgenerator.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.IslandClimate;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Zone temperature or moisture with island climate (core/IslandClimate): a small island takes the mean climate of
 * its land. One model per compile context (one per RandomState) and input graph, shared by both outputs.
 * <p>The output is the own sample of the caller of the local field plus the island offset: the local field goes
 * through the caches of the caller (sampling it point by point in a private context re-evaluated the whole regional
 * climate for every column: generation +10-17%). Only cell centres - once per 32x32 cell - use the private context
 * of the model. */
public record IslandClimateDensity(DensityFunction height, DensityFunction temperature, DensityFunction moisture, String output)
        implements DensityFunction {
    public static final String TEMPERATURE = "temperature", MOISTURE = "moisture";
    public static final Identifier ID = Identifier.parse("freshgenerator:island_climate");
    public static final MapCodec<IslandClimateDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("height").forGetter(IslandClimateDensity::height),
            DensityFunction.CODEC.fieldOf("temperature").forGetter(IslandClimateDensity::temperature),
            DensityFunction.CODEC.fieldOf("moisture").forGetter(IslandClimateDensity::moisture),
            // An unknown output is a load error, not a silent temperature ("moistrue" gave 0.8 instead of -0.6:
            // code audit 24.11).
            Codec.STRING.validate(v -> TEMPERATURE.equals(v) || MOISTURE.equals(v) ? com.mojang.serialization.DataResult.success(v)
                    : com.mojang.serialization.DataResult.error(() -> "freshgenerator:island_climate output must be temperature or moisture, got " + v))
                    .fieldOf("output").forGetter(IslandClimateDensity::output)
    ).apply(i, IslandClimateDensity::new));

    private static final Map<CompileContext, Map<List<DensityFunction>, IslandClimate>> MODELS = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        List<DensityFunction> key = List.of(height, temperature, moisture);
        IslandClimate model;
        synchronized (MODELS) {
            Map<List<DensityFunction>, IslandClimate> byInputs = MODELS.computeIfAbsent(context, c -> new HashMap<>());
            model = byInputs.get(key);
            if (model == null) {
                model = new IslandClimate(new Inputs(height.compileSampler(context), temperature.compileSampler(context), moisture.compileSampler(context)));
                byInputs.put(key, model);
            }
        }
        boolean moist = MOISTURE.equals(output);
        DensityFunction local = moist ? moisture : temperature;
        return new Sampler(model, local.compileSampler(context), local.range(), moist);
    }
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return new IslandClimateDensity(rule.rewrite(height), rule.rewrite(temperature), rule.rewrite(moisture), output);
    }
    /** The island value stays inside the bounds of the local field (IslandClimate.apply). */
    @Override public Interval range() { return (MOISTURE.equals(output) ? moisture : temperature).range(); }
    /** Horizontal, plus height when the local field depends on it: the sampler passes Y to the local field, and an
     * input with a height gradient lost it under a horizontal-only declaration (code audit 24.11). The island offset
     * itself is horizontal. */
    @Override public int domainAxes() { return AXIS_X | AXIS_Z | ((MOISTURE.equals(output) ? moisture : temperature).domainAxes() & AXIS_Y); }
    @Override public MapCodec<IslandClimateDensity> codec() { return CODEC; }

    /** Seed-bound samplers of one world with their own per-thread caching contexts. A static context was shared by
     * every world of the JVM, while 26.3 numbers cache slots anew for each RandomState and does not check which node
     * filled a slot: a second world could read the first world's value at the same position (audit 24.11 T-4). */
    private static final class Inputs implements IslandClimate.Inputs {
        private final DensitySampler height, temperature, moisture;
        private final ThreadLocal<SamplerContext> context = ThreadLocal.withInitial(() -> SamplerContext.builder().enableCaches().build());
        Inputs(DensitySampler height, DensitySampler temperature, DensitySampler moisture) { this.height = height; this.temperature = temperature; this.moisture = moisture; }
        public double height(int x, int z) { return height.sampleValue(context.get(), x, 0, z); }
        public double temperature(int x, int z) { return temperature.sampleValue(context.get(), x, 0, z); }
        public double moisture(int x, int z) { return moisture.sampleValue(context.get(), x, 0, z); }
    }

    public static final class Sampler implements DensitySampler {
        private final IslandClimate model;
        private final DensitySampler local;
        private final float min, max;
        private final boolean moisture;
        Sampler(IslandClimate model, DensitySampler local, Interval range, boolean moisture) {
            this.model = model; this.local = local; this.moisture = moisture;
            boolean known = !range.isNaI();
            this.min = known ? range.min() : -Float.MAX_VALUE; this.max = known ? range.max() : Float.MAX_VALUE;
        }
        public IslandClimate model() { return model; }
        public DensitySampler input() { return local; }
        private float island(float value, int x, int z) { return (float) IslandClimate.apply(value, model.offset(x, z, !moisture), min, max); }
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) { return island(local.sampleValue(context, x, y, z), x, z); }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            local.sampleVolume(context, output, volume);
            for (int a = 0; a < volume.sizeX(); a++) for (int b = 0; b < volume.sizeZ(); b++) {
                int x = volume.blockX(a), z = volume.blockZ(b);
                double offset = model.offset(x, z, !moisture);
                if (offset == 0) continue;
                for (int y = 0; y < volume.sizeY(); y++) {
                    int i = volume.indexUnchecked(a, y, b);
                    output.set(i, (float) IslandClimate.apply(output.get(i), offset, min, max));
                }
            }
        }
    }
}
