import math
from typing import Tuple, List
from shapely.geometry import Point, LineString, Polygon
import numpy as np


def tile_to_latlon(z: int, x: int, y: int) -> Tuple[float, float]:
    n = 2.0 ** z
    lon_deg = x / n * 360.0 - 180.0
    lat_rad = math.atan(math.sinh(math.pi * (1 - 2 * y / n)))
    lat_deg = math.degrees(lat_rad)
    return lat_deg, lon_deg


def latlon_to_tile(lat: float, lon: float, z: int) -> Tuple[int, int]:
    n = 2.0 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return x, y


def tile_bbox(z: int, x: int, y: int) -> Tuple[float, float, float, float]:
    lat1, lon1 = tile_to_latlon(z, x, y + 1)
    lat2, lon2 = tile_to_latlon(z, x + 1, y)
    return lon1, lat1, lon2, lat2


def bbox_to_tiles(min_lon: float, min_lat: float, max_lon: float,
                  max_lat: float, z: int) -> List[Tuple[int, int]]:
    x1, y1 = latlon_to_tile(max_lat, min_lon, z)
    x2, y2 = latlon_to_tile(min_lat, max_lon, z)

    tiles = []
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            tiles.append((x, y))
    return tiles


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371000

    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)

    a = math.sin(d_phi / 2) ** 2 + \
        math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    return R * c


def point_to_line_distance(point: Point, line: LineString) -> float:
    return point.distance(line)


def match_point_to_road(point: Point, roads: List[dict],
                        max_distance: float = 50.0) -> dict:
    best_match = None
    best_distance = float('inf')

    for road in roads:
        road_geom = road.get('geom')
        if road_geom is None:
            continue

        distance = point_to_line_distance(point, road_geom) * 111000

        if distance < best_distance and distance <= max_distance:
            best_distance = distance
            best_match = road

    if best_match:
        best_match['match_distance'] = best_distance
    return best_match


def generate_grid_points(bbox: Tuple[float, float, float, float],
                         resolution: float = 0.001) -> np.ndarray:
    min_lon, min_lat, max_lon, max_lat = bbox
    lons = np.arange(min_lon, max_lon, resolution)
    lats = np.arange(min_lat, max_lat, resolution)
    lon_grid, lat_grid = np.meshgrid(lons, lats)
    return np.column_stack([lon_grid.ravel(), lat_grid.ravel()])


def gaussian_kernel(distance: float, sigma: float) -> float:
    return np.exp(-0.5 * (distance / sigma) ** 2) / (sigma * np.sqrt(2 * np.pi))
