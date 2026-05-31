from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import (
    TimeSeriesCompressRequest,
    TimeSeriesCompressResponse,
    TimeSeriesQueryRequest,
)
from streamsql.services.timeseries_service import TimeSeriesService
from streamsql.api.dependencies import get_timeseries_service

router = APIRouter(prefix="/timeseries", tags=["timeseries"])


@router.post("/series/{name}")
def create_series(
    name: str,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.create_series(name)
        return {"code": 201, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/data")
def add_data(
    name: str,
    timestamps: list[int],
    values: list[float],
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.add_data_batch(name, timestamps, values)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/datapoint")
def add_data_point(
    name: str,
    timestamp: int,
    value: float,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.add_data_point(name, timestamp, value)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/compress", response_model=TimeSeriesCompressResponse)
def compress(
    request: TimeSeriesCompressRequest,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        service.add_data_batch("temp_series", request.timestamps, request.values)
        result = service.compress("temp_series", request.encoder_type)
        return TimeSeriesCompressResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/compress")
def compress_series(
    name: str,
    encoder_type: str = "gorilla",
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.compress(name, encoder_type)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/series/{name}/decompress")
def decompress_series(
    name: str,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.decompress(name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/downsample")
def downsample(
    name: str,
    target_count: int,
    method: str = "lttb",
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.downsample(name, target_count, method)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/query")
def query_series(
    name: str,
    request: TimeSeriesQueryRequest,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.query(
            name,
            start_time=request.start_time,
            end_time=request.end_time,
            resolution=request.resolution,
        )
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/series/{name}/optimal-encoder")
def get_optimal_encoder(
    name: str,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.get_optimal_encoder(name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/series/{name}/compact")
def compact_series(
    name: str,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.compact(name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/series/{name}")
def get_series_info(
    name: str,
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.get_series_info(name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/series")
def list_series(
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    try:
        result = service.list_series()
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/encoders")
def get_available_encoders(
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    return {"code": 200, "data": service.get_available_encoders()}


@router.get("/downsamplers")
def get_available_downsamplers(
    service: TimeSeriesService = Depends(get_timeseries_service),
):
    return {"code": 200, "data": service.get_available_downsamplers()}
