import pytest
import asyncio
from datetime import datetime, timezone, timedelta
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import UUID, uuid4

from app.exceptions import (
    PlatformException,
    ValidationError,
    NotFoundError,
    ConflictError,
    TransactionFailedError,
)
from app.logging import get_logger, log_operation
from app.feature_store.service import FeatureStoreService
from app.monitoring.service import MonitoringService, QueryCache, MetricsAggregator
from app.schemas import (
    FeatureCreate,
    FeatureUpdate,
    FeatureVersionCreate,
    MetricsQuery,
    MetricSnapshotCreate,
)


pytestmark = pytest.mark.asyncio


class TestDataConsistencyFix:
    """测试 #001: 特征存储服务数据一致性修复"""

    async def test_create_feature_transaction_rollback(self):
        """测试创建特征异常时事务正确回滚"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()
        mock_db.add = MagicMock()
        mock_db.flush = AsyncMock()
        mock_db.commit = AsyncMock(side_effect=Exception("DB connection failed"))
        mock_db.rollback = AsyncMock()

        service = FeatureStoreService(mock_db)

        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db.execute.return_value = mock_result

        with pytest.raises(TransactionFailedError) as exc_info:
            await service.create_feature(
                FeatureCreate(
                    name="test_feature",
                    namespace="test",
                    entity_type="user",
                    value_type="float",
                    schema_definition={"type": "number"},
                )
            )

        mock_db.rollback.assert_called_once()
        assert exc_info.value.error_code == "ERR_TRANSACTION_FAILED"
        assert "operation_id" in exc_info.value.details
        assert exc_info.value.details["operation"] == "create_feature"

    async def test_update_feature_transaction_rollback(self):
        """测试更新特征异常时事务正确回滚"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()
        mock_db.commit = AsyncMock(side_effect=Exception("DB error"))
        mock_db.rollback = AsyncMock()

        service = FeatureStoreService(mock_db)

        mock_feature = MagicMock()
        mock_feature.id = uuid4()
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_feature)
        mock_db.execute.return_value = mock_result

        with pytest.raises(TransactionFailedError):
            await service.update_feature(
                mock_feature.id,
                FeatureUpdate(description="new description"),
            )

        mock_db.rollback.assert_called_once()

    async def test_delete_feature_transaction_rollback(self):
        """测试删除特征异常时事务正确回滚"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()
        mock_db.delete = MagicMock()
        mock_db.commit = AsyncMock(side_effect=Exception("DB error"))
        mock_db.rollback = AsyncMock()

        service = FeatureStoreService(mock_db)

        mock_feature = MagicMock()
        mock_feature.id = uuid4()
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_feature)
        mock_db.execute.return_value = mock_result

        with pytest.raises(TransactionFailedError):
            await service.delete_feature(mock_feature.id)

        mock_db.rollback.assert_called_once()

    async def test_create_version_transaction_rollback(self):
        """测试创建版本异常时事务正确回滚"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()
        mock_db.add = MagicMock()
        mock_db.commit = AsyncMock(side_effect=Exception("DB error"))
        mock_db.rollback = AsyncMock()

        service = FeatureStoreService(mock_db)

        mock_feature = MagicMock()
        mock_feature.id = uuid4()
        mock_feature.versions = []
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_feature)
        mock_result.scalars = MagicMock(return_value=MagicMock(all=lambda: [mock_feature]))
        mock_db.execute.return_value = mock_result

        with pytest.raises(TransactionFailedError):
            await service.create_version(
                FeatureVersionCreate(feature_id=mock_feature.id)
            )

        mock_db.rollback.assert_called_once()

    async def test_duplicate_feature_raises_conflict_not_transaction(self):
        """测试重复特征抛出ConflictError而非TransactionFailedError"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()

        service = FeatureStoreService(mock_db)

        existing_feature = MagicMock()
        existing_feature.id = uuid4()
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=existing_feature)
        mock_db.execute.return_value = mock_result

        with pytest.raises(ConflictError) as exc_info:
            await service.create_feature(
                FeatureCreate(
                    name="existing_feature",
                    namespace="test",
                    entity_type="user",
                    value_type="float",
                    schema_definition={"type": "number"},
                )
            )

        assert exc_info.value.error_code == "ERR_RESOURCE_CONFLICT"
        mock_db.rollback.assert_not_called()


class TestErrorLoggingFix:
    """测试 #002: 日志模块错误传递修复"""

    def test_platform_exception_has_structured_fields(self):
        """测试PlatformException包含结构化字段"""
        exc = ValidationError("Test error", details={"field": "value"})

        assert exc.error_id is not None
        assert isinstance(exc.error_id, str)
        assert len(exc.error_id) > 0

        assert exc.error_code == "ERR_VALIDATION_FAILED"
        assert exc.timestamp is not None
        assert exc.details["field"] == "value"
        assert exc.details["error_id"] == exc.error_id
        assert exc.details["error_code"] == exc.error_code

    def test_exception_str_contains_error_code(self):
        """测试异常字符串表示包含错误码"""
        exc = NotFoundError("Resource not found")
        exc_str = str(exc)

        assert "ERR_RESOURCE_NOT_FOUND" in exc_str
        assert exc.error_id in exc_str

    def test_exception_to_dict_contains_all_fields(self):
        """测试to_dict方法包含所有字段"""
        exc = ConflictError("Conflict detected", details={"resource_id": "123"})
        exc_dict = exc.to_dict()

        assert exc_dict["error"]["code"] == 409
        assert exc_dict["error"]["error_code"] == "ERR_RESOURCE_CONFLICT"
        assert exc_dict["error"]["error_id"] == exc.error_id
        assert exc_dict["error"]["message"] == "Conflict detected"
        assert exc_dict["error"]["details"]["resource_id"] == "123"

    def test_transaction_failed_error_from_exception(self):
        """测试TransactionFailedError.from_exception工厂方法"""
        original_exc = ValueError("Original error")
        context = {"feature_id": "test_id"}

        with patch("traceback.format_exc", return_value="traceback..."):
            exc = TransactionFailedError.from_exception(
                original_exc,
                operation="test_operation",
                context=context,
            )

        assert exc.error_code == "ERR_TRANSACTION_FAILED"
        assert exc.details["operation"] == "test_operation"
        assert exc.details["original_error"] == "Original error"
        assert exc.details["original_error_type"] == "ValueError"
        assert exc.details["traceback"] == "traceback..."
        assert exc.details["feature_id"] == "test_id"

    def test_log_operation_context_manager_success(self):
        """测试log_operation上下文管理器成功场景"""
        logger = get_logger("test_log_operation")

        with patch.object(logger, "debug") as mock_debug:
            with log_operation("test_log_operation", "my_operation", key="value"):
                pass

            assert mock_debug.call_count >= 2
            calls = mock_debug.call_args_list
            assert "Starting operation: my_operation" in str(calls[0])
            assert "Operation completed: my_operation" in str(calls[1])

    def test_log_operation_context_manager_failure(self):
        """测试log_operation上下文管理器失败场景"""
        logger_name = "test_log_operation_fail"
        logger = get_logger(logger_name)

        with patch.object(logger, "error") as mock_error:
            with pytest.raises(ValueError, match="Test error"):
                with log_operation(logger_name, "failing_operation"):
                    raise ValueError("Test error")

            mock_error.assert_called_once()
            call_args = mock_error.call_args
            assert "Operation failed: failing_operation" in str(call_args)
            assert "duration_ms" in str(call_args.kwargs)


class TestMonitoringPerformanceFix:
    """测试 #003: 监控统计模块性能修复"""

    async def test_query_cache_basic_operations(self):
        """测试QueryCache基本操作"""
        cache = QueryCache(ttl_seconds=60, max_entries=100)

        query = {"type": "test", "metric": "cpu"}
        value = {"data": [1, 2, 3]}

        result = await cache.get(query)
        assert result is None

        await cache.set(query, value)

        result = await cache.get(query)
        assert result == value

    async def test_query_cache_ttl_expiration(self):
        """测试QueryCache TTL过期"""
        cache = QueryCache(ttl_seconds=0.1, max_entries=100)

        query = {"type": "test"}
        value = {"data": [1, 2, 3]}

        await cache.set(query, value)

        result = await cache.get(query)
        assert result == value

        await asyncio.sleep(0.2)

        result = await cache.get(query)
        assert result is None

    async def test_query_cache_lru_eviction(self):
        """测试QueryCache LRU淘汰"""
        cache = QueryCache(ttl_seconds=60, max_entries=3)

        for i in range(5):
            await cache.set({"key": f"q{i}"}, {"value": i})

        assert len(cache._cache) == 3

        old_value = await cache.get({"key": "q0"})
        assert old_value is None

        new_value = await cache.get({"key": "q4"})
        assert new_value == {"value": 4}

    async def test_query_cache_invalidate(self):
        """测试QueryCache失效"""
        cache = QueryCache(ttl_seconds=60, max_entries=100)

        await cache.set({"key": "metrics_query_1"}, {"data": 1})
        await cache.set({"key": "other_query"}, {"data": 2})

        await cache.invalidate("metrics_query")

        assert len(cache._cache) == 1
        result = await cache.get({"key": "other_query"})
        assert result == {"data": 2}

    def test_metrics_aggregator_time_window(self):
        """测试MetricsAggregator时间窗口聚合"""
        now = datetime.now(timezone.utc)
        timestamps = [
            now + timedelta(seconds=i * 10)
            for i in range(6)
        ]
        metric_data = {
            "cpu_usage": [10, 20, 30, 40, 50, 60],
        }

        result = MetricsAggregator.aggregate_by_time_window(
            timestamps,
            metric_data,
            window_size_seconds=30,
        )

        assert len(result["timestamps"]) == 2
        assert len(result["metrics"]["cpu_usage"]) == 2
        assert result["aggregation"]["original_points"] == 6
        assert result["aggregation"]["aggregated_points"] == 2

    def test_metrics_aggregator_statistics(self):
        """测试MetricsAggregator统计计算"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        stats = MetricsAggregator.compute_statistics(values)

        assert stats["count"] == 10
        assert stats["sum"] == 55
        assert stats["mean"] == 5.5
        assert stats["min"] == 1
        assert stats["max"] == 10
        assert stats["p50"] == 5.5
        assert stats["p95"] is not None
        assert stats["p99"] is not None

    async def test_query_metrics_with_time_range_validation(self):
        """测试查询指标时时间范围验证"""
        mock_db = AsyncMock()
        service = MonitoringService(mock_db)

        start_time = datetime.now(timezone.utc)
        end_time = start_time - timedelta(hours=1)

        query = MetricsQuery(
            start_time=start_time,
            end_time=end_time,
            metric_names=["cpu_usage"],
        )

        with pytest.raises(ValidationError) as exc_info:
            await service.query_metrics(query)

        assert "start_time must be before end_time" in str(exc_info.value)

    async def test_query_metrics_max_time_range_limit(self):
        """测试查询指标最大时间范围限制"""
        mock_db = AsyncMock()
        service = MonitoringService(mock_db)

        start_time = datetime.now(timezone.utc) - timedelta(days=31)
        end_time = datetime.now(timezone.utc)

        query = MetricsQuery(
            start_time=start_time,
            end_time=end_time,
            metric_names=["cpu_usage"],
        )

        with pytest.raises(ValidationError) as exc_info:
            await service.query_metrics(query)

        assert "exceeds maximum allowed" in str(exc_info.value)

    async def test_batch_query_generator(self):
        """测试分批查询生成器"""
        mock_db = AsyncMock()
        service = MonitoringService(mock_db)

        mock_results = [
            MagicMock(scalars=lambda: MagicMock(all=lambda: [MagicMock()] * 1000))
            for _ in range(3)
        ]
        mock_results.append(MagicMock(scalars=lambda: MagicMock(all=lambda: [])))

        mock_db.execute = AsyncMock(side_effect=mock_results)

        from sqlalchemy import select
        from app.models import MetricSnapshot

        stmt = select(MetricSnapshot)
        batch_count = 0
        total_items = 0

        async for batch in service._batch_query_snapshots(stmt, batch_size=1000):
            batch_count += 1
            total_items += len(batch)

        assert batch_count == 3
        assert total_items == 3000

    async def test_record_snapshot_invalidates_cache(self):
        """测试记录快照时使缓存失效"""
        mock_db = AsyncMock()
        mock_db.add = MagicMock()
        mock_db.commit = AsyncMock()
        mock_db.refresh = AsyncMock()

        service = MonitoringService(mock_db)

        with patch.object(service._cache, "invalidate") as mock_invalidate:
            await service.record_metric_snapshot(
                MetricSnapshotCreate(metrics={"cpu": 50})
            )
            mock_invalidate.assert_called_once_with("metrics_query")


class TestIntegration:
    """集成测试"""

    async def test_full_transaction_flow_with_logging(self):
        """测试完整事务流和日志集成"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock()
        mock_db.add = MagicMock()
        mock_db.flush = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.refresh = AsyncMock()

        service = FeatureStoreService(mock_db)

        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db.execute.return_value = mock_result

        from app.feature_store import service as feature_store_service
        logger = feature_store_service.logger

        with patch.object(logger, "info") as mock_info:
            feature = await service.create_feature(
                FeatureCreate(
                    name="integration_test",
                    namespace="test",
                    entity_type="user",
                    value_type="float",
                    schema_definition={"type": "number"},
                )
            )

            mock_info.assert_called()
            call_args = mock_info.call_args
            assert "Feature created successfully" in str(call_args)
            assert "operation_id" in str(call_args.kwargs)

    async def test_exception_propagation_with_context(self):
        """测试异常传播携带完整上下文"""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(side_effect=Exception("DB connection failed"))

        service = FeatureStoreService(mock_db)

        try:
            await service.create_feature(
                FeatureCreate(
                    name="test_exception",
                    namespace="test",
                    entity_type="user",
                    value_type="float",
                    schema_definition={"type": "number"},
                )
            )
        except TransactionFailedError as e:
            assert "traceback" in e.details
            assert e.details["error_type"] == "Exception"
            assert "operation_id" in e.details
        else:
            pytest.fail("Expected TransactionFailedError")
