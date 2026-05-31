from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db
from models import ResponseModel, PaginatedResponse
from .schemas import (
    RuleCreate,
    RuleResponse,
    RuleUpdate,
    RuleExecutionRequest,
    RuleExecutionResult,
)
from .service import RuleService

router = APIRouter(prefix="/api/v1/edge-rules", tags=["Edge Rule Engine"])


@router.post("", response_model=ResponseModel[RuleResponse], status_code=status.HTTP_201_CREATED)
async def create_rule(
    data: RuleCreate,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    rule = await service.create_rule(data)
    return ResponseModel(data=RuleResponse.model_validate(rule))


@router.get("", response_model=PaginatedResponse[RuleResponse])
async def list_rules(
    page: int = 1,
    page_size: int = 20,
    edge_node_id: Optional[str] = None,
    enabled: Optional[bool] = None,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    skip = (page - 1) * page_size
    rules = await service.list_rules(skip, page_size, edge_node_id, enabled)
    return PaginatedResponse(
        data=[RuleResponse.model_validate(r) for r in rules],
        total=len(rules),
        page=page,
        page_size=page_size,
    )


@router.get("/{rule_id}", response_model=ResponseModel[RuleResponse])
async def get_rule(
    rule_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    rule = await service.get_rule(rule_id)
    return ResponseModel(data=RuleResponse.model_validate(rule))


@router.put("/{rule_id}", response_model=ResponseModel[RuleResponse])
async def update_rule(
    rule_id: str,
    data: RuleUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    rule = await service.update_rule(rule_id, data)
    return ResponseModel(data=RuleResponse.model_validate(rule))


@router.delete("/{rule_id}", response_model=ResponseModel)
async def delete_rule(
    rule_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    await service.delete_rule(rule_id)
    return ResponseModel(message="Rule deleted successfully")


@router.post("/execute", response_model=ResponseModel[RuleExecutionResult])
async def execute_rule(
    data: RuleExecutionRequest,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    result = await service.execute_rule(data.rule_id, data.input_data, data.context)
    return ResponseModel(data=RuleExecutionResult(**result))


@router.post("/process", response_model=ResponseModel[List[RuleExecutionResult]])
async def process_data(
    input_data: Dict[str, Any],
    context: Optional[Dict[str, Any]] = None,
    db: AsyncSession = Depends(get_db),
):
    service = RuleService(db)
    results = await service.process_data(input_data, context)
    return ResponseModel(data=[RuleExecutionResult(**r) for r in results])
