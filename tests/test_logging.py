import pytest
import tempfile
import os
from pathlib import Path
from src.modules import LogManager, get_logger


def test_log_manager_singleton():
    with tempfile.TemporaryDirectory() as tmpdir:
        lm1 = LogManager(log_dir=tmpdir)
        lm2 = LogManager(log_dir=tmpdir)
        assert lm1 is lm2


def test_get_logger():
    with tempfile.TemporaryDirectory() as tmpdir:
        LogManager(log_dir=tmpdir)
        logger = get_logger("test")
        assert logger is not None


def test_log_manager_cleanup():
    with tempfile.TemporaryDirectory() as tmpdir:
        lm = LogManager(log_dir=tmpdir)
        removed = lm.cleanup_old_logs(retention_days=0)
        assert isinstance(removed, int)


def test_log_manager_archive():
    with tempfile.TemporaryDirectory() as tmpdir:
        log_dir = Path(tmpdir)
        test_log = log_dir / "test.log"
        test_log.write_text("test log content")

        lm = LogManager(log_dir=tmpdir)
        archive_path = lm.archive_logs()
        assert archive_path.exists()
