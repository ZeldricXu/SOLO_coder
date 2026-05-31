from typing import List, Dict, Any, Optional
from datetime import datetime
from ..models import FeatureEntity, FeatureDefinition
from ..interfaces import FeatureRegistryPort
from src.core import generate_id, PlatformError
import logging

logger = logging.getLogger(__name__)


class FeatureRegistry(FeatureRegistryPort):
    def __init__(self):
        self._entities: Dict[str, FeatureEntity] = {}
        self._features: Dict[str, FeatureDefinition] = {}
        self._entity_features: Dict[str, List[str]] = {}

    async def register_entity(self, entity: FeatureEntity) -> FeatureEntity:
        if entity.name in [e.name for e in self._entities.values()]:
            raise PlatformError(f"Entity with name '{entity.name}' already exists")

        entity_id = entity.entity_id or generate_id("ent")
        entity.entity_id = entity_id
        entity.created_at = entity.created_at or datetime.utcnow()
        self._entities[entity_id] = entity
        self._entity_features[entity_id] = []

        logger.info(f"Registered entity: {entity.name} (id={entity_id})")
        return entity

    async def register_feature(self, feature: FeatureDefinition) -> FeatureDefinition:
        if feature.entity_id not in self._entities:
            raise PlatformError(f"Entity '{feature.entity_id}' not found")

        feature_id = feature.feature_id or generate_id("feat")
        feature.feature_id = feature_id
        feature.created_at = feature.created_at or datetime.utcnow()
        self._features[feature_id] = feature
        self._entity_features[feature.entity_id].append(feature_id)

        logger.info(f"Registered feature: {feature.name} (id={feature_id})")
        return feature

    async def list_features(self, entity: Optional[str] = None) -> List[FeatureDefinition]:
        if entity:
            feature_ids = self._entity_features.get(entity, [])
            return [self._features[fid] for fid in feature_ids]
        return list(self._features.values())

    async def list_entities(self) -> List[FeatureEntity]:
        return list(self._entities.values())

    async def get_entity(self, entity_id: str) -> Optional[FeatureEntity]:
        return self._entities.get(entity_id)

    async def get_feature(self, feature_id: str) -> Optional[FeatureDefinition]:
        return self._features.get(feature_id)
