"""Measure forest structure from preserved native FULL states, without world writes.

Canopy and gap components at the sample edge are censored. Root groups merge
touching stems and are not exact individual-tree counts. Density contrasts are
measurements with climate context, not universal pass/fail realism thresholds.
"""
from __future__ import annotations

import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys


def components(mask):
    rows, columns = len(mask), len(mask[0])
    seen, found = set(), []
    for z in range(rows):
        for x in range(columns):
            if not mask[z][x] or (z, x) in seen:
                continue
            todo, members = [(z, x)], []
            seen.add((z, x))
            while todo:
                zz, xx = todo.pop()
                members.append((zz, xx))
                for nz, nx in ((zz - 1, xx), (zz + 1, xx), (zz, xx - 1), (zz, xx + 1)):
                    if 0 <= nz < rows and 0 <= nx < columns and mask[nz][nx] and (nz, nx) not in seen:
                        seen.add((nz, nx))
                        todo.append((nz, nx))
            boundary = any(zz in (0, rows - 1) or xx in (0, columns - 1) for zz, xx in members)
            found.append({"columns": len(members), "boundary_censored": boundary,
                "centroid_xz": [sum(xx for zz, xx in members) / len(members), sum(zz for zz, xx in members) / len(members)],
                "bbox_xz": [min(xx for zz, xx in members), min(zz for zz, xx in members),
                            max(xx for zz, xx in members), max(zz for zz, xx in members)]})
    return sorted(found, key=lambda component: component["columns"], reverse=True)


def distribution(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "min": None, "p10": None, "median": None, "p90": None, "max": None, "mean": None}
    return {"count": len(values), "min": values[0], "p10": values[int((len(values) - 1) * .1)],
        "median": values[len(values) // 2], "p90": values[int((len(values) - 1) * .9)],
        "max": values[-1], "mean": sum(values) / len(values)}


SUPPORT = {"grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "moss_block", "pale_moss_block", "mud",
    "packed_mud", "mangrove_roots", "muddy_mangrove_roots", "mycelium", "sand", "red_sand",
    "clay", "snow_block", "stone", "gravel", "crimson_nylium", "warped_nylium", "end_stone"}
FUNGI = {"nether_wart_block", "warped_wart_block", "red_mushroom_block", "brown_mushroom_block"}


def measure_window(run, window, np):
    raw = gzip.decompress((run / window["raw_file"]).read_bytes())
    header_length = struct.unpack(">i", raw[:4])[0]
    meta = json.loads(raw[4:4 + header_length])
    sx, sy, sz = meta["shape_xyz"]
    x0, y0, z0 = meta["origin_xyz"]
    ids = np.frombuffer(raw, dtype=">u2", offset=4 + header_length).reshape(sy, sz, sx)
    names = [re.search(r"(?:Block\{)?(?:minecraft:)?([a-z0-9_]+)(?:\}|\[|$)", state).group(1)
             for state in meta["palette"]]
    traits = meta.get("palette_traits")
    log_ids = [i for i, name in enumerate(names) if (traits[i]["logs"] if traits else name.endswith(("_log", "_wood", "_stem", "_hyphae")))]
    leaf_ids = [i for i, name in enumerate(names) if (traits[i]["leaves"] if traits else name.endswith("_leaves"))]
    support_ids = [i for i, name in enumerate(names) if name in SUPPORT]
    logs, leaves, soil = np.isin(ids, log_ids), np.isin(ids, leaf_ids), np.isin(ids, support_ids)
    fungal = np.isin(ids, [i for i, name in enumerate(names) if name in FUNGI])
    water = np.isin(ids, [i for i, name in enumerate(names) if name == "water"])
    rows = list(csv.DictReader((run / (window["site"]["name"] + "-columns.csv")).open()))
    assert len(rows) == sx * sz
    heights = np.array([float(row["height"]) for row in rows]).reshape(sz, sx)
    bases = np.ceil(heights).astype(int) - 1
    roots = np.zeros((sz, sx), dtype=bool)
    canopy = np.zeros_like(roots)
    fungal_canopy = np.zeros_like(roots)
    surface_water = np.zeros_like(roots)
    lengths, root_examples, clearances, crown_depths = [], [], [], []
    for z in range(sz):
        for x in range(sx):
            base = bases[z, x]
            low, high = max(0, base + 2 - y0), min(sy, base + 65 - y0)
            canopy[z, x] = bool(leaves[low:high, z, x].any())
            fungal_canopy[z, x] = bool(fungal[low:high, z, x].any())
            surface_water[z, x] = bool(water[max(0, base + 1 - y0):min(sy, base + 9 - y0), z, x].any())
            if canopy[z, x]:
                occupied = np.flatnonzero(leaves[low:high, z, x])
                clearances.append(int(occupied[0] + low + y0 - base - 1))
                crown_depths.append(int(occupied[-1] - occupied[0] + 1))
            best = 0
            for y in range(max(1, base - 8 - y0), min(sy, base + 13 - y0)):
                if not logs[y, z, x] or logs[y - 1, z, x] or not soil[y - 1, z, x]:
                    continue
                top = y
                while top < sy and top - y < 64 and logs[top, z, x]:
                    top += 1
                best = max(best, top - y)
            if best >= 2:
                roots[z, x] = True
                lengths.append(best)
                root_examples.append([x0 + x, z0 + z, best])
    root_components = components(roots)
    canopy_components = components(canopy)
    gaps = components(~canopy)
    centres = [component["centroid_xz"] for component in root_components]
    nearest = [min(math.dist(point, other) for j, other in enumerate(centres) if i != j)
               for i, point in enumerate(centres)] if len(centres) > 1 else []
    by_biome = {}
    for biome in sorted({row["biome"] for row in rows}):
        mask = np.array([row["biome"] == biome for row in rows]).reshape(sz, sx)
        by_biome[biome] = {"columns": int(mask.sum()), "root_columns": int((roots & mask).sum()),
            "canopy_columns": int((canopy & mask).sum()), "canopy_fraction": float(canopy[mask].mean()),
            "fungal_canopy_fraction": float(fungal_canopy[mask].mean()),
            "water_above_terrain_envelope_fraction": float(surface_water[mask].mean()),
            "ground_at_terrain_envelope": dict(collections.Counter(row["ground"] for row in rows if row["biome"] == biome))}
    heart_ids = [i for i, name in enumerate(names) if name == "creaking_heart"]
    hearts = []
    for y, z, x in np.argwhere(np.isin(ids, heart_ids)):
        hearts.append({"xyz": [x0 + int(x), y0 + int(y), z0 + int(z)],
            "column_biome_at_coherent_floor": rows[int(z) * sx + int(x)]["biome"],
            "state": meta["palette"][int(ids[y, z, x])]})
    cells_by_scale = {}
    for pitch in (8, 16, 32):
        cells = []
        for z in range(0, sz, pitch):
            for x in range(0, sx, pitch):
                z1, x1 = min(sz, z + pitch), min(sx, x + pitch)
                indices = [zz * sx + xx for zz in range(z, z1) for xx in range(x, x1)]
                biome_counts = collections.Counter(rows[index]["biome"] for index in indices)
                context = {}
                for field in ("height", "slope", "convexity16", "temperature", "moisture", "forest_edge", "shore",
                              "forest_retention", "forest_stand", "forest_opening", "forest_stress"):
                    values = [float(rows[index][field]) for index in indices if rows[index].get(field)]
                    context[field] = sum(values) / len(values) if values else None
                cells.append({"origin_xz": [x0 + x, z0 + z], "column_count": len(indices),
                    "root_columns": int(roots[z:z1, x:x1].sum()), "canopy_fraction": float(canopy[z:z1, x:x1].mean()),
                    "biomes": dict(biome_counts), "context": context})
        index = {tuple(cell["origin_xz"]): cell for cell in cells}
        contrasts = []
        for cell in cells:
            cx, cz = cell["origin_xz"]
            for delta in ((pitch, 0), (0, pitch)):
                other = index.get((cx + delta[0], cz + delta[1]))
                if other is not None:
                    contrasts.append({"first_xz": [cx, cz], "second_xz": other["origin_xz"],
                        "canopy_fraction_difference": abs(cell["canopy_fraction"] - other["canopy_fraction"]),
                        "root_density_difference_per_256": abs(cell["root_columns"] / cell["column_count"] -
                            other["root_columns"] / other["column_count"]) * 256})
        cells_by_scale[str(pitch)] = {"cells": cells, "adjacent_contrasts": sorted(contrasts,
            key=lambda contrast: contrast["canopy_fraction_difference"], reverse=True)}
    return {"site": window["site"], "columns": sx * sz, "biomes": window["biomes"],
        "expected_biome_fraction": window.get("expected_biome_fraction"),
        "canopy_fraction_above_two_blocks": float(canopy.mean()), "fungal_canopy_fraction": float(fungal_canopy.mean()),
        "canopy_components": canopy_components, "gap_components": gaps, "root_components": root_components,
        "interior_gap_areas": distribution([gap["columns"] for gap in gaps if not gap["boundary_censored"]]),
        "rooted_vertical_lengths": distribution(lengths), "root_cluster_nearest_distance": distribution(nearest),
        "clearance_beneath_canopy": distribution(clearances), "vertical_crown_depth": distribution(crown_depths),
        "conditional_metrics_by_biome": by_biome,
        "creaking_heart_voxels": hearts,
        "root_examples_x_z_length": root_examples, "spatial_cells": cells_by_scale,
        "raw_states_sha256": hashlib.sha256(raw).hexdigest(),
        "limits": ["Whole-window components may include neighbouring biome types; per-cell biome counts are preserved.",
                   "An edge-censored gap is not measured as a complete clearing.",
                   "Adjacent rooted columns can merge distinct trees; nearest distances use those group centroids.",
                   "Support requires explicit soil/root IDs and never accepts leaves as roots.",
                   "Heart voxel or floor-column biome is not the native tree's placement owner; trunks and 3-D biome zoom can cross an edge. Pipeline guard counters establish palette ownership.",
                   "Canopy is measured against the saved terrain envelope; no physical stand age or light simulation."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--numpy-path", type=Path)
    args = parser.parse_args()
    if args.numpy_path:
        sys.path.insert(0, str(args.numpy_path))
    import numpy as np
    report = json.loads((args.lab / "summary.json").read_text())
    output = {"seed": report["seed"], "jar_sha256": report["jar_sha256"], "observer_sha256": report["observer_sha256"],
        "metric_source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "kind": "Offline actual FULL-state forest structure, no generated-world mutation",
        "windows": [measure_window(args.lab / "run", window, np) for window in report["windows"]]}
    telemetry = collections.Counter()
    for window in report["windows"]:
        telemetry.update(window.get("forest_telemetry_delta", {}))
    controls = {"native_counts_control": report.get("audit_native_counts_control", False),
        "forced_palette": report.get("audit_forced_palette"),
        "modifier_bypasses": sum(window.get("audit_count_bypasses", 0) for window in report["windows"]),
        "forced_palette_calls": sum(window.get("audit_forced_palette_calls", 0) for window in report["windows"]),
        "pale_to_other_rejections": sum(window.get("audit_pale_palette_out_rejections", 0) for window in report["windows"]),
        "other_to_pale_rejections": sum(window.get("audit_pale_palette_in_rejections", 0) for window in report["windows"]),
        "ecotone_budget_plans": telemetry["ecotone_budget_plans"]}
    if controls["native_counts_control"]:
        assert controls["modifier_bypasses"] > 0, "Audit native-count bypass did not execute"
        assert controls["ecotone_budget_plans"] == 0, "Native-count control unexpectedly created an ecotone plan"
    if controls["forced_palette"]:
        assert controls["forced_palette_calls"] > 0, "Forced palette control did not execute"
        key = "other_to_pale_rejections" if controls["forced_palette"] == "minecraft:pale_garden" else "pale_to_other_rejections"
        assert controls[key] > 0, "The requested native pale-palette boundary guard was not exercised"
    output["native_pipeline_controls"] = controls
    (args.lab / "forest-block-metrics.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps({"seed": output["seed"], "windows": len(output["windows"]),
        "artifact": str(args.lab / "forest-block-metrics.json")}))


if __name__ == "__main__":
    main()
