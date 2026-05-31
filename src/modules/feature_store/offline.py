from typing import Dict, List, Any, Optional
from datetime import datetime
from collections import defaultdict
from .types import FeatureValue, HistoricalLookupRequest
from src.core import NotFoundError
import logging

logger = logging.getLogger(__name__)


class OfflineFeatureService:
    def __init__(self):
        self._historical_store: Dict[str, Dict[str, List[FeatureValue]]] = defaultdict(
            lambda: defaultdict(list)
        )

    async def ingest_features(self, entity_id: str, features: List[FeatureValue]) -> int:
        logger.info(f"Ingesting {len(features)} historical features for entity {entity_id}")
        entity_history = self._historical_store[entity_id]
        count = 0

        for fv in features:
            entity_history[fv.feature_name].append(fv)
            entity_history[fv.feature_name].sort(key=lambda x: x.timestamp)
            count += 1

        return count

    async def historical_lookup(self, request: HistoricalLookupRequest) -> Dict[str, List[Dict[str, Any]]]:
        logger.info(
            f"Historical lookup for {len(request.entity_ids)} entities, "
            f"{len(request.features)} features from {request.start_time}"
        )
        result = {}

        end_time = request.end_time or datetime.utcnow()
        for entity_id in request.entity_ids:
            entity_history = self._historical_store.get(entity_id)
            if not entity_history:
                result[entity_id] = []
                continue

            entity_result = []
            for feature_name in request.features:
                history = entity_history.get(feature_name, [])
                filtered = [
                    fv for fv in history
                    if request.start_time <= fv.timestamp <= end_time
                ]
                for fv in filtered:
                    entity_result.append({
                        "feature_name": feature_name,
                        "value": fv.value,
                        "timestamp": fv.timestamp,
                        "event_timestamp": fv.event_timestamp,
                    })

            result[entity_id] = entity_result

        return result

    async def get_point_in_time(
        self,
        entity_id: str,
        features: List[str],
        point_in_time: datetime,
    ) -> Dict[str, Any]:
        logger.info(f"Point-in-time lookup for {entity_id} at {point_in_time}")
        entity_history = self._historical_store.get(entity_id)
        if not entity_history:
            raise NotFoundError(f"Entity not found in offline store: {entity_id}")

        result = {}
        for feature_name in features:
            history = entity_history.get(feature_name, [])
            prior_values = [fv for fv in history if fv.timestamp <= point_in_time]
            if prior_values:
                result[feature_name] = prior_values[-1].value
            else:
                result[feature_name] = None

        return result

    async def list_entity_features(self, entity_id: str) -> List[str]:
        entity_history = self._historical_store.get(entity_id, {})
        return list(entity_history.keys())

    async def get_feature_history(
        self,
        entity_id: str,
        feature_name: str,
        limit: int = 100,
    ) -> List[FeatureValue]:
        entity_history = self._historical_store.get(entity_id, {})
        history = entity_history.get(feature_name, [])
        return history[-limit:] if limit > 0 else history
