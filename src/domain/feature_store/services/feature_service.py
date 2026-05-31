from typing import Dict, Any, Optional, List
from datetime import datetime

from ..models import (
    FeatureEntity,
    FeatureDefinition,
    FeatureLookupRequest,
    FeatureStoreRequest,
    HistoricalLookupRequest,
    FeatureOnlineStats,
)
from ..interfaces import (
    FeatureRegistryPort,
    OnlineFeatureServicePort,
    OfflineFeatureServicePort,
    ConsistencyCheckerPort,
    FeatureStoreServicePort,
)
from ..impl.registry import FeatureRegistry
from ..impl.online_service import OnlineFeatureService
from ..impl.offline_service import OfflineFeatureService
from ..impl.consistency import ConsistencyChecker
from ..impl.batch_processor import BatchProcessor

from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class FeatureStoreService(FeatureStoreServicePort):
    def __init__(
        self,
        registry: Optional[FeatureRegistryPort] = None,
        online_service: Optional[OnlineFeatureServicePort] = None,
        offline_service: Optional[OfflineFeatureServicePort] = None,
        consistency_checker: Optional[ConsistencyCheckerPort] = None,
        batch_processor: Optional[BatchProcessor] = None,
    ):
        self.registry: FeatureRegistryPort = registry or FeatureRegistry()
        self.online_service: OnlineFeatureServicePort = online_service or OnlineFeatureService()
        self.offline_service: OfflineFeatureServicePort = offline_service or OfflineFeatureService()
        self.consistency_checker: ConsistencyCheckerPort = consistency_checker or ConsistencyChecker(
            self.online_service, self.offline_service
        )
        self.batch_processor: BatchProcessor = batch_processor or BatchProcessor(
            max_batch_size=100,
            max_wait_ms=50,
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

    async def batch_store_features(
        self,
        requests: List[FeatureStoreRequest],
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="batch_store_features"):
            self._metrics.increment("feature_store_batch_writes")
            timer_id = self._metrics.start_timer("feature_store_batch")

            try:
                total_count = 0
                results = []

                for request in requests:
                    count = await self.online_service.store_features(request)
                    await self.offline_service.ingest_features(request.entity_id, request.features)
                    total_count += count
                    results.append({"entity_id": request.entity_id, "stored_count": count})

                emit_event(
                    "feature.batch_stored",
                    {"batch_size": len(requests), "total_count": total_count},
                    source="feature_store",
                )

                return {
                    "batch_size": len(requests),
                    "total_stored": total_count,
                    "results": results,
                }
            except Exception as e:
                self._metrics.increment("feature_store_batch_error")
                logger.error(f"Failed batch store: {e}")
                raise PlatformError(f"批量特征存储失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    async def batch_lookup_features(
        self,
        requests: List[FeatureLookupRequest],
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="batch_lookup_features"):
            self._metrics.increment("feature_store_batch_reads")
            timer_id = self._metrics.start_timer("feature_store_batch_lookup")

            try:
                results = []
                for request in requests:
                    features = await self.online_service.lookup_features(request)
                    results.append({
                        "entity_id": request.entity_id,
                        "features": features,
                    })

                return {
                    "batch_size": len(requests),
                    "results": results,
                }
            except Exception as e:
                self._metrics.increment("feature_store_batch_error")
                logger.error(f"Failed batch lookup: {e}")
                raise PlatformError(f"批量特征查询失败: {str(e)}")
            finally:
                self._metrics.stop_timer(timer_id)

    async def lookup_features_merged(
        self,
        request: FeatureLookupRequest,
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="lookup_features_merged"):
            self._metrics.increment("feature_store_merged_reads")

            async def process_batch(items: List[FeatureLookupRequest]) -> List[Dict[str, Any]]:
                results = []
                for item in items:
                    features = await self.online_service.lookup_features(item)
                    results.append({"entity_id": item.entity_id, "features": features})
                return results

            batch_key = f"lookup:{request.entity_id}"
            result = await self.batch_processor.add_request(
                batch_key,
                request,
                process_batch,
            )

            return result

    async def store_features_merged(
        self,
        request: FeatureStoreRequest,
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        with init_context(trace_id, operation="store_features_merged"):
            self._metrics.increment("feature_store_merged_writes")

            async def process_batch(items: List[FeatureStoreRequest]) -> List[Dict[str, Any]]:
                results = []
                for item in items:
                    count = await self.online_service.store_features(item)
                    await self.offline_service.ingest_features(item.entity_id, item.features)
                    results.append({"entity_id": item.entity_id, "stored_count": count})
                return results

            batch_key = f"store:{request.entity_id}"
            result = await self.batch_processor.add_request(
                batch_key,
                request,
                process_batch,
            )

            return result

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

    def get_batch_stats(self) -> Dict[str, Any]:
        return self.batch_processor.get_stats()
