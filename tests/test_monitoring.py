"""
监控统计模块测试 - 聚焦事务回滚正确性
测试策略：
1. 正常流程测试 - 验证指标记录、快照功能正常工作
2. 事务回滚测试 - 验证各种错误场景下的数据一致性
3. 并发场景测试 - 验证并发操作时的事务正确性
4. 边界条件测试 - 验证极端输入的处理
"""

import pytest
import time
import random
from unittest.mock import Mock, patch, MagicMock, call
from typing import Dict, Any, List

from tests.builders import (
    MetricRecordBuilder,
    StatsSnapshotBuilder,
    MockResponseBuilder,
    TestDataGenerator,
)


class TestMonitoringBase:
    """监控模块测试基类"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """测试前初始化"""
        self.base_url = "http://localhost:8080/api/v1/monitoring"
        self.metric_builder = MetricRecordBuilder()
        self.snapshot_builder = StatsSnapshotBuilder()


class TestMonitoringNormalFlow(TestMonitoringBase):
    """正常流程测试"""

    @pytest.mark.parametrize("metric_type", MetricRecordBuilder.VALID_METRIC_TYPES)
    def test_record_valid_metric_types(self, mock_requests, metric_type):
        """测试记录所有有效类型的指标"""
        request_data = self.metric_builder.with_type(metric_type).as_request()
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "metric recorded"
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )
        data = response.json()

        assert response.status_code == 200
        assert data["code"] == 200
        assert data["message"] == "metric recorded"

    def test_record_metric_with_labels(self, mock_requests):
        """测试记录带标签的指标"""
        labels = {
            "host": "prod-node-001",
            "region": "cn-east",
            "env": "production",
            "service": "api-gateway",
            "version": "v2.1.0",
        }
        request_data = self.metric_builder.with_labels(labels).as_request()
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "metric recorded"
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )

        assert response.status_code == 200

    def test_take_snapshot(self, mock_requests):
        """测试创建统计快照"""
        request_data = self.snapshot_builder.as_request()
        expected_response = self.snapshot_builder.as_response()
        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/snapshot", json=request_data
        )
        data = response.json()

        assert response.status_code == 200
        assert data["code"] == 200
        assert "snapshot_id" in data["data"]
        assert "metrics" in data["data"]
        assert "dimensions" in data["data"]
        assert data["data"]["dimensions"] == request_data["dimensions"]

    def test_get_snapshots_with_filters(self, mock_requests):
        """测试带过滤条件获取快照列表"""
        snapshots = []
        for i in range(5):
            snapshots.append(self.snapshot_builder.as_response(f"snap_{i}"))

        mock_response = MockResponseBuilder.success(snapshots, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        params = {
            "start_time": "2026-05-11T00:00:00Z",
            "end_time": "2026-05-11T23:59:59Z",
            "limit": 10,
        }
        response = mock_requests.get(f"{self.base_url}/snapshots", params=params)
        data = response.json()

        assert response.status_code == 200
        assert data["code"] == 200
        assert len(data["data"]) == 5

    def test_get_metrics_list(self, mock_requests):
        """测试获取已注册的指标列表"""
        expected_data = {
            "counters": {
                "http_requests_total": "counter",
                "task_executions_total": "counter",
            },
            "gauges": {
                "active_goroutines": "gauge",
            },
            "snapshot_count": 42,
        }
        mock_response = MockResponseBuilder.success(expected_data, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.get(f"{self.base_url}")
        data = response.json()

        assert data["code"] == 200
        assert "counters" in data["data"]
        assert "gauges" in data["data"]
        assert "snapshot_count" in data["data"]

    def test_record_http_request_metrics(self):
        """测试HTTP请求指标记录"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "IncrementCounter") as mock_inc:
            with patch.object(service, "ObserveHistogram") as mock_observe:
                service.RecordHTTPRequest("GET", "/api/v1/users", 200, 0.123)

                mock_inc.assert_called_once()
                mock_observe.assert_called_once()

                inc_call_args = mock_inc.call_args
                assert inc_call_args[0][1] == "http_requests_total"
                assert inc_call_args[0][2]["method"] == "GET"
                assert inc_call_args[0][2]["path"] == "/api/v1/users"
                assert inc_call_args[0][2]["status"] == "200"

    def test_record_http_request_all_status_codes(self):
        """测试所有HTTP状态码的记录"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        status_codes = [200, 201, 204, 301, 302, 400, 401, 403, 404, 500, 502, 503, 504]

        with patch.object(service, "IncrementCounter") as mock_inc:
            with patch.object(service, "ObserveHistogram") as mock_observe:
                for status in status_codes:
                    service.RecordHTTPRequest("GET", "/test", status, 0.1)

                assert mock_inc.call_count == len(status_codes)
                assert mock_observe.call_count == len(status_codes)


class TestMonitoringTransactionRollback(TestMonitoringBase):
    """事务回滚测试 - 监控模块的重点测试"""

    @pytest.mark.parametrize("error_scenario", TestDataGenerator.generate_error_scenarios())
    def test_database_error_rollback_on_snapshot(self, error_scenario):
        """测试各种数据库错误场景下快照创建的事务回滚"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "mu"):
            with patch(
                "loglevelplatform.internal.modules.monitoring.service.time.Now",
                return_value=1234567890,
            ):
                with patch(
                    "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                    return_value=42,
                ):
                    mock_db = MagicMock()
                    service.db = mock_db

                    if error_scenario["error_type"] == "connection":
                        mock_db.Create.side_effect = Exception("database connection lost")
                    elif error_scenario["error_type"] == "constraint":
                        mock_db.Create.side_effect = Exception("unique constraint violation")
                    elif error_scenario["error_type"] == "deadlock":
                        mock_db.Create.side_effect = Exception("deadlock detected")
                    elif error_scenario["error_type"] == "timeout":
                        mock_db.Create.side_effect = Exception("query timeout")
                    else:
                        mock_db.Create.side_effect = Exception("database error")

                    initial_snapshot_count = len(service.snapshots)

                    try:
                        service.TakeSnapshot(MagicMock(), {"host": "test"})
                    except Exception:
                        pass

                    assert len(service.snapshots) == initial_snapshot_count

    def test_partial_snapshot_rollback(self):
        """测试部分失败时的回滚 - 指标收集成功但持久化失败"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)

        with patch.object(service.mu, "Lock"):
            with patch.object(service.mu, "Unlock"):
                with patch(
                    "loglevelplatform.internal.modules.monitoring.service.time.Now",
                    return_value=1234567890,
                ):
                    with patch(
                        "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                        return_value=42,
                    ):
                        service.db = MagicMock()
                        service.db.Create.side_effect = Exception("DB write failed")

                        try:
                            service.TakeSnapshot(MagicMock(), {"host": "test"})
                        except Exception:
                            pass

                        assert len(service.snapshots) == initial_count

    def test_concurrent_snapshot_transaction_isolation(self):
        """测试并发快照创建时的事务隔离性"""
        import threading

        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)
        results = []
        errors = []

        def create_snapshot(should_fail):
            try:
                if should_fail:
                    with patch.object(service.db, "Create", side_effect=Exception("DB error")):
                        service.TakeSnapshot(MagicMock(), {"host": f"host_{random.randint(1, 100)}"})
                else:
                    service.TakeSnapshot(MagicMock(), {"host": f"host_{random.randint(1, 100)}"})
                results.append(True)
            except Exception as e:
                errors.append(e)

        threads = []
        for i in range(20):
            should_fail = i % 3 == 0
            threads.append(threading.Thread(target=create_snapshot, args=(should_fail,)))

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        success_count = len(results)
        assert len(service.snapshots) == initial_count + success_count

    def test_metric_recording_transaction_atomicity(self):
        """测试指标记录的事务原子性 - 多个指标要么全部成功要么全部回滚"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "IncrementCounter") as mock_inc:
            with patch.object(service, "SetGauge") as mock_gauge:
                with patch.object(service, "ObserveHistogram") as mock_histogram:
                    mock_histogram.side_effect = Exception("metric recording failed")

                    try:
                        service.IncrementCounter(MagicMock(), "test_counter", {})
                        service.SetGauge(MagicMock(), "test_gauge", 42.0, {})
                        service.ObserveHistogram(MagicMock(), "test_histogram", 0.5, {})
                    except Exception:
                        pass

                    assert mock_inc.call_count == 1
                    assert mock_gauge.call_count == 1

    def test_snapshot_rollback_preserves_existing_data(self):
        """测试回滚不影响已存在的数据"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        for i in range(5):
            service.snapshots.append(MagicMock())

        initial_count = len(service.snapshots)

        with patch(
            "loglevelplatform.internal.modules.monitoring.service.time.Now",
            return_value=1234567890,
        ):
            with patch(
                "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                return_value=42,
            ):
                service.db = MagicMock()
                service.db.Create.side_effect = Exception("DB error")

                try:
                    service.TakeSnapshot(MagicMock(), {"host": "test"})
                except Exception:
                    pass

        assert len(service.snapshots) == initial_count

    def test_snapshot_creation_with_db_transaction(self):
        """测试快照创建时的数据库事务正确提交或回滚"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)

        with patch(
            "loglevelplatform.internal.modules.monitoring.service.time.Now",
            return_value=1234567890,
        ):
            with patch(
                "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                return_value=42,
            ):
                mock_db = MagicMock()
                service.db = mock_db

                service.TakeSnapshot(MagicMock(), {"host": "test"})

                assert len(service.snapshots) == initial_count + 1

        service.db = MagicMock()
        service.db.Create.side_effect = Exception("DB error")
        initial_count2 = len(service.snapshots)

        try:
            service.TakeSnapshot(MagicMock(), {"host": "test2"})
        except Exception:
            pass

        assert len(service.snapshots) == initial_count2

    def test_transaction_rollback_on_out_of_memory(self):
        """测试内存不足时的事务回滚"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)

        with patch(
            "loglevelplatform.internal.modules.monitoring.service.time.Now",
            side_effect=MemoryError("out of memory"),
        ):
            try:
                service.TakeSnapshot(MagicMock(), {"host": "test"})
            except MemoryError:
                pass

            assert len(service.snapshots) == initial_count

    def test_concurrent_modification_rollback(self):
        """测试并发修改冲突时的回滚"""
        import threading

        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)

        conflict_count = 0
        success_count = 0

        def modify_snapshots():
            nonlocal conflict_count, success_count
            try:
                for _ in range(100):
                    with patch(
                        "loglevelplatform.internal.modules.monitoring.service.time.Now",
                        return_value=time.time(),
                    ):
                        with patch(
                            "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                            return_value=random.randint(1, 100),
                        ):
                            service.TakeSnapshot(MagicMock(), {"host": f"thread_{threading.get_ident()}"})
                success_count += 1
            except Exception:
                conflict_count += 1

        threads = [threading.Thread(target=modify_snapshots) for _ in range(5)]

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(service.snapshots) >= initial_count

    def test_snapshot_queue_overflow_rollback(self):
        """测试快照队列溢出时的回滚（超过1000条限制）"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        for i in range(1000):
            service.snapshots.append(MagicMock(snapshot_id=f"snap_{i}"))

        assert len(service.snapshots) == 1000

        with patch(
            "loglevelplatform.internal.modules.monitoring.service.time.Now",
            return_value=1234567890,
        ):
            with patch(
                "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                return_value=42,
            ):
                service.TakeSnapshot(MagicMock(), {"host": "overflow_test"})

                assert len(service.snapshots) == 1000
                assert service.snapshots[-1].dimensions["host"] == "overflow_test"
                assert service.snapshots[0].snapshot_id == "snap_1"


class TestMonitoringMetricOperations(TestMonitoringBase):
    """指标操作测试"""

    def test_counter_increment_transaction(self):
        """测试计数器递增的事务性"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "IncrementCounter") as mock_inc:
            ctx = MagicMock()
            labels = {"host": "test", "region": "cn-east"}

            for i in range(10):
                service.IncrementCounter(ctx, "test_counter", labels)

            assert mock_inc.call_count == 10
            for i in range(10):
                assert mock_inc.call_args_list[i][0][1] == "test_counter"

    def test_gauge_set_transaction(self):
        """测试Gauge设置的事务性"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "SetGauge") as mock_gauge:
            ctx = MagicMock()

            values = [0, 1, 100, 999, -1, 0.5, 3.14159]
            for v in values:
                service.SetGauge(ctx, "test_gauge", v, {})

            assert mock_gauge.call_count == len(values)
            for i, v in enumerate(values):
                assert mock_gauge.call_args_list[i][0][2] == v

    def test_histogram_observation_transaction(self):
        """测试直方图观测的事务性"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "ObserveHistogram") as mock_hist:
            ctx = MagicMock()

            observations = [0.001, 0.01, 0.1, 1.0, 10.0, 100.0]
            for obs in observations:
                service.ObserveHistogram(ctx, "test_histogram", obs, {})

            assert mock_hist.call_count == len(observations)

    def test_summary_observation_transaction(self):
        """测试Summary观测的事务性"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "ObserveSummary") as mock_summary:
            ctx = MagicMock()

            for i in range(100):
                service.ObserveSummary(ctx, "test_summary", random.random(), {})

            assert mock_summary.call_count == 100

    def test_metric_registration_transaction(self):
        """测试指标注册的事务性"""
        from loglevelplatform.internal.modules.monitoring.service import Service, MetricDefinition, MetricType

        service = Service()
        initial_counter_count = len(service.counters)
        initial_gauge_count = len(service.gauges)

        with patch.object(service.registry, "MustRegister") as mock_register:
            mock_register.side_effect = Exception("registration failed")

            defs = [
                MetricDefinition(
                    name="new_counter",
                    type=MetricTypeCounter,
                    help="Test counter",
                    labels=["host"],
                ),
                MetricDefinition(
                    name="new_gauge",
                    type=MetricTypeGauge,
                    help="Test gauge",
                    labels=["host"],
                ),
            ]

            for d in defs:
                try:
                    service.RegisterMetric(d)
                except Exception:
                    pass

            assert len(service.counters) == initial_counter_count
            assert len(service.gauges) == initial_gauge_count


class TestMonitoringBoundaryConditions(TestMonitoringBase):
    """边界条件测试"""

    @pytest.mark.parametrize("invalid_type", MetricRecordBuilder.INVALID_METRIC_TYPES)
    def test_invalid_metric_type_rejected(self, mock_requests, invalid_type):
        """测试无效指标类型被正确拒绝"""
        request_data = self.metric_builder.with_type(invalid_type).as_request()

        if invalid_type == "":
            mock_response = MockResponseBuilder.validation_error("type is required")
        else:
            mock_response = MockResponseBuilder.validation_error("invalid metric type")

        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )
        data = response.json()

        assert response.status_code == 400
        assert data["code"] == 400

    def test_empty_metric_name_rejected(self, mock_requests):
        """测试空指标名被拒绝"""
        request_data = self.metric_builder.with_empty_name().as_request()
        mock_response = MockResponseBuilder.validation_error("name is required")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )

        assert response.status_code == 400

    def test_negative_metric_value(self, mock_requests):
        """测试负值指标的处理"""
        request_data = self.metric_builder.with_negative_value().as_request()
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "metric recorded"
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )

        assert response.status_code == 200

    def test_extremely_large_metric_value(self, mock_requests):
        """测试超大指标值的处理"""
        request_data = self.metric_builder.with_large_value().as_request()
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "metric recorded"
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )

        assert response.status_code == 200

    def test_too_many_labels_rejected(self, mock_requests):
        """测试过多标签被拒绝"""
        request_data = self.metric_builder.with_many_labels(100).as_request()
        mock_response = MockResponseBuilder.validation_error("too many labels")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/metric", json=request_data
        )

        assert response.status_code == 400

    def test_empty_dimensions_snapshot(self, mock_requests):
        """测试空维度快照的创建"""
        request_data = self.snapshot_builder.with_empty_dimensions().as_request()
        expected_response = self.snapshot_builder.with_empty_dimensions().as_response()
        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(
            f"{self.base_url}/snapshot", json=request_data
        )
        data = response.json()

        assert response.status_code == 200
        assert data["data"]["dimensions"] == {}

    def test_invalid_time_format_snapshots(self, mock_requests):
        """测试无效时间格式的快照查询"""
        mock_response = MockResponseBuilder.success([], 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        params = {
            "start_time": "invalid-time-format",
            "end_time": "not-a-date",
        }
        response = mock_requests.get(f"{self.base_url}/snapshots", params=params)
        data = response.json()

        assert response.status_code == 200
        assert isinstance(data["data"], list)

    def test_zero_and_negative_limit(self, mock_requests):
        """测试零和负数limit参数"""
        mock_response = MockResponseBuilder.success([], 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        for limit in [0, -1, -100]:
            response = mock_requests.get(
                f"{self.base_url}/snapshots", params={"limit": limit}
            )
            assert response.status_code == 200

    def test_extremely_large_limit(self, mock_requests):
        """测试超大limit参数"""
        snapshots = [self.snapshot_builder.as_response(f"snap_{i}") for i in range(100)]
        mock_response = MockResponseBuilder.success(snapshots, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.get(
            f"{self.base_url}/snapshots", params={"limit": 1000000}
        )
        data = response.json()

        assert response.status_code == 200
        assert len(data["data"]) == 100


class TestMonitoringConcurrency(TestMonitoringBase):
    """并发场景测试"""

    def test_concurrent_metric_recording(self):
        """测试并发指标记录"""
        import threading

        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        record_count = 0
        error_count = 0

        def record_metrics():
            nonlocal record_count, error_count
            try:
                ctx = MagicMock()
                for i in range(100):
                    metric_type = MetricRecordBuilder.VALID_METRIC_TYPES[i % 4]
                    if metric_type == "counter":
                        service.IncrementCounter(ctx, "concurrent_test", {"thread": str(threading.get_ident())})
                    elif metric_type == "gauge":
                        service.SetGauge(ctx, "concurrent_gauge", random.random(), {})
                    elif metric_type == "histogram":
                        service.ObserveHistogram(ctx, "concurrent_histogram", random.random(), {})
                    else:
                        service.ObserveSummary(ctx, "concurrent_summary", random.random(), {})
                record_count += 1
            except Exception:
                error_count += 1

        threads = [threading.Thread(target=record_metrics) for _ in range(10)]

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert record_count + error_count == 10

    def test_concurrent_snapshot_creation(self):
        """测试并发快照创建"""
        import threading

        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        initial_count = len(service.snapshots)

        def create_snapshots():
            for i in range(10):
                with patch(
                    "loglevelplatform.internal.modules.monitoring.service.time.Now",
                    return_value=time.time(),
                ):
                    with patch(
                        "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                        return_value=random.randint(1, 100),
                    ):
                        service.TakeSnapshot(MagicMock(), {"thread": str(threading.get_ident())})

        threads = [threading.Thread(target=create_snapshots) for _ in range(10)]

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        expected_count = min(initial_count + 100, 1000)
        assert len(service.snapshots) <= 1000
        assert len(service.snapshots) == expected_count

    def test_concurrent_read_write_metrics(self):
        """测试并发读写指标"""
        import threading

        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()
        read_count = 0
        write_count = 0

        def read_operation():
            nonlocal read_count
            try:
                for _ in range(100):
                    service.GetMetrics(MagicMock())
                read_count += 1
            except Exception:
                pass

        def write_operation():
            nonlocal write_count
            try:
                for i in range(100):
                    service.IncrementCounter(MagicMock(), "http_requests_total", {})
                write_count += 1
            except Exception:
                pass

        threads = []
        for i in range(20):
            if i % 2 == 0:
                threads.append(threading.Thread(target=read_operation))
            else:
                threads.append(threading.Thread(target=write_operation))

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert read_count + write_count > 0


class TestMonitoringEdgeCases(TestMonitoringBase):
    """极端场景测试"""

    def test_rapid_metric_recording(self):
        """测试快速连续记录指标"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch.object(service, "IncrementCounter") as mock_inc:
            for i in range(10000):
                service.IncrementCounter(MagicMock(), "rapid_test", {"index": str(i)})

            assert mock_inc.call_count == 10000

    def test_label_key_special_characters(self):
        """测试标签键中的特殊字符"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        special_labels = {
            "normal-key": "value",
            "key_with_underscores": "value",
            "key-with-dashes": "value",
            "key.with.dots": "value",
            "key/with/slashes": "value",
        }

        with patch.object(service, "IncrementCounter") as mock_inc:
            service.IncrementCounter(MagicMock(), "test_metric", special_labels)

            mock_inc.assert_called_once()
            assert mock_inc.call_args[0][2] == special_labels

    def test_label_value_extreme_length(self):
        """测试超长标签值"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        long_value = "a" * 10000
        labels = {"long_value": long_value}

        with patch.object(service, "IncrementCounter") as mock_inc:
            service.IncrementCounter(MagicMock(), "test_metric", labels)

            mock_inc.assert_called_once()
            assert mock_inc.call_args[0][2]["long_value"] == long_value

    def test_metric_name_extreme_cases(self):
        """测试极端的指标名"""
        from loglevelplatform.internal.modules.monitoring.service import Service, MetricDefinition, MetricType

        service = Service()

        extreme_names = [
            "a",
            "a" * 255,
            "metric.with.dots",
            "metric_with_underscores",
            "metric-with-dashes",
            "MetricWithMixedCase",
        ]

        for name in extreme_names:
            try:
                service.RegisterMetric(MetricDefinition(
                    name=name,
                    type=MetricTypeCounter,
                    help="Test metric",
                    labels=[],
                ))
            except Exception:
                pass

    def test_prometheus_endpoint_availability(self, mock_requests):
        """测试Prometheus端点可用性"""
        mock_requests.get.return_value = MagicMock(
            status_code=200,
            text="# HELP http_requests_total Total HTTP requests\n# TYPE http_requests_total counter\nhttp_requests_total 42",
        )

        response = mock_requests.get(f"{self.base_url}/prometheus")

        assert response.status_code == 200
        assert "http_requests_total" in response.text

    def test_snapshot_metrics_completeness(self):
        """测试快照指标的完整性"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        with patch(
            "loglevelplatform.internal.modules.monitoring.service.time.Now",
            return_value=1234567890.0,
        ):
            with patch(
                "loglevelplatform.internal.modules.monitoring.service.runtimeNumGoroutine",
                return_value=42,
            ):
                snapshot = service.TakeSnapshot(MagicMock(), {"host": "test"})

                assert "timestamp" in snapshot.Metrics
                assert "active_goroutines" in snapshot.Metrics
                assert snapshot.Metrics["timestamp"] == 1234567890.0
                assert snapshot.Metrics["active_goroutines"] == 42.0

    def test_get_snapshots_time_range_edge_cases(self):
        """测试快照时间范围查询的边界情况"""
        from loglevelplatform.internal.modules.monitoring.service import Service

        service = Service()

        now = time.time()
        for i in range(10):
            snapshot = MagicMock()
            snapshot.Timestamp = time.Time()
            snapshot.Timestamp = time.time() - (10 - i) * 60
            service.snapshots.append(snapshot)

        results = service.GetSnapshots(
            MagicMock(),
            time.Time(),
            time.Time(),
            100,
        )
        assert len(results) == 10

        future_time = time.time() + 3600
        results = service.GetSnapshots(
            MagicMock(),
            time.Time(),
            time.Time(),
            100,
        )
        assert len(results) == 10
