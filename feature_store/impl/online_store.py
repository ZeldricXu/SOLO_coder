from typing import List, Dict, Any
from collections import defaultdict

from ..protocols import OnlineFeatureStore
from ..schemas import (
    FeatureOnlineGetRequest,
    FeatureOnlineGetResponse,
    FeatureValue,
    FeaturePoint,
    StorageTier,
)
from common.logger import get_logger

logger = get_logger(__name__)


class InMemoryOnlineFeatureStore(OnlineFeatureStore):
    def __init__(self):
        self.store: Dict[str, Dict[str, Dict[str, FeatureValue]]] = defaultdict(
            lambda: defaultdict(dict)
        )

    async def get_features(self, request: FeatureOnlineGetRequest) -> FeatureOnlineGetResponse:
        entity_name = request.entity_name
        entity_ids = request.entity_ids
        feature_names = request.feature_names

        valid_feature_names = set(request._valid_feature_names)
        if feature_names:
            feature_names = [f for f in feature_names if f in valid_feature_names]
        else:
            feature_names = list(valid_feature_names)

        results: Dict[str, List[FeatureValue]] = {}
        missing_ids: List[str] = []

        entity_key = self._get_entity_key(entity_name)
        store_data = self.store.get(entity_key, {})

        for entity_id in entity_ids:
            if entity_id in store_data:
                entity_features = store_data[entity_id]
                filtered = [
                    v for k, v in entity_features.items() if k in feature_names
                ]
                if filtered:
                    results[entity_id] = filtered
                else:
                    missing_ids.append(entity_id)
            else:
                missing_ids.append(entity_id)

        logger.info(
            f"Fetched online features for {entity_name}: {len(results)} found, {len(missing_ids)} missing"
        )

        return FeatureOnlineGetResponse(
            entity_name=entity_name,
            results=results,
            missing_entity_ids=missing_ids,
        )

    async def ingest(self, entity_key: str, entity_id: str, features: List[FeatureValue]) -> None:
        feature_dict = {fv.feature_name: fv for fv in features}
        self.store[entity_key][entity_id] = feature_dict

    def get_entity_values(self, entity_key: str, entity_id: str) -> Dict[str, Any]:
        entity_features = self.store.get(entity_key, {}).get(entity_id, {})
        return {name: fv.value for name, fv in entity_features.items()}

    @staticmethod
    def _get_entity_key(entity_name: str) -> str:
        return f"entity:{entity_name}"
