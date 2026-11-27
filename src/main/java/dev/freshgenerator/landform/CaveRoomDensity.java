package dev.freshgenerator.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.CaveRooms;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;

import java.util.Map;
import java.util.WeakHashMap;

/** Great breakdown chambers ({@link CaveRooms}): output "room" is the chamber density (negative inside, 2 far from
 * any chamber), "shell" the wall-zone weight 0..1. One room model per compile context (world). */
public record CaveRoomDensity(String output) implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:cave_rooms");
    public static final String ROOM = "room", SHELL = "shell";
    public static final MapCodec<CaveRoomDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.validate(v -> ROOM.equals(v) || SHELL.equals(v) ? DataResult.success(v)
                    : DataResult.error(() -> "freshgenerator:cave_rooms output must be room or shell, got " + v)).fieldOf("output").forGetter(CaveRoomDensity::output)
    ).apply(i, CaveRoomDensity::new));

    private static final Map<CompileContext, Model> MODELS = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        Model model;
        synchronized (MODELS) { model = MODELS.computeIfAbsent(context, c -> new Model(new CaveRooms(c.createRandom(ID).nextLong()))); }
        return new Sampler(model, SHELL.equals(output));
    }
    record Sampler(Model model, boolean shell) implements DensitySampler {
        @Override public float sampleValue(SamplerContext ctx, int x, int y, int z) { return model.value(x, y, z, shell); }
        @Override public void sampleVolume(SamplerContext ctx, DensityBuffer out, DensityVolume v) { DensitySampler.sampleVolumeNaive(ctx, out, v, this); }
    }

    /** The chamber density of the current world (pack SpeleoCaveCompiler). */
    public static final Identifier ROOM_FUNCTION = Identifier.parse("freshgenerator:highlands_v2/karst/speleo/room");
    private static final Map<RandomState, Model> BOUND = new WeakHashMap<>();

    /** The chamber model of a world of the current mod (null elsewhere: vanilla worlds see the pack's functions in the
     * registry, but have no chambers). */
    public static Model model(RandomState state, NoiseGeneratorSettings settings, RegistryAccess registries) {
        synchronized (BOUND) { if (BOUND.containsKey(state)) return BOUND.get(state); }
        Model found = null;
        if (HydrologyDensity.field(state, settings) != null) {
            var df = registries.lookupOrThrow(Registries.DENSITY_FUNCTION).get(ResourceKey.create(Registries.DENSITY_FUNCTION, ROOM_FUNCTION));
            if (df.isPresent()) {
                DensitySampler sampler = state.getSampler(df.get().value());
                for (int guard = 0; guard < 16 && found == null; guard++) {
                    switch (sampler) {
                        case Sampler s -> found = s.model();
                        case CachingDensitySampler cache -> sampler = cache.input();
                        case SliceFunction.YSampler horizontal -> sampler = horizontal.input();
                        default -> guard = 16;
                    }
                }
            }
        }
        synchronized (BOUND) { BOUND.put(state, found); }
        return found;
    }
    /** The chamber model of a world found through the debug label of its noise settings (no registries needed: the
     * noise chunk has none). Null where there is none. */
    public static Model model(RandomState state, NoiseGeneratorSettings settings) {
        synchronized (BOUND) { if (BOUND.containsKey(state)) return BOUND.get(state); }
        Model found = null;
        for (var debug : settings.debugFunctions().functions()) {
            if (!debug.label().equals(LABEL)) continue;
            DensitySampler sampler = state.getSampler(debug.function());
            for (int guard = 0; guard < 16 && found == null; guard++) {
                switch (sampler) {
                    case Sampler s -> found = s.model();
                    case CachingDensitySampler cache -> sampler = cache.input();
                    case SliceFunction.YSampler horizontal -> sampler = horizontal.input();
                    default -> guard = 16;
                }
            }
            if (found != null) break;
        }
        if (found == null) return null;
        synchronized (BOUND) { BOUND.put(state, found); }
        return found;
    }
    /** Debug label of the chamber density in the noise settings (SpeleoCaveCompiler.ROOM_LABEL). */
    public static final String LABEL = "FG cave rooms";

    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
    @Override public Interval range() { return SHELL.equals(output) ? Interval.encapsulating(0, 1) : Interval.encapsulating(-1, 2); }
    @Override public int domainAxes() { return ALL_AXES; }
    @Override public MapCodec<CaveRoomDensity> codec() { return CODEC; }

    public static final class Model {
        final CaveRooms rooms;
        /** Rooms by cell, direct-mapped on the low CELL_BITS of both cell indices (a window of 2^CELL_BITS cells, 46 km,
         * without conflicts). Every density sample looks up nine cells: a ConcurrentHashMap keyed by the packed pair
         * hashed it to i ^ j, its bins turned into trees, and the lookups took a third of the noise stage of a plain
         * (Long.hashCode of (i << 32) ^ j). A pure memo: a replaced entry is the same room computed again. */
        private static final int CELL_BITS = 6, CELL_MASK = (1 << CELL_BITS) - 1;
        private record Cell(int i, int j, CaveRooms.Room room) { }
        private final java.util.concurrent.atomic.AtomicReferenceArray<Cell> cells = new java.util.concurrent.atomic.AtomicReferenceArray<>(1 << (2 * CELL_BITS));
        Model(CaveRooms rooms) { this.rooms = rooms; }
        CaveRooms.Room room(int i, int j) {
            int slot = (i & CELL_MASK) | (j & CELL_MASK) << CELL_BITS;
            Cell c = cells.getOpaque(slot);
            if (c != null && c.i == i && c.j == j) return c.room;
            CaveRooms.Room r = rooms.room(i, j);
            cells.setOpaque(slot, new Cell(i, j, r));
            return r;
        }
        /** The chamber that can reach column (x, z), or null. */
        public CaveRooms.Room near(int x, int z) { return rooms.near(x, z, this::room); }
        /** Whether a chamber dome reaches into a block box. */
        public boolean domesTouch(double x0, double y0, double z0, double x1, double y1, double z1) {
            return rooms.domesTouch(x0, y0, z0, x1, y1, z1, this::room);
        }
        float value(int x, int y, int z, boolean shell) {
            CaveRooms.Room r = rooms.near(x, z, this::room);
            if (r == null) return shell ? 0f : 2f;
            if (shell) return (float) CaveRooms.shell(r, x, y, z);
            return (float) Math.min(2, rooms.density(r, x, y, z));
        }
    }
}
