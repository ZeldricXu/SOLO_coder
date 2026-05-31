import pytest
import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.core_processor import get_core_processor, ValidationError


@pytest.mark.asyncio
async def test_execute_handler_success():
    processor = get_core_processor()
    result = await processor.execute_handler({
        "traceId": "test_trace",
        "requestId": "test_req",
        "namespace": "test",
        "params": {"payload": {"test": "data"}},
    })
    assert result.success is True
    assert result.data is not None


@pytest.mark.asyncio
async def test_execute_handler_validation_error():
    processor = get_core_processor()
    result = await processor.execute_handler({
        "traceId": "test_trace",
        "requestId": "test_req",
        "namespace": "test",
        "params": {},
    })
    assert result.success is False
    assert result.error_code == 422


def test_config_manager():
    processor = get_core_processor()
    config_mgr = processor.get_config_manager()
    config = config_mgr.load_config("test_ns")
    assert config.namespace == "test_ns"
    assert config.version >= 1


def test_run_manager():
    processor = get_core_processor()
    run_mgr = processor.get_run_manager()
    run = run_mgr.create_run("test_entity")
    assert run.run_id is not None
    assert run.entity_id == "test_entity"
