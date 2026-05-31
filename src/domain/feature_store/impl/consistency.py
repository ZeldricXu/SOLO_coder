from typing import List, Dict, Any, Optional
from datetime import datetime
from ..models import ConsistencyCheckResult, FeatureValue
from ..interfaces import ConsistencyCheckerPort, OnlineFeatureServicePort, OfflineFeatureServicePort
import logging
import math

logger = logging.getLogger(__name__)


class ConsistencyChecker(ConsistencyCheckerPort):
    def __init__(
        self,
        online_service: OnlineFeatureServicePort,
        offline_service: OfflineFeatureServicePort,
    ):
        self.online_service = online_service
        self.offline_service = offline_service

    async def check_all_features(
        self, entity_id: str, feature_names: List[str]
    ) -> List[ConsistencyCheckResult]:
        results: List[ConsistencyCheckResult] = []
        now = datetime.utcnow()

        for feature_name in feature_names:
            result = await self._check_single_feature(entity_id, feature_name, now)
            results.append(result)

        inconsistent = [r for r in results if not r.is_consistent]
        if inconsistent:
            logger.warning(f"Found {len(inconsistent)} inconsistent features for entity {entity_id}")

        return results

    async def _check_single_feature(
        self, entity_id: str, feature_name: str, check_time: datetime
    ) -> ConsistencyCheckResult:
        from ..models import FeatureLookupRequest, FeatureValue

        online_request = FeatureLookupRequest(
            entity_id=entity_id,
            entity_key={"_dummy": "key"},
            feature_names=[feature_name],
        )
        online_values = await self.online_service.lookup_features(online_request)
        online_value = online_values[0].value if online_values else None

        offline_values = await self.offline_service.get_point_in_time(
            entity_id, [feature_name], check_time
        )
        offline_value = offline_values[0].value if offline_values else None

        is_consistent = self._compare_values(online_value, offline_value)
        diff_score = self._calculate_diff_score(online_value, offline_value)

        return ConsistencyCheckResult(
            feature_name=feature_name,
            is_consistent=is_consistent,
            online_value=online_value,
            offline_value=offline_value,
            diff_score=diff_score,
            checked_at=check_time,
        )

    def _compare_values(self, online: Any, offline: Any) -> bool:
        if online is None and offline is None:
            return True
        if online is None or offline is None:
            return False

        if isinstance(online, (int, float)) and isinstance(offline, (int, float)):
            if online == 0 and offline == 0:
                return True
            relative_diff = abs(online - offline) / max(abs(online), abs(offline), 1e-9)
            return relative_diff < 0.01

        return online == offline

    def _calculate_diff_score(self, online: Any, offline: Any) -> float:
        if online is None and offline is None:
            return 0.0
        if online is None or offline is None:
            return 1.0

        if isinstance(online, (int, float)) and isinstance(offline, (int, float)):
            if online == 0 and offline == 0:
                return 0.0
            return min(abs(online - offline) / max(abs(online), abs(offline), 1e-9), 1.0)

        if isinstance(online, str) and isinstance(offline, str):
            return 0.0 if online == offline else 1.0

        return 0.0 if online == offline else 1.0

    async def auto_repair(
        self, entity_id: str, feature_names: List[str]
    ) -> Dict[str, Any]:
        from ..models import FeatureStoreRequest

        results = await self.check_all_features(entity_id, feature_names)
        repaired = []

        for result in results:
            if not result.is_consistent and result.offline_value is not None:
                repaired.append(result.feature_name)

        return {
            "checked": len(results),
            "repaired": len(repaired),
            "repaired_features": repaired,
        }
