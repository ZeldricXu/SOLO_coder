import numpy as np
from typing import List, Tuple, Dict, Optional
from datetime import datetime
from PIL import Image, ImageDraw
import io
import base64
import json
import logging

from app.utils.geo_utils import tile_bbox, generate_grid_points, gaussian_kernel

logger = logging.getLogger(__name__)

COLOR_RAMP = [
    (0, 0, 255, 0),
    (0, 100, 255, 80),
    (0, 200, 255, 120),
    (0, 255, 150, 160),
    (0, 255, 0, 200),
    (150, 255, 0, 200),
    (255, 255, 0, 220),
    (255, 150, 0, 230),
    (255, 50, 0, 240),
    (255, 0, 0, 255),
]


def interpolate_color(value: float, color_ramp: List[Tuple] = None) -> Tuple[int, int, int, int]:
    if color_ramp is None:
        color_ramp = COLOR_RAMP

    value = max(0.0, min(1.0, value))
    idx = value * (len(color_ramp) - 1)
    low_idx = int(np.floor(idx))
    high_idx = min(low_idx + 1, len(color_ramp) - 1)
    frac = idx - low_idx

    low_color = color_ramp[low_idx]
    high_color = color_ramp[high_idx]

    return tuple(
        int(low_color[i] + frac * (high_color[i] - low_color[i]))
        for i in range(4)
    )


class HeatmapGenerator:
    def __init__(self, tile_size: int = 256, radius: float = 0.005):
        self.tile_size = tile_size
        self.radius = radius

    def generate_heatmap_tile(self, z: int, x: int, y: int,
                              data_points: List[Dict],
                              value_field: str = "value",
                              max_value: float = None) -> bytes:
        bbox = tile_bbox(z, x, y)
        min_lon, min_lat, max_lon, max_lat = bbox

        img = Image.new("RGBA", (self.tile_size, self.tile_size), (0, 0, 0, 0))
        pixels = np.zeros((self.tile_size, self.tile_size), dtype=np.float32)

        lon_range = max_lon - min_lon
        lat_range = max_lat - min_lat

        if lon_range == 0 or lat_range == 0:
            return self._image_to_bytes(img)

        pixel_radius = max(1, int(self.radius / lon_range * self.tile_size))

        for point in data_points:
            lon = point.get("lon") or point.get("longitude")
            lat = point.get("lat") or point.get("latitude")
            value = point.get(value_field, 1.0)

            if lon is None or lat is None:
                continue

            if not (min_lon <= lon <= max_lon and min_lat <= lat <= max_lat):
                continue

            px = int((lon - min_lon) / lon_range * self.tile_size)
            py = int((1 - (lat - min_lat) / lat_range) * self.tile_size)

            self._draw_gaussian(pixels, px, py, pixel_radius, value)

        if max_value is None and len(data_points) > 0:
            max_value = np.max(pixels) if np.max(pixels) > 0 else 1.0

        if max_value > 0:
            normalized = pixels / max_value
        else:
            normalized = pixels

        img_array = np.zeros((self.tile_size, self.tile_size, 4), dtype=np.uint8)
        for i in range(self.tile_size):
            for j in range(self.tile_size):
                if normalized[i, j] > 0:
                    img_array[i, j] = interpolate_color(normalized[i, j])

        img = Image.fromarray(img_array, "RGBA")
        return self._image_to_bytes(img)

    def _draw_gaussian(self, pixels: np.ndarray, cx: int, cy: int,
                       radius: int, value: float):
        size = radius * 2 + 1
        kernel = np.fromfunction(
            lambda x, y: np.exp(-((x - radius) ** 2 + (y - radius) ** 2) / (2 * (radius / 3) ** 2)),
            (size, size)
        )
        kernel = kernel * value

        x_start = max(0, cx - radius)
        x_end = min(pixels.shape[1], cx + radius + 1)
        y_start = max(0, cy - radius)
        y_end = min(pixels.shape[0], cy + radius + 1)

        kx_start = max(0, radius - cx)
        kx_end = kx_start + (x_end - x_start)
        ky_start = max(0, radius - cy)
        ky_end = ky_start + (y_end - y_start)

        if y_end > y_start and x_end > x_start:
            pixels[y_start:y_end, x_start:x_end] += kernel[ky_start:ky_end, kx_start:kx_end]

    def generate_heatmap_geojson(self, data_points: List[Dict],
                                 value_field: str = "value") -> Dict:
        features = []
        for point in data_points:
            lon = point.get("lon") or point.get("longitude")
            lat = point.get("lat") or point.get("latitude")
            value = point.get(value_field, 0)

            if lon is None or lat is None:
                continue

            feature = {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [lon, lat]
                },
                "properties": {
                    value_field: value,
                    "color": interpolate_color(min(1.0, value / 100) if value else 0)
                }
            }
            features.append(feature)

        return {
            "type": "FeatureCollection",
            "features": features
        }

    def generate_3d_heatmap(self, data_points: List[Dict],
                            terrain_heights: np.ndarray,
                            bbox: Tuple[float, float, float, float],
                            resolution: float = 0.001,
                            height_scale: float = 100.0) -> Dict:
        grid_points = generate_grid_points(bbox, resolution)
        values = np.zeros(len(grid_points))

        for point in data_points:
            lon = point.get("lon") or point.get("longitude")
            lat = point.get("lat") or point.get("latitude")
            value = point.get("value", 1.0)

            if lon is None or lat is None:
                continue

            distances = np.sqrt(
                (grid_points[:, 0] - lon) ** 2 +
                (grid_points[:, 1] - lat) ** 2
            )

            sigma = self.radius
            weights = gaussian_kernel(distances * 111000, sigma * 111000)
            values += value * weights

        heights = terrain_heights + values * height_scale if terrain_heights is not None else values * height_scale

        return {
            "grid_points": grid_points.tolist(),
            "values": values.tolist(),
            "heights": heights.tolist(),
            "bbox": bbox,
            "resolution": resolution,
        }

    def _image_to_bytes(self, img: Image.Image) -> bytes:
        buffer = io.BytesIO()
        img.save(buffer, format="PNG")
        return buffer.getvalue()

    def generate_heatmap_data_uri(self, image_bytes: bytes) -> str:
        base64_data = base64.b64encode(image_bytes).decode("utf-8")
        return f"data:image/png;base64,{base64_data}"


heatmap_generator = HeatmapGenerator()
