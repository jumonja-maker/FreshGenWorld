package dev.freshgenerator.hydrology;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroInputs;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Terrain height of the current world with the drainage network carved in (docs/hydrology-1.0.md). The input
 * ground is interpolated on the native 4-block grid by the model itself, so the model predicts the generated
 * ground exactly. One model per world seed is shared by every compiled sampler. */
public record HydrologyDensity(DensityFunction base, DensityFunction continents, DensityFunction temperature,
                               DensityFunction moisture, DensityFunction erosion, int seaLevel, String output) implements DensityFunction {
    /** Outputs: the carved terrain height, or the dry-banks mask (0 next to rivers and lakes, 1 away from them). */
    public static final String TERRAIN = "terrain", DRY_BANKS = "dry_banks";
    public static final Identifier ID = Identifier.parse("freshgenerator:hydrology");
    public static final String LABEL = "FreshGenerator hydrology";
    public static final MapCodec<HydrologyDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("base").forGetter(HydrologyDensity::base),
            DensityFunction.CODEC.fieldOf("continents").forGetter(HydrologyDensity::continents),
            DensityFunction.CODEC.fieldOf("temperature").forGetter(HydrologyDensity::temperature),
            DensityFunction.CODEC.fieldOf("moisture").forGetter(HydrologyDensity::moisture),
            DensityFunction.CODEC.fieldOf("erosion").forGetter(HydrologyDensity::erosion),
            // The model is calibrated for the native sea (fluid level 63, top water block 62): any other value is
            // rejected when the data loads, instead of being accepted and ignored.
            Codec.INT.validate(v -> v == (int) HydroModel.SEA_LEVEL ? DataResult.success(v)
                    : DataResult.error(() -> "freshgenerator:hydrology supports sea_level " + (int) HydroModel.SEA_LEVEL + " only, got " + v))
                    .fieldOf("sea_level").forGetter(HydrologyDensity::seaLevel),
            // An unknown output is a load error: any other string used to select the terrain height silently (code
            // audit 24.11 HY-04: "dry_bank" fed heights -186..70 into the dry-banks rule).
            Codec.STRING.validate(v -> TERRAIN.equals(v) || DRY_BANKS.equals(v) ? DataResult.success(v)
                    : DataResult.error(() -> "freshgenerator:hydrology output must be terrain or dry_banks, got " + v))
                    .optionalFieldOf("output", TERRAIN).forGetter(HydrologyDensity::output)
    ).apply(i, HydrologyDensity::new));

    /** One model per compile context and input graph. The game creates one compile context per RandomState (one
     * per world and data load), so a new world, another seed or reloaded data never reuses a model built from other
     * inputs; the terrain and dry-banks outputs of one world have the same inputs and share it. */
    private static final Map<CompileContext, Map<List<DensityFunction>, HydroField>> FIELDS = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        List<DensityFunction> key = List.of(base, continents, temperature, moisture, erosion);
        HydroField field;
        synchronized (FIELDS) {
            Map<List<DensityFunction>, HydroField> byInputs = FIELDS.computeIfAbsent(context, c -> new HashMap<>());
            field = byInputs.get(key);
            if (field == null) {
                long seed = context.createRandom(ID).nextLong();
                Inputs inputs = new Inputs(base.compileSampler(context), continents.compileSampler(context), temperature.compileSampler(context),
                        moisture.compileSampler(context), erosion.compileSampler(context));
                field = new HydroField(new HydroModel(inputs, seed));
                byInputs.put(key, field);
                if (byInputs.size() > 1) LOG.warn("FreshGenerator hydrology: {} drainage models in one world (different inputs)", byInputs.size());
            }
        }
        return new Sampler(field, DRY_BANKS.equals(output));
    }
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("FreshGenerator");
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return new HydrologyDensity(rule.rewrite(base), rule.rewrite(continents), rule.rewrite(temperature), rule.rewrite(moisture), rule.rewrite(erosion), seaLevel, output);
    }
    @Override public Interval range() {
        if (DRY_BANKS.equals(output)) return Interval.of(0, 1);
        Interval b = base.range(); return Interval.of(b.min() - 256, b.max());
    }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<HydrologyDensity> codec() { return CODEC; }

    /** Seed-bound native samplers as model inputs; one caching context per thread and per world. A static context
     * was shared by every world of the JVM, while 26.3 numbers cache slots anew for each RandomState and does not
     * check which node filled a slot: the second world could read the first one's height (audit 24.11 T-4). */
    private static final class Inputs implements HydroInputs {
        private final DensitySampler height, continents, temperature, moisture, erosion;
        private final ThreadLocal<SamplerContext> context = ThreadLocal.withInitial(() -> SamplerContext.builder().enableCaches().build());
        Inputs(DensitySampler height, DensitySampler continents, DensitySampler temperature, DensitySampler moisture, DensitySampler erosion) {
            this.height = height; this.continents = continents; this.temperature = temperature; this.moisture = moisture; this.erosion = erosion;
        }
        public double height(int x, int z) { return height.sampleValue(context.get(), x, 0, z); }
        public double continents(int x, int z) { return continents.sampleValue(context.get(), x, 0, z); }
        public double temperature(int x, int z) { return temperature.sampleValue(context.get(), x, 0, z); }
        public double moisture(int x, int z) { return moisture.sampleValue(context.get(), x, 0, z); }
        public double erosion(int x, int z) { return erosion.sampleValue(context.get(), x, 0, z); }
    }

    public static final class Sampler implements DensitySampler {
        private final HydroField field;
        private final boolean dry;
        private static final ThreadLocal<HydroField.Column> COLUMN = ThreadLocal.withInitial(HydroField.Column::new);
        Sampler(HydroField field, boolean dry) { this.field = field; this.dry = dry; }
        public HydroField field() { return field; }
        private float value(HydroField.Column c) { return (float) (dry ? c.dryBanks() : c.terrain); }

        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            HydroField.Column c = COLUMN.get();
            field.column(x, z, c);
            return value(c);
        }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            HydroField.Column c = COLUMN.get();
            for (int a = 0; a < volume.sizeX(); a++) for (int b = 0; b < volume.sizeZ(); b++) {
                field.column(volume.blockX(a), volume.blockZ(b), c);
                float t = value(c);
                for (int y = 0; y < volume.sizeY(); y++) output.set(volume.indexUnchecked(a, y, b), t);
            }
        }
    }

    private static final Map<RandomState, HydroField> BOUND = new WeakHashMap<>();
    private static final HydroField NONE_FIELD = null;

    /** The field already bound to a RandomState by a chunk of its world (null if none yet or none at all). */
    public static HydroField boundField(RandomState state) {
        synchronized (BOUND) { return BOUND.get(state); }
    }

    /** The hydrology field of a world's RandomState (null for worlds without it). */
    public static HydroField field(RandomState state, NoiseGeneratorSettings settings) {
        synchronized (BOUND) {
            if (BOUND.containsKey(state)) return BOUND.get(state);
        }
        HydroField found = NONE_FIELD;
        for (var debug : settings.debugFunctions().functions()) {
            if (!debug.label().equals(LABEL)) continue;
            DensitySampler sampler = state.getSampler(debug.function());
            for (int guard = 0; guard < 16 && found == null; guard++) {
                switch (sampler) {
                    case Sampler s -> { if (!s.dry) found = s.field(); else guard = 16; }
                    case CachingDensitySampler cache -> sampler = cache.input();
                    case SliceFunction.YSampler horizontal -> sampler = horizontal.input();
                    default -> guard = 16;
                }
            }
        }
        // The current world without its drainage label (a data pack renamed it): rivers would stay carved but dry,
        // without biomes, ice or protection from structures - reported instead of passing silently (code audit 24.11 F-4).
        if (found == null && settings.debugFunctions().functions().stream().anyMatch(d -> d.label().startsWith("FG ")))
            dev.freshgenerator.core.Diagnostics.warnOnce("hydro-label", "FreshGenerator: the world settings lack the debug function '" + LABEL + "'; the drainage network is off");
        synchronized (BOUND) { BOUND.put(state, found); }
        return found;
    }
}
