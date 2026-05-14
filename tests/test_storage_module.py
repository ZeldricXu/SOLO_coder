import sys
import os
import time
from pathlib import Path
from datetime import datetime, timedelta
from typing import Dict, Any, List

import pytest

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from videoprocess.modules.storage import StorageModule
from videoprocess.config import DEFAULT_CLEANUP_STRATEGY
from tests.test_data_builder import test_data_builder


class TestStorageModule:
    def setup_method(self):
        test_data_builder.reset_counters()

    def test_storage_usage_calculation(self, test_db, test_settings):
        module = StorageModule(test_db)

        video1 = test_settings.uploads_dir / "video_usage_001.mp4"
        video1.write_bytes(b"content" * 100000)

        video2 = test_settings.uploads_dir / "video_usage_002.mp4"
        video2.write_bytes(b"content" * 50000)

        thumbnail = test_settings.thumbnails_dir / "video_thumb.jpg"
        thumbnail.write_bytes(b"thumb_content" * 1000)

        usage = module.get_storage_usage()

        assert usage["total_size_bytes"] > 0
        assert usage["total_size_mb"] > 0
        assert usage["file_counts"]["uploads"] == 2
        assert usage["file_counts"]["thumbnails"] == 1
        assert usage["needs_cleanup"] is False

    def test_file_exists_check(self, test_db, test_settings):
        module = StorageModule(test_db)

        existing_file = test_settings.uploads_dir / "existing.mp4"
        existing_file.write_bytes(b"test_content")

        assert module.file_exists(str(existing_file)) is True
        assert module.file_exists("/nonexistent/path/file.mp4") is False

    def test_get_file_size(self, test_db, test_settings):
        module = StorageModule(test_db)

        file_path = test_settings.uploads_dir / "size_test.mp4"
        content = b"test_content" * 1000
        file_path.write_bytes(content)

        size = module.get_file_size(str(file_path))
        assert size == len(content)

        assert module.get_file_size("/nonexistent/file.mp4") == 0

    def test_delete_file(self, test_db, test_settings):
        module = StorageModule(test_db)

        file_to_delete = test_settings.uploads_dir / "to_delete.mp4"
        file_to_delete.write_bytes(b"delete_me")

        assert module.file_exists(str(file_to_delete)) is True
        assert module.delete_file(str(file_to_delete)) is True
        assert module.file_exists(str(file_to_delete)) is False

        assert module.delete_file("/nonexistent/path.mp4") is False

    def test_copy_file(self, test_db, test_settings):
        module = StorageModule(test_db)

        source = test_settings.uploads_dir / "source.mp4"
        source.write_bytes(b"source_content")

        target = test_settings.transcoded_dir / "target.mp4"

        assert module.copy_file(str(source), str(target)) is True
        assert module.file_exists(str(target)) is True
        assert module.get_file_size(str(target)) == module.get_file_size(str(source))

    def test_move_file(self, test_db, test_settings):
        module = StorageModule(test_db)

        source = test_settings.uploads_dir / "source_move.mp4"
        source.write_bytes(b"move_me_content")
        original_size = module.get_file_size(str(source))

        target = test_settings.transcoded_dir / "target_move.mp4"

        assert module.move_file(str(source), str(target)) is True
        assert module.file_exists(str(source)) is False
        assert module.file_exists(str(target)) is True
        assert module.get_file_size(str(target)) == original_size

    def test_cleanup_strategy_config_loading(self, test_db):
        module = StorageModule(test_db)

        assert module.cleanup_config["source_expire_days"] == 30
        assert module.cleanup_config["transcoded_expire_days"] == 15
        assert module.cleanup_config["thumbnail_expire_days"] == 7
        assert module.cleanup_config["max_storage_gb"] == 500
        assert module.cleanup_config["cleanup_percentage"] == 85

    def test_expired_videos_auto_cleanup(self, test_db, test_settings):
        module = StorageModule(test_db)

        expired_video = test_settings.uploads_dir / "expired_video.mp4"
        expired_video.write_bytes(b"old_content" * 1000)

        recent_video = test_settings.uploads_dir / "recent_video.mp4"
        recent_video.write_bytes(b"new_content" * 1000)

        old_time = (datetime.now() - timedelta(days=40)).timestamp()
        os.utime(expired_video, (old_time, old_time))

        video_expired = test_data_builder.create_video_info(
            video_id="expired_001",
            video_format="mp4",
            storage_path=str(expired_video),
            days_ago=40,
        )
        video_recent = test_data_builder.create_video_info(
            video_id="recent_001",
            video_format="mp4",
            storage_path=str(recent_video),
            days_ago=5,
        )

        test_db.add(video_expired)
        test_db.add(video_recent)
        test_db.commit()

        usage_before = module.get_storage_usage()

        result = module.cleanup_expired_files()

        assert result["deleted_count"] >= 1
        assert result["deleted_size_bytes"] > 0

        assert not expired_video.exists()
        assert recent_video.exists()

    def test_reference_integrity_check(self, test_db, test_settings):
        module = StorageModule(test_db)

        video = test_data_builder.create_video_info(
            video_id="ref_check_001",
            video_format="mp4",
            storage_path=str(test_settings.uploads_dir / "ref_check.mp4"),
        )
        test_db.add(video)
        test_db.commit()

        transcode_record = test_data_builder.create_transcode_record(
            video_id=video.video_id,
            output_path=str(test_settings.transcoded_dir / "ref_check_transcoded.mp4"),
        )
        test_db.add(transcode_record)
        test_db.commit()

        video_file = test_settings.uploads_dir / "ref_check.mp4"
        video_file.write_bytes(b"video_data")

        transcoded_file = test_settings.transcoded_dir / "ref_check_transcoded.mp4"
        transcoded_file.write_bytes(b"transcoded_data")

        assert module.file_exists(video.storage_path)
        assert module.file_exists(transcode_record.output_path)

        assert module.get_file_size(video.storage_path) > 0
        assert module.get_file_size(transcode_record.output_path) > 0

    def test_cleanup_after_space_release(self, test_db, test_settings):
        module = StorageModule(test_db)

        files_to_delete = []
        for i in range(5):
            file_path = test_settings.uploads_dir / f"cleanup_test_{i}.mp4"
            file_path.write_bytes(b"content" * (i + 1) * 1000)

            old_time = (datetime.now() - timedelta(days=40 + i)).timestamp()
            os.utime(file_path, (old_time, old_time))

            files_to_delete.append(file_path)

            video = test_data_builder.create_video_info(
                video_id=f"cleanup_{i}",
                storage_path=str(file_path),
                days_ago=40 + i,
            )
            test_db.add(video)
        test_db.commit()

        usage_before = module.get_storage_usage()

        result = module.cleanup_expired_files()

        usage_after = module.get_storage_usage()

        assert usage_after["total_size_bytes"] < usage_before["total_size_bytes"]
        assert result["deleted_count"] == 5

    def test_list_storage_contents(self, test_db, test_settings):
        module = StorageModule(test_db)

        for i in range(3):
            file_path = test_settings.uploads_dir / f"list_test_{i}.mp4"
            file_path.write_bytes(f"content_{i}".encode() * 100)

        contents = module.list_storage_contents("uploads")

        assert len(contents) == 3
        assert all("name" in item for item in contents)
        assert all("size_bytes" in item for item in contents)
        assert all("modified_at" in item for item in contents)

    def test_get_directory_size(self, test_db, test_settings):
        module = StorageModule(test_db)

        for i in range(5):
            file_path = test_settings.uploads_dir / f"dir_size_{i}.mp4"
            file_path.write_bytes(b"content" * 1000)

        size_info = module.get_directory_size("uploads")

        assert size_info["file_count"] == 5
        assert size_info["size_bytes"] == 5 * len(b"content" * 1000)
        assert size_info["size_mb"] > 0

    def test_temp_directory_cleanup(self, test_db, test_settings):
        module = StorageModule(test_db)

        old_temp = test_settings.temp_dir / "old_temp.tmp"
        old_temp.write_bytes(b"temporary_data")

        old_time = (datetime.now() - timedelta(hours=48)).timestamp()
        os.utime(old_temp, (old_time, old_time))

        new_temp = test_settings.temp_dir / "new_temp.tmp"
        new_temp.write_bytes(b"recent_data")

        result = module.cleanup_expired_files()

        assert not old_temp.exists()
        assert new_temp.exists()

    def test_thumbnail_cleanup(self, test_db, test_settings):
        module = StorageModule(test_db)

        old_thumb = test_settings.thumbnails_dir / "old_thumb.jpg"
        old_thumb.write_bytes(b"thumbnail_data")

        old_time = (datetime.now() - timedelta(days=10)).timestamp()
        os.utime(old_thumb, (old_time, old_time))

        new_thumb = test_settings.thumbnails_dir / "new_thumb.jpg"
        new_thumb.write_bytes(b"recent_thumbnail")

        thumbnail_old = test_data_builder.create_thumbnail_info(
            video_id="video_001",
            thumbnail_path=str(old_thumb),
            days_ago=10,
        )
        thumbnail_new = test_data_builder.create_thumbnail_info(
            video_id="video_002",
            thumbnail_path=str(new_thumb),
            days_ago=1,
        )

        test_db.add(thumbnail_old)
        test_db.add(thumbnail_new)
        test_db.commit()

        result = module.cleanup_expired_files()

        assert not old_thumb.exists()
        assert new_thumb.exists()

    def test_cleanup_threshold_check(self, test_db, test_settings):
        module = StorageModule(test_db)

        usage = module.get_storage_usage()

        assert "needs_cleanup" in usage
        assert "usage_percentage" in usage
        assert "max_storage_gb" in usage

        assert usage["usage_percentage"] < module.cleanup_config["cleanup_percentage"]
        assert usage["needs_cleanup"] is False

    def test_mixed_expiration_cleanup(self, test_db, test_settings):
        module = StorageModule(test_db)

        for i in range(10):
            days_ago = 5 + i * 5
            file_path = test_settings.uploads_dir / f"mixed_{i}.mp4"
            file_path.write_bytes(b"mixed_content" * 1000)

            old_time = (datetime.now() - timedelta(days=days_ago)).timestamp()
            os.utime(file_path, (old_time, old_time))

            video = test_data_builder.create_video_info(
                video_id=f"mixed_{i}",
                storage_path=str(file_path),
                days_ago=days_ago,
            )
            test_db.add(video)
        test_db.commit()

        result = module.cleanup_expired_files()

        files_left = [f for f in test_settings.uploads_dir.iterdir() if f.is_file()]

        for f in files_left:
            stat = f.stat()
            file_age = (datetime.now().timestamp() - stat.st_mtime) / (24 * 3600)
            assert file_age < 30

    def test_nonexistent_directory_listing(self, test_db):
        module = StorageModule(test_db)

        contents = module.list_storage_contents("nonexistent_dir")
        assert contents == []

    def test_get_multiple_directory_sizes(self, test_db, test_settings):
        module = StorageModule(test_db)

        test_settings.uploads_dir / "file1.mp4".write_bytes(b"a" * 1000)
        test_settings.uploads_dir / "file2.mp4".write_bytes(b"b" * 2000)
        test_settings.transcoded_dir / "trans1.mp4".write_bytes(b"c" * 3000)
        test_settings.thumbnails_dir / "thumb1.jpg".write_bytes(b"d" * 100)

        uploads_size = module.get_directory_size("uploads")
        transcoded_size = module.get_directory_size("transcoded")
        thumbnails_size = module.get_directory_size("thumbnails")

        assert uploads_size["file_count"] == 2
        assert uploads_size["size_bytes"] == 3000

        assert transcoded_size["file_count"] == 1
        assert transcoded_size["size_bytes"] == 3000

        assert thumbnails_size["file_count"] == 1
        assert thumbnails_size["size_bytes"] == 100

    def test_cleanup_with_preserved_references(self, test_db, test_settings):
        module = StorageModule(test_db)

        video_file = test_settings.uploads_dir / "preserved.mp4"
        video_file.write_bytes(b"preserved_content" * 1000)

        old_time = (datetime.now() - timedelta(days=35)).timestamp()
        os.utime(video_file, (old_time, old_time))

        transcode_file = test_settings.transcoded_dir / "preserved_transcoded.mp4"
        transcode_file.write_bytes(b"preserved_transcode" * 500)

        old_trans_time = (datetime.now() - timedelta(days=20)).timestamp()
        os.utime(transcode_file, (old_trans_time, old_trans_time))

        video = test_data_builder.create_video_info(
            video_id="preserved_001",
            storage_path=str(video_file),
            days_ago=35,
        )
        test_db.add(video)
        test_db.commit()

        usage_before = module.get_storage_usage()

        result = module.cleanup_expired_files()

        usage_after = module.get_storage_usage()

        assert usage_after["total_size_bytes"] < usage_before["total_size_bytes"]

    def test_capacity_based_cleanup_trigger(self, test_db, test_settings):
        module = StorageModule(test_db)

        usage = module.get_storage_usage()

        original_threshold = module.cleanup_config["cleanup_percentage"]
        module.cleanup_config["cleanup_percentage"] = 0.1

        high_threshold = module.get_storage_usage()

        if high_threshold["needs_cleanup"]:
            result = module.cleanup_by_capacity()
            assert "deleted_count" in result
            assert "message" in result

        module.cleanup_config["cleanup_percentage"] = original_threshold

    def test_delete_directory_contents(self, test_db, test_settings):
        module = StorageModule(test_db)

        for i in range(5):
            file_path = test_settings.temp_dir / f"temp_{i}.tmp"
            file_path.write_bytes(b"temp_data")

        for f in test_settings.temp_dir.iterdir():
            if f.is_file():
                module.delete_file(str(f))

        remaining = [f for f in test_settings.temp_dir.iterdir() if f.is_file()]
        assert len(remaining) == 0

    def test_storage_metrics_accuracy(self, test_db, test_settings):
        module = StorageModule(test_db)

        test_sizes = [1024, 2048, 4096, 8192, 16384]
        total_expected = sum(test_sizes)

        for i, size in enumerate(test_sizes):
            file_path = test_settings.uploads_dir / f"metrics_{i}.mp4"
            file_path.write_bytes(b"x" * size)

        usage = module.get_storage_usage()

        assert usage["total_size_bytes"] >= total_expected
        assert usage["file_counts"]["uploads"] == len(test_sizes)
        assert usage["total_size_mb"] == usage["total_size_bytes"] / (1024 * 1024)

    def test_cleanup_scenarios(self, test_db, test_settings):
        module = StorageModule(test_db)
        scenarios = test_data_builder.create_cleanup_scenarios()

        for scenario in scenarios:
            name = scenario["name"]

            if "videos" in scenario:
                for i, video_info in enumerate(scenario["videos"]):
                    file_path = test_settings.uploads_dir / f"{name}_{i}.mp4"
                    file_path.write_bytes(b"scenario_content" * 100)

                    old_time = (datetime.now() - timedelta(days=video_info["days_ago"])).timestamp()
                    os.utime(file_path, (old_time, old_time))

                    video = test_data_builder.create_video_info(
                        video_id=f"{name}_{i}",
                        storage_path=str(file_path),
                        days_ago=video_info["days_ago"],
                    )
                    test_db.add(video)

            if "thumbnails" in scenario:
                for i, thumb_info in enumerate(scenario["thumbnails"]):
                    file_path = test_settings.thumbnails_dir / f"{name}_thumb_{i}.jpg"
                    file_path.write_bytes(b"thumbnail_content")

                    old_time = (datetime.now() - timedelta(days=thumb_info["days_ago"])).timestamp()
                    os.utime(file_path, (old_time, old_time))

                    thumb = test_data_builder.create_thumbnail_info(
                        video_id=f"thumb_{name}_{i}",
                        thumbnail_path=str(file_path),
                        days_ago=thumb_info["days_ago"],
                    )
                    test_db.add(thumb)

            test_db.commit()

            result = module.cleanup_expired_files()

            assert result["deleted_count"] >= 0

    def test_cleanup_config_validation(self, test_db):
        module = StorageModule(test_db)

        assert module.cleanup_config["source_expire_days"] == DEFAULT_CLEANUP_STRATEGY["source_expire_days"]
        assert module.cleanup_config["transcoded_expire_days"] == DEFAULT_CLEANUP_STRATEGY["transcoded_expire_days"]
        assert module.cleanup_config["thumbnail_expire_days"] == DEFAULT_CLEANUP_STRATEGY["thumbnail_expire_days"]
        assert module.cleanup_config["max_storage_gb"] == DEFAULT_CLEANUP_STRATEGY["max_storage_gb"]
        assert module.cleanup_config["cleanup_percentage"] == DEFAULT_CLEANUP_STRATEGY["cleanup_percentage"]
        assert module.cleanup_config["check_interval_hours"] == DEFAULT_CLEANUP_STRATEGY["check_interval_hours"]

    def test_empty_storage_usage(self, test_db, test_settings):
        module = StorageModule(test_db)

        usage = module.get_storage_usage()

        assert usage["total_size_bytes"] == 0
        assert usage["total_size_mb"] == 0
        assert usage["usage_percentage"] == 0
        assert usage["needs_cleanup"] is False
        assert sum(usage["file_counts"].values()) == 0
