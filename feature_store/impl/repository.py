from typing import List, Dict, Any, Optional

from ..protocols import FeatureGroupRepository
from ..schemas import (
    FeatureRegistrationRequest,
    FeatureRegistrationResponse,
    FeatureGroupInfo,
    FeatureSchema,
)
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class InMemoryFeatureGroupRepository(FeatureGroupRepository):
    def __init__(self):
        self.feature_groups: Dict[str, Dict[str, Any]] = {}
        self.entity_index: Dict[str, str] = {}
        self.registration_history: List[Dict[str, Any]] = []

    async def register(self, request: FeatureRegistrationRequest) -> FeatureRegistrationResponse:
        entity_name = request.entity.entity_name
        version = request.version

        group_id = f"fg_{entity_name}_{version}_{generate_id()[8:]}"
        existing_key = f"{entity_name}_{version}"

        if existing_key in self.entity_index:
            logger.warning(f"Feature group for {entity_name} v{version} already exists, updating")

        feature_group = {
            "feature_group_id": group_id,
            "entity_name": entity_name,
            "entity_id_field": request.entity.entity_id_field,
            "version": version,
            "features": [f.model_dump() for f in request.entity.features],
            "storage_tier": request.storage_tier,
            "ttl_seconds": request.ttl_seconds,
            "tags": request.tags or {},
            "owner": request.owner,
            "registered_at": utc_now(),
            "last_updated_at": utc_now(),
            "status": "active",
        }

        self.feature_groups[group_id] = feature_group
        self.entity_index[existing_key] = group_id
        self.registration_history.append(feature_group)

        logger.info(f"Registered feature group {group_id} for {entity_name} v{version}")

        return FeatureRegistrationResponse(
            feature_group_id=group_id,
            entity_name=entity_name,
            version=version,
            status="registered",
            registered_at=utc_now(),
            message=f"特征组 {group_id} 注册成功",
        )

    def get_by_entity(self, entity_name: str) -> Optional[Dict[str, Any]]:
        for existing_key, group_id in self.entity_index.items():
            if existing_key.startswith(f"{entity_name}_"):
                group = self.feature_groups.get(group_id)
                if group and group["status"] == "active":
                    return group
        return None

    def list_all(self, entity_name: Optional[str] = None) -> List[FeatureGroupInfo]:
        groups = []
        for group in self.feature_groups.values():
            if entity_name and group["entity_name"] != entity_name:
                continue

            groups.append(
                FeatureGroupInfo(
                    feature_group_id=group["feature_group_id"],
                    entity_name=group["entity_name"],
                    version=group["version"],
                    storage_tier=group["storage_tier"],
                    features=[FeatureSchema(**f) for f in group["features"]],
                    ttl_seconds=group.get("ttl_seconds"),
                    tags=group.get("tags"),
                    owner=group.get("owner"),
                    registered_at=group["registered_at"],
                    last_updated_at=group["last_updated_at"],
                    status=group["status"],
                )
            )
        return groups

    def update_timestamp(self, entity_name: str) -> None:
        group = self.get_by_entity(entity_name)
        if group:
            group["last_updated_at"] = utc_now()
