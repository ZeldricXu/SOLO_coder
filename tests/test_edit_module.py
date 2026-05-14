import sys
import time
from pathlib import Path
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch
from typing import Dict, Any, List

import pytest

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from videoprocess.modules.edit import EditModule
from tests.test_data_builder import test_data_builder


class TestEditModule:
    def setup_method(self):
        test_data_builder.reset_counters()

    def test_validate_edit_params_valid_cut(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
            video_duration=120.0,
        )
        assert valid is True
        assert error is None

    def test_validate_edit_params_valid_merge(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="merge",
            edit_params={"video_ids": ["video_001", "video_002", "video_003"]},
            video_duration=120.0,
        )
        assert valid is True
        assert error is None

    def test_validate_edit_params_negative_start(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": -10.0, "end": 50.0},
            video_duration=120.0,
        )
        assert valid is False
        assert error is not None
        assert "负数" in error

    def test_validate_edit_params_end_after_start(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 50.0, "end": 10.0},
            video_duration=120.0,
        )
        assert valid is False
        assert error is not None
        assert "大于" in error

    def test_validate_edit_params_end_exceeds_duration(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 10.0, "end": 200.0},
            video_duration=120.0,
        )
        assert valid is False
        assert error is not None
        assert "超出" in error

    def test_validate_edit_params_merge_insufficient_videos(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="merge",
            edit_params={"video_ids": ["video_001"]},
            video_duration=120.0,
        )
        assert valid is False
        assert error is not None
        assert "至少" in error

    def test_validate_edit_params_invalid_type(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="invalid_type",
            edit_params={},
            video_duration=120.0,
        )
        assert valid is False
        assert error is not None

    def test_validate_edit_params_edge_cases(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 0.0, "end": 120.0},
            video_duration=120.0,
        )
        assert valid is True

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 59.99, "end": 60.0},
            video_duration=120.0,
        )
        assert valid is True

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 0.0, "end": 0.01},
            video_duration=120.0,
        )
        assert valid is True

    def test_create_edit_record_cut(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_edit_001",
            video_format="mp4",
            video_duration=120.0,
            storage_path=str(test_settings.uploads_dir / "video_edit_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)
        record = module.create_edit_record(
            video=video,
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
        )

        assert record.edit_id is not None
        assert record.edit_id.startswith("edit_")
        assert record.video_id == "video_edit_001"
        assert record.edit_type == "cut"
        assert record.edit_params["start"] == 10.0
        assert record.edit_params["end"] == 50.0
        assert record.edit_status == "pending"

    def test_create_edit_record_merge(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_edit_002",
            video_format="mp4",
            video_duration=60.0,
            storage_path=str(test_settings.uploads_dir / "video_edit_002.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)
        record = module.create_edit_record(
            video=video,
            edit_type="merge",
            edit_params={"video_ids": ["video_edit_002", "video_003", "video_004"]},
        )

        assert record.edit_type == "merge"
        assert len(record.edit_params["video_ids"]) == 3

    def test_edit_async_status_transitions(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_async_edit_001",
            video_format="mp4",
            video_duration=120.0,
            storage_path=str(test_settings.uploads_dir / "video_async_edit_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)
        record = module.create_edit_record(
            video=video,
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
        )

        assert record.edit_status == "pending"

        record.edit_status = "processing"
        test_db.commit()
        test_db.refresh(record)

        processing_record = module.get_edit_record(record.edit_id)
        assert processing_record.edit_status == "processing"

        record.edit_status = "completed"
        test_db.commit()
        test_db.refresh(record)

        completed_record = module.get_edit_record(record.edit_id)
        assert completed_record.edit_status == "completed"

    def test_edit_request_submit_immediate_return(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_submit_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_submit_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)

        start_time = time.time()
        record = module.create_edit_record(
            video=video,
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
        )
        submit_time = time.time() - start_time

        assert submit_time < 1.0
        assert record.edit_id is not None
        assert record.edit_status == "pending"

    def test_edit_cut_time_accuracy(self, test_db):
        module = EditModule(test_db)

        test_cases = [
            {
                "params": {"start": 10.0, "end": 50.0},
                "video_duration": 120.0,
                "expected_valid": True,
                "expected_cut_duration": 40.0,
            },
            {
                "params": {"start": 0.0, "end": 30.0},
                "video_duration": 120.0,
                "expected_valid": True,
                "expected_cut_duration": 30.0,
            },
            {
                "params": {"start": 60.0, "end": 120.0},
                "video_duration": 120.0,
                "expected_valid": True,
                "expected_cut_duration": 60.0,
            },
        ]

        for i, case in enumerate(test_cases):
            valid, error = module.validate_edit_params(
                edit_type="cut",
                edit_params=case["params"],
                video_duration=case["video_duration"],
            )
            assert valid == case["expected_valid"], f"Test case {i} failed: {error}"

            if valid:
                actual_duration = case["params"]["end"] - case["params"]["start"]
                assert abs(actual_duration - case["expected_cut_duration"]) < 0.01

    def test_edit_merge_correctness(self, test_db, test_settings):
        module = EditModule(test_db)

        video1 = test_data_builder.create_video_info(
            video_id="video_merge_001",
            video_format="mp4",
            video_duration=30.0,
            storage_path=str(test_settings.uploads_dir / "video_merge_001.mp4"),
        )
        video2 = test_data_builder.create_video_info(
            video_id="video_merge_002",
            video_format="mp4",
            video_duration=45.0,
            storage_path=str(test_settings.uploads_dir / "video_merge_002.mp4"),
        )
        video3 = test_data_builder.create_video_info(
            video_id="video_merge_003",
            video_format="mp4",
            video_duration=15.0,
            storage_path=str(test_settings.uploads_dir / "video_merge_003.mp4"),
        )

        test_db.add(video1)
        test_db.add(video2)
        test_db.add(video3)
        test_db.commit()

        valid, error = module.validate_edit_params(
            edit_type="merge",
            edit_params={"video_ids": ["video_merge_001", "video_merge_002", "video_merge_003"]},
            video_duration=30.0,
        )
        assert valid is True

        total_duration = 30.0 + 45.0 + 15.0
        assert total_duration == 90.0

    def test_watermark_add_effect(self, test_db, test_settings):
        from videoprocess.modules.watermark import WatermarkModule

        video = test_data_builder.create_video_info(
            video_id="video_watermark_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_watermark_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        video_file = test_settings.uploads_dir / "video_watermark_001.mp4"
        video_file.write_bytes(b"fake_video_content" * 1000)

        module = WatermarkModule(test_db)
        result = module.add_text_watermark(
            video=video,
            text="VideoProcess",
            position="bottom-right",
            opacity=0.7,
        )

        assert result["success"] is True
        assert result["watermark_type"] == "text"
        assert result["text"] == "VideoProcess"
        assert result["position"] == "bottom-right"

    def test_watermark_different_positions(self, test_db, test_settings):
        from videoprocess.modules.watermark import WatermarkModule

        video = test_data_builder.create_video_info(
            video_id="video_watermark_pos_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_watermark_pos_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        video_file = test_settings.uploads_dir / "video_watermark_pos_001.mp4"
        video_file.write_bytes(b"fake_video_content" * 1000)

        module = WatermarkModule(test_db)

        positions = ["top-left", "top-right", "bottom-left", "bottom-right", "center"]

        for position in positions:
            result = module.add_text_watermark(
                video=video,
                text=f"Watermark-{position}",
                position=position,
            )
            assert result["success"] is True
            assert result["position"] == position

    def test_watermark_available_positions(self, test_db):
        from videoprocess.modules.watermark import WatermarkModule

        module = WatermarkModule(test_db)
        positions = module.get_available_positions()

        assert len(positions) == 7
        assert "top-left" in positions
        assert "top-right" in positions
        assert "bottom-left" in positions
        assert "bottom-right" in positions
        assert "center" in positions

    def test_edit_exception_handling(self, test_db, test_settings):
        module = EditModule(test_db)

        test_scenarios = test_data_builder.create_edit_scenarios()

        for scenario in test_scenarios:
            name = scenario["name"]
            params = scenario["params"]
            video_duration = scenario["video_duration"]
            expected = scenario["expected"]

            edit_type = params.get("edit_type", "cut")
            edit_params = params.get("params", {})

            valid, error = module.validate_edit_params(
                edit_type=edit_type,
                edit_params=edit_params,
                video_duration=video_duration,
            )

            if expected.get("valid", True):
                assert valid is True, f"Scenario {name} should be valid: {error}"
            else:
                assert valid is False, f"Scenario {name} should be invalid"
                if expected.get("error_contains"):
                    assert expected["error_contains"] in error

    def test_edit_failure_records_error(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_edit_fail_001",
            video_format="mp4",
            video_duration=120.0,
            storage_path="/nonexistent/path/non_existent.mp4",
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)
        record = module.create_edit_record(
            video=video,
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
        )

        result = module.execute_cut(video, record)

        assert result["success"] is False
        assert "error" in result

        updated = module.get_edit_record(record.edit_id)
        assert updated.edit_status == "failed"
        assert updated.error_message is not None

    def test_edit_list_records(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_edit_list_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_edit_list_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)

        completed_edit = test_data_builder.create_edit_record(
            video_id=video.video_id,
            edit_type="cut",
            edit_status="completed",
        )
        failed_edit = test_data_builder.create_edit_record(
            video_id=video.video_id,
            edit_type="merge",
            edit_status="failed",
            error_message="Merge failed",
        )
        pending_edit = test_data_builder.create_edit_record(
            video_id=video.video_id,
            edit_type="cut",
            edit_status="pending",
        )

        test_db.add(completed_edit)
        test_db.add(failed_edit)
        test_db.add(pending_edit)
        test_db.commit()

        all_records = module.list_edit_records(video_id=video.video_id)
        assert len(all_records) == 3

        cut_records = module.list_edit_records(video_id=video.video_id, edit_type="cut")
        assert len(cut_records) == 2

        merge_records = module.list_edit_records(video_id=video.video_id, edit_type="merge")
        assert len(merge_records) == 1

    def test_edit_output_path(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_output_path_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "video_output_path_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)

        cut_record = module.create_edit_record(
            video=video,
            edit_type="cut",
            edit_params={"start": 10.0, "end": 50.0},
        )

        assert cut_record.output_path is not None
        assert "video_output_path_001" in cut_record.output_path
        assert "_cut" in cut_record.output_path

        merge_record = module.create_edit_record(
            video=video,
            edit_type="merge",
            edit_params={"video_ids": ["video_output_path_001", "video_002"]},
        )

        assert merge_record.output_path is not None
        assert "_merge" in merge_record.output_path

    def test_edit_duration_recording(self, test_db, test_settings):
        module = EditModule(test_db)

        quick_edit = test_data_builder.create_edit_record(
            edit_type="cut",
            edit_status="completed",
            duration=5.5,
        )
        medium_edit = test_data_builder.create_edit_record(
            edit_type="merge",
            edit_status="completed",
            duration=30.0,
        )
        long_edit = test_data_builder.create_edit_record(
            edit_type="cut",
            edit_status="completed",
            duration=120.0,
        )

        assert quick_edit.duration == 5.5
        assert medium_edit.duration == 30.0
        assert long_edit.duration == 120.0

        assert quick_edit.duration < medium_edit.duration
        assert medium_edit.duration < long_edit.duration

    def test_edit_all_scenarios(self, test_db):
        module = EditModule(test_db)
        scenarios = test_data_builder.create_edit_scenarios()

        for scenario in scenarios:
            name = scenario["name"]
            params = scenario["params"]
            video_duration = scenario["video_duration"]
            expected = scenario["expected"]

            edit_type = params.get("edit_type", "cut")
            edit_params = params.get("params", {})

            valid, error = module.validate_edit_params(
                edit_type=edit_type,
                edit_params=edit_params,
                video_duration=video_duration,
            )

            if expected.get("valid", True):
                assert valid is True, f"Scenario {name} should be valid: {error}"
            else:
                assert valid is False, f"Scenario {name} should be invalid"
                if expected.get("error_contains"):
                    assert expected["error_contains"] in error

    def test_edit_with_zero_duration_clip(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 30.0, "end": 30.0},
            video_duration=120.0,
        )
        assert valid is False
        assert "大于" in error

    def test_edit_with_exact_boundary(self, test_db):
        module = EditModule(test_db)

        valid, error = module.validate_edit_params(
            edit_type="cut",
            edit_params={"start": 0.0, "end": 120.0},
            video_duration=120.0,
        )
        assert valid is True

    def test_edit_multiple_cuts_on_same_video(self, test_db, test_settings):
        video = test_data_builder.create_video_info(
            video_id="video_multi_cut_001",
            video_format="mp4",
            video_duration=120.0,
            storage_path=str(test_settings.uploads_dir / "video_multi_cut_001.mp4"),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        module = EditModule(test_db)

        cuts = [
            {"start": 0.0, "end": 30.0},
            {"start": 30.0, "end": 60.0},
            {"start": 60.0, "end": 90.0},
            {"start": 90.0, "end": 120.0},
        ]

        for i, cut_params in enumerate(cuts):
            record = module.create_edit_record(
                video=video,
                edit_type="cut",
                edit_params=cut_params,
            )
            assert record.edit_id is not None
            assert record.edit_type == "cut"

        all_cuts = module.list_edit_records(video_id=video.video_id, edit_type="cut")
        assert len(all_cuts) == len(cuts)
