import pytest


class TestSpan:
    def test_span_creation(self, trace_collector):
        span = trace_collector.start_span("test-span", service_name="test-service")
        assert span.trace_id is not None
        assert span.span_id is not None
        assert span.name == "test-span"
        assert span.service_name == "test-service"

    def test_span_end(self, trace_collector):
        span = trace_collector.start_span("test-span")
        assert span.end_time is None
        trace_collector.end_span(span)
        assert span.end_time is not None

    def test_span_duration(self, trace_collector):
        span = trace_collector.start_span("test-span")
        import time

        time.sleep(0.01)
        trace_collector.end_span(span)
        assert span.duration_ms > 0

    def test_span_attributes(self, trace_collector):
        span = trace_collector.start_span("test-span", custom_attr="value")
        assert span.attributes["custom_attr"] == "value"
        span.set_attribute("another", "attr")
        assert span.attributes["another"] == "attr"

    def test_span_events(self, trace_collector):
        span = trace_collector.start_span("test-span")
        span.add_event("event1", key="value")
        assert len(span.events) == 1
        assert span.events[0]["name"] == "event1"


class TestTrace:
    def test_trace_add_spans(self, trace_collector):
        trace_id = "trace-123"
        span1 = trace_collector.start_span("span1", trace_id=trace_id)
        span2 = trace_collector.start_span("span2", trace_id=trace_id, parent_span_id=span1.span_id)

        trace = trace_collector.get_trace(trace_id)
        assert trace is not None
        assert len(trace.spans) == 2

    def test_trace_root_span(self, trace_collector):
        trace_id = "trace-456"
        parent = trace_collector.start_span("parent", trace_id=trace_id)
        trace_collector.start_span("child", trace_id=trace_id, parent_span_id=parent.span_id)

        trace = trace_collector.get_trace(trace_id)
        root = trace.get_root_span()
        assert root is not None
        assert root.name == "parent"
        assert root.parent_span_id is None


class TestTraceCollector:
    def test_collector_metrics(self, trace_collector):
        initial = trace_collector.get_metrics()
        span = trace_collector.start_span("test")
        trace_collector.end_span(span)

        metrics = trace_collector.get_metrics()
        assert metrics["spans_received"] > initial["spans_received"]

    def test_list_traces(self, trace_collector):
        span1 = trace_collector.start_span("span1")
        trace_collector.end_span(span1)

        span2 = trace_collector.start_span("span2")
        trace_collector.end_span(span2)

        traces = trace_collector.list_traces(limit=10)
        assert len(traces) >= 2
