import unittest
try:
    import numpy as np
    from native_water_metrics import analyse
except ImportError:
    np = None


@unittest.skipIf(np is None, 'Native-volume analysis requires numpy/scipy')
class NativeWaterMetricsTest(unittest.TestCase):
    def make(self):
        return {'shape_xyz': [7, 8, 7], 'origin_xyz': [-3, 56, -3], 'palette':
                ['Block{minecraft:air}', 'Block{minecraft:stone}', 'Block{minecraft:water}[level=0]',
                 'Block{minecraft:water}[level=8]', 'Block{minecraft:oak_slab}[waterlogged=true]']}, np.zeros((8, 7, 7), dtype=np.uint16)

    def test_closed_floating_pool_differs_from_supported_pool(self):
        meta, ids = self.make()
        ids[4, 2:5, 2:5] = 2
        report = analyse(meta, ids)
        self.assertEqual([9], report['closed_free_component_sizes'])
        ids[3, 2:5, 2:5] = 1
        self.assertEqual(0, analyse(meta, ids)['closed_components_without_solid_or_vegetation_contact'])

    def test_clipped_ocean_is_not_reported_as_floating(self):
        meta, ids = self.make()
        ids[4, :, :] = 2
        report = analyse(meta, ids)
        self.assertEqual(1, report['clipped_components_unknown_extent'])
        self.assertEqual(0, report['closed_components_without_solid_or_vegetation_contact'])

    def test_flowing_fall_and_waterlogged_blocks_are_separate(self):
        meta, ids = self.make()
        ids[3:6, 3, 3] = 3
        ids[6, 3, 3] = 2
        ids[2, 3, 3] = 4
        report = analyse(meta, ids)
        self.assertEqual(4, report['water_voxels'])
        self.assertEqual(1, report['source_water_voxels'])
        self.assertEqual(1, report['waterlogged_voxels_separate'])
        self.assertEqual(0, report['source_water_above_air_review_voxels'])

    def test_water_level_is_world_y_not_buffer_index(self):
        meta, ids = self.make()
        ids[6, 2:5, 2:5] = 2
        ids[5, 2:5, 2:5] = 1
        self.assertEqual({'62': 9}, analyse(meta, ids)['open_water_height_histogram'])


if __name__ == '__main__':
    unittest.main()
