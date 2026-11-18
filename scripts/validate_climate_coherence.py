"""Protect the 0.8.1 climate/ecotone update against the exact accepted 0.8.0.

This is a package/graph scope contract. It does not claim visual acceptance,
natural frequencies, correct tree populations or simulated native drainage.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile
from validate_ecoregions import (same_graph, binary, add, sub, mul, maximum,
                                cache, clamp, smooth, require)

ROOT=Path(__file__).resolve().parent.parent
VERSION='0.8.1-alpha.1'
CANDIDATE=ROOT/f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
FIXTURE=Path(__file__).resolve().parent/'fixtures/climate-baseline-0.8.0.json'
FIXTURE_SHA='0bef1f71d7bae09ba0437efba9a33e00fcfa6bc215440f9eada1043f0af77023'
BASELINE_SHA='226df1e6e14761fc390755f5905a76344a44ff33097c6c837267fb5c38bc1a50'
P='freshgenerator:highlands_v2/'
C=P+'landscape/climate/'
DATA='data/freshgenerator/worldgen/density_function/highlands_v2/'
CD=DATA+'landscape/climate/'
SETTINGS='data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
SURFACE='data/freshgenerator/worldgen/material_rule/highlands_v2/surface.json'
MANIFEST='freshgenerator-worldgen-manifest.json'
CLASS='dev/freshgenerator/'
REMOVED=frozenset(CLASS+n+'.class' for n in ('surface/SnowTransitions','mixin/BiomeSnowMixin'))
ADDED_RUNTIME=frozenset(CLASS+n+'.class' for n in ('core/ForestEcotone','core/ForestEcotone$Pool',
    'core/ForestEcotone$Share','core/ForestEcotone$Source','forest/NativeForestCount',
    'forest/NativeForestPalette'))
CHANGED_RUNTIME=frozenset(CLASS+n+'.class' for n in ('FreshGenerator','core/EcotoneModel',
    'core/EcotoneModel$Fields','core/ForestComposition','core/GlacialIce','core/LandscapeMaterials',
    'forest/ForestEnvironment','forest/ForestTelemetry','ice/GlacialSurface','ice/GlacialSurface$Environment','mixin/ForestPlacementMixin',
    'surface/EcotonePolicy','surface/EcotoneSurfaceRule','surface/EcotoneSurfaceRule$1','surface/EcotoneSurfaceRule$2'))
ADDED_DATA=frozenset(CD+n+'.json' for n in ('zone_temperature','biome_moisture','surface_continents','biome_continents'))
CHANGED_DATA=frozenset((SETTINGS,SURFACE,CD+'surface_temperature.json',DATA+'regions/biome_vegetation.json'))


def expected_graphs():
    deep=smooth(24,64,P+'coherent/depth_blocks')
    return {
        CD+'zone_temperature.json':cache(clamp(add(C+'regional_temperature',mul(.08,P+'regions/original_temperature')))),
        CD+'biome_moisture.json':cache(clamp(sub(add(C+'regional_vegetation',mul(.08,P+'regions/original_vegetation')),C+'continental_drying'))),
        CD+'surface_temperature.json':cache(clamp(sub(C+'zone_temperature',C+'altitude_cooling'))),
        CD+'surface_continents.json':cache(sub(P+'continents',mul(sub(1,smooth(-.11,.03,P+'continents')),
            maximum(sub(P+'continents',add(-.20,mul(1.30,smooth(61,75,P+'coherent/height')))),0)))),
        CD+'biome_continents.json':{'type':'minecraft:lerp','alpha':copy.deepcopy(deep),
            'first':C+'surface_continents','second':P+'continents'},
        DATA+'regions/biome_vegetation.json':{'type':'minecraft:lerp','alpha':deep,
            'first':C+'biome_moisture','second':P+'regions/original_vegetation'},
    }


def same_material(a,b):
    """Material thresholds are native doubles, including Double.MAX_VALUE.

    They are not float32 density scalars. Keep exact JSON numeric values/types
    instead of rejecting an unchanged intentional double sentinel as overflow.
    """
    if type(a) is not type(b):return False
    if isinstance(a,dict):return a.keys()==b.keys() and all(same_material(v,b[k]) for k,v in a.items())
    if isinstance(a,list):return len(a)==len(b) and all(same_material(x,y) for x,y in zip(a,b))
    return a==b


def validate(candidate=CANDIDATE, baseline=None):
    raw=FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest()==FIXTURE_SHA,'Pinned 0.8.0 fixture changed')
    f=json.loads(raw);old=f['entry_sha256']
    require(f['schema']==1 and f['baseline_sha256']==BASELINE_SHA and len(old)==497,'Wrong baseline provenance')
    if baseline:
        require(hashlib.sha256(Path(baseline).read_bytes()).hexdigest()==BASELINE_SHA,'Wrong baseline JAR')
        with ZipFile(baseline) as z:
            require({n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}==old,'Baseline entry hashes differ')
    candidate=Path(candidate)
    with ZipFile(candidate) as z:
        names=[n for n in z.namelist() if not n.endswith('/')]
        require(len(names)==len(set(names)),'Duplicate ZIP entries')
        hashes={n:hashlib.sha256(z.read(n)).hexdigest() for n in names}
        before,after=set(old),set(hashes)
        require(before-after==REMOVED,'Unexpected removals or independent snow override still packaged')
        require(after-before==ADDED_DATA|ADDED_RUNTIME,'Unreviewed new runtime/data entries')
        changed={n for n in before&after if hashes[n]!=old[n]}
        expected=CHANGED_RUNTIME|CHANGED_DATA|{'fabric.mod.json','freshgenerator.mixins.json',MANIFEST}
        require(changed==expected,f'Unreviewed changes or missing required changes: {sorted(changed^expected)}')
        decode=lambda n:json.loads(z.read(n))
        for n,graph in expected_graphs().items():
            require(same_graph(decode(n),graph),'Climate graph differs: '+n)
        settings=decode(SETTINGS);expected_settings=copy.deepcopy(f['settings'])
        expected_settings['noise_router']['continents']=C+'biome_continents'
        debug=expected_settings['debug_functions']
        climate_end=next(i for i,e in enumerate(debug) if e['label']=='FG cave network enabled')
        debug[climate_end:climate_end] = [
            {'label':'FG regional biome moisture','function':C+'biome_moisture'},
            {'label':'FG coastal biome continentalness','function':C+'biome_continents'}]
        require(same_graph(settings,expected_settings),'Unreviewed settings/terrain/water/biome routing change')
        # The native badlands route returns before the generic biome-surface
        # adapter. Only its guarded wooded-floor branch may gain rooting soil.
        expected_surface=copy.deepcopy(f['surface_rule'])
        wooded=expected_surface['sequence'][0]['then_run']['sequence'][0]
        require(wooded['if_true']=={'type':'minecraft:biome','biome_is':'minecraft:wooded_badlands'},'Pinned wooded branch differs')
        gate=wooded['then_run']
        soil={'type':'freshgenerator:ecotone_surface','height':P+'coherent/height',
              'temperature':P+'temperature','erosion':P+'erosion','subsurface':False,
              'landscape':True,'moisture':P+'vegetation','continentality':P+'continents'}
        wooded['then_run']={'type':'minecraft:sequence','sequence':[soil,gate]}
        require(same_material(decode(SURFACE),expected_surface),'Unreviewed native badlands/material routing change')
        metadata=copy.deepcopy(f['metadata']);metadata['version']=VERSION
        require(decode('fabric.mod.json')==metadata,'Unexpected mod metadata changes')
        mixins=copy.deepcopy(f['mixins']);mixins['mixins'].remove('BiomeSnowMixin')
        require(decode('freshgenerator.mixins.json')==mixins,'Unexpected mixin registration')
        manifest=decode(MANIFEST);data={n:v for n,v in hashes.items() if n.startswith('data/')}
        require(manifest['generated_sha256']==data and manifest['generated_entries']==len(data)==434,'Manifest inventory/digests differ')
        require(manifest['default_world_preset']=='minecraft:normal' and
                manifest['selectable_world_presets']==['minecraft:normal'],'Default world selection changed')
        for n in names:
            if n.endswith('.class'):
                body=z.read(n).lower()
                require(not any(word in body for word in (b'hydrolog',b'watershed',b'drainage')),'Removed custom water code returned: '+n)
        # Every physical density, native biome table, feature, cave, water, village,
        # End/Nether resource outside the exact climate scope has the baseline hash.
        unchanged_data=sum(n.startswith('data/') and old.get(n)==h for n,h in hashes.items())
        return dict(version=VERSION,sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    runtime_classes=sum(n.endswith('.class') for n in names),generated_resources=len(data),
                    identical_worldgen_resources=unchanged_data,changed_runtime_classes=sorted(CHANGED_RUNTIME),
                    added_runtime_classes=sorted(ADDED_RUNTIME),removed_runtime_classes=sorted(REMOVED),
                    physical_terrain_and_native_water_unchanged=True,nether_end_resources_unchanged=True,
                    scope='Package and emitted climate graph contract; native end-to-end evidence is separate')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--candidate',type=Path,default=CANDIDATE)
    p.add_argument('--baseline',type=Path);p.add_argument('--output',type=Path)
    a=p.parse_args();r=validate(a.candidate,a.baseline)
    if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(r,indent=2)+'\n')
    print(json.dumps(r,indent=2))
