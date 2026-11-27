package dev.freshgenerator.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.DuneField;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Dune height (blocks) of a sand sea: {@code amplitude} times the transverse dune profile of {@link DuneField}
 * (wind direction from the world seed). Zero wherever the amplitude is not positive; the dune model is evaluated
 * only inside the erg. Horizontal field (no Y dependence). */
public record DuneDensity(DensityFunction amplitude, double spacing) implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:dunes");
    public static final MapCodec<DuneDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("amplitude").forGetter(DuneDensity::amplitude),
            Codec.doubleRange(16, 1024).fieldOf("spacing").forGetter(DuneDensity::spacing)
    ).apply(i, DuneDensity::new));

    /** One dune field per compile context (world) and spacing: the seed comes from the context. */
    private static final Map<CompileContext, Map<Double, DuneField>> FIELDS = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        DuneField field;
        synchronized (FIELDS) {
            Map<Double, DuneField> bySpacing = FIELDS.computeIfAbsent(context, c -> new HashMap<>());
            field = bySpacing.get(spacing);
            if (field == null) {
                field = new DuneField(context.createRandom(ID).nextLong(), spacing);
                bySpacing.put(spacing, field);
            }
        }
        return new Sampler(amplitude.compileSampler(context), field);
    }
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return new DuneDensity(rule.rewrite(amplitude), spacing); }
    @Override public Interval range() {
        Interval a = amplitude.range();
        return Interval.encapsulating(Math.min(0, a.min()), Math.max(0, a.max()));
    }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<DuneDensity> codec() { return CODEC; }

    static final class Sampler implements DensitySampler {
        private final DensitySampler amplitude;
        private final DuneField field;
        Sampler(DensitySampler amplitude, DuneField field) { this.amplitude = amplitude; this.field = field; }
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            float a = amplitude.sampleValue(context, x, y, z);
            return a > 0 ? (float) (a * field.height(x, z)) : 0f;
        }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            for (int i = 0; i < volume.sizeX(); i++) for (int k = 0; k < volume.sizeZ(); k++) {
                int x = volume.blockX(i), z = volume.blockZ(k);
                float v = sampleValue(context, x, volume.blockY(0), z);
                for (int j = 0; j < volume.sizeY(); j++) output.set(volume.indexUnchecked(i, j, k), v);
            }
        }
    }
}
