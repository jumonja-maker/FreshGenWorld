"""Independent package-scope tests, including a valid attacker-rehashed manifest."""
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile,ZIP_DEFLATED

import validate_ecoregions as v


class EcoregionsValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.candidate=Path(os.environ.get('FG_ECOREGIONS_CANDIDATE',v.CANDIDATE))
        if not cls.candidate.is_file():raise unittest.SkipTest('Build 0.8.0 before current package mutation tests')
        with ZipFile(cls.candidate) as z:cls.accepted={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
        v.validate(cls.candidate)

    def reject(self,changes=None,remove=()):
        entries=dict(self.accepted);entries.update(changes or {})
        for name in remove:entries.pop(name)
        manifest=json.loads(entries[v.MANIFEST])
        manifest['generated_sha256']={n:hashlib.sha256(raw).hexdigest() for n,raw in entries.items() if n.startswith('data/')}
        manifest['generated_entries']=len(manifest['generated_sha256'])
        entries[v.MANIFEST]=json.dumps(manifest).encode()
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'mutated.jar'
            with ZipFile(target,'w',ZIP_DEFLATED) as z:
                for name,raw in entries.items():z.writestr(name,raw)
            with self.assertRaises(AssertionError):v.validate(target)

    def change(self,name,callback):
        value=copy.deepcopy(json.loads(self.accepted[name]));return {name:json.dumps(callback(value)).encode()}

    def test_candidate_and_pinned_baseline(self):
        result=v.validate(self.candidate)
        self.assertEqual(61,result['runtime_classes']);self.assertEqual(430,result['generated_resources'])
        self.assertEqual(418,result['identical_worldgen_resources']);self.assertTrue(result['cave_graphs_unchanged'])
        self.assertEqual(v.CHANGED_RUNTIME,set(result['changed_runtime_classes']))

    def test_native_float_comparison_rejects_booleans_and_nonfinite(self):
        self.assertTrue(v.same_graph(.08,.0800000001))
        self.assertFalse(v.same_graph(False,0));self.assertFalse(v.same_graph(True,1))
        self.assertFalse(v.same_graph(math.nan,math.nan));self.assertFalse(v.same_graph(math.inf,math.inf))

    def test_cave_glacier_and_village_runtime_remain_exact(self):
        for fragment in ('CaveCarverMixin','GlacialSurface.class','VillageTerrainModel'):
            name=next(n for n in self.accepted if n.endswith('.class') and fragment in n)
            with self.subTest(name=name):self.reject({name:self.accepted[name]+b'\0'})

    def test_unreviewed_runtime_class_is_rejected(self):
        self.reject({'dev/freshgenerator/core/UnexpectedTerrain.class':b'\xca\xfe\xba\xbe\0'})

    def test_world_height_authority_interpolation_and_native_biomes_are_unchanged(self):
        for name in (v.PREFIX+'coherent/height.json',v.DEFAULT_PRESET,'freshgenerator.mixins.json'):
            with self.subTest(name=name):self.reject({name:self.accepted[name]+b' '})
        self.reject({v.FRESH_PRESET:self.accepted[v.FRESH_PRESET]+b' '})

    def test_apron_cannot_exceed_reviewed_limit(self):
        name=v.LANDSCAPE+'foothill_apron.json'
        self.reject(self.change(name,lambda value:dict(value,input=v.minimum(100,value['input']['right']))))

    def test_apron_cannot_become_a_fixed_height_stamp(self):
        name=v.LANDSCAPE+'foothill_apron.json';self.reject({name:b'10'})
        name=v.LANDSCAPE+'footslope.json';self.reject({name:b'1'})

    def test_quiet_lowlands_cannot_erase_broad_relief(self):
        name=v.LANDSCAPE+'rolling_relief.json';self.reject({name:b'0'})
        name=v.LANDSCAPE+'surface_detail_quiet.json'
        self.reject(self.change(name,lambda value:dict(value,input=v.mul(v.L+'surface_detail',0))))

    def test_quietness_cannot_reach_submerged_coasts(self):
        name=v.LANDSCAPE+'lowland_quiet.json'
        self.reject(self.change(name,lambda value:dict(value,input=v.maximum(v.L+'eroded_lowland',v.L+'valley_floor'))))

    def test_moisture_bias_cannot_widen_or_use_an_unrelated_field(self):
        name=v.CLIMATE+'terrain_moisture.json'
        def wet(value):value['input']['right']['left']['left']=.8;return value
        self.reject(self.change(name,wet))
        def dry(value):value['input']['right']['right']['left']=.8;return value
        self.reject(self.change(name,dry))
        self.reject({name:json.dumps(v.cache(v.mul(.08,v.ID+'coherent/height'))).encode()})

    def test_boolean_cannot_impersonate_a_density_zero(self):
        name=v.LANDSCAPE+'eroded_lowland.json'
        def mutate(value):
            def walk(node):
                if isinstance(node,dict):
                    for key,item in node.items():
                        if type(item) in (int,float) and item==0:node[key]=False;return True
                        if walk(item):return True
                return False
            self.assertTrue(walk(value));return value
        self.reject(self.change(name,mutate))

    def test_deep_climate_and_surface_temperature_are_unchanged(self):
        for name in (v.PREFIX+'regions/biome_temperature.json',v.PREFIX+'regions/biome_vegetation.json',v.CLIMATE+'surface_temperature.json'):
            with self.subTest(name=name):self.reject({name:self.accepted[name]+b' '})

    def test_accepted_cave_roof_and_access_junction_are_unchanged(self):
        for local in ('ceiling','deep_ceiling','access_body','access_limit','final_body'):
            name=v.PREFIX+'cave_network/'+local+'.json'
            with self.subTest(name=name):self.reject({name:self.accepted[name]+b' '})

    def test_aquifers_sea_level_and_saved_vanilla_worlds_remain_native(self):
        def sea(value):value['sea_level']=72;return value
        self.reject(self.change(v.SETTINGS,sea))
        self.reject({'data/minecraft/worldgen/noise_settings/overworld.json':self.accepted[v.SETTINGS]})

    def test_custom_water_system_and_unknown_resources_are_rejected(self):
        self.reject({'data/freshgenerator/worldgen/density_function/hydrology/test.json':b'0'})
        name='dev/freshgenerator/core/ForestHabitat.class'
        self.reject({name:self.accepted[name]+b'hydrology'})
        self.reject(remove=(v.PREFIX+'cave_network/enabled.json',))

    def test_custom_codec_cannot_replace_reviewed_native_graph(self):
        name=v.LANDSCAPE+'foothill_apron.json'
        self.reject({name:b'{"type":"freshgenerator:terrain_stamp"}'})

    def test_debug_fields_cannot_enable_other_behavior(self):
        def marker(value):value['debug_functions'][0]['function']='minecraft:y';return value
        self.reject(self.change(v.SETTINGS,marker))

    def test_version_is_only_permitted_mod_metadata_change(self):
        self.reject(self.change('fabric.mod.json',lambda value:dict(value,environment='client')))
        self.reject(self.change('fabric.mod.json',lambda value:dict(value,schemaVersion=True)))

    def test_fixture_is_pinned_independently(self):
        with tempfile.TemporaryDirectory() as folder:
            fixture=Path(folder)/'baseline.json';fixture.write_bytes(v.BASELINE_FIXTURE.read_bytes()+b' ')
            with patch.object(v,'BASELINE_FIXTURE',fixture):
                with self.assertRaisesRegex(AssertionError,'Frozen baseline fixture changed'):v.validate(self.candidate)


if __name__=='__main__':unittest.main()
