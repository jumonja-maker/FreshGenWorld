"""Independent tamper tests; a self-consistent manifest is not approval."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED
import validate_climate_coherence as v


class ClimateCoherenceValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not v.CANDIDATE.exists():raise unittest.SkipTest('Build 0.8.1 before current package tests')
        v.validate(v.CANDIDATE)
        with ZipFile(v.CANDIDATE) as z:cls.entries={n:z.read(n) for n in z.namelist() if not n.endswith('/')}

    def reject(self, changes=None, remove=()):
        entries=dict(self.entries);entries.update(changes or {})
        for n in remove:entries.pop(n)
        m=json.loads(entries[v.MANIFEST]);m['generated_sha256']={n:hashlib.sha256(b).hexdigest() for n,b in entries.items() if n.startswith('data/')};m['generated_entries']=len(m['generated_sha256']);entries[v.MANIFEST]=json.dumps(m).encode()
        with tempfile.TemporaryDirectory() as folder:
            f=Path(folder)/'tampered.jar'
            with ZipFile(f,'w',ZIP_DEFLATED) as z:
                for n,b in entries.items():z.writestr(n,b)
            with self.assertRaises(AssertionError):v.validate(f)

    def test_exact_candidate_and_inventory(self):
        r=v.validate(v.CANDIDATE);self.assertEqual(434,r['generated_resources']);self.assertEqual(65,r['runtime_classes']);self.assertEqual(426,r['identical_worldgen_resources'])

    def test_physical_terrain_and_native_water_cannot_change_even_with_rehashed_manifest(self):
        for n in [v.DATA+'coherent/height.json',v.DATA+'final_density.json',v.DATA+'cave_network/final_body.json']:
            with self.subTest(resource=n):self.reject({n:self.entries[n]+b' '})

    def test_local_microclimate_cannot_return_to_biome_lookup(self):
        n=v.CD+'biome_moisture.json';graph=json.loads(self.entries[n]);graph['input']['input']['left']['right']={'type':'minecraft:mul','left':.12,'right':v.C+'local_vegetation'}
        self.reject({n:json.dumps(graph).encode()})

    def test_unreviewed_runtime_and_snow_override_are_rejected(self):
        for n in ['dev/freshgenerator/Unexpected.class','dev/freshgenerator/surface/SnowTransitions.class']:
            with self.subTest(resource=n):self.reject({n:b'not-a-valid-class'})

    def test_native_biome_feature_and_other_dimensions_remain_protected(self):
        n='data/minecraft/worldgen/world_preset/normal.json';world=json.loads(self.entries[n]);world['dimensions']['minecraft:the_nether']['generator']['settings']='freshgenerator:highlands_v2';self.reject({n:json.dumps(world).encode()})
        self.reject({'data/minecraft/worldgen/biome/forest.json':b'{}'})

    def test_removal_and_mixin_revival_are_rejected(self):
        self.reject(remove=[v.CD+'zone_temperature.json'])
        n='freshgenerator.mixins.json';m=json.loads(self.entries[n]);m['mixins'].append('BiomeSnowMixin');self.reject({n:json.dumps(m).encode()})

    def test_native_badlands_fallback_and_other_palettes_are_protected(self):
        n=v.SURFACE;surface=json.loads(self.entries[n]);surface['sequence'][0]['then_run']['sequence'][0]['then_run']['sequence'][1]['if_true']['anchor']['absolute']=80
        self.reject({n:json.dumps(surface).encode()})


if __name__=='__main__':unittest.main()
