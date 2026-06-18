import logging
import base64
import json
import time
from typing import List, Dict, Optional, Tuple
from datetime import datetime, timedelta
from collections import OrderedDict
from threading import RLock

from sqlalchemy.orm import Session
from sqlalchemy import func, and_
from geoalchemy2.shape import to_shape
import numpy as np
from PIL import Image
import io

from app.models import TrafficFlowRecord, TrafficSensor, RoadNetwork
from app.heatmap.generator import heatmap_generator
from app.utils.redis_client import redis_manager
from app.config import settings

logger = logging.getLogger(__name__)


class LRUCache:
    def __init__(self, max_size: int = 10000, ttl_seconds: int = 7 * 24 * 3600):
        self._store = OrderedDict()
        self._expiry = {}
        self._max_size = max_size
        self._ttl = ttl_seconds
        self._lock = RLock()

    def get(self, key: str):
        with self._lock:
            self._evict_expired()
            if key in self._store:
                self._store.move_to_end(key)
                return self._store[key]
            return None

    def set(self, key: str, value, ttl_seconds: int = None):
        with self._lock:
            self._evict_expired()
            ttl = ttl_seconds if ttl_seconds is not None else self._ttl
            self._store[key] = value
            self._expiry[key] = time.time() + ttl
            self._store.move_to_end(key)
            while len(self._store) > self._max_size:
                k, _ = self._store.popitem(last=False)
                self._expiry.pop(k, None)

    def delete(self, key: str):
        with self._lock:
            self._store.pop(key, None)
            self._expiry.pop(key, None)

    def clear_before(self, cutoff: datetime):
        with self._lock:
            keys_to_delete = [k for k, v in self._expiry.items() if v < cutoff.timestamp()]
            for k in keys_to_delete:
                self._store.pop(k, None)
                self._expiry.pop(k, None)

    def _evict_expired(self):
        now = time.time()
        expired_keys = [k for k, v in self._expiry.items() if v < now]
        for k in expired_keys:
            self._store.pop(k, None)
            self._expiry.pop(k, None)

    def __len__(self):
        with self._lock:
            self._evict_expired()
            return len(self._store)


lru_tile_cache = LRUCache(max_size=50000, ttl_seconds=7 * 24 * 3600)


class TemporalHeatmapService:
    """时态热力图动画服务

    - 24小时每5分钟一帧，共 24 * 12 = 288 帧/天
    - 帧间alpha混合过渡
    - LRU缓存（保留最近7天）
    """

    FRAME_INTERVAL_MINUTES = 5
    FRAMES_PER_DAY = 24 * 60 // FRAME_INTERVAL_MINUTES
    DEFAULT_ZOOM_RANGE = (10, 17)
    CACHE_TTL_SECONDS = 7 * 24 * 3600

    def __init__(self):
        self.cache = lru_tile_cache

    def _datetime_to_frame_index(self, dt: datetime, day_start: datetime = None) -> int:
        if day_start is None:
            day_start = dt.replace(hour=0, minute=0, second=0, microsecond=0)
        delta = dt - day_start
        total_minutes = int(delta.total_seconds() // 60)
        idx = total_minutes // self.FRAME_INTERVAL_MINUTES
        return max(0, min(self.FRAMES_PER_DAY - 1, idx))

    def _frame_index_to_datetime(self, day: datetime, frame_idx: int) -> datetime:
        day_start = day.replace(hour=0, minute=0, second=0, microsecond=0)
        return day_start + timedelta(minutes=frame_idx * self.FRAME_INTERVAL_MINUTES)

    def get_frame_timestamps(self, date: datetime = None) -> List[str]:
        if date is None:
            date = datetime.utcnow()
        return [
            self._frame_index_to_datetime(date, i).isoformat()
            for i in range(self.FRAMES_PER_DAY)
        ]

    def _cache_key(self, z: int, x: int, y: int, frame_dt: datetime,
                   data_type: str, vehicle_type: str,
                   road_level: str, direction: str) -> str:
        return (
            f"temporal:tile:{z}:{x}:{y}:"
            f"{frame_dt.strftime('%Y%m%d%H%M')}:"
            f"{data_type}:{vehicle_type}:{road_level}:{direction}"
        )

    def get_frame_tile(self, db: Session, z: int, x: int, y: int,
                       frame_dt: datetime, data_type: str = "vehicle",
                       vehicle_type: str = "all",
                       road_level: str = "all",
                       direction: str = "both") -> bytes:
        key = self._cache_key(z, x, y, frame_dt, data_type, vehicle_type, road_level, direction)
        cached = self.cache.get(key)
        if cached is not None:
            return cached

        redis_val = redis_manager.get(key)
        if redis_val is not None:
            tile_bytes = base64.b64decode(redis_val)
            self.cache.set(key, tile_bytes)
            return tile_bytes

        from app.utils.geo_utils import tile_bbox
        bbox = tile_bbox(z, x, y)

        half_window = timedelta(minutes=self.FRAME_INTERVAL_MINUTES // 2 + 1)
        start_time = frame_dt - half_window
        end_time = frame_dt + half_window

        points = self._query_points_in_range(
            db, start_time, end_time, bbox,
            data_type=data_type,
            vehicle_type=vehicle_type,
            road_level=road_level,
            direction=direction,
        )

        tile_bytes = heatmap_generator.generate_heatmap_tile(
            z, x, y, points, value_field="value"
        )

        self.cache.set(key, tile_bytes, ttl_seconds=self.CACHE_TTL_SECONDS)
        try:
            redis_manager.setex(key, self.CACHE_TTL_SECONDS,
                                base64.b64encode(tile_bytes).decode())
        except Exception as e:
            logger.warning(f"Failed to cache tile in Redis: {e}")

        return tile_bytes

    def get_blended_tile(self, db: Session, z: int, x: int, y: int,
                         current_dt: datetime, data_type: str = "vehicle",
                         vehicle_type: str = "all",
                         road_level: str = "all",
                         direction: str = "both",
                         alpha: float = None) -> bytes:
        """相邻帧之间做alpha混合过渡，alpha∈[0,1]表示前帧→后帧过渡进度"""
        day_start = current_dt.replace(hour=0, minute=0, second=0, microsecond=0)
        total_minutes = int((current_dt - day_start).total_seconds() // 60)
        frame_interval = self.FRAME_INTERVAL_MINUTES
        frame_idx = total_minutes // frame_interval
        frac = (total_minutes % frame_interval) / frame_interval
        blend_alpha = alpha if alpha is not None else frac

        prev_dt = self._frame_index_to_datetime(day_start, min(frame_idx, self.FRAMES_PER_DAY - 1))
        next_idx = min(frame_idx + 1, self.FRAMES_PER_DAY - 1)
        next_dt = self._frame_index_to_datetime(day_start, next_idx)

        tile_a = self.get_frame_tile(
            db, z, x, y, prev_dt, data_type, vehicle_type, road_level, direction
        )
        if blend_alpha <= 0.01:
            return tile_a

        tile_b = self.get_frame_tile(
            db, z, x, y, next_dt, data_type, vehicle_type, road_level, direction
        )
        if blend_alpha >= 0.99:
            return tile_b

        return self._blend_png_bytes(tile_a, tile_b, blend_alpha)

    def _blend_png_bytes(self, tile_a: bytes, tile_b: bytes, alpha: float) -> bytes:
        try:
            img_a = Image.open(io.BytesIO(tile_a)).convert("RGBA")
            img_b = Image.open(io.BytesIO(tile_b)).convert("RGBA")
            if img_a.size != img_b.size:
                return tile_b
            a = np.array(img_a, dtype=np.float32)
            b = np.array(img_b, dtype=np.float32)
            blended = (1 - alpha) * a + alpha * b
            blended_img = Image.fromarray(blended.astype(np.uint8), "RGBA")
            buf = io.BytesIO()
            blended_img.save(buf, format="PNG")
            return buf.getvalue()
        except Exception as e:
            logger.warning(f"Blending failed: {e}")
            return tile_b

    def _query_points_in_range(self, db: Session, start: datetime, end: datetime,
                               bbox: Tuple[float, float, float, float],
                               data_type: str = "vehicle",
                               vehicle_type: str = "all",
                               road_level: str = "all",
                               direction: str = "both") -> List[Dict]:
        query = db.query(
            TrafficFlowRecord,
            TrafficSensor,
            RoadNetwork,
        ).join(
            TrafficSensor, TrafficFlowRecord.sensor_id == TrafficSensor.sensor_id
        ).outerjoin(
            RoadNetwork, RoadNetwork.id == TrafficSensor.road_id
        ).filter(
            TrafficFlowRecord.timestamp >= start,
            TrafficFlowRecord.timestamp <= end,
        )

        if vehicle_type and vehicle_type != "all":
            query = query.filter(TrafficFlowRecord.vehicle_type == vehicle_type)

        if direction and direction != "both":
            query = query.filter(TrafficFlowRecord.direction == direction)

        if road_level and road_level != "all":
            query = query.filter(RoadNetwork.road_type == road_level)

        min_lon, min_lat, max_lon, max_lat = bbox
        query = query.filter(
            func.ST_Intersects(
                TrafficSensor.location,
                func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
            )
        )

        records = query.limit(20000).all()

        agg = {}
        for flow_record, sensor, road in records:
            sid = sensor.sensor_id
            if sid not in agg:
                if sensor.location:
                    pt = to_shape(sensor.location)
                    lon, lat = pt.x, pt.y
                else:
                    lon = lat = None
                agg[sid] = {
                    "lon": lon,
                    "lat": lat,
                    "sensor_id": sid,
                    "vehicle_count": 0,
                    "pedestrian_count": 0,
                    "congestion_index": 0,
                    "n": 0,
                }
            if data_type == "vehicle":
                agg[sid]["vehicle_count"] += flow_record.vehicle_count or 0
            elif data_type == "pedestrian":
                agg[sid]["pedestrian_count"] += flow_record.pedestrian_count or 0
            elif data_type == "congestion":
                agg[sid]["congestion_index"] += flow_record.congestion_index or 0
            agg[sid]["n"] += 1

        points = []
        for sid, d in agg.items():
            if d["lon"] is None or d["lat"] is None:
                continue
            n = max(1, d["n"])
            if data_type == "vehicle":
                value = d["vehicle_count"] / n
            elif data_type == "pedestrian":
                value = d["pedestrian_count"] / n
            elif data_type == "congestion":
                value = d["congestion_index"] / n
            else:
                value = d["vehicle_count"] / n
            points.append({"sensor_id": sid, "lon": d["lon"], "lat": d["lat"], "value": value})
        return points

    def evict_old_frames(self, days_to_keep: int = 7):
        cutoff = datetime.utcnow() - timedelta(days=days_to_keep)
        self.cache.clear_before(cutoff)
        try:
            keys = redis_manager.keys("temporal:tile:*")
            pattern = cutoff.strftime("%Y%m%d")
            deleted = 0
            for k in keys:
                date_part = k.split(":")[3] if len(k.split(":")) >= 4 else ""
                if date_part and len(date_part) >= 8 and date_part[:8] < pattern[:8]:
                    redis_manager.delete(k)
                    deleted += 1
            logger.info(f"Evicted {deleted} old temporal tiles from Redis")
        except Exception as e:
            logger.warning(f"Redis eviction failed: {e}")

    def get_timeline_summary(self, db: Session, date: datetime = None,
                             data_type: str = "vehicle",
                             vehicle_type: str = "all") -> Dict:
        if date is None:
            date = datetime.utcnow()
        frames = []
        for i in range(self.FRAMES_PER_DAY):
            dt = self._frame_index_to_datetime(date, i)
            frames.append({
                "frame_index": i,
                "timestamp": dt.isoformat(),
                "available": True,
            })
        return {
            "date": date.date().isoformat(),
            "frame_interval_minutes": self.FRAME_INTERVAL_MINUTES,
            "frames_per_day": self.FRAMES_PER_DAY,
            "frames": frames,
        }


temporal_heatmap_service = TemporalHeatmapService()
