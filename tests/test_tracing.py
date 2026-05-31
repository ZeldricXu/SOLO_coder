import pytest

from app.monitoring.tracing import Tracer, get_tracer, Span, TraceContext


def test_tracer_singleton():
    t1 = get_tracer()
    t2 = get_tracer()
    assert t1 is t2


def test_span_creation():
    tracer = Tracer()

    with tracer.span("test_operation", trace_id="trace_123") as span:
        assert span.name == "test_operation"
        assert span.trace_id == "trace_123"
        assert span.span_id is not None
        assert span.start_time is not None
        assert span.end_time is None

    assert span.end_time is not None
    assert span.duration_ms >= 0


def test_nested_spans():
    tracer = Tracer()

    with tracer.span("parent", trace_id="nested_trace") as parent:
        with tracer.span("child") as child:
            assert child.trace_id == parent.trace_id
            assert child.parent_span_id == parent.span_id

    trace = tracer.get_trace("nested_trace")
    assert trace is not None
    assert len(trace.spans) == 2


def test_get_trace():
    tracer = Tracer()

    with tracer.span("test", trace_id="trace_get"):
        pass

    trace = tracer.get_trace("trace_get")
    assert trace is not None
    assert len(trace.spans) == 1

    assert tracer.get_trace("nonexistent") is None


def test_export_trace():
    tracer = Tracer()

    with tracer.span("export_test", trace_id="export_trace"):
        pass

    exported = tracer.export_trace("export_trace")
    assert exported["trace_id"] == "export_trace"
    assert "spans" in exported
    assert len(exported["spans"]) == 1


def test_cleanup_old_traces():
    tracer = Tracer()

    with tracer.span("old_trace", trace_id="trace_old"):
        pass

    removed = tracer.cleanup_old_traces(max_age_hours=0)
    assert removed >= 1


def test_span_attributes():
    tracer = Tracer()

    with tracer.span("attr_test", trace_id="attr_trace") as span:
        span.set_attribute("key1", "value1")
        span.set_attribute("key2", 123)

    assert span.attributes["key1"] == "value1"
    assert span.attributes["key2"] == 123


def test_current_trace_id():
    tracer = Tracer()

    assert tracer.get_current_trace_id() is None

    with tracer.span("current_test", trace_id="current_id"):
        assert tracer.get_current_trace_id() == "current_id"

    assert tracer.get_current_trace_id() is None


def test_span_error():
    tracer = Tracer()

    try:
        with tracer.span("error_test", trace_id="error_trace") as span:
            raise ValueError("test error")
    except ValueError:
        pass

    trace = tracer.get_trace("error_trace")
    assert trace is not None
    span = trace.spans[0]
    assert "error" in span.attributes
    assert span.attributes["error"] is True


def test_trace_context():
    ctx = TraceContext(trace_id="ctx_trace", span_id="span_1")

    assert ctx.trace_id == "ctx_trace"
    assert ctx.span_id == "span_1"
    assert ctx.sampled is True

    new_ctx = ctx.with_span_id("span_2")
    assert new_ctx.trace_id == "ctx_trace"
    assert new_ctx.span_id == "span_2"
    assert new_ctx.sampled is True


def test_generate_ids():
    import re

    trace_id = Tracer._generate_trace_id()
    span_id = Tracer._generate_span_id()

    assert re.match(r"^[0-9a-f]{16}$", span_id)
    assert re.match(r"^[0-9a-f]{32}$", trace_id)
