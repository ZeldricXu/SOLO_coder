from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import SQLParseRequest, SQLParseResponse
from streamsql.services.query_service import QueryService
from streamsql.api.dependencies import get_query_service

router = APIRouter(prefix="/query", tags=["query"])


@router.post("/parse", response_model=SQLParseResponse)
def parse_sql(
    request: SQLParseRequest,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.parse_sql(sql=request.sql, optimize=request.optimize)
        return SQLParseResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/validate")
def validate_sql(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.validate_sql(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/ast")
def get_ast(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.get_query_ast(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/optimize")
def optimize_query(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.optimize_query(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/physical-plan")
def generate_physical_plan(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.generate_physical_plan(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/estimate")
def estimate_resources(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.estimate_resources(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/detect-windows")
def detect_window_functions(
    sql: str,
    service: QueryService = Depends(get_query_service),
):
    try:
        result = service.detect_window_functions(sql)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))
