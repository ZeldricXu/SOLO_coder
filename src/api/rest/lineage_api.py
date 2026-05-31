from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

from src.service.lineage_service import LineageService

router = APIRouter()
_lineage_service = LineageService()


class LineageSQLRequest(BaseModel):
    sql: str
    default_database: str = "default"


class LineageDAGRequest(BaseModel):
    sql_list: List[str]
    default_database: str = "default"


class LineageQueryRequest(BaseModel):
    node_id: str
    depth: int = -1


class ImpactRequest(BaseModel):
    node_id: str


class PathRequest(BaseModel):
    source_id: str
    target_id: str


@router.post("/parse")
async def parse_lineage(request: LineageSQLRequest):
    try:
        result = _lineage_service.parse_sql_lineage(request.sql, request.default_database)
        return result
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/dag")
async def build_dag(request: LineageDAGRequest):
    try:
        result = _lineage_service.build_lineage_dag(request.sql_list, request.default_database)
        return result
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/upstream")
async def get_upstream(request: LineageQueryRequest):
    return {"node_id": request.node_id, "upstream": _lineage_service.get_upstream(request.node_id, request.depth)}


@router.post("/downstream")
async def get_downstream(request: LineageQueryRequest):
    return {"node_id": request.node_id, "downstream": _lineage_service.get_downstream(request.node_id, request.depth)}


@router.post("/impact")
async def impact_analysis(request: ImpactRequest):
    return _lineage_service.impact_analysis(request.node_id)


@router.post("/paths")
async def get_paths(request: PathRequest):
    return {"source": request.source_id, "target": request.target_id, "paths": _lineage_service.get_lineage_paths(request.source_id, request.target_id)}


@router.get("/export/dot")
async def export_dot():
    return {"dot": _lineage_service.export_dot()}


@router.get("/export/json")
async def export_json():
    return _lineage_service.export_json()
