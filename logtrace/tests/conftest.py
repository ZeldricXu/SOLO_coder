import os
import tempfile
import shutil
from datetime import datetime
from unittest.mock import MagicMock, patch
import pytest

from logtrace.core.config import ConfigManager
from logtrace.core.models import NodeConfig, LogRecord, ExceptionRule, LogStats
from logtrace.core.log_parser import LogParser
from logtrace.tests.data_generator import builder as data_builder


@pytest.fixture
def temp_dir():
    tmpdir = tempfile.mkdtemp()
    yield tmpdir
    shutil.rmtree(tmpdir, ignore_errors=True)


@pytest.fixture
def test_log_file(temp_dir):
    log_lines = data_builder.build_sample_log_lines(count=3)
    log_path = data_builder.build_log_file(temp_dir, log_lines)
    yield log_path


@pytest.fixture
def test_node_config(test_log_file):
    return data_builder.build_node_config(
        log_path=test_log_file
    )


@pytest.fixture
def mock_config():
    config = MagicMock(spec=ConfigManager)
    config.get_nodes.return_value = []
    config.get_exception_rules.return_value = []
    config.get_alert_config.return_value = {'channels': []}
    config.get_elasticsearch_config.return_value = {
        'host': 'localhost',
        'port': 9200,
        'index_prefix': 'logtrace'
    }
    return config


@pytest.fixture
def mock_config_with_rules(mock_config):
    rules = data_builder.build_default_rules()
    mock_config.get_exception_rules.return_value = data_builder.build_config_dict_for_rules(rules)
    return mock_config


@pytest.fixture
def log_parser():
    return LogParser()


@pytest.fixture
def sample_log_records():
    return data_builder.build_mixed_logs(
        info_count=2,
        warning_count=1,
        error_count=2
    )


@pytest.fixture
def sample_exception_logs():
    return data_builder.build_error_logs(count=3)


@pytest.fixture
def sample_exception_rule():
    return data_builder.build_exception_rule()


@pytest.fixture
def data_builder_fixture():
    return data_builder
