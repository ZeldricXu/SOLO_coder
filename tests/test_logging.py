import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.logging_module import get_logger, set_log_level, get_current_log_level


def test_get_logger():
    logger = get_logger("test")
    assert logger is not None
    assert logger.name == "test"


def test_set_log_level():
    original = get_current_log_level()
    set_log_level("DEBUG")
    assert get_current_log_level() == "DEBUG"
    set_log_level(original)


def test_specific_logger_level():
    logger = get_logger("test_specific")
    set_log_level("WARNING", "test_specific")
    assert logger.level == 30
