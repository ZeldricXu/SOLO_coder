from typing import List, Dict, Any, Optional
from datetime import datetime
from collections import defaultdict
from ..models import FeatureValue, HistoricalLookupRequest
from ..interfaces import OfflineFeatureServicePort
from src.core import PlatformError
import logging

logger = logging.getLogger(__name__)


class OfflineFeatureService(OfflineFeatureServicePort):
    def __init__(self):
        self._historical_data: Dict[str, List[FeatureValue]] = defaultdict(list)

    async def ingest_features(self, entity_id: str, features: List[FeatureValue]) -> int:
        for feature in features:
            key = f"{entity_id}:{feature.feature_name}"
            self._historical_data[key].append(feature)
            self._historical_data[key].sort(key=lambda x: x.timestamp)

            if len(self._historical_data[key]) > 10000:
                self._historical_data[key] = self._historical_data[key][-10000:]

        logger.debug(f"Ingested {len(features)} features for offline storage")
        return len(features)

    async def historical_lookup(self, request: HistoricalLookupRequest) -> List[Dict[str, Any]]:
        entity_key_str = str(sorted(request.entity_key.items()))
        results: List[Dict[str, Any]] = []

        for feature_name in request.feature_names:
            key = f"{request.entity_id}:{feature_name}"
            history = self._historical_data.get(key, [])

            filtered = [
                f for f in history
                if request.start_time <= f.timestamp <= request.end_time
            ]

            for fv in filtered:
                results.append({
                    "feature_name": fv.feature_name,
                    "value": fv.value,
                    "timestamp": fv.timestamp.isoformat(),
                })

        logger.debug(f"Historical lookup returned {len(results)} records")
        return results

    async def get_point_in_time(
        self, entity_id: str, features: List[str], point_in_time: datetime
    ) -> List[FeatureValue]:
        results: List[FeatureValue] = []

        for feature_name in features:
            key = f"{entity_id}:{feature_name}"
            history = self._historical_data.get(key, [])

            filtered = [f for f in history if f.timestamp <= point_in_time]
            if filtered:
                latest = max(filtered, key=lambda x: x.timestamp)
                results.append(latest)

        logger.debug(f"Point-in-time lookup returned {len(results)} features at {point_in_time}")
        return results
