from datetime import datetime
from typing import List, Dict, Optional, Any, Union
from pydantic import BaseModel, Field
from enum import Enum


class ConditionType(str, Enum):
    COMPARISON = "comparison"
    LOGICAL = "logical"
    MEMBERSHIP = "membership"
    RANGE = "range"


class LogicalOperator(str, Enum):
    AND = "and"
    OR = "or"
    NOT = "not"


class PlayerProfile(BaseModel):
    player_id: str
    profile_tags: List[str]
    level: int
    vip_level: int
    total_play_time: int
    pay_amount: float
    last_active: datetime
    churn_risk: str
    activity_score: float
    payment_score: float
    social_score: float
    
    class Config:
        from_attributes = True


class ProfileTag(BaseModel):
    tag: str
    category: str
    confidence: float
    reasoning: str


class PlayerStats(BaseModel):
    player_id: str
    total_events: int
    login_count: int
    payment_count: int
    social_interaction_count: int
    quest_complete_count: int
    total_payment_amount: float
    days_since_last_active: int
    unique_active_days: int
    avg_events_per_day: float
    
    def to_context(self) -> Dict[str, Any]:
        return {
            "player_id": self.player_id,
            "total_events": self.total_events,
            "login_count": self.login_count,
            "payment_count": self.payment_count,
            "social_interaction_count": self.social_interaction_count,
            "quest_complete_count": self.quest_complete_count,
            "total_payment_amount": self.total_payment_amount,
            "days_since_last_active": self.days_since_last_active,
            "unique_active_days": self.unique_active_days,
            "avg_events_per_day": self.avg_events_per_day
        }


class ChurnPrediction(BaseModel):
    player_id: str
    risk_level: str
    risk_score: float
    risk_factors: List[str]
    predicted_churn_probability: float


class ProfileGenerationRequest(BaseModel):
    player_ids: Optional[List[str]] = None
    game_id: Optional[str] = None
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None


class ProfileGenerationResponse(BaseModel):
    success: bool
    processed_count: int
    message: str
    profiles: Optional[List[PlayerProfile]] = None


class RuleConditionModel(BaseModel):
    condition_type: ConditionType = ConditionType.COMPARISON
    field: Optional[str] = None
    operator: Optional[str] = None
    value: Any = None
    conditions: Optional[List['RuleConditionModel']] = None
    logical_op: Optional[LogicalOperator] = None
    
    class Config:
        schema_extra = {
            "example": {
                "condition_type": "logical",
                "logical_op": "and",
                "conditions": [
                    {
                        "condition_type": "comparison",
                        "field": "unique_active_days",
                        "operator": "gte",
                        "value": 3
                    },
                    {
                        "condition_type": "comparison",
                        "field": "avg_events_per_day",
                        "operator": "gte",
                        "value": 20
                    }
                ]
            }
        }


RuleConditionModel.model_rebuild()


class TagRuleModel(BaseModel):
    rule_id: str
    tag_name: str
    category: str
    description: str
    condition: RuleConditionModel
    confidence: float = 0.8
    reasoning_template: str = ""
    priority: int = 0
    enabled: bool = True
    exclusive_group: Optional[str] = None
    
    class Config:
        schema_extra = {
            "example": {
                "rule_id": "activity_high_v2",
                "tag_name": "高活跃",
                "category": "activity",
                "description": "高活跃玩家 - 近90天活跃3天以上且日均行为20次以上",
                "condition": {
                    "condition_type": "logical",
                    "logical_op": "and",
                    "conditions": [
                        {
                            "condition_type": "comparison",
                            "field": "unique_active_days",
                            "operator": "gte",
                            "value": 3
                        },
                        {
                            "condition_type": "comparison",
                            "field": "avg_events_per_day",
                            "operator": "gte",
                            "value": 20
                        }
                    ]
                },
                "confidence": 0.85,
                "reasoning_template": "近90天活跃{unique_active_days}天，日均{avg_events_per_day:.1f}次行为",
                "priority": 100,
                "enabled": True,
                "exclusive_group": "activity_level"
            }
        }


class RuleEvaluationResultModel(BaseModel):
    rule_id: str
    tag_name: str
    category: str
    matched: bool
    confidence: float
    reasoning: str


class TagRulesConfigResponse(BaseModel):
    version: str
    description: str
    updated_at: str
    total_rules: int
    rules: List[TagRuleModel]


class AddRuleRequest(BaseModel):
    rule: TagRuleModel


class AddRuleResponse(BaseModel):
    success: bool
    rule_id: str
    message: str


class UpdateRuleRequest(BaseModel):
    rule: TagRuleModel


class UpdateRuleResponse(BaseModel):
    success: bool
    rule_id: str
    message: str


class DeleteRuleResponse(BaseModel):
    success: bool
    rule_id: str
    message: str


class ReloadRulesResponse(BaseModel):
    success: bool
    message: str
    rules_count: int
    config_version: str


class EngineStatusResponse(BaseModel):
    version: str
    loaded_at: Optional[str]
    total_rules: int
    categories: List[str]
    exclusive_groups: List[str]
    auto_reload_enabled: bool
    config_path: str


class TestRuleRequest(BaseModel):
    condition: RuleConditionModel
    context: Dict[str, Any]


class TestRuleResponse(BaseModel):
    matched: bool
    evaluation_result: bool
    context_used: Dict[str, Any]


class PlayerProfileWithDetails(BaseModel):
    player_id: str
    profile_tags: List[str]
    tag_details: List[ProfileTag]
    level: int
    vip_level: int
    total_play_time: int
    pay_amount: float
    last_active: datetime
    churn_risk: str
    activity_score: float
    payment_score: float
    social_score: float
    stats: Optional[PlayerStats] = None
    
    class Config:
        from_attributes = True
