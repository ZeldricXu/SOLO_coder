from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from sqlalchemy.orm import Session
from typing import Optional
import io

from app.database import get_db
from app.tiles import tile_generator, wms_service, wmts_service
from app.utils.auth import get_current_active_user

import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/tiles", tags=["地图切片"])


@router.get("/{layer_type}/tileset.json")
async def get_tileset(
    layer_type: str,
    min_zoom: int = Query(10),
    max_zoom: int = Query(18),
    db: Session = Depends(get_db),
):
    tileset = tile_generator.generate_3dtileset(layer_type, min_zoom, max_zoom)
    return tileset


@router.get("/{layer_type}/{z}/{x}/{y}.geojson")
async def get_tile(
    layer_type: str,
    z: int,
    x: int,
    y: int,
    db: Session = Depends(get_db),
):
    if layer_type == "buildings":
        tile_data = tile_generator.generate_building_tile(db, z, x, y)
    elif layer_type == "roads":
        tile_data = tile_generator.generate_road_tile(db, z, x, y)
    elif layer_type == "pois":
        tile_data = tile_generator.generate_poi_tile(db, z, x, y)
    else:
        return JSONResponse(
            status_code=400,
            content={"error": f"Unknown layer type: {layer_type}"}
        )

    return tile_data


@router.get("/cache/stats")
async def get_cache_stats():
    stats = tile_generator.get_cache_stats()
    return stats


@router.post("/cache/clear")
async def clear_cache(
    layer_type: Optional[str] = Query(None),
    current_user=Depends(get_current_active_user),
):
    tile_generator.clear_cache(layer_type)
    return {"status": "success", "message": f"Cache cleared for {layer_type or 'all layers'}"}


@router.get("/wms")
async def wms_endpoint(
    request: Request,
    service: str = Query(..., alias="SERVICE"),
    request_type: str = Query("GetCapabilities", alias="REQUEST"),
    layers: Optional[str] = Query(None, alias="LAYERS"),
    bbox: Optional[str] = Query(None, alias="BBOX"),
    width: Optional[int] = Query(256, alias="WIDTH"),
    height: Optional[int] = Query(256, alias="HEIGHT"),
    format: str = Query("image/png", alias="FORMAT"),
    srs: Optional[str] = Query("EPSG:4326", alias="SRS"),
    crs: Optional[str] = Query(None, alias="CRS"),
    time: Optional[str] = Query(None, alias="TIME"),
    styles: Optional[str] = Query(None, alias="STYLES"),
    db: Session = Depends(get_db),
):
    request_type = request_type.upper() if request_type else "GETCAPABILITIES"

    if request_type == "GETCAPABILITIES":
        base_url = str(request.url).split('?')[0]
        xml = wms_service.get_capabilities(base_url)
        return Response(content=xml, media_type="text/xml")

    elif request_type == "GETMAP":
        if not bbox or not layers:
            return JSONResponse(
                status_code=400,
                content={"error": "Missing required parameters: BBOX, LAYERS"}
            )

        bbox_values = [float(x) for x in bbox.split(',')]
        if len(bbox_values) != 4:
            return JSONResponse(
                status_code=400,
                content={"error": "Invalid BBOX format"}
            )

        srs = srs or crs or "EPSG:4326"
        image_bytes = wms_service.get_map(
            db, layers, tuple(bbox_values), width, height, format, srs, time
        )

        return Response(content=image_bytes, media_type=format)

    elif request_type == "GETFEATUREINFO":
        if not bbox or not layers:
            return JSONResponse(
                status_code=400,
                content={"error": "Missing required parameters"}
            )

        bbox_values = [float(x) for x in bbox.split(',')]
        x = request.query_params.get("I", request.query_params.get("X", 0))
        y = request.query_params.get("J", request.query_params.get("Y", 0))

        info = wms_service.get_feature_info(
            db, layers, tuple(bbox_values),
            int(x), int(y), width, height
        )

        return JSONResponse(content=info)

    else:
        return JSONResponse(
            status_code=400,
            content={"error": f"Unsupported request type: {request_type}"}
        )


@router.get("/wmts")
async def wmts_endpoint(
    request: Request,
    service: str = Query(..., alias="SERVICE"),
    request_type: str = Query("GetCapabilities", alias="REQUEST"),
    layer: Optional[str] = Query(None, alias="LAYER"),
    style: Optional[str] = Query("default", alias="STYLE"),
    tile_matrix_set: Optional[str] = Query(None, alias="TILEMATRIXSET"),
    tile_matrix: Optional[str] = Query(None, alias="TILEMATRIX"),
    tile_col: Optional[int] = Query(None, alias="TILECOL"),
    tile_row: Optional[int] = Query(None, alias="TILEROW"),
    format: str = Query("image/png", alias="FORMAT"),
    db: Session = Depends(get_db),
):
    request_type = request_type.upper() if request_type else "GETCAPABILITIES"

    if request_type == "GETCAPABILITIES":
        base_url = str(request.url).split('?')[0]
        xml = wmts_service.get_capabilities(base_url)
        return Response(content=xml, media_type="text/xml")

    elif request_type == "GETTILE":
        if not layer or tile_matrix is None or tile_col is None or tile_row is None:
            return JSONResponse(
                status_code=400,
                content={"error": "Missing required parameters: LAYER, TILEMATRIX, TILECOL, TILEROW"}
            )

        z = int(tile_matrix)
        tile_bytes = wmts_service.get_tile(db, layer, z, tile_col, tile_row, format)

        return Response(content=tile_bytes, media_type=format)

    else:
        return JSONResponse(
            status_code=400,
            content={"error": f"Unsupported request type: {request_type}"}
        )
