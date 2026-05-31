from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

from src.service.quality_service import QualityService

router = APIRouter()
_quality_service = QualityService()


class AddRuleRequest(BaseModel):
    rule_id: str
    rule_name: str
    rule_type: str
    target_database: str
    target_table: str
    target_column: Optional[str] = None
    strictness: str = "warning"
    params: Optional[Dict[str, Any]] = None
    description: Optional[str] = None


class ValidateTableRequest(BaseModel):
    database_name: str
    table_name: str
    data: List[Dict[str, Any]]


class ValidateColumnRequest(BaseModel):
    database_name: str
    table_name: str
    column_name: str
    data: List[Dict[str, Any]]


class AnomalyRequest(BaseModel):
    database_name: str
    table_name: str
    data: List[Dict[str, Any]]
    columns: Optional[List[str]] = None
    method: str = "zscore"


class MarkAnomalyRequest(BaseModel):
    database_name: str
    table_name: str
    data: List[Dict[str, Any]]
    columns: Optional[List[str]] = None
    method: str = "zscore"
    marker_column: str = "_is_anomaly"


class BaselineRequest(BaseModel):
    column: str
    values: List[Any]


class ScheduledCheckRequest(BaseModel):
    check_id: str
    database_name: str
    table_name: str
    cron_expression: str


@router.post("/rules")
async def add_rule(request: AddRuleRequest):
    return _quality_service.add_rule(
        request.rule_id, request.rule_name, request.rule_type,
        request.target_database, request.target_table,
        request.target_column, request.strictness,
        request.params, request.description,
    )


@router.delete("/rules/{rule_id}")
async def remove_rule(rule_id: str):
    _quality_service.remove_rule(rule_id)
    return {"status": "removed", "rule_id": rule_id}


@router.get("/rules")
async def get_rules(database: Optional[str] = None, table: Optional[str] = None):
    return {"rules": _quality_service.get_rules(database, table)}


@router.post("/validate/table")
async def validate_table(request: ValidateTableRequest):
    return _quality_service.validate_table(request.database_name, request.table_name, request.data)


@router.post("/validate/column")
async def validate_column(request: ValidateColumnRequest):
    return _quality_service.validate_column(request.database_name, request.table_name, request.column_name, request.data)


@router.post("/anomalies/detect")
async def detect_anomalies(request: AnomalyRequest):
    return _quality_service.detect_anomalies(
        request.database_name, request.table_name,
        request.data, request.columns, request.method,
    )


@router.post("/anomalies/mark")
async def mark_anomalies(request: MarkAnomalyRequest):
    return {"data": _quality_service.mark_anomalies(
        request.database_name, request.table_name,
        request.data, request.columns, request.method,
        request.marker_column,
    )}


@router.post("/baseline")
async def compute_baseline(request: BaselineRequest):
    return _quality_service.compute_baseline(request.column, request.values)


@router.get("/score")
async def get_quality_score(database_name: str, table_name: str):
    score = _quality_service.get_quality_score(database_name, table_name)
    return {"database_name": database_name, "table_name": table_name, "score": score}


@router.get("/summary")
async def get_quality_summary(database: Optional[str] = None):
    return _quality_service.get_quality_summary(database)


@router.get("/history")
async def get_validation_history(database_name: str, table_name: str, limit: int = 10):
    return {"history": _quality_service.get_validation_history(database_name, table_name, limit)}


@router.post("/schedule")
async def add_scheduled_check(request: ScheduledCheckRequest):
    _quality_service.add_scheduled_check(
        request.check_id, request.database_name,
        request.table_name, request.cron_expression,
    )
    return {"status": "scheduled", "check_id": request.check_id}


@router.post("/schedule/run")
async def run_scheduled_checks():
    results = _quality_service.run_scheduled_checks()
    return {"results": results}
