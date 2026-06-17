from typing import Literal

from pydantic import BaseModel, Field, model_validator


class QualityRule(BaseModel):
    rule_type: Literal[
        "null_rate",
        "uniqueness",
        "value_range",
        "distribution_drift",
        "custom",
    ]
    column: str | None = None
    params: dict = {}
    threshold: float = Field(default=1.0, ge=0.0, le=1.0)
    strategy: Literal["alert", "block"] = "alert"

    @model_validator(mode="after")
    def _validate_params(self) -> "QualityRule":
        if self.rule_type == "null_rate":
            self.params.setdefault("max_null_rate", 0.05)
        elif self.rule_type == "uniqueness":
            self.params.setdefault("expect_unique", True)
        elif self.rule_type == "value_range":
            if "min_value" not in self.params or "max_value" not in self.params:
                raise ValueError(
                    "value_range rule requires 'min_value' and 'max_value' in params"
                )
        elif self.rule_type == "distribution_drift":
            self.params.setdefault("drift_threshold", 0.1)
            if "reference_stats" not in self.params:
                raise ValueError(
                    "distribution_drift rule requires 'reference_stats' in params"
                )
        elif self.rule_type == "custom":
            if "expectation_type" not in self.params:
                raise ValueError(
                    "custom rule requires 'expectation_type' in params"
                )
        return self
