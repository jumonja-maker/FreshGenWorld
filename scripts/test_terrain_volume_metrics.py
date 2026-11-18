import unittest
try:
    import numpy as np
    from terrain_volume_metrics import analyse
except ImportError:
    np=None


@unittest.skipIf(np is None,'Native-volume analysis requires numpy/scipy')
class TerrainVolumeMetricsTest(unittest.TestCase):
    def make(self):
        return {'shape_xyz':[7,8,7],'origin_xyz':[-3,56,-3],
                'palette':['Block{minecraft:air}','Block{minecraft:stone}','Block{minecraft:bedrock}',
                           'Block{minecraft:sculk}','Block{minecraft:packed_ice}']},np.zeros((8,7,7),dtype=np.uint16)

    def test_detached_closed_rock_is_detected(self):
        m,a=self.make();a[4,3,3]=1;r=analyse(m,a)
        self.assertEqual(1,r['closed_detached_rock_soil_voxels']);self.assertEqual([0,60,0],r['pieces'][0]['minimum_xyz'])

    def test_sculk_connects_natural_rock_to_bedrock(self):
        m,a=self.make();a[0,3,3]=2;a[1:4,3,3]=3;a[4,3,3]=1
        self.assertEqual(0,analyse(m,a)['closed_detached_rock_soil_components'])

    def test_clipped_rock_is_unknown(self):
        m,a=self.make();a[4,3,0:3]=1;r=analyse(m,a)
        self.assertEqual(0,r['closed_detached_rock_soil_components']);self.assertEqual(1,r['clipped_unanchored_components_unknown_extent'])

    def test_buoyant_ice_contact_is_explicit(self):
        m,a=self.make();a[4,3,3]=1;a[3,3,3]=4;r=analyse(m,a)
        self.assertEqual(0,r['closed_detached_rock_soil_components']);self.assertEqual(1,r['closed_components_supported_by_ice'])

    def test_current_native_sulfur_rock_and_boulder_blocks_can_support_soil(self):
        for name in ['cinnabar','sulfur','mossy_cobblestone','snow_block','raw_copper_block','pale_moss_block']:
            m,a=self.make();m['palette'].append('Block{minecraft:'+name+'}')
            a[0,3,3]=2;a[1:4,3,3]=5;a[4,3,3]=1
            self.assertEqual(0,analyse(m,a)['closed_detached_rock_soil_components'],name)


if __name__=='__main__':unittest.main()
