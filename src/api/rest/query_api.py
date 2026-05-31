from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

from src.service.query_service import QueryService

router = APIRouter()
_query_service = QueryService()


class QueryRequest(BaseModel):
    sql: str
    optimize: bool = True


class ValidateRequest(BaseModel):
    sql: str


@router.post("/execute")
async def execute_query(request: QueryRequest):
    try:
        result = _query_service.execute_query(request.sql, request.optimize)
        return result
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/validate")
async def validate_query(request: ValidateRequest):
    result = _query_service.validate_sql(request.sql)
    return result


@router.post("/explain")
async def explain_query(request: ValidateRequest):
    try:
        explanation = _query_service.explain_query(request.sql)
        return {"sql": request.sql, "explanation": explanation}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/parse")
async def parse_query(request: ValidateRequest):
    try:
        parsed = _query_service.parse_sql(request.sql)
        return {
            "sql_type": parsed.sql_type.value,
            "sources": [{"name": s.name, "alias": s.alias, "is_stream": s.is_stream} for s in parsed.sources],
            "columns": [{"name": c.name, "alias": c.alias, "aggregation": c.aggregation} for c in parsed.columns],
            "window_type": parsed.window.window_type.value,
            "window_size": parsed.window.size,
            "join_type": parsed.join.join_type.value if parsed.join else None,
            "group_by": parsed.group_by,
            "order_by": parsed.order_by,
            "limit": parsed.limit,
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))
