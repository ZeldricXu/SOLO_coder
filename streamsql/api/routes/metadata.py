from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import MetadataCrawlRequest, MetadataCrawlResponse
from streamsql.services.metadata_service import MetadataService
from streamsql.api.dependencies import get_metadata_service

router = APIRouter(prefix="/metadata", tags=["metadata"])


@router.post("/crawl", response_model=MetadataCrawlResponse)
def crawl_data_source(
    request: MetadataCrawlRequest,
    service: MetadataService = Depends(get_metadata_service),
):
    try:
        result = service.crawl_data_source(
            data_source_config=request.data_source,
            scan_tables=request.scan_tables,
            sample_size=request.sample_size,
        )
        return MetadataCrawlResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schema/{table_name}")
def get_table_schema(
    table_name: str,
    service: MetadataService = Depends(get_metadata_service),
):
    try:
        result = service.infer_schema_from_data([
            {"id": 1, "name": "test", "age": 25, "email": "test@example.com"}
        ])
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/stats/{table_name}")
def get_table_stats(
    table_name: str,
    service: MetadataService = Depends(get_metadata_service),
):
    try:
        result = service.get_table_stats(table_name, {})
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/infer-schema")
def infer_schema(
    data: list[dict],
    service: MetadataService = Depends(get_metadata_service),
):
    try:
        result = service.infer_schema_from_data(data)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
