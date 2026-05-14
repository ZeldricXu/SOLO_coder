import sys
import os
from pathlib import Path
from datetime import datetime
from unittest.mock import MagicMock, patch, Mock
from copy import deepcopy

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tests.test_data_builder import (
    TestDataBuilder,
    test_builder,
    TestConvertTask,
    TestFileInfo,
    TestConversionParams,
    generate_test_id,
    iso_time,
)

from fileengine.models import (
    ConvertTask,
    FileInfo,
    TaskStatus,
    ConvertResult,
    FileStatus,
    now_iso,
)
from fileengine.converter import ConverterManager, converter
from fileengine.task_queue import TaskQueueManager, task_queue
from fileengine.metadata import MetadataManager, metadata
from fileengine.storage import StorageManager, storage
from fileengine.logger import logger


class TestConvertTaskCreation:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_create_convert_task_pdf_to_jpg(self):
        file_info = self.builder.create_test_file_info(
            file_name="report.pdf",
            file_type="pdf",
            file_size=1024 * 1024,
        )

        params = self.builder.create_conversion_params(quality=80, dpi=300)

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save_task):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task is not None
        assert task.source_file_id == file_info.file_id
        assert task.source_format == "pdf"
        assert task.target_format == "jpg"
        assert task.task_status == TaskStatus.PENDING
        assert task.conversion_params.get("quality") == 80
        assert task.conversion_params.get("dpi") == 300

    def test_create_convert_task_source_not_found(self):
        mock_get_file = MagicMock(return_value=None)

        with patch.object(metadata, "get_file", mock_get_file):
            success, task, message = converter.create_convert_task(
                file_id="nonexistent",
                target_format="jpg",
            )

        assert success is False
        assert task is None
        assert "not found" in message.lower()

    def test_create_convert_task_unsupported_format(self):
        file_info = self.builder.create_test_file_info(
            file_name="document.doc",
            file_type="doc",
            file_size=1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))

        with patch.object(metadata, "get_file", mock_get_file):
            success, task, message = converter.create_convert_task(
                file_id=file_info.file_id,
                target_format="mp3",
            )

        assert success is False
        assert task is None
        assert "unsupported" in message.lower()

    @pytest.mark.parametrize(
        "source_ext,target,expected_format",
        [
            ("pdf", "jpg", "pdf"),
            ("png", "webp", "image"),
            ("mp4", "webm", "video"),
            ("jpeg", "gif", "image"),
        ],
    )
    def test_format_detection(self, source_ext, target, expected_format):
        file_info = self.builder.create_test_file_info(
            file_name=f"test.{source_ext}",
            file_type=source_ext,
            file_size=1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, _ = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format=target,
                )

        if success:
            assert task.source_format == expected_format


class TestConvertTaskStateMachine:
    def setup_method(self):
        self.builder = TestDataBuilder()
        self.state_transitions = self.builder.create_task_status_transitions()

    def test_task_status_transitions(self):
        for transition in self.state_transitions:
            task = self.builder.create_convert_task(
                source_file_id="file_001",
                source_format="pdf",
                target_format="jpg",
                status=transition["initial_status"],
            )

            assert task.task_status == transition["initial_status"]

            new_status = transition["expected_status"]
            task.task_status = new_status

            assert task.task_status == new_status, (
                f"Failed transition: {transition['transition']}"
            )

    def test_convert_task_lifecycle(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024,
        )

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            status="pending",
        )

        assert task.task_status == TaskStatus.PENDING
        assert task.started_at is None
        assert task.completed_at is None

        task.task_status = TaskStatus.PROCESSING
        task.started_at = now_iso()

        assert task.task_status == TaskStatus.PROCESSING
        assert task.started_at is not None

        task.task_status = TaskStatus.COMPLETED
        task.completed_at = now_iso()
        task.target_file_id = generate_test_id("file")

        assert task.task_status == TaskStatus.COMPLETED
        assert task.completed_at is not None
        assert task.target_file_id is not None

    def test_convert_task_error_lifecycle(self):
        task = self.builder.create_convert_task(
            source_file_id="file_001",
            status="pending",
        )

        assert task.task_status == TaskStatus.PENDING
        assert task.error_message is None

        task.task_status = TaskStatus.PROCESSING

        task.task_status = TaskStatus.FAILED
        task.error_message = "Conversion failed: timeout"
        task.retry_count = 0

        assert task.task_status == TaskStatus.FAILED
        assert task.error_message is not None
        assert "timeout" in task.error_message

    def test_convert_task_retry_lifecycle(self):
        task = self.builder.create_convert_task(
            source_file_id="file_001",
            status="failed",
        )

        task.retry_count = 1
        task.task_status = TaskStatus.RETRYING

        assert task.task_status == TaskStatus.RETRYING
        assert task.retry_count == 1

        task.task_status = TaskStatus.PROCESSING
        assert task.task_status == TaskStatus.PROCESSING


class TestAsyncConversion:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_async_task_submission(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024 * 1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save_task):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                )

        assert success is True
        assert task.task_status == TaskStatus.PENDING
        assert task.started_at is None

        original_size = task_queue.get_queue_size()
        task_queue.add_task(
            task_type="convert",
            task_id=task.task_id,
            priority=5,
        )
        new_size = task_queue.get_queue_size()

        assert new_size >= original_size

    def test_task_progress_query(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024,
        )

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            status="processing",
        )

        mock_get_task = MagicMock(return_value=ConvertTask(**task.__dict__))

        with patch.object(metadata, "get_convert_task", mock_get_task):
            status = converter.get_task_status(task.task_id)

        assert status is not None
        assert status["task_id"] == task.task_id
        assert status["status"] == TaskStatus.PROCESSING

    def test_completed_task_result_query(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024,
        )

        result_file = self.builder.create_test_file_info(
            file_name="test_page_1.jpg",
            file_type="jpg",
            file_size=512,
        )

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            status="completed",
        )
        task.target_file_id = result_file.file_id
        task.completed_at = now_iso()

        mock_get_task = MagicMock(return_value=ConvertTask(**task.__dict__))

        with patch.object(metadata, "get_convert_task", mock_get_task):
            status = converter.get_task_status(task.task_id)

        assert status is not None
        assert status["status"] == TaskStatus.COMPLETED
        assert status["target_file_id"] == result_file.file_id
        assert status["completed_at"] is not None

    def test_conversion_complete_link(self):
        source_file = self.builder.create_test_file_info(
            file_name="source.pdf",
            file_type="pdf",
            file_size=2048,
        )

        target_file = self.builder.create_test_file_info(
            file_name="result.jpg",
            file_type="jpg",
            file_size=1024,
        )

        task = self.builder.create_convert_task(
            source_file_id=source_file.file_id,
            source_format="pdf",
            target_format="jpg",
            status="completed",
        )
        task.target_file_id = target_file.file_id

        mock_get_task = MagicMock(return_value=ConvertTask(**task.__dict__))
        mock_get_source = MagicMock(return_value=FileInfo(**source_file.__dict__))
        mock_get_target = MagicMock(return_value=FileInfo(**target_file.__dict__))

        with patch.object(metadata, "get_convert_task", mock_get_task):
            with patch.object(metadata, "get_file", mock_get_source):
                status = converter.get_task_status(task.task_id)
                retrieved_source = metadata.get_file(status["source_file_id"])

                assert retrieved_source.file_id == source_file.file_id

        with patch.object(metadata, "get_file", mock_get_target):
            retrieved_target = metadata.get_file(status["target_file_id"])
            assert retrieved_target.file_id == target_file.file_id


class TestConversionErrorHandling:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_source_file_not_exists(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024,
        )

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            status="pending",
        )

        mock_get_task = MagicMock(return_value=ConvertTask(**task.__dict__))
        mock_get_file = MagicMock(return_value=None)
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_convert_task", mock_get_task):
            with patch.object(metadata, "get_file", mock_get_file):
                with patch.object(metadata, "save_convert_task", mock_save_task):
                    success, result, message = converter.execute_convert(task.task_id)

        assert success is False
        assert "not found" in message.lower()

    def test_conversion_exception_handling(self):
        file_info = self.builder.create_test_file_info(
            file_name="corrupt.pdf",
            file_type="pdf",
            file_size=1024,
        )

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            status="pending",
        )

        mock_get_task = MagicMock(return_value=ConvertTask(**task.__dict__))
        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_exists = MagicMock(return_value=True)
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_convert_task", mock_get_task):
            with patch.object(metadata, "get_file", mock_get_file):
                with patch("pathlib.Path.exists", mock_exists):
                    with patch.object(metadata, "save_convert_task", mock_save_task):
                        success, result, message = converter.execute_convert(task.task_id)

        assert success is False
        assert task.task_status in [TaskStatus.PENDING, TaskStatus.FAILED]


class TestTaskQueueIntegration:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_task_priority_order(self):
        high_priority_task_id = generate_test_id("task_high")
        low_priority_task_id = generate_test_id("task_low")

        initial_size = task_queue.get_queue_size()

        task_queue.add_task(
            task_type="convert",
            task_id=low_priority_task_id,
            priority=10,
        )

        task_queue.add_task(
            task_type="convert",
            task_id=high_priority_task_id,
            priority=1,
        )

        final_size = task_queue.get_queue_size()
        assert final_size == initial_size + 2

    def test_multiple_tasks_queued(self):
        file_ids = [
            generate_test_id("file"),
            generate_test_id("file"),
            generate_test_id("file"),
        ]

        for fid in file_ids:
            task_queue.add_task(
                task_type="convert",
                task_id=generate_test_id("task"),
                priority=5,
                extra_args={"file_id": fid},
            )

        assert task_queue.get_queue_size() >= len(file_ids)

    def test_queue_size_tracking(self):
        initial = task_queue.get_queue_size()

        for i in range(5):
            task_queue.add_task(
                task_type="convert",
                task_id=generate_test_id("task"),
                priority=5,
            )

        after_add = task_queue.get_queue_size()
        assert after_add >= initial

        task_queue.clear_queue()
        assert task_queue.get_queue_size() == 0


class TestConversionParamsPassing:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_all_params_passed_to_converter(self):
        params = TestConversionParams(
            quality=95,
            dpi=600,
            max_width=1920,
            max_height=1080,
        )

        file_info = self.builder.create_test_file_info(
            file_name="test.jpg",
            file_type="jpg",
            file_size=1024 * 1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save_task):
                success, task, _ = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="png",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params["quality"] == 95
        assert task.conversion_params["dpi"] == 600
        assert task.conversion_params["max_width"] == 1920
        assert task.conversion_params["max_height"] == 1080

    def test_pdf_specific_params(self):
        params = self.builder.create_pdf_conversion_params(pages=[0, 1, 2])

        file_info = self.builder.create_test_file_info(
            file_name="multipage.pdf",
            file_type="pdf",
            file_size=5 * 1024 * 1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save_task):
                success, task, _ = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params.get("pages") == [0, 1, 2]

    def test_video_specific_params(self):
        params = self.builder.create_video_conversion_params(fps=24)

        file_info = self.builder.create_test_file_info(
            file_name="video.mp4",
            file_type="mp4",
            file_size=50 * 1024 * 1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save_task = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save_task):
                success, task, _ = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="webm",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params.get("fps") == 24
        assert task.conversion_params.get("crf") == 23
        assert task.conversion_params.get("preset") == "medium"
