import logging
from typing import List, Dict, Optional, Tuple
from itertools import product
from datetime import datetime
from sqlalchemy.orm import Session
from sqlalchemy import func, distinct

from app.models import TrafficFlowRecord, RoadNetwork, TrafficSensor
from app.utils.geo_utils import tile_bbox

logger = logging.getLogger(__name__)


VEHICLE_TYPES = ["car", "truck", "bus", "taxi", "motorcycle"]
VEHICLE_TYPE_LABELS = {
    "car": "小客车",
    "truck": "货车",
    "bus": "公交",
    "taxi": "出租",
    "motorcycle": "摩托车",
}

ROAD_LEVELS = ["expressway", "main_road", "secondary", "branch"]
ROAD_LEVEL_LABELS = {
    "expressway": "快速路",
    "main_road": "主干道",
    "secondary": "次干道",
    "branch": "支路",
}

DIRECTIONS = ["up", "down"]
DIRECTION_LABELS = {
    "up": "上行",
    "down": "下行",
}

DATA_TYPES = ["vehicle", "pedestrian", "congestion", "speed"]
DATA_TYPE_LABELS = {
    "vehicle": "车流量",
    "pedestrian": "人流量",
    "congestion": "拥堵指数",
    "speed": "平均车速",
}


class HeatmapDimensionService:
    """热力图多维度服务

    支持维度组合：
    - vehicle_type: car / truck / bus / taxi / motorcycle / all
    - road_level: expressway / main_road / secondary / branch / all
    - direction: up / down / both
    - data_type: vehicle / pedestrian / congestion / speed
    """

    def get_all_dimensions(self) -> Dict:
        return {
            "data_types": [
                {"code": c, "label": DATA_TYPE_LABELS.get(c, c)}
                for c in DATA_TYPES
            ],
            "vehicle_types": [
                {"code": "all", "label": "全部"}
            ] + [
                {"code": c, "label": VEHICLE_TYPE_LABELS.get(c, c)}
                for c in VEHICLE_TYPES
            ],
            "road_levels": [
                {"code": "all", "label": "全部"}
            ] + [
                {"code": c, "label": ROAD_LEVEL_LABELS.get(c, c)}
                for c in ROAD_LEVELS
            ],
            "directions": [
                {"code": "both", "label": "双向"},
            ] + [
                {"code": c, "label": DIRECTION_LABELS.get(c, c)}
                for c in DIRECTIONS
            ],
        }

    def get_available_dimensions(self, db: Session, bbox: Tuple = None,
                                  start_time: datetime = None,
                                  end_time: datetime = None) -> Dict:
        available_vt = db.query(distinct(TrafficFlowRecord.vehicle_type)).filter(
            TrafficFlowRecord.vehicle_type.isnot(None)
        ).all()
        vehicle_types = [r[0] for r in available_vt if r[0] and r[0] != "all"]

        available_dirs = db.query(distinct(TrafficFlowRecord.direction)).filter(
            TrafficFlowRecord.direction.isnot(None)
        ).all()
        directions = [r[0] for r in available_dirs if r[0] and r[0] != "both"]

        available_rl = db.query(distinct(RoadNetwork.road_type)).filter(
            RoadNetwork.road_type.isnot(None)
        ).all()
        road_levels = [r[0] for r in available_rl if r[0] and r[0] != "all"]

        return {
            "vehicle_types": ["all"] + vehicle_types,
            "road_levels": ["all"] + road_levels,
            "directions": ["both"] + directions,
            "data_types": DATA_TYPES,
        }

    def get_all_combos(self, include_all: bool = True) -> List[Dict]:
        vt_options = ["all"] + VEHICLE_TYPES if include_all else VEHICLE_TYPES
        rl_options = ["all"] + ROAD_LEVELS if include_all else ROAD_LEVELS
        dir_options = ["both"] + DIRECTIONS if include_all else DIRECTIONS

        combos = []
        for dt, vt, rl, d in product(DATA_TYPES, vt_options, rl_options, dir_options):
            key = self.encode_dimension_key(dt, vt, rl, d)
            combos.append({
                "key": key,
                "data_type": dt,
                "vehicle_type": vt,
                "road_level": rl,
                "direction": d,
                "label": self.human_readable_label(dt, vt, rl, d),
            })
        return combos

    @staticmethod
    def encode_dimension_key(data_type: str, vehicle_type: str = "all",
                              road_level: str = "all", direction: str = "both") -> str:
        return f"{data_type}:vt={vehicle_type}:rl={road_level}:d={direction}"

    @staticmethod
    def parse_dimension_key(key: str) -> Dict:
        parts = key.split(":")
        result = {"data_type": parts[0] if parts else "vehicle"}
        for p in parts[1:]:
            if "=" in p:
                k, v = p.split("=", 1)
                if k == "vt":
                    result["vehicle_type"] = v
                elif k == "rl":
                    result["road_level"] = v
                elif k == "d":
                    result["direction"] = v
        result.setdefault("vehicle_type", "all")
        result.setdefault("road_level", "all")
        result.setdefault("direction", "both")
        return result

    def human_readable_label(self, data_type: str, vehicle_type: str = "all",
                              road_level: str = "all", direction: str = "both") -> str:
        parts = [DATA_TYPE_LABELS.get(data_type, data_type)]
        if vehicle_type != "all":
            parts.append(VEHICLE_TYPE_LABELS.get(vehicle_type, vehicle_type))
        if road_level != "all":
            parts.append(ROAD_LEVEL_LABELS.get(road_level, road_level))
        if direction != "both":
            parts.append(DIRECTION_LABELS.get(direction, direction))
        return " - ".join(parts)

    def list_popular_combos(self, db: Session, top_n: int = 20) -> List[Dict]:
        combos = self.get_all_combos(include_all=True)
        scored = []
        for c in combos:
            score = 0
            if c["vehicle_type"] == "all":
                score += 10
            if c["road_level"] == "all":
                score += 10
            if c["direction"] == "both":
                score += 10
            if c["data_type"] == "vehicle":
                score += 10
            scored.append((score, c))
        scored.sort(key=lambda x: -x[0])
        return [c for _, c in scored[:top_n]]

    def validate_dimensions(self, dimensions: Dict) -> Tuple[bool, str]:
        if "data_type" in dimensions and dimensions["data_type"] not in DATA_TYPES and dimensions["data_type"] != "all":
            return False, f"Invalid data_type: {dimensions['data_type']}"
        if "vehicle_type" in dimensions:
            allowed_vt = set(VEHICLE_TYPES) | {"all"}
            if dimensions["vehicle_type"] not in allowed_vt:
                return False, f"Invalid vehicle_type: {dimensions['vehicle_type']}"
        if "road_level" in dimensions:
            allowed_rl = set(ROAD_LEVELS) | {"all"}
            if dimensions["road_level"] not in allowed_rl:
                return False, f"Invalid road_level: {dimensions['road_level']}"
        if "direction" in dimensions:
            allowed_d = set(DIRECTIONS) | {"both"}
            if dimensions["direction"] not in allowed_d:
                return False, f"Invalid direction: {dimensions['direction']}"
        return True, "OK"


heatmap_dimension_service = HeatmapDimensionService()
