package dev.freshgenerator.hydrology;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.Set;
import java.util.stream.Stream;

/** Keeps each world on one family of ocean structure sets (audit 23.11): the current world uses the adapted
 * freshgenerator:shipwrecks / ocean_ruins / ocean_monuments (wider spacing, monument exclusion), every other world the
 * untouched native minecraft: sets. ChunkGenerator.createState marks which kind of world is being set up; the native
 * structure state then filters the set list it builds. */
public final class StructureSets {
    private static final Set<String> NAMES = Set.of("shipwrecks", "ocean_ruins", "ocean_monuments");
    private static final ThreadLocal<Boolean> CURRENT_WORLD = new ThreadLocal<>();
    private StructureSets() { }

    public static void begin(boolean currentWorld) { CURRENT_WORLD.set(currentWorld); }
    public static void end() { CURRENT_WORLD.remove(); }

    /** The structure sets a world may use. Without a mark (any other caller) the adapted sets are left out. */
    public static Stream<Holder.Reference<StructureSet>> filter(Stream<Holder.Reference<StructureSet>> sets) {
        boolean current = Boolean.TRUE.equals(CURRENT_WORLD.get());
        return sets.filter(h -> {
            var id = h.key().identifier();
            if (!NAMES.contains(id.getPath())) return true;
            String ns = id.getNamespace();
            if (ns.equals("freshgenerator")) return current;
            if (ns.equals("minecraft")) return !current;
            return true;
        });
    }
}
