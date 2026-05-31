from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import (
    QualityRuleRequest,
    QualityRuleResponse,
    QualityValidateRequest,
    QualityValidateResponse,
    QualityReportResponse,
)
from streamsql.services.quality_service import QualityService
from streamsql.api.dependencies import get_quality_service

router = APIRouter(prefix="/quality", tags=["quality"])


@router.post("/rules", response_model=QualityRuleResponse)
def create_rule(
    request: QualityRuleRequest,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.create_rule(
            rule_type=request.rule_type,
            name=request.name,
            column=request.column,
            table=request.table,
            parameters=request.parameters,
            severity=request.severity,
        )
        return QualityRuleResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/rules/{rule_id}")
def get_rule(
    rule_id: str,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.get_rule(rule_id)
        if not result:
            raise HTTPException(status_code=404, detail="Rule not found")
        return {"code": 200, "data": result}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/rules")
def list_rules(
    table_name: str | None = None,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.list_rules(table_name)
        return {"code": 200, "data": result, "total": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/rules/{rule_id}")
def delete_rule(
    rule_id: str,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.delete_rule(rule_id)
        if not result:
            raise HTTPException(status_code=404, detail="Rule not found")
        return {"code": 200, "data": {"deleted": True}}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/validate", response_model=QualityValidateResponse)
def validate_data(
    request: QualityValidateRequest,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.validate(
            data=request.data,
            table_name=request.table_name,
            rule_ids=request.rule_ids,
        )
        return QualityValidateResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/validate-row")
def validate_row(
    row: dict,
    table_name: str = "",
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.validate_row(row, table_name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/anomalies")
def get_anomalies(
    table_name: str | None = None,
    severity: str | None = None,
    limit: int = 100,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.get_anomalies(table_name, severity, limit)
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/anomalies")
def clear_anomalies(
    table_name: str | None = None,
    service: QualityService = Depends(get_quality_service),
):
    try:
        count = service.clear_anomalies(table_name)
        return {"code": 200, "data": {"cleared_count": count}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/history")
def get_validation_history(
    table_name: str | None = None,
    limit: int = 100,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.get_validation_history(table_name, limit)
        return {"code": 200, "data": result, "count": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/report", response_model=QualityReportResponse)
def get_quality_report(
    table_name: str | None = None,
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.get_quality_report(table_name)
        return QualityReportResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/rule-types")
def get_available_rule_types(
    service: QualityService = Depends(get_quality_service),
):
    return {"code": 200, "data": service.get_available_rule_types()}


@router.get("/severities")
def get_available_severities(
    service: QualityService = Depends(get_quality_service),
):
    return {"code": 200, "data": service.get_available_severities()}


@router.post("/export-rules")
def export_rules(
    path: str,
    service: QualityService = Depends(get_quality_service),
):
    try:
        service.export_rules(path)
        return {"code": 200, "data": {"path": path}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/import-rules")
def import_rules(
    path: str,
    service: QualityService = Depends(get_quality_service),
):
    try:
        count = service.import_rules(path)
        return {"code": 200, "data": {"imported_count": count}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/scheduler/status")
def get_scheduler_status(
    service: QualityService = Depends(get_quality_service),
):
    try:
        result = service.get_scheduler_status()
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
