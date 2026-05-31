from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import (
    LineageExtractRequest,
    LineageResponse,
    LineageImpactRequest,
    LineageImpactResponse,
)
from streamsql.services.lineage_service import LineageService
from streamsql.api.dependencies import get_lineage_service

router = APIRouter(prefix="/lineage", tags=["lineage"])


@router.post("/extract", response_model=LineageResponse)
def extract_lineage(
    request: LineageExtractRequest,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.extract_from_sql(sql=request.sql)
        return LineageResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/table/{table_name}")
def get_table_lineage(
    table_name: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_table_lineage(table_name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/column/{table_name}/{column_name}")
def get_column_lineage(
    table_name: str,
    column_name: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_column_lineage(table_name, column_name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/impact", response_model=LineageImpactResponse)
def analyze_impact(
    request: LineageImpactRequest,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.analyze_impact(table_name=request.table_name)
        return LineageImpactResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/upstream/{table_name}")
def get_upstream(
    table_name: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_upstream(table_name)
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/downstream/{table_name}")
def get_downstream(
    table_name: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_downstream(table_name)
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/path")
def find_path(
    source: str,
    target: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.find_path(source, target)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tables")
def get_all_tables(
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_all_tables()
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/summary")
def get_summary(
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_summary()
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/search")
def search_tables(
    keyword: str,
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.search_tables(keyword)
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/has-cycle")
def has_cycle(
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.has_cycle()
        return {"code": 200, "data": {"has_cycle": result}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/topological-order")
def get_topological_order(
    service: LineageService = Depends(get_lineage_service),
):
    try:
        result = service.get_topological_order()
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
