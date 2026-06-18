import logging
from typing import List, Dict, Optional, Tuple
from datetime import datetime, timedelta
from collections import deque, defaultdict
from math import exp

from sqlalchemy.orm import Session
from sqlalchemy import func
from geoalchemy2.shape import to_shape
from shapely.geometry import Point, LineString

from app.models import RoadNetwork, TrafficFlowRecord, TrafficSensor
from app.utils.geo_utils import haversine_distance

logger = logging.getLogger(__name__)


class RoadGraph:
    """基于RoadNetwork构建的路网图，用于上下游BFS遍历"""

    def __init__(self):
        self.nodes = {}
        self.edges = defaultdict(list)
        self.reverse_edges = defaultdict(list)
        self.road_id_to_node_ids = defaultdict(list)
        self.road_cache = {}

    @classmethod
    def build_from_db(cls, db: Session, bbox: Tuple = None) -> "RoadGraph":
        graph = cls()
        query = db.query(RoadNetwork)
        if bbox and len(bbox) == 4:
            min_lon, min_lat, max_lon, max_lat = bbox
            query = query.filter(
                func.ST_Intersects(
                    RoadNetwork.geom,
                    func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
                )
            )
        roads = query.limit(50000).all()
        for road in roads:
            graph._add_road(road)
        return graph

    def _add_road(self, road):
        try:
            if road.geom is None:
                return
            geom = to_shape(road.geom)
        except Exception:
            try:
                from shapely import wkb
                geom = wkb.loads(bytes(road.geom.data))
            except Exception:
                return
        if geom is None or geom.is_empty:
            return
        coords = list(geom.coords)
        if len(coords) < 2:
            return
        start_node = self._node_key(coords[0][0], coords[0][1])
        end_node = self._node_key(coords[-1][0], coords[-1][1])
        length = self._line_length_meters(coords)
        self.nodes[start_node] = {"lon": coords[0][0], "lat": coords[0][1]}
        self.nodes[end_node] = {"lon": coords[-1][0], "lat": coords[-1][1]}
        edge_info = {
            "road_id": road.id,
            "road_name": road.name,
            "road_type": road.road_type,
            "lanes": road.lanes,
            "speed_limit": road.speed_limit,
            "length_meters": length,
            "start_node": start_node,
            "end_node": end_node,
            "geom": coords,
        }
        self.edges[start_node].append(edge_info)
        self.reverse_edges[end_node].append(edge_info)
        self.road_id_to_node_ids[road.id].append((start_node, end_node))
        self.road_cache[road.id] = road

    @staticmethod
    def _node_key(lon: float, lat: float, precision: int = 6) -> str:
        return f"{lon:.{precision}f}_{lat:.{precision}f}"

    @staticmethod
    def _line_length_meters(coords: List[Tuple[float, float]]) -> float:
        total = 0.0
        for i in range(len(coords) - 1):
            lon1, lat1 = coords[i]
            lon2, lat2 = coords[i + 1]
            total += haversine_distance(lat1, lon1, lat2, lon2)
        return max(total, 1.0)

    def find_nearest_edges(self, point: Point, max_distance_meters: float = 50.0) -> List[Dict]:
        best = []
        for road_id, road in self.road_cache.items():
            try:
                geom = to_shape(road.geom) if hasattr(road.geom, 'data') else road.geom
                if not isinstance(geom, LineString):
                    continue
            except Exception:
                continue
            dist = point.distance(geom)
            dist_meters = dist * 111000.0
            if dist_meters <= max_distance_meters:
                for (sn, en) in self.road_id_to_node_ids.get(road_id, []):
                    best.append({
                        "road_id": road_id,
                        "start_node": sn,
                        "end_node": en,
                        "distance_meters": dist_meters,
                    })
        best.sort(key=lambda x: x["distance_meters"])
        return best[:5]

    def bfs_downstream(self, start_nodes: List[str], max_depth: int = 5,
                       max_edges: int = 50) -> List[Dict]:
        visited = set()
        queue = deque()
        results = []
        for sn in start_nodes:
            queue.append((sn, 0, 0.0, None))
            visited.add(sn)
        while queue and len(results) < max_edges:
            node, depth, cum_dist, from_edge = queue.popleft()
            if depth > max_depth:
                continue
            for edge in self.edges.get(node, []):
                key = (edge["start_node"], edge["end_node"])
                if key in visited:
                    continue
                visited.add(key)
                results.append({
                    "road_id": edge["road_id"],
                    "road_name": edge["road_name"],
                    "road_type": edge["road_type"],
                    "length_meters": edge["length_meters"],
                    "start_node": edge["start_node"],
                    "end_node": edge["end_node"],
                    "depth": depth,
                    "distance_from_start_meters": cum_dist + edge["length_meters"],
                    "geom": edge["geom"],
                })
                queue.append((
                    edge["end_node"],
                    depth + 1,
                    cum_dist + edge["length_meters"],
                    edge,
                ))
        return results

    def bfs_upstream(self, end_nodes: List[str], max_depth: int = 5,
                      max_edges: int = 50) -> List[Dict]:
        visited = set()
        queue = deque()
        results = []
        for en in end_nodes:
            queue.append((en, 0, 0.0, None))
            visited.add(en)
        while queue and len(results) < max_edges:
            node, depth, cum_dist, from_edge = queue.popleft()
            if depth > max_depth:
                continue
            for edge in self.reverse_edges.get(node, []):
                key = (edge["start_node"], edge["end_node"])
                if key in visited:
                    continue
                visited.add(key)
                results.append({
                    "road_id": edge["road_id"],
                    "road_name": edge["road_name"],
                    "road_type": edge["road_type"],
                    "length_meters": edge["length_meters"],
                    "start_node": edge["start_node"],
                    "end_node": edge["end_node"],
                    "depth": depth,
                    "distance_from_start_meters": cum_dist + edge["length_meters"],
                    "geom": edge["geom"],
                })
                queue.append((
                    edge["start_node"],
                    depth + 1,
                    cum_dist + edge["length_meters"],
                    edge,
                ))
        return results


class CongestionPropagationService:
    """拥堵传播分析服务

    功能：
    - 选定拥堵点，BFS追溯上游来源 & 下游影响范围
    - 结合流量数据计算传播强度、到达时间
    - 输出GeoJSON FeatureCollection + 粒子流线数据供前端动画渲染
    """

    def __init__(self):
        pass

    def analyze_propagation(self, db: Session, lon: float, lat: float,
                             start_time: datetime, end_time: datetime,
                             max_depth: int = 5) -> Dict:
        center = Point(lon, lat)

        bbox = (
            lon - 0.05, lat - 0.05, lon + 0.05, lat + 0.05,
        )
        graph = RoadGraph.build_from_db(db, bbox=bbox)

        nearest = graph.find_nearest_edges(center, max_distance_meters=100.0)
        if not nearest:
            return {
                "center": {"lon": lon, "lat": lat},
                "upstream": [],
                "downstream": [],
                "error": "No roads found near the given point",
            }

        start_nodes_down = []
        end_nodes_up = []
        for n in nearest:
            if n["end_node"]:
                start_nodes_down.append(n["end_node"])
            if n["start_node"]:
                end_nodes_up.append(n["start_node"])

        start_nodes_down = list(dict.fromkeys(start_nodes_down))
        end_nodes_up = list(dict.fromkeys(end_nodes_up))

        upstream_edges = graph.bfs_upstream(end_nodes_up, max_depth=max_depth)
        downstream_edges = graph.bfs_downstream(start_nodes_down, max_depth=max_depth)

        center_sensor = self._find_nearest_sensor(db, lon, lat)
        congestion_index_center = self._get_congestion_at_sensor(
            db, center_sensor, start_time, end_time
        ) if center_sensor else 0.5

        upstream_annotated = self._annotate_with_flow(
            db, upstream_edges, center_sensor,
            start_time, end_time, congestion_index_center, direction="upstream"
        )
        downstream_annotated = self._annotate_with_flow(
            db, downstream_edges, center_sensor,
            start_time, end_time, congestion_index_center, direction="downstream"
        )

        streamlines = self._build_streamlines(
            upstream_annotated, downstream_annotated, center
        )

        geojson_up = self._edges_to_geojson(upstream_annotated, "upstream")
        geojson_down = self._edges_to_geojson(downstream_annotated, "downstream")

        return {
            "center": {"lon": lon, "lat": lat},
            "time_range": {"start": start_time.isoformat(), "end": end_time.isoformat()},
            "congestion_index_at_center": congestion_index_center,
            "upstream": {
                "road_count": len(upstream_annotated),
                "total_distance_meters": sum(e["length_meters"] for e in upstream_annotated),
                "max_propagation_time_minutes": max(
                    (e["arrival_time_minutes"] for e in upstream_annotated if e.get("arrival_time_minutes") is not None),
                    default=0,
                ),
                "geojson": geojson_up,
            },
            "downstream": {
                "road_count": len(downstream_annotated),
                "total_distance_meters": sum(e["length_meters"] for e in downstream_annotated),
                "max_propagation_time_minutes": max(
                    (e["arrival_time_minutes"] for e in downstream_annotated if e.get("arrival_time_minutes") is not None),
                    default=0,
                ),
                "geojson": geojson_down,
            },
            "streamlines": streamlines,
        }

    def _find_nearest_sensor(self, db: Session, lon: float, lat: float) -> Optional[str]:
        try:
            result = db.query(
                TrafficSensor.sensor_id,
                func.ST_Distance(
                    TrafficSensor.location,
                    func.ST_SetSRID(func.ST_MakePoint(lon, lat), 4326),
                ).label("dist"),
            ).order_by("dist").limit(1).first()
            return result.sensor_id if result else None
        except Exception:
            return None

    def _get_congestion_at_sensor(self, db: Session, sensor_id: str,
                                   start_time: datetime, end_time: datetime) -> float:
        try:
            avg = db.query(
                func.avg(TrafficFlowRecord.congestion_index)
            ).filter(
                TrafficFlowRecord.sensor_id == sensor_id,
                TrafficFlowRecord.timestamp >= start_time,
                TrafficFlowRecord.timestamp <= end_time,
            ).scalar()
            return float(avg or 0.0)
        except Exception:
            return 0.0

    def _annotate_with_flow(self, db: Session, edges: List[Dict],
                            center_sensor_id: str, start_time: datetime,
                            end_time: datetime, center_congestion: float,
                            direction: str) -> List[Dict]:
        annotated = []
        for e in edges:
            avg_speed = 40.0
            vehicle_count = 50
            congestion = 0.3
            try:
                sensor = db.query(TrafficSensor).filter(
                    TrafficSensor.road_id == e["road_id"]
                ).first()
                if sensor:
                    stats = db.query(
                        func.avg(TrafficFlowRecord.avg_speed),
                        func.sum(TrafficFlowRecord.vehicle_count),
                        func.avg(TrafficFlowRecord.congestion_index),
                    ).filter(
                        TrafficFlowRecord.sensor_id == sensor.sensor_id,
                        TrafficFlowRecord.timestamp >= start_time,
                        TrafficFlowRecord.timestamp <= end_time,
                    ).first()
                    if stats:
                        if stats[0] is not None:
                            avg_speed = float(stats[0])
                        if stats[1] is not None:
                            vehicle_count = int(stats[1])
                        if stats[2] is not None:
                            congestion = float(stats[2])
            except Exception:
                pass

            distance_m = e.get("distance_from_start_meters", e["length_meters"])
            effective_speed_kmh = max(avg_speed, 5.0)
            arrival_time_minutes = (distance_m / 1000.0) / effective_speed_kmh * 60.0

            depth = e.get("depth", 0)
            decay = exp(-0.3 * depth)
            propagation_strength = center_congestion * decay * (1.0 + congestion) / 2.0
            propagation_strength = max(0.0, min(1.0, propagation_strength))

            severity = self._severity_from_strength(propagation_strength)

            annotated_e = dict(e)
            annotated_e.update({
                "avg_speed_kmh": avg_speed,
                "vehicle_count": vehicle_count,
                "congestion_index": congestion,
                "arrival_time_minutes": round(arrival_time_minutes, 2),
                "propagation_strength": round(propagation_strength, 3),
                "severity": severity,
                "color": self._color_for_severity(severity),
            })
            annotated.append(annotated_e)
        return annotated

    @staticmethod
    def _severity_from_strength(strength: float) -> str:
        if strength >= 0.8:
            return "critical"
        elif strength >= 0.6:
            return "severe"
        elif strength >= 0.4:
            return "moderate"
        elif strength >= 0.2:
            return "light"
        else:
            return "smooth"

    @staticmethod
    def _color_for_severity(severity: str) -> str:
        palette = {
            "critical": "#ff0000",
            "severe": "#ff6600",
            "moderate": "#ffcc00",
            "light": "#66ff66",
            "smooth": "#00ccff",
        }
        return palette.get(severity, "#888888")

    def _edges_to_geojson(self, edges: List[Dict], direction: str) -> Dict:
        features = []
        for e in edges:
            if not e.get("geom") or len(e["geom"]) < 2:
                continue
            feature = {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[c[0], c[1]] for c in e["geom"]],
                },
                "properties": {
                    "road_id": e["road_id"],
                    "road_name": e.get("road_name"),
                    "road_type": e.get("road_type"),
                    "direction": direction,
                    "depth": e.get("depth", 0),
                    "length_meters": round(e["length_meters"], 2),
                    "distance_from_center_meters": round(e.get("distance_from_start_meters", 0), 2),
                    "propagation_strength": e.get("propagation_strength"),
                    "arrival_time_minutes": e.get("arrival_time_minutes"),
                    "avg_speed_kmh": e.get("avg_speed_kmh"),
                    "congestion_index": e.get("congestion_index"),
                    "severity": e.get("severity"),
                    "color": e.get("color"),
                    "width": self._width_from_strength(e.get("propagation_strength", 0)),
                },
            }
            features.append(feature)
        return {"type": "FeatureCollection", "features": features}

    @staticmethod
    def _width_from_strength(strength: float) -> int:
        return max(2, int(strength * 10))

    def _build_streamlines(self, upstream: List[Dict], downstream: List[Dict],
                           center: Point) -> Dict:
        def edges_to_streamlines(edges, direction_tag):
            lines = []
            for e in edges:
                if not e.get("geom") or len(e["geom"]) < 2:
                    continue
                coords = [[c[0], c[1]] for c in e["geom"]]
                lines.append({
                    "id": f"{direction_tag}_{e['road_id']}",
                    "direction": direction_tag,
                    "coordinates": coords,
                    "speed_ms": max(e.get("avg_speed_kmh", 30.0) / 3.6, 1.0),
                    "color": e.get("color", "#ff0000"),
                    "strength": e.get("propagation_strength", 0.5),
                    "arrival_time_minutes": e.get("arrival_time_minutes", 0),
                    "particle_count": max(3, int(e.get("propagation_strength", 0.3) * 10)),
                })
            return lines

        up_lines = edges_to_streamlines(upstream, "upstream")
        down_lines = edges_to_streamlines(downstream, "downstream")

        center_point = {
            "lon": center.x,
            "lat": center.y,
            "color": "#ff0000",
            "radius": 10,
        }

        return {
            "center": center_point,
            "upstream_streamlines": up_lines,
            "downstream_streamlines": down_lines,
            "total_streamlines": len(up_lines) + len(down_lines),
        }


congestion_propagation_service = CongestionPropagationService()
