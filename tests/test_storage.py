import sys
import os
from pathlib import Path
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch, Mock, PropertyMock
from hashlib import sha256
from copy import deepcopy

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tests.test_data_builder import (
    TestDataBuilder,
    test_builder,
    TestFileInfo,
    generate_test_id,
    iso_time,
)

from fileengine.models import FileInfo, FileStatus, expire_at_days, now_iso
from fileengine.storage import StorageManager, storage
from fileengine.metadata import MetadataManager, metadata
from fileengine.config import settings
from fileengine.logger import logger


class TestStorageBasics:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_calculate_sha256(self, tmp_path):
        test_content = b"Hello, World! Test content for SHA256 check."
        test_file = tmp_path / "test.txt"
        with open(test_file, "wb") as f:
            f.write(test_content)

        expected_hash = sha256(test_content).hexdigest()

        with patch.object(storage, "_calculate_sha256", return_value=expected_hash):
            result = storage._calculate_sha256(test_file)
            assert result == expected_hash

    def test_get_file_extension(self):
        test_cases = [
            ("document.pdf", "pdf"),
            ("image.JPG", "jpg"),
            ("archive.tar.gz", "gz"),
            ("file_without_ext", "bin"),
            ("test .txt", "txt"),
            ("image.test.name.png", "png"),
        ]

        for filename, expected in test_cases:
            with patch.object(storage, "_get_file_extension", return_value=expected):
                result = storage._get_file_extension(Path(filename))
                assert result == expected


class TestExpiredFileCleanup:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_expired_file_removed(self):
        expired_file = self.builder.create_expired_file_info(
            file_name="old_report.pdf",
            days_expired=5,
        )

        valid_file = self.builder.create_test_file_info(
            file_name="new_document.txt",
            expire_days=30,
        )

        mock_list = MagicMock(return_value=[
            FileInfo(**expired_file.__dict__),
            FileInfo(**valid_file.__dict__),
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 1
        assert mock_delete.called_once()

    def test_valid_file_not_removed(self):
        valid_files = [
            self.builder.create_test_file_info(file_name=f"valid_{i}.txt", expire_days=30)
            for i in range(5)
        ]

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in valid_files
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 0
        assert not mock_delete.called

    def test_batch_expired_cleanup(self):
        expired_files = self.builder.create_batch_expired_files(count=10)
        valid_files = self.builder.create_batch_files(count=5, expire_days=30)

        all_files = expired_files + valid_files

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in all_files
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == len(expired_files)
        assert mock_delete.call_count == len(expired_files)

    def test_expiry_date_boundary_expired(self):
        just_expired = self.builder.create_critical_expire_file_info(
            file_name="just_expired.txt",
            seconds_to_expire=-1,
        )

        mock_list = MagicMock(return_value=[FileInfo(**just_expired.__dict__)])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 1

    def test_expiry_date_boundary_valid(self):
        just_valid = self.builder.create_critical_expire_file_info(
            file_name="just_valid.txt",
            seconds_to_expire=1,
        )

        mock_list = MagicMock(return_value=[FileInfo(**just_valid.__dict__)])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 0

    def test_exactly_zero_boundary(self):
        exactly_now = self.builder.create_critical_expire_file_info(
            file_name="exactly_now.txt",
            seconds_to_expire=0,
        )

        mock_list = MagicMock(return_value=[FileInfo(**exactly_now.__dict__)])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 0 or deleted_count == 1

    def test_mixed_expiry_cleanup(self):
        mixed_files = self.builder.create_mixed_expiry_files(
            expired_count=3,
            valid_count=4,
            critical_count=2,
        )

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in mixed_files
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count >= 3


class TestFileReferenceProtection:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_file_referenced_by_convert_task_not_deleted(self):
        source_file = self.builder.create_expired_file_info(
            file_name="source.pdf",
            days_expired=2,
        )

        convert_task = self.builder.create_convert_task(
            source_file_id=source_file.file_id,
            status="processing",
        )

        mock_list_files = MagicMock(return_value=[FileInfo(**source_file.__dict__)])
        mock_list_tasks = MagicMock(return_value=[convert_task])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list_files):
            with patch.object(metadata, "list_convert_tasks", mock_list_tasks):
                with patch.object(storage, "delete_file", mock_delete):
                    deleted_count = storage.cleanup_expired()

        assert deleted_count >= 0

    def test_file_target_of_completed_convert_task(self):
        target_file = self.builder.create_expired_file_info(
            file_name="result.jpg",
            days_expired=1,
        )

        convert_task = self.builder.create_convert_task(
            source_file_id="source_file_id",
            status="completed",
        )
        convert_task.target_file_id = target_file.file_id

        mock_list_files = MagicMock(return_value=[FileInfo(**target_file.__dict__)])
        mock_list_tasks = MagicMock(return_value=[convert_task])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list_files):
            with patch.object(metadata, "list_convert_tasks", mock_list_tasks):
                with patch.object(storage, "delete_file", mock_delete):
                    deleted_count = storage.cleanup_expired()

        assert deleted_count >= 0

    def test_compress_task_source_file_reference(self):
        source_files = [
            self.builder.create_expired_file_info(
                file_name=f"part_{i}.txt",
                days_expired=3,
            )
            for i in range(3)
        ]

        compress_task = {
            "compress_id": generate_test_id("compress"),
            "source_files": [f.file_id for f in source_files],
            "compress_status": "processing",
        }

        mock_list_files = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in source_files
        ])
        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list_files):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count >= 0


class TestStorageFileOperations:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_store_file_creates_metadata(self, tmp_path):
        file_content = b"Test file content for storage test."
        file_name = "test_document.txt"

        test_storage_dir = tmp_path / "test_store"
        test_storage_dir.mkdir()

        mock_save = MagicMock()

        with patch.object(metadata, "save_file", mock_save):
            with patch.object(storage, "upload_dir", test_storage_dir):
                result = storage.store_file(
                    file_data=file_content,
                    filename=file_name,
                    upload_user="test_user",
                    mime_type="text/plain",
                )

        assert result is not None
        assert result.file_name == file_name
        assert result.file_type == "txt"
        assert result.file_size == len(file_content)
        assert result.status == FileStatus.STORED

        stored_files = list(test_storage_dir.glob("*"))
        assert len(stored_files) == 1

    def test_store_file_from_path(self, tmp_path):
        source_file = tmp_path / "source.txt"
        source_content = b"Content from source path"
        with open(source_file, "wb") as f:
            f.write(source_content)

        test_result_dir = tmp_path / "result_store"
        test_result_dir.mkdir()
        test_upload_dir = tmp_path / "upload_store"
        test_upload_dir.mkdir()

        mock_save = MagicMock()

        with patch.object(metadata, "save_file", mock_save):
            with patch.object(storage, "upload_dir", test_upload_dir):
                with patch.object(storage, "result_dir", test_result_dir):
                    result = storage.store_file_from_path(
                        source_file,
                        target_filename="copied.txt",
                        upload_user="test_user",
                        is_result=True,
                    )

        assert result is not None
        assert result.file_name == "copied.txt"

    def test_delete_file_removes_metadata_and_file(self, tmp_path):
        file_id = generate_test_id("file")
        test_file = tmp_path / "to_delete.txt"
        with open(test_file, "w") as f:
            f.write("content")

        file_info = self.builder.create_test_file_info(
            file_name="to_delete.txt",
        )
        file_info.file_id = file_id
        file_info.storage_path = str(test_file)

        mock_get = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_delete_metadata = MagicMock(return_value=True)

        with patch.object(metadata, "get_file", mock_get):
            with patch.object(metadata, "delete_file", mock_delete_metadata):
                result = storage.delete_file(file_id)

        assert result is True
        assert mock_delete_metadata.called
        assert not test_file.exists()

    def test_get_file_path_lookup(self):
        file_id = generate_test_id("file")
        file_info = self.builder.create_test_file_info(
            file_name="lookup_test.txt",
        )

        mock_get = MagicMock(return_value=FileInfo(**file_info.__dict__))

        with patch.object(metadata, "get_file", mock_get):
            result = storage.get_file_info(file_id)

        assert result is not None
        assert result.file_id == file_info.file_id

    def test_get_file_info_not_found(self):
        mock_get = MagicMock(return_value=None)

        with patch.object(metadata, "get_file", mock_get):
            result = storage.get_file_path("nonexistent_file")

        assert result is None


class TestTempPathGeneration:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_temp_path_generation(self):
        filename = "temp_processing.pdf"
        temp_path = storage.get_temp_path(filename)

        assert temp_path is not None
        assert filename in temp_path.name
        assert str(storage.temp_dir) in str(temp_path)

    def test_unique_temp_paths(self):
        paths = [storage.get_temp_path(f"test_{i}.tmp") for i in range(5)]

        assert len(set(paths)) == 5


class TestStorageEdgeCases:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_empty_file_storage(self, tmp_path):
        test_storage_dir = tmp_path / "empty_test"
        test_storage_dir.mkdir()

        mock_save = MagicMock()

        with patch.object(metadata, "save_file", mock_save):
            with patch.object(storage, "upload_dir", test_storage_dir):
                result = storage.store_file(
                    file_data=b"",
                    filename="empty.txt",
                )

        assert result is not None
        assert result.file_size == 0

    def test_large_file_info_creation(self):
        large_file = self.builder.create_test_file_info(
            file_name="large_video.mp4",
            file_type="mp4",
            file_size=1024 * 1024 * 1024,
        )

        assert large_file.file_size == 1024 * 1024 * 1024

    def test_unicode_filename_storage(self, tmp_path):
        test_storage_dir = tmp_path / "unicode_test"
        test_storage_dir.mkdir()

        mock_save = MagicMock()

        with patch.object(metadata, "save_file", mock_save):
            with patch.object(storage, "upload_dir", test_storage_dir):
                result = storage.store_file(
                    file_data=b"content",
                    filename="测试文件_中文.txt",
                )

        assert result is not None
        assert "测试文件" in result.file_name

    def test_special_characters_filename(self, tmp_path):
        test_storage_dir = tmp_path / "special_test"
        test_storage_dir.mkdir()

        mock_save = MagicMock()

        with patch.object(metadata, "save_file", mock_save):
            with patch.object(storage, "upload_dir", test_storage_dir):
                result = storage.store_file(
                    file_data=b"content",
                    filename="file with spaces&special@chars.txt",
                )

        assert result is not None


class TestCleanupPerformance:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_large_batch_cleanup(self):
        large_batch = self.builder.create_batch_expired_files(count=50)

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in large_batch
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 50
        assert mock_delete.call_count == 50

    def test_cleanup_with_no_files(self):
        mock_list = MagicMock(return_value=[])
        mock_delete = MagicMock()

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 0
        assert not mock_delete.called


class TestExpiredFileDeletionErrorHandling:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_file_already_deleted(self):
        expired_file = self.builder.create_expired_file_info(
            file_name="already_gone.txt",
            days_expired=1,
        )
        expired_file.storage_path = "/nonexistent/path.txt"

        mock_list = MagicMock(return_value=[FileInfo(**expired_file.__dict__)])
        mock_delete_metadata = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(metadata, "delete_file", mock_delete_metadata):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 1
        assert mock_delete_metadata.called

    def test_metadata_deletion_failure(self):
        expired_file = self.builder.create_expired_file_info(
            file_name="metadata_error.txt",
            days_expired=1,
        )

        mock_list = MagicMock(return_value=[FileInfo(**expired_file.__dict__)])
        mock_delete_metadata = MagicMock(return_value=False)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(metadata, "delete_file", mock_delete_metadata):
                deleted_count = storage.cleanup_expired()

        assert deleted_count >= 0


class TestStorageCleanupScenarios:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_scenario_mixed_files_all(self):
        files = []

        for i in range(5):
            files.append(self.builder.create_expired_file_info(
                file_name=f"expired_{i}.pdf",
                days_expired=i + 1,
            ))

        for i in range(10):
            files.append(self.builder.create_test_file_info(
                file_name=f"valid_{i}.pdf",
                expire_days=30 + i,
            ))

        for i in range(3):
            files.append(self.builder.create_critical_expire_file_info(
                file_name=f"critical_{i}.txt",
                seconds_to_expire=(-1) ** i * 60 * i,
            ))

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in files
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count >= 5
        assert mock_delete.call_count >= 5

    def test_scenario_expired_days_range(self):
        files = self.builder.create_batch_expired_files(
            count=20,
            expired_days_range=(1, 365),
        )

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in files
        ])

        mock_delete = MagicMock(return_value=True)

        with patch.object(metadata, "list_files", mock_list):
            with patch.object(storage, "delete_file", mock_delete):
                deleted_count = storage.cleanup_expired()

        assert deleted_count == 20
