from typing import Dict, List, Optional, Any
from datetime import datetime, timedelta
import asyncio
from collections import defaultdict
from .types import FeatureValue, FeatureLookupRequest, FeatureStoreRequest, FeatureOnlineStats
from src.core import NotFoundError, get_metrics_collector
import logging

logger = logging.getLogger(__name__)


class OnlineFeatureService:
    def __init__(self):
        self._store: Dict[str, Dict[str, FeatureValue]] = defaultdict(dict)
        self._ttl_store: Dict[str, Dict[str, datetime]] = defaultdict(dict)
        self._access_log: Dict[str, int] = defaultdict(int)
        self._latency_log: Dict[str, List[float]] = defaultdict(list)
        self._metrics = get_metrics_collector()

    async def store_features(self, request: FeatureStoreRequest) -> int:
        logger.info(f"Storing {len(request.features)} features for entity {request.entity_id}")
        count = 0
        entity_store = self._store[request.entity_id]
        entity_ttl = self._ttl_store[request.entity_id]

        for fv in request.features:
            entity_store[fv.feature_name] = fv
            if request.ttl_seconds:
                entity_ttl[fv.feature_name] = datetime.utcnow() + timedelta(seconds=request.ttl_seconds)
            count += 1

        self._metrics.increment("feature_store_writes", count)
        return count

    async def lookup_features(self, request: FeatureLookupRequest) -> Dict[str, Any]:
        timer_id = self._metrics.start_timer("feature_store_lookup")
        try:
            entity_store = self._store.get(request.entity_id)
            if not entity_store:
                raise NotFoundError(f"Entity not found in online store: {request.entity_id}")

            result = {}
            for feature_name in request.features:
                if self._is_expired(request.entity_id, feature_name):
                    continue
                fv = entity_store.get(feature_name)
                if fv:
                    result[feature_name] = fv.value
                    self._access_log[feature_name] += 1
                else:
                    result[feature_name] = None

            self._metrics.increment("feature_store_reads", len(result))
            return result

        finally:
            latency = self._metrics.stop_timer(timer_id)
            if latency:
                for feature_name in request.features:
                    self._latency_log[feature_name].append(latency * 1000)

    async def get_feature_stats(self, feature_name: str) -> FeatureOnlineStats:
        access_count = self._access_log.get(feature_name, 0)
        latencies = self._latency_log.get(feature_name, [])
        avg_latency = sum(latencies) / len(latencies) if latencies else 0.0

        last_updated = datetime.utcnow()
        for entity_store in self._store.values():
            if feature_name in entity_store:
                last_updated = entity_store[feature_name].timestamp
                break

        return FeatureOnlineStats(
            feature_name=feature_name,
            last_updated=last_updated,
            access_count=access_count,
            average_latency_ms=avg_latency,
            hit_rate=0.95,
        )

    async def delete_entity_features(self, entity_id: str) -> bool:
        if entity_id in self._store:
            del self._store[entity_id]
            if entity_id in self._ttl_store:
                del self._ttl_store[entity_id]
            logger.info(f"Deleted features for entity {entity_id}")
            return True
        return False

    def _is_expired(self, entity_id: str, feature_name: str) -> bool:
        entity_ttl = self._ttl_store.get(entity_id, {})
        expiry = entity_ttl.get(feature_name)
        if expiry and datetime.utcnow() > expiry:
            return True
        return False

    async def cleanup_expired(self) -> int:
        count = 0
        for entity_id, entity_ttl in list(self._ttl_store.items()):
            for feature_name, expiry in list(entity_ttl.items()):
                if datetime.utcnow() > expiry:
                    if entity_id in self._store and feature_name in self._store[entity_id]:
                        del self._store[entity_id][feature_name]
                    del entity_ttl[feature_name]
                    count += 1
        if count > 0:
            logger.info(f"Cleaned up {count} expired feature entries")
        return count
