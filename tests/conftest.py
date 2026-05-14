import os
import sys
import tempfile
import shutil
from pathlib import Path
from datetime import datetime, timedelta

import pytest


PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))


class TestSettings:
    def __init__(self, tmp_path):
        self.tmp_path = tmp_path
        self.storage_dir = tmp_path / "test_storage"
        self.chunks_dir = tmp_path / "test_storage" / "chunks"
        self.upload_dir = tmp_path / "test_storage" / "uploads"
        self.result_dir = tmp_path / "test_storage" / "results"
        self.temp_dir = tmp_path / "test_storage" / "temp"
        self.logs_dir = tmp_path / "test_storage" / "logs"

    def ensure_dirs(self):
        for dir_path in [
            self.storage_dir,
            self.chunks_dir,
            self.upload_dir,
            self.result_dir,
            self.temp_dir,
            self.logs_dir,
        ]:
            dir_path.mkdir(parents=True, exist_ok=True)


@pytest.fixture(scope="function")
def test_settings(tmp_path):
    settings = TestSettings(tmp_path)
    settings.ensure_dirs()
    return settings


@pytest.fixture(scope="function")
def test_file_data():
    def _create_test_file(content, name="test.txt"):
        return {
            "content": content,
            "name": name,
            "size": len(content),
        }
    return _create_test_file


@pytest.fixture(scope="function")
def temp_test_file(tmp_path):
    def _create_file(file_name, content):
        file_path = tmp_path / file_name
        with open(file_path, "wb") as f:
            if isinstance(content, str):
                f.write(content.encode("utf-8"))
            else:
                f.write(content)
        return file_path
    return _create_file
