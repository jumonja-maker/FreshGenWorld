"""Read-only metrics of ACTUAL saved native block volumes; no water generation.

Accepts the audit's length-prefixed JSON + uint16BE palette format, and the
TerrainIntegrityAudit variant that also carries a uint8 occupancy plane. Needs
numpy/scipy only when reading native volumes; synthetic unit tests use them too.
Clipped components are unknown, not claimed floating. A waterfall is reported
for review, not automatically a defect. There is no drainage simulation here.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import numpy as np
from scipy import ndimage

STRUCTURE = ndimage.generate_binary_structure(3, 1)


def read_volume(path):
    path = Path(path)
    with gzip.open(path, 'rb') as f:
        length = struct.unpack('>I', f.read(4))[0]
        if length > 4_000_000:
            raise ValueError('Invalid audit metadata length')
        meta = json.loads(f.read(length))
        raw = f.read()
    x, y, z = meta['shape_xyz']
    count = x * y * z
    if len(raw) == count * 3:  # integrity observer's occupancy bytes precede IDs
        raw = raw[count:]
    if len(raw) != count * 2:
        raise ValueError('Unexpected actual-state volume length')
    ids = np.frombuffer(raw, dtype='>u2').reshape(y, z, x)
    if int(ids.max()) >= len(meta['palette']):
        raise ValueError('Palette ID outside metadata')
    return meta, ids


def block_id(state):
    if 'Block{' in state:
        return state.split('Block{', 1)[1].split('}', 1)[0]
    return state.split('[', 1)[0]


def analyse(meta, ids, *, sea_level=63, column_csv=None):
    palette = meta['palette']
    names = [block_id(p) for p in palette]
    water_palette = np.array([n == 'minecraft:water' for n in names])
    air_palette = np.array([n in {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'} for n in names])
    source_palette = np.array([n == 'minecraft:water' and ('level=0]' in p or '[' not in p)
                               for n, p in zip(names, palette)])
    waterlogged_palette = np.array(['waterlogged=true' in p for p in palette])
    water, air = water_palette[ids], air_palette[ids]
    source = source_palette[ids]
    y_size, z_size, x_size = ids.shape
    y0 = meta['origin_xyz'][1]
    labels, number = ndimage.label(water, STRUCTURE)
    counts = np.bincount(labels.ravel(), minlength=number + 1)
    clipped = set(np.unique(np.concatenate([labels[0].ravel(), labels[-1].ravel(),
                                            labels[:, 0].ravel(), labels[:, -1].ravel(),
                                            labels[:, :, 0].ravel(), labels[:, :, -1].ravel()])).tolist())
    clipped.discard(0)
    # Contact is deliberately not a test that the whole fluid is gravity-supported:
    # native flowing water can fall. A closed component without ANY non-air contact
    # deserves review; a component clipped by the observation edge is unresolved.
    other = ~(water | air)
    contact_labels = set(np.unique(labels[ndimage.binary_dilation(other, STRUCTURE) & water]).tolist())
    contact_labels.discard(0)
    closed_free = [i for i in range(1, number + 1) if i not in clipped and i not in contact_labels]
    water_above_air = np.zeros_like(water)
    water_above_air[1:] = water[1:] & air[:-1]
    open_top = np.zeros_like(water)
    open_top[:-1] = water[:-1] & air[1:]
    # Highest exposed water in each column. Missing columns remain -1.
    ys = np.arange(y_size, dtype=np.int16)[:, None, None]
    highest = np.where(open_top, ys, -1).max(axis=0)
    heights = highest + y0
    surface_source = np.zeros((z_size, x_size), dtype=bool)
    zz, xx = np.nonzero(highest >= 0)
    surface_source[zz, xx] = source[highest[zz, xx], zz, xx]
    jumps = []
    for axis in [0, 1]:
        a, b = (slice(None, -1), slice(1, None))
        valid = ((highest[a, :] >= 0) & (highest[b, :] >= 0)) if axis == 0 else ((highest[:, a] >= 0) & (highest[:, b] >= 0))
        delta = abs(np.diff(heights, axis=axis))
        pair_source = (surface_source[a, :] & surface_source[b, :]) if axis == 0 else (surface_source[:, a] & surface_source[:, b])
        jumps.append({'axis': 'z' if axis == 0 else 'x', 'adjacent_water_pairs': int(valid.sum()),
                      'top_height_jumps_over1': int((valid & (delta > 1)).sum()),
                      'source_to_source_jumps_over1': int((valid & pair_source & (delta > 1)).sum()),
                      'max_jump': int(delta[valid].max()) if valid.any() else 0})
    hist_values, hist_counts = np.unique(heights[highest >= 0], return_counts=True)
    report = {
        'origin_xyz': meta['origin_xyz'], 'shape_xyz': meta['shape_xyz'],
        'water_voxels': int(water.sum()), 'source_water_voxels': int(source.sum()),
        'waterlogged_voxels_separate': int(waterlogged_palette[ids].sum()),
        'open_water_columns': int((highest >= 0).sum()),
        'open_water_height_histogram': {str(int(k)): int(v) for k, v in zip(hist_values, hist_counts)},
        'water_components': number, 'clipped_components_unknown_extent': len(clipped),
        'closed_components_without_solid_or_vegetation_contact': len(closed_free),
        'closed_free_component_sizes': [int(counts[i]) for i in closed_free],
        'source_water_above_air_review_voxels': int((source & water_above_air).sum()),
        'flowing_water_above_air_voxels': int((~source & water_above_air).sum()),
        'surface_jumps': jumps,
        'ice_voxels': int(np.isin(ids, [i for i, n in enumerate(names) if n in
                                     {'minecraft:ice', 'minecraft:packed_ice', 'minecraft:blue_ice'}]).sum()),
        'native_lava_voxels': int(np.isin(ids, [i for i, n in enumerate(names) if n == 'minecraft:lava']).sum()),
        'scope': 'Measured native block states in a finite audit window. No flow paths, drainage basins, rainfall, lake origins or current directions are inferred. Waterfalls/jumps need context; clipped volumes are unknown. Icebergs are buoyant geometry, not unsupported terrain defects.',
    }
    if column_csv:
        import csv
        with Path(column_csv).open(newline='', encoding='utf-8') as f:
            columns = list(csv.DictReader(f))
        expected = x_size * z_size
        if len(columns) != expected:
            raise ValueError('Column table does not match actual state raster')
        dry_river, high_beach, river_columns = [], [], 0
        for index, row in enumerate(columns):
            if int(row['x']) != meta['origin_xyz'][0] + index % x_size or int(row['z']) != meta['origin_xyz'][2] + index // x_size:
                raise ValueError('Column coordinate ordering differs')
            biome = row.get('biome', row.get('surface_biome', ''))
            h = float(row.get('height', row.get('height_y0', 'nan')))
            if biome in {'minecraft:river', 'minecraft:frozen_river'}:
                river_columns += 1
                if h > sea_level + 2 and highest.ravel()[index] < 0:
                    dry_river.append([int(row['x']), int(row['z']), h])
            if biome in {'minecraft:beach', 'minecraft:snowy_beach'} and h > sea_level + 20:
                high_beach.append([int(row['x']), int(row['z']), h])
        report.update(river_biome_columns=river_columns, elevated_dry_river_review_columns=len(dry_river),
                      elevated_dry_river_examples=dry_river[:24], high_beach_review_columns=len(high_beach),
                      high_beach_examples=high_beach[:24])
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('volumes', nargs='+', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    records = []
    for path in args.volumes:
        meta, ids = read_volume(path)
        csv = path.with_name(path.name.replace('-states.bin.gz', '-columns.csv').replace('-voxels.bin.gz', '-columns.csv'))
        report = analyse(meta, ids, column_csv=csv if csv.exists() else None)
        report.update(input=str(path.resolve()), input_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        records.append(report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'schema': 1, 'windows': records}, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'windows': len(records), 'water_voxels': sum(r['water_voxels'] for r in records),
                      'closed_free_components': sum(r['closed_components_without_solid_or_vegetation_contact'] for r in records)}))


if __name__ == '__main__':
    main()
