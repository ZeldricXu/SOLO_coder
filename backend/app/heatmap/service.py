import logging
from datetime import datetime, timedelta
from typing import List, Dict, Optional
from sqlalchemy import func, and_, or_
from sqlalchemy.orm import Session
from geoalchemy2.shape import to_shape

from app.models import TrafficFlowRecord, TrafficSensor, RoadNetwork
from app.heatmap.generator import heatmap_generator
from app.utils.redis_client import redis_manager
from app.config import settings
import json

logger = logging.getLogger(__name__)


class HeatmapService:
    def __init__(self):
        self.cache_prefix = "heatmap:tile"
        self.cache_ttl = 3600

    def get_traffic_data_points(self, db: Session, start_time: datetime,
                                end_time: datetime, data_type: str = "vehicle",
                                vehicle_type: str = "all",
                                bbox: List[float] = None) -> List[Dict]:
        query = db.query(
            TrafficFlowRecord,
            TrafficSensor
        ).join(
            TrafficSensor, TrafficFlowRecord.sensor_id == TrafficSensor.sensor_id
        ).filter(
            TrafficFlowRecord.timestamp >= start_time,
            TrafficFlowRecord.timestamp <= end_time,
        )

        if vehicle_type != "all":
            query = query.filter(TrafficFlowRecord.vehicle_type == vehicle_type)

        if bbox and len(bbox) == 4:
            min_lon, min_lat, max_lon, max_lat = bbox
            query = query.filter(
                func.ST_Intersects(
                    TrafficFlowRecord.geom,
                    func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
                )
            )

        records = query.limit(10000).all()

        points = []
        sensor_values = {}

        for flow_record, sensor in records:
            sid = sensor.sensor_id
            if sid not in sensor_values:
                sensor_values[sid] = {
                    "lon": None,
                    "lat": None,
                    "vehicle_count": 0,
                    "pedestrian_count": 0,
                    "congestion_index": 0,
                    "record_count": 0
                }
                if sensor.location:
                    point = to_shape(sensor.location)
                    sensor_values[sid]["lon"] = point.x
                    sensor_values[sid]["lat"] = point.y

            if data_type == "vehicle":
                sensor_values[sid]["vehicle_count"] += flow_record.vehicle_count or 0
            elif data_type == "pedestrian":
                sensor_values[sid]["pedestrian_count"] += flow_record.pedestrian_count or 0
            elif data_type == "congestion":
                sensor_values[sid]["congestion_index"] += flow_record.congestion_index or 0

            sensor_values[sid]["record_count"] += 1

        for sid, data in sensor_values.items():
            if data["lon"] is None or data["lat"] is None:
                continue

            count = max(1, data["record_count"])
            if data_type == "vehicle":
                value = data["vehicle_count"] / count
            elif data_type == "pedestrian":
                value = data["pedestrian_count"] / count
            elif data_type == "congestion":
                value = data["congestion_index"] / count
            else:
                value = data["vehicle_count"] / count

            points.append({
                "sensor_id": sid,
                "lon": data["lon"],
                "lat": data["lat"],
                "value": value,
            })

        return points

    def generate_tile(self, db: Session, z: int, x: int, y: int,
                      timestamp: datetime = None, data_type: str = "vehicle",
                      vehicle_type: str = "all") -> bytes:
        cache_key = f"{self.cache_prefix}:{z}:{x}:{y}:{timestamp or 'latest'}:{data_type}:{vehicle_type}"
        cached = redis_manager.get(cache_key)

        if cached:
            logger.debug(f"Cache hit for heatmap tile {z}/{x}/{y}")
            import base64
            return base64.b64decode(cached)

        from app.utils.geo_utils import tile_bbox
        bbox = tile_bbox(z, x, y)

        if timestamp is None:
            timestamp = datetime.utcnow()

        time_window = self._get_time_window_by_zoom(z)
        start_time = timestamp - time_window
        end_time = timestamp

        data_points = self.get_traffic_data_points(
            db, start_time, end_time, data_type, vehicle_type, bbox
        )

        tile_bytes = heatmap_generator.generate_heatmap_tile(
            z, x, y, data_points, value_field="value"
        )

        import base64
        redis_manager.set(cache_key, base64.b64encode(tile_bytes).decode(), expire=self.cache_ttl)

        return tile_bytes

    def generate_timeline_heatmap(self, db: Session, start_time: datetime,
                                  end_time: datetime, interval: str = "1h",
                                  data_type: str = "vehicle",
                                  vehicle_type: str = "all",
                                  bbox: List[float] = None) -> List[Dict]:
        time_delta = self._parse_interval(interval)
        current_time = start_time

        timeline = []

        while current_time <= end_time:
            window_start = current_time - time_delta / 2
            window_end = current_time + time_delta / 2

            points = self.get_traffic_data_points(
                db, window_start, window_end, data_type, vehicle_type, bbox
            )

            timeline.append({
                "timestamp": current_time.isoformat(),
                "points": points,
                "count": len(points),
            })

            current_time += time_delta

        return timeline

    def get_dynamic_heatmap(self, db: Session, time_range: str = "1h",
                            data_type: str = "vehicle",
                            vehicle_type: str = "all",
                            bbox: List[float] = None) -> Dict:
        end_time = datetime.utcnow()
        start_time = end_time - self._parse_interval(time_range)

        points = self.get_traffic_data_points(
            db, start_time, end_time, data_type, vehicle_type, bbox
        )

        geojson = heatmap_generator.generate_heatmap_geojson(points, value_field="value")

        return {
            "start_time": start_time.isoformat(),
            "end_time": end_time.isoformat(),
            "data_type": data_type,
            "vehicle_type": vehicle_type,
            "point_count": len(points),
            "geojson": geojson,
        }

    def _get_time_window_by_zoom(self, z: int) -> timedelta:
        if z >= 16:
            return timedelta(minutes=5)
        elif z >= 14:
            return timedelta(minutes=15)
        elif z >= 12:
            return timedelta(hours=1)
        elif z >= 10:
            return timedelta(hours=4)
        else:
            return timedelta(days=1)

    def _parse_interval(self, interval: str) -> timedelta:
        if interval.endswith("m"):
            minutes = int(interval[:-1])
            return timedelta(minutes=minutes)
        elif interval.endswith("h"):
            hours = int(interval[:-1])
            return timedelta(hours=hours)
        elif interval.endswith("d"):
            days = int(interval[:-1])
            return timedelta(days=days)
        else:
            return timedelta(hours=1)

    def get_heatmap_statistics(self, db: Session, start_time: datetime,
                               end_time: datetime, data_type: str = "vehicle",
                               vehicle_type: str = "all") -> Dict:
        points = self.get_traffic_data_points(
            db, start_time, end_time, data_type, vehicle_type
        )

        if not points:
            return {
                "min": 0,
                "max": 0,
                "avg": 0,
                "count": 0,
            }

        values = [p["value"] for p in points if p["value"] is not None]

        return {
            "min": min(values) if values else 0,
            "max": max(values) if values else 0,
            "avg": sum(values) / len(values) if values else 0,
            "count": len(values),
        }


heatmap_service = HeatmapService()
