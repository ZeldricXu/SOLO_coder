import pytest
import time
from streamsql.core.context import ProcessingContext, ContextManager


def test_processing_context_creation():
    ctx = ProcessingContext(trace_id="test-123")
    assert ctx.trace_id == "test-123"
    assert ctx.start_time > 0
    assert ctx.metadata == {}


def test_processing_context_add_metadata():
    ctx = ProcessingContext(trace_id="test-123")
    ctx.add_metadata("key1", "value1")
    ctx.add_metadata("key2", 123)
    assert ctx.metadata["key1"] == "value1"
    assert ctx.metadata["key2"] == 123


def test_processing_context_elapsed_time():
    ctx = ProcessingContext(trace_id="test-123")
    time.sleep(0.01)
    elapsed = ctx.elapsed()
    assert elapsed >= 0.01


def test_processing_context_cleanup():
    ctx = ProcessingContext(trace_id="test-123")
    ctx.add_metadata("key", "value")
    ctx.cleanup()
    assert ctx.resources == []


def test_processing_context_errors():
    ctx = ProcessingContext(trace_id="test-123")
    ctx.add_error("type1", "message1")
    assert len(ctx.errors) == 1
    assert ctx.errors[0] == ("type1", "message1")


def test_processing_context_metrics():
    ctx = ProcessingContext(trace_id="test-123")
    ctx.add_metric("throughput", 100.0)
    assert ctx.metrics["throughput"] == 100.0


def test_context_manager_create():
    ctx = ContextManager.create("test-456")
    assert ctx.trace_id == "test-456"
    assert ContextManager.get("test-456") is ctx


def test_context_manager_remove():
    ContextManager.create("test-789")
    assert ContextManager.get("test-789") is not None
    ContextManager.remove("test-789")
    assert ContextManager.get("test-789") is None


def test_context_manager_cleanup_all():
    ContextManager.create("test-cleanup1")
    ContextManager.create("test-cleanup2")
    ContextManager.cleanup_all()
    assert ContextManager.get("test-cleanup1") is None
    assert ContextManager.get("test-cleanup2") is None
