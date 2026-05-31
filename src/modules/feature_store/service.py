from typing import Dict, Any, Optional, List
from datetime import datetime
from .types import (
    FeatureEntity,
    FeatureDefinition,
    FeatureValue,
    FeatureLookupRequest,
    FeatureStoreRequest,
    HistoricalLookupRequest,
    FeatureOnlineStats,
    ConsistencyCheckResult,
    FeatureSet,
)
from .registry import FeatureRegistry
from .online import OnlineFeatureService
from .offline import OfflineFeatureService
from .consistency import ConsistencyChecker
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class FeatureStoreService:
    def __init__(
        self,
        registry: Optional[FeatureRegistry] = None,
        online_service: Optional[OnlineFeatureService] = None,
        offline_service: Optional[OfflineFeatureService] = None,
        consistency_checker: Optional[ConsistencyChecker] = None,
    ):
        self.registry = registry or FeatureRegistry()
        self.online_service = online_service or OnlineFeatureService()
        self.offline_service = offline_service or OfflineFeatureService()
        self.consistency_checker = consistency_checker or ConsistencyChecker(
            self.online_service, self.offline_service
        )
        self._metrics = get_metrics_collector()

    async def register_entity(self, entity: FeatureEntity, trace_id: Optional[str] = None) -> FeatureEntity:
        with init_context(trace_id, operation="register_entity"):
            try:
                result = await self.registry.register_entity(entity)
                emit_event(
                    "feature.entity.registered",
                    {"name": entity.name},
                    source="feature_store",
                )
                return result
            except Exception as e:
                logger.error(f"Failed to register entity: {e}")
                raise PlatformError(f"实体注册失败: {str(e)}")

    async def register_feature(self, feature: FeatureDefinition, trace_id: Optional[str] = None) -> FeatureDefinition:
        with init_context(trace_id, operation="register_feature"):
            try:
                result = await self.registry.register_feature(feature)
                emit_event(
                    "feature.registered",
                    {"feature_id": result.feature_id, "name": result.name},
                    source="feature_store",
                )
                return result
            except Exception as e:
                logger.error(f"Failed to register feature: {e}")
                raise PlatformError(f"特征注册失败: {str(e)}")

    async def store_features(self, request: FeatureStoreRequest, trace_id: Optional[str] = None) -> Dict[str, Any]:
        with init_context(trace_id, operation="store_features"):
            self._metrics.increment("feature_store_online_writes")
            try:
                count = await self.online_service.store_features(request)
                await self.offline_service.ingest_features(request.entity_id, request.features)

                emit_event(
                    "feature.stored",
                    {"entity_id": request.entity_id, "count": count},
                    source="feature_store",
                )
                return {"entity_id": request.entity_id, "stored_count": count}
            except Exception as e:
                self._metrics.increment("feature_store_write_error")
                logger.error(f"Failed to store features: {e}")
                raise PlatformError(f"特征存储失败: {str(e)}")

    async def lookup_features(self, request: FeatureLookupRequest, trace_id: Optional[str] = None) -> Dict[str, Any]:
        with init_context(trace_id, operation="lookup_features"):
            self._metrics.increment("feature_store_online_reads")
            try:
                result = await self.online_service.lookup_features(request)
                return {"entity_id": request.entity_id, "features": result}
            except Exception as e:
                self._metrics.increment("feature_store_read_error")
                logger.error(f"Failed to lookup features: {e}")
                raise PlatformError(f"特征查询失败: {str(e)}")

    async def historical_lookup(
        self,
        request: HistoricalLookupRequest,
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="historical_lookup"):
            self._metrics.increment("feature_store_offline_reads")
            try:
                result = await self.offline_service.historical_lookup(request)
                return {"data": result}
            except Exception as e:
                self._metrics.increment("feature_store_offline_error")
                logger.error(f"Failed historical lookup: {e}")
                raise PlatformError(f"离线特征回溯失败: {str(e)}")

    async def get_point_in_time(
        self,
        entity_id: str,
        features: List[str],
        point_in_time: datetime,
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="point_in_time_lookup"):
            try:
                result = await self.offline_service.get_point_in_time(
                    entity_id, features, point_in_time
                )
                return {"entity_id": entity_id, "timestamp": point_in_time, "features": result}
            except Exception as e:
                logger.error(f"Failed point-in-time lookup: {e}")
                raise PlatformError(f"时点特征查询失败: {str(e)}")

    async def check_consistency(
        self,
        entity_id: str,
        feature_names: List[str],
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="check_consistency"):
            try:
                results = await self.consistency_checker.check_all_features(entity_id, feature_names)
                inconsistent = [r for r in results if not r.is_consistent]
                return {
                    "entity_id": entity_id,
                    "total_checked": len(results),
                    "consistent_count": len(results) - len(inconsistent),
                    "inconsistent_count": len(inconsistent),
                    "details": results,
                }
            except Exception as e:
                logger.error(f"Failed consistency check: {e}")
                raise PlatformError(f"一致性检查失败: {str(e)}")

    async def get_feature_stats(self, feature_name: str, trace_id: Optional[str] = None) -> FeatureOnlineStats:
        with init_context(trace_id, operation="get_feature_stats"):
            return await self.online_service.get_feature_stats(feature_name)

    async def list_features(self, entity: Optional[str] = None, trace_id: Optional[str] = None) -> List[FeatureDefinition]:
        with init_context(trace_id, operation="list_features"):
            return await self.registry.list_features(entity)

    async def list_entities(self, trace_id: Optional[str] = None) -> List[FeatureEntity]:
        with init_context(trace_id, operation="list_entities"):
            return await self.registry.list_entities()

    async def cleanup_expired(self, trace_id: Optional[str] = None) -> int:
        with init_context(trace_id, operation="cleanup_expired"):
            return await self.online_service.cleanup_expired()
