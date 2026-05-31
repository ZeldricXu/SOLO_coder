from typing import Optional, List, Dict, Any, Tuple, BinaryIO
from uuid import UUID
from datetime import datetime, timezone, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, func
import hashlib
import mimetypes
import io

from app.models import StorageObject, StorageMetadata
from app.schemas import (
    StorageObjectCreate,
    StorageMetadataCreate,
    PresignedUrlRequest,
    PresignedUrlResponse,
)
from app.exceptions import NotFoundError, ConflictError, ValidationError
from app.logging import get_logger
from app.config import settings

logger = get_logger(__name__)


class StorageService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self._s3_client = None

    async def _get_s3_client(self):
        if self._s3_client is None:
            try:
                import aiobotocore.session

                session = aiobotocore.session.get_session()
                self._s3_client = session.create_client(
                    "s3",
                    endpoint_url=settings.s3_endpoint_url,
                    aws_access_key_id=settings.s3_access_key_id,
                    aws_secret_access_key=settings.s3_secret_access_key,
                    region_name=settings.s3_region_name,
                )
            except ImportError:
                logger.warning("aiobotocore not available, using mock storage")
                self._s3_client = None
        return self._s3_client

    async def upload_object(
        self,
        bucket: str,
        key: str,
        data: bytes,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[Dict[str, str]] = None,
    ) -> StorageObject:
        if not content_type:
            content_type, _ = mimetypes.guess_type(key)
            content_type = content_type or "application/octet-stream"

        checksum = hashlib.sha256(data).hexdigest()
        size_bytes = len(data)

        stmt = select(StorageObject).where(
            and_(
                StorageObject.bucket == bucket,
                StorageObject.key == key,
            )
        )
        result = await self.db.execute(stmt)
        existing = result.scalar_one_or_none()

        if existing:
            existing.size_bytes = size_bytes
            existing.content_type = content_type
            existing.checksum = checksum
            existing.meta_data = metadata or {}
            existing.tags = tags or {}
            storage_obj = existing
        else:
            storage_obj = StorageObject(
                bucket=bucket,
                key=key,
                size_bytes=size_bytes,
                content_type=content_type,
                checksum=checksum,
                meta_data=metadata or {},
                tags=tags or {},
            )
            self.db.add(storage_obj)

        s3_client = await self._get_s3_client()
        if s3_client:
            await s3_client.put_object(
                Bucket=bucket,
                Key=key,
                Body=data,
                ContentType=content_type,
                Metadata=metadata or {},
            )
        else:
            logger.debug("Mock upload", bucket=bucket, key=key, size=size_bytes)

        await self.db.commit()
        await self.db.refresh(storage_obj)

        logger.info(
            "Object uploaded",
            storage_id=str(storage_obj.id),
            bucket=bucket,
            key=key,
            size_bytes=size_bytes,
        )
        return storage_obj

    async def download_object(self, bucket: str, key: str) -> Tuple[bytes, StorageObject]:
        stmt = select(StorageObject).where(
            and_(
                StorageObject.bucket == bucket,
                StorageObject.key == key,
            )
        )
        result = await self.db.execute(stmt)
        storage_obj = result.scalar_one_or_none()

        if not storage_obj:
            raise NotFoundError(f"Object {key} not found in bucket {bucket}")

        storage_obj.last_accessed_at = datetime.now(timezone.utc)
        storage_obj.access_count += 1
        await self.db.commit()

        s3_client = await self._get_s3_client()
        if s3_client:
            response = await s3_client.get_object(Bucket=bucket, Key=key)
            data = await response["Body"].read()
        else:
            logger.debug("Mock download", bucket=bucket, key=key)
            data = b"mock_data"

        logger.debug(
            "Object downloaded",
            storage_id=str(storage_obj.id),
            bucket=bucket,
            key=key,
            size_bytes=storage_obj.size_bytes,
        )
        return data, storage_obj

    async def get_object(self, object_id: UUID) -> StorageObject:
        stmt = select(StorageObject).where(StorageObject.id == object_id)
        result = await self.db.execute(stmt)
        storage_obj = result.scalar_one_or_none()

        if not storage_obj:
            raise NotFoundError(f"Storage object {object_id} not found")

        return storage_obj

    async def list_objects(
        self,
        bucket: str,
        prefix: Optional[str] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[StorageObject], int]:
        stmt = select(StorageObject).where(StorageObject.bucket == bucket)

        if prefix:
            stmt = stmt.where(StorageObject.key.startswith(prefix))

        count_stmt = select(func.count(StorageObject.id)).where(StorageObject.bucket == bucket)
        if prefix:
            count_stmt = count_stmt.where(StorageObject.key.startswith(prefix))

        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(StorageObject.key.asc())
        result = await self.db.execute(stmt)
        objects = result.scalars().all()

        return list(objects), total

    async def delete_object(self, bucket: str, key: str) -> None:
        stmt = select(StorageObject).where(
            and_(
                StorageObject.bucket == bucket,
                StorageObject.key == key,
            )
        )
        result = await self.db.execute(stmt)
        storage_obj = result.scalar_one_or_none()

        if not storage_obj:
            raise NotFoundError(f"Object {key} not found in bucket {bucket}")

        s3_client = await self._get_s3_client()
        if s3_client:
            await s3_client.delete_object(Bucket=bucket, Key=key)

        await self.db.delete(storage_obj)
        await self.db.commit()

        logger.info("Object deleted", bucket=bucket, key=key)

    async def create_presigned_url(
        self, request: PresignedUrlRequest
    ) -> PresignedUrlResponse:
        s3_client = await self._get_s3_client()
        if s3_client:
            url = await s3_client.generate_presigned_url(
                f"{request.operation}_object",
                Params={
                    "Bucket": request.bucket,
                    "Key": request.key,
                    **({"VersionId": request.version_id} if request.version_id else {}),
                },
                ExpiresIn=request.expires_in,
            )
        else:
            url = f"https://{request.bucket}.s3.amazonaws.com/{request.key}?expires={request.expires_in}"

        return PresignedUrlResponse(
            url=url,
            expires_in=request.expires_in,
            operation=request.operation,
        )

    async def add_metadata(self, metadata_in: StorageMetadataCreate) -> StorageMetadata:
        storage_obj = await self.get_object(metadata_in.storage_object_id)

        metadata = StorageMetadata(
            storage_object_id=metadata_in.storage_object_id,
            key=metadata_in.key,
            value=metadata_in.value,
            data_type=metadata_in.data_type,
            is_searchable=metadata_in.is_searchable,
        )
        self.db.add(metadata)
        await self.db.commit()
        await self.db.refresh(metadata)

        logger.info(
            "Metadata added",
            storage_object_id=str(metadata_in.storage_object_id),
            key=metadata_in.key,
        )
        return metadata

    async def get_object_metadata(self, object_id: UUID) -> List[StorageMetadata]:
        stmt = select(StorageMetadata).where(
            StorageMetadata.storage_object_id == object_id
        )
        result = await self.db.execute(stmt)
        return list(result.scalars().all())

    async def search_by_metadata(
        self,
        key: str,
        value: Any,
        bucket: Optional[str] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[StorageObject], int]:
        stmt = (
            select(StorageObject)
            .join(
                StorageMetadata,
                StorageMetadata.storage_object_id == StorageObject.id,
            )
            .where(
                and_(
                    StorageMetadata.key == key,
                    StorageMetadata.value.astext == str(value),
                    StorageMetadata.is_searchable == True,
                )
            )
        )

        if bucket:
            stmt = stmt.where(StorageObject.bucket == bucket)

        count_stmt = select(func.count()).select_from(stmt.subquery())
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(StorageObject.created_at.desc())
        result = await self.db.execute(stmt)
        objects = result.scalars().all()

        return list(objects), total

    async def archive_object(self, object_id: UUID) -> StorageObject:
        storage_obj = await self.get_object(object_id)
        storage_obj.is_archived = True
        storage_obj.storage_class = "glacier"

        s3_client = await self._get_s3_client()
        if s3_client:
            await s3_client.restore_object(
                Bucket=storage_obj.bucket,
                Key=storage_obj.key,
                RestoreRequest={"Days": 30, "GlacierJobParameters": {"Tier": "Standard"}},
            )

        await self.db.commit()
        await self.db.refresh(storage_obj)

        logger.info("Object archived", object_id=str(object_id))
        return storage_obj

    async def restore_object(self, object_id: UUID) -> StorageObject:
        storage_obj = await self.get_object(object_id)
        storage_obj.is_archived = False
        storage_obj.storage_class = "standard"

        await self.db.commit()
        await self.db.refresh(storage_obj)

        logger.info("Object restored", object_id=str(object_id))
        return storage_obj

    async def get_storage_stats(self, bucket: str) -> Dict[str, Any]:
        stmt = select(
            func.count(StorageObject.id).label("object_count"),
            func.sum(StorageObject.size_bytes).label("total_size"),
            func.sum(
                func.case([(StorageObject.is_archived == True, 1)], else_=0)
            ).label("archived_count"),
        ).where(StorageObject.bucket == bucket)

        result = await self.db.execute(stmt)
        row = result.one()

        return {
            "bucket": bucket,
            "object_count": row.object_count or 0,
            "total_size_bytes": row.total_size or 0,
            "archived_count": row.archived_count or 0,
        }
