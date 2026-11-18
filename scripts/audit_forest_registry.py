"""Inventory every biome and its actual native vegetation graph.

This is a static resource/source audit, not proof of canopy in a generated world.
Run it against the exact frozen playable JAR and its pinned Minecraft JAR before
choosing native FULL controls. Unknown providers and unsupported adapter paths
remain explicit; a missing sample never becomes a successful test.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re
import zipfile


def asset_path(kind, identifier):
    namespace, local = identifier.split(":", 1) if ":" in identifier else ("minecraft", identifier)
    return f"data/{namespace}/worldgen/{kind}/{local}.json"


def expected_integer(value):
    """Mean of a known native integer provider; None means not inferred."""
    if isinstance(value, (int, float)):
        return float(value)
    if not isinstance(value, dict):
        return None
    kind = value.get("type", "").removeprefix("minecraft:")
    if kind == "constant":
        return expected_integer(value.get("value"))
    if kind == "uniform":
        return (value["min_inclusive"] + value["max_inclusive"]) / 2
    if kind == "weighted_list":
        entries = value["distribution"]
        means = [expected_integer(entry["data"]) for entry in entries]
        if any(mean is None for mean in means):
            return None
        return sum(mean * entry["weight"] for mean, entry in zip(means, entries)) / sum(
            entry["weight"] for entry in entries
        )
    return None


def root_attempts(placed):
    mean, limitations = 1.0, []
    for modifier in placed.get("placement", []):
        kind = modifier["type"].removeprefix("minecraft:")
        if kind == "count":
            count = expected_integer(modifier["count"])
            if count is None:
                limitations.append(modifier)
            else:
                mean *= count
        elif kind == "rarity_filter":
            mean /= modifier["chance"]
        elif "count" in kind:
            limitations.append(modifier)
    return {"mean_before_survival_and_space_filters": None if limitations else mean,
            "unresolved_count_modifiers": limitations,
            "modifiers": placed.get("placement", [])}


class Catalog:
    def __init__(self, *jars):
        self.resources = {}
        for jar in jars:
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.startswith("data/") and "/worldgen/" in name and name.endswith(".json"):
                        self.resources[name] = json.loads(archive.read(name))

    def get(self, kind, identifier):
        return self.resources.get(asset_path(kind, identifier))

    def references(self, value):
        # Only values of feature-reference fields are traversed. Trunk block
        # providers, biome IDs and placement predicates are not feature edges.
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"feature", "default", "features", "data", "true_feature",
                           "false_feature", "vegetation_feature"}:
                    yield child
        elif isinstance(value, list):
            for child in value:
                yield child

    def vegetation(self, placed_id):
        features, selectors, visited, pending = {}, set(), set(), [("placed_feature", placed_id)]
        while pending:
            kind, value = pending.pop()
            if isinstance(value, str):
                key = kind, value
                if key in visited:
                    continue
                visited.add(key)
                resource = self.get(kind, value)
                if resource is None:
                    # Native selector leaves refer to placed features; their
                    # configured children refer to feature definitions.
                    other = "feature" if kind == "placed_feature" else "placed_feature"
                    resource = self.get(other, value)
                    if resource is not None:
                        kind = other
                if resource is None:
                    continue
                identifier = value
            elif isinstance(value, dict):
                resource, identifier = value, "<inline>"
            elif isinstance(value, list):
                pending.extend((kind, child) for child in value)
                continue
            else:
                continue
            typ = resource.get("type", "")
            if kind == "placed_feature" and "feature" in resource and "placement" in resource:
                pending.append(("feature", resource["feature"]))
                continue
            if "selector" in typ or typ == "minecraft:sequence":
                selectors.add(typ)
            category = {
                "minecraft:tree": "tree", "minecraft:fallen_tree": "fallen_tree",
                "minecraft:huge_fungus": "fungal_canopy", "minecraft:huge_brown_mushroom": "fungal_canopy",
                "minecraft:huge_red_mushroom": "fungal_canopy", "minecraft:bamboo": "bamboo",
                "minecraft:chorus_plant": "chorus", "minecraft:root_system": "root_system",
            }.get(typ)
            if category == "tree" and resource.get("foliage_placer", {}).get("type") == "minecraft:bush_foliage_placer":
                category = "shrub"
            if category:
                features[identifier] = {"id": identifier, "type": typ, "category": category,
                    "trunk": resource.get("trunk_placer"), "foliage": resource.get("foliage_placer"),
                    "decorators": resource.get("decorators"), "root_placer": resource.get("root_placer"),
                    "source": asset_path("feature", identifier) if identifier != "<inline>" else "inline"}
            for reference in self.references(resource):
                pending.append(("placed_feature", reference))
        return sorted(features.values(), key=lambda feature: feature["id"]), sorted(selectors)


def enum_cases(source, method):
    body = source.split(f"{method}(", 1)[1].split("default ->", 1)[0]
    return {identifier: habitat for cases, habitat in re.findall(r"case\s+(.*?)\s*->\s*(\w+);", body, re.S)
            for identifier in re.findall(r'"([^"]+)"', cases)}


def named_strings(source, name):
    block = source.split(name + "=", 1)[1].split(";", 1)[0]
    return set(re.findall(r'"([^"]+)"', block))


def inventory(catalog, repository):
    habitat_source = (repository / "src/core/java/dev/freshgenerator/core/ForestHabitat.java").read_text()
    environment = (repository / "src/main/java/dev/freshgenerator/forest/ForestEnvironment.java").read_text()
    adapter = (repository / "src/main/java/dev/freshgenerator/forest/NativeTreeGrowth.java").read_text()
    habitats = enum_cases(habitat_source, "forBiome")
    unmodified = named_strings(habitat_source.replace("UNMODIFIED =", "UNMODIFIED="), "UNMODIFIED")
    outer_features = {"minecraft:" + name for name in named_strings(environment, "FEATURES")}
    trunks = named_strings(adapter, "TRUNKS")
    foliages = named_strings(adapter, "FOLIAGE")
    rows = []
    for path, definition in sorted(catalog.resources.items()):
        match = re.fullmatch(r"data/([^/]+)/worldgen/biome/(.+)\.json", path)
        if not match:
            continue
        identifier = f"{match[1]}:{match[2]}"
        roots, forms, gaps = [], {}, []
        for stage, references in enumerate(definition.get("features", [])):
            for reference in references:
                leaves, selectors = catalog.vegetation(reference)
                if not leaves:
                    continue
                placed = catalog.get("placed_feature", reference) or {}
                tree_forms = [leaf for leaf in leaves if leaf["category"] == "tree"]
                adapted = reference in outer_features and identifier in habitats
                supported_selector = all(selector == "minecraft:random_selector" for selector in selectors)
                geometry_supported = all(
                    form["trunk"].get("type") in trunks and form["foliage"].get("type") in foliages
                    for form in tree_forms
                )
                if adapted and tree_forms and (not supported_selector or not geometry_supported):
                    gaps.append({"placed_feature": reference, "selectors": selectors,
                                 "geometry_supported": geometry_supported,
                                 "reason": "Recruitment hook exists; at least one native geometry path is preserved by adapter"})
                roots.append({"id": reference, "stage": stage, "native_attempt_capacity": root_attempts(placed),
                    "recruitment_adapted": adapted,
                    "geometry_adapted": adapted and supported_selector and geometry_supported,
                    "selectors": selectors, "forms": [leaf["id"] for leaf in leaves]})
                forms.update((leaf["id"], leaf) for leaf in leaves)
        categories = sorted({form["category"] for form in forms.values()})
        declared = habitats.get(identifier)
        policy = declared or ("PRESERVED_NATIVE" if match[2] in unmodified else "UNCLASSIFIED")
        rows.append({"biome": identifier, "source": path, "native_temperature": definition.get("temperature"),
            "native_downfall": definition.get("downfall"), "habitat": policy,
            "native_feature_stages": definition.get("features", []),
            "potential_canopy_categories": categories,
            "expected_canopy": "native trees; climate/rooting/stand dependent" if "tree" in categories else
                "native fungal canopy" if "fungal_canopy" in categories else
                "bamboo/chorus vegetation; no ordinary tree canopy" if set(categories) & {"bamboo", "chorus"} else
                "no ordinary tree canopy in native feature graph",
            "vegetation_roots": roots, "native_forms": list(forms.values()), "adapter_gaps": gaps,
            "static_coverage": True, "native_full_coverage": [],
            "limits": ["Asset capacity is not successful-tree density or measured canopy.",
                       "Native patch survival, crowding, climate masks and actual source biome footprint require FULL controls."]})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", type=Path, required=True)
    parser.add_argument("--mod", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    catalog = Catalog(args.minecraft, args.mod)
    rows = inventory(catalog, args.repository)
    args.output.mkdir(parents=True, exist_ok=True)
    result = {"audit_kind": "static native vegetation registry and current source-hook coverage",
        "inputs": [{"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                   for path in (args.minecraft, args.mod)],
        "registered_biomes": len(rows), "habitats": dict(collections.Counter(row["habitat"] for row in rows)),
        "unclassified_biomes": [row["biome"] for row in rows if row["habitat"] == "UNCLASSIFIED"],
        "native_full_tested_biomes_in_this_inventory": 0,
        "rows": rows}
    (args.output / "forest-registry.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    with (args.output / "forest-registry.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(["biome", "source", "habitat", "potential_canopy", "native_capacity_before_filters", "adapter_gaps", "FULL_runs"])
        for row in rows:
            capacities = "; ".join(f"{root['id']}={root['native_attempt_capacity']['mean_before_survival_and_space_filters']}"
                                   for root in row["vegetation_roots"])
            writer.writerow([row["biome"], row["source"], row["habitat"], ", ".join(row["potential_canopy_categories"]),
                             capacities, len(row["adapter_gaps"]), 0])
    print(json.dumps({key: value for key, value in result.items() if key != "rows"}, indent=2))


if __name__ == "__main__":
    main()
