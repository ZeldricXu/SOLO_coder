import sys
import os
from pathlib import Path
from datetime import datetime
from unittest.mock import MagicMock, patch, Mock
from hashlib import sha256

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tests.test_data_builder import (
    TestDataBuilder,
    test_builder,
    TestFileInfo,
    generate_test_id,
    iso_time,
)

from fileengine.models import FileInfo, FileStatus, UploadSession, now_iso
from fileengine.upload import UploadManager, upload_manager
from fileengine.storage import StorageManager, storage
from fileengine.metadata import MetadataManager, metadata
from fileengine.config import settings


class TestRegularUpload:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_basic_file_upload(self, tmp_path):
        file_content = b"Test file content for basic upload test."
        file_name = "basic_test.txt"

        test_upload_dir = tmp_path / "upload_test"
        test_upload_dir.mkdir()

        with patch.object(storage, "upload_dir", test_upload_dir):
            success, file_info, message = upload_manager.upload_file(
                file_data=file_content,
                filename=file_name,
                upload_user="test_user",
                mime_type="text/plain",
            )

        assert success is True
        assert file_info is not None
        assert file_info.file_name == file_name
        assert file_info.file_size == len(file_content)
        assert file_info.status == FileStatus.STORED

        stored_files = list(test_upload_dir.glob("*"))
        assert len(stored_files) == 1

    def test_upload_file_exceeds_limit(self):
        file_content = b"x" * (settings.max_file_size + 1)

        success, file_info, message = upload_manager.upload_file(
            file_data=file_content,
            filename="too_large.txt",
        )

        assert success is False
        assert file_info is None
        assert "exceeds" in message.lower()

    def test_upload_with_different_mime_types(self):
        test_cases = [
            (b"Plain text", "document.txt", "text/plain"),
            (test_builder.create_test_image_data(100, 100, "png"), "image.png", "image/png"),
            (test_builder.create_test_pdf_data(), "doc.pdf", "application/pdf"),
        ]

        for content, name, mime in test_cases:
            with patch.object(metadata, "save_file", MagicMock()):
                success, file_info, message = upload_manager.upload_file(
                    file_data=content,
                    filename=name,
                    upload_user="test_user",
                    mime_type=mime,
                )

            assert success is True
            assert file_info is not None

    def test_unicode_filename_upload(self, tmp_path):
        test_upload_dir = tmp_path / "unicode_upload"
        test_upload_dir.mkdir()

        with patch.object(storage, "upload_dir", test_upload_dir):
            success, file_info, message = upload_manager.upload_file(
                file_data=b"unicode test",
                filename="中文文件名_测试.txt",
                upload_user="test_user",
            )

        assert success is True
        assert "中文文件名" in file_info.file_name


class TestChunkUploadSession:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_init_chunk_upload_session(self, tmp_path):
        test_chunks_dir = tmp_path / "chunks_init"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            result = upload_manager.init_chunk_upload(
                file_name="large_file.bin",
                total_size=10 * 1024 * 1024,
                upload_user="test_user",
            )

        assert result["success"] is True
        assert "session_id" in result
        assert result["total_chunks"] > 0
        assert "chunk_size" in result

        session_dir = test_chunks_dir / result["session_id"]
        assert session_dir.exists()

    def test_chunk_upload_calculation(self):
        chunk_size = 1024 * 1024

        test_cases = [
            (5 * 1024 * 1024, chunk_size, 5),
            (5 * 1024 * 1024 + 1, chunk_size, 6),
            (0, chunk_size, 1),
            (chunk_size - 1, chunk_size, 1),
            (chunk_size, chunk_size, 1),
            (chunk_size + 1, chunk_size, 2),
        ]

        for total_size, chunk_size, expected in test_cases:
            with patch.object(storage, "chunks_dir", Path("/tmp")):
                result = upload_manager.init_chunk_upload(
                    file_name="test.bin",
                    total_size=total_size,
                    upload_user="test",
                )

            assert result["total_chunks"] == expected

    def test_nonexistent_session_lookup(self):
        result = upload_manager.get_upload_progress("nonexistent_session_id")

        assert result["success"] is False


class TestChunkUploadProgress:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_chunk_upload_progress_update(self, tmp_path):
        test_chunks_dir = tmp_path / "progress_test"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="progress_test.bin",
                total_size=5 * 1024 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]
            total_chunks = init_result["total_chunks"]

            for i in range(total_chunks):
                chunk_data = self.builder.create_chunk_data(index=i, size=1024 * 1024)
                result = upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=chunk_data,
                )

                expected_progress = ((i + 1) / total_chunks) * 100
                assert result["progress"] == expected_progress

    def test_chunk_upload_complete_detection(self, tmp_path):
        test_chunks_dir = tmp_path / "complete_test"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="complete_test.bin",
                total_size=3 * 1024 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]
            total_chunks = init_result["total_chunks"]

            for i in range(total_chunks - 1):
                chunk_data = self.builder.create_chunk_data(index=i)
                result = upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=chunk_data,
                )
                assert result["is_complete"] is False

            final_chunk = self.builder.create_chunk_data(index=total_chunks - 1)
            final_result = upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=total_chunks - 1,
                chunk_data=final_chunk,
            )

            assert final_result["is_complete"] is True

    def test_progress_query_after_chunks(self, tmp_path):
        test_chunks_dir = tmp_path / "progress_query"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="query_test.bin",
                total_size=5 * 1024 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]
            total_chunks = init_result["total_chunks"]

            chunks_to_upload = total_chunks // 2
            for i in range(chunks_to_upload):
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=self.builder.create_chunk_data(index=i),
                )

            progress = upload_manager.get_upload_progress(session_id)

            assert progress["success"] is True
            assert progress["total_chunks"] == total_chunks
            assert progress["chunks_received"] == chunks_to_upload
            assert progress["progress"] == (chunks_to_upload / total_chunks) * 100


class TestChunkDataIntegrity:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_chunk_data_written_correctly(self, tmp_path):
        test_chunks_dir = tmp_path / "integrity_test"
        test_chunks_dir.mkdir()

        chunk_index = 0
        original_data = self.builder.create_chunk_data(
            index=chunk_index,
            size=1024,
            seed=12345,
        )

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="integrity.bin",
                total_size=1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=chunk_index,
                chunk_data=original_data,
            )

            chunk_file = test_chunks_dir / session_id / f"chunk_{chunk_index}"

            assert chunk_file.exists()

            with open(chunk_file, "rb") as f:
                stored_data = f.read()

            assert stored_data == original_data

    def test_multiple_chunk_integrity(self, tmp_path):
        test_chunks_dir = tmp_path / "multi_integrity"
        test_chunks_dir.mkdir()

        total_chunks = 5
        chunk_data_map = {}

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="multi.bin",
                total_size=total_chunks * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            for i in range(total_chunks):
                chunk_data = self.builder.create_chunk_data(index=i, size=1024)
                chunk_data_map[i] = chunk_data

                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=chunk_data,
                )

            for i in range(total_chunks):
                chunk_file = test_chunks_dir / session_id / f"chunk_{i}"
                assert chunk_file.exists()

                with open(chunk_file, "rb") as f:
                    stored = f.read()

                assert stored == chunk_data_map[i]

    def test_chunk_hash_verification(self, tmp_path):
        test_chunks_dir = tmp_path / "hash_test"
        test_chunks_dir.mkdir()

        original_data = self.builder.create_chunk_data(index=0, size=2048)
        expected_hash = sha256(original_data).hexdigest()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="hash_test.bin",
                total_size=2048,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=0,
                chunk_data=original_data,
            )

            chunk_file = test_chunks_dir / session_id / "chunk_0"

            with open(chunk_file, "rb") as f:
                stored = f.read()

            actual_hash = sha256(stored).hexdigest()
            assert actual_hash == expected_hash


class TestChunkMerge:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_chunk_merge_creates_file(self, tmp_path):
        test_chunks_dir = tmp_path / "merge_test"
        test_chunks_dir.mkdir()
        test_upload_dir = tmp_path / "merged_uploads"
        test_upload_dir.mkdir()

        total_chunks = 4
        total_size = total_chunks * 1024

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            with patch.object(storage, "upload_dir", test_upload_dir):
                init_result = upload_manager.init_chunk_upload(
                    file_name="merged.bin",
                    total_size=total_size,
                    upload_user="test",
                )

                session_id = init_result["session_id"]

                all_data = bytearray()
                for i in range(total_chunks):
                    chunk = self.builder.create_chunk_data(index=i, size=1024)
                    all_data.extend(chunk)

                    upload_manager.upload_chunk(
                        session_id=session_id,
                        chunk_index=i,
                        chunk_data=chunk,
                    )

                result = upload_manager.complete_chunk_upload(session_id)

        assert result["success"] is True
        assert "file_id" in result
        assert result["upload_status"] == "completed"

        stored_files = list(test_upload_dir.glob("*"))
        assert len(stored_files) == 1

        with open(stored_files[0], "rb") as f:
            merged_data = f.read()

        assert merged_data == bytes(all_data)

    def test_merge_incomplete_chunks_fails(self, tmp_path):
        test_chunks_dir = tmp_path / "incomplete_merge"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="incomplete.bin",
                total_size=4 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            for i in range(2):
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=self.builder.create_chunk_data(index=i),
                )

            result = upload_manager.complete_chunk_upload(session_id)

        assert result["success"] is False
        assert "not all chunks" in result["message"].lower()

    def test_merge_produces_correct_size(self, tmp_path):
        test_chunks_dir = tmp_path / "size_test"
        test_chunks_dir.mkdir()
        test_upload_dir = tmp_path / "size_uploads"
        test_upload_dir.mkdir()

        total_chunks = 3
        chunk_sizes = [500, 1000, 750]
        total_size = sum(chunk_sizes)

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            with patch.object(storage, "upload_dir", test_upload_dir):
                init_result = upload_manager.init_chunk_upload(
                    file_name="sizes.bin",
                    total_size=total_size,
                    upload_user="test",
                )

                session_id = init_result["session_id"]

                for i, size in enumerate(chunk_sizes):
                    upload_manager.upload_chunk(
                        session_id=session_id,
                        chunk_index=i,
                        chunk_data=self.builder.create_chunk_data(index=i, size=size),
                    )

                result = upload_manager.complete_chunk_upload(session_id)

        assert result["success"] is True
        assert result["file_size"] == total_size


class TestChunkResume:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_resume_after_interruption(self, tmp_path):
        test_chunks_dir = tmp_path / "resume_test"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="resume_test.bin",
                total_size=5 * 1024 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]
            total_chunks = init_result["total_chunks"]

            chunks_to_send = total_chunks // 2
            for i in range(chunks_to_send):
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=self.builder.create_chunk_data(index=i),
                )

            progress_before = upload_manager.get_upload_progress(session_id)
            assert progress_before["chunks_received"] == chunks_to_send

            for i in range(chunks_to_send, total_chunks):
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=self.builder.create_chunk_data(index=i),
                )

            progress_after = upload_manager.get_upload_progress(session_id)
            assert progress_after["chunks_received"] == total_chunks
            assert progress_after["progress"] == 100.0
            assert progress_after["is_complete"] is True

    def test_resume_does_not_reupload_received_chunks(self, tmp_path):
        test_chunks_dir = tmp_path / "no_reupload"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="no_reupload.bin",
                total_size=3 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            chunk0_data = self.builder.create_chunk_data(index=0, size=1024)
            chunk1_data = self.builder.create_chunk_data(index=1, size=1024)
            chunk2_data = self.builder.create_chunk_data(index=2, size=1024)

            upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=0,
                chunk_data=chunk0_data,
            )

            upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=1,
                chunk_data=chunk1_data,
            )

            upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=0,
                chunk_data=chunk0_data,
            )

            chunk0_file = test_chunks_dir / session_id / "chunk_0"
            with open(chunk0_file, "rb") as f:
                stored = f.read()

            assert stored == chunk0_data

    def test_resume_with_session_persistence(self, tmp_path):
        test_chunks_dir = tmp_path / "session_persist"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="persist.bin",
                total_size=4 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            for i in range(2):
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=i,
                    chunk_data=self.builder.create_chunk_data(index=i),
                )

            progress = upload_manager.get_upload_progress(session_id)

            assert progress["success"] is True
            assert progress["session_id"] == session_id
            assert progress["chunks_received"] == 2
            assert progress["total_chunks"] == 4


class TestUploadManagerOperations:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_list_files(self):
        test_files = [
            self.builder.create_test_file_info(file_name=f"file_{i}.txt")
            for i in range(5)
        ]

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in test_files
        ])

        with patch.object(metadata, "list_files", mock_list):
            result = upload_manager.list_files()

        assert len(result) == 5
        assert all(f["file_name"].startswith("file_") for f in result)

    def test_list_files_by_user(self):
        user_files = [
            self.builder.create_test_file_info(
                file_name=f"user_file_{i}.txt",
                upload_user="alice",
            )
            for i in range(3)
        ]

        mock_list = MagicMock(return_value=[
            FileInfo(**f.__dict__) for f in user_files
        ])

        with patch.object(metadata, "list_files", mock_list):
            result = upload_manager.list_files(user_id="alice")

        assert len(result) == 3
        mock_list.assert_called_with("alice")

    def test_delete_file(self):
        file_id = generate_test_id("file")

        mock_delete = MagicMock(return_value=True)

        with patch.object(storage, "delete_file", mock_delete):
            result = upload_manager.delete_file(file_id)

        assert result is True
        mock_delete.assert_called_with(file_id)

    def test_get_file_info(self):
        file_info = self.builder.create_test_file_info(file_name="lookup.txt")

        mock_get = MagicMock(return_value=FileInfo(**file_info.__dict__))

        with patch.object(metadata, "get_file", mock_get):
            result = upload_manager.get_file_info(file_info.file_id)

        assert result is not None
        assert result.file_name == "lookup.txt"


class TestUploadEdgeCases:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_single_chunk_upload(self, tmp_path):
        test_chunks_dir = tmp_path / "single_chunk"
        test_chunks_dir.mkdir()
        test_upload_dir = tmp_path / "single_upload"
        test_upload_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            with patch.object(storage, "upload_dir", test_upload_dir):
                init_result = upload_manager.init_chunk_upload(
                    file_name="single.bin",
                    total_size=500,
                    upload_user="test",
                )

                assert init_result["total_chunks"] == 1

                session_id = init_result["session_id"]

                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=0,
                    chunk_data=b"x" * 500,
                )

                result = upload_manager.complete_chunk_upload(session_id)

        assert result["success"] is True
        assert result["file_size"] == 500

    def test_empty_chunk_data(self, tmp_path):
        test_chunks_dir = tmp_path / "empty_chunk"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="empty_test.bin",
                total_size=2 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            result = upload_manager.upload_chunk(
                session_id=session_id,
                chunk_index=0,
                chunk_data=b"",
            )

        assert result["success"] is True

    def test_chunk_order_independence(self, tmp_path):
        test_chunks_dir = tmp_path / "order_test"
        test_chunks_dir.mkdir()

        with patch.object(storage, "chunks_dir", test_chunks_dir):
            init_result = upload_manager.init_chunk_upload(
                file_name="order.bin",
                total_size=4 * 1024,
                upload_user="test",
            )

            session_id = init_result["session_id"]

            upload_order = [2, 0, 3, 1]
            for idx in upload_order:
                upload_manager.upload_chunk(
                    session_id=session_id,
                    chunk_index=idx,
                    chunk_data=self.builder.create_chunk_data(index=idx),
                )

            progress = upload_manager.get_upload_progress(session_id)

            assert progress["chunks_received"] == 4
            assert progress["is_complete"] is True
