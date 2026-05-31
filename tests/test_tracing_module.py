from __future__ import annotations
import pytest
import time
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock
from typing import List, Dict, Any

from conftest import MockTracingService

from builders import (
    TraceSpanBuilder,
    SamplingStrategyBuilder,
    Priority,
    NotificationType,
    SamplingType,
    TestDataGenerator,
)


pytestmark = [pytest.mark.unit, pytest.mark.tracing]


class TestSpanCollection:
    def test_collect_single_span(self, tracing_service: MockTracingService):
        span = TraceSpanBuilder() \
            .with_name("test-span") \
            .with_service("api-gateway") \
            .with_duration(150) \
            .build()

        result = tracing_service.collect_span(span)

        assert result is True
        assert len(tracing_service.spans) == 1
        assert tracing_service.spans[0]["name"] == "test-span"
        assert tracing_service.spans[0]["serviceName"] == "api-gateway"

    def test_collect_multiple_spans_same_trace(self, tracing_service: MockTracingService):
        trace_id = "trace_test_001"
        spans = TestDataGenerator.create_trace_spans(5, "test-service")
        for span in spans:
            span["traceId"] = trace_id

        for span in spans:
            tracing_service.collect_span(span)

        assert len(tracing_service.spans) == 5
        assert trace_id in tracing_service.traces
        assert len(tracing_service.traces[trace_id]) == 5

    def test_span_parent_child_relationship(self, tracing_service: MockTracingService):
        spans = TestDataGenerator.create_trace_spans(3, "order-service")

        for span in spans:
            tracing_service.collect_span(span)

        trace = tracing_service.traces[spans[0]["traceId"]]
        assert trace[0].get("parentSpanId") is None
        assert trace[1]["parentSpanId"] == trace[0]["spanId"]
        assert trace[2]["parentSpanId"] == trace[1]["spanId"]

    def test_collect_span_with_error_status(self, tracing_service: MockTracingService):
        span = TraceSpanBuilder() \
            .with_name("failing-operation") \
            .with_service("payment-service") \
            .with_duration(200) \
            .with_error() \
            .with_attributes(error_code="500", error_message="Payment failed") \
            .build()

        tracing_service.collect_span(span)

        assert tracing_service.spans[0]["status"] == "ERROR"
        assert tracing_service.spans[0]["attributes"]["error"] is True
        assert tracing_service.spans[0]["attributes"]["error_code"] == "500"

    def test_collect_span_with_events(self, tracing_service: MockTracingService):
        span = TraceSpanBuilder() \
            .with_name("db-operation") \
            .with_service("user-service") \
            .with_duration(50) \
            .add_event("query_start", sql="SELECT * FROM users") \
            .add_event("query_end", rows_affected=42) \
            .build()

        tracing_service.collect_span(span)

        assert len(tracing_service.spans[0]["events"]) == 2
        assert tracing_service.spans[0]["events"][0]["name"] == "query_start"
        assert tracing_service.spans[0]["events"][1]["name"] == "query_end"

    def test_collect_high_volume_spans(self, tracing_service: MockTracingService):
        trace_count = 100
        spans_per_trace = 5

        for i in range(trace_count):
            spans = TestDataGenerator.create_trace_spans(spans_per_trace, f"service-{i % 5}")
            for span in spans:
                tracing_service.collect_span(span)

        assert len(tracing_service.spans) == trace_count * spans_per_trace
        assert len(tracing_service.traces) == trace_count


class TestTraceDataConsistency:
    def test_trace_retrieval_consistency(self, tracing_service: MockTracingService):
        spans = TestDataGenerator.create_trace_spans(5, "consistency-test")
        trace_id = spans[0]["traceId"]

        for span in spans:
            tracing_service.collect_span(span)

        retrieved = tracing_service.get_trace(trace_id)
        assert len(retrieved) == 5
        assert all(s["traceId"] == trace_id for s in retrieved)

    def test_trace_span_ids_uniqueness(self, tracing_service: MockTracingService):
        spans = TestDataGenerator.create_trace_spans(10, "unique-test")

        for span in spans:
            tracing_service.collect_span(span)

        span_ids = [s["spanId"] for s in tracing_service.spans]
        assert len(span_ids) == len(set(span_ids))

    def test_trace_duration_calculation(self, tracing_service: MockTracingService):
        spans = TestDataGenerator.create_trace_spans(5, "duration-test")
        trace_id = spans[0]["traceId"]

        expected_duration = sum(s.get("duration", 0) for s in spans)

        for span in spans:
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)
        assert result["totalDuration"] == expected_duration
        assert result["spanCount"] == 5

    def test_empty_trace_handling(self, tracing_service: MockTracingService):
        result = tracing_service.get_trace("non_existent_trace")
        assert result == []

    def test_concurrent_span_collection_consistency(self, tracing_service: MockTracingService):
        trace_id = "concurrent_trace_001"
        spans = []
        for i in range(20):
            span = TraceSpanBuilder() \
                .with_trace_id(trace_id) \
                .with_name(f"concurrent-span-{i}") \
                .with_service("concurrent-service") \
                .with_duration(10 + i) \
                .build()
            spans.append(span)

        for span in spans:
            tracing_service.collect_span(span)

        retrieved = tracing_service.get_trace(trace_id)
        assert len(retrieved) == 20

        span_ids = [s["spanId"] for s in retrieved]
        assert len(span_ids) == len(set(span_ids))

    def test_trace_with_partial_span_collection(self, tracing_service: MockTracingService):
        trace_id = "partial_trace_001"
        spans = TestDataGenerator.create_trace_spans(5, "partial-test")
        for span in spans:
            span["traceId"] = trace_id

        for span in spans[:3]:
            tracing_service.collect_span(span)

        partial = tracing_service.get_trace(trace_id)
        assert len(partial) == 3

        for span in spans[3:]:
            tracing_service.collect_span(span)

        complete = tracing_service.get_trace(trace_id)
        assert len(complete) == 5


class TestHeadSampling:
    def test_head_sampling_always_sample(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(1.0) \
            .for_service("test-service") \
            .build()
        tracing_service.add_strategy(strategy)

        span = TraceSpanBuilder() \
            .with_service("test-service") \
            .with_name("test-span") \
            .build()

        result = tracing_service.should_sample_head(span)
        assert result is True

    def test_head_sampling_never_sample(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(0.0) \
            .for_service("test-service") \
            .build()
        tracing_service.add_strategy(strategy)

        span = TraceSpanBuilder() \
            .with_service("test-service") \
            .with_name("test-span") \
            .build()

        result = tracing_service.should_sample_head(span)
        assert result is False

    def test_head_sampling_service_filter(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(1.0) \
            .for_service("specific-service") \
            .build()
        tracing_service.add_strategy(strategy)

        matching_span = TraceSpanBuilder() \
            .with_service("specific-service") \
            .build()
        non_matching_span = TraceSpanBuilder() \
            .with_service("other-service") \
            .build()

        assert tracing_service.should_sample_head(matching_span) is True
        assert tracing_service.should_sample_head(non_matching_span) is True

    def test_head_sampling_span_name_filter(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(0.0) \
            .for_span_name("health-check") \
            .build()
        tracing_service.add_strategy(strategy)

        health_span = TraceSpanBuilder() \
            .with_name("health-check") \
            .build()

        result = tracing_service.should_sample_head(health_span)
        assert result is False

    def test_head_sampling_disabled_strategy(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(0.0) \
            .for_service("test-service") \
            .disabled() \
            .build()
        tracing_service.add_strategy(strategy)

        span = TraceSpanBuilder() \
            .with_service("test-service") \
            .build()

        result = tracing_service.should_sample_head(span)
        assert result is True

    def test_head_sampling_probabilistic(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_head_sampling(0.5) \
            .build()
        tracing_service.add_strategy(strategy)

        sampled_count = 0
        total_count = 1000

        for i in range(total_count):
            span = TraceSpanBuilder() \
                .with_span_id(f"span_{i}") \
                .build()
            if tracing_service.should_sample_head(span):
                sampled_count += 1

        sample_rate = sampled_count / total_count
        assert 0.4 < sample_rate < 0.6


class TestTailSampling:
    def test_tail_sampling_error_only_with_error(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .build()
        tracing_service.add_strategy(strategy)

        error_spans = TestDataGenerator.create_error_trace("error-service")
        trace_id = error_spans[0]["traceId"]

        for span in error_spans:
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)
        assert result["sampled"] is True

    def test_tail_sampling_error_only_without_error(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .build()
        tracing_service.add_strategy(strategy)

        spans = TestDataGenerator.create_trace_spans(3, "normal-service")
        trace_id = spans[0]["traceId"]

        for span in spans:
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)
        assert result["sampled"] is False

    def test_tail_sampling_min_duration_threshold(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .with_min_duration(500) \
            .build()
        tracing_service.add_strategy(strategy)

        fast_spans = TestDataGenerator.create_trace_spans(3, "fast-service")
        trace_id_fast = fast_spans[0]["traceId"]
        for span in fast_spans:
            span["duration"] = 50
            tracing_service.collect_span(span)

        slow_spans = TestDataGenerator.create_trace_spans(5, "slow-service")
        trace_id_slow = slow_spans[0]["traceId"]
        for span in slow_spans:
            span["duration"] = 200
            tracing_service.collect_span(span)

        fast_result = tracing_service.finalize_trace(trace_id_fast)
        slow_result = tracing_service.finalize_trace(trace_id_slow)

        assert fast_result["sampled"] is False
        assert slow_result["sampled"] is True

    def test_tail_sampling_combined_conditions(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .with_min_duration(1000) \
            .build()
        tracing_service.add_strategy(strategy)

        error_spans = TestDataGenerator.create_error_trace("slow-error-service")
        trace_id = error_spans[0]["traceId"]
        for span in error_spans:
            span["duration"] = 300
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)
        assert result["sampled"] is False

    def test_tail_sampling_probabilistic(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(0.7) \
            .build()
        tracing_service.add_strategy(strategy)

        sampled_count = 0
        total_count = 1000

        for i in range(total_count):
            spans = TestDataGenerator.create_trace_spans(2, f"service-{i}")
            trace_id = spans[0]["traceId"]
            for span in spans:
                tracing_service.collect_span(span)
            result = tracing_service.finalize_trace(trace_id)
            if result["sampled"]:
                sampled_count += 1

        sample_rate = sampled_count / total_count
        assert 0.6 < sample_rate < 0.8

    def test_tail_sampling_multiple_strategies(self, tracing_service: MockTracingService):
        error_strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .with_priority(1) \
            .build()
        duration_strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .with_min_duration(500) \
            .with_priority(2) \
            .build()
        tracing_service.add_strategy(error_strategy)
        tracing_service.add_strategy(duration_strategy)

        error_spans = TestDataGenerator.create_error_trace("error-service")
        trace_id = error_spans[0]["traceId"]
        for span in error_spans:
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)
        assert result["sampled"] is True


class TestTracePipeline:
    def test_full_trace_lifecycle(self, tracing_service: MockTracingService):
        head_strategy = SamplingStrategyBuilder() \
            .with_head_sampling(1.0) \
            .build()
        tail_strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .build()
        tracing_service.add_strategy(head_strategy)
        tracing_service.add_strategy(tail_strategy)

        spans = TestDataGenerator.create_trace_spans(5, "lifecycle-test")
        trace_id = spans[0]["traceId"]

        for span in spans:
            head_decision = tracing_service.should_sample_head(span)
            assert head_decision is True
            tracing_service.collect_span(span)

        collected = tracing_service.get_trace(trace_id)
        assert len(collected) == 5

        final_result = tracing_service.finalize_trace(trace_id)
        assert final_result["sampled"] is False
        assert trace_id in tracing_service.sampling_decisions

    def test_trace_with_mixed_sampling_decisions(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .build()
        tracing_service.add_strategy(strategy)

        normal_spans = TestDataGenerator.create_trace_spans(3, "normal-service")
        error_spans = TestDataGenerator.create_error_trace("error-service")

        normal_trace_id = normal_spans[0]["traceId"]
        error_trace_id = error_spans[0]["traceId"]

        for span in normal_spans:
            tracing_service.collect_span(span)
        for span in error_spans:
            tracing_service.collect_span(span)

        normal_result = tracing_service.finalize_trace(normal_trace_id)
        error_result = tracing_service.finalize_trace(error_trace_id)

        assert normal_result["sampled"] is False
        assert error_result["sampled"] is True

    def test_trace_finalization_data_integrity(self, tracing_service: MockTracingService):
        spans = TestDataGenerator.create_trace_spans(10, "integrity-test")
        trace_id = spans[0]["traceId"]

        for span in spans:
            tracing_service.collect_span(span)

        result = tracing_service.finalize_trace(trace_id)

        assert result["traceId"] == trace_id
        assert result["spanCount"] == 10
        assert "sampled" in result
        assert "totalDuration" in result
        assert result["totalDuration"] > 0

    def test_multiple_trace_finalization(self, tracing_service: MockTracingService):
        strategy = SamplingStrategyBuilder() \
            .with_tail_sampling(1.0) \
            .error_only() \
            .build()
        tracing_service.add_strategy(strategy)

        for i in range(50):
            if i % 5 == 0:
                spans = TestDataGenerator.create_error_trace(f"service-{i}")
            else:
                spans = TestDataGenerator.create_trace_spans(3, f"service-{i}")
            trace_id = spans[0]["traceId"]
            for span in spans:
                tracing_service.collect_span(span)
            tracing_service.finalize_trace(trace_id)

        assert len(tracing_service.sampling_decisions) == 50
        error_sampled = sum(
            1 for sampled in tracing_service.sampling_decisions.values() if sampled
        )
        assert error_sampled == 10
