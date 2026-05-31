from typing import List, Dict, Any, Optional
from datetime import datetime, timedelta
from collections import defaultdict
from ..models import (
    FeatureValue,
    FeatureLookupRequest,
    FeatureStoreRequest,
    FeatureOnlineStats,
)
from ..interfaces import OnlineFeatureServicePort
from src.core import PlatformError
import logging

logger = logging.getLogger(__name__)


class OnlineFeatureService(OnlineFeatureServicePort):
    def __init__(self):
        self._feature_store: Dict[str, Dict[str, FeatureValue]] = defaultdict(dict)
        self._stats: Dict[str, FeatureOnlineStats] = defaultdict(lambda: FeatureOnlineStats(feature_name=""))
        self._ttl_index: Dict[str, datetime] = {}

    async def store_features(self, request: FeatureStoreRequest) -> int:
        entity_key_str = str(sorted(request.entity_key.items()))
        stored = 0

        for feature_value in request.features:
            key = f"{request.entity_id}:{entity_key_str}:{feature_value.feature_name}"
            self._feature_store[key][feature_value.feature_name] = feature_value

            if feature_value.timestamp:
                self._ttl_index[key] = feature_value.timestamp

            stat_key = f"{request.entity_id}:{feature_value.feature_name}"
            if self._stats[stat_key].feature_name != feature_value.feature_name:
                self._stats[stat_key] = FeatureOnlineStats(feature_name=feature_value.feature_name)
            self._stats[stat_key].write_count += 1
            self._stats[stat_key].last_write_at = datetime.utcnow()

            stored += 1

        logger.debug(f"Stored {stored} features for entity {request.entity_id}")
        return stored

    async def lookup_features(self, request: FeatureLookupRequest) -> List[FeatureValue]:
        entity_key_str = str(sorted(request.entity_key.items()))
        results: List[FeatureValue] = []

        for feature_name in request.feature_names:
            key = f"{request.entity_id}:{entity_key_str}:{feature_name}"
            feature = self._feature_store[key].get(feature_name)

            if feature:
                results.append(feature)

                stat_key = f"{request.entity_id}:{feature_name}"
                if self._stats[stat_key].feature_name != feature_name:
                    self._stats[stat_key] = FeatureOnlineStats(feature_name=feature_name)
                self._stats[stat_key].read_count += 1
                self._stats[stat_key].last_read_at = datetime.utcnow()

        return results

    async def get_feature_stats(self, feature_name: str) -> FeatureOnlineStats:
        for stat in self._stats.values():
            if stat.feature_name == feature_name:
                return stat
        return FeatureOnlineStats(feature_name=feature_name)

    async def cleanup_expired(self) -> int:
        now = datetime.utcnow()
        cleaned = 0

        for key, timestamp in list(self._ttl_index.items()):
            if timestamp and timestamp + timedelta(hours=24) < now:
                entity_id, entity_key_str, feature_name = key.split(":", 2)
                if feature_name in self._feature_store[key]:
                    del self._feature_store[key][feature_name]
                    cleaned += 1
                del self._ttl_index[key]

        if cleaned > 0:
            logger.info(f"Cleaned up {cleaned} expired features")
        return cleaned
