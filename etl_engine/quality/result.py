from pydantic import BaseModel


class RuleResult(BaseModel):
    rule_type: str
    column: str | None
    passed: bool
    actual_value: float | None = None
    expected_threshold: float | None = None
    details: dict = {}
    strategy: str = "alert"


class ValidationResult(BaseModel):
    passed: bool
    total_rules: int
    passed_rules: int
    failed_rules: int
    blocked: bool
    rule_results: list[RuleResult]
    summary: dict

    def to_dict(self) -> dict:
        return self.model_dump()
