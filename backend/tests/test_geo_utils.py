import pytest
from shapely.geometry import Point, LineString

from app.utils.geo_utils import (
    tile_to_latlon, latlon_to_tile, tile_bbox,
    haversine_distance, point_to_line_distance,
    match_point_to_road, generate_grid_points, gaussian_kernel,
)


@pytest.mark.unit
class TestGeoUtils:
    """地理计算工具函数测试"""

    def test_tile_to_latlon_roundtrip(self):
        z, x, y = 14, 13634, 6497
        lat, lon = tile_to_latlon(z, x, y)
        assert -90 <= lat <= 90
        assert -180 <= lon <= 180

    def test_latlon_to_tile_consistency(self):
        lat, lon = 39.9042, 116.4074
        z = 14
        x, y = latlon_to_tile(lat, lon, z)
        assert isinstance(x, int)
        assert isinstance(y, int)
        assert 0 <= x < 2 ** z
        assert 0 <= y < 2 ** z

    def test_tile_bbox_valid_bounds(self):
        bbox = tile_bbox(14, 13634, 6497)
        min_lon, min_lat, max_lon, max_lat = bbox
        assert min_lon < max_lon
        assert min_lat < max_lat

    def test_tile_bbox_beijing_coverage(self):
        bbox = tile_bbox(14, 13634, 6497)
        min_lon, min_lat, max_lon, max_lat = bbox
        assert 115.0 < min_lon < 118.0
        assert 39.0 < min_lat < 41.0

    def test_haversine_distance_known_values(self):
        dist = haversine_distance(39.9042, 116.4074, 39.9042, 116.4074)
        assert abs(dist) < 1.0

        dist = haversine_distance(0, 0, 0, 1)
        assert 110000 < dist < 112000

    def test_haversine_equator_distance(self):
        dist = haversine_distance(0, 0, 0, 180)
        assert 20000000 < dist < 20100000

    def test_point_to_line_distance_on_line(self):
        line = LineString([(0, 0), (10, 0)])
        point = Point(5, 0)
        dist = point_to_line_distance(point, line)
        assert abs(dist) < 1e-10

    def test_point_to_line_distance_off_line(self):
        line = LineString([(0, 0), (10, 0)])
        point = Point(5, 3)
        dist = point_to_line_distance(point, line)
        assert abs(dist - 3.0) < 1e-10

    def test_generate_grid_points_count(self):
        bbox = (116.40, 39.90, 116.41, 39.91)
        resolution = 0.002
        points = generate_grid_points(bbox, resolution)
        assert len(points) > 0
        assert points.shape[1] == 2

    def test_gaussian_kernel_peak_at_zero(self):
        val = gaussian_kernel(0, 1.0)
        assert val > 0
        assert val == pytest.approx(1.0 / (1.0 * np.sqrt(2 * np.pi)), rel=1e-5)

    def test_gaussian_kernel_decreases(self):
        val_near = gaussian_kernel(1, 1.0)
        val_far = gaussian_kernel(10, 1.0)
        assert val_near > val_far

    def test_match_point_to_road_finds_closest(self):
        road1 = LineString([(0, 0), (10, 0)])
        road2 = LineString([(0, 5), (10, 5)])
        roads = [
            {"id": 1, "geom": road1},
            {"id": 2, "geom": road2},
        ]

        point_near_road2 = Point(5, 5.001)
        result = match_point_to_road(point_near_road2, roads, max_distance=50.0)
        assert result is not None
        assert result["id"] == 2

    def test_match_point_no_match_beyond_threshold(self):
        road = LineString([(0, 0), (10, 0)])
        roads = [{"id": 1, "geom": road}]

        far_point = Point(5, 100)
        result = match_point_to_road(far_point, roads, max_distance=50.0)
        assert result is None


import numpy as np
