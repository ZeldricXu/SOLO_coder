from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import CDCCaptureRequest, CDCCaptureResponse
from streamsql.services.cdc_service import CDCService
from streamsql.api.dependencies import get_cdc_service

router = APIRouter(prefix="/cdc", tags=["cdc"])


@router.post("/capture", response_model=CDCCaptureResponse)
def create_capture(
    request: CDCCaptureRequest,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.create_capture(
            source_config=request.source_config,
            output_config=request.output_config,
            serializer_format=request.serializer_format,
        )
        return CDCCaptureResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/mock-events/{table_name}")
def generate_mock_events(
    table_name: str,
    count: int = 10,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.generate_mock_events(table_name, event_count=count)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/start/{capture_id}")
def start_capture(
    capture_id: str,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.start_capture(capture_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/stop/{capture_id}")
def stop_capture(
    capture_id: str,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.stop_capture(capture_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status/{capture_id}")
def get_capture_status(
    capture_id: str,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.get_capture_status(capture_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/serialize")
def serialize_events(
    events: list[dict],
    format_type: str = "json",
    compress: bool = False,
    service: CDCService = Depends(get_cdc_service),
):
    try:
        result = service.serialize_events(events, format_type, compress)
        return {"code": 200, "data": {"format": format_type, "size_bytes": len(result)}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
