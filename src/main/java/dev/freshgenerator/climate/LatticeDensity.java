package dev.freshgenerator.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;

import dev.freshgenerator.core.NodeTable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** A smooth horizontal field read from a lattice: the input is sampled at lattice nodes (every {@code spacing}
 * blocks) and interpolated bilinearly. For fields whose shortest wavelength is many times the spacing - the domain
 * warp of the climate borders (wavelengths 512-8192 blocks on a 32-block lattice: under 2% of the displacement,
 * about a block) - one cached lookup replaces evaluating the noises in every column that asks for the climate.
 * <p>Pure function of the input: node values do not depend on which thread or column computed them first. One node
 * cache per compile context (one per RandomState) and lattice. */
public record LatticeDensity(DensityFunction input, int spacing) implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:lattice");
    public static final MapCodec<LatticeDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("input").forGetter(LatticeDensity::input),
            Codec.intRange(1, 4096).fieldOf("spacing").forGetter(LatticeDensity::spacing)
    ).apply(i, LatticeDensity::new));

    private static final Map<CompileContext, Map<LatticeDensity, Nodes>> NODES = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        Nodes nodes;
        synchronized (NODES) {
            Map<LatticeDensity, Nodes> byLattice = NODES.computeIfAbsent(context, c -> new HashMap<>());
            nodes = byLattice.get(this);
            if (nodes == null) {
                nodes = new Nodes(input.compileSampler(context), spacing);
                byLattice.put(this, nodes);
            }
        }
        return new Sampler(nodes);
    }
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return new LatticeDensity(rule.rewrite(input), spacing); }
    /** Bilinear interpolation stays inside the bounds of the node values. */
    @Override public Interval range() { return input.range(); }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<LatticeDensity> codec() { return CODEC; }

    /** Node values of one lattice, shared by every sampler of the same compile context. Lookups go through a
     * lock-free direct-mapped table (no ThreadLocal, no map: the climate is read by every height sample). */
    static final class Nodes {
        /** Per world (compile context), not static: cache slots are numbered anew for each RandomState (audit 24.11
         * T-4: a shared context let a second world read the first world's node at the same position). Read only on
         * a table miss. */
        private final ThreadLocal<SamplerContext> context = ThreadLocal.withInitial(() -> SamplerContext.builder().enableCaches().build());
        private final DensitySampler input;
        private final int spacing;
        private final NodeTable table = new NodeTable();
        Nodes(DensitySampler input, int spacing) { this.input = input; this.spacing = spacing; }

        private float node(int i, int j) {
            float v = table.get(i, j);
            if (v == v) return v;
            v = input.sampleValue(context.get(), i * spacing, 0, j * spacing);
            table.put(i, j, v);
            return v;
        }

        float value(int x, int z) {
            int i = Math.floorDiv(x, spacing), j = Math.floorDiv(z, spacing);
            float a = node(i, j), b = node(i + 1, j), c = node(i, j + 1), d = node(i + 1, j + 1);
            double tx = (double) (x - i * spacing) / spacing, tz = (double) (z - j * spacing) / spacing;
            return (float) ((a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz);
        }
    }

    public static final class Sampler implements DensitySampler {
        private final Nodes nodes;
        Sampler(Nodes nodes) { this.nodes = nodes; }
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) { return nodes.value(x, z); }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            for (int a = 0; a < volume.sizeX(); a++) for (int b = 0; b < volume.sizeZ(); b++) {
                float v = nodes.value(volume.blockX(a), volume.blockZ(b));
                for (int y = 0; y < volume.sizeY(); y++) output.set(volume.indexUnchecked(a, y, b), v);
            }
        }
    }
}
