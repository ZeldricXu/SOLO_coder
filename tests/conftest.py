import asyncio
import os
import shutil
import tempfile
from datetime import datetime
from typing import Any, Dict, Generator

import pytest
from fastapi.testclient import TestClient

from src.main import app
from src.models import (
    EntityStatus,
    EntityType,
    ServiceMetadata,
    Task,
    TaskGraph,
)
from src.scheduler.scheduler import TaskScheduler


@pytest.fixture(scope="session")
def event_loop() -> Generator[asyncio.AbstractEventLoop, None, None]:
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def client() -> Generator[TestClient, None, None]:
    with TestClient(app) as c:
        yield c


@pytest.fixture
def temp_dir() -> Generator[str, None, None]:
    tmpdir = tempfile.mkdtemp()
    yield tmpdir
    shutil.rmtree(tmpdir, ignore_errors=True)


@pytest.fixture
def sample_task() -> Task:
    return Task(
        task_id="task_test_001",
        name="test_task",
        description="A test task",
        dependencies=[],
        parameters={"param1": "value1"},
        timeout=60,
        retries=2,
    )


@pytest.fixture
def sample_task_graph() -> TaskGraph:
    task_a = Task(
        task_id="task_a",
        name="task_a",
        dependencies=[],
        parameters={"key": "a"},
    )
    task_b = Task(
        task_id="task_b",
        name="task_b",
        dependencies=["task_a"],
        parameters={"key": "b"},
    )
    task_c = Task(
        task_id="task_c",
        name="task_c",
        dependencies=["task_a"],
        parameters={"key": "c"},
    )
    return TaskGraph(
        graph_id="graph_test_001",
        name="test_graph",
        tasks=[task_a, task_b, task_c],
    )


@pytest.fixture
def sample_service_metadata() -> ServiceMetadata:
    return ServiceMetadata(
        service_id="svc_test_001",
        name="test-service",
        version="1.0.0",
        description="A test service",
        type="service",
        language="python",
        dependencies=[],
        tags=["test", "api"],
        endpoints=[{"path": "/api/v1", "method": "GET"}],
    )


@pytest.fixture
def sample_entity_data() -> Dict[str, Any]:
    return {
        "type": "job",
        "config": {"timeout": 30, "retries": 3},
        "labels": {"env": "test", "team": "dev"},
    }


@pytest.fixture
def task_scheduler() -> TaskScheduler:
    return TaskScheduler(max_workers=4)
