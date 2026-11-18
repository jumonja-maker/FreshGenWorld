"""Screen complete log components in preserved FULL blocks for missing support.

Twenty-six-neighbour log connectivity includes native diagonal branches. A
component cut by the crop is censored. Unsupported components are review
candidates, not proof that every native disconnected branch is a floating tree.
An explicit soil contact also does not prove the soil connects to bedrock.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

from forest_block_metrics import SUPPORT


def measure(run, window, np, ndimage):
    from terrain_volume_metrics import NATURAL
    raw = gzip.decompress((run / window["raw_file"]).read_bytes())
    length = struct.unpack(">i", raw[:4])[0]
    meta = json.loads(raw[4:4 + length])
    sx, sy, sz = meta["shape_xyz"]
    x0, y0, z0 = meta["origin_xyz"]
    ids = np.frombuffer(raw, dtype=">u2", offset=4 + length).reshape(sy, sz, sx)
    names = [re.search(r"(?:Block\{)?(?:minecraft:)?([a-z0-9_]+)(?:\}|\[|$)", state).group(1)
             for state in meta["palette"]]
    traits = meta["palette_traits"]
    logs = np.isin(ids, [i for i, trait in enumerate(traits) if trait["logs"]])
    soil = np.isin(ids, [i for i, name in enumerate(names) if name in SUPPORT])
    labels, count = ndimage.label(logs, structure=np.ones((3, 3, 3), dtype=np.uint8))
    contacts = np.zeros_like(logs)
    contacts[1:] = logs[1:] & soil[:-1]
    supported_ids = set(int(value) for value in np.unique(labels[contacts]) if value)
    # A dirt block written below a misplaced tree is an explicit soil contact,
    # but does not turn that block into rooted terrain. Trace the contact's
    # six-face ground component separately, including native root connectors.
    terrain_names = set(NATURAL) | SUPPORT | {"ice", "packed_ice", "blue_ice", "powder_snow"}
    terrain = np.isin(ids, [i for i, name in enumerate(names)
                           if name in terrain_names or name.endswith(("_ore", "_terracotta"))])
    ground_labels, ground_count = ndimage.label(terrain, structure=ndimage.generate_binary_structure(3, 1))
    bedrock = np.isin(ids, [i for i, name in enumerate(names) if name == "bedrock"])
    anchored = set(int(value) for value in np.unique(ground_labels[bedrock]) if value)
    edge_ids = np.concatenate([ground_labels[0].ravel(), ground_labels[-1].ravel(),
                              ground_labels[:, 0].ravel(), ground_labels[:, -1].ravel(),
                              ground_labels[:, :, 0].ravel(), ground_labels[:, :, -1].ravel()])
    clipped = set(int(value) for value in np.unique(edge_ids) if value)
    ground_sizes = np.bincount(ground_labels.ravel(), minlength=ground_count + 1)
    root_ids = [i for i, name in enumerate(names) if name in {"mangrove_roots", "muddy_mangrove_roots"}]
    native_roots = np.isin(ids, root_ids)
    organic_labels, _ = ndimage.label(logs | native_roots, structure=np.ones((3, 3, 3), dtype=np.uint8))
    anchored_organic = set(int(value) for value in np.unique(
        organic_labels[np.isin(ground_labels, list(anchored))]) if value)
    organic_edge = np.concatenate([organic_labels[0].ravel(), organic_labels[-1].ravel(),
                                  organic_labels[:, 0].ravel(), organic_labels[:, -1].ravel(),
                                  organic_labels[:, :, 0].ravel(), organic_labels[:, :, -1].ravel()])
    clipped_organic = set(int(value) for value in np.unique(organic_edge) if value)
    contact_rows = []
    for y, z, x in np.argwhere(contacts):
        component = int(ground_labels[y - 1, z, x])
        status = "bedrock_connected" if component in anchored else "crop_censored" if component in clipped else "closed_unanchored"
        name = names[int(ids[y - 1, z, x])]
        contact = {"log_xyz": [x0 + int(x), y0 + int(y), z0 + int(z)],
                             "soil_xyz": [x0 + int(x), y0 + int(y) - 1, z0 + int(z)],
                             "soil": name,
                             "log_component": int(labels[y, z, x]), "terrain_component": component,
                             "terrain_component_voxels": int(ground_sizes[component]), "terrain_status": status,
                             "material_role": "native_root_connector" if name in {"mangrove_roots", "muddy_mangrove_roots"} else "terrain_soil"}
        if contact["material_role"] == "native_root_connector":
            organic = int(organic_labels[y, z, x])
            contact["native_root_network_status"] = "bedrock_connected" if organic in anchored_organic else "crop_censored" if organic in clipped_organic else "closed_unanchored"
            contact["native_root_network"] = organic
        contact_rows.append(contact)
    objects = ndimage.find_objects(labels)
    components, candidates, censored = [], [], []
    for label, box in enumerate(objects, start=1):
        yy, zz, xx = np.nonzero(labels[box] == label)
        yy, zz, xx = yy + box[0].start, zz + box[1].start, xx + box[2].start
        touches_crop = bool((xx == 0).any() or (xx == sx - 1).any() or (zz == 0).any() or
                            (zz == sz - 1).any() or (yy == 0).any() or (yy == sy - 1).any())
        supported = label in supported_ids
        component = {"log_voxels": len(yy), "vertical_span": int(yy.max() - yy.min() + 1),
                     "bbox_xyz": [x0 + int(xx.min()), y0 + int(yy.min()), z0 + int(zz.min()),
                                  x0 + int(xx.max()), y0 + int(yy.max()), z0 + int(zz.max())],
                     "explicit_soil_contact_below_log": supported, "crop_censored": touches_crop}
        components.append(component)
        if not supported:
            component["voxel_examples_xyz"] = [[x0 + int(x), y0 + int(y), z0 + int(z)]
                                               for y, z, x in zip(yy[:16], zz[:16], xx[:16])]
            (censored if touches_crop else candidates).append(component)
    return {"site": window["site"], "log_components": count,
            "supported_log_components": len(supported_ids),
            "unsupported_complete_components": candidates,
            "unsupported_crop_censored_components": censored,
            "root_soil_terrain_status_counts": dict(collections.Counter(row["terrain_status"] for row in contact_rows if row["material_role"] == "terrain_soil")),
            "closed_unanchored_root_soil_contacts": [row for row in contact_rows if row["material_role"] == "terrain_soil" and row["terrain_status"] == "closed_unanchored"],
            "native_root_connector_contacts": [row for row in contact_rows if row["material_role"] == "native_root_connector"],
            "closed_unanchored_native_root_network_contacts": [row for row in contact_rows if row.get("native_root_network_status") == "closed_unanchored"],
            "all_components": components}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--numpy-path", type=Path)
    args = parser.parse_args()
    if args.numpy_path:
        sys.path.insert(0, str(args.numpy_path))
    import numpy as np
    from scipy import ndimage
    source = json.loads((args.lab / "summary.json").read_text())
    result = {"seed": source["seed"], "jar_sha256": source["jar_sha256"],
              "observer_sha256": source["observer_sha256"],
              "metric_source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "support_ids": sorted(SUPPORT),
              "support_classifier_dependency_sha256": hashlib.sha256(
                  Path(__file__).with_name("forest_block_metrics.py").read_bytes()).hexdigest(),
              "terrain_classifier_dependency_sha256": hashlib.sha256(
                  Path(__file__).with_name("terrain_volume_metrics.py").read_bytes()).hexdigest(),
              "kind": "Offline 26-neighbour log-component support screen from actual FULL states",
              "windows": [measure(args.lab / "run", window, np, ndimage) for window in source["windows"]],
              "limits": ["An unsupported complete component is a review candidate, not automatic proof of a floating tree.",
                         "Crop-censored components have unknown external support.",
                         "Explicit soil contact and six-face terrain anchoring are separate fields; isolated native-written dirt can otherwise hide a floating tree.",
                         "Native mangrove root blocks are organic connectors, traced with native diagonal root/trunk connectivity to six-face bedrock-connected terrain; they are reported separately from soil. This exception never applies to dirt.",
                         "Leaves never count as ground or as connecting logs.",
                         "Native diagonal branches are connected through a 26-neighbour stencil."]}
    output = args.lab / "tree-support-screen.json"
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"lab": str(args.lab), "windows": len(result["windows"]),
                      "unsupported_complete_components": sum(len(w["unsupported_complete_components"]) for w in result["windows"]),
                      "closed_unanchored_root_soil_contacts": sum(len(w["closed_unanchored_root_soil_contacts"]) for w in result["windows"]),
                      "artifact": str(output)}))


if __name__ == "__main__":
    main()
