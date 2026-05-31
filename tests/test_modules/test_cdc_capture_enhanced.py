from __future__ import annotations

import asyncio
from datetime import datetime
import pytest
from uuid import uuid4

from streamsql.modules.cdc_capture import (
    CDCCapture,
    DeduplicationStrategy,
    FilterStrategy,
    MaskingStrategy,
    OperationType,
    StrategyRegistry,
    TransformStrategy,
)
from streamsql.modules.cdc_capture.strategies import (
    ProcessingContext,
    StrategyPipeline,
    ThrottlingStrategy,
    RouteStrategy,
)


def make_event(
    table: str,
    operation: str,
    after: dict | None = None,
    before: dict | None = None,
    database: str = "test_db",
) -> Any:
    from streamsql.modules.cdc_capture.binlog_parser import CDCEvent

    return CDCEvent(
        event_id=uuid4().hex,
        source="test_source",
        database=database,
        table=table,
        operation=OperationType(operation.lower()),
        timestamp=datetime.utcnow(),
        before=before,
        after=after,
    )


class TestProcessingStrategies:
    def test_strategy_registry_has_builtin_strategies(self):
        names = StrategyRegistry.list_names()
        assert "filter" in names
        assert "transform" in names
        assert "deduplicate" in names
        assert "throttling" in names
        assert "masking" in names
        assert "route" in names

    def test_create_strategy(self):
        strategy = StrategyRegistry.create("filter", include_tables=["users"])
        assert strategy is not None
        assert strategy.name == "filter"

    @pytest.mark.asyncio
    async def test_filter_strategy_include_tables(self):
        strategy = FilterStrategy(include_tables=["users"])
        ctx = ProcessingContext()

        user_event = make_event("users", "INSERT", after={"id": 1, "name": "test"})
        order_event = make_event("orders", "INSERT", after={"id": 1, "amount": 100})

        result1 = await strategy.process(user_event, ctx)
        result2 = await strategy.process(order_event, ctx)

        assert result1 is not None
        assert result2 is None

    @pytest.mark.asyncio
    async def test_filter_strategy_exclude_tables(self):
        strategy = FilterStrategy(exclude_tables=["audit_log"])
        ctx = ProcessingContext()

        user_event = make_event("users", "INSERT", after={})
        audit_event = make_event("audit_log", "INSERT", after={})

        assert await strategy.process(user_event, ctx) is not None
        assert await strategy.process(audit_event, ctx) is None

    @pytest.mark.asyncio
    async def test_filter_strategy_include_operations(self):
        strategy = FilterStrategy(include_operations=[OperationType.INSERT])
        ctx = ProcessingContext()

        insert_event = make_event("users", "INSERT", after={})
        update_event = make_event("users", "UPDATE", before={}, after={})

        assert await strategy.process(insert_event, ctx) is not None
        assert await strategy.process(update_event, ctx) is None

    @pytest.mark.asyncio
    async def test_transform_strategy_field_mapping(self):
        strategy = TransformStrategy(field_mapping={"user_id": "customer_id"})
        ctx = ProcessingContext()

        event = make_event("users", "INSERT", after={"user_id": 1, "name": "test"})

        result = await strategy.process(event, ctx)
        assert result is not None
        assert "customer_id" in result.after
        assert "user_id" not in result.after
        assert result.after["customer_id"] == 1

    @pytest.mark.asyncio
    async def test_transform_strategy_add_fields(self):
        strategy = TransformStrategy(add_fields={"source": "mysql", "processed": True})
        ctx = ProcessingContext()

        event = make_event("users", "INSERT", after={"id": 1})
        result = await strategy.process(event, ctx)

        assert result is not None
        assert result.after["source"] == "mysql"
        assert result.after["processed"] is True

    @pytest.mark.asyncio
    async def test_transform_strategy_remove_fields(self):
        strategy = TransformStrategy(remove_fields=["password", "secret"])
        ctx = ProcessingContext()

        event = make_event("users", "INSERT", after={"id": 1, "name": "test", "password": "secret123"})
        result = await strategy.process(event, ctx)

        assert result is not None
        assert "password" not in result.after
        assert "name" in result.after

    @pytest.mark.asyncio
    async def test_masking_strategy(self):
        strategy = MaskingStrategy(sensitive_fields=["email", "phone"])
        ctx = ProcessingContext()

        event = make_event("users", "INSERT", after={"id": 1, "email": "user@example.com", "phone": "1234567890"})
        result = await strategy.process(event, ctx)

        assert result is not None
        assert result.after["email"] != "user@example.com"
        assert "*" in result.after["email"]
        assert "*" in result.after["phone"]

    @pytest.mark.asyncio
    async def test_deduplication_strategy(self):
        strategy = DeduplicationStrategy(key_fields=["id"], window_seconds=60)
        ctx = ProcessingContext()

        event1 = make_event("users", "INSERT", after={"id": 1, "name": "test"})
        event2 = make_event("users", "INSERT", after={"id": 1, "name": "test"})

        result1 = await strategy.process(event1, ctx)
        result2 = await strategy.process(event2, ctx)

        assert result1 is not None
        assert result2 is None

    @pytest.mark.asyncio
    async def test_route_strategy(self):
        strategy = RouteStrategy(
            routing_rules={
                "kafka_users": lambda e: e.table == "users",
                "kafka_orders": lambda e: e.table == "orders",
            },
            default_output="default_topic",
        )
        ctx = ProcessingContext()

        user_event = make_event("users", "INSERT", after={})
        order_event = make_event("orders", "INSERT", after={})
        other_event = make_event("products", "INSERT", after={})

        result1 = await strategy.process(user_event, ctx)
        result2 = await strategy.process(order_event, ctx)
        result3 = await strategy.process(other_event, ctx)

        assert result1.metadata["route_target"] == "kafka_users"
        assert result2.metadata["route_target"] == "kafka_orders"
        assert result3.metadata["route_target"] == "default_topic"


class TestStrategyPipeline:
    @pytest.fixture
    def pipeline(self):
        return StrategyPipeline()

    def test_add_strategy(self, pipeline):
        filter_strategy = FilterStrategy(include_tables=["users"])
        pipeline.add_strategy(filter_strategy)
        assert len(pipeline.list_strategies()) == 1
        assert pipeline.list_strategies()[0][0] == "filter"

    def test_remove_strategy(self, pipeline):
        filter_strategy = FilterStrategy()
        pipeline.add_strategy(filter_strategy)
        result = pipeline.remove_strategy("filter")
        assert result is True
        assert len(pipeline.list_strategies()) == 0

    def test_remove_nonexistent_strategy(self, pipeline):
        result = pipeline.remove_strategy("nonexistent")
        assert result is False

    def test_get_strategy(self, pipeline):
        filter_strategy = FilterStrategy()
        pipeline.add_strategy(filter_strategy)
        retrieved = pipeline.get_strategy("filter")
        assert retrieved is filter_strategy

    def test_clear_strategies(self, pipeline):
        pipeline.add_strategy(FilterStrategy())
        pipeline.add_strategy(TransformStrategy())
        assert len(pipeline.list_strategies()) == 2
        pipeline.clear()
        assert len(pipeline.list_strategies()) == 0

    @pytest.mark.asyncio
    async def test_process_event_with_pipeline(self, pipeline):
        pipeline.add_strategy(FilterStrategy(include_tables=["users"]))
        pipeline.add_strategy(TransformStrategy(add_fields={"processed": True}))

        event = make_event("users", "INSERT", after={"id": 1, "name": "test"})

        result = await pipeline.process_event(event)
        assert result is not None
        assert result.after["processed"] is True

    @pytest.mark.asyncio
    async def test_process_event_filtered_out(self, pipeline):
        pipeline.add_strategy(FilterStrategy(include_tables=["users"]))

        event = make_event("orders", "INSERT", after={})
        result = await pipeline.process_event(event)
        assert result is None

    @pytest.mark.asyncio
    async def test_process_batch(self, pipeline):
        pipeline.add_strategy(TransformStrategy(add_fields={"tagged": True}))

        events = [
            make_event("users", "INSERT", after={"id": i})
            for i in range(5)
        ]

        results = await pipeline.process_batch(events)
        assert len(results) == 5
        for r in results:
            assert r.after["tagged"] is True


class TestCDCCaptureEnhanced:
    @pytest.fixture
    def cdc(self):
        return CDCCapture(
            datasource_config={"name": "test_db", "type": "mysql"},
        )

    def test_add_strategy(self, cdc):
        filter_strategy = FilterStrategy(include_tables=["users"])
        cdc.add_strategy(filter_strategy)
        assert ("filter", "Filter events by table, operation type, or custom predicate") in cdc.list_strategies()

    def test_remove_strategy(self, cdc):
        cdc.add_strategy(FilterStrategy())
        result = cdc.remove_strategy("filter")
        assert result is True
        assert len(cdc.list_strategies()) == 0

    def test_get_strategy(self, cdc):
        filter_strategy = FilterStrategy()
        cdc.add_strategy(filter_strategy)
        retrieved = cdc.get_strategy("filter")
        assert retrieved is filter_strategy

    def test_clear_strategies(self, cdc):
        cdc.add_strategy(FilterStrategy())
        cdc.add_strategy(TransformStrategy())
        assert len(cdc.list_strategies()) == 2
        cdc.clear_strategies()
        assert len(cdc.list_strategies()) == 0

    def test_create_strategy(self, cdc):
        strategy = cdc.create_strategy("filter", include_tables=["users"])
        assert strategy is not None
        assert strategy.include_tables == ["users"]

    def test_get_available_strategies(self, cdc):
        strategies = cdc.get_available_strategies()
        assert "filter" in strategies
        assert "transform" in strategies

    def test_get_pipeline_stats_empty(self, cdc):
        stats = cdc.get_pipeline_stats()
        assert stats["event_count"] == 0
        assert stats["batch_count"] == 0

    @pytest.mark.asyncio
    async def test_capture_with_pipeline(self, cdc):
        cdc.add_strategy(FilterStrategy(include_tables=["users"]))
        cdc.add_strategy(TransformStrategy(add_fields={"source": "test"}))

        events = []
        async for event in cdc.start_capture():
            events.append(event)
            if len(events) >= 5:
                cdc.stop()

        assert len(events) > 0
        for event in events:
            assert event.table == "users"
            assert event.after["source"] == "test"

    @pytest.mark.asyncio
    async def test_capture_once_with_pipeline(self, cdc):
        cdc.add_strategy(FilterStrategy(include_tables=["users"]))
        events = await cdc.capture_once()
        assert len(events) > 0
        for event in events:
            assert event.table == "users"
