"""Independent finite-volume support checks on actual native audit states.

The world's physical density is separately hash-protected. This observer catches
decorator/support interactions that a scalar height map alone cannot establish.
Boundary-truncated components are unknown, not reported as floating. Ice and
snow are analysed separately from rock/soil: a buoyant iceberg is intentional.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy import ndimage
from native_water_metrics import read_volume, block_id, STRUCTURE

NATURAL=frozenset(('stone deepslate granite diorite andesite tuff calcite bedrock '
    'dirt grass_block coarse_dirt rooted_dirt podzol mycelium gravel sand red_sand '
    'sandstone red_sandstone clay terracotta mud packed_mud smooth_basalt amethyst_block '
    'budding_amethyst sculk sculk_catalyst moss_block pale_moss_block dripstone_block basalt polished_basalt '
    'blackstone cobblestone mossy_cobblestone snow_block magma_block raw_iron_block raw_copper_block '
    'bone_block cinnabar sulfur infested_stone infested_deepslate infested_cobblestone infested_stone_bricks '
    'infested_mossy_stone_bricks infested_chiseled_stone_bricks infested_cracked_stone_bricks').split())


def analyse(meta, ids):
    names=[block_id(s) for s in meta['palette']]
    natural=np.array([n.startswith('minecraft:') and (n[10:] in NATURAL or n.endswith('_ore')
                      or n.endswith('_terracotta')) for n in names],dtype=bool)[ids]
    bedrock=np.array([n=='minecraft:bedrock' for n in names],dtype=bool)[ids]
    ice=np.array([n in {'minecraft:ice','minecraft:packed_ice','minecraft:blue_ice'} for n in names],dtype=bool)[ids]
    labels,number=ndimage.label(natural,STRUCTURE)
    sizes=np.bincount(labels.ravel(),minlength=number+1)
    anchored=set(np.unique(labels[bedrock]).tolist());anchored.discard(0)
    clipped=set(np.unique(np.concatenate([labels[0].ravel(),labels[-1].ravel(),labels[:,0].ravel(),
        labels[:,-1].ravel(),labels[:,:,0].ravel(),labels[:,:,-1].ravel()])).tolist());clipped.discard(0)
    buoyant=set(np.unique(labels[ndimage.binary_dilation(ice,STRUCTURE)&natural]).tolist());buoyant.discard(0)
    closed=[i for i in range(1,number+1) if i not in anchored and i not in clipped and i not in buoyant]
    bounds=ndimage.find_objects(labels);pieces=[]
    x0,y0,z0=meta['origin_xyz']
    for i in closed:
        by,bz,bx=bounds[i-1]
        pieces.append(dict(voxels=int(sizes[i]),minimum_xyz=[x0+bx.start,y0+by.start,z0+bz.start],
                           maximum_xyz=[x0+bx.stop-1,y0+by.stop-1,z0+bz.stop-1]))
    return dict(origin_xyz=meta['origin_xyz'],shape_xyz=meta['shape_xyz'],natural_voxels=int(natural.sum()),
                structural_components=number,bedrock_anchored_components=len(anchored),
                clipped_unanchored_components_unknown_extent=len(clipped-anchored),
                closed_components_supported_by_ice=len(buoyant-clipped-anchored),
                closed_detached_rock_soil_components=len(pieces),closed_detached_rock_soil_voxels=sum(p['voxels'] for p in pieces),
                detached_above_y20_components=sum(p['maximum_xyz'][1]>20 for p in pieces),pieces=pieces,
                scope='Finite six-face connectivity of actual rock/soil/ore/moss/sculk/snow/sulfur/cinnabar and native cobblestone boulders. Not a claim about unclipped world components, loose gravity blocks, intentionally buoyant ice, wooden structures, or naturalness of every one-block voxel step.')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('volumes',nargs='+',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();rows=[]
    for f in a.volumes:
        meta,ids=read_volume(f);r=analyse(meta,ids);r.update(input=str(f.resolve()),input_sha256=hashlib.sha256(f.read_bytes()).hexdigest());rows.append(r)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(dict(schema=1,windows=rows),indent=2)+'\n')
    print(json.dumps(dict(windows=len(rows),closed_detached=sum(r['closed_detached_rock_soil_components'] for r in rows),
                          detached_above20=sum(r['detached_above_y20_components'] for r in rows))))


if __name__=='__main__':main()
