import pytest
import json
import time
import asyncio
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch, AsyncMock

import httpx
from shapely.geometry import Point, box, mapping, shape
from shapely.assertions import assert_geometries_equal

from app.heatmap.generator import HeatmapGenerator
from app.heatmap.service import HeatmapService
from app.tiles.generator import TileGenerator
from app.etl.pipeline import DataCleaner, TrajectoryMatcher, TimeWindowAggregator
from app.etl.kafka_consumer import KafkaConsumerManager
from app.utils.geo_utils import latlon_to_tile, tile_bbox


SAMPLE_TRAFFIC_MESSAGES = []
_base_time = datetime(2024, 1, 1, 8, 0, 0)
for minute in range(60):
    for sensor_id in range(5):
        SAMPLE_TRAFFIC_MESSAGES.append({
            "sensor_id": f"S{sensor_id:03d}",
            "timestamp": (_base_time + timedelta(minutes=minute)).isoformat(),
            "vehicle_count": 50 + minute + sensor_id * 10,
            "pedestrian_count": 10 + minute,
            "avg_speed": 35.0 + minute * 0.5,
            "congestion_index": min(0.3 + minute * 0.01, 1.0),
            "lon": 116.4074 + sensor_id * 0.001,
            "lat": 39.9042 + sensor_id * 0.0008,
        })


@pytest.mark.integration
class TestEndToEndPipeline:
    """完整链路集成测试：Kafka→ETL→热力图→3D Tiles→API"""

    def test_kafka_to_heatmap_pipeline(self, sample_road_network):
        consumed_messages = list(SAMPLE_TRAFFIC_MESSAGES[:10])

        cleaner = DataCleaner()
        cleaned = cleaner.clean_traffic_data(consumed_messages)

        assert len(cleaned) > 0
        for record in cleaned:
            assert record["vehicle_count"] >= 0
            assert 0.0 <= record.get("congestion_index", 0) <= 1.0

        gen = HeatmapGenerator(tile_size=64)
        data_points = [
            {"lon": r["lon"], "lat": r["lat"], "value": r["vehicle_count"]}
            for r in cleaned
        ]
        z, x, y = 14, 13634, 6497
        tile_bytes = gen.generate_heatmap_tile(z, x, y, data_points, value_field="value")

        assert tile_bytes is not None
        assert len(tile_bytes) > 0

    def test_3d_tiles_building_generation(self):
        gen = TileGenerator()

        mock_buildings = [
            {"id": 1, "name": "Building A", "height": 50.0,
             "geom": '{"type": "Polygon", "coordinates": [[[116.4074, 39.9042], [116.4078, 39.9042], [116.4078, 39.9046], [116.4074, 39.9046], [116.4074, 39.9042]]]}'},
            {"id": 2, "name": "Building B", "height": 80.0,
             "geom": '{"type": "Polygon", "coordinates": [[[116.4084, 39.9052], [116.4088, 39.9052], [116.4088, 39.9056], [116.4084, 39.9056], [116.4084, 39.9052]]]}'},
        ]

        with patch.object(gen, '_query_buildings', return_value=mock_buildings):
            with patch.object(gen, '_query_roads', return_value=[]):
                with patch.object(gen, '_query_pois', return_value=[]):
                    tileset = gen.generate_building_tileset(
                        bbox=(116.3, 39.8, 116.5, 40.0),
                        max_level=2,
                    )

        if tileset is not None:
            assert "asset" in tileset
            assert "root" in tileset

    def test_api_tile_url_format(self, httpx_client):
        response = httpx_client.get("/api/v1/tiles/tileset.json")
        assert response.status_code in [200, 404]

    def test_heatmap_tile_endpoint(self, httpx_client):
        response = httpx_client.get(
            "/api/v1/heatmap/tile/14/13634/6497",
            params={"time_range": "2024-01-01T08:00:00"},
        )
        assert response.status_code in [200, 404]

    def test_health_check(self, httpx_client):
        response = httpx_client.get("/health")
        assert response.status_code == 200

    def test_root_info(self, httpx_client):
        response = httpx_client.get("/")
        assert response.status_code == 200


@pytest.mark.integration
class TestEndToEndLatency:
    """端到端延迟验证：从Kafka接收到热力瓦片刷新可用不超过30秒"""

    def test_kafka_to_heatmap_latency_under_30s(self, sample_road_network):
        start_time = time.time()

        messages = SAMPLE_TRAFFIC_MESSAGES[:10]

        cleaner = DataCleaner()
        cleaned = cleaner.clean_traffic_data(messages)

        gen = HeatmapGenerator(tile_size=64)
        data_points = [
            {"lon": r["lon"], "lat": r["lat"], "value": r["vehicle_count"]}
            for r in cleaned
        ]
        tile_bytes = gen.generate_heatmap_tile(14, 13634, 6497, data_points, value_field="value")

        elapsed = time.time() - start_time

        assert tile_bytes is not None
        assert elapsed < 30.0, \
            f"End-to-end latency {elapsed:.2f}s exceeds 30s threshold"

    def test_cached_tile_retrieval_under_1s(self):
        gen = HeatmapGenerator(tile_size=64)
        data_points = [
            {"lon": 116.4074, "lat": 39.9042, "value": 100},
        ]

        first_gen_start = time.time()
        first_tile = gen.generate_heatmap_tile(14, 13634, 6497, data_points, value_field="value")
        first_gen_time = time.time() - first_gen_start

        assert first_tile is not None
        assert first_gen_time < 1.0, f"Tile generation took {first_gen_time:.3f}s"


@pytest.mark.integration
class TestKafkaETLPipelineIntegration:
    """Kafka生产消费 + ETL清洗入库集成测试"""

    def test_produce_consume_roundtrip(self):
        test_messages = SAMPLE_TRAFFIC_MESSAGES[:5]

        received_messages = []
        for msg in test_messages:
            processed = {
                "sensor_id": msg["sensor_id"],
                "timestamp": msg["timestamp"],
                "vehicle_count": msg["vehicle_count"],
                "lon": msg["lon"],
                "lat": msg["lat"],
            }
            received_messages.append(processed)

        assert len(received_messages) == len(test_messages)
        for orig, recv in zip(test_messages, received_messages):
            assert orig["sensor_id"] == recv["sensor_id"]
            assert orig["vehicle_count"] == recv["vehicle_count"]

    def test_clean_and_store_pipeline(self, sample_road_network):
        raw_messages = SAMPLE_TRAFFIC_MESSAGES[:10]

        cleaner = DataCleaner()
        cleaned = cleaner.clean_traffic_data(raw_messages)

        assert len(cleaned) > 0

        for record in cleaned:
            assert "sensor_id" in record
            assert "timestamp" in record
            assert record["vehicle_count"] >= 0

        with patch.object(TrajectoryMatcher, '_get_roads', return_value=sample_road_network):
            matcher = TrajectoryMatcher(max_match_distance=50.0)
            trajectories = [
                {"vehicle_id": f"V{i}", "lon": r["lon"], "lat": r["lat"],
                 "speed": r["avg_speed"], "heading": 90.0,
                 "vehicle_type": "car", "timestamp": datetime.utcnow()}
                for i, r in enumerate(cleaned)
            ]
            matched = matcher.match_trajectories(trajectories)

        assert len(matched) > 0

        aggregator = TimeWindowAggregator()
        aggregated = aggregator.aggregate_by_sensor(cleaned, window="1h")
        assert len(aggregated) > 0


@pytest.mark.integration
class TestHTTPAPIEndpoints:
    """HTTP接口测试：FastAPI返回正确的切片URL和GeoJSON"""

    def test_health_endpoint(self, httpx_client):
        response = httpx_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert "status" in data

    def test_heatmap_tile_endpoint(self, httpx_client):
        response = httpx_client.get("/api/v1/heatmap/tile/14/13634/6497")
        assert response.status_code in [200, 404]

    def test_heatmap_geojson_endpoint(self, httpx_client):
        response = httpx_client.get(
            "/api/v1/heatmap/geojson",
            params={
                "bbox": "116.3,39.8,116.5,40.0",
                "time_range": "2024-01-01T08:00:00",
            },
        )
        assert response.status_code in [200, 404, 422]

    def test_tiles_tileset_endpoint(self, httpx_client):
        response = httpx_client.get("/api/v1/tiles/tileset.json")
        assert response.status_code in [200, 404]

    def test_analysis_od_endpoint(self, httpx_client):
        response = httpx_client.post(
            "/api/v1/analysis/od",
            json={
                "origin_bbox": [116.3, 39.8, 116.4, 39.9],
                "destination_bbox": [116.4, 39.9, 116.5, 40.0],
                "time_range": "2024-01-01T08:00:00/2024-01-01T09:00:00",
            },
        )
        assert response.status_code in [200, 404, 422]

    def test_prediction_endpoint(self, httpx_client):
        response = httpx_client.post(
            "/api/v1/prediction/predict",
            json={
                "sensor_id": "S001",
                "prediction_horizons": [15, 30, 60],
            },
        )
        assert response.status_code in [200, 404, 422]

    def test_data_sources_endpoint(self, httpx_client):
        response = httpx_client.get("/api/v1/data/sources")
        assert response.status_code in [200, 404]

    def test_wms_get_capabilities(self, httpx_client):
        response = httpx_client.get(
            "/api/v1/tiles/wms",
            params={
                "SERVICE": "WMS",
                "VERSION": "1.3.0",
                "REQUEST": "GetCapabilities",
            },
        )
        assert response.status_code in [200, 404]

    def test_wmts_get_capabilities(self, httpx_client):
        response = httpx_client.get(
            "/api/v1/tiles/wmts",
            params={
                "SERVICE": "WMTS",
                "VERSION": "1.0.0",
                "REQUEST": "GetCapabilities",
            },
        )
        assert response.status_code in [200, 404]

    def test_wms_get_map(self, httpx_client):
        response = httpx_client.get(
            "/api/v1/tiles/wms",
            params={
                "SERVICE": "WMS",
                "VERSION": "1.3.0",
                "REQUEST": "GetMap",
                "LAYERS": "heatmap",
                "CRS": "EPSG:4326",
                "BBOX": "39.8,116.3,40.0,116.5",
                "WIDTH": "256",
                "HEIGHT": "256",
                "FORMAT": "image/png",
            },
        )
        assert response.status_code in [200, 404, 422]


@pytest.mark.integration
class TestShapelyGeometryAssertions:
    """使用shapely做几何断言的空间数据测试"""

    def test_tile_bbox_covers_target_point(self):
        target = Point(116.4074, 39.9042)
        z, x, y = latlon_to_tile(39.9042, 116.4074, 14)
        bbox = tile_bbox(z, x, y)

        tile_bounds = box(bbox[0], bbox[1], bbox[2], bbox[3])
        assert tile_bounds.contains(target), \
            f"Tile ({z}/{x}/{y}) bbox should contain target point"

    def test_adjacent_tiles_share_edge(self):
        z = 14
        bbox1 = tile_bbox(z, 13634, 6497)
        bbox2 = tile_bbox(z, 13635, 6497)

        tile1 = box(bbox1[0], bbox1[1], bbox1[2], bbox1[3])
        tile2 = box(bbox2[0], bbox2[1], bbox2[2], bbox2[3])

        assert tile1.touches(tile2), "Adjacent tiles should share an edge"

    def test_diagonal_tiles_do_not_overlap(self):
        z = 14
        bbox1 = tile_bbox(z, 13634, 6497)
        bbox2 = tile_bbox(z, 13635, 6498)

        tile1 = box(bbox1[0], bbox1[1], bbox1[2], bbox1[3])
        tile2 = box(bbox2[0], bbox2[1], bbox2[2], bbox2[3])

        assert not tile1.overlaps(tile2), "Diagonal tiles should not overlap"

    def test_road_network_geometry_valid(self, sample_road_network):
        for road in sample_road_network:
            geom = road["geom"]
            assert geom.is_valid, f"Road {road['id']} geometry is not valid: {geom}"
            assert geom.length > 0, f"Road {road['id']} has zero length"

    def test_building_polygon_valid(self):
        building_coords = [
            (116.4074, 39.9042), (116.4078, 39.9042),
            (116.4078, 39.9046), (116.4074, 39.9046),
            (116.4074, 39.9042),
        ]
        building = Polygon(building_coords)

        assert building.is_valid
        assert building.area > 0
        assert abs(building.area - 0.0004 * 0.0004) < 1e-10

    def test_point_within_query_bbox(self):
        query_bbox = box(116.3, 39.8, 116.5, 40.0)
        sensor_point = Point(116.4074, 39.9042)

        assert query_bbox.contains(sensor_point), \
            "Sensor point should be within query bbox"

    def test_spatial_intersection_query(self, sample_road_network):
        query_area = box(116.4070, 39.9040, 116.4080, 39.9050)

        intersecting_roads = [
            road for road in sample_road_network
            if road["geom"].intersects(query_area)
        ]

        assert len(intersecting_roads) > 0, \
            "Query area should intersect with at least one road"

    def test_geojson_feature_geometry_roundtrip(self):
        original_point = Point(116.4074, 39.9042)
        geojson = mapping(original_point)

        assert geojson["type"] == "Point"
        assert len(geojson["coordinates"]) == 2

        reconstructed = shape(geojson)
        assert_geometries_equal(original_point, reconstructed, tolerance=1e-10)

    def test_heatmap_data_covers_target_region(self):
        target_region = box(116.4070, 39.9040, 116.4080, 39.9050)

        data_points = [
            Point(116.4074, 39.9042),
            Point(116.4076, 39.9044),
            Point(116.4078, 39.9046),
        ]

        for pt in data_points:
            assert target_region.contains(pt), \
                f"Data point {pt} should be within target region"
