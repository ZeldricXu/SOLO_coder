from __future__ import annotations

import asyncio
import pytest

from streamsql.modules.streaming_query import (
    AsyncQueryResult,
    ParsePipelineOptions,
    QueryStatus,
    StreamingQueryParser,
)


class TestAsyncQueryResult:
    def test_default_status(self):
        result = AsyncQueryResult(query_id="test_123", raw_sql="SELECT * FROM users")
        assert result.status == QueryStatus.PENDING
        assert result.query_id == "test_123"
        assert result.raw_sql == "SELECT * FROM users"

    def test_to_dict(self):
        result = AsyncQueryResult(
            query_id="test_123",
            raw_sql="SELECT * FROM users",
            status=QueryStatus.COMPLETED,
        )
        result_dict = result.to_dict()
        assert result_dict["query_id"] == "test_123"
        assert result_dict["status"] == "completed"
        assert result_dict["raw_sql"] == "SELECT * FROM users"

    @pytest.mark.asyncio
    async def test_notify_callbacks(self):
        result = AsyncQueryResult(query_id="test_123", raw_sql="SELECT * FROM users")
        callback_called = []

        def callback(r):
            callback_called.append(r)

        result.add_callback(callback)
        await result.notify_callbacks()
        assert len(callback_called) == 1
        assert callback_called[0] is result


class TestParsePipelineOptions:
    def test_default_options(self):
        options = ParsePipelineOptions()
        assert options.generate_logical_plan is True
        assert options.optimize_plan is True
        assert options.generate_physical_plan is False
        assert options.timeout == 30


class TestStreamingQueryParserAsync:
    @pytest.fixture
    def parser(self):
        return StreamingQueryParser()

    @pytest.mark.asyncio
    async def test_parse_now_async(self, parser):
        result = await parser.parse_now_async("SELECT * FROM users WHERE id > 100")
        assert result.status == QueryStatus.COMPLETED
        assert result.parsed_query is not None
        assert result.parsed_query.query_type.value == "SELECT"
        assert "users" in result.parsed_query.tables
        assert result.duration_ms is not None

    @pytest.mark.asyncio
    async def test_parse_now_async_with_options(self, parser):
        options = ParsePipelineOptions(
            generate_logical_plan=True,
            optimize_plan=True,
            generate_physical_plan=True,
        )
        result = await parser.parse_now_async(
            "SELECT id, name FROM users WHERE active = true ORDER BY created_at DESC",
            options=options,
        )
        assert result.status == QueryStatus.COMPLETED
        assert result.logical_plan is not None
        assert result.optimized_plan is not None
        assert result.physical_plan is not None

    @pytest.mark.asyncio
    async def test_parse_now_async_with_callback(self, parser):
        callback_called = []

        async def callback(result):
            callback_called.append(result.query_id)

        result = await parser.parse_now_async(
            "SELECT * FROM users",
            callback=callback,
        )
        assert len(callback_called) == 1
        assert callback_called[0] == result.query_id

    @pytest.mark.asyncio
    async def test_parse_async_queued(self, parser):
        await parser.start_async()
        try:
            result = await parser.parse_async("SELECT * FROM orders")
            assert result.status in (QueryStatus.PENDING, QueryStatus.COMPLETED)

            await asyncio.sleep(0.1)
            updated = parser.get_async_result(result.query_id)
            assert updated is not None
        finally:
            await parser.stop_async()

    @pytest.mark.asyncio
    async def test_parse_many_concurrent(self, parser):
        sqls = [
            "SELECT * FROM users",
            "SELECT * FROM orders",
            "SELECT * FROM products",
            "SELECT id, name FROM users WHERE active = true",
            "SELECT COUNT(*) FROM orders",
        ]
        results = await parser.parse_many_concurrent(sqls, max_concurrent=2)
        assert len(results) == 5
        for result in results:
            assert result.status == QueryStatus.COMPLETED
            assert result.parsed_query is not None

    @pytest.mark.asyncio
    async def test_parse_async_invalid_sql(self, parser):
        result = await parser.parse_now_async("RANDOM TEXT THAT IS NOT SQL AT ALL")
        assert result.status == QueryStatus.FAILED
        assert result.error is not None

    def test_get_async_stats_not_started(self, parser):
        stats = parser.get_async_stats()
        assert stats["status"] == "not_started"

    @pytest.mark.asyncio
    async def test_get_async_stats_after_parsing(self, parser):
        await parser.start_async()
        try:
            await parser.parse_now_async("SELECT * FROM users")
            stats = parser.get_async_stats()
            assert stats["completed_queries"] >= 1
        finally:
            await parser.stop_async()

    @pytest.mark.asyncio
    async def test_async_pipeline_lifecycle(self, parser):
        assert parser._async_pipeline is None

        await parser.start_async()
        assert parser._async_pipeline is not None
        assert parser._async_pipeline._running is True

        await parser.stop_async()
        assert parser._async_pipeline is None

    @pytest.mark.asyncio
    async def test_parse_async_callback_sync(self, parser):
        callback_called = []

        def sync_callback(result):
            callback_called.append(result.query_id)

        result = await parser.parse_now_async(
            "SELECT * FROM users",
            callback=sync_callback,
        )
        assert len(callback_called) == 1

    def test_backward_compatibility_parse_sync(self, parser):
        parsed = parser.parse("SELECT id, name FROM users WHERE age > 18")
        assert parsed.query_type.value == "SELECT"
        assert "users" in parsed.tables
        assert len(parsed.columns) == 2

    def test_backward_compatibility_parse_many_sync(self, parser):
        sqls = [
            "SELECT * FROM users",
            "SELECT * FROM orders WHERE status = 'completed'",
        ]
        results = parser.parse_many(sqls)
        assert len(results) == 2
        assert all(r.query_type.value == "SELECT" for r in results)

    def test_backward_compatibility_validate(self, parser):
        is_valid, errors = parser.validate("SELECT * FROM users")
        assert is_valid is True
        assert len(errors) == 0

        is_valid, errors = parser.validate("RANDOM TEXT THAT IS NOT SQL")
        assert is_valid is False
        assert len(errors) >= 1

    def test_backward_compatibility_extract_tables(self, parser):
        tables = parser.extract_tables("SELECT * FROM users JOIN orders ON users.id = orders.user_id")
        assert "users" in tables
        assert "orders" in tables

    def test_backward_compatibility_is_streaming_query(self, parser):
        is_streaming = parser.is_streaming_query("SELECT * FROM TABLE(TUMBLE(TABLE orders, 1 minute))")
        assert is_streaming is True

        is_streaming = parser.is_streaming_query("SELECT * FROM orders")
        assert is_streaming is False
