import os
import pytest
from datetime import datetime, timedelta
from unittest import mock
import tempfile

from tests.app.exceptions import (
    ValidationError,
    NotFoundError,
    DatabaseError,
    StorageLimitExceededError,
)
from tests.app.storage import (
    StorageManager,
    STORAGE_CLASS_STANDARD,
    STORAGE_CLASS_IA,
    STORAGE_CLASS_ARCHIVE,
)
from tests.factories.data_factory import StorageFactory

pytestmark = pytest.mark.unit

class TestStorageStoreFile:
    @pytest.mark.validation
    def test_store_file_success(self, storage_manager):
        file_data = StorageFactory.create_file_data()
        stored_file = storage_manager.store_file(**file_data)

        assert stored_file.id is not None
        assert stored_file.name == file_data["name"]
        assert stored_file.size == len(file_data["content"])
        assert stored_file.content_type == file_data["content_type"]
        assert stored_file.storage_class == STORAGE_CLASS_STANDARD
        assert stored_file.expire_at is None
        assert isinstance(stored_file.created_at, datetime)
        assert isinstance(stored_file.last_accessed, datetime)

    def test_store_file_with_ttl(self, storage_manager):
        ttl = timedelta(hours=1)
        file_data = StorageFactory.create_file_data()
        stored_file = storage_manager.store_file(**file_data, ttl=ttl)

        assert stored_file.expire_at is not None
        assert stored_file.expire_at > datetime.utcnow()
        assert (stored_file.expire_at - datetime.utcnow()).total_seconds() <= 3600

    @pytest.mark.validation
    @pytest.mark.parametrize("invalid_data", StorageFactory.create_invalid_file_data())
    def test_store_file_validation_errors(self, storage_manager, invalid_data):
        with pytest.raises(ValidationError):
            storage_manager.store_file(**invalid_data)

    @pytest.mark.validation
    def test_store_file_ttl_too_short(self, storage_manager):
        file_data = StorageFactory.create_file_data()
        with pytest.raises(ValidationError):
            storage_manager.store_file(**file_data, ttl=timedelta(seconds=30))

    @pytest.mark.boundary
    def test_store_file_max_name_length(self, storage_manager):
        file_data = StorageFactory.create_file_data(name="x" * 255)
        stored_file = storage_manager.store_file(**file_data)
        assert len(stored_file.name) == 255

    @pytest.mark.boundary
    def test_store_file_small_content(self, storage_manager):
        file_data = StorageFactory.create_file_data(content=b"a", size=1)
        stored_file = storage_manager.store_file(**file_data)
        assert stored_file.size == 1

    @pytest.mark.boundary
    def test_store_file_storage_limit_exceeded(self, storage_manager_small_limit):
        file_data = StorageFactory.create_file_data(size=20 * 1024)
        with pytest.raises(StorageLimitExceededError):
            storage_manager_small_limit.store_file(**file_data)

    def test_store_file_updates_storage_used(self, storage_manager):
        initial_used = storage_manager._storage_used
        file_data = StorageFactory.create_file_data(size=1024)
        stored_file = storage_manager.store_file(**file_data)

        assert storage_manager._storage_used == initial_used + 1024

    @pytest.mark.transaction
    def test_store_file_with_db_success(self, storage_manager_with_db, mock_db_session):
        file_data = StorageFactory.create_file_data()
        stored_file = storage_manager_with_db.store_file(**file_data)

        mock_db_session.add.assert_called_once()
        mock_db_session.commit.assert_called_once()
        mock_db_session.rollback.assert_not_called()

    @pytest.mark.transaction
    def test_store_file_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            failing_session = mocker.MagicMock()
            failing_session.add = mocker.MagicMock()
            failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
            failing_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=failing_session)
            file_data = StorageFactory.create_file_data()

            with pytest.raises(DatabaseError) as exc_info:
                manager.store_file(**file_data)

            assert "store file metadata" in str(exc_info.value)
            failing_session.rollback.assert_called_once()

    @pytest.mark.transaction
    def test_store_file_cleanup_on_db_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            failing_session = mocker.MagicMock()
            failing_session.add = mocker.MagicMock()
            failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
            failing_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=failing_session)
            file_data = StorageFactory.create_file_data()

            try:
                manager.store_file(**file_data)
            except DatabaseError:
                pass

            files_in_dir = os.listdir(tmpdir)
            assert len(files_in_dir) == 0 or all(
                not os.path.isfile(os.path.join(tmpdir, f)) for f in files_in_dir
            )

class TestStorageGetFile:
    def test_get_file_success(self, storage_manager, sample_file):
        retrieved_file, content = storage_manager.get_file(sample_file.id)

        assert retrieved_file.id == sample_file.id
        assert retrieved_file.name == sample_file.name
        assert isinstance(content, bytes)
        assert len(content) == sample_file.size

    def test_get_file_updates_last_accessed(self, storage_manager, sample_file):
        original_access = sample_file.last_accessed
        retrieved_file, _ = storage_manager.get_file(sample_file.id)
        assert retrieved_file.last_accessed >= original_access

    def test_get_file_not_found(self, storage_manager):
        with pytest.raises(NotFoundError):
            storage_manager.get_file("non_existent")

    @pytest.mark.transaction
    def test_get_file_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            normal_session = mocker.MagicMock()
            normal_session.add = mocker.MagicMock()
            normal_session.commit = mocker.MagicMock()
            normal_session.delete = mocker.MagicMock()
            normal_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=normal_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager.store_file(**file_data)

            normal_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))

            with pytest.raises(DatabaseError):
                manager.get_file(stored_file.id)

            normal_session.rollback.assert_called_once()

class TestStorageDeleteFile:
    def test_delete_file_success(self, storage_manager, sample_file):
        storage_manager.delete_file(sample_file.id)

        with pytest.raises(NotFoundError):
            storage_manager.get_file(sample_file.id)

    def test_delete_file_updates_storage_used(self, storage_manager):
        file_data = StorageFactory.create_file_data(size=1024)
        stored_file = storage_manager.store_file(**file_data)
        initial_used = storage_manager._storage_used

        storage_manager.delete_file(stored_file.id)
        assert storage_manager._storage_used == initial_used - 1024

    def test_delete_file_not_found(self, storage_manager):
        with pytest.raises(NotFoundError):
            storage_manager.delete_file("non_existent")

    @pytest.mark.transaction
    def test_delete_file_with_db_success(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            mock_session = mocker.MagicMock()
            mock_session.add = mocker.MagicMock()
            mock_session.commit = mocker.MagicMock()
            mock_session.delete = mocker.MagicMock()
            mock_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=mock_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager.store_file(**file_data)

            mock_session.delete.reset_mock()
            mock_session.commit.reset_mock()

            manager.delete_file(stored_file.id)

            mock_session.delete.assert_called_once()
            mock_session.commit.assert_called_once()
            mock_session.rollback.assert_not_called()

    @pytest.mark.transaction
    def test_delete_file_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            failing_session = mocker.MagicMock()
            failing_session.delete = mocker.MagicMock(side_effect=Exception("DB error"))
            failing_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=failing_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager._store_file_without_db(**file_data)
            manager._files[stored_file.id] = stored_file
            manager._storage_used += stored_file.size

            with pytest.raises(DatabaseError):
                manager.delete_file(stored_file.id)

            failing_session.rollback.assert_called_once()
            assert stored_file.id in manager._files

class TestStorageListFiles:
    def test_list_files_empty(self, storage_manager):
        files = storage_manager.list_files()
        assert files == []

    def test_list_files_with_data(self, storage_manager):
        for _ in range(5):
            storage_manager.store_file(**StorageFactory.create_file_data())

        files = storage_manager.list_files()
        assert len(files) == 5

    def test_list_files_with_prefix(self, storage_manager):
        for i in range(3):
            storage_manager.store_file(**StorageFactory.create_file_data(name=f"report_{i}.txt"))
        for i in range(2):
            storage_manager.store_file(**StorageFactory.create_file_data(name=f"log_{i}.txt"))

        report_files = storage_manager.list_files(prefix="report_")
        assert len(report_files) == 3
        assert all(f.name.startswith("report_") for f in report_files)

    def test_list_files_with_storage_class(self, storage_manager):
        for i in range(3):
            stored_file = storage_manager.store_file(**StorageFactory.create_file_data())
            if i == 0:
                storage_manager.update_storage_class(stored_file.id, STORAGE_CLASS_IA)

        ia_files = storage_manager.list_files(storage_class=STORAGE_CLASS_IA)
        assert len(ia_files) == 1

    @pytest.mark.validation
    def test_list_files_invalid_storage_class(self, storage_manager):
        with pytest.raises(ValidationError):
            storage_manager.list_files(storage_class="invalid_class")

    @pytest.mark.boundary
    def test_list_files_pagination_limit(self, storage_manager):
        for i in range(150):
            storage_manager.store_file(**StorageFactory.create_file_data(name=f"file_{i}.txt"))

        files = storage_manager.list_files(limit=100)
        assert len(files) == 100

    @pytest.mark.boundary
    def test_list_files_ordered_by_created_at_desc(self, storage_manager):
        for i in range(5):
            storage_manager.store_file(**StorageFactory.create_file_data(name=f"file_{i}.txt"))

        files = storage_manager.list_files()
        names = [f.name for f in files]
        assert names == sorted(names, reverse=True)

class TestStorageTTL:
    def test_update_ttl_success(self, storage_manager, sample_file):
        new_ttl = timedelta(days=7)
        storage_manager.update_ttl(sample_file.id, new_ttl)

        retrieved_file, _ = storage_manager.get_file(sample_file.id)
        assert retrieved_file.expire_at is not None
        assert (retrieved_file.expire_at - datetime.utcnow()).total_seconds() <= 7 * 24 * 3600

    @pytest.mark.validation
    def test_update_ttl_invalid(self, storage_manager, sample_file):
        with pytest.raises(ValidationError):
            storage_manager.update_ttl(sample_file.id, "not a timedelta")

    @pytest.mark.validation
    def test_update_ttl_too_short(self, storage_manager, sample_file):
        with pytest.raises(ValidationError):
            storage_manager.update_ttl(sample_file.id, timedelta(seconds=30))

    def test_update_ttl_not_found(self, storage_manager):
        with pytest.raises(NotFoundError):
            storage_manager.update_ttl("non_existent", timedelta(hours=1))

    @pytest.mark.transaction
    def test_update_ttl_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            failing_session = mocker.MagicMock()
            failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
            failing_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=failing_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager._store_file_without_db(**file_data)
            manager._files[stored_file.id] = stored_file

            with pytest.raises(DatabaseError):
                manager.update_ttl(stored_file.id, timedelta(hours=1))

            failing_session.rollback.assert_called_once()

class TestStorageClass:
    def test_update_storage_class_success(self, storage_manager, sample_file):
        storage_manager.update_storage_class(sample_file.id, STORAGE_CLASS_IA)

        retrieved_file, _ = storage_manager.get_file(sample_file.id)
        assert retrieved_file.storage_class == STORAGE_CLASS_IA

    @pytest.mark.validation
    def test_update_storage_class_invalid(self, storage_manager, sample_file):
        with pytest.raises(ValidationError):
            storage_manager.update_storage_class(sample_file.id, "invalid_class")

    def test_update_storage_class_not_found(self, storage_manager):
        with pytest.raises(NotFoundError):
            storage_manager.update_storage_class("non_existent", STORAGE_CLASS_IA)

    @pytest.mark.parametrize("storage_class", [
        STORAGE_CLASS_STANDARD,
        STORAGE_CLASS_IA,
        STORAGE_CLASS_ARCHIVE,
    ])
    def test_update_storage_class_all_valid(self, storage_manager, sample_file, storage_class):
        storage_manager.update_storage_class(sample_file.id, storage_class)
        retrieved_file, _ = storage_manager.get_file(sample_file.id)
        assert retrieved_file.storage_class == storage_class

    @pytest.mark.transaction
    def test_update_storage_class_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            failing_session = mocker.MagicMock()
            failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
            failing_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=failing_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager._store_file_without_db(**file_data)
            manager._files[stored_file.id] = stored_file

            with pytest.raises(DatabaseError):
                manager.update_storage_class(stored_file.id, STORAGE_CLASS_IA)

            failing_session.rollback.assert_called_once()

class TestStorageStats:
    def test_get_storage_stats_empty(self, storage_manager):
        stats = storage_manager.get_storage_stats()

        assert stats["total_files"] == 0
        assert stats["total_bytes"] == 0
        assert stats["usage_percent"] == 0.0
        assert STORAGE_CLASS_STANDARD in stats["by_class"]
        assert STORAGE_CLASS_IA in stats["by_class"]
        assert STORAGE_CLASS_ARCHIVE in stats["by_class"]

    def test_get_storage_stats_with_files(self, storage_manager):
        for i in range(3):
            storage_manager.store_file(**StorageFactory.create_file_data(size=1024))

        stats = storage_manager.get_storage_stats()
        assert stats["total_files"] == 3
        assert stats["total_bytes"] == 3 * 1024
        assert stats["by_class"][STORAGE_CLASS_STANDARD]["count"] == 3

    def test_get_storage_stats_with_mixed_classes(self, storage_manager):
        for i in range(5):
            stored_file = storage_manager.store_file(**StorageFactory.create_file_data(size=1024))
            if i < 2:
                storage_manager.update_storage_class(stored_file.id, STORAGE_CLASS_IA)
            elif i < 4:
                storage_manager.update_storage_class(stored_file.id, STORAGE_CLASS_ARCHIVE)

        stats = storage_manager.get_storage_stats()
        assert stats["by_class"][STORAGE_CLASS_STANDARD]["count"] == 1
        assert stats["by_class"][STORAGE_CLASS_IA]["count"] == 2
        assert stats["by_class"][STORAGE_CLASS_ARCHIVE]["count"] == 2

    @pytest.mark.boundary
    def test_get_storage_stats_near_limit(self, storage_manager_small_limit):
        for _ in range(5):
            storage_manager_small_limit.store_file(**StorageFactory.create_file_data(size=1024))

        stats = storage_manager_small_limit.get_storage_stats()
        assert stats["total_bytes"] == 5 * 1024
        assert stats["usage_percent"] == 50.0

class TestStorageGC:
    def test_collect_expired_no_expired(self, storage_manager):
        for _ in range(3):
            storage_manager.store_file(**StorageFactory.create_file_data())

        expired = storage_manager.collect_expired()
        assert expired == []
        assert len(storage_manager._files) == 3

    def test_collect_expired_with_expired(self, storage_manager):
        for _ in range(2):
            storage_manager.store_file(**StorageFactory.create_file_data())

        file_data = StorageFactory.create_file_data()
        stored_file = storage_manager.store_file(**file_data, ttl=timedelta(minutes=60))
        stored_file.expire_at = datetime.utcnow() - timedelta(minutes=1)

        expired = storage_manager.collect_expired()
        assert len(expired) == 1
        assert expired[0] == stored_file.id
        assert len(storage_manager._files) == 2

    def test_collect_expired_updates_storage_used(self, storage_manager):
        file_data = StorageFactory.create_file_data(size=1024)
        stored_file = storage_manager.store_file(**file_data, ttl=timedelta(minutes=60))
        stored_file.expire_at = datetime.utcnow() - timedelta(minutes=1)
        initial_used = storage_manager._storage_used

        storage_manager.collect_expired()
        assert storage_manager._storage_used == initial_used - 1024

    def test_transition_storage_classes_no_transitions(self, storage_manager):
        for _ in range(3):
            storage_manager.store_file(**StorageFactory.create_file_data())

        transitioned = storage_manager.transition_storage_classes()
        assert transitioned == 0

    def test_transition_storage_classes_to_ia(self, storage_manager):
        stored_file = storage_manager.store_file(**StorageFactory.create_file_data())
        stored_file.last_accessed = datetime.utcnow() - timedelta(days=45)

        transitioned = storage_manager.transition_storage_classes()
        assert transitioned == 1
        assert stored_file.storage_class == STORAGE_CLASS_IA

    def test_transition_storage_classes_to_archive(self, storage_manager):
        stored_file = storage_manager.store_file(**StorageFactory.create_file_data())
        stored_file.storage_class = STORAGE_CLASS_IA
        stored_file.last_accessed = datetime.utcnow() - timedelta(days=100)

        transitioned = storage_manager.transition_storage_classes()
        assert transitioned == 1
        assert stored_file.storage_class == STORAGE_CLASS_ARCHIVE

    def test_transition_storage_classes_multiple(self, storage_manager):
        for i in range(5):
            stored_file = storage_manager.store_file(**StorageFactory.create_file_data())
            if i < 2:
                stored_file.last_accessed = datetime.utcnow() - timedelta(days=45)
            elif i < 4:
                stored_file.storage_class = STORAGE_CLASS_IA
                stored_file.last_accessed = datetime.utcnow() - timedelta(days=100)

        transitioned = storage_manager.transition_storage_classes()
        assert transitioned == 4

    @pytest.mark.transaction
    def test_transition_storage_classes_db_rollback_on_error(self, mocker):
        from tests.app.storage import StorageManager
        with tempfile.TemporaryDirectory() as tmpdir:
            normal_session = mocker.MagicMock()
            normal_session.add = mocker.MagicMock()
            normal_session.commit = mocker.MagicMock()
            normal_session.delete = mocker.MagicMock()
            normal_session.rollback = mocker.MagicMock()

            manager = StorageManager(base_path=tmpdir, db_session=normal_session)
            file_data = StorageFactory.create_file_data()
            stored_file = manager.store_file(**file_data)
            stored_file.last_accessed = datetime.utcnow() - timedelta(days=45)

            normal_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))

            with pytest.raises(DatabaseError):
                manager.transition_storage_classes()

            normal_session.rollback.assert_called_once()
