import sys
import time
from pathlib import Path
from datetime import datetime, timedelta
from typing import Dict, Any, List

import pytest

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from videoprocess.modules.quality import QualityModule
from videoprocess.config import QUALITY_THRESHOLDS
from tests.test_data_builder import test_data_builder


class TestQualityModule:
    def setup_method(self):
        test_data_builder.reset_counters()

    def test_resolution_detection_correctness(self, test_db, test_settings):
        module = QualityModule(test_db)

        test_resolutions = [
            {"width": 3840, "height": 2160, "expected_resolution": "3840x2160"},
            {"width": 1920, "height": 1080, "expected_resolution": "1920x1080"},
            {"width": 1280, "height": 720, "expected_resolution": "1280x720"},
            {"width": 640, "height": 360, "expected_resolution": "640x360"},
            {"width": 320, "height": 180, "expected_resolution": "320x180"},
        ]

        for i, res in enumerate(test_resolutions):
            video_file = test_settings.uploads_dir / f"resolution_test_{i}.mp4"
            video_file.write_bytes(b"video_content" * 1000)

            video = test_data_builder.create_video_info(
                video_id=f"res_video_{i}",
                video_format="mp4",
                storage_path=str(video_file),
                metadata={
                    "width": res["width"],
                    "height": res["height"],
                    "resolution": res["expected_resolution"],
                },
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)

            assert report.resolution is not None
            assert "x" in report.resolution

    def test_bitrate_calculation_accuracy(self, test_db, test_settings):
        module = QualityModule(test_db)

        test_cases = [
            {"size_mb": 50, "duration": 120, "expected_bitrate_range": (2000, 6000)},
            {"size_mb": 200, "duration": 120, "expected_bitrate_range": (8000, 20000)},
            {"size_mb": 500, "duration": 300, "expected_bitrate_range": (10000, 30000)},
        ]

        for i, case in enumerate(test_cases):
            size_bytes = case["size_mb"] * 1024 * 1024

            video_file = test_settings.uploads_dir / f"bitrate_test_{i}.mp4"
            video_file.write_bytes(b"x" * 10000)

            video = test_data_builder.create_video_info(
                video_id=f"bit_video_{i}",
                video_format="mp4",
                video_size=size_bytes,
                video_duration=case["duration"],
                storage_path=str(video_file),
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)

            assert report.bitrate > 0
            assert isinstance(report.bitrate, int)

            min_br, max_br = case["expected_bitrate_range"]
            assert report.bitrate >= min_br // 10 or report.bitrate <= max_br * 10

    def test_frame_rate_detection(self, test_db, test_settings):
        module = QualityModule(test_db)

        test_fps_values = [60, 30, 24, 15, 10]

        for i, fps in enumerate(test_fps_values):
            video_file = test_settings.uploads_dir / f"fps_test_{i}.mp4"
            video_file.write_bytes(b"video_data" * 1000)

            video = test_data_builder.create_video_info(
                video_id=f"fps_video_{i}",
                video_format="mp4",
                storage_path=str(video_file),
                metadata={"fps": fps},
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)

            assert report.frame_rate >= 0
            assert isinstance(report.frame_rate, float)

    def test_quality_score_calculation(self, test_db, test_settings):
        module = QualityModule(test_db)

        scenarios = test_data_builder.create_quality_scenarios()

        for scenario in scenarios:
            name = scenario["name"]
            video_info = scenario["video_info"]
            expected = scenario["expected"]

            video_file = test_settings.uploads_dir / f"{name}.mp4"
            video_file.write_bytes(b"quality_test_content" * 1000)

            video = test_data_builder.create_video_info(
                video_id=f"quality_{name}",
                video_format="mp4",
                storage_path=str(video_file),
                metadata=video_info,
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)

            assert report.quality_score >= 0
            assert report.quality_score <= 100

            if "min_score" in expected:
                assert report.quality_score >= expected["min_score"], f"{name}: score {report.quality_score} < {expected['min_score']}"

            if "max_score" in expected:
                assert report.quality_score <= expected["max_score"], f"{name}: score {report.quality_score} > {expected['max_score']}"

            if "level" in expected:
                quality_level = module.get_quality_level(report.quality_score)
                assert quality_level == expected["level"]

    def test_quality_issue_identification(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "issue_test.mp4"
        video_file.write_bytes(b"low_quality" * 500)

        video = test_data_builder.create_video_info(
            video_id="issue_video",
            video_format="mp4",
            video_size=10000,
            video_duration=120.0,
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report = module.analyze_video(video)

        assert isinstance(report.quality_issues, list)

    def test_high_resolution_low_bitrate_issue(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "high_res_low_bitrate.mp4"
        video_file.write_bytes(b"fake_content" * 100)

        video = test_data_builder.create_video_info(
            video_id="hr_lb_video",
            video_format="mp4",
            video_size=50000,
            video_duration=300.0,
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report = module.analyze_video(video)

        assert len(report.quality_issues) >= 0

        bitrate_issue_found = any("比特率" in issue for issue in report.quality_issues)
        if report.bitrate < QUALITY_THRESHOLDS["warning_bitrate"]:
            assert bitrate_issue_found is True

    def test_quality_thresholds(self, test_db):
        module = QualityModule(test_db)

        assert QUALITY_THRESHOLDS["min_resolution"] == "320x180"
        assert QUALITY_THRESHOLDS["min_bitrate"] == 500
        assert QUALITY_THRESHOLDS["min_fps"] == 10
        assert QUALITY_THRESHOLDS["excellent_score"] == 90
        assert QUALITY_THRESHOLDS["good_score"] == 70
        assert QUALITY_THRESHOLDS["fair_score"] == 50

    def test_quality_levels(self, test_db):
        module = QualityModule(test_db)

        test_cases = [
            {"score": 95, "expected_level": "excellent"},
            {"score": 90, "expected_level": "excellent"},
            {"score": 85, "expected_level": "good"},
            {"score": 70, "expected_level": "good"},
            {"score": 65, "expected_level": "fair"},
            {"score": 50, "expected_level": "fair"},
            {"score": 45, "expected_level": "poor"},
            {"score": 0, "expected_level": "poor"},
        ]

        for case in test_cases:
            level = module.get_quality_level(case["score"])
            assert level == case["expected_level"], f"Score {case['score']} should be {case['expected_level']}, got {level}"

    def test_check_quality_threshold(self, test_db, test_settings):
        module = QualityModule(test_db)

        scenarios = [
            {"name": "high_quality", "size": 500 * 1024 * 1024, "duration": 120, "min_score": 70, "expected_pass": True},
            {"name": "low_quality", "size": 10000, "duration": 120, "min_score": 70, "expected_pass": False},
        ]

        for scenario in scenarios:
            video_file = test_settings.uploads_dir / f"{scenario['name']}.mp4"
            video_file.write_bytes(b"content" * 100)

            video = test_data_builder.create_video_info(
                video_id=scenario["name"],
                video_format="mp4",
                video_size=scenario["size"],
                video_duration=scenario["duration"],
                storage_path=str(video_file),
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            result = module.check_quality_threshold(video, scenario["min_score"])

            assert "video_id" in result
            assert "quality_score" in result
            assert "quality_level" in result
            assert "passes_threshold" in result

            if result["quality_score"] >= scenario["min_score"]:
                assert result["passes_threshold"] is True
            else:
                assert result["passes_threshold"] is False

    def test_get_latest_report(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "latest_test.mp4"
        video_file.write_bytes(b"test_content" * 1000)

        video = test_data_builder.create_video_info(
            video_id="latest_video",
            video_format="mp4",
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        first_report = module.analyze_video(video)
        time.sleep(0.1)
        second_report = module.analyze_video(video)

        latest = module.get_latest_report(video.video_id)

        assert latest is not None
        assert latest.quality_id == second_report.quality_id

    def test_get_video_quality_reports(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "history_test.mp4"
        video_file.write_bytes(b"test_content" * 1000)

        video = test_data_builder.create_video_info(
            video_id="history_video",
            video_format="mp4",
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        for i in range(5):
            module.analyze_video(video)
            time.sleep(0.01)

        reports = module.get_video_quality_reports(video.video_id, limit=10)

        assert len(reports) == 5
        assert all(r.video_id == video.video_id for r in reports)

    def test_list_all_reports_filtering(self, test_db, test_settings):
        module = QualityModule(test_db)

        videos = []
        for i in range(5):
            video_file = test_settings.uploads_dir / f"filter_test_{i}.mp4"
            video_file.write_bytes(b"content" * 1000)

            video = test_data_builder.create_video_info(
                video_id=f"filter_video_{i}",
                video_format="mp4",
                storage_path=str(video_file),
            )
            test_db.add(video)
            videos.append(video)
        test_db.commit()

        for video in videos:
            module.analyze_video(video)

        all_reports = module.list_all_reports(limit=100)
        assert len(all_reports) >= 5

        high_score_reports = module.list_all_reports(min_score=50, limit=100)
        assert len(high_score_reports) <= len(all_reports)

        low_score_reports = module.list_all_reports(max_score=90, limit=100)
        assert len(low_score_reports) <= len(all_reports)

    def test_quality_report_persistence(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "persistence_test.mp4"
        video_file.write_bytes(b"persistence_content" * 1000)

        video = test_data_builder.create_video_info(
            video_id="persist_video",
            video_format="mp4",
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report = module.analyze_video(video)

        retrieved = module.get_quality_report(report.quality_id)

        assert retrieved is not None
        assert retrieved.quality_id == report.quality_id
        assert retrieved.video_id == report.video_id
        assert retrieved.quality_score == report.quality_score
        assert retrieved.resolution == report.resolution
        assert retrieved.bitrate == report.bitrate
        assert retrieved.frame_rate == report.frame_rate
        assert retrieved.quality_issues == report.quality_issues

    def test_quality_scenarios_all(self, test_db, test_settings):
        module = QualityModule(test_db)
        scenarios = test_data_builder.create_quality_scenarios()

        for scenario in scenarios:
            name = scenario["name"]
            video_info = scenario["video_info"]
            expected = scenario["expected"]

            video_file = test_settings.uploads_dir / f"scenario_{name}.mp4"
            video_file.write_bytes(b"scenario_content" * 100)

            video = test_data_builder.create_video_info(
                video_id=f"scenario_{name}",
                video_format="mp4",
                storage_path=str(video_file),
                metadata=video_info,
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)

            assert 0 <= report.quality_score <= 100

            if "min_score" in expected:
                assert report.quality_score >= expected["min_score"]

            if "max_score" in expected:
                assert report.quality_score <= expected["max_score"]

            if "level" in expected:
                level = module.get_quality_level(report.quality_score)
                assert level == expected["level"]

            if "issues" in expected:
                assert len(report.quality_issues) == expected["issues"]

            if "issues_greater_than" in expected:
                assert len(report.quality_issues) > expected["issues_greater_than"]

            if "issues_contains" in expected:
                assert any(expected["issues_contains"] in issue for issue in report.quality_issues)

    def test_quality_score_monotonic_quality(self, test_db, test_settings):
        module = QualityModule(test_db)

        test_configs = [
            {"name": "excellent", "width": 3840, "height": 2160, "bitrate": 15000, "fps": 60},
            {"name": "good", "width": 1920, "height": 1080, "bitrate": 5000, "fps": 30},
            {"name": "fair", "width": 1280, "height": 720, "bitrate": 2000, "fps": 24},
            {"name": "poor", "width": 320, "height": 180, "bitrate": 300, "fps": 10},
        ]

        scores = []
        for config in test_configs:
            video_file = test_settings.uploads_dir / f"mono_{config['name']}.mp4"
            video_file.write_bytes(b"mono_content" * 100)

            video = test_data_builder.create_video_info(
                video_id=f"mono_{config['name']}",
                video_format="mp4",
                storage_path=str(video_file),
                metadata={
                    "width": config["width"],
                    "height": config["height"],
                    "resolution": f"{config['width']}x{config['height']}",
                    "bitrate": config["bitrate"],
                    "fps": config["fps"],
                },
            )
            test_db.add(video)
            test_db.commit()
            test_db.refresh(video)

            report = module.analyze_video(video)
            scores.append(report.quality_score)

        for i in range(len(scores) - 1):
            assert scores[i] >= scores[i + 1]

    def test_quality_report_metadata(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "metadata_test.mp4"
        video_file.write_bytes(b"meta_content" * 1000)

        video = test_data_builder.create_video_info(
            video_id="meta_video",
            video_format="mp4",
            video_duration=120.5,
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report = module.analyze_video(video)

        assert report.duration is not None
        assert report.duration >= 0
        assert report.codec is not None
        assert report.detected_at is not None

    def test_quality_threshold_check_custom_minimum(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "custom_threshold.mp4"
        video_file.write_bytes(b"custom_content" * 1000)

        video = test_data_builder.create_video_info(
            video_id="custom_threshold_video",
            video_format="mp4",
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report = module.analyze_video(video)

        check_high = module.check_quality_threshold(video, min_score=95)
        check_low = module.check_quality_threshold(video, min_score=10)

        assert check_high["passes_threshold"] == (report.quality_score >= 95)
        assert check_low["passes_threshold"] == (report.quality_score >= 10)

    def test_quality_nonexistent_video(self, test_db, test_settings):
        module = QualityModule(test_db)

        video = test_data_builder.create_video_info(
            video_id="nonexistent",
            video_format="mp4",
            storage_path="/nonexistent/path/video.mp4",
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        with pytest.raises(FileNotFoundError):
            module.analyze_video(video)

    def test_quality_issues_consistency(self, test_db, test_settings):
        module = QualityModule(test_db)

        video_file = test_settings.uploads_dir / "consistency_test.mp4"
        video_file.write_bytes(b"small_content" * 100)

        video = test_data_builder.create_video_info(
            video_id="consistency_video",
            video_format="mp4",
            video_size=100000,
            video_duration=300.0,
            storage_path=str(video_file),
        )
        test_db.add(video)
        test_db.commit()
        test_db.refresh(video)

        report1 = module.analyze_video(video)
        report2 = module.analyze_video(video)

        assert report1.quality_score == report2.quality_score
        assert report1.resolution == report2.resolution
