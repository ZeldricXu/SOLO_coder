from typing import List, Dict, Any
from datetime import datetime
from .types import ConsistencyCheckResult
from .online import OnlineFeatureService
from .offline import OfflineFeatureService
from src.core import generate_id
import logging

logger = logging.getLogger(__name__)


class ConsistencyChecker:
    def __init__(self, online_service: OnlineFeatureService, offline_service: OfflineFeatureService):
        self.online_service = online_service
        self.offline_service = offline_service
        self._check_history: List[ConsistencyCheckResult] = []

    async def check_feature_consistency(
        self,
        entity_id: str,
        feature_name: str,
    ) -> ConsistencyCheckResult:
        logger.info(f"Checking consistency for {feature_name} on entity {entity_id}")

        online_value = None
        try:
            online_result = await self.online_service.lookup_features(
                type("Req", (), {"entity_id": entity_id, "features": [feature_name]})()
            )
            online_value = online_result.get(feature_name)
        except Exception as e:
            logger.warning(f"Online lookup failed for consistency check: {e}")

        offline_value = None
        try:
            offline_value = await self.offline_service.get_point_in_time(
                entity_id=entity_id,
                features=[feature_name],
                point_in_time=datetime.utcnow(),
            )
            offline_value = offline_value.get(feature_name)
        except Exception as e:
            logger.warning(f"Offline lookup failed for consistency check: {e}")

        is_consistent = self._values_equal(online_value, offline_value)
        diff = None if is_consistent else {
            "online_value": online_value,
            "offline_value": offline_value,
        }

        result = ConsistencyCheckResult(
            feature_name=feature_name,
            entity_id=entity_id,
            online_value=online_value,
            offline_value=offline_value,
            is_consistent=is_consistent,
            diff=diff,
        )

        self._check_history.append(result)
        if not is_consistent:
            logger.warning(
                f"Inconsistency detected for {feature_name}/{entity_id}: "
                f"online={online_value}, offline={offline_value}"
            )

        return result

    async def check_all_features(
        self,
        entity_id: str,
        feature_names: List[str],
    ) -> List[ConsistencyCheckResult]:
        results = []
        for feature_name in feature_names:
            result = await self.check_feature_consistency(entity_id, feature_name)
            results.append(result)
        return results

    async def sync_online_to_offline(
        self,
        entity_id: str,
        feature_names: List[str],
    ) -> int:
        logger.info(f"Syncing {len(feature_names)} features for entity {entity_id} from online to offline")

        try:
            online_result = await self.online_service.lookup_features(
                type("Req", (), {"entity_id": entity_id, "features": feature_names})()
            )
        except Exception as e:
            logger.error(f"Failed to read online features for sync: {e}")
            return 0

        from .types import FeatureValue
        features_to_sync = []
        for feature_name, value in online_result.items():
            if value is not None:
                features_to_sync.append(
                    FeatureValue(
                        feature_name=feature_name,
                        entity_id=entity_id,
                        value=value,
                    )
                )

        if features_to_sync:
            await self.offline_service.ingest_features(entity_id, features_to_sync)
            logger.info(f"Synced {len(features_to_sync)} features")

        return len(features_to_sync)

    async def repair_inconsistency(
        self,
        entity_id: str,
        feature_name: str,
        prefer_online: bool = True,
    ) -> bool:
        logger.info(f"Repairing inconsistency for {feature_name}/{entity_id}")
        result = await self.check_feature_consistency(entity_id, feature_name)

        if result.is_consistent:
            return True

        source_value = result.online_value if prefer_online else result.offline_value
        if source_value is None:
            logger.warning(f"Cannot repair: source value is None")
            return False

        from .types import FeatureValue, FeatureStoreRequest

        if prefer_online:
            await self.offline_service.ingest_features(
                entity_id,
                [FeatureValue(feature_name=feature_name, entity_id=entity_id, value=source_value)],
            )
        else:
            await self.online_service.store_features(
                FeatureStoreRequest(
                    entity_id=entity_id,
                    features=[FeatureValue(feature_name=feature_name, entity_id=entity_id, value=source_value)],
                )
            )

        new_result = await self.check_feature_consistency(entity_id, feature_name)
        return new_result.is_consistent

    def _values_equal(self, val1: Any, val2: Any) -> bool:
        if val1 is None and val2 is None:
            return True
        if val1 is None or val2 is None:
            return False

        try:
            if isinstance(val1, float) and isinstance(val2, float):
                return abs(val1 - val2) < 1e-9
            return val1 == val2
        except Exception:
            return str(val1) == str(val2)

    def get_check_history(self, limit: int = 100) -> List[ConsistencyCheckResult]:
        return self._check_history[-limit:]
