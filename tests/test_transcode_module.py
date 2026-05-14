import sys
import time
from pathlib import Path
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch, AsyncMock
from typing import Dict, Any

import pytest

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from videoprocess.modules.transcode import TranscodeModule
from videoprocess.config import ALLOWED_VIDEO_FORMATS, ALLOWED_CODECS
from tests.test_data_builder import test_data_builder


class TestTranscodeModule:
    def setup_method(self):
        test_data_builder.reset_counters()

    def test_validate_target_format_valid_formats(self, test_db):
        module = TranscodeModule(test_db)

        valid_formats = ["mp4", "webm", "avi", "mkv", "mov"]
        for fmt in valid_formats:
            if fmt in ALLOWED_VIDEO_FORMATS:
                valid, error = module.validate_target_format(fmt)
                assert valid is True, f"Format {fmt} should be valid"
                assert error is None

    def test_validate_target_format_invalid_format(self, test_db):
        module = TranscodeModule(test_db)

        valid, error = module.validate_target_format("invalid_format_xyz")
        assert valid is False
        assert error is not None
        assert "不支持" in error

    def test_validate_target_format_with_codec(self, test_db):
        module = TranscodeModule(test_db)

        valid, error = module.validate_target_format("mp4", "h264")
        assert valid is True

        valid, error = module.validate_target_format("mp4", "invalid_codec_xyz")
        assert valid is False

    def test_validate_target_format_codec_matching(self, test_db):
        module = TranscodeModule(test_db)

        for fmt, codecs in ALLOWED_CODECS.items():
            for codec in codecs:
                valid, error = module.validate_target_format(fmt, codec)
                assert valid is True, f"Codec {codec} should be valid for format {fmt}"

    def test_create_transcode_record_returns_id(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_test_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_test_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
            target_codec="vp9",
            profile="medium",
        )

        assert record.transcode_id is not None
        assert record.transcode_id.startswith("transcode_")
        assert record.video_id == "video_test_001"
        assert record.source_format == "mp4"
        assert record.target_format == "webm"
        assert record.target_codec == "vp9"
        assert record.transcode_status == "pending"

    def test_transcode_request_submit_returns_immediately(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_async_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_async_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)

        start_time = time.time()
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
            target_codec="vp9",
        )
        submit_time = time.time() - start_time

        assert submit_time < 1.0
        assert record.transcode_status == "pending"
        assert record.transcode_id is not None

    def test_transcode_status_transitions_pending_to_processing(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_status_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_status_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        video_file = test_settings.uploads_dir / "video_status_001.mp4"
        video_file.write_bytes(b"fake_video_content" * 1000)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="mp4",
            target_codec="h264",
        )

        assert record.transcode_status == "pending"

        record.transcode_status = "processing"
        test_db.commit()
        test_db.refresh(record)

        updated = module.get_transcode_record(record.transcode_id)
        assert updated.transcode_status == "processing"

    def test_transcode_progress_calculation(self, test_db):
        module = TranscodeModule(test_db)

        total_steps = 10
        progress_steps = []

        for step in range(total_steps + 1):
            progress = (step / total_steps) * 100
            progress_steps.append(
                {
                    "step": step,
                    "progress_percent": round(progress, 1),
                    "is_complete": step == total_steps,
                }
            )

        assert progress_steps[0]["progress_percent"] == 0.0
        assert progress_steps[0]["is_complete"] is False

        assert progress_steps[5]["progress_percent"] == 50.0
        assert progress_steps[5]["is_complete"] is False

        assert progress_steps[10]["progress_percent"] == 100.0
        assert progress_steps[10]["is_complete"] is True

        for i in range(1, len(progress_steps)):
            assert progress_steps[i]["progress_percent"] > progress_steps[i - 1]["progress_percent"]

    def test_transcode_progress_query(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_progress_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_progress_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
        )

        queried = module.get_transcode_record(record.transcode_id)
        assert queried is not None
        assert queried.transcode_id == record.transcode_id
        assert queried.video_id == "video_progress_001"

    def test_transcode_failure_records_error(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_fail_001",
            video_format="mp4",
            storage_path="/nonexistent/path/video.mp4",
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
        )

        result = module.execute_transcode(video, record)

        assert result["success"] is False
        assert "error" in result

        updated = module.get_transcode_record(record.transcode_id)
        assert updated.transcode_status == "failed"
        assert updated.error_message is not None

    def test_transcode_retry_mechanism(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_retry_001",
            video_format="mp4",
            storage_path="/nonexistent/path/retry_video.mp4",
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)

        max_retries = 3
        retry_count = 0
        last_error = None

        for attempt in range(max_retries):
            retry_count += 1
            record = module.create_transcode_record(
                video=video,
                target_format="webm",
            )
            result = module.execute_transcode(video, record)
            last_error = result.get("error")

            if result["success"]:
                break

        assert retry_count == max_retries
        assert last_error is not None

        failed_records = module.list_transcode_records(
            video_id=video.video_id,
            status="failed",
        )
        assert len(failed_records) == max_retries

    def test_different_format_conversions(self, test_db, test_settings):
        module = TranscodeModule(test_db)
        scenarios = test_data_builder.create_transcode_scenarios()

        for scenario in scenarios:
            name = scenario["name"]
            config = scenario["config"]
            expected = scenario["expected"]

            if expected.get("valid", True):
                valid, error = module.validate_target_format(
                    config["target_format"],
                    config["target_codec"],
                )
                assert valid is True, f"Scenario {name} should be valid: {error}"
                assert config["target_format"] == expected["target_format"]
                assert config["target_codec"] == expected["target_codec"]
            else:
                valid, error = module.validate_target_format(
                    config["target_format"],
                    config["target_codec"],
                )
                assert valid is False, f"Scenario {name} should be invalid"
                assert expected["error_contains"] in error

    def test_transcode_profiles(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_profile_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_profile_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        profiles = ["low", "medium", "high", "ultra"]

        for profile in profiles:
            record = module.create_transcode_record(
                video=video,
                target_format="webm",
                profile=profile,
            )
            assert record.profile == profile

    def test_list_transcode_records_by_status(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_list_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_list_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)

        pending_record = test_data_builder.create_transcode_record(
            video_id=video.video_id,
            transcode_status="pending",
        )
        completed_record = test_data_builder.create_transcode_record(
            video_id=video.video_id,
            transcode_status="completed",
        )
        failed_record = test_data_builder.create_transcode_record(
            video_id=video.video_id,
            transcode_status="failed",
            error_message="Test error",
        )

        test_db.add(pending_record)
        test_db.add(completed_record)
        test_db.add(failed_record)
        test_db.commit()

        pending = module.list_transcode_records(video_id=video.video_id, status="pending")
        assert len(pending) == 1

        completed = module.list_transcode_records(video_id=video.video_id, status="completed")
        assert len(completed) == 1

        failed = module.list_transcode_records(video_id=video.video_id, status="failed")
        assert len(failed) == 1

        all_records = module.list_transcode_records(video_id=video.video_id)
        assert len(all_records) == 3

    def test_update_transcode_status(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_update_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_update_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
        )

        assert module.update_status(record.transcode_id, "processing") is True

        updated = module.get_transcode_record(record.transcode_id)
        assert updated.transcode_status == "processing"

        assert module.update_status(record.transcode_id, "completed") is True
        updated = module.get_transcode_record(record.transcode_id)
        assert updated.transcode_status == "completed"

        assert module.update_status("nonexistent_id", "completed") is False

    def test_transcode_record_output_path(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_output_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_output_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)
        record = module.create_transcode_record(
            video=video,
            target_format="webm",
        )

        assert record.output_path is not None
        assert "video_output_001" in record.output_path
        assert record.output_path.endswith(".webm")

    def test_transcode_record_transcode_time(self, test_db, test_settings):
        module = TranscodeModule(test_db)

        record1 = test_data_builder.create_transcode_record(
            transcode_time=60.5,
            transcode_status="completed",
        )
        record2 = test_data_builder.create_transcode_record(
            transcode_time=120.0,
            transcode_status="completed",
        )
        record3 = test_data_builder.create_transcode_record(
            transcode_time=0.0,
            transcode_status="pending",
        )

        assert record1.transcode_time == 60.5
        assert record2.transcode_time == 120.0
        assert record3.transcode_time == 0.0

        assert record1.transcode_time < record2.transcode_time

    def test_transcode_target_codec_optional(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_codec_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_codec_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)

        record_with_codec = module.create_transcode_record(
            video=video,
            target_format="mp4",
            target_codec="h264",
        )
        assert record_with_codec.target_codec == "h264"

        record_without_codec = module.create_transcode_record(
            video=video,
            target_format="mp4",
        )
        assert record_without_codec.target_codec is None

    def test_transcode_with_custom_profile(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_custom_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_custom_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = TranscodeModule(test_db)

        record = module.create_transcode_record(
            video=video,
            target_format="webm",
            profile="high",
        )

        assert record.profile == "high"

        queried = module.get_transcode_record(record.transcode_id)
        assert queried.profile == "high"
