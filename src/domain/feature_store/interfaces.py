from typing import Protocol, List, Optional, Dict, Any, runtime_checkable
from abc import abstractmethod
from datetime import datetime

from .models import (
    FeatureEntity,
    FeatureDefinition,
    FeatureValue,
    FeatureLookupRequest,
    FeatureStoreRequest,
    HistoricalLookupRequest,
    FeatureOnlineStats,
    ConsistencyCheckResult,
)


@runtime_checkable
class FeatureRegistryPort(Protocol):
    @abstractmethod
    async def register_entity(self, entity: FeatureEntity) -> FeatureEntity:
        ...

    @abstractmethod
    async def register_feature(self, feature: FeatureDefinition) -> FeatureDefinition:
        ...

    @abstractmethod
    async def list_features(self, entity: Optional[str] = None) -> List[FeatureDefinition]:
        ...

    @abstractmethod
    async def list_entities(self) -> List[FeatureEntity]:
        ...


@runtime_checkable
class OnlineFeatureServicePort(Protocol):
    @abstractmethod
    async def store_features(self, request: FeatureStoreRequest) -> int:
        ...

    @abstractmethod
    async def lookup_features(self, request: FeatureLookupRequest) -> List[FeatureValue]:
        ...

    @abstractmethod
    async def get_feature_stats(self, feature_name: str) -> FeatureOnlineStats:
        ...

    @abstractmethod
    async def cleanup_expired(self) -> int:
        ...


@runtime_checkable
class OfflineFeatureServicePort(Protocol):
    @abstractmethod
    async def ingest_features(
        self, entity_id: str, features: List[FeatureValue]
    ) -> int:
        ...

    @abstractmethod
    async def historical_lookup(
        self, request: HistoricalLookupRequest
    ) -> List[Dict[str, Any]]:
        ...

    @abstractmethod
    async def get_point_in_time(
        self, entity_id: str, features: List[str], point_in_time: datetime
    ) -> List[FeatureValue]:
        ...


@runtime_checkable
class ConsistencyCheckerPort(Protocol):
    @abstractmethod
    async def check_all_features(
        self, entity_id: str, feature_names: List[str]
    ) -> List[ConsistencyCheckResult]:
        ...


@runtime_checkable
class FeatureStoreServicePort(Protocol):
    @abstractmethod
    async def register_entity(
        self, entity: FeatureEntity, trace_id: Optional[str] = None
    ) -> FeatureEntity:
        ...

    @abstractmethod
    async def register_feature(
        self, feature: FeatureDefinition, trace_id: Optional[str] = None
    ) -> FeatureDefinition:
        ...

    @abstractmethod
    async def store_features(
        self, request: FeatureStoreRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def lookup_features(
        self, request: FeatureLookupRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def historical_lookup(
        self, request: HistoricalLookupRequest, trace_id: Optional[str] = None
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def get_point_in_time(
        self,
        entity_id: str,
        features: List[str],
        point_in_time: datetime,
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def check_consistency(
        self,
        entity_id: str,
        feature_names: List[str],
        trace_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def get_feature_stats(
        self, feature_name: str, trace_id: Optional[str] = None
    ) -> FeatureOnlineStats:
        ...

    @abstractmethod
    async def list_features(
        self, entity: Optional[str] = None, trace_id: Optional[str] = None
    ) -> List[FeatureDefinition]:
        ...

    @abstractmethod
    async def list_entities(self, trace_id: Optional[str] = None) -> List[FeatureEntity]:
        ...

    @abstractmethod
    async def cleanup_expired(self, trace_id: Optional[str] = None) -> int:
        ...
