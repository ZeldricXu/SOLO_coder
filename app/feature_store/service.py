from typing import Optional, List, Dict, Any, Tuple
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_
from sqlalchemy.orm import selectinload
import json
import time
import traceback

from app.models import Feature, FeatureVersion
from app.schemas import FeatureCreate, FeatureUpdate, FeatureVersionCreate, FeatureDataBatch
from app.exceptions import (
    NotFoundError,
    ConflictError,
    ValidationError,
    TransactionFailedError,
)
from app.logging import get_logger, LogContext
from app.utils import calculate_checksum

logger = get_logger(__name__)


class FeatureStoreService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_feature(self, feature_in: FeatureCreate) -> Feature:
        operation_id = f"create_feature_{feature_in.namespace}_{feature_in.name}"

        try:
            stmt = select(Feature).where(
                and_(
                    Feature.namespace == feature_in.namespace,
                    Feature.name == feature_in.name,
                )
            )
            result = await self.db.execute(stmt)
            existing = result.scalar_one_or_none()

            if existing:
                raise ConflictError(
                    f"Feature '{feature_in.name}' already exists in namespace '{feature_in.namespace}'",
                    details={
                        "feature_id": str(existing.id),
                        "namespace": feature_in.namespace,
                        "name": feature_in.name,
                    },
                )

            feature = Feature(
                name=feature_in.name,
                namespace=feature_in.namespace,
                description=feature_in.description,
                entity_type=feature_in.entity_type,
                value_type=feature_in.value_type,
                is_online=feature_in.is_online,
                is_offline=feature_in.is_offline,
                ttl_seconds=feature_in.ttl_seconds,
                schema_definition=feature_in.schema_definition,
                metadata=feature_in.metadata,
            )

            self.db.add(feature)
            await self.db.flush()

            initial_version = FeatureVersion(
                feature_id=feature.id,
                version=1,
                metadata={"initial": True},
            )
            self.db.add(initial_version)

            await self.db.commit()
            await self.db.refresh(feature)

            logger.info(
                "Feature created successfully",
                feature_id=str(feature.id),
                name=feature.name,
                namespace=feature.namespace,
                operation_id=operation_id,
                version=1,
            )
            return feature

        except ConflictError:
            raise
        except Exception as e:
            await self.db.rollback()
            error_msg = f"Failed to create feature: {str(e)}"
            logger.error(
                error_msg,
                exc_info=e,
                operation_id=operation_id,
                namespace=feature_in.namespace,
                name=feature_in.name,
                error_type=type(e).__name__,
            )
            raise TransactionFailedError(
                error_msg,
                details={
                    "operation": "create_feature",
                    "operation_id": operation_id,
                    "namespace": feature_in.namespace,
                    "name": feature_in.name,
                    "error_type": type(e).__name__,
                    "traceback": traceback.format_exc(),
                },
            ) from e

    async def get_feature(self, feature_id: UUID, include_versions: bool = True) -> Feature:
        stmt = select(Feature).where(Feature.id == feature_id)
        if include_versions:
            stmt = stmt.options(selectinload(Feature.versions))

        result = await self.db.execute(stmt)
        feature = result.scalar_one_or_none()

        if not feature:
            raise NotFoundError(
                f"Feature {feature_id} not found",
                details={"feature_id": str(feature_id)},
            )

        return feature

    async def list_features(
        self,
        namespace: Optional[str] = None,
        entity_type: Optional[str] = None,
        name_pattern: Optional[str] = None,
        is_online: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[Feature], int]:
        stmt = select(Feature)
        conditions = []

        if namespace:
            conditions.append(Feature.namespace == namespace)
        if entity_type:
            conditions.append(Feature.entity_type == entity_type)
        if name_pattern:
            conditions.append(Feature.name.ilike(f"%{name_pattern}%"))
        if is_online is not None:
            conditions.append(Feature.is_online == is_online)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = select(Feature.id).where(and_(*conditions)) if conditions else select(Feature.id)
        count_result = await self.db.execute(count_stmt)
        total = len(count_result.scalars().all())

        stmt = stmt.offset(skip).limit(limit).order_by(Feature.created_at.desc())
        result = await self.db.execute(stmt)
        features = result.scalars().all()

        return list(features), total

    async def update_feature(self, feature_id: UUID, feature_in: FeatureUpdate) -> Feature:
        operation_id = f"update_feature_{feature_id}"

        try:
            feature = await self.get_feature(feature_id, include_versions=False)

            update_data = feature_in.model_dump(exclude_unset=True)
            updated_fields = list(update_data.keys())

            for field, value in update_data.items():
                setattr(feature, field, value)

            await self.db.commit()
            await self.db.refresh(feature)

            logger.info(
                "Feature updated successfully",
                feature_id=str(feature_id),
                updated_fields=updated_fields,
                operation_id=operation_id,
            )
            return feature

        except NotFoundError:
            raise
        except Exception as e:
            await self.db.rollback()
            error_msg = f"Failed to update feature {feature_id}: {str(e)}"
            logger.error(
                error_msg,
                exc_info=e,
                operation_id=operation_id,
                feature_id=str(feature_id),
                error_type=type(e).__name__,
            )
            raise TransactionFailedError(
                error_msg,
                details={
                    "operation": "update_feature",
                    "operation_id": operation_id,
                    "feature_id": str(feature_id),
                    "error_type": type(e).__name__,
                    "traceback": traceback.format_exc(),
                },
            ) from e

    async def delete_feature(self, feature_id: UUID) -> None:
        operation_id = f"delete_feature_{feature_id}"

        try:
            feature = await self.get_feature(feature_id, include_versions=False)
            await self.db.delete(feature)
            await self.db.commit()

            logger.info(
                "Feature deleted successfully",
                feature_id=str(feature_id),
                operation_id=operation_id,
                name=feature.name,
                namespace=feature.namespace,
            )
        except NotFoundError:
            raise
        except Exception as e:
            await self.db.rollback()
            error_msg = f"Failed to delete feature {feature_id}: {str(e)}"
            logger.error(
                error_msg,
                exc_info=e,
                operation_id=operation_id,
                feature_id=str(feature_id),
                error_type=type(e).__name__,
            )
            raise TransactionFailedError(
                error_msg,
                details={
                    "operation": "delete_feature",
                    "operation_id": operation_id,
                    "feature_id": str(feature_id),
                    "error_type": type(e).__name__,
                    "traceback": traceback.format_exc(),
                },
            ) from e

    async def create_version(self, version_in: FeatureVersionCreate) -> FeatureVersion:
        operation_id = f"create_version_{version_in.feature_id}"

        try:
            feature = await self.get_feature(version_in.feature_id, include_versions=True)

            max_version = max((v.version for v in feature.versions), default=0)
            new_version = max_version + 1

            checksum = None
            if version_in.transformation_logic:
                checksum = calculate_checksum(json.dumps(version_in.transformation_logic, sort_keys=True))

            feature_version = FeatureVersion(
                feature_id=version_in.feature_id,
                version=new_version,
                data_source=version_in.data_source,
                transformation_logic=version_in.transformation_logic,
                checksum=checksum,
                meta_data=version_in.metadata,
            )

            self.db.add(feature_version)
            await self.db.commit()
            await self.db.refresh(feature_version)

            logger.info(
                "Feature version created successfully",
                feature_id=str(version_in.feature_id),
                version=new_version,
                operation_id=operation_id,
                checksum=checksum,
            )
            return feature_version

        except NotFoundError:
            raise
        except Exception as e:
            await self.db.rollback()
            error_msg = f"Failed to create version for feature {version_in.feature_id}: {str(e)}"
            logger.error(
                error_msg,
                exc_info=e,
                operation_id=operation_id,
                feature_id=str(version_in.feature_id),
                error_type=type(e).__name__,
            )
            raise TransactionFailedError(
                error_msg,
                details={
                    "operation": "create_version",
                    "operation_id": operation_id,
                    "feature_id": str(version_in.feature_id),
                    "error_type": type(e).__name__,
                    "traceback": traceback.format_exc(),
                },
            ) from e

    async def get_online_features(
        self,
        entity_ids: List[str],
        feature_names: List[str],
        namespace: str,
    ) -> Dict[str, Dict[str, Any]]:
        if not entity_ids:
            raise ValidationError(
                "entity_ids cannot be empty",
                details={"field": "entity_ids", "value": entity_ids},
            )
        if not feature_names:
            raise ValidationError(
                "feature_names cannot be empty",
                details={"field": "feature_names", "value": feature_names},
            )

        stmt = select(Feature).where(
            and_(
                Feature.namespace == namespace,
                Feature.name.in_(feature_names),
                Feature.is_online == True,
            )
        )
        result = await self.db.execute(stmt)
        features = result.scalars().all()

        if len(features) != len(feature_names):
            found_names = {f.name for f in features}
            missing = set(feature_names) - found_names
            raise ValidationError(
                f"Features not found or not online: {missing}",
                details={
                    "namespace": namespace,
                    "requested_features": feature_names,
                    "found_features": list(found_names),
                    "missing_features": list(missing),
                },
            )

        results = {}
        for entity_id in entity_ids:
            entity_data = {}
            for feature in features:
                entity_data[feature.name] = self._get_feature_value(feature, entity_id)
            results[entity_id] = entity_data

        logger.debug(
            "Online features fetched",
            entity_count=len(entity_ids),
            feature_count=len(features),
            namespace=namespace,
        )
        return results

    def _get_feature_value(self, feature: Feature, entity_id: str) -> Any:
        return {
            "value": f"mock_value_{entity_id}_{feature.name}",
            "timestamp": int(time.time()),
            "version": 1,
        }

    async def get_offline_features(
        self,
        entity_ids: List[str],
        feature_names: List[str],
        namespace: str,
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        if not entity_ids:
            raise ValidationError(
                "entity_ids cannot be empty",
                details={"field": "entity_ids", "value": entity_ids},
            )
        if not feature_names:
            raise ValidationError(
                "feature_names cannot be empty",
                details={"field": "feature_names", "value": feature_names},
            )

        stmt = select(Feature).where(
            and_(
                Feature.namespace == namespace,
                Feature.name.in_(feature_names),
                Feature.is_offline == True,
            )
        )
        result = await self.db.execute(stmt)
        features = result.scalars().all()

        if len(features) != len(feature_names):
            found_names = {f.name for f in features}
            missing = set(feature_names) - found_names
            raise ValidationError(
                f"Features not found or not offline: {missing}",
                details={
                    "namespace": namespace,
                    "requested_features": feature_names,
                    "found_features": list(found_names),
                    "missing_features": list(missing),
                },
            )

        results = []
        for entity_id in entity_ids:
            for feature in features:
                results.append(
                    {
                        "entity_id": entity_id,
                        "feature_name": feature.name,
                        "value": f"offline_value_{entity_id}_{feature.name}",
                        "timestamp": start_time or int(time.time()) - 86400,
                        "version": 1,
                    }
                )

        logger.debug(
            "Offline features fetched",
            entity_count=len(entity_ids),
            feature_count=len(features),
            namespace=namespace,
        )
        return results

    async def batch_get_features(self, batch_in: FeatureDataBatch) -> Dict[str, Any]:
        return await self.get_online_features(
            entity_ids=batch_in.entity_ids,
            feature_names=batch_in.feature_names,
            namespace=batch_in.namespace,
        )

    async def check_consistency(
        self,
        feature_id: UUID,
        entity_ids: List[str],
    ) -> Dict[str, Any]:
        if not entity_ids:
            raise ValidationError(
                "entity_ids cannot be empty",
                details={"field": "entity_ids", "value": entity_ids},
            )

        feature = await self.get_feature(feature_id, include_versions=False)

        try:
            online_result = await self.get_online_features(
                entity_ids=entity_ids,
                feature_names=[feature.name],
                namespace=feature.namespace,
            )
            offline_result = await self.get_offline_features(
                entity_ids=entity_ids,
                feature_names=[feature.name],
                namespace=feature.namespace,
            )
        except ValidationError:
            raise
        except Exception as e:
            error_msg = f"Failed to perform consistency check: {str(e)}"
            logger.error(
                error_msg,
                exc_info=e,
                feature_id=str(feature_id),
                entity_count=len(entity_ids),
                error_type=type(e).__name__,
            )
            raise TransactionFailedError(
                error_msg,
                details={
                    "operation": "check_consistency",
                    "feature_id": str(feature_id),
                    "entity_count": len(entity_ids),
                    "error_type": type(e).__name__,
                    "traceback": traceback.format_exc(),
                },
            ) from e

        inconsistencies = []
        for entity_id in entity_ids:
            online_val = online_result.get(entity_id, {}).get(feature.name, {}).get("value")
            offline_vals = [
                r["value"] for r in offline_result if r["entity_id"] == entity_id
            ]
            if offline_vals and online_val != offline_vals[-1]:
                inconsistencies.append(
                    {
                        "entity_id": entity_id,
                        "online_value": online_val,
                        "offline_value": offline_vals[-1],
                    }
                )

        return {
            "feature_id": str(feature_id),
            "feature_name": feature.name,
            "total_entities": len(entity_ids),
            "inconsistent_count": len(inconsistencies),
            "inconsistencies": inconsistencies,
        }
