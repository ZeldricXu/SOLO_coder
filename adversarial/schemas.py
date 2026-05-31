from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class AttackStrategy(str, Enum):
    PROMPT_INJECTION = "prompt_injection"
    JAILBREAK = "jailbreak"
    ROLE_PLAYING = "role_playing"
    OBFUSCATION = "obfuscation"
    FEW_SHOT = "few_shot"
    MULTI_MODAL = "multi_modal"
    TREE_OF_THOUGHT = "tree_of_thought"


class AdversarialExample(BaseModel):
    id: str = Field(default_factory=lambda: f"adv_{__import__('uuid').uuid4().hex[:12]}")
    original_prompt: str
    adversarial_prompt: str
    strategy: AttackStrategy
    success_probability: float = Field(default=0.0, ge=0.0, le=1.0)
    attack_params: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=lambda: datetime.now(datetime.timezone.utc))

    model_config = ConfigDict(from_attributes=True)


class AttackResult(BaseModel):
    strategy: AttackStrategy
    success: bool
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    adversarial_prompts: List[AdversarialExample] = Field(default_factory=list)
    model_response: Optional[str] = None
    attack_duration_ms: float = Field(default=0.0)
    error_message: Optional[str] = None


class AdversarialAttackRequest(BaseModel):
    target_prompt: str = Field(..., min_length=1, description="目标提示词")
    strategies: List[AttackStrategy] = Field(
        default=[AttackStrategy.PROMPT_INJECTION, AttackStrategy.JAILBREAK],
        description="攻击策略列表"
    )
    model_provider: str = Field(default="openai", description="模型提供商")
    model_name: str = Field(default="gpt-3.5-turbo", description="模型名称")
    max_attempts: int = Field(default=5, ge=1, le=20, description="最大尝试次数")
    temperature: float = Field(default=0.7, ge=0.0, le=1.0, description="生成温度")
    custom_attack_params: Optional[Dict[str, Any]] = Field(default=None, description="自定义攻击参数")


class AdversarialAttackResponse(BaseModel):
    request_id: str
    target_prompt: str
    results: List[AttackResult]
    overall_risk_score: float = Field(default=0.0, ge=0.0, le=1.0)
    total_attack_duration_ms: float
    recommendations: List[str] = Field(default_factory=list)


class SecurityAssessmentRequest(BaseModel):
    model_provider: str
    model_name: str
    test_categories: Optional[List[str]] = Field(default=None, description="测试类别，如None则测试全部")
    test_prompts: Optional[List[str]] = Field(default=None, description="自定义测试提示词")
    num_tests_per_category: int = Field(default=10, ge=1, le=100)


class SecurityMetrics(BaseModel):
    category: str
    total_tests: int
    success_count: int
    failure_count: int
    success_rate: float
    average_confidence: float


class SecurityAssessmentResponse(BaseModel):
    assessment_id: str
    model_provider: str
    model_name: str
    overall_security_score: float = Field(default=1.0, ge=0.0, le=1.0)
    risk_level: str = Field(default="low")
    metrics: List[SecurityMetrics] = Field(default_factory=list)
    high_risk_examples: List[AdversarialExample] = Field(default_factory=list)
    recommendations: List[str] = Field(default_factory=list)
    started_at: datetime
    completed_at: datetime
    duration_seconds: float
