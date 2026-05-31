from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional
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
)


class FeatureGroupRepository(ABC):
    @abstractmethod
    async def register(self, request: FeatureRegistrationRequest) -> FeatureRegistrationResponse:
        pass

    @abstractmethod
    def get_by_entity(self, entity_name: str) -> Optional[Dict[str, Any]]:
        pass

    @abstractmethod
    def list_all(self, entity_name: Optional[str] = None) -> List[FeatureGroupInfo]:
        pass


class OnlineFeatureStore(ABC):
    @abstractmethod
    async def get_features(self, request: FeatureOnlineGetRequest) -> FeatureOnlineGetResponse:
        pass

    @abstractmethod
    async def ingest(self, entity_key: str, entity_id: str, features: List[FeatureValue]) -> None:
        pass

    @abstractmethod
    def get_entity_values(self, entity_key: str, entity_id: str) -> Dict[str, Any]:
        pass


class OfflineFeatureStore(ABC):
    @abstractmethod
    async def fetch_features(self, request: FeatureOfflineFetchRequest) -> FeatureOfflineFetchResponse:
        pass

    @abstractmethod
    async def ingest(self, entity_key: str, point: FeaturePoint) -> None:
        pass

    @abstractmethod
    def get_entity_points(self, entity_key: str, entity_id: str) -> List[FeaturePoint]:
        pass


class FeatureValidator(ABC):
    @abstractmethod
    def validate_feature_definitions(self, features: List[Any]) -> None:
        pass

    @abstractmethod
    def validate_feature_values(
        self, features: List[FeatureValue], valid_features: Dict[str, str]
    ) -> List[FeatureValue]:
        pass

    @abstractmethod
    def check_type(self, value: Any, expected_type: str) -> bool:
        pass


class ConsistencyChecker(ABC):
    @abstractmethod
    async def check(self, request: ConsistencyCheckRequest) -> ConsistencyCheckResponse:
        pass

    @abstractmethod
    def values_equal(self, v1: Any, v2: Any) -> bool:
        pass


class TransactionManager(ABC):
    @abstractmethod
    async def begin(self) -> None:
        pass

    @abstractmethod
    async def commit(self) -> None:
        pass

    @abstractmethod
    async def rollback(self) -> None:
        pass
