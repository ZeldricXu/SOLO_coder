from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime
from enum import Enum


class AttackType(str, Enum):
    PROMPT_INJECTION = "prompt_injection"
    JAILBREAK = "jailbreak"
    ROLE_PLAY = "role_play"
    TOKEN_MANIPULATION = "token_manipulation"
    CONTEXT_POISONING = "context_poisoning"
    GRADIENT_ATTACK = "gradient_attack"


class AttackSeverity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class AttackConfig(BaseModel):
    attack_type: AttackType
    severity: AttackSeverity = AttackSeverity.MEDIUM
    iterations: int = Field(default=5, ge=1, le=100)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    target_behavior: Optional[str] = None
    constraints: Optional[List[str]] = None
    max_tokens: int = Field(default=500, ge=10)


class AdversarialPrompt(BaseModel):
    id: str
    original_prompt: str
    adversarial_prompt: str
    attack_type: AttackType
    success_probability: float = Field(default=0.0, ge=0.0, le=1.0)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class EvaluationResult(BaseModel):
    prompt_id: str
    passed: bool
    score: float = Field(ge=0.0, le=1.0)
    detected_issues: List[str] = Field(default_factory=list)
    response_analysis: Optional[Dict[str, Any]] = None
    execution_time_ms: float


class EvaluationReport(BaseModel):
    report_id: str
    model_name: str
    total_attacks: int
    successful_attacks: int
    failed_attacks: int
    success_rate: float
    average_score: float
    results: List[EvaluationResult]
    generated_at: datetime = Field(default_factory=datetime.utcnow)
    recommendations: List[str] = Field(default_factory=list)
