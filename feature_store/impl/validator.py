from typing import List, Dict, Any
from datetime import datetime

from ..protocols import FeatureValidator
from ..schemas import FeatureValue, FeatureType


_TYPE_CHECKERS = {
    FeatureType.INT: lambda v: isinstance(int(v), int),
    FeatureType.FLOAT: lambda v: isinstance(float(v), float),
    FeatureType.STRING: lambda v: isinstance(v, str),
    FeatureType.BOOLEAN: lambda v: isinstance(v, bool),
    FeatureType.LIST: lambda v: isinstance(v, list),
    FeatureType.MAP: lambda v: isinstance(v, dict),
    FeatureType.EMBEDDING: lambda v: isinstance(v, list) and all(isinstance(x, (int, float)) for x in v),
    FeatureType.DATETIME: lambda v: isinstance(v, (str, datetime)),
}


class DefaultFeatureValidator(FeatureValidator):
    def validate_feature_definitions(self, features: List[Any]) -> None:
        names = set()
        for f in features:
            if f.name in names:
                raise ValueError(f"Duplicate feature name: {f.name}")
            names.add(f.name)
            if f.type == FeatureType.EMBEDDING and not f.embedding_dim:
                raise ValueError(f"Embedding feature {f.name} must specify embedding_dim")

    def validate_feature_values(
        self, features: List[FeatureValue], valid_features: Dict[str, str]
    ) -> List[FeatureValue]:
        validated = []
        for fv in features:
            if fv.feature_name not in valid_features:
                raise ValueError(f"Unknown feature: {fv.feature_name}")
            expected_type = valid_features[fv.feature_name]
            if not self.check_type(fv.value, expected_type):
                raise ValueError(
                    f"Feature {fv.feature_name} expects type {expected_type}, got {type(fv.value)}"
                )
            validated.append(fv)
        return validated

    def check_type(self, value: Any, expected_type: str) -> bool:
        checker = _TYPE_CHECKERS.get(expected_type)
        if not checker:
            return True
        try:
            return checker(value)
        except (ValueError, TypeError):
            return False
