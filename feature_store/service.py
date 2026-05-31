from typing import List, Optional
from datetime import datetime

from .schemas import (
    FeatureRegistrationRequest,
    FeatureRegistrationResponse,
    FeatureOnlineGetRequest,
    FeatureOnlineGetResponse,
    FeatureOfflineFetchRequest,
    FeatureOfflineFetchResponse,
    FeatureIngestRequest,
    FeatureIngestResponse,
    ConsistencyCheckRequest,
    ConsistencyCheckResponse,
    FeatureValue,
    FeaturePoint,
    FeatureGroupInfo,
    StorageTier,
)
from .protocols import (
    FeatureGroupRepository,
    OnlineFeatureStore,
    OfflineFeatureStore,
    FeatureValidator,
    ConsistencyChecker,
)
from .impl import (
    InMemoryFeatureGroupRepository,
    InMemoryOnlineFeatureStore,
    InMemoryOfflineFeatureStore,
    DefaultFeatureValidator,
)
from .impl.consistency import DefaultConsistencyChecker
from common.logger import get_logger

logger = get_logger(__name__)


class FeatureStoreService:
    def __init__(
        self,
        group_repository: Optional[FeatureGroupRepository] = None,
        online_store: Optional[OnlineFeatureStore] = None,
        offline_store: Optional[OfflineFeatureStore] = None,
        validator: Optional[FeatureValidator] = None,
        consistency_checker: Optional[ConsistencyChecker] = None,
    ):
        self.group_repository = group_repository or InMemoryFeatureGroupRepository()
        self.online_store = online_store or InMemoryOnlineFeatureStore()
        self.offline_store = offline_store or InMemoryOfflineFeatureStore()
        self.validator = validator or DefaultFeatureValidator()
        self.consistency_checker = consistency_checker or DefaultConsistencyChecker(
            self.online_store, self.offline_store
        )

    async def register_feature_group(
        self, request: FeatureRegistrationRequest
    ) -> FeatureRegistrationResponse:
        self.validator.validate_feature_definitions(request.entity.features)
        return await self.group_repository.register(request)

    async def get_online_features(
        self, request: FeatureOnlineGetRequest
    ) -> FeatureOnlineGetResponse:
        group = self.group_repository.get_by_entity(request.entity_name)
        if not group:
            raise ValueError(f"Entity {request.entity_name} not found in feature store")

        valid_feature_names = {f["name"] for f in group["features"]}
        if request.feature_names:
            invalid = set(request.feature_names) - valid_feature_names
            if invalid:
                raise ValueError(f"Invalid features: {invalid}")

        request._valid_feature_names = list(valid_feature_names)
        return await self.online_store.get_features(request)

    async def ingest_features(self, request: FeatureIngestRequest) -> FeatureIngestResponse:
        entity_name = request.entity_name
        group = self.group_repository.get_by_entity(entity_name)
        if not group:
            raise ValueError(f"Entity {entity_name} not registered")

        valid_features = {f["name"]: f["type"] for f in group["features"]}
        entity_key = self._get_entity_key(entity_name)
        storage_tier = group.get("storage_tier", StorageTier.BOTH)

        ingested_count = 0
        failed_count = 0
        errors: List[str] = []

        for point in request.points:
            try:
                validated_features = self.validator.validate_feature_values(
                    point.features, valid_features
                )
                validated_point = FeaturePoint(
                    entity_id=point.entity_id,
                    features=validated_features,
                    event_timestamp=point.event_timestamp,
                )

                if storage_tier in [StorageTier.ONLINE, StorageTier.BOTH]:
                    await self.online_store.ingest(
                        entity_key, point.entity_id, validated_features
                    )

                if storage_tier in [StorageTier.OFFLINE, StorageTier.BOTH]:
                    await self.offline_store.ingest(entity_key, validated_point)

                ingested_count += 1
            except Exception as e:
                failed_count += 1
                errors.append(f"Entity {point.entity_id}: {str(e)}")

        self.group_repository.update_timestamp(entity_name)

        logger.info(
            f"Ingested {ingested_count} features for {entity_name}, {failed_count} failed"
        )

        return FeatureIngestResponse(
            entity_name=entity_name,
            ingested_count=ingested_count,
            failed_count=failed_count,
            errors=errors,
        )

    async def fetch_offline_features(
        self, request: FeatureOfflineFetchRequest
    ) -> FeatureOfflineFetchResponse:
        group = self.group_repository.get_by_entity(request.entity_name)
        if not group:
            raise ValueError(f"Entity {request.entity_name} not registered")
        return await self.offline_store.fetch_features(request)

    async def check_consistency(
        self, request: ConsistencyCheckRequest
    ) -> ConsistencyCheckResponse:
        group = self.group_repository.get_by_entity(request.entity_name)
        if not group:
            raise ValueError(f"Entity {request.entity_name} not registered")
        return await self.consistency_checker.check(request)

    def list_feature_groups(self, entity_name: Optional[str] = None) -> List[FeatureGroupInfo]:
        return self.group_repository.list_all(entity_name)

    @staticmethod
    def _get_entity_key(entity_name: str) -> str:
        return f"entity:{entity_name}"


feature_store_service = FeatureStoreService()
