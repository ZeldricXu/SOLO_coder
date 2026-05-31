import os
import tempfile
from pathlib import Path

import pytest

from app.storage.manager import (
    LocalStorage,
    FileMetadata,
    StoredFile,
    LifecycleRule,
    get_storage_manager,
    upload_file,
    download_file,
    delete_file
)


@pytest.fixture
def storage_manager():
    with tempfile.TemporaryDirectory() as tmpdir:
        manager = LocalStorage(base_dir=tmpdir)
        yield manager


def test_local_storage_upload_download(storage_manager):
    content = b"Hello, World!"
    meta = storage_manager.upload("test.txt", content, "text/plain")

    assert meta.original_name == "test.txt"
    assert meta.content_type == "text/plain"
    assert meta.size == len(content)

    stored = storage_manager.download(meta.file_id)
    assert stored is not None
    assert stored.content == content
    assert stored.metadata.file_id == meta.file_id


def test_local_storage_delete(storage_manager):
    meta = storage_manager.upload("delete_me.txt", b"content", "text/plain")

    assert storage_manager.exists(meta.file_id)

    success = storage_manager.delete(meta.file_id)
    assert success is True

    assert not storage_manager.exists(meta.file_id)
    assert storage_manager.download(meta.file_id) is None


def test_local_storage_list(storage_manager):
    for i in range(3):
        storage_manager.upload(f"file{i}.txt", b"content", "text/plain")

    files = storage_manager.list_files()
    assert len(files) == 3


def test_local_storage_bucket(storage_manager):
    meta = storage_manager.upload(
        "bucket_test.txt",
        b"content",
        "text/plain",
        bucket="custom_bucket"
    )

    assert storage_manager.exists(meta.file_id, "custom_bucket")
    assert not storage_manager.exists(meta.file_id)


def test_file_metadata():
    meta = FileMetadata(
        file_id="test_id",
        original_name="test.txt",
        size=100,
        content_type="text/plain",
        md5_hash="abc123",
        bucket="default"
    )

    assert meta.file_id == "test_id"
    assert meta.original_name == "test.txt"
    assert meta.size == 100


def test_stored_file():
    meta = FileMetadata(
        file_id="test",
        original_name="test.txt",
        size=5,
        content_type="text/plain",
        md5_hash="hash",
        bucket="default"
    )

    stored = StoredFile(content=b"hello", metadata=meta)
    assert stored.content == b"hello"
    assert stored.metadata == meta


def test_lifecycle_rule():
    rule = LifecycleRule(
        name="archive_rule",
        action="archive",
        min_age_days=30,
        prefixes=["old/"]
    )

    assert rule.name == "archive_rule"
    assert rule.action == "archive"
    assert rule.min_age_days == 30


def test_async_functions():
    import asyncio

    async def test():
        meta = await upload_file(
            file_name="async_test.txt",
            content=b"async content",
            content_type="text/plain"
        )
        assert meta is not None

        stored = await download_file(meta.file_id)
        assert stored is not None
        assert stored.content == b"async content"

        success = await delete_file(meta.file_id)
        assert success is True

    asyncio.run(test())


def test_md5_hash():
    manager = get_storage_manager()
    content = b"test content"

    meta = manager.upload("md5_test.txt", content, "text/plain")
    assert meta.md5_hash is not None
    assert len(meta.md5_hash) == 32


def test_ttl():
    manager = get_storage_manager()

    meta = manager.upload(
        "ttl_test.txt",
        b"content",
        "text/plain",
        ttl_days=7
    )

    assert meta.expires_at is not None


def test_list_buckets():
    manager = get_storage_manager()

    manager.upload("b1.txt", b"content", "text/plain", bucket="bucket1")
    manager.upload("b2.txt", b"content", "text/plain", bucket="bucket2")

    buckets = manager.list_buckets()
    assert "bucket1" in buckets
    assert "bucket2" in buckets
