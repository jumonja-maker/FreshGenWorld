package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** No building on the water of the drainage network or the sea (current world).
 * <ul>
 * <li>Villages (user request 24.11: a house over a river cut it in two). Houses, decor and the pieces standing in them
 *     that would touch water are left out; a village whose meeting point stands on water, or which would lose more
 *     than a third of its buildings, is not built at all. Streets keep the native rule - planks over water - but a
 *     street mostly over water that leads nowhere (a dead end, or to houses left out) goes too, with the pieces that
 *     only it served (audit 25.11 DR-1: plank piers 60 blocks long through lakes, a crossroads in the middle of the
 *     water).</li>
 * <li>Other surface structures (audit 25.11 S-1: a pillager outpost in a lake, igloos on lake ice, jungle temples on
 *     water): a structure is not built when its main piece touches water; pieces of an outpost or of trail ruins that
 *     would stand in water are left out.</li>
 * </ul> */
public final class VillageWater {
    /** Piece kinds of a village. */
    public static final int STREET = 0, CENTRE = 1, BUILDING = 2;
    /** Blocks around a building kept off the water. */
    static final int MARGIN = 1;
    /** Share of a street piece over water from which it is a pier. */
    static final double PIER = 0.5;
    /** Surface structures built whole or not at all, and those that may lose outlying pieces. */
    static final Set<String> WHOLE = Set.of("minecraft:igloo", "minecraft:jungle_pyramid", "minecraft:desert_pyramid", "minecraft:mansion",
            "minecraft:ruined_portal", "minecraft:ruined_portal_desert", "minecraft:ruined_portal_jungle", "minecraft:ruined_portal_mountain");
    static final Set<String> PARTIAL = Set.of("minecraft:pillager_outpost", "minecraft:trail_ruins");
    private VillageWater() { }

    /** Whether a non-village structure keeps off the water (abandoned camps by prefix). */
    public static boolean surfaceId(String id) { return WHOLE.contains(id) || PARTIAL.contains(id) || id.startsWith("minecraft:abandoned_camp"); }

    /** The kind of a village piece by its template (village/&lt;type&gt;/streets|terminators|town_centers/...). */
    public static int kind(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece p && p.getElement() instanceof SinglePoolElement s) {
            String path;
            try { path = s.getTemplateLocation().getPath(); } catch (RuntimeException e) { return BUILDING; }
            if (path.contains("/streets/") || path.contains("/terminators/")) return STREET;
            if (path.contains("/town_centers/")) return CENTRE;
        }
        return BUILDING;
    }

    /** The village start without its buildings on water and its dead-end piers, INVALID_START for a village on water,
     * or the start itself. */
    public static StructureStart filter(StructureStart start, HydroField field) {
        List<StructurePiece> pieces = start.getPieces();
        int n = pieces.size(), buildings = 0, wet = 0;
        int[] kinds = new int[n];
        BoundingBox[] box = new BoundingBox[n];
        boolean[] drop = new boolean[n];
        HydroField.Column c = new HydroField.Column();
        for (int i = 0; i < n; i++) {
            StructurePiece piece = pieces.get(i);
            kinds[i] = kind(piece);
            box[i] = piece.getBoundingBox();
            if (kinds[i] == STREET) continue;
            buildings++;
            if (!overWater(box[i], field, c)) continue;
            if (kinds[i] == CENTRE) return StructureStart.INVALID_START;
            drop[i] = true;
            wet++;
        }
        if (wet * 3 > buildings) return StructureStart.INVALID_START;
        // Piers: a group of streets mostly over water stays only as a causeway between two inhabited dry parts of the
        // village (a part with a house or the meeting point); every other wet group goes - leaf pruning alone left a
        // pier with two crossroads at its end, whose pieces held on to each other (seed 31337, X1140 Z-4818).
        boolean[] pier = new boolean[n], pruned = new boolean[n];
        boolean anyPier = false;
        for (int i = 0; i < n; i++) if (kinds[i] == STREET && wetShare(box[i], field, c) >= PIER) { pier[i] = true; anyPier = true; }
        if (anyPier) {
            boolean[] node = new boolean[n];
            for (int i = 0; i < n; i++) node[i] = !drop[i] && (kinds[i] != BUILDING || house(box[i]));
            // Dry parts: streets on land, the meeting point and houses, joined where their boxes touch.
            int[] part = new int[n];
            java.util.Arrays.fill(part, -1);
            List<Boolean> inhabited = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!node[i] || pier[i] || part[i] >= 0) continue;
                int label = inhabited.size();
                boolean lived = false;
                java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(i));
                part[i] = label;
                while (!queue.isEmpty()) {
                    int a = queue.poll();
                    if (kinds[a] != STREET) lived = true;
                    for (int b = 0; b < n; b++) {
                        if (!node[b] || pier[b] || part[b] >= 0 || !linked(a, b, kinds, box)) continue;
                        part[b] = label;
                        queue.add(b);
                    }
                }
                inhabited.add(lived);
            }
            // Wet groups and the inhabited parts each touches.
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (!pier[i] || seen[i]) continue;
                List<Integer> group = new ArrayList<>();
                java.util.Set<Integer> touched = new java.util.HashSet<>();
                java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(i));
                seen[i] = true;
                while (!queue.isEmpty()) {
                    int a = queue.poll();
                    group.add(a);
                    for (int b = 0; b < n; b++) {
                        if (!node[b] || !linked(a, b, kinds, box)) continue;
                        if (pier[b]) { if (!seen[b]) { seen[b] = true; queue.add(b); } }
                        else if (part[b] >= 0 && inhabited.get(part[b])) touched.add(part[b]);
                    }
                }
                if (touched.size() < 2) for (int a : group) pruned[a] = true;
            }
            // What the meeting point no longer reaches through what is left goes too: stubs on the far shore and the
            // houses that only a cut pier served.
            int centre = -1;
            for (int i = 0; i < n && centre < 0; i++) if (kinds[i] == CENTRE && node[i]) centre = i;
            if (centre >= 0) {
                boolean[] reached = new boolean[n];
                java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(centre));
                reached[centre] = true;
                while (!queue.isEmpty()) {
                    int a = queue.poll();
                    for (int b = 0; b < n; b++) {
                        if (reached[b] || !node[b] || pruned[b] || !linked(a, b, kinds, box)) continue;
                        reached[b] = true;
                        // Houses end a walk: a street on the far side of a house is not reached through it.
                        if (kinds[b] != BUILDING) queue.add(b);
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (!node[i] || pruned[i] || reached[i]) continue;
                    if (kinds[i] == STREET) pruned[i] = true;
                    else if (kinds[i] == BUILDING) drop[i] = true;
                }
            }
        }
        boolean any = false;
        for (int i = 0; i < n; i++) if (drop[i] || pruned[i]) { any = true; break; }
        if (!any) return start;
        // Pieces inside a dropped building (its villagers, animals, a bell) or standing on a cut pier (lamps) go with it.
        List<StructurePiece> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (drop[i] || pruned[i]) continue;
            boolean inside = false;
            if (kinds[i] != STREET) for (int j = 0; j < n && !inside; j++) {
                if (drop[j] && box[i].intersects(box[j])) inside = true;
                else if (pruned[j] && overlapXZ(box[i], box[j])) inside = true;
            }
            if (!inside) kept.add(pieces.get(i));
        }
        return new StructureStart(start.getStructure(), start.getChunkPos(), start.getReferences(), new PiecesContainer(kept));
    }

    /** A non-village surface structure without its pieces on water, INVALID_START when it would stand on water, or the
     * start itself. */
    public static StructureStart filterSurface(StructureStart start, HydroField field, String id) {
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty()) return start;
        HydroField.Column c = new HydroField.Column();
        if (!PARTIAL.contains(id)) {
            for (StructurePiece p : pieces) if (overWater(p.getBoundingBox(), field, c)) return StructureStart.INVALID_START;
            return start;
        }
        if (overWater(pieces.get(0).getBoundingBox(), field, c)) return StructureStart.INVALID_START;
        List<BoundingBox> dropped = new ArrayList<>();
        List<StructurePiece> kept = new ArrayList<>(pieces.size());
        kept.add(pieces.get(0));
        for (int i = 1; i < pieces.size(); i++) {
            StructurePiece p = pieces.get(i);
            if (overWater(p.getBoundingBox(), field, c)) dropped.add(p.getBoundingBox());
            else kept.add(p);
        }
        if (dropped.isEmpty()) return start;
        kept.removeIf(p -> p != pieces.get(0) && inside(p.getBoundingBox(), dropped));
        return new StructureStart(start.getStructure(), start.getChunkPos(), start.getReferences(), new PiecesContainer(kept));
    }

    private static boolean inside(BoundingBox box, List<BoundingBox> dropped) {
        for (BoundingBox d : dropped) if (box.intersects(d)) return true;
        return false;
    }
    /** Whether two pieces of the village graph are joined: they touch or overlap in plan (a street's box includes its
     * verges, and a house beside it stands inside them); two houses are not joined to each other. */
    private static boolean linked(int a, int b, int[] kinds, BoundingBox[] box) {
        if (a == b || !touch(box[a], box[b])) return false;
        return kinds[a] != BUILDING || kinds[b] != BUILDING;
    }
    /** Pieces that touch or overlap in plan (a block apart at most) at heights a walker gets between (code audits 25.11
     * Opus KO-08, OpenAI: a street at Y60 and a house at Y144 above it counted as joined). */
    private static boolean touch(BoundingBox a, BoundingBox b) {
        return a.minX() <= b.maxX() + 1 && a.maxX() >= b.minX() - 1 && a.minZ() <= b.maxZ() + 1 && a.maxZ() >= b.minZ() - 1
                && a.minY() <= b.maxY() + 3 && b.minY() <= a.maxY() + 3;
    }
    private static boolean overlapXZ(BoundingBox a, BoundingBox b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }
    /** A house rather than a lamp, a hay bale or a villager: a footprint of at least 4x4 blocks. */
    private static boolean house(BoundingBox b) { return b.getXSpan() >= 4 && b.getZSpan() >= 4; }

    /** Whether a piece (with MARGIN) touches river, lake or sea water: every column (code audit 25.11 OpenAI 7: every
     * second one let a single wet column inside a 7x7 house through). */
    static boolean overWater(BoundingBox box, HydroField field, HydroField.Column c) {
        for (int z = box.minZ() - MARGIN; z <= box.maxZ() + MARGIN; z++)
            for (int x = box.minX() - MARGIN; x <= box.maxX() + MARGIN; x++) {
                field.column(x, z, c);
                if (c.wetIncludingSea()) return true;
            }
        return false;
    }
    /** Share of a piece's columns over water. */
    static double wetShare(BoundingBox box, HydroField field, HydroField.Column c) {
        int total = 0, wet = 0;
        for (int z = box.minZ(); z <= box.maxZ(); z++)
            for (int x = box.minX(); x <= box.maxX(); x++) {
                field.column(x, z, c);
                total++;
                if (c.wetIncludingSea()) wet++;
            }
        return total == 0 ? 0 : wet / (double) total;
    }
}
