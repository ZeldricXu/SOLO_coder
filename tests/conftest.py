import sys
import shutil
import tempfile
from pathlib import Path
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from videoprocess.models import Base
from videoprocess.config import settings


class TestSettings:
    def __init__(self, tmp_path):
        self.tmp_path = tmp_path
        self.storage_dir = tmp_path / "test_storage"
        self.uploads_dir = tmp_path / "test_storage" / "uploads"
        self.transcoded_dir = tmp_path / "test_storage" / "transcoded"
        self.thumbnails_dir = tmp_path / "test_storage" / "thumbnails"
        self.temp_dir = tmp_path / "test_storage" / "temp"
        self.logs_dir = tmp_path / "test_storage" / "logs"
        self.database_url = f"sqlite:///{tmp_path}/test_videoprocess.db"

    def ensure_dirs(self):
        for dir_path in [
            self.storage_dir,
            self.uploads_dir,
            self.transcoded_dir,
            self.thumbnails_dir,
            self.temp_dir,
            self.logs_dir,
        ]:
            dir_path.mkdir(parents=True, exist_ok=True)


@pytest.fixture(scope="function")
def test_settings(tmp_path):
    test_settings = TestSettings(tmp_path)
    test_settings.ensure_dirs()
    return test_settings


@pytest.fixture(scope="function")
def test_db(test_settings):
    engine = create_engine(test_settings.database_url, echo=False)
    Base.metadata.create_all(bind=engine)
    TestingSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    db = TestingSession()

    try:
        yield db
    finally:
        db.close()
        Base.metadata.drop_all(bind=engine)


@pytest.fixture(scope="function")
def temp_video_file(test_settings):
    def _create_video_file(
        video_id: str = "video_001",
        format: str = "mp4",
        size_bytes: int = 1024 * 1024,
        content: Optional[bytes] = None,
    ):
        filename = f"{video_id}.{format}"
        file_path = test_settings.uploads_dir / filename

        if content is None:
            content = b"fake_video_content" * (size_bytes // 16)

        with open(file_path, "wb") as f:
            f.write(content[:size_bytes])

        return file_path

    return _create_video_file


@pytest.fixture(scope="function")
def expired_video_file(test_settings):
    def _create_expired_video(
        video_id: str = "expired_001",
        format: str = "mp4",
        days_ago: int = 45,
        size_bytes: int = 1024 * 1024,
    ):
        filename = f"{video_id}.{format}"
        file_path = test_settings.uploads_dir / filename

        content = b"expired_video_content" * (size_bytes // 20)
        with open(file_path, "wb") as f:
            f.write(content[:size_bytes])

        old_time = (datetime.now() - timedelta(days=days_ago)).timestamp()
        import os
        os.utime(file_path, (old_time, old_time))

        return file_path

    return _create_expired_video


@pytest.fixture(scope="function")
def temp_thumbnail_file(test_settings):
    def _create_thumbnail(
        video_id: str = "video_001",
        size_name: str = "medium",
        days_ago: int = 0,
    ):
        filename = f"{video_id}_{size_name}.jpg"
        file_path = test_settings.thumbnails_dir / filename

        content = b"fake_thumbnail_data"
        with open(file_path, "wb") as f:
            f.write(content)

        if days_ago > 0:
            import os
            old_time = (datetime.now() - timedelta(days=days_ago)).timestamp()
            os.utime(file_path, (old_time, old_time))

        return file_path

    return _create_thumbnail


@pytest.fixture(scope="function")
def patch_settings(monkeypatch, test_settings):
    monkeypatch.setattr("videoprocess.config.settings.storage_dir", test_settings.storage_dir)
    monkeypatch.setattr("videoprocess.config.settings.uploads_dir", test_settings.uploads_dir)
    monkeypatch.setattr("videoprocess.config.settings.transcoded_dir", test_settings.transcoded_dir)
    monkeypatch.setattr("videoprocess.config.settings.thumbnails_dir", test_settings.thumbnails_dir)
    monkeypatch.setattr("videoprocess.config.settings.temp_dir", test_settings.temp_dir)
    monkeypatch.setattr("videoprocess.config.settings.logs_dir", test_settings.logs_dir)
    monkeypatch.setattr("videoprocess.config.settings.database_url", test_settings.database_url)

    monkeypatch.setattr("videoprocess.config.settings.max_file_size", 100 * 1024 * 1024)
    monkeypatch.setattr("videoprocess.config.settings.debug", False)
    return test_settings


@pytest.fixture(scope="function")
def mock_video_metadata():
    def _create_metadata(
        width: int = 1920,
        height: int = 1080,
        fps: float = 30.0,
        bitrate: int = 5000,
        codec: str = "h264",
        duration: float = 120.0,
    ):
        return {
            "width": width,
            "height": height,
            "resolution": f"{width}x{height}",
            "fps": fps,
            "bitrate": bitrate,
            "codec": codec,
            "duration": duration,
        }

    return _create_metadata


@pytest.fixture(scope="function")
def mock_transcode_progress():
    def _create_progress_updates(total_steps: int = 10):
        updates = []
        for i in range(total_steps + 1):
            progress = (i / total_steps) * 100
            updates.append(
                {
                    "step": i,
                    "total_steps": total_steps,
                    "progress_percent": round(progress, 1),
                    "status": "processing" if i < total_steps else "completed",
                    "timestamp": datetime.now().isoformat(),
                }
            )
        return updates

    return _create_progress_updates
