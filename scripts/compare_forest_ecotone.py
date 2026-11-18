"""Causal native-count/continuous-recruitment comparison on the same final JAR.

The external observer can bypass the count-budget method. Both arms otherwise
use identical terrain/climate/biome inputs and native survival filters. Distance
bands refer to the observed forest/plains boundary, not an illustrative line.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys


def load(lab, window, np):
    rows = list(csv.DictReader((lab / "run" / (window["site"]["name"] + "-columns.csv")).open()))
    raw = gzip.decompress((lab / "run" / window["raw_file"]).read_bytes())
    length = struct.unpack(">i", raw[:4])[0]
    meta = json.loads(raw[4:4 + length])
    sx, sy, sz = meta["shape_xyz"]
    ids = np.frombuffer(raw, dtype=">u2", offset=4 + length).reshape(sy, sz, sx)
    leaf_ids = [index for index, traits in enumerate(meta["palette_traits"]) if traits["leaves"]]
    leaves = np.isin(ids, leaf_ids)
    canopy = np.zeros((sz, sx), dtype=bool)
    for index, row in enumerate(rows):
        z, x = divmod(index, sx)
        base = int(np.ceil(float(row["height"]))) - 1
        canopy[z, x] = bool(leaves[max(0, base + 2 - meta["origin_xyz"][1]):min(sy, base + 65 - meta["origin_xyz"][1]), z, x].any())
    metrics = json.loads((lab / "forest-block-metrics.json").read_text())
    measured = next(candidate for candidate in metrics["windows"] if candidate["site"]["name"] == window["site"]["name"])
    roots = np.zeros((sz, sx), dtype=bool)
    for x, z, stem in measured["root_examples_x_z_length"]:
        roots[z - meta["origin_xyz"][2], x - meta["origin_xyz"][0]] = True
    return rows, canopy, roots, (sz, sx), measured


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("native", type=Path)
    parser.add_argument("ecotone", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--libraries", type=Path)
    args = parser.parse_args()
    if args.libraries:
        sys.path.insert(0, str(args.libraries))
    import numpy as np
    from scipy.ndimage import distance_transform_edt
    native = json.loads((args.native / "summary.json").read_text())
    ecotone = json.loads((args.ecotone / "summary.json").read_text())
    assert native["jar_sha256"] == ecotone["jar_sha256"]
    assert native["observer_sha256"] == ecotone["observer_sha256"]
    assert native["seed"] == ecotone["seed"]
    assert native["audit_native_counts_control"] and not ecotone["audit_native_counts_control"]
    assert not native.get("audit_forced_palette") and not ecotone.get("audit_forced_palette")
    assert len(native["windows"]) == len(ecotone["windows"])
    cases = []
    for before, after in zip(native["windows"], ecotone["windows"]):
        assert before["site"] == after["site"]
        first, before_canopy, before_roots, shape, before_metrics = load(args.native, before, np)
        second, after_canopy, after_roots, other_shape, after_metrics = load(args.ecotone, after, np)
        assert shape == other_shape and len(first) == len(second)
        fields = ("x", "z", "height", "slope", "convexity16", "biome", "temperature", "moisture", "forest_edge", "shore",
                  "forest_retention", "forest_stand", "forest_opening", "forest_stress")
        for one, two in zip(first, second):
            for field in fields:
                assert one[field] == two[field], f"Non-budget input changed: {field} at {one['x']},{one['z']}"
        biomes = np.array([row["biome"] for row in first]).reshape(shape)
        forest, plain = biomes == "minecraft:forest", biomes == "minecraft:plains"
        assert forest.any() and plain.any(), "Both actual source biomes are required"
        signed_distance = np.where(forest, -distance_transform_edt(~plain), distance_transform_edt(~forest))
        pair = forest | plain
        edges = [-48, -32, -24, -16, -8, 0, 8, 16, 24, 32, 48, 96]
        bands = []
        for low, high in zip(edges, edges[1:]):
            mask = pair & (signed_distance >= low) & (signed_distance < high)
            count = int(mask.sum())
            if not count:
                continue
            bands.append({"distance_blocks": [low, high], "columns": count,
                "native": {"canopy_fraction": float(before_canopy[mask].mean()), "root_columns": int(before_roots[mask].sum()),
                           "root_columns_per_1024": float(before_roots[mask].mean()) * 1024},
                "ecotone": {"canopy_fraction": float(after_canopy[mask].mean()), "root_columns": int(after_roots[mask].sum()),
                            "root_columns_per_1024": float(after_roots[mask].mean()) * 1024}})
        step = {}
        for name, canopy, roots in (("native", before_canopy, before_roots), ("ecotone", after_canopy, after_roots)):
            inside, outside = forest & (signed_distance >= -8), plain & (signed_distance < 8)
            step[name] = {"forest_near_columns": int(inside.sum()), "plain_near_columns": int(outside.sum()),
                "forest_canopy": float(canopy[inside].mean()), "plain_canopy": float(canopy[outside].mean()),
                "canopy_contrast": abs(float(canopy[inside].mean()) - float(canopy[outside].mean())),
                "forest_root_columns_per_1024": float(roots[inside].mean()) * 1024,
                "plain_root_columns_per_1024": float(roots[outside].mean()) * 1024,
                "root_density_contrast_per_1024": abs(float(roots[inside].mean()) - float(roots[outside].mean())) * 1024}
        cases.append({"site": before["site"], "identical_input_comparisons": len(first) * len(fields),
            "biomes": before["biomes"], "distance_bands": bands, "eight_block_boundary_contrast": step,
            "native_guard_counters": before.get("forest_telemetry_delta"), "ecotone_guard_counters": after.get("forest_telemetry_delta"),
            "native_metrics": str(args.native / "forest-block-metrics.json"), "ecotone_metrics": str(args.ecotone / "forest-block-metrics.json")})
    result = {"kind": "Native same-JAR ecotone ablation; actual FULL blocks and measured biome border",
        "seed": native["seed"], "jar_sha256": native["jar_sha256"], "observer_sha256": native["observer_sha256"],
        "metric_source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "cases": cases,
        "limits": ["Tree growth and native placement collisions change the canopy together with recruitment; this is the complete budget component's effect.",
                   "Rooted vertical columns are not exact tree individuals. Canopy crossing a biome border is normal.",
                   "These two controlled natural windows do not establish every forest transition or a global numerical density target."]}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"seed": native["seed"], "artifact": str(args.output), "contrasts": [case["eight_block_boundary_contrast"] for case in cases]}, indent=2))


if __name__ == "__main__":
    main()
