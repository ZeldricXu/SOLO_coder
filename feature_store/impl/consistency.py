from typing import Dict, Any
import json

from ..protocols import ConsistencyChecker
from ..schemas import (
    ConsistencyCheckRequest,
    ConsistencyCheckResponse,
)
from common.utils import utc_now


class DefaultConsistencyChecker(ConsistencyChecker):
    def __init__(self, online_store, offline_store):
        self.online_store = online_store
        self.offline_store = offline_store

    async def check(self, request: ConsistencyCheckRequest) -> ConsistencyCheckResponse:
        entity_name = request.entity_name
        entity_id = request.entity_id
        entity_key = self._get_entity_key(entity_name)

        online_values = self._get_online_values(entity_key, entity_id, request.feature_names)
        offline_values = self._get_offline_values(entity_key, entity_id, request)

        inconsistent = self._find_inconsistent_features(
            online_values, offline_values
        )

        return ConsistencyCheckResponse(
            entity_name=entity_name,
            entity_id=entity_id,
            is_consistent=len(inconsistent) == 0,
            online_values=online_values,
            offline_values=offline_values,
            inconsistent_features=inconsistent,
            check_timestamp=utc_now(),
        )

    def values_equal(self, v1: Any, v2: Any) -> bool:
        if isinstance(v1, float) and isinstance(v2, float):
            return abs(v1 - v2) < 1e-9
        if isinstance(v1, list) and isinstance(v2, list):
            return json.dumps(v1, sort_keys=True) == json.dumps(v2, sort_keys=True)
        if isinstance(v1, dict) and isinstance(v2, dict):
            return json.dumps(v1, sort_keys=True) == json.dumps(v2, sort_keys=True)
        return v1 == v2

    def _get_online_values(
        self, entity_key: str, entity_id: str, feature_names: list
    ) -> Dict[str, Any]:
        entity_values = self.online_store.get_entity_values(entity_key, entity_id)
        if feature_names:
            return {k: v for k, v in entity_values.items() if k in feature_names}
        return entity_values

    def _get_offline_values(
        self, entity_key: str, entity_id: str, request: ConsistencyCheckRequest
    ) -> Dict[str, Any]:
        points = self.offline_store.get_entity_points(entity_key, entity_id)
        if not points:
            return {}

        if request.timestamp:
            ts = request.timestamp.timestamp() if hasattr(request.timestamp, 'timestamp') else request.timestamp
            points = [p for p in points if (p.event_timestamp.timestamp() if hasattr(p.event_timestamp, 'timestamp') else p.event_timestamp) <= ts]

        if not points:
            return {}

        latest = max(
            points,
            key=lambda p: p.event_timestamp.timestamp() if hasattr(p.event_timestamp, 'timestamp') else p.event_timestamp
        )

        if request.feature_names:
            return {
                fv.feature_name: fv.value
                for fv in latest.features
                if fv.feature_name in request.feature_names
            }
        return {fv.feature_name: fv.value for fv in latest.features}

    def _find_inconsistent_features(
        self, online_values: Dict[str, Any], offline_values: Dict[str, Any]
    ) -> list:
        inconsistent = []
        all_features = set(online_values.keys()) | set(offline_values.keys())
        for feature in all_features:
            if feature not in online_values or feature not in offline_values:
                inconsistent.append(feature)
            elif not self.values_equal(online_values[feature], offline_values[feature]):
                inconsistent.append(feature)
        return inconsistent

    @staticmethod
    def _get_entity_key(entity_name: str) -> str:
        return f"entity:{entity_name}"
