from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

from src.service.cdc_service import CDCService

router = APIRouter()
_cdc_service: Optional[CDCService] = None


def _get_service() -> CDCService:
    global _cdc_service
    if _cdc_service is None:
        from src.infrastructure.config.settings import get_settings
        settings = get_settings()
        _cdc_service = CDCService(
            mysql_config=settings.cdc.mysql if settings.cdc else None,
            pg_config=settings.cdc.postgresql if settings.cdc else None,
            kafka_config=settings.kafka if settings.kafka else None,
        )
    return _cdc_service


class OutputDestinationRequest(BaseModel):
    name: str
    dest_type: str
    config: Dict[str, Any]


class SerializationFormatRequest(BaseModel):
    format_type: str


class BinlogFileRequest(BaseModel):
    file_path: str


class WALMessageRequest(BaseModel):
    message: str


@router.get("/status")
async def get_status():
    service = _get_service()
    return service.get_status()


@router.post("/start/mysql")
async def start_mysql_cdc():
    service = _get_service()
    try:
        service.start_mysql_cdc()
        return {"status": "started"}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/stop/mysql")
async def stop_mysql_cdc():
    service = _get_service()
    service.stop_mysql_cdc()
    return {"status": "stopped"}


@router.post("/destinations")
async def add_destination(request: OutputDestinationRequest):
    service = _get_service()
    service.add_output_destination(request.name, request.dest_type, request.config)
    return {"status": "added"}


@router.post("/serialization")
async def set_serialization_format(request: SerializationFormatRequest):
    service = _get_service()
    service.set_serialization_format(request.format_type)
    return {"status": "updated", "format": request.format_type}


@router.post("/parse/binlog")
async def parse_binlog_file(request: BinlogFileRequest):
    service = _get_service()
    try:
        results = service.parse_binlog_file(request.file_path)
        return {"event_count": len(results), "events": results}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/parse/wal")
async def parse_wal_message(request: WALMessageRequest):
    service = _get_service()
    try:
        result = service.parse_wal_message(request.message)
        return result or {"status": "no_event"}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))
