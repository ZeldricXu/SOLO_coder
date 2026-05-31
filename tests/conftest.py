from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).parent.parent
TESTS_ROOT = Path(__file__).parent

sys.path.insert(0, str(PROJECT_ROOT))
sys.path.insert(0, str(TESTS_ROOT))

os.environ.setdefault("ENV", "test")
os.environ.setdefault("LOG_LEVEL", "critical")


@pytest.fixture(scope="session")
def project_root():
    return PROJECT_ROOT


@pytest.fixture(scope="session")
def tests_root():
    return TESTS_ROOT


@pytest.fixture
def temp_test_dir(tmp_path):
    test_dir = tmp_path / "test_data"
    test_dir.mkdir()
    return test_dir


@pytest.fixture
def mock_logger():
    from unittest.mock import MagicMock

    logger = MagicMock()
    logger.info = MagicMock()
    logger.warn = MagicMock()
    logger.error = MagicMock()
    logger.debug = MagicMock()
    return logger


@pytest.fixture
def sample_service_data():
    from tests.builders import ServiceBuilder

    return ServiceBuilder.create_default()


@pytest.fixture
def sample_scaffold_request():
    from tests.builders import ScaffoldBuilder

    return ScaffoldBuilder.create_default_request()


@pytest.fixture
def sample_vulnerability_sbom():
    from tests.builders import VulnerabilityBuilder

    return VulnerabilityBuilder.create_sbom_with_vulnerabilities()


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "unit: Unit tests that don't require external services",
    )
    config.addinivalue_line(
        "markers",
        "integration: Integration tests that require external services",
    )
    config.addinivalue_line(
        "markers",
        "concurrency: Tests related to concurrent operations",
    )
    config.addinivalue_line(
        "markers",
        "timeout: Tests related to timeout and degradation behavior",
    )
    config.addinivalue_line(
        "markers",
        "consistency: Tests related to data consistency",
    )


def pytest_collection_modifyitems(config, items):
    for item in items:
        if "concurrency" in item.keywords:
            item.add_marker(pytest.mark.concurrency)
        if "timeout" in item.keywords:
            item.add_marker(pytest.mark.timeout)
        if "consistency" in item.keywords:
            item.add_marker(pytest.mark.consistency)
