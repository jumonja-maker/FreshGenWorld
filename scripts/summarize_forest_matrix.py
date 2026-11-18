"""Summarize preserved native FULL forest evidence without upgrading its SHA scope.

This consumes explicit completed labs only. Static inventory, natural FULL
windows, controlled palette experiments and a repeated run of the final archive
are different evidence; this script never treats one as another.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def archive_scope(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        forest = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                  if name.startswith(("dev/freshgenerator/core/Forest", "dev/freshgenerator/forest/",
                                      "dev/freshgenerator/mixin/ForestPlacementMixin")) and name.endswith(".class")}
        data = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                if name.startswith("data/") and not name.endswith("/")}
        worldgen = {name: sha for name, sha in data.items() if "/worldgen/" in name}
    return {"archive": str(path), "sha256": digest(path),
            "forest_class_sha256": forest, "worldgen_resource_sha256": worldgen,
            "data_resource_sha256": data}


def summarize(workspace, patterns, final_jar=None, ancestors=()):
    eco = workspace / "biome-realism-2026-11-15"
    registry = json.loads((eco / "forest-global-audit/initial/forest-registry.json").read_text())
    habitats = {row["biome"]: row["habitat"] for row in registry["rows"]}
    final = archive_scope(final_jar) if final_jar else None
    archives = {scope["sha256"]: scope for scope in map(archive_scope, ancestors)}
    if final:
        archives[final["sha256"]] = final
    labs = sorted({p for pattern in patterns for p in eco.glob(pattern) if (p / "summary.json").exists()})
    rows = {biome: {"biome": biome, "habitat": habitat, "static_source_audited": True,
                    "natural_FULL_cases": []} for biome, habitat in sorted(habitats.items())}
    windows, counts, hashes = [], collections.Counter(), collections.Counter()
    for lab in labs:
        summary = json.loads((lab / "summary.json").read_text())
        metrics_path = lab / "forest-block-metrics.json"
        metrics = json.loads(metrics_path.read_text())
        assert summary["jar_sha256"] == metrics["jar_sha256"], lab
        assert summary["observer_sha256"] == metrics["observer_sha256"], lab
        assert not summary.get("audit_native_counts_control"), "A/B controls are not natural-matrix cases"
        assert not summary.get("audit_forced_palette"), "Forced palettes are not natural-matrix cases"
        by_name = {window["site"]["name"]: window for window in metrics["windows"]}
        support_path = lab / "tree-support-screen.json"
        support = json.loads(support_path.read_text()) if support_path.exists() else None
        if support:
            assert support["jar_sha256"] == summary["jar_sha256"], lab
            support_by_name = {window["site"]["name"]: window for window in support["windows"]}
        for window in summary["windows"]:
            site, seed = window["site"], summary["seed"]
            assert not window.get("audit_forced_palette_calls", 0), "Forced palette case"
            assert not window.get("audit_count_bypasses", 0), "Native-count A/B case"
            biome = site["expectedBiome"]
            assert biome != "*" and not window.get("fixed_coordinate_regression"), "Regressions have no target-ID coverage credit"
            measured = by_name[site["name"]]
            conditional = measured["conditional_metrics_by_biome"].get(biome)
            assert conditional and conditional["columns"] > 0, (lab, biome)
            sha = summary["jar_sha256"]
            scope = "direct_final_archive" if final and sha == final["sha256"] else "measured_archive_only"
            ancestor = archives.get(sha)
            parity = None
            if final and ancestor and sha != final["sha256"]:
                parity = {"forest_classes_identical": ancestor["forest_class_sha256"] == final["forest_class_sha256"],
                          "worldgen_resources_identical": ancestor["worldgen_resource_sha256"] == final["worldgen_resource_sha256"],
                          "all_data_resources_identical": ancestor["data_resource_sha256"] == final["data_resource_sha256"]}
                if all(parity.values()):
                    scope = "ancestor_archive_with_exact_forest_and_worldgen_parity"
            case = {"seed": seed, "site": site, "actual_biomes": window["biomes"],
                    "expected_biome_fraction": window["expected_biome_fraction"],
                    "conditional": conditional, "height_mean": window["height_mean"],
                    "mean_slope": window["mean_slope"],
                    "root_components": len(measured["root_components"]),
                    "rooted_vertical_lengths": measured["rooted_vertical_lengths"],
                    "root_cluster_nearest_distance": measured["root_cluster_nearest_distance"],
                    "clearance_beneath_canopy": measured["clearance_beneath_canopy"],
                    "vertical_crown_depth": measured["vertical_crown_depth"],
                    "canopy_component_count": len(measured["canopy_components"]),
                    "interior_gap_areas": measured["interior_gap_areas"],
                    "creaking_heart_voxel_count": len(measured.get("creaking_heart_voxels", [])),
                    "forest_telemetry_delta": window.get("forest_telemetry_delta", {}),
                    "summary": str(lab / "summary.json"), "forest_metrics": str(metrics_path),
                    "raw_states": str(lab / "run" / window["raw_file"]),
                    "jar_sha256": sha, "observer_sha256": summary["observer_sha256"],
                    "evidence_scope": scope, "parity_to_final": parity}
            if support:
                support_window = support_by_name[site["name"]]
                case["log_component_support_screen"] = {
                    "log_components": support_window["log_components"],
                    "supported_log_components": support_window["supported_log_components"],
                    "unsupported_complete_components": len(support_window["unsupported_complete_components"]),
                    "unsupported_crop_censored_components": len(support_window["unsupported_crop_censored_components"]),
                    "artifact": str(support_path)}
                if "closed_unanchored_root_soil_contacts" in support_window:
                    case["log_component_support_screen"]["closed_unanchored_root_soil_contacts"] = len(
                        support_window["closed_unanchored_root_soil_contacts"])
                    case["log_component_support_screen"]["root_soil_terrain_status_counts"] = support_window["root_soil_terrain_status_counts"]
                    if "native_root_connector_contacts" in support_window:
                        case["log_component_support_screen"]["native_root_connector_contacts"] = len(support_window["native_root_connector_contacts"])
                        case["log_component_support_screen"]["closed_unanchored_native_root_network_contacts"] = len(support_window["closed_unanchored_native_root_network_contacts"])
            rows[biome]["natural_FULL_cases"].append(case)
            windows.append(case)
            counts[str(seed)] += 1
            hashes[sha] += 1
    for row in rows.values():
        row["native_FULL_seed_count"] = len({case["seed"] for case in row["natural_FULL_cases"]})
        row["all_target_windows_fraction_at_least_0_9"] = bool(row["natural_FULL_cases"]) and all(
            case["expected_biome_fraction"] >= .9 for case in row["natural_FULL_cases"])
    return {"phase": "preserved natural FULL evidence; each archive scope remains explicit",
            "registered_biomes": len(rows), "native_FULL_windows": len(windows),
            "native_FULL_per_seed": dict(counts), "native_FULL_per_jar_sha256": dict(hashes),
            "natural_targeted_biomes": sum(bool(row["natural_FULL_cases"]) for row in rows.values()),
            "native_FULL_on_at_least_two_seeds": sum(row["native_FULL_seed_count"] >= 2 for row in rows.values()),
            "direct_final_archive_windows": sum(case["evidence_scope"] == "direct_final_archive" for case in windows),
            "ancestor_forest_resource_parity_windows": sum(case["evidence_scope"].startswith("ancestor_") for case in windows),
            "final_archive": final, "ancestor_archives": [scope for sha, scope in archives.items()
                                                          if not final or sha != final["sha256"]],
            "rows": list(rows.values()),
            "limits": ["Expected-ID presence is checked; a mixed sample is not a pure biome interior.",
                       "Root components and vertical columns are not exact individual-tree counts.",
                       "A heart voxel's biome alone does not prove the tree placement's biome.",
                       "Ancestor forest/resource parity is not a rerun of every final-archive block.",
                       "No inventory-only, forced-palette, fixed-coordinate or A/B control receives natural-ID coverage credit."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workspace", type=Path)
    parser.add_argument("--lab-pattern", action="append", required=True)
    parser.add_argument("--final-jar", type=Path)
    parser.add_argument("--ancestor-jar", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.workspace, args.lab_pattern, args.final_jar, args.ancestor_jar)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("native_FULL_windows", "native_FULL_per_seed", "natural_targeted_biomes",
                                                   "native_FULL_on_at_least_two_seeds", "direct_final_archive_windows",
                                                   "ancestor_forest_resource_parity_windows")}))
