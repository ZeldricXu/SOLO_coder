from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class RuleCondition(BaseModel):
    field: str
    operator: str
    value: Any
    type: str = "comparison"


class RuleAction(BaseModel):
    action_type: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    target: Optional[str] = None


class RuleCreate(BaseModel):
    name: str
    description: Optional[str] = None
    trigger_type: str
    trigger_config: Dict[str, Any] = Field(default_factory=dict)
    conditions: List[RuleCondition] = Field(default_factory=list)
    actions: List[RuleAction] = Field(default_factory=list)
    priority: int = 0
    enabled: bool = True
    edge_node_id: Optional[str] = None
    labels: Dict[str, Any] = Field(default_factory=dict)


class RuleUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    trigger_type: Optional[str] = None
    trigger_config: Optional[Dict[str, Any]] = None
    conditions: Optional[List[RuleCondition]] = None
    actions: Optional[List[RuleAction]] = None
    priority: Optional[int] = None
    enabled: Optional[bool] = None
    status: Optional[str] = None


class RuleResponse(BaseModel):
    id: str
    rule_id: str
    name: str
    description: Optional[str]
    trigger_type: str
    trigger_config: Dict[str, Any]
    conditions: List[Dict[str, Any]]
    actions: List[Dict[str, Any]]
    priority: int
    enabled: bool
    status: str
    edge_node_id: Optional[str]
    labels: Dict[str, Any]
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class RuleExecutionRequest(BaseModel):
    rule_id: str
    input_data: Dict[str, Any]
    context: Dict[str, Any] = Field(default_factory=dict)


class RuleExecutionResult(BaseModel):
    rule_id: str
    triggered: bool
    actions_executed: List[Dict[str, Any]]
    success: bool
    error: Optional[str] = None
    execution_time_ms: float
    timestamp: datetime
