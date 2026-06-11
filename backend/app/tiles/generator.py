import os
import json
import logging
from typing import List, Dict, Optional, Tuple
from datetime import datetime
from pathlib import Path
import math

from sqlalchemy.orm import Session
from geoalchemy2.shape import to_shape
from shapely.geometry import Polygon, Point

from app.config import settings
from app.models import Building, RoadNetwork, POI
from app.utils.geo_utils import tile_bbox, latlon_to_tile, tile_to_latlon

logger = logging.getLogger(__name__)


class TileGenerator:
    def __init__(self):
        self.cache_dir = Path(settings.TILE_CACHE_DIR)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.max_zoom = 20
        self.min_zoom = 10

    def generate_building_tile(self, db: Session, z: int, x: int, y: int) -> Optional[Dict]:
        cache_path = self._get_cache_path("buildings", z, x, y)
        if cache_path.exists():
            return self._read_tile_file(cache_path)

        bbox = tile_bbox(z, x, y)
        min_lon, min_lat, max_lon, max_lat = bbox

        buildings = db.query(Building).filter(
            func.ST_Intersects(
                Building.geom,
                func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
            )
        ).all()

        features = []
        for b in buildings:
            try:
                geom = to_shape(b.geom)
                coords = self._geometry_to_coords(geom)

                properties = {
                    "id": b.id,
                    "name": b.name or "",
                    "height": b.height or 10,
                    "floors": b.floors or 3,
                    "building_type": b.building_type or "",
                }

                feature = {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": coords
                    },
                    "properties": properties
                }
                features.append(feature)
            except Exception as e:
                logger.warning(f"Failed to process building {b.id}: {e}")
                continue

        tile_data = {
            "type": "FeatureCollection",
            "features": features,
            "tile": {"z": z, "x": x, "y": y},
            "bbox": bbox,
        }

        self._save_tile_file(cache_path, tile_data)
        return tile_data

    def generate_road_tile(self, db: Session, z: int, x: int, y: int) -> Optional[Dict]:
        cache_path = self._get_cache_path("roads", z, x, y)
        if cache_path.exists():
            return self._read_tile_file(cache_path)

        bbox = tile_bbox(z, x, y)
        min_lon, min_lat, max_lon, max_lat = bbox

        roads = db.query(RoadNetwork).filter(
            func.ST_Intersects(
                RoadNetwork.geom,
                func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
            )
        ).all()

        features = []
        for road in roads:
            try:
                geom = to_shape(road.geom)
                coords = self._line_to_coords(geom)

                properties = {
                    "id": road.id,
                    "name": road.name or "",
                    "road_type": road.road_type or "",
                    "lanes": road.lanes or 2,
                    "speed_limit": road.speed_limit or 60,
                }

                feature = {
                    "type": "Feature",
                    "geometry": {
                        "type": "LineString",
                        "coordinates": coords
                    },
                    "properties": properties
                }
                features.append(feature)
            except Exception as e:
                logger.warning(f"Failed to process road {road.id}: {e}")
                continue

        tile_data = {
            "type": "FeatureCollection",
            "features": features,
            "tile": {"z": z, "x": x, "y": y},
        }

        self._save_tile_file(cache_path, tile_data)
        return tile_data

    def generate_poi_tile(self, db: Session, z: int, x: int, y: int) -> Optional[Dict]:
        cache_path = self._get_cache_path("pois", z, x, y)
        if cache_path.exists():
            return self._read_tile_file(cache_path)

        bbox = tile_bbox(z, x, y)
        min_lon, min_lat, max_lon, max_lat = bbox

        pois = db.query(POI).filter(
            func.ST_Intersects(
                POI.geom,
                func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
            )
        ).limit(500).all()

        features = []
        for poi in pois:
            try:
                geom = to_shape(poi.geom)
                properties = {
                    "id": poi.id,
                    "name": poi.name,
                    "category": poi.category or "",
                    "address": poi.address or "",
                    "properties": poi.properties or {},
                }

                feature = {
                    "type": "Feature",
                    "geometry": {
                        "type": "Point",
                        "coordinates": [geom.x, geom.y]
                    },
                    "properties": properties
                }
                features.append(feature)
            except Exception as e:
                logger.warning(f"Failed to process POI {poi.id}: {e}")
                continue

        tile_data = {
            "type": "FeatureCollection",
            "features": features,
            "tile": {"z": z, "x": x, "y": y},
        }

        self._save_tile_file(cache_path, tile_data)
        return tile_data

    def generate_3dtileset(self, layer_type: str, min_zoom: int = 10,
                           max_zoom: int = 18) -> Dict:
        tileset = {
            "asset": {
                "version": "1.0",
                "tilesetVersion": "1.0.0"
            },
            "geometricError": 500,
            "root": {
                "transform": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0,
                              settings.MAP_CENTER_LON, settings.MAP_CENTER_LAT, 0, 1],
                "geometricError": 500,
                "refine": "add",
                "content": {
                    "uri": f"/api/v1/tiles/{layer_type}/0/0/0.geojson"
                },
                "children": self._generate_tile_children(layer_type, 0, 0, 0, min_zoom, max_zoom)
            }
        }
        return tileset

    def _generate_tile_children(self, layer_type: str, z: int, x: int, y: int,
                                min_zoom: int, max_zoom: int) -> List[Dict]:
        if z >= max_zoom:
            return []

        children = []
        for dx in [0, 1]:
            for dy in [0, 1]:
                child_z = z + 1
                child_x = x * 2 + dx
                child_y = y * 2 + dy

                child = {
                    "geometricError": 500 / (2 ** child_z),
                    "refine": "add",
                    "content": {
                        "uri": f"/api/v1/tiles/{layer_type}/{child_z}/{child_x}/{child_y}.geojson"
                    },
                    "children": [] if child_z >= max_zoom else self._generate_tile_children(
                        layer_type, child_z, child_x, child_y, min_zoom, max_zoom
                    )
                }
                children.append(child)

        return children

    def _geometry_to_coords(self, geom) -> List:
        if geom.geom_type == 'Polygon':
            exterior_coords = list(geom.exterior.coords)
            return [exterior_coords]
        elif geom.geom_type == 'MultiPolygon':
            coords = []
            for poly in geom.geoms:
                coords.append(list(poly.exterior.coords))
            return coords
        return []

    def _line_to_coords(self, geom) -> List:
        if geom.geom_type == 'LineString':
            return list(geom.coords)
        elif geom.geom_type == 'MultiLineString':
            coords = []
            for line in geom.geoms:
                coords.extend(list(line.coords))
            return coords
        return []

    def _get_cache_path(self, layer_type: str, z: int, x: int, y: int) -> Path:
        return self.cache_dir / layer_type / str(z) / str(x) / f"{y}.geojson"

    def _save_tile_file(self, path: Path, data: Dict):
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)

    def _read_tile_file(self, path: Path) -> Dict:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)

    def clear_cache(self, layer_type: str = None):
        if layer_type:
            layer_dir = self.cache_dir / layer_type
            if layer_dir.exists():
                import shutil
                shutil.rmtree(layer_dir)
        else:
            for child in self.cache_dir.iterdir():
                if child.is_dir():
                    import shutil
                    shutil.rmtree(child)

    def get_cache_stats(self) -> Dict:
        stats = {
            "total_size_bytes": 0,
            "total_tiles": 0,
            "layers": {}
        }

        if self.cache_dir.exists():
            for layer_dir in self.cache_dir.iterdir():
                if layer_dir.is_dir():
                    layer_stats = {"tiles": 0, "size_bytes": 0}
                    for f in layer_dir.rglob('*.geojson'):
                        layer_stats["tiles"] += 1
                        layer_stats["size_bytes"] += f.stat().st_size
                    stats["layers"][layer_dir.name] = layer_stats
                    stats["total_tiles"] += layer_stats["tiles"]
                    stats["total_size_bytes"] += layer_stats["size_bytes"]

        return stats


from sqlalchemy import func

tile_generator = TileGenerator()
