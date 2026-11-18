"""Validate the bounded 0.8.0 landform/ecology update against frozen 0.7.2.

This checks package scope and the independent density-graph contract. Ecological
directions, actual populations, support and appearance need separate evidence.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import struct
from zipfile import ZipFile

VERSION='0.8.0-alpha.1'
REPOSITORY=Path(__file__).resolve().parent.parent
CANDIDATE=REPOSITORY/f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE_FIXTURE=Path(__file__).resolve().parent/'fixtures/ecoregions-baseline-0.7.2.json'
FIXTURE_SHA256='d3b5567290f6bee512b8c1bc783664091eb37921ca4d6320b13012cad9ab3de2'
BASELINE_SHA256='3d94af5de439f4285bd02f750204728b731518c6529beffa5f5a5b49220f2cae'
MANIFEST='freshgenerator-worldgen-manifest.json'
SETTINGS='data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
PROFILE='data/freshgenerator/terrain_profile/highlands_v2.json'
PREFIX='data/freshgenerator/worldgen/density_function/highlands_v2/'
ID='freshgenerator:highlands_v2/'
L=ID+'landscape/'
C=L+'climate/'
LANDSCAPE=PREFIX+'landscape/'
CLIMATE=LANDSCAPE+'climate/'
NORMAL_TAG='data/minecraft/tags/worldgen/world_preset/normal.json'
DEFAULT_PRESET='data/minecraft/worldgen/world_preset/normal.json'
FRESH_PRESET='data/freshgenerator/worldgen/world_preset/world.json'
ADDED_DATA=frozenset([LANDSCAPE+n+'.json' for n in ('eroded_lowland','valley_floor',
    'lowland_quiet','footslope','foothill_apron','surface_detail_quiet')]+[CLIMATE+'terrain_moisture.json'])
CHANGED_DATA=frozenset([SETTINGS,PROFILE,LANDSCAPE+'rolling_relief.json',
    LANDSCAPE+'height_raw.json',CLIMATE+'moisture.json'])
CLASS='dev/freshgenerator/'
CHANGED_RUNTIME=frozenset(CLASS+n+'.class' for n in (
    'core/LandscapeTerrainProgram','core/ForestEcology',
    'core/ForestGrowth','core/ForestGrowth$1','core/ForestComposition',
    'forest/ForestEnvironment','forest/NativeTreeGrowth'))
ADDED_RUNTIME=frozenset(CLASS+n+'.class' for n in ('core/ForestHabitat','core/ForestEcology$1'))
FORBIDDEN=(b'hydrolog',b'watershed',b'drainage')


def require(condition,message):
    if not condition:raise AssertionError(message)


def decoded(archive,name):return json.loads(archive.read(name))


def same_graph(a,b):
    """Native density scalars are float32; reject booleans and nonfinite values.

    Float32 comparison accepts Gson's shortest float spelling and independently
    computed IR constants as the same native value, without loosening types.
    """
    if isinstance(a,bool) or isinstance(b,bool):return type(a) is type(b) and a==b
    if isinstance(a,dict) or isinstance(b,dict):
        return isinstance(a,dict) and isinstance(b,dict) and a.keys()==b.keys() and all(same_graph(v,b[k]) for k,v in a.items())
    if isinstance(a,list) or isinstance(b,list):
        return isinstance(a,list) and isinstance(b,list) and len(a)==len(b) and all(same_graph(x,y) for x,y in zip(a,b))
    if isinstance(a,(int,float)) and isinstance(b,(int,float)):
        if not math.isfinite(a) or not math.isfinite(b):return False
        try:return struct.pack('>f',a)==struct.pack('>f',b)
        except (OverflowError,struct.error):return False
    return type(a) is type(b) and a==b


def baseline_fixture(baseline=None):
    raw=BASELINE_FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest()==FIXTURE_SHA256,'Frozen baseline fixture changed')
    f=json.loads(raw);h=f['entry_sha256']
    require(f['schema']==1 and f['baseline_version']=='0.7.2-alpha.1' and f['baseline_sha256']==BASELINE_SHA256,'Wrong baseline provenance')
    require(len(h)==488 and sum(n.endswith('.class') for n in h)==59 and sum(n.startswith('data/') for n in h)==423,'Unexpected baseline inventory')
    if baseline is not None:
        baseline=Path(baseline)
        require(hashlib.sha256(baseline.read_bytes()).hexdigest()==BASELINE_SHA256,'Wrong accepted 0.7.2 JAR')
        with ZipFile(baseline) as z:
            names=[n for n in z.namelist() if not n.endswith('/')]
            require(len(names)==len(set(names)) and set(names)==set(h),'Baseline JAR inventory differs')
            require(all(hashlib.sha256(z.read(n)).hexdigest()==v for n,v in h.items()),'Baseline entry hashes differ')
            require(decoded(z,'fabric.mod.json')==f['metadata'] and decoded(z,SETTINGS)==f['settings'] and decoded(z,PROFILE)==f['terrain_profile'],'Baseline metadata differs')
            manifest=decoded(z,MANIFEST)
            require(manifest['generated_entries']==423 and manifest['generated_sha256']=={n:v for n,v in h.items() if n.startswith('data/')} and {k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}==f['manifest_header'],'Baseline manifest differs')
    return f


def binary(op,a,b):return {'type':'minecraft:'+op,'left':a,'right':b}
def add(a,b):return binary('add',a,b)
def sub(a,b):return binary('sub',a,b)
def mul(a,b):return binary('mul',a,b)
def minimum(a,b):return binary('min',a,b)
def maximum(a,b):return binary('max',a,b)
def cache(a):return {'type':'minecraft:cache','input':a}
def clamp(a):return {'type':'minecraft:clamp','input':a,'min':-1.,'max':1.}
def scale(a,v):return mul(a,v)
def unit(a):return minimum(1,maximum(0,a))
def smooth(lo,hi,value):
    t=unit(scale(sub(value,lo),1/(hi-lo)))
    return unit(mul(mul(mul(t,t),t),add(10,mul(t,add(-15,scale(t,6))))))


def expected_graphs(f):
    """Reviewed expressions, reconstructed independently of candidate/manifest."""
    h=L+'native_height';m=L+'mountain'
    expected={
        'eroded_lowland':cache(mul(L+'land',mul(sub(1,smooth(95,155,h)),smooth(.05,.65,ID+'erosion')))),
        'valley_floor':cache(mul(L+'land',sub(1,smooth(-.92,-.52,ID+'ridges_folded')))),
        'lowland_quiet':cache(mul(smooth(65,80,h),maximum(L+'eroded_lowland',L+'valley_floor'))),
        'footslope':cache(mul(scale(mul(m,sub(1,m)),4),mul(mul(L+'valley_clearance',smooth(.10,.30,ID+'continents')),mul(smooth(80,112,h),sub(1,smooth(160,220,h)))))),
        'foothill_apron':cache(minimum(10,scale(mul(L+'footslope',add(scale(add(.45,scale(L+'shoulder',.55)),18),L+'form_crest')),.125))),
        'surface_detail_quiet':cache(mul(L+'surface_detail',sub(1,scale(L+'lowland_quiet',.65)))),
    }
    # Retain the accepted broad relief exactly, attenuating its amplitude only.
    rolling=copy.deepcopy(f['landscape_nodes']['landscape/rolling_relief.json'])
    old=rolling['input']['right']['right']
    rolling['input']['right']['right']=mul(old,sub(1,scale(L+'lowland_quiet',.25)))
    expected['rolling_relief']=rolling
    height=copy.deepcopy(f['landscape_nodes']['landscape/height_raw.json'])
    height['input']['right']=add(L+'foothill_apron',add(L+'rolling_relief',add(L+'surface_detail_quiet',L+'seabed_detail')))
    expected['height_raw']=height
    graphs={LANDSCAPE+k+'.json':v for k,v in expected.items()}
    graphs[CLIMATE+'terrain_moisture.json']=cache(mul(smooth(63,79,ID+'coherent/height'),sub(
        mul(.08,maximum(L+'valley_floor',mul(.65,L+'footslope'))),mul(.02,mul(L+'mountain',L+'crest')))))
    graphs[CLIMATE+'moisture.json']=cache(clamp(sub(add(C+'regional_vegetation',add(
        add(mul(.22,ID+'regions/original_vegetation'),mul(.12,C+'local_vegetation')),C+'terrain_moisture')),C+'continental_drying')))
    return graphs


def validate(candidate=CANDIDATE,baseline=None):
    candidate=Path(candidate);f=baseline_fixture(baseline)
    with ZipFile(candidate) as z:
        names=[n for n in z.namelist() if not n.endswith('/')]
        require(len(names)==len(set(names)),'Duplicate package entries')
        hashes={n:hashlib.sha256(z.read(n)).hexdigest() for n in names}
        old=f['entry_sha256'];before,after=set(old),set(hashes)
        require(before<=after,'Accepted package entries removed')
        require(after-before==ADDED_DATA|ADDED_RUNTIME,f'Unreviewed new entries: {sorted((after-before)^(ADDED_DATA|ADDED_RUNTIME))}')
        changed={n for n in before if old[n]!=hashes[n]}
        runtime_changed={n for n in changed if n.endswith('.class')}
        require(runtime_changed==CHANGED_RUNTIME,f'Runtime scope changed: {sorted(runtime_changed)}')
        expected=CHANGED_DATA|runtime_changed|{'fabric.mod.json',MANIFEST}
        require(changed==expected,f'Unreviewed changed entries: {sorted(changed^expected)}')
        classes={n for n in after if n.endswith('.class')};data={n for n in after if n.startswith('data/')}
        require(len(classes)==61 and len(data)==430,'Unexpected runtime/data count')
        require(all(z.read(n).startswith(b'\xca\xfe\xba\xbe') for n in classes),'Invalid runtime class signature')
        require(same_graph(decoded(z,'fabric.mod.json'),dict(f['metadata'],version=VERSION)),'Mod metadata changed beyond version')
        require(same_graph(decoded(z,NORMAL_TAG),{'replace':False,'values':[]}),'Additional public preset or native alternatives changed')
        require(hashes[DEFAULT_PRESET]==hashes[FRESH_PRESET] and decoded(z,DEFAULT_PRESET)==decoded(z,FRESH_PRESET),'Default no longer aliases FreshGenerator')
        require('data/minecraft/worldgen/noise_settings/overworld.json' not in after,'Existing vanilla generator settings overridden')
        manifest=decoded(z,MANIFEST)
        require(manifest['generated_entries']==430 and manifest['generated_sha256']=={n:hashes[n] for n in data},'Manifest inventory/digests invalid')
        require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}==f['manifest_header'],'Source fingerprints/world metadata changed')
        for name in classes|data:
            require(not any(term in name.lower().encode() or term in z.read(name).lower() for term in FORBIDDEN),f'Removed custom water subsystem reference: {name}')
        settings=decoded(z,SETTINGS);old_settings=copy.deepcopy(f['settings'])
        debug=old_settings.pop('debug_functions');actual_settings=copy.deepcopy(settings);actual_debug=actual_settings.pop('debug_functions')
        require(actual_settings==old_settings,'Noise/aquifer settings changed outside diagnostics')
        insertion=next(i+1 for i,v in enumerate(debug) if v['label']=='FG landscape form_plateau')
        additions=[{'label':'FG landscape '+n,'function':L+n} for n in ('eroded_lowland','valley_floor','footslope','lowland_quiet')]
        require(actual_debug==debug[:insertion]+additions+debug[insertion:],'Unreviewed diagnostic sampler changes')
        require(len({v['label'] for v in actual_debug})==len(actual_debug),'Duplicate diagnostic labels')
        profile=dict(f['terrain_profile'],activeLandscapeAlgorithm=3,lowlandForms='quieter fine relief on eroded low country and native valley floors; intermediate mountain footslope aprons')
        require(decoded(z,PROFILE)==profile,'Terrain profile changed beyond reviewed lowland layer')
        for name,graph in expected_graphs(f).items():
            require(same_graph(decoded(z,name),graph),f'Unreviewed lowland/climate expression: {name}')
        # Exact byte preservation above covers every cave-network density/noise
        # graph, H interpolation authority, biome table, native fluid, glacier,
        # village, default-preset and mixin resource outside the explicit delta.
        return dict(version=VERSION,sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
            baseline_version='0.7.2-alpha.1',baseline_sha256=BASELINE_SHA256,
            baseline_fixture_sha256=FIXTURE_SHA256,baseline_jar_verified=baseline is not None,
            runtime_classes=len(classes),changed_runtime_classes=sorted(runtime_changed),
            added_runtime_classes=sorted(ADDED_RUNTIME),unchanged_runtime_classes=59-len(runtime_changed),
            generated_resources=len(data),identical_worldgen_resources=423-len(CHANGED_DATA),
            changed_resources=sorted(CHANGED_DATA),added_resources=sorted(ADDED_DATA),
            cave_graphs_unchanged=True,glacial_runtime_unchanged=True,village_runtime_unchanged=True,
            native_biome_tables_unchanged=True,default_world_preset='minecraft:normal',
            native_water_unchanged=True,custom_water_system_present=False,
            lowland_fine_retained_fraction=.35,lowland_rolling_retained_fraction=.75,
            maximum_foothill_addition=10,maximum_terrain_wetting=.08,maximum_ridge_drying=.02,
            scope='Package scope and density graph only; native populations, sampled support, connectivity and appearance require separate evidence')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',type=Path,default=CANDIDATE)
    parser.add_argument('--baseline',type=Path)
    parser.add_argument('--report',type=Path,default=REPOSITORY/'build/reports/village-release/ecoregions-validation.json')
    args=parser.parse_args();report=validate(args.candidate,args.baseline)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('ECOREGIONS_PACKAGE_PASS',json.dumps(report))
