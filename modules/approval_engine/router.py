from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    ApprovalRuleCreate,
    ApprovalRuleResponse,
    ApprovalProcessCreate,
    ApprovalProcessResponse,
    ApprovalActionRequest,
    ApprovalRecordResponse,
    ApprovalStatus,
    RuleCombinationOperator,
)
from .service import ApprovalRuleService, ApprovalProcessService

router = APIRouter(prefix="/approvals", tags=["审批规则引擎"])


@router.post("/rules", response_model=Dict[str, Any], status_code=201)
async def create_approval_rule(
    rule_data: ApprovalRuleCreate,
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalRuleService(db)
    rule = await service.create_rule(rule_data)
    return {
        "code": 201,
        "data": rule.model_dump(),
        "message": "审批规则创建成功",
    }


@router.get("/rules/{rule_id}", response_model=Dict[str, Any])
async def get_approval_rule(
    rule_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalRuleService(db)
    rule = await service.get_rule(rule_id, tenant_id)
    return {
        "code": 200,
        "data": rule.model_dump(),
        "message": "查询成功",
    }


@router.post("/rules/{rule_id}/evaluate", response_model=Dict[str, Any])
async def evaluate_approval_rule(
    rule_id: str,
    context: Dict[str, Any],
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalRuleService(db)
    result = await service.evaluate_rule(rule_id, context, tenant_id)
    return {
        "code": 200,
        "data": result,
        "message": "规则评估完成",
    }


@router.get("/rules", response_model=Dict[str, Any])
async def list_approval_rules(
    rule_type: Optional[str] = Query(None),
    tenant_id: Optional[str] = Query(None),
    is_active: Optional[bool] = Query(None),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalRuleService(db)
    rules = await service.list_rules(rule_type, tenant_id, is_active, limit, offset)
    return {
        "code": 200,
        "data": [r.model_dump() for r in rules],
        "total": len(rules),
        "message": "查询成功",
    }


@router.post("/processes", response_model=Dict[str, Any], status_code=201)
async def start_approval_process(
    process_data: ApprovalProcessCreate,
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalProcessService(db)
    process = await service.start_process(process_data)
    return {
        "code": 201,
        "data": process.model_dump(),
        "message": "审批流程启动成功",
    }


@router.get("/processes/{process_id}", response_model=Dict[str, Any])
async def get_approval_process(
    process_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalProcessService(db)
    process = await service.get_process(process_id, tenant_id)
    return {
        "code": 200,
        "data": process.model_dump(),
        "message": "查询成功",
    }


@router.get("/processes/{process_id}/records", response_model=Dict[str, Any])
async def get_process_records(
    process_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalProcessService(db)
    records = await service.get_process_records(process_id, tenant_id)
    return {
        "code": 200,
        "data": [r.model_dump() for r in records],
        "total": len(records),
        "message": "查询成功",
    }


@router.post("/actions", response_model=Dict[str, Any])
async def execute_approval_action(
    action_request: ApprovalActionRequest,
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalProcessService(db)
    record = await service.execute_action(action_request)
    return {
        "code": 200,
        "data": record.model_dump(),
        "message": "审批操作执行成功",
    }


@router.get("/processes", response_model=Dict[str, Any])
async def list_approval_processes(
    entity_type: Optional[str] = Query(None),
    status: Optional[ApprovalStatus] = Query(None),
    started_by: Optional[str] = Query(None),
    approver_id: Optional[str] = Query(None),
    tenant_id: Optional[str] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = ApprovalProcessService(db)
    processes = await service.list_processes(
        entity_type, status, started_by, approver_id, tenant_id, limit, offset
    )
    return {
        "code": 200,
        "data": [p.model_dump() for p in processes],
        "total": len(processes),
        "message": "查询成功",
    }
