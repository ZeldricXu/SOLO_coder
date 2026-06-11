import logging
from typing import List, Dict, Optional
from datetime import datetime, timedelta
import pandas as pd
import numpy as np
from shapely.geometry import Point

from app.utils.geo_utils import match_point_to_road
from app.database import SessionLocal
from app.models import TrajectoryPoint, RoadNetwork

logger = logging.getLogger(__name__)


class DataCleaner:
    def __init__(self):
        self.speed_threshold = 200
        self.acceleration_threshold = 10

    def clean_traffic_data(self, records: List[Dict]) -> List[Dict]:
        cleaned = []
        for record in records:
            try:
                cleaned_record = self._validate_and_clean(record)
                if cleaned_record:
                    cleaned.append(cleaned_record)
            except Exception as e:
                logger.warning(f"Failed to clean record: {e}")
                continue
        logger.info(f"Cleaned {len(records)} records, kept {len(cleaned)}")
        return cleaned

    def _validate_and_clean(self, record: Dict) -> Optional[Dict]:
        required_fields = ['sensor_id', 'timestamp', 'vehicle_count']
        for field in required_fields:
            if field not in record:
                logger.warning(f"Missing required field: {field}")
                return None

        if record.get('vehicle_count', 0) < 0:
            record['vehicle_count'] = 0

        if record.get('pedestrian_count', 0) < 0:
            record['pedestrian_count'] = 0

        if 'avg_speed' in record:
            if record['avg_speed'] < 0 or record['avg_speed'] > self.speed_threshold:
                record['avg_speed'] = None

        if 'congestion_index' in record:
            record['congestion_index'] = max(0.0, min(1.0, record['congestion_index']))

        if 'timestamp' in record:
            if isinstance(record['timestamp'], str):
                record['timestamp'] = datetime.fromisoformat(record['timestamp'].replace('Z', '+00:00'))

        if 'lon' in record and 'lat' in record:
            if not (-180 <= record['lon'] <= 180 and -90 <= record['lat'] <= 90):
                logger.warning(f"Invalid coordinates: {record['lon']}, {record['lat']}")
                return None

        return record

    def remove_outliers(self, data: pd.DataFrame, column: str,
                        method: str = 'iqr') -> pd.DataFrame:
        if method == 'iqr':
            Q1 = data[column].quantile(0.25)
            Q3 = data[column].quantile(0.75)
            IQR = Q3 - Q1
            lower_bound = Q1 - 1.5 * IQR
            upper_bound = Q3 + 1.5 * IQR
            return data[(data[column] >= lower_bound) & (data[column] <= upper_bound)]
        elif method == 'zscore':
            mean = data[column].mean()
            std = data[column].std()
            if std == 0:
                return data
            z_scores = np.abs((data[column] - mean) / std)
            return data[z_scores < 3]
        return data

    def fill_missing_values(self, data: pd.DataFrame, column: str,
                            method: str = 'linear') -> pd.DataFrame:
        if method == 'linear':
            data[column] = data[column].interpolate(method='linear')
        elif method == 'ffill':
            data[column] = data[column].ffill()
        elif method == 'mean':
            data[column] = data[column].fillna(data[column].mean())
        return data


class TrajectoryMatcher:
    def __init__(self, max_match_distance: float = 50.0):
        self.max_match_distance = max_match_distance
        self._roads_cache = None
        self._roads_cache_time = None
        self._cache_duration = timedelta(hours=1)

    def match_trajectories(self, trajectories: List[Dict]) -> List[Dict]:
        roads = self._get_roads()
        matched = []

        for traj in trajectories:
            try:
                point = Point(traj['lon'], traj['lat'])
                matched_road = match_point_to_road(point, roads, self.max_match_distance)

                if matched_road:
                    traj['matched_road_id'] = matched_road.get('id')
                    traj['match_distance'] = matched_road.get('match_distance')
                else:
                    traj['matched_road_id'] = None
                    traj['match_distance'] = None

                matched.append(traj)
            except Exception as e:
                logger.warning(f"Failed to match trajectory: {e}")
                matched.append(traj)

        return matched

    def _get_roads(self):
        if (self._roads_cache is not None and
                self._roads_cache_time is not None and
                datetime.utcnow() - self._roads_cache_time < self._cache_duration):
            return self._roads_cache

        db = SessionLocal()
        try:
            road_records = db.query(RoadNetwork).all()
            roads = []
            for road in road_records:
                road_dict = {
                    'id': road.id,
                    'name': road.name,
                    'road_type': road.road_type,
                    'lanes': road.lanes,
                    'geom': road.geom,
                }
                roads.append(road_dict)

            self._roads_cache = roads
            self._roads_cache_time = datetime.utcnow()
            return roads
        finally:
            db.close()

    def batch_match_to_db(self, trajectories: List[Dict]) -> int:
        db = SessionLocal()
        try:
            matched = self.match_trajectories(trajectories)

            batch = []
            for traj in matched:
                point = Point(traj['lon'], traj['lat'])
                tp = TrajectoryPoint(
                    vehicle_id=traj.get('vehicle_id', ''),
                    timestamp=traj.get('timestamp', datetime.utcnow()),
                    location=point.wkt,
                    speed=traj.get('speed'),
                    heading=traj.get('heading'),
                    vehicle_type=traj.get('vehicle_type'),
                    matched_road_id=traj.get('matched_road_id'),
                )
                batch.append(tp)

            db.bulk_save_objects(batch)
            db.commit()
            return len(batch)
        except Exception as e:
            db.rollback()
            logger.error(f"Failed to batch save trajectories: {e}")
            raise
        finally:
            db.close()


class TimeWindowAggregator:
    def __init__(self):
        pass

    def aggregate(self, data: pd.DataFrame, time_column: str,
                  value_columns: List[str], window: str = '5m',
                  agg_func: str = 'mean') -> pd.DataFrame:
        data = data.set_index(pd.to_datetime(data[time_column]))

        agg_dict = {col: agg_func for col in value_columns}

        aggregated = data.resample(window).agg(agg_dict)
        aggregated = aggregated.reset_index()

        return aggregated

    def aggregate_by_sensor(self, records: List[Dict], window: str = '1h') -> List[Dict]:
        if not records:
            return []

        df = pd.DataFrame(records)
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        df = df.set_index('timestamp')

        numeric_cols = ['vehicle_count', 'pedestrian_count', 'avg_speed', 'congestion_index']
        numeric_cols = [col for col in numeric_cols if col in df.columns]

        agg_df = df.groupby('sensor_id').resample(window).agg({
            col: 'mean' for col in numeric_cols
        }).reset_index()

        return agg_df.to_dict('records')

    def get_time_period(self, timestamp: datetime) -> str:
        hour = timestamp.hour
        if 7 <= hour < 9:
            return "morning_peak"
        elif 9 <= hour < 12:
            return "morning"
        elif 12 <= hour < 14:
            return "lunch"
        elif 14 <= hour < 17:
            return "afternoon"
        elif 17 <= hour < 20:
            return "evening_peak"
        elif 20 <= hour < 23:
            return "evening"
        else:
            return "night"


data_cleaner = DataCleaner()
trajectory_matcher = TrajectoryMatcher()
time_aggregator = TimeWindowAggregator()
