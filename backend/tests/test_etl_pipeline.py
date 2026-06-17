import pytest
import numpy as np
from datetime import datetime, timedelta
from shapely.geometry import Point, LineString
from shapely.assertions import assert_geometries_equal
from unittest.mock import MagicMock, patch

from app.etl.pipeline import DataCleaner, TrajectoryMatcher, TimeWindowAggregator
from app.utils.geo_utils import match_point_to_road, point_to_line_distance, haversine_distance


METERS_PER_DEGREE_LAT = 111000.0
METERS_PER_DEGREE_LON_AT_BEIJING = 111000.0 * np.cos(np.radians(39.9))


@pytest.mark.unit
class TestTrajectoryMatchingAccuracy:
    """ETL管道路网匹配精度测试：匹配结果与实际路网偏差不超过5米"""

    def test_point_exactly_on_road_matches_with_zero_distance(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]
        mid_point = road_geom.interpolate(0.5, normalized=True)

        result = match_point_to_road(mid_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["id"] == 1
        assert result["match_distance"] < 1.0, \
            f"Point on road should have near-zero match distance, got {result['match_distance']:.4f}m"

    def test_point_1m_offset_matches_within_5m(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]
        mid_point = road_geom.interpolate(0.5, normalized=True)

        offset_meters = 1
        offset_degrees = offset_meters / METERS_PER_DEGREE_LAT
        offset_point = Point(mid_point.x, mid_point.y + offset_degrees)

        result = match_point_to_road(offset_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["match_distance"] <= 5.0, \
            f"1m offset point should match within 5m, got {result['match_distance']:.2f}m"

    def test_point_3m_offset_matches_within_5m(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]
        mid_point = road_geom.interpolate(0.5, normalized=True)

        offset_meters = 3
        offset_degrees = offset_meters / METERS_PER_DEGREE_LAT
        offset_point = Point(mid_point.x, mid_point.y + offset_degrees)

        result = match_point_to_road(offset_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["match_distance"] <= 5.0, \
            f"3m offset should match within 5m, got {result['match_distance']:.2f}m"

    def test_point_5m_offset_at_boundary(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]
        mid_point = road_geom.interpolate(0.5, normalized=True)

        offset_meters = 5
        offset_degrees = offset_meters / METERS_PER_DEGREE_LAT
        offset_point = Point(mid_point.x, mid_point.y + offset_degrees)

        result = match_point_to_road(offset_point, sample_road_network, max_distance=50.0)

        assert result is not None, "5m offset should still find a match"
        actual_m = result["match_distance"]
        assert actual_m <= 10.0, \
            f"5m offset match distance {actual_m:.2f}m should be within reasonable range"

    def test_point_10m_offset_beyond_5m_threshold(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]
        mid_point = road_geom.interpolate(0.5, normalized=True)

        offset_meters = 10
        offset_degrees = offset_meters / METERS_PER_DEGREE_LAT
        offset_point = Point(mid_point.x, mid_point.y + offset_degrees)

        result = match_point_to_road(offset_point, sample_road_network, max_distance=50.0)

        assert result is not None, "10m should still match with max_distance=50m"
        assert result["match_distance"] > 5.0, \
            "10m offset should have match distance > 5m"

    def test_point_far_from_all_roads_no_match(self, sample_road_network):
        far_point = Point(116.3900, 39.9200)

        result = match_point_to_road(far_point, sample_road_network, max_distance=50.0)

        assert result is None

    def test_multiple_roads_picks_closest(self, sample_road_network):
        road1 = sample_road_network[0]
        mid1 = road1["geom"].interpolate(0.5, normalized=True)
        closer_point = Point(mid1.x + 0.00001, mid1.y)

        result = match_point_to_road(closer_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["id"] == 1

    def test_on_road_trajectory_matches_within_5m(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]

        on_road_point = road_geom.interpolate(0.3, normalized=True)
        trajectory = [{
            "vehicle_id": "V_ON_ROAD",
            "lon": on_road_point.x,
            "lat": on_road_point.y,
            "speed": 45.0,
            "heading": 90.0,
            "vehicle_type": "car",
            "timestamp": datetime.utcnow(),
        }]

        with patch.object(TrajectoryMatcher, '_get_roads', return_value=sample_road_network):
            matcher = TrajectoryMatcher(max_match_distance=50.0)
            matched = matcher.match_trajectories(trajectory)

        assert len(matched) == 1
        assert matched[0]["matched_road_id"] is not None
        assert matched[0]["match_distance"] <= 5.0, \
            f"On-road match distance {matched[0]['match_distance']:.2f}m exceeds 5m"

    def test_curved_road_matching_within_5m(self, sample_road_network):
        curved_road = sample_road_network[4]
        road_geom = curved_road["geom"]

        mid_point = road_geom.interpolate(0.5, normalized=True)
        offset_meters = 2
        offset_degrees = offset_meters / METERS_PER_DEGREE_LAT
        near_point = Point(mid_point.x, mid_point.y + offset_degrees)

        result = match_point_to_road(near_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["match_distance"] <= 5.0, \
            f"Curved road match distance {result['match_distance']:.2f}m exceeds 5m"

    def test_batch_matching_accuracy_statistics(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]

        test_trajectories = []
        for frac in [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]:
            on_road = road_geom.interpolate(frac, normalized=True)
            offset_deg = 2 / METERS_PER_DEGREE_LAT
            test_trajectories.append({
                "vehicle_id": f"V_{frac:.1f}",
                "lon": on_road.x,
                "lat": on_road.y + offset_deg,
                "speed": 40.0,
                "heading": 90.0,
                "vehicle_type": "car",
                "timestamp": datetime.utcnow(),
            })

        with patch.object(TrajectoryMatcher, '_get_roads', return_value=sample_road_network):
            matcher = TrajectoryMatcher(max_match_distance=50.0)
            matched = matcher.match_trajectories(test_trajectories)

        matched_to_road = [m for m in matched if m.get("matched_road_id") is not None]
        assert len(matched_to_road) == len(test_trajectories), \
            f"Only {len(matched_to_road)}/{len(test_trajectories)} trajectories matched"

        max_distance = max(m["match_distance"] for m in matched_to_road)
        avg_distance = sum(m["match_distance"] for m in matched_to_road) / len(matched_to_road)

        assert max_distance <= 5.0, \
            f"Max match distance {max_distance:.2f}m exceeds 5m threshold"
        assert avg_distance <= 3.0, \
            f"Average match distance {avg_distance:.2f}m exceeds 3m"

    def test_haversine_distance_accuracy_beijing(self):
        lat1, lon1 = 39.9042, 116.4074
        lat2, lon2 = 39.9042, 116.4084

        dist = haversine_distance(lat1, lon1, lat2, lon2)

        expected = 0.001 * METERS_PER_DEGREE_LON_AT_BEIJING
        assert abs(dist - expected) / expected < 0.01, \
            f"Haversine {dist:.1f}m differs from expected {expected:.1f}m by >1%"

    def test_haversine_north_south_distance(self):
        lat1, lon1 = 39.9042, 116.4074
        lat2, lon2 = 39.9052, 116.4074

        dist = haversine_distance(lat1, lon1, lat2, lon2)

        expected = 0.001 * METERS_PER_DEGREE_LAT
        assert abs(dist - expected) / expected < 0.01

    def test_matching_at_road_endpoints(self, sample_road_network):
        road = sample_road_network[0]
        road_geom = road["geom"]

        start_point = Point(road_geom.coords[0])
        end_point = Point(road_geom.coords[-1])

        result_start = match_point_to_road(start_point, sample_road_network, max_distance=50.0)
        result_end = match_point_to_road(end_point, sample_road_network, max_distance=50.0)

        assert result_start is not None
        assert result_end is not None
        assert result_start["match_distance"] <= 5.0
        assert result_end["match_distance"] <= 5.0

    def test_matching_at_intersection(self, sample_road_network):
        intersection_point = Point(116.4074, 39.9042)

        result = match_point_to_road(intersection_point, sample_road_network, max_distance=50.0)

        assert result is not None
        assert result["match_distance"] <= 5.0


@pytest.mark.unit
class TestDataCleaner:
    """数据清洗管道测试"""

    def test_negative_vehicle_count_corrected_to_zero(self):
        cleaner = DataCleaner()
        records = [{
            "sensor_id": "S001",
            "timestamp": datetime.utcnow().isoformat(),
            "vehicle_count": -10,
            "pedestrian_count": -5,
        }]
        cleaned = cleaner.clean_traffic_data(records)
        assert len(cleaned) == 1
        assert cleaned[0]["vehicle_count"] == 0
        assert cleaned[0]["pedestrian_count"] == 0

    def test_invalid_speed_nullified(self):
        cleaner = DataCleaner()
        records = [{
            "sensor_id": "S001",
            "timestamp": datetime.utcnow().isoformat(),
            "vehicle_count": 100,
            "avg_speed": 300,
        }]
        cleaned = cleaner.clean_traffic_data(records)
        assert cleaned[0]["avg_speed"] is None

    def test_congestion_index_clamped_to_1(self):
        cleaner = DataCleaner()
        records = [{
            "sensor_id": "S001",
            "timestamp": datetime.utcnow().isoformat(),
            "vehicle_count": 100,
            "congestion_index": 1.5,
        }]
        cleaned = cleaner.clean_traffic_data(records)
        assert cleaned[0]["congestion_index"] == 1.0

    def test_negative_congestion_index_clamped_to_0(self):
        cleaner = DataCleaner()
        records = [{
            "sensor_id": "S001",
            "timestamp": datetime.utcnow().isoformat(),
            "vehicle_count": 100,
            "congestion_index": -0.5,
        }]
        cleaned = cleaner.clean_traffic_data(records)
        assert cleaned[0]["congestion_index"] == 0.0

    def test_invalid_coordinates_rejected(self):
        cleaner = DataCleaner()
        bad_coords = [
            {"sensor_id": "S", "timestamp": datetime.utcnow().isoformat(),
             "vehicle_count": 100, "lon": 200.0, "lat": 100.0},
            {"sensor_id": "S", "timestamp": datetime.utcnow().isoformat(),
             "vehicle_count": 100, "lon": -181.0, "lat": 39.0},
            {"sensor_id": "S", "timestamp": datetime.utcnow().isoformat(),
             "vehicle_count": 100, "lon": 116.0, "lat": -91.0},
        ]
        cleaned = cleaner.clean_traffic_data(bad_coords)
        assert len(cleaned) == 0

    def test_missing_required_fields_rejected(self):
        cleaner = DataCleaner()
        records = [
            {"timestamp": datetime.utcnow().isoformat(), "vehicle_count": 100},
            {"sensor_id": "S001", "vehicle_count": 100},
            {"sensor_id": "S001", "timestamp": datetime.utcnow().isoformat()},
        ]
        cleaned = cleaner.clean_traffic_data(records)
        assert len(cleaned) == 0

    def test_iqr_outlier_removal(self):
        cleaner = DataCleaner()
        import pandas as pd
        data = pd.DataFrame({"vehicle_count": [50, 55, 48, 52, 60, 5000, 53, 47]})
        result = cleaner.remove_outliers(data, "vehicle_count", method="iqr")
        assert 5000 not in result["vehicle_count"].values

    def test_zscore_outlier_removal(self):
        cleaner = DataCleaner()
        import pandas as pd
        data = pd.DataFrame({"vehicle_count": [50, 55, 48, 52, 60, 5000, 53, 47]})
        result = cleaner.remove_outliers(data, "vehicle_count", method="zscore")
        assert 5000 not in result["vehicle_count"].values

    def test_fill_missing_linear(self):
        cleaner = DataCleaner()
        import pandas as pd
        import numpy as np
        data = pd.DataFrame({"vehicle_count": [50, None, 70, None, 90]})
        result = cleaner.fill_missing_values(data, "vehicle_count", method="linear")
        assert result["vehicle_count"].isna().sum() == 0
        assert abs(result["vehicle_count"].iloc[1] - 60.0) < 0.01

    def test_clean_traffic_data_preserves_valid_records(self):
        cleaner = DataCleaner()
        now = datetime.utcnow().isoformat()
        records = [
            {"sensor_id": "S001", "timestamp": now, "vehicle_count": 100,
             "pedestrian_count": 20, "avg_speed": 45.0, "congestion_index": 0.5},
            {"sensor_id": "S002", "timestamp": now, "vehicle_count": 80,
             "pedestrian_count": 15, "avg_speed": 50.0, "congestion_index": 0.3},
        ]
        cleaned = cleaner.clean_traffic_data(records)
        assert len(cleaned) == 2
        for c in cleaned:
            assert c["vehicle_count"] >= 0
            assert 0.0 <= c["congestion_index"] <= 1.0


@pytest.mark.unit
class TestTimeWindowAggregator:
    """时间窗口聚合测试"""

    def test_time_period_classification_morning_peak(self):
        aggregator = TimeWindowAggregator()
        assert aggregator.get_time_period(datetime(2024, 1, 1, 8, 0, 0)) == "morning_peak"
        assert aggregator.get_time_period(datetime(2024, 1, 1, 7, 30, 0)) == "morning_peak"

    def test_time_period_classification_evening_peak(self):
        aggregator = TimeWindowAggregator()
        assert aggregator.get_time_period(datetime(2024, 1, 1, 18, 0, 0)) == "evening_peak"
        assert aggregator.get_time_period(datetime(2024, 1, 1, 19, 30, 0)) == "evening_peak"

    def test_time_period_classification_night(self):
        aggregator = TimeWindowAggregator()
        assert aggregator.get_time_period(datetime(2024, 1, 1, 2, 0, 0)) == "night"
        assert aggregator.get_time_period(datetime(2024, 1, 1, 23, 30, 0)) == "evening"

    def test_time_period_classification_lunch(self):
        aggregator = TimeWindowAggregator()
        assert aggregator.get_time_period(datetime(2024, 1, 1, 12, 30, 0)) == "lunch"

    def test_aggregate_by_sensor(self):
        aggregator = TimeWindowAggregator()
        now = datetime.utcnow()
        records = [
            {"sensor_id": "S001", "timestamp": now, "vehicle_count": 100, "pedestrian_count": 10},
            {"sensor_id": "S001", "timestamp": now + timedelta(minutes=30), "vehicle_count": 120, "pedestrian_count": 15},
            {"sensor_id": "S002", "timestamp": now, "vehicle_count": 80, "pedestrian_count": 5},
        ]
        result = aggregator.aggregate_by_sensor(records, window="1h")
        assert len(result) > 0

    def test_aggregate_by_sensor_empty_input(self):
        aggregator = TimeWindowAggregator()
        result = aggregator.aggregate_by_sensor([], window="1h")
        assert result == []
