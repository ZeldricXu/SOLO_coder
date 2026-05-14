import os
import pytest
import tempfile
import shutil
from typing import Generator
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from reporthub.models.base import Base
from reporthub.modules import (
    StorageModule, StatisticsModule, TemplateModule,
    DataModule, AsyncDataModule, ExportModule, RetryExportModule,
    AsyncExportModule, QueryModule, ScheduleModule
)
from tests.data import TestDataBuilder


@pytest.fixture
def test_builder() -> Generator[TestDataBuilder, None, None]:
    builder = TestDataBuilder()
    yield builder
    builder.cleanup()


@pytest.fixture
def temp_storage_path() -> Generator[str, None, None]:
    temp_dir = tempfile.mkdtemp(prefix="reporthub_test_storage_")
    os.makedirs(os.path.join(temp_dir, "reports"), exist_ok=True)
    os.makedirs(os.path.join(temp_dir, "exports"), exist_ok=True)
    yield temp_dir
    shutil.rmtree(temp_dir, ignore_errors=True)


@pytest.fixture
def in_memory_db():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
    Base.metadata.create_all(bind=engine)
    TestingSession = sessionmaker(bind=engine)
    session = TestingSession()
    yield session
    session.close()
    engine.dispose()


@pytest.fixture
def storage_module(temp_storage_path):
    original_settings = {}
    from reporthub.config import settings
    original_settings["STORAGE_PATH"] = settings.STORAGE_PATH
    settings.STORAGE_PATH = temp_storage_path
    storage = StorageModule()
    yield storage
    settings.STORAGE_PATH = original_settings["STORAGE_PATH"]


@pytest.fixture
def statistics_module(in_memory_db):
    return StatisticsModule(in_memory_db)


@pytest.fixture
def template_module(in_memory_db):
    return TemplateModule(in_memory_db)


@pytest.fixture
def query_module(in_memory_db):
    return QueryModule(in_memory_db)


@pytest.fixture
def schedule_module(in_memory_db, template_module):
    return ScheduleModule(in_memory_db, template_module)


@pytest.fixture
def data_module(in_memory_db, storage_module, statistics_module):
    from reporthub.modules.version_module import VersionModule
    version_module = VersionModule(in_memory_db, storage_module)
    return DataModule(in_memory_db, storage_module, version_module, statistics_module)


@pytest.fixture
def async_data_module(in_memory_db, storage_module, statistics_module):
    from reporthub.modules.version_module import VersionModule
    version_module = VersionModule(in_memory_db, storage_module)
    return AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)


@pytest.fixture
def export_module(in_memory_db, storage_module, statistics_module):
    return ExportModule(in_memory_db, storage_module, statistics_module)


@pytest.fixture
def retry_export_module(in_memory_db, storage_module, statistics_module):
    return RetryExportModule(
        in_memory_db,
        storage_module,
        statistics_module
    )


@pytest.fixture
def async_export_module(in_memory_db, storage_module, statistics_module):
    return AsyncExportModule(
        in_memory_db,
        storage_module,
        statistics_module,
        use_redis=False
    )
