import logging
from typing import List, Dict, Optional, Tuple
from datetime import datetime, timedelta
import numpy as np
from sqlalchemy.orm import Session
from sqlalchemy import func, and_
from geoalchemy2.shape import to_shape
from shapely.geometry import Point, LineString

from app.models import RoadNetwork, TrafficFlowRecord, ODRecord, TrafficZone, SignalTimingPlan
from app.utils.geo_utils import haversine_distance

logger = logging.getLogger(__name__)


class PathAnalysisService:
    def __init__(self):
        pass

    def analyze_od_flows(self, db: Session, origin_zone: int = None,
                         dest_zone: int = None, time_period: str = "morning_peak",
                         travel_mode: str = "all", limit: int = 1000) -> List[Dict]:
        query = db.query(ODRecord)

        if origin_zone:
            query = query.filter(ODRecord.origin_zone_id == origin_zone)
        if dest_zone:
            query = query.filter(ODRecord.dest_zone_id == dest_zone)
        if time_period and time_period != "all":
            query = query.filter(ODRecord.time_period == time_period)
        if travel_mode and travel_mode != "all":
            query = query.filter(ODRecord.travel_mode == travel_mode)

        records = query.order_by(ODRecord.trip_count.desc()).limit(limit).all()

        results = []
        for record in records:
            results.append({
                "id": record.id,
                "origin": {
                    "lng": record.origin_lng,
                    "lat": record.origin_lat,
                    "zone_id": record.origin_zone_id
                },
                "destination": {
                    "lng": record.dest_lng,
                    "lat": record.dest_lat,
                    "zone_id": record.dest_zone_id
                },
                "trip_count": record.trip_count,
                "travel_mode": record.travel_mode,
                "time_period": record.time_period,
                "avg_travel_time": record.avg_travel_time,
            })

        return results

    def get_od_flow_lines(self, db: Session, origin_zone: int = None,
                          dest_zone: int = None, time_period: str = "morning_peak",
                          min_trips: int = 10) -> Dict:
        flows = self.analyze_od_flows(
            db, origin_zone, dest_zone, time_period, limit=5000
        )

        flows = [f for f in flows if f["trip_count"] >= min_trips]

        features = []
        for flow in flows:
            origin = flow["origin"]
            dest = flow["destination"]

            mid_lng = (origin["lng"] + dest["lng"]) / 2
            mid_lat = (origin["lat"] + dest["lat"]) / 2 + 0.005

            feature = {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [
                        [origin["lng"], origin["lat"]],
                        [mid_lng, mid_lat],
                        [dest["lng"], dest["lat"]]
                    ]
                },
                "properties": {
                    "trip_count": flow["trip_count"],
                    "travel_mode": flow["travel_mode"],
                    "avg_travel_time": flow["avg_travel_time"],
                    "width": min(10, max(1, flow["trip_count"] / 100)),
                }
            }
            features.append(feature)

        return {
            "type": "FeatureCollection",
            "features": features,
            "total_flows": len(features),
            "time_period": time_period,
        }

    def find_congestion_nodes(self, db: Session, start_time: datetime,
                              end_time: datetime, threshold: float = 0.7,
                              top_n: int = 50) -> List[Dict]:
        results = db.query(
            TrafficFlowRecord.sensor_id,
            func.avg(TrafficFlowRecord.congestion_index).label('avg_congestion'),
            func.avg(TrafficFlowRecord.avg_speed).label('avg_speed'),
            func.sum(TrafficFlowRecord.vehicle_count).label('total_vehicles'),
        ).filter(
            TrafficFlowRecord.timestamp >= start_time,
            TrafficFlowRecord.timestamp <= end_time,
        ).group_by(
            TrafficFlowRecord.sensor_id
        ).having(
            func.avg(TrafficFlowRecord.congestion_index) >= threshold
        ).order_by(
            func.avg(TrafficFlowRecord.congestion_index).desc()
        ).limit(top_n).all()

        nodes = []
        for row in results:
            nodes.append({
                "sensor_id": row.sensor_id,
                "avg_congestion": float(row.avg_congestion),
                "avg_speed": float(row.avg_speed) if row.avg_speed else 0,
                "total_vehicles": int(row.total_vehicles),
                "severity": self._get_congestion_severity(float(row.avg_congestion)),
            })

        return nodes

    def trace_upstream_downstream(self, db: Session, sensor_id: str,
                                   start_time: datetime, end_time: datetime,
                                   max_depth: int = 3) -> Dict:
        from app.models import TrafficSensor
        from geoalchemy2.shape import to_shape

        sensor = db.query(TrafficSensor).filter(TrafficSensor.sensor_id == sensor_id).first()
        if not sensor:
            return {"error": "Sensor not found"}

        center_point = to_shape(sensor.location)

        upstream = self._find_related_sensors(db, center_point, start_time, end_time, "upstream", max_depth)
        downstream = self._find_related_sensors(db, center_point, start_time, end_time, "downstream", max_depth)

        return {
            "center_sensor": {
                "sensor_id": sensor_id,
                "lng": center_point.x,
                "lat": center_point.y,
            },
            "upstream": upstream,
            "downstream": downstream,
            "time_range": {
                "start": start_time.isoformat(),
                "end": end_time.isoformat(),
            }
        }

    def _find_related_sensors(self, db: Session, center_point: Point,
                               start_time: datetime, end_time: datetime,
                               direction: str, max_depth: int) -> List[Dict]:
        from app.models import TrafficSensor
        from geoalchemy2.shape import to_shape

        sensors = db.query(TrafficSensor).filter(
            func.ST_DWithin(
                TrafficSensor.location,
                func.ST_SetSRID(func.ST_MakePoint(center_point.x, center_point.y), 4326),
                0.05
            )
        ).all()

        related = []
        for sensor in sensors:
            if sensor.sensor_id == "" :
                continue

            point = to_shape(sensor.location)
            distance = haversine_distance(center_point.y, center_point.x, point.y, point.x)

            congestion = db.query(
                func.avg(TrafficFlowRecord.congestion_index)
            ).filter(
                TrafficFlowRecord.sensor_id == sensor.sensor_id,
                TrafficFlowRecord.timestamp >= start_time,
                TrafficFlowRecord.timestamp <= end_time,
            ).scalar()

            related.append({
                "sensor_id": sensor.sensor_id,
                "name": sensor.name,
                "lng": point.x,
                "lat": point.y,
                "distance": distance,
                "avg_congestion": float(congestion) if congestion else 0,
                "direction": direction,
                "depth": 1,
            })

        return sorted(related, key=lambda x: x["distance"])[:10]

    def _get_congestion_severity(self, congestion_index: float) -> str:
        if congestion_index >= 0.9:
            return "critical"
        elif congestion_index >= 0.7:
            return "severe"
        elif congestion_index >= 0.5:
            return "moderate"
        elif congestion_index >= 0.3:
            return "light"
        else:
            return "smooth"

    def simulate_signal_timing(self, db: Session, plan_id: int,
                                start_time: datetime, end_time: datetime) -> Dict:
        plan = db.query(SignalTimingPlan).filter(SignalTimingPlan.id == plan_id).first()
        if not plan:
            return {"error": "Signal timing plan not found"}

        base_flow = 1000
        base_delay = 30

        cycle_length = plan.cycle_length or 120
        phases = plan.phases or []

        total_green_time = sum(p.get("green_time", 0) for p in phases)
        green_ratio = total_green_time / cycle_length if cycle_length > 0 else 0.5

        simulated_throughput = base_flow * green_ratio
        simulated_delay = base_delay * (1 - green_ratio) * 2

        baseline_throughput = base_flow * 0.5
        baseline_delay = base_delay

        improvement = (simulated_throughput - baseline_throughput) / baseline_throughput * 100
        delay_change = (simulated_delay - baseline_delay) / baseline_delay * 100

        return {
            "plan_id": plan_id,
            "plan_name": plan.name,
            "cycle_length": cycle_length,
            "phases": phases,
            "simulation_period": {
                "start": start_time.isoformat(),
                "end": end_time.isoformat(),
            },
            "baseline": {
                "throughput_vehicles_per_hour": baseline_throughput,
                "avg_delay_seconds": baseline_delay,
            },
            "simulated": {
                "throughput_vehicles_per_hour": round(simulated_throughput, 2),
                "avg_delay_seconds": round(simulated_delay, 2),
            },
            "improvement": {
                "throughput_percent": round(improvement, 2),
                "delay_percent": round(delay_change, 2),
            },
            "status": "completed",
        }

    def compare_signal_plans(self, db: Session, plan_ids: List[int],
                              start_time: datetime, end_time: datetime) -> Dict:
        results = []
        for plan_id in plan_ids:
            result = self.simulate_signal_timing(db, plan_id, start_time, end_time)
            if "error" not in result:
                results.append(result)

        return {
            "comparison_count": len(results),
            "plans": results,
            "best_plan_id": min(results, key=lambda x: x["simulated"]["avg_delay_seconds"])["plan_id"] if results else None,
        }

    def get_zones(self, db: Session, zone_type: str = None) -> List[Dict]:
        query = db.query(TrafficZone)
        if zone_type:
            query = query.filter(TrafficZone.zone_type == zone_type)

        zones = query.all()
        results = []
        for zone in zones:
            results.append({
                "id": zone.id,
                "zone_code": zone.zone_code,
                "name": zone.name,
                "zone_type": zone.zone_type,
                "properties": zone.properties,
            })

        return results


path_analysis_service = PathAnalysisService()
