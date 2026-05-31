from typing import Dict, List, Optional
from datetime import datetime
from .types import FeatureDefinition, FeatureEntity
from src.core import ValidationError, NotFoundError, generate_id
import logging

logger = logging.getLogger(__name__)


class FeatureRegistry:
    def __init__(self):
        self._entities: Dict[str, FeatureEntity] = {}
        self._features: Dict[str, FeatureDefinition] = {}
        self._feature_by_name: Dict[str, List[FeatureDefinition]] = {}
        self._feature_sets: Dict[str, List[str]] = {}

    async def register_entity(self, entity: FeatureEntity) -> FeatureEntity:
        logger.info(f"Registering entity: {entity.name}")
        if entity.name in self._entities:
            raise ValidationError(f"Entity already exists: {entity.name}")
        self._entities[entity.name] = entity
        return entity

    async def get_entity(self, name: str) -> FeatureEntity:
        entity = self._entities.get(name)
        if not entity:
            raise NotFoundError(f"Entity not found: {name}")
        return entity

    async def list_entities(self) -> List[FeatureEntity]:
        return list(self._entities.values())

    async def register_feature(self, feature: FeatureDefinition) -> FeatureDefinition:
        logger.info(f"Registering feature: {feature.name}, entity={feature.entity}")

        if feature.entity not in self._entities:
            raise ValidationError(f"Entity not found: {feature.entity}")

        if feature.feature_type == "vector" and feature.dimensions is None:
            raise ValidationError("Vector features require dimensions specification")

        feature.feature_id = feature.feature_id or generate_id("feat")
        feature.created_at = datetime.utcnow()
        feature.updated_at = feature.created_at

        self._features[feature.feature_id] = feature

        if feature.name not in self._feature_by_name:
            self._feature_by_name[feature.name] = []
        self._feature_by_name[feature.name].append(feature)

        return feature

    async def get_feature(self, feature_id: str) -> FeatureDefinition:
        feature = self._features.get(feature_id)
        if not feature:
            raise NotFoundError(f"Feature not found: {feature_id}")
        return feature

    async def get_feature_by_name(self, name: str, version: Optional[int] = None) -> FeatureDefinition:
        versions = self._feature_by_name.get(name, [])
        if not versions:
            raise NotFoundError(f"Feature not found: {name}")

        if version is not None:
            for f in versions:
                if f.version == version:
                    return f
            raise NotFoundError(f"Feature version not found: {name} v{version}")

        return max(versions, key=lambda f: f.version)

    async def list_features(self, entity: Optional[str] = None) -> List[FeatureDefinition]:
        features = list(self._features.values())
        if entity:
            features = [f for f in features if f.entity == entity]
        return features

    async def update_feature(self, feature_id: str, updates: Dict[str, any]) -> FeatureDefinition:
        feature = await self.get_feature(feature_id)
        for key, value in updates.items():
            if hasattr(feature, key):
                setattr(feature, key, value)
        feature.updated_at = datetime.utcnow()
        self._features[feature_id] = feature
        return feature

    async def create_feature_set(self, name: str, features: List[str], description: str = "") -> str:
        set_id = generate_id("fset")
        self._feature_sets[set_id] = features
        logger.info(f"Created feature set {name} (id={set_id}) with {len(features)} features")
        return set_id

    async def get_feature_set(self, set_id: str) -> List[str]:
        features = self._feature_sets.get(set_id)
        if not features:
            raise NotFoundError(f"Feature set not found: {set_id}")
        return features
