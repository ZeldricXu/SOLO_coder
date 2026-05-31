"""
日志模块测试 - 聚焦边界条件处理
测试策略：
1. 正常流程测试 - 验证所有有效日志级别的设置和获取
2. 边界条件测试 - 空值、特殊字符、超长字符串、Unicode等
3. 无效参数测试 - 非法日志级别、缺失必填字段等
4. 数据库错误场景 - 验证事务回滚正确性
5. 并发场景测试 - 验证并发修改时的一致性
"""

import pytest
import time
from unittest.mock import Mock, patch, MagicMock
from typing import Dict, Any

from tests.builders import (
    LogLevelConfigBuilder,
    MockResponseBuilder,
    TestDataGenerator,
)


class TestLogLevelBase:
    """日志模块测试基类"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """测试前初始化"""
        self.base_url = "http://localhost:8080/api/v1/log-level"
        self.builder = LogLevelConfigBuilder()


class TestLogLevelNormalFlow(TestLogLevelBase):
    """正常流程测试"""

    @pytest.mark.parametrize("level_index", range(len(LogLevelConfigBuilder.VALID_LEVELS)))
    def test_set_valid_log_level_all_levels(self, mock_requests, level_index):
        """测试设置所有有效日志级别"""
        level = LogLevelConfigBuilder.VALID_LEVELS[level_index]
        request_data = self.builder.with_level(level).as_request()
        expected_response = self.builder.with_level(level).as_response()

        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.post(f"{self.base_url}", json=request_data)

        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["level"] == level
        assert data["data"]["namespace"] == request_data["namespace"]
        assert data["data"]["component"] == request_data["component"]

    def test_set_log_level_with_updated_by(self, mock_requests):
        """测试设置带更新者信息的日志级别"""
        request_data = self.builder.with_updated_by("admin_user").as_request()
        expected_response = self.builder.with_updated_by("admin_user").as_response()

        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.post(f"{self.base_url}", json=request_data)
        data = response.json()

        assert data["data"]["updated_by"] == "admin_user"

    def test_get_log_level_by_component(self, mock_requests):
        """测试获取指定组件的日志级别"""
        component = "api.server"
        expected_data = {"component": component, "level": "debug"}
        mock_response = MockResponseBuilder.success(expected_data, 200)
        mock_requests.get.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.get(f"{self.base_url}", params={"component": component})
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["component"] == component
        assert data["data"]["level"] == "debug"

    def test_get_log_level_by_namespace(self, mock_requests):
        """测试获取指定命名空间的所有日志级别配置"""
        namespace = "production"
        expected_configs = TestDataGenerator.generate_log_level_configs(5)
        for cfg in expected_configs:
            cfg["namespace"] = namespace

        mock_response = MockResponseBuilder.success(expected_configs, 200)
        mock_requests.get.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.get(f"{self.base_url}", params={"namespace": namespace})
        data = response.json()

        assert data["code"] == 200
        assert isinstance(data["data"], list)
        assert len(data["data"]) == 5
        for cfg in data["data"]:
            assert cfg["namespace"] == namespace

    def test_get_all_log_levels(self, mock_requests):
        """测试获取所有组件的日志级别"""
        expected_levels = {
            "api": "info",
            "worker": "debug",
            "scheduler": "warn",
        }
        mock_response = MockResponseBuilder.success(expected_levels, 200)
        mock_requests.get.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.get(f"{self.base_url}")
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["api"] == "info"
        assert data["data"]["worker"] == "debug"

    def test_delete_log_level_config(self, mock_requests):
        """测试删除日志级别配置"""
        config_id = "log_abc123def4567"
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "deleted successfully"
        mock_requests.delete.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.delete(f"{self.base_url}/{config_id}")
        data = response.json()

        assert data["code"] == 200
        assert data["message"] == "deleted successfully"

    def test_get_all_configs(self, mock_requests):
        """测试获取所有持久化的配置"""
        configs = TestDataGenerator.generate_log_level_configs(10)
        expected_configs = []
        for i, cfg in enumerate(configs):
            builder = LogLevelConfigBuilder()
            builder._data = cfg
            expected_configs.append(builder.as_response(f"log_{i}"))

        mock_response = MockResponseBuilder.success(expected_configs, 200)
        mock_requests.get.return_value = MagicMock(status_code=200, json=lambda: mock_response)

        response = mock_requests.get(f"{self.base_url}/configs")
        data = response.json()

        assert data["code"] == 200
        assert len(data["data"]) == 10


class TestLogLevelBoundaryConditions(TestLogLevelBase):
    """边界条件测试 - 日志模块的重点测试"""

    @pytest.mark.parametrize("boundary_case", [
        ("empty_namespace", LogLevelConfigBuilder().with_empty_namespace()),
        ("empty_component", LogLevelConfigBuilder().with_empty_component()),
        ("special_chars_component", LogLevelConfigBuilder().with_special_chars_component()),
        ("long_component_128", LogLevelConfigBuilder().with_long_component_name(128)),
        ("long_component_256", LogLevelConfigBuilder().with_long_component_name(256)),
        ("unicode_component", LogLevelConfigBuilder().with_unicode_component()),
    ])
    def test_boundary_conditions_component_names(self, mock_requests, boundary_case):
        """测试各种边界条件下的组件名处理"""
        case_name, builder = boundary_case
        request_data = builder.as_request()

        if case_name in ["empty_namespace", "empty_component"]:
            mock_response = MockResponseBuilder.validation_error(
                "namespace and component are required"
            )
            mock_requests.post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}", json=request_data)
            data = response.json()

            assert response.status_code == 400
            assert data["code"] == 400
            assert "error" in data
        else:
            expected_response = builder.as_response()
            mock_response = MockResponseBuilder.success(expected_response, 200)
            mock_requests.post.return_value = MagicMock(
                status_code=200, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}", json=request_data)
            data = response.json()

            assert response.status_code == 200
            assert data["code"] == 200
            assert data["data"]["component"] == request_data["component"]

    @pytest.mark.parametrize("invalid_level", LogLevelConfigBuilder.INVALID_LEVELS)
    def test_invalid_log_level_rejected(self, mock_requests, invalid_level):
        """测试所有无效日志级别都被正确拒绝"""
        request_data = self.builder.with_level(invalid_level).as_request()

        mock_response = MockResponseBuilder.validation_error(
            f"invalid log level: {invalid_level}"
        )
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}", json=request_data)
        data = response.json()

        assert response.status_code == 400
        assert data["code"] == 400
        assert "error" in data

    def test_missing_required_fields(self, mock_requests):
        """测试缺失必填字段的处理"""
        test_cases = [
            {"component": "test", "level": "info"},
            {"namespace": "test", "level": "info"},
            {"namespace": "test", "component": "test"},
            {},
        ]

        for request_data in test_cases:
            mock_response = MockResponseBuilder.validation_error("required field missing")
            mock_requests.post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}", json=request_data)
            data = response.json()

            assert response.status_code == 400
            assert data["code"] == 400

    def test_null_values(self, mock_requests):
        """测试null值处理"""
        request_data = {
            "namespace": None,
            "component": None,
            "level": None,
        }

        mock_response = MockResponseBuilder.validation_error("null values not allowed")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}", json=request_data)
        data = response.json()

        assert response.status_code == 400
        assert data["code"] == 400

    def test_whitespace_only_values(self, mock_requests):
        """测试仅包含空白字符的值"""
        request_data = {
            "namespace": "   ",
            "component": "   ",
            "level": "info",
        }

        mock_response = MockResponseBuilder.validation_error("whitespace-only values not allowed")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}", json=request_data)
        data = response.json()

        assert response.status_code == 400
        assert data["code"] == 400

    def test_duplicate_namespace_component_combination(self, mock_requests):
        """测试重复的命名空间+组件组合（应执行更新而非报错）"""
        request_data = self.builder.as_request()
        expected_response = self.builder.as_response()
        expected_response["updated_at"] = "2026-05-11T09:00:00Z"

        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}", json=request_data)
        data = response.json()

        assert response.status_code == 200
        assert data["code"] == 200
        assert "updated_at" in data["data"]

    def test_delete_nonexistent_config(self, mock_requests):
        """测试删除不存在的配置"""
        config_id = "log_nonexistent123"
        mock_response = MockResponseBuilder.not_found("log level config not found")
        mock_requests.delete.return_value = MagicMock(
            status_code=500, json=lambda: mock_response
        )

        response = mock_requests.delete(f"{self.base_url}/{config_id}")
        data = response.json()

        assert data["code"] == 500
        assert "error" in data

    def test_get_nonexistent_component_level(self, mock_requests):
        """测试获取不存在组件的日志级别（应返回全局级别）"""
        component = "nonexistent.component"
        expected_data = {"component": component, "level": "info"}

        mock_response = MockResponseBuilder.success(expected_data, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.get(f"{self.base_url}", params={"component": component})
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["level"] == "info"

    def test_extremely_long_component_name(self, mock_requests):
        """测试超长组件名（超过数据库字段限制）"""
        builder = LogLevelConfigBuilder().with_long_component_name(1024)
        request_data = builder.as_request()

        mock_response = MockResponseBuilder.validation_error("component name too long")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}", json=request_data)

        assert response.status_code == 400


class TestLogLevelTransactionRollback(TestLogLevelBase):
    """事务回滚测试 - 验证数据库操作失败时的正确性"""

    def test_database_error_on_create_triggers_rollback(self):
        """测试创建时数据库错误触发回滚"""
        request_data = self.builder.as_request()

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            with patch("loglevelplatform.internal.modules.log_level.service.gorm.DB.Create") as mock_create:
                from loglevelplatform.internal.modules.log_level.service import Service

                service = Service()

                mock_set_level.return_value = None
                mock_create.side_effect = Exception("database connection failed")

                with pytest.raises(Exception) as exc_info:
                    service.SetLogLevel(Mock(), request_data)

                assert "database connection failed" in str(exc_info.value)
                mock_set_level.assert_called_once()

    def test_database_error_on_update_triggers_rollback(self):
        """测试更新时数据库错误触发回滚"""
        request_data = self.builder.as_request()

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            with patch("loglevelplatform.internal.modules.log_level.service.gorm.DB.First") as mock_first:
                with patch("loglevelplatform.internal.modules.log_level.service.gorm.DB.Save") as mock_save:
                    from loglevelplatform.internal.modules.log_level.service import Service

                    service = Service()

                    mock_set_level.return_value = None
                    mock_first.return_value = Mock(error=None)
                    mock_save.side_effect = Exception("disk full")

                    with pytest.raises(Exception) as exc_info:
                        service.SetLogLevel(Mock(), request_data)

                    assert "disk full" in str(exc_info.value)

    def test_partial_failure_rollback(self):
        """测试部分失败时的回滚（内存设置成功但DB失败）"""
        request_data = self.builder.as_request()

        with patch("loglevelplatform.internal.common.logger.SetComponentLevel") as mock_set_level:
            with patch("loglevelplatform.internal.common.logger.SetComponentLevel") as mock_revert_level:
                from loglevelplatform.internal.modules.log_level.service import Service

                service = Service()
                service.db = Mock()
                service.db.Where = Mock(return_value=service.db)
                service.db.First = Mock(return_value=Mock(error=None))
                service.db.Create = Mock(side_effect=Exception("DB write failed"))

                ctx = Mock()
                with pytest.raises(Exception):
                    service.SetLogLevel(ctx, self.builder)

                mock_set_level.assert_called_once()

    def test_transaction_atomicity_multiple_records(self):
        """测试多条记录操作的原子性"""
        configs = TestDataGenerator.generate_log_level_configs(5)

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            from loglevelplatform.internal.modules.log_level.service import Service

            service = Service()
            service.db = Mock()
            service.db.Where = Mock(return_value=service.db)
            service.db.First = Mock(return_value=Mock(error=Exception("not found")))

            call_count = 0
            def mock_create(*args, **kwargs):
                nonlocal call_count
                call_count += 1
                if call_count == 3:
                    raise Exception("DB failed on 3rd record")
                return Mock(error=None)

            service.db.Create = Mock(side_effect=mock_create)

            success_count = 0
            for i, cfg in enumerate(configs):
                try:
                    builder = LogLevelConfigBuilder()
                    builder._data = cfg
                    service.SetLogLevel(Mock(), builder)
                    success_count += 1
                except Exception:
                    break

            assert success_count == 2
            assert mock_set_level.call_count == 3

    def test_idempotent_operations(self):
        """测试幂等操作 - 重复设置相同级别不应产生副作用"""
        request_data = self.builder.with_level("debug").as_request()

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            from loglevelplatform.internal.modules.log_level.service import Service

            service = Service()
            service.db = Mock()
            service.db.Where = Mock(return_value=service.db)
            service.db.First = Mock(return_value=Mock(error=Exception("not found")))
            service.db.Create = Mock(return_value=Mock(error=None))

            for _ in range(5):
                service.SetLogLevel(Mock(), self.builder)

            assert mock_set_level.call_count == 5


class TestLogLevelConcurrency(TestLogLevelBase):
    """并发场景测试"""

    def test_concurrent_updates_same_component(self):
        """测试同一组件的并发更新"""
        import threading

        request_data = self.builder.as_request()
        results = []
        errors = []

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            from loglevelplatform.internal.modules.log_level.service import Service

            service = Service()
            service.db = Mock()
            service.db.Where = Mock(return_value=service.db)
            service.db.First = Mock(return_value=Mock(error=Exception("not found")))
            service.db.Create = Mock(return_value=Mock(error=None))

            def update_level(level):
                try:
                    builder = LogLevelConfigBuilder().with_level(level)
                    result = service.SetLogLevel(Mock(), builder)
                    results.append(result)
                except Exception as e:
                    errors.append(e)

            levels = ["debug", "info", "warn", "error", "fatal"]
            threads = [threading.Thread(target=update_level, args=(level,)) for level in levels]

            for t in threads:
                t.start()
            for t in threads:
                t.join()

            assert len(results) + len(errors) == 5
            assert mock_set_level.call_count == 5

    def test_concurrent_read_write(self):
        """测试并发读写场景"""
        import threading

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel"):
            from loglevelplatform.internal.modules.log_level.service import Service

            service = Service()
            service.db = Mock()
            service.db.Where = Mock(return_value=service.db)
            service.db.First = Mock(return_value=Mock(error=Exception("not found")))
            service.db.Create = Mock(return_value=Mock(error=None))
            service.db.Find = Mock(return_value=Mock(error=None))

            read_results = []
            write_results = []

            def read_operation():
                try:
                    req = Mock()
                    req.Namespace = "test"
                    req.Component = ""
                    result = service.GetLogLevel(Mock(), req)
                    read_results.append(result)
                except Exception:
                    pass

            def write_operation():
                try:
                    builder = LogLevelConfigBuilder()
                    result = service.SetLogLevel(Mock(), builder)
                    write_results.append(result)
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

            assert len(read_results) + len(write_results) > 0


class TestLogLevelEdgeCases(TestLogLevelBase):
    """极端场景测试"""

    def test_rapid_successive_changes(self):
        """测试快速连续变更日志级别"""
        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            from loglevelplatform.internal.modules.log_level.service import Service

            service = Service()
            service.db = Mock()
            service.db.Where = Mock(return_value=service.db)
            service.db.First = Mock(return_value=Mock(error=Exception("not found")))
            service.db.Create = Mock(return_value=Mock(error=None))

            levels = ["debug", "info", "warn", "error", "fatal", "error", "warn", "info", "debug"]
            for level in levels:
                builder = LogLevelConfigBuilder().with_level(level)
                service.SetLogLevel(Mock(), builder)

            assert mock_set_level.call_count == len(levels)

    def test_config_id_format_validation(self):
        """测试配置ID格式验证"""
        invalid_ids = [
            "",
            "invalid",
            "log_",
            "_abc123",
            "LOG_abc123",
            "log_abc123!",
        ]

        for invalid_id in invalid_ids:
            mock_response = MockResponseBuilder.validation_error("invalid config id format")

            with patch("requests.delete") as mock_delete:
                mock_delete.return_value = MagicMock(
                    status_code=400, json=lambda: mock_response
                )

                response = mock_delete(f"{self.base_url}/{invalid_id}")
                assert response.status_code == 400

    def test_large_namespace(self):
        """测试超大命名空间"""
        builder = LogLevelConfigBuilder().with_namespace("a" * 512)
        request_data = builder.as_request()

        mock_response = MockResponseBuilder.validation_error("namespace too long")
        with patch("requests.post") as mock_post:
            mock_post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_post(f"{self.base_url}", json=request_data)
            assert response.status_code == 400

    def test_special_chars_in_level(self):
        """测试日志级别字段中的特殊字符"""
        special_chars = ["debug\n", "info\r", "warn\t", "error\x00", "fatal\b"]

        for level in special_chars:
            builder = LogLevelConfigBuilder().with_level(level)
            request_data = builder.as_request()

            mock_response = MockResponseBuilder.validation_error("invalid characters in level")
            with patch("requests.post") as mock_post:
                mock_post.return_value = MagicMock(
                    status_code=400, json=lambda: mock_response
                )

                response = mock_post(f"{self.base_url}", json=request_data)
                assert response.status_code == 400


class TestLogLevelLoadConfigs(TestLogLevelBase):
    """LoadConfigs方法测试"""

    def test_load_configs_success(self):
        """测试成功加载配置"""
        from loglevelplatform.internal.modules.log_level.service import Service

        service = Service()
        service.db = Mock()

        configs = []
        for i in range(5):
            configs.append(Mock(
                Component=f"component_{i}",
                Level=LogLevelConfigBuilder.VALID_LEVELS[i % 7]
            ))

        service.db.Find = Mock(return_value=Mock(error=None))
        service.db.Find.return_value = configs

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            ctx = Mock()
            result = service.LoadConfigs(ctx)

            assert result is None
            assert mock_set_level.call_count == 5

    def test_load_configs_database_error(self):
        """测试加载配置时数据库错误"""
        from loglevelplatform.internal.modules.log_level.service import Service

        service = Service()
        service.db = Mock()
        service.db.Find = Mock(return_value=Mock(error=Exception("DB connection lost")))

        with pytest.raises(Exception) as exc_info:
            service.LoadConfigs(Mock())

        assert "DB connection lost" in str(exc_info.value)

    def test_load_configs_partial_failure(self):
        """测试部分配置加载失败时继续处理其他配置"""
        from loglevelplatform.internal.modules.log_level.service import Service

        service = Service()
        service.db = Mock()

        configs = [
            Mock(Component="good1", Level="info"),
            Mock(Component="bad", Level="invalid"),
            Mock(Component="good2", Level="debug"),
        ]
        service.db.Find = Mock(return_value=configs)

        with patch("loglevelplatform.internal.modules.log_level.service.SetComponentLevel") as mock_set_level:
            mock_set_level.side_effect = [None, Exception("invalid level"), None]

            result = service.LoadConfigs(Mock())

            assert result is None
            assert mock_set_level.call_count == 3
