import logging
from typing import Dict, Optional, Tuple
from datetime import datetime
from PIL import Image
import io

from sqlalchemy.orm import Session
from app.heatmap import heatmap_service
from app.tiles import tile_generator
from app.utils.geo_utils import tile_bbox

logger = logging.getLogger(__name__)


class WMSservice:
    def __init__(self):
        self.supported_formats = ["image/png", "image/jpeg"]
        self.supported_srs = ["EPSG:4326", "EPSG:3857", "CRS:84"]
        self.layers = ["traffic_heatmap", "buildings", "roads", "pois"]

    def get_capabilities(self, base_url: str) -> str:
        xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms">
  <Service>
    <Name>WMS</Name>
    <Title>城市交通流量三维可视化平台 WMS 服务</title>
    <Abstract>提供交通热力图、建筑、道路、POI等空间数据的WMS服务</Abstract>
  </Service>
  <Capability>
    <Request>
      <GetCapabilities>
        <Format>text/xml</Format>
        <DCPType>
          <HTTP><Get><OnlineResource xlink:href="{base_url}"/></Get></HTTP>
        </DCPType>
      </GetCapabilities>
      <GetMap>
        <Format>image/png</Format>
        <Format>image/jpeg</Format>
        <DCPType>
          <HTTP><Get><OnlineResource xlink:href="{base_url}"/></Get></HTTP>
        </DCPType>
      </GetMap>
      <GetFeatureInfo>
        <Format>text/plain</Format>
        <Format>application/json</Format>
        <DCPType>
          <HTTP><Get><OnlineResource xlink:href="{base_url}"/></Get></HTTP>
        </DCPType>
      </GetFeatureInfo>
    </Request>
    <Layer>
      <Title>Traffic Visualization Layers</Title>
      <Abstract>城市交通相关图层集合</Abstract>
      <CRS>EPSG:4326</CRS>
      <EX_GeographicBoundingBox>
        <westBoundLongitude>116.0</westBoundLongitude>
        <eastBoundLongitude>117.0</eastBoundLongitude>
        <southBoundLatitude>39.5</southBoundLatitude>
        <northBoundLatitude>40.5</northBoundLatitude>
      </EX_GeographicBoundingBox>
      <Layer>
        <Name>traffic_heatmap</Name>
        <Title>交通热力图</Title>
        <Abstract>实时交通流量热力图</Abstract>
        <CRS>EPSG:4326</CRS>
      </Layer>
      <Layer>
        <Name>buildings</Name>
        <Title>建筑白模</Title>
        <Abstract>城市建筑物三维白模</Abstract>
        <CRS>EPSG:4326</CRS>
      </Layer>
      <Layer>
        <Name>roads</Name>
        <Title>道路网络</Title>
        <Abstract>城市道路网络</Abstract>
        <CRS>EPSG:4326</CRS>
      </Layer>
      <Layer>
        <Name>pois</Name>
        <Title>POI标注</Title>
        <Abstract>兴趣点标注</Abstract>
        <CRS>EPSG:4326</CRS>
      </Layer>
    </Layer>
  </Capability>
</WMS_Capabilities>'''
        return xml

    def get_map(self, db: Session, layers: str, bbox: Tuple[float, float, float, float],
                width: int, height: int, format: str = "image/png",
                srs: str = "EPSG:4326", time: str = None,
                data_type: str = "vehicle") -> bytes:
        min_lon, min_lat, max_lon, max_lat = bbox

        layer_list = [l.strip() for l in layers.split(',')]

        base_img = Image.new('RGBA', (width, height), (0, 0, 0, 0))

        for layer in layer_list:
            if layer == 'traffic_heatmap':
                heatmap_img = self._render_heatmap(
                    db, min_lon, min_lat, max_lon, max_lat, width, height, data_type, time
                )
                if heatmap_img:
                    base_img = Image.alpha_composite(base_img, heatmap_img)

        return self._image_to_bytes(base_img, format)

    def _render_heatmap(self, db: Session, min_lon: float, min_lat: float,
                        max_lon: float, max_lat: float, width: int, height: int,
                        data_type: str, time_str: str = None) -> Optional[Image.Image]:
        timestamp = None
        if time_str:
            try:
                timestamp = datetime.fromisoformat(time_str.replace('Z', '+00:00'))
            except:
                pass

        points = heatmap_service.get_traffic_data_points(
            db,
            timestamp - __import__('datetime').timedelta(hours=1) if timestamp else __import__('datetime').datetime.utcnow() - __import__('datetime').timedelta(hours=1),
            timestamp if timestamp else __import__('datetime').datetime.utcnow(),
            data_type,
            'all',
            [min_lon, min_lat, max_lon, max_lat]
        )

        from app.heatmap.generator import heatmap_generator

        lon_range = max_lon - min_lon
        lat_range = max_lat - min_lat

        pixels = []
        for point in points:
            if lon_range > 0 and lat_range > 0:
                px = int((point['lon'] - min_lon) / lon_range * width)
                py = int((1 - (point['lat'] - min_lat) / lat_range) * height)
                pixels.append((px, py, point['value']))

        img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
        draw = Image.new('RGBA', (width, height), (0, 0, 0, 0))
        draw_pixels = draw.load()

        from app.heatmap.generator import interpolate_color

        max_val = max([p[2] for p in pixels]) if pixels else 1
        radius = max(5, min(width, height) // 50)

        for px, py, val in pixels:
            intensity = min(1.0, val / max_val)
            color = interpolate_color(intensity)

            for dx in range(-radius, radius + 1):
                for dy in range(-radius, radius + 1):
                    d = (dx ** 2 + dy ** 2) ** 0.5
                    if d > radius:
                        continue
                    alpha_factor = 1 - d / radius
                    nx, ny = px + dx, py + dy
                    if 0 <= nx < width and 0 <= ny < height:
                        r, g, b, a = color
                        existing = draw_pixels[nx, ny]
                        new_a = min(255, existing[3] + int(a * alpha_factor))
                        draw_pixels[nx, ny] = (r, g, b, new_a)

        return draw

    def _image_to_bytes(self, img: Image.Image, format: str) -> bytes:
        buffer = io.BytesIO()
        if format == 'image/jpeg':
            img = img.convert('RGB')
            img.save(buffer, format='JPEG', quality=85)
        else:
            img.save(buffer, format='PNG')
        return buffer.getvalue()

    def get_feature_info(self, db: Session, layers: str, bbox: Tuple,
                         x: int, y: int, width: int, height: int,
                         info_format: str = "application/json") -> Dict:
        min_lon, min_lat, max_lon, max_lat = bbox
        lon_range = max_lon - min_lon
        lat_range = max_lat - min_lat

        click_lon = min_lon + (x / width) * lon_range
        click_lat = max_lat - (y / height) * lat_range

        features = []

        layer_list = [l.strip() for l in layers.split(',')]

        for layer in layer_list:
            if layer == 'traffic_heatmap':
                features.append({
                    "layer": layer,
                    "click_point": {"lon": click_lon, "lat": click_lat},
                    "attributes": {
                        "description": "交通流量热力图点击位置"
                    }
                })

        if info_format == "application/json":
            return {
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "Point",
                            "coordinates": [click_lon, click_lat]
                        },
                        "properties": f
                    }
                    for f in features
                ]
            }

        return {"features": features}


class WMTSservice:
    def __init__(self):
        self.tile_matrix_set = "GoogleMapsCompatible"

    def get_capabilities(self, base_url: str) -> str:
        xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<Capabilities xmlns="http://www.opengis.net/wmts/1.0"
    xmlns:ows="http://www.opengis.net/ows/1.1"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.opengis.net/wmts/1.0 http://schemas.opengis.net/wmts/1.0/wmtsGetCapabilities_response.xsd"
    version="1.0.0">
  <ows:ServiceIdentification>
    <ows:Title>城市交通流量三维可视化平台 WMTS 服务</ows:Title>
    <ows:ServiceType>OGC WMTS</ows:ServiceType>
    <ows:ServiceTypeVersion>1.0.0</ows:ServiceTypeVersion>
  </ows:ServiceIdentification>
  <Contents>
    <Layer>
      <ows:Title>交通热力图</ows:Title>
      <ows:Identifier>traffic_heatmap</ows:Identifier>
      <Style>
        <ows:Identifier>default</ows:Identifier>
      </Style>
      <Format>image/png</Format>
      <TileMatrixSetLink>
        <TileMatrixSet>GoogleMapsCompatible</TileMatrixSet>
      </TileMatrixSetLink>
      <ResourceURL format="image/png" resourceType="tile"
        template="{base_url}/tile/{{TileMatrix}}/{{TileCol}}/{{TileRow}}.png"/>
    </Layer>
  </Contents>
  <TileMatrixSet>
    <ows:Identifier>GoogleMapsCompatible</ows:Identifier>
    <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>
    <WellKnownScaleSet>urn:ogc:def:wkss:OGC:1.0:GoogleMapsCompatible</WellKnownScaleSet>
  </TileMatrixSet>
</Capabilities>'''
        return xml

    def get_tile(self, db: Session, layer: str, z: int, x: int, y: int,
                 format: str = "image/png", data_type: str = "vehicle") -> bytes:
        if layer == 'traffic_heatmap':
            return heatmap_service.generate_tile(db, z, x, y, None, data_type, 'all')
        elif layer == 'buildings':
            tile_data = tile_generator.generate_building_tile(db, z, x, y)
            return self._geojson_to_image(tile_data)
        elif layer == 'roads':
            tile_data = tile_generator.generate_road_tile(db, z, x, y)
            return self._geojson_to_image(tile_data)
        elif layer == 'pois':
            tile_data = tile_generator.generate_poi_tile(db, z, x, y)
            return self._geojson_to_image(tile_data)
        else:
            return b''

    def _geojson_to_image(self, geojson: Dict) -> bytes:
        img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
        return self._image_to_bytes(img)

    def _image_to_bytes(self, img: Image.Image, format: str = 'png') -> bytes:
        buffer = io.BytesIO()
        img.save(buffer, format='PNG')
        return buffer.getvalue()


wms_service = WMSservice()
wmts_service = WMTSservice()
