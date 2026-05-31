import pytest

from app.core.processor import (
    ProcessingContext,
    EventEmitter,
    EventType,
    BusinessRuleEngine,
    RequestProcessor,
    ValidationError,
    get_request_processor
)


def test_processing_context():
    ctx = ProcessingContext(trace_id="test_trace")

    assert ctx.trace_id == "test_trace"
    assert ctx.phase == "initializing"
    assert ctx.progress == 0.0
    assert ctx.start_time is not None

    ctx.set_progress(0.5)
    assert ctx.progress == 0.5

    ctx.set_attribute("key", "value")
    assert ctx.get_attribute("key") == "value"
    assert ctx.get_attribute("nonexistent") is None


def test_processing_context_cleanup():
    ctx = ProcessingContext(trace_id="cleanup_test")
    ctx.set_attribute("test", "data")

    ctx.cleanup()

    assert ctx.get_attribute("test") is None


def test_event_emitter():
    emitter = EventEmitter()

    received = []

    def handler(event, **kwargs):
        received.append((event, kwargs))

    emitter.on("test.event", handler)
    emitter.emit("test.event", data="test_data")

    assert len(received) == 1
    assert received[0][0] == "test.event"
    assert received[0][1] == {"data": "test_data"}


def test_event_emitter_emit_without_handler():
    emitter = EventEmitter()
    emitter.emit("unhandled.event")


def test_event_emitter_off():
    emitter = EventEmitter()

    count = 0

    def handler(*args):
        nonlocal count
        count += 1

    emitter.on("count.event", handler)
    emitter.emit("count.event")
    emitter.off("count.event", handler)
    emitter.emit("count.event")

    assert count == 1


def test_event_type_enum():
    assert EventType.TASK_CREATED.value == "task.created"
    assert EventType.TASK_COMPLETED.value == "task.completed"
    assert EventType.TASK_FAILED.value == "task.failed"
    assert EventType.CONFIG_UPDATED.value == "config.updated"


def test_business_rule_engine():
    engine = BusinessRuleEngine()

    def rule1(data, config):
        data["rule1_applied"] = True
        return True

    def rule2(data, config):
        data["rule2_applied"] = True
        if "fail" in data:
            raise ValueError("Rule failed")
        return True

    engine.add_rule("rule1", rule1, priority=1)
    engine.add_rule("rule2", rule2, priority=2)

    data = {}
    result = engine.apply_rules(data, {})

    assert result["success"] is True
    assert data["rule1_applied"] is True
    assert data["rule2_applied"] is True

    data2 = {"fail": True}
    result2 = engine.apply_rules(data2, {})

    assert result2["success"] is False
    assert "Rule failed" in result2["error"]


def test_request_processor_singleton():
    p1 = get_request_processor()
    p2 = get_request_processor()
    assert p1 is p2


@pytest.mark.asyncio
async def test_request_processor_execute_handler():
    processor = get_request_processor()

    result = await processor.execute_handler({
        "traceId": "test_handler",
        "namespace": "default",
        "payload": {"data": "test"}
    })

    assert "code" in result
    assert "data" in result or "error" in result


@pytest.mark.asyncio
async def test_request_processor_validation_error():
    processor = get_request_processor()

    result = await processor.execute_handler({
        "traceId": "validation_test",
        "namespace": "default",
        "payload": {"__force_validation_error": True}
    })

    assert result["code"] == 422


@pytest.mark.asyncio
async def test_request_processor_timeout():
    processor = get_request_processor()

    result = await processor.execute_handler({
        "traceId": "timeout_test",
        "namespace": "default",
        "payload": {"__force_timeout": True}
    })

    assert result["code"] == 504


def test_validation_error():
    error = ValidationError(message="Test error", details={"field": "invalid"})
    assert str(error) == "Test error"
    assert error.details == {"field": "invalid"}
