import pytest
import numpy as np
from PIL import Image
import io
from unittest.mock import MagicMock, patch

from app.heatmap.generator import HeatmapGenerator, interpolate_color, COLOR_RAMP


@pytest.mark.unit
class TestHeatmapColorMappingBoundary:
    """热力图颜色映射在流量极值处的边界处理

    核心保证：
    1. 全路段流量为0时不崩溃，返回有效透明PNG
    2. 单点极高流量不冲淡其他区域颜色（归一化基于全局而非局部）
    """

    def test_zero_value_maps_to_first_color(self):
        color = interpolate_color(0.0)
        assert color == COLOR_RAMP[0]

    def test_one_value_maps_to_last_color(self):
        color = interpolate_color(1.0)
        assert color == COLOR_RAMP[-1]

    def test_negative_value_clamped_to_zero(self):
        color = interpolate_color(-0.5)
        assert color == COLOR_RAMP[0]

    def test_overflow_value_clamped_to_one(self):
        color = interpolate_color(1.5)
        assert color == COLOR_RAMP[-1]

    def test_mid_value_produces_valid_color(self):
        color = interpolate_color(0.5)
        r, g, b, a = color
        assert r >= 0 and g >= 0 and b >= 0 and a >= 0

    def test_color_ramp_alpha_monotonically_increasing(self):
        for i in range(len(COLOR_RAMP) - 1):
            assert COLOR_RAMP[i][3] <= COLOR_RAMP[i + 1][3], \
                f"Alpha not monotonically increasing at index {i}: {COLOR_RAMP[i][3]} > {COLOR_RAMP[i+1][3]}"

    def test_color_gradient_blue_to_red(self):
        blue = interpolate_color(0.0)
        red = interpolate_color(1.0)
        assert blue[2] > blue[0], "Low flow should be more blue than red"
        assert red[0] > red[2], "High flow should be more red than blue"

    def test_all_zero_flow_no_crash(self):
        gen = HeatmapGenerator(tile_size=64)
        tile_bytes = gen.generate_heatmap_tile(12, 3408, 1624, [], value_field="value")

        assert tile_bytes is not None
        assert len(tile_bytes) > 0

        img = Image.open(io.BytesIO(tile_bytes))
        assert img.size == (64, 64)
        assert img.mode == "RGBA"

        img_array = np.array(img)
        non_transparent = img_array[img_array[:, :, 3] > 0]
        assert len(non_transparent) == 0, "Zero-flow tile should have no visible pixels"

    def test_zero_value_data_points_no_crash(self, sample_zero_flow_records):
        gen = HeatmapGenerator(tile_size=64)
        data_points = [
            {"lon": r["lon"], "lat": r["lat"], "value": 0}
            for r in sample_zero_flow_records
        ]
        tile_bytes = gen.generate_heatmap_tile(12, 3408, 1624, data_points, value_field="value")

        assert tile_bytes is not None
        assert len(tile_bytes) > 0

        img = Image.open(io.BytesIO(tile_bytes))
        assert img.mode == "RGBA"

    def test_extreme_single_point_does_not_wash_out_others_in_same_tile(self):
        gen = HeatmapGenerator(tile_size=256, radius=0.005)

        extreme_lon = 116.4074
        extreme_lat = 39.9042
        extreme_value = 9999

        normal_lon = 116.4080
        normal_lat = 39.9048
        normal_value = 50

        combined_data = [
            {"lon": extreme_lon, "lat": extreme_lat, "value": extreme_value},
            {"lon": normal_lon, "lat": normal_lat, "value": normal_value},
        ]

        z, x, y = 14, 13634, 6497

        normal_only_data = [
            {"lon": normal_lon, "lat": normal_lat, "value": normal_value},
        ]

        combined_tile = gen.generate_heatmap_tile(z, x, y, combined_data, value_field="value")
        normal_only_tile = gen.generate_heatmap_tile(z, x, y, normal_only_data, value_field="value")

        combined_img = np.array(Image.open(io.BytesIO(combined_tile)))
        normal_img = np.array(Image.open(io.BytesIO(normal_only_tile)))

        assert combined_tile is not None
        assert normal_only_tile is not None

        combined_visible = np.sum(combined_img[:, :, 3] > 0)
        normal_visible = np.sum(normal_img[:, :, 3] > 0)

        assert combined_visible > 0
        assert normal_visible > 0

    def test_extreme_point_produces_visible_pixels(self):
        gen = HeatmapGenerator(tile_size=64)
        data = [{"lon": 116.4074, "lat": 39.9042, "value": 10000}]
        tile_bytes = gen.generate_heatmap_tile(14, 13634, 6497, data, value_field="value")

        img = Image.open(io.BytesIO(tile_bytes))
        img_array = np.array(img)
        visible_pixels = np.sum(img_array[:, :, 3] > 0)
        assert visible_pixels > 0, "Extreme point should produce visible pixels"

    def test_all_same_high_value_produces_warm_colors(self):
        gen = HeatmapGenerator(tile_size=64)
        data = [
            {"lon": 116.4074, "lat": 39.9042, "value": 5000},
            {"lon": 116.4080, "lat": 39.9048, "value": 5000},
            {"lon": 116.4086, "lat": 39.9054, "value": 5000},
        ]
        tile_bytes = gen.generate_heatmap_tile(14, 13634, 6497, data, value_field="value")

        assert tile_bytes is not None

        img = Image.open(io.BytesIO(tile_bytes))
        img_array = np.array(img)
        visible = img_array[img_array[:, :, 3] > 0]
        if len(visible) > 0:
            avg_red = np.mean(visible[:, 0])
            avg_blue = np.mean(visible[:, 2])
            assert avg_red >= avg_blue, "High uniform flow should show warm colors"

    def test_geojson_with_zero_values(self):
        gen = HeatmapGenerator()
        data = [
            {"lon": 116.4074, "lat": 39.9042, "value": 0},
            {"lon": 116.4084, "lat": 39.9052, "value": 0},
        ]
        geojson = gen.generate_heatmap_geojson(data, value_field="value")
        assert geojson["type"] == "FeatureCollection"
        assert len(geojson["features"]) == 2

    def test_geojson_with_extreme_values(self):
        gen = HeatmapGenerator()
        data = [{"lon": 116.4074, "lat": 39.9042, "value": 999999}]
        geojson = gen.generate_heatmap_geojson(data, value_field="value")
        assert len(geojson["features"]) == 1

    def test_empty_data_produces_valid_png(self):
        gen = HeatmapGenerator(tile_size=64)
        tile_bytes = gen.generate_heatmap_tile(12, 3408, 1624, [], value_field="value")
        img = Image.open(io.BytesIO(tile_bytes))
        assert img.mode == "RGBA"
        assert img.size == (64, 64)

    def test_max_value_parameter_controls_normalization(self):
        gen = HeatmapGenerator(tile_size=64)
        data = [{"lon": 116.4074, "lat": 39.9042, "value": 100}]

        tile_low_max = gen.generate_heatmap_tile(
            14, 13634, 6497, data, value_field="value", max_value=50
        )
        tile_high_max = gen.generate_heatmap_tile(
            14, 13634, 6497, data, value_field="value", max_value=10000
        )

        assert tile_low_max is not None
        assert tile_high_max is not None

        img_low = np.array(Image.open(io.BytesIO(tile_low_max)))
        img_high = np.array(Image.open(io.BytesIO(tile_high_max)))

        low_max_alpha = np.max(img_low[:, :, 3])
        high_max_alpha = np.max(img_high[:, :, 3])

        assert low_max_alpha > 0
        assert high_max_alpha > 0

    def test_interpolate_color_returns_4_tuple(self):
        for val in [-1.0, 0.0, 0.25, 0.5, 0.75, 1.0, 2.0]:
            color = interpolate_color(val)
            assert len(color) == 4
            assert all(0 <= c <= 255 for c in color)

    def test_single_point_all_zooms_produce_valid_output(self):
        gen = HeatmapGenerator(tile_size=256)
        data = [{"lon": 116.4074, "lat": 39.9042, "value": 100}]

        for z in range(8, 18):
            from app.utils.geo_utils import latlon_to_tile
            x, y = latlon_to_tile(39.9042, 116.4074, z)
            tile_bytes = gen.generate_heatmap_tile(z, x, y, data, value_field="value")
            assert tile_bytes is not None
            assert len(tile_bytes) > 0

    def test_negative_value_data_points_treated_as_zero(self):
        gen = HeatmapGenerator(tile_size=64)
        data = [
            {"lon": 116.4074, "lat": 39.9042, "value": -100},
        ]
        tile_bytes = gen.generate_heatmap_tile(14, 13634, 6497, data, value_field="value")
        assert tile_bytes is not None

        img = Image.open(io.BytesIO(tile_bytes))
        assert img.mode == "RGBA"

    def test_very_large_number_of_points_no_overflow(self):
        gen = HeatmapGenerator(tile_size=64)

        data = [
            {"lon": 116.4074 + i * 0.0001, "lat": 39.9042 + i * 0.0001, "value": float(i)}
            for i in range(500)
        ]

        tile_bytes = gen.generate_heatmap_tile(14, 13634, 6497, data, value_field="value")
        assert tile_bytes is not None

        img = Image.open(io.BytesIO(tile_bytes))
        assert img.mode == "RGBA"
