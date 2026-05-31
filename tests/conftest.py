import os
import sys
import tempfile
from datetime import datetime, timedelta

import pytest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from tests.app.scheduler import Scheduler
from tests.app.slomonitor import SLOMonitor, MetricEvent
from tests.app.storage import StorageManager
from tests.factories.data_factory import TaskFactory, SLOFactory, StorageFactory, DatabaseFactory

@pytest.fixture(scope="function")
def mock_db_session(mocker):
    return DatabaseFactory.create_mock_db_session(mocker)

@pytest.fixture(scope="function")
def failing_db_session(mocker):
    return DatabaseFactory.create_failing_db_session(mocker, fail_on="commit")

@pytest.fixture(scope="function")
def scheduler():
    return Scheduler()

@pytest.fixture(scope="function")
def scheduler_with_db(mock_db_session):
    return Scheduler(db_session=mock_db_session)

@pytest.fixture(scope="function")
def slomonitor():
    return SLOMonitor()

@pytest.fixture(scope="function")
def slomonitor_with_db(mock_db_session):
    return SLOMonitor(db_session=mock_db_session)

@pytest.fixture(scope="function")
def mock_alerter(mocker):
    alerter = mocker.MagicMock()
    alerter.fire_alert = mocker.MagicMock()
    return alerter

@pytest.fixture(scope="function")
def slomonitor_with_alerter(mock_alerter):
    return SLOMonitor(alerter=mock_alerter)

@pytest.fixture(scope="function")
def storage_manager():
    with tempfile.TemporaryDirectory() as tmpdir:
        manager = StorageManager(base_path=tmpdir)
        yield manager

@pytest.fixture(scope="function")
def storage_manager_with_db(mock_db_session):
    with tempfile.TemporaryDirectory() as tmpdir:
        manager = StorageManager(base_path=tmpdir, db_session=mock_db_session)
        yield manager

@pytest.fixture(scope="function")
def storage_manager_small_limit():
    with tempfile.TemporaryDirectory() as tmpdir:
        manager = StorageManager(base_path=tmpdir, max_storage_bytes=10 * 1024)
        yield manager

@pytest.fixture(scope="function")
def sample_task(scheduler):
    task_data = TaskFactory.create_task_data()
    return scheduler.create_task(task_data)

@pytest.fixture(scope="function")
def sample_slo(slomonitor):
    slo_data = SLOFactory.create_slo_data()
    return slomonitor.create_slo(slo_data)

@pytest.fixture(scope="function")
def sample_file(storage_manager):
    file_data = StorageFactory.create_file_data()
    return storage_manager.store_file(**file_data)

@pytest.fixture
def freezer():
    with mock.patch("datetime.datetime") as mock_dt:
        mock_dt.utcnow = mock.Mock(return_value=datetime(2026, 5, 17, 10, 0, 0))
        yield mock_dt
