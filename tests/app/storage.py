from __future__ import annotations

import os
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from .exceptions import ValidationError, NotFoundError, DatabaseError, StorageLimitExceededError

STORAGE_CLASS_STANDARD = "standard"
STORAGE_CLASS_IA = "infrequent_access"
STORAGE_CLASS_ARCHIVE = "archive"
VALID_STORAGE_CLASSES = {STORAGE_CLASS_STANDARD, STORAGE_CLASS_IA, STORAGE_CLASS_ARCHIVE}

@dataclass
class StoredFile:
    id: str
    name: str
    path: str
    size: int
    content_type: str
    storage_class: str = STORAGE_CLASS_STANDARD
    expire_at: Optional[datetime] = None
    last_accessed: datetime = field(default_factory=datetime.utcnow)
    created_at: datetime = field(default_factory=datetime.utcnow)

class StorageManager:
    def __init__(self, base_path: str, db_session=None, max_storage_bytes: int = 10 * 1024 * 1024 * 1024):
        self.base_path = base_path
        self.db_session = db_session
        self.max_storage_bytes = max_storage_bytes
        self._files: Dict[str, StoredFile] = {}
        self._storage_used: int = 0

        if base_path:
            os.makedirs(base_path, exist_ok=True)

    def _validate_file_data(self, name: str, content: bytes, content_type: str, ttl: Optional[timedelta]) -> None:
        if not name or not isinstance(name, str):
            raise ValidationError("name", "File name is required")
        if len(name) > 255:
            raise ValidationError("name", "File name must be less than 255 characters")
        if "/" in name or "\\" in name:
            raise ValidationError("name", "File name cannot contain path separators")

        if content is None:
            raise ValidationError("content", "Content cannot be None")
        if not isinstance(content, (bytes, bytearray)):
            raise ValidationError("content", "Content must be bytes")
        if len(content) == 0:
            raise ValidationError("content", "Content cannot be empty")

        if not content_type or not isinstance(content_type, str):
            raise ValidationError("content_type", "Content type is required")

        if ttl is not None and not isinstance(ttl, timedelta):
            raise ValidationError("ttl", "TTL must be a timedelta")
        if ttl is not None and ttl.total_seconds() < 60:
            raise ValidationError("ttl", "TTL must be at least 60 seconds")

    def _check_storage_limit(self, additional_bytes: int) -> None:
        if self._storage_used + additional_bytes > self.max_storage_bytes:
            raise StorageLimitExceededError(
                limit=self.max_storage_bytes,
                current=self._storage_used + additional_bytes,
            )

    def store_file(self, name: str, content: bytes, content_type: str, ttl: Optional[timedelta] = None) -> StoredFile:
        self._validate_file_data(name, content, content_type, ttl)
        self._check_storage_limit(len(content))

        file_id = str(uuid.uuid4())
        rel_path = os.path.join(file_id[:2], file_id[2:4], file_id)
        full_path = os.path.join(self.base_path, rel_path) if self.base_path else rel_path

        if self.base_path:
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            try:
                with open(full_path, "wb") as f:
                    f.write(content)
            except IOError as e:
                raise DatabaseError("write file", e)

        stored_file = StoredFile(
            id=file_id,
            name=name,
            path=rel_path,
            size=len(content),
            content_type=content_type,
        )

        if ttl:
            stored_file.expire_at = datetime.utcnow() + ttl

        if self.db_session:
            try:
                self.db_session.add(stored_file)
                self.db_session.commit()
            except Exception as e:
                if self.base_path and os.path.exists(full_path):
                    os.remove(full_path)
                self.db_session.rollback()
                raise DatabaseError("store file metadata", e)

        self._files[file_id] = stored_file
        self._storage_used += stored_file.size
        return stored_file

    def get_file(self, file_id: str) -> tuple[StoredFile, bytes]:
        stored_file = self._get_file_metadata(file_id)
        stored_file.last_accessed = datetime.utcnow()

        content = b""
        if self.base_path:
            full_path = os.path.join(self.base_path, stored_file.path)
            try:
                with open(full_path, "rb") as f:
                    content = f.read()
            except IOError as e:
                raise DatabaseError("read file", e)

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update last accessed", e)

        return stored_file, content

    def _get_file_metadata(self, file_id: str) -> StoredFile:
        stored_file = self._files.get(file_id)
        if not stored_file:
            raise NotFoundError("StoredFile", file_id)
        return stored_file

    def delete_file(self, file_id: str) -> None:
        stored_file = self._get_file_metadata(file_id)

        if self.base_path:
            full_path = os.path.join(self.base_path, stored_file.path)
            try:
                if os.path.exists(full_path):
                    os.remove(full_path)
            except IOError as e:
                raise DatabaseError("delete file", e)

        if self.db_session:
            try:
                self.db_session.delete(stored_file)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("delete file metadata", e)

        del self._files[file_id]
        self._storage_used -= stored_file.size

    def list_files(self, prefix: Optional[str] = None, storage_class: Optional[str] = None, limit: int = 100) -> List[StoredFile]:
        files = list(self._files.values())

        if prefix:
            files = [f for f in files if f.name.startswith(prefix)]

        if storage_class:
            if storage_class not in VALID_STORAGE_CLASSES:
                raise ValidationError("storage_class", f"Invalid storage class: {storage_class}")
            files = [f for f in files if f.storage_class == storage_class]

        return sorted(files, key=lambda f: f.created_at, reverse=True)[:limit]

    def update_ttl(self, file_id: str, ttl: timedelta) -> None:
        if not isinstance(ttl, timedelta):
            raise ValidationError("ttl", "TTL must be a timedelta")
        if ttl.total_seconds() < 60:
            raise ValidationError("ttl", "TTL must be at least 60 seconds")

        stored_file = self._get_file_metadata(file_id)
        stored_file.expire_at = datetime.utcnow() + ttl
        stored_file.last_accessed = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update TTL", e)

    def update_storage_class(self, file_id: str, storage_class: str) -> None:
        if storage_class not in VALID_STORAGE_CLASSES:
            raise ValidationError("storage_class", f"Invalid storage class: {storage_class}")

        stored_file = self._get_file_metadata(file_id)
        stored_file.storage_class = storage_class
        stored_file.last_accessed = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update storage class", e)

    def get_storage_stats(self) -> Dict[str, Any]:
        stats = {
            "total_files": len(self._files),
            "total_bytes": self._storage_used,
            "max_bytes": self.max_storage_bytes,
            "free_bytes": self.max_storage_bytes - self._storage_used,
            "usage_percent": round((self._storage_used / self.max_storage_bytes) * 100, 2) if self.max_storage_bytes > 0 else 0,
            "by_class": {},
        }

        for cls in VALID_STORAGE_CLASSES:
            class_files = [f for f in self._files.values() if f.storage_class == cls]
            stats["by_class"][cls] = {
                "count": len(class_files),
                "bytes": sum(f.size for f in class_files),
            }

        return stats

    def collect_expired(self) -> List[str]:
        now = datetime.utcnow()
        expired_ids = [
            file_id for file_id, f in self._files.items()
            if f.expire_at is not None and f.expire_at < now
        ]

        for file_id in expired_ids:
            try:
                self.delete_file(file_id)
            except Exception:
                pass

        return expired_ids

    def _store_file_without_db(self, name: str, content: bytes, content_type: str, ttl: Optional[timedelta] = None) -> StoredFile:
        file_id = str(uuid.uuid4())
        rel_path = os.path.join(file_id[:2], file_id[2:4], file_id)

        stored_file = StoredFile(
            id=file_id,
            name=name,
            path=rel_path,
            size=len(content),
            content_type=content_type,
        )

        if ttl:
            stored_file.expire_at = datetime.utcnow() + ttl

        return stored_file

    def transition_storage_classes(self) -> int:
        now = datetime.utcnow()
        ia_threshold = timedelta(days=30)
        archive_threshold = timedelta(days=90)
        transitioned = 0

        for file_id, stored_file in self._files.items():
            age = now - stored_file.last_accessed
            if stored_file.storage_class == STORAGE_CLASS_STANDARD and age > ia_threshold:
                stored_file.storage_class = STORAGE_CLASS_IA
                transitioned += 1
            elif stored_file.storage_class == STORAGE_CLASS_IA and age > archive_threshold:
                stored_file.storage_class = STORAGE_CLASS_ARCHIVE
                transitioned += 1

        if self.db_session and transitioned > 0:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("transition storage classes", e)

        return transitioned
