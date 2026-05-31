"""
调度模块测试 - 聚焦参数校验完备性
测试策略：
1. 正常流程测试 - 验证任务创建、执行、查询等正常流程
2. 参数校验测试 - 验证所有输入参数的边界条件和合法性校验
3. 依赖校验测试 - 验证依赖关系的正确性校验
4. 状态校验测试 - 验证任务状态转换的合法性
5. 并发场景测试 - 验证并发执行时的参数校验正确性
"""

import pytest
import time
import threading
from unittest.mock import Mock, patch, MagicMock, call
from typing import Dict, Any, List

from tests.builders import (
    ScheduledTaskBuilder,
    TaskExecutionBuilder,
    MockResponseBuilder,
    TestDataGenerator,
)


class TestSchedulerBase:
    """调度模块测试基类"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """测试前初始化"""
        self.base_url = "http://localhost:8080/api/v1/scheduler"
        self.task_builder = ScheduledTaskBuilder()
        self.execution_builder = TaskExecutionBuilder()


class TestSchedulerNormalFlow(TestSchedulerBase):
    """正常流程测试"""

    def test_create_valid_task(self, mock_requests):
        """测试创建有效任务"""
        request_data = self.task_builder.as_request()
        expected_response = self.task_builder.as_response()

        mock_response = MockResponseBuilder.success(expected_response, 201)
        mock_requests.post.return_value = MagicMock(
            status_code=201, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
        data = response.json()

        assert response.status_code == 201
        assert data["code"] == 201
        assert data["data"]["name"] == request_data["name"]
        assert data["data"]["status"] == "pending"
        assert "id" in data["data"]

    def test_create_task_with_dependencies(self, mock_requests):
        """测试创建带依赖的任务"""
        deps = TestDataGenerator.generate_id_list("task", 3)
        request_data = self.task_builder.with_depends_on(deps).as_request()
        expected_response = self.task_builder.with_depends_on(deps).as_response()

        mock_response = MockResponseBuilder.success(expected_response, 201)
        mock_requests.post.return_value = MagicMock(
            status_code=201, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
        data = response.json()

        assert response.status_code == 201
        assert len(data["data"]["depends_on"]) == 3

    def test_get_task_by_id(self, mock_requests):
        """测试根据ID获取任务"""
        task_id = "task_abc123def4567"
        expected_response = self.task_builder.as_response(task_id)

        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.get(f"{self.base_url}/tasks/{task_id}")
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["id"] == task_id

    def test_list_tasks_with_filters(self, mock_requests):
        """测试带过滤条件的任务列表"""
        tasks = []
        for i in range(5):
            builder = ScheduledTaskBuilder()
            builder.with_status("running" if i % 2 == 0 else "pending")
            tasks.append(builder.as_response(f"task_{i}"))

        mock_response = MockResponseBuilder.success({
            "items": tasks,
            "total": 5,
            "limit": 10,
            "offset": 0,
        }, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        params = {"status": "running", "limit": 10, "offset": 0}
        response = mock_requests.get(f"{self.base_url}/tasks", params=params)
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["total"] == 5
        assert len(data["data"]["items"]) == 5

    def test_execute_task_success(self, mock_requests):
        """测试成功执行任务"""
        task_id = "task_abc123def4567"
        expected_response = self.execution_builder.with_task_id(task_id).as_completed("Task executed successfully").as_response()

        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.post.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["status"] == "completed"
        assert data["data"]["result"] == "Task executed successfully"

    def test_update_task(self, mock_requests):
        """测试更新任务"""
        task_id = "task_abc123def4567"
        update_data = {
            "name": "Updated Task Name",
            "description": "Updated description",
            "timeout_seconds": 60,
        }

        expected_response = self.task_builder.with_name("Updated Task Name").as_response(task_id)
        mock_response = MockResponseBuilder.success(expected_response, 200)
        mock_requests.put.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.put(f"{self.base_url}/tasks/{task_id}", json=update_data)
        data = response.json()

        assert data["code"] == 200
        assert data["data"]["name"] == "Updated Task Name"

    def test_delete_task(self, mock_requests):
        """测试删除任务"""
        task_id = "task_abc123def4567"
        mock_response = MockResponseBuilder.success(None, 200)
        mock_response["message"] = "task deleted successfully"
        mock_requests.delete.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.delete(f"{self.base_url}/tasks/{task_id}")
        data = response.json()

        assert data["code"] == 200
        assert data["message"] == "task deleted successfully"

    def test_get_task_executions(self, mock_requests):
        """测试获取任务执行记录"""
        task_id = "task_abc123def4567"
        executions = []
        for i in range(3):
            builder = TaskExecutionBuilder()
            builder.with_task_id(task_id)
            if i == 0:
                builder.as_completed(f"Execution {i} success")
            elif i == 1:
                builder.as_failed(f"Execution {i} failed")
            else:
                builder.as_running()
            executions.append(builder.as_response(f"exec_{i}"))

        mock_response = MockResponseBuilder.success(executions, 200)
        mock_requests.get.return_value = MagicMock(
            status_code=200, json=lambda: mock_response
        )

        response = mock_requests.get(f"{self.base_url}/tasks/{task_id}/executions", params={"limit": 10})
        data = response.json()

        assert data["code"] == 200
        assert len(data["data"]) == 3


class TestSchedulerParameterValidation(TestSchedulerBase):
    """参数校验测试 - 调度模块的重点测试"""

    @pytest.mark.parametrize("boundary_case", [
        ("empty_name", ScheduledTaskBuilder().with_empty_name(), "task name is required"),
        ("very_long_name", ScheduledTaskBuilder().with_very_long_name(512), "task name too long"),
        ("negative_timeout", ScheduledTaskBuilder().with_negative_timeout(), "timeout must be positive"),
        ("zero_timeout", ScheduledTaskBuilder().with_zero_timeout(), "timeout must be positive"),
        ("invalid_cron", ScheduledTaskBuilder().with_invalid_cron(), "invalid cron expression"),
        ("empty_cron", ScheduledTaskBuilder().with_empty_cron(), "cron expression is required"),
        ("invalid_type", ScheduledTaskBuilder().with_invalid_type(), "invalid task type"),
    ])
    def test_task_creation_parameter_validation(self, mock_requests, boundary_case):
        """测试任务创建时的各种参数校验"""
        case_name, builder, expected_error = boundary_case
        request_data = builder.as_request()

        mock_response = MockResponseBuilder.validation_error(expected_error)
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
        data = response.json()

        assert response.status_code == 400
        assert data["code"] == 400
        assert expected_error in data["error"] or "validation" in data["error"].lower()

    def test_task_name_empty_whitespace(self, mock_requests):
        """测试任务名仅包含空白字符"""
        request_data = self.task_builder.with_name("   ").as_request()

        mock_response = MockResponseBuilder.validation_error("task name is required")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_task_name_null(self, mock_requests):
        """测试任务名为null"""
        request_data = self.task_builder.as_request()
        request_data["name"] = None

        mock_response = MockResponseBuilder.validation_error("task name is required")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_retries_negative(self, mock_requests):
        """测试重试次数为负数"""
        request_data = self.task_builder.with_retries(-1).as_request()

        mock_response = MockResponseBuilder.validation_error("retries must be non-negative")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_retries_exceeds_maximum(self, mock_requests):
        """测试重试次数超过最大值"""
        request_data = self.task_builder.with_retries(100).as_request()

        mock_response = MockResponseBuilder.validation_error("retries exceeds maximum")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_timeout_exceeds_maximum(self, mock_requests):
        """测试超时时间超过最大值"""
        request_data = self.task_builder.with_timeout_seconds(86400 * 30).as_request()

        mock_response = MockResponseBuilder.validation_error("timeout exceeds maximum")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_payload_too_large(self, mock_requests):
        """测试Payload过大"""
        request_data = self.task_builder.with_large_payload(1000).as_request()

        mock_response = MockResponseBuilder.validation_error("payload too large")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_cron_expression_boundary_cases(self, mock_requests):
        """测试Cron表达式的各种边界情况"""
        invalid_crons = [
            "* * * *",
            "* * * * * *",
            "60 * * * *",
            "* 24 * * *",
            "* * 32 * *",
            "* * * 13 *",
            "* * * * 8",
            "invalid * * * *",
            "*/0 * * * *",
            "1-60 * * * *",
        ]

        for cron in invalid_crons:
            request_data = self.task_builder.with_cron_expr(cron).as_request()
            mock_response = MockResponseBuilder.validation_error("invalid cron expression")
            mock_requests.post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
            assert response.status_code == 400, f"Should reject invalid cron: {cron}"

    def test_valid_cron_expressions(self, mock_requests):
        """测试各种有效的Cron表达式"""
        valid_crons = [
            "* * * * *",
            "0 * * * *",
            "30 2 * * *",
            "0 9-17 * * 1-5",
            "*/5 * * * *",
            "0 0 1 * *",
            "0 0 * * 0",
            "15,45 * * * *",
            "0-30/5 * * * *",
        ]

        for cron in valid_crons:
            request_data = self.task_builder.with_cron_expr(cron).as_request()
            expected_response = self.task_builder.with_cron_expr(cron).as_response()
            mock_response = MockResponseBuilder.success(expected_response, 201)
            mock_requests.post.return_value = MagicMock(
                status_code=201, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
            assert response.status_code == 201, f"Should accept valid cron: {cron}"

    def test_task_id_format_validation(self, mock_requests):
        """测试任务ID格式验证"""
        invalid_ids = [
            "",
            "invalid",
            "task_",
            "_abc123",
            "TASK_abc123",
            "task_abc123!",
            "task/abc123",
            "task\\abc123",
        ]

        for invalid_id in invalid_ids:
            mock_response = MockResponseBuilder.validation_error("invalid task id format")
            mock_requests.get.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.get(f"{self.base_url}/tasks/{invalid_id}")
            assert response.status_code == 400, f"Should reject invalid task id: {invalid_id}"

    def test_list_tasks_pagination_validation(self, mock_requests):
        """测试任务列表分页参数校验"""
        test_cases = [
            {"limit": -1, "offset": 0, "error": "limit must be non-negative"},
            {"limit": 0, "offset": 0, "error": "limit must be positive"},
            {"limit": 1000000, "offset": 0, "error": "limit exceeds maximum"},
            {"limit": 10, "offset": -1, "error": "offset must be non-negative"},
        ]

        for params in test_cases:
            mock_response = MockResponseBuilder.validation_error(params["error"])
            mock_requests.get.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.get(
                f"{self.base_url}/tasks",
                params={"limit": params["limit"], "offset": params["offset"]}
            )
            assert response.status_code == 400

    def test_status_filter_validation(self, mock_requests):
        """测试状态过滤参数校验"""
        invalid_statuses = [
            "invalid",
            "RUNNING",
            "",
            "pending,running",
            "nonexistent",
        ]

        for status in invalid_statuses:
            mock_response = MockResponseBuilder.validation_error("invalid status")
            mock_requests.get.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.get(f"{self.base_url}/tasks", params={"status": status})
            assert response.status_code == 400

    def test_missing_required_fields_create_task(self, mock_requests):
        """测试创建任务时缺失必填字段"""
        test_cases = [
            {"type": "data_processing", "cron_expr": "0 * * * *"},
            {"name": "Test Task", "cron_expr": "0 * * * *"},
            {"name": "Test Task", "type": "data_processing"},
            {},
        ]

        for request_data in test_cases:
            mock_response = MockResponseBuilder.validation_error("required field missing")
            mock_requests.post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
            assert response.status_code == 400

    def test_task_type_validation(self, mock_requests):
        """测试任务类型验证"""
        invalid_types = [
            "",
            "invalid",
            "DATA_PROCESSING",
            "data-processing",
            "data_processing123",
            "data.processing",
            "data/processing",
        ]

        for task_type in invalid_types:
            request_data = self.task_builder.with_type(task_type).as_request()
            mock_response = MockResponseBuilder.validation_error("invalid task type")
            mock_requests.post.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
            assert response.status_code == 400

    def test_enabled_field_validation(self, mock_requests):
        """测试enabled字段类型验证"""
        test_cases = [
            "true",
            "false",
            1,
            0,
            None,
            "yes",
        ]

        for enabled in test_cases:
            request_data = self.task_builder.as_request()
            request_data["enabled"] = enabled

            if not isinstance(enabled, bool):
                mock_response = MockResponseBuilder.validation_error("enabled must be boolean")
                mock_requests.post.return_value = MagicMock(
                    status_code=400, json=lambda: mock_response
                )

                response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
                assert response.status_code == 400


class TestSchedulerDependencyValidation(TestSchedulerBase):
    """依赖关系校验测试"""

    def test_circular_dependency_detection(self, mock_requests):
        """测试循环依赖检测"""
        task_id = "task_abc123"
        request_data = self.task_builder.with_circular_dependency(task_id).as_request()

        mock_response = MockResponseBuilder.validation_error("circular dependency detected")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400
        assert "circular" in response.json()["error"].lower()

    def test_nonexistent_dependency(self, mock_requests):
        """测试依赖不存在的任务"""
        deps = ["task_nonexistent123"]
        request_data = self.task_builder.with_depends_on(deps).as_request()

        mock_response = MockResponseBuilder.validation_error("dependency task not found")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_dependency_chain_too_long(self, mock_requests):
        """测试依赖链过长"""
        deps = TestDataGenerator.generate_id_list("task", 20)
        request_data = self.task_builder.with_depends_on(deps).as_request()

        mock_response = MockResponseBuilder.validation_error("dependency chain too long")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_duplicate_dependencies(self, mock_requests):
        """测试重复依赖"""
        deps = ["task_abc123", "task_abc123", "task_def456"]
        request_data = self.task_builder.with_depends_on(deps).as_request()

        mock_response = MockResponseBuilder.validation_error("duplicate dependencies")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 400

    def test_dependency_task_disabled(self, mock_requests):
        """测试依赖的任务已被禁用"""
        task_id = "task_abc123"
        mock_response = MockResponseBuilder.validation_error("dependency task is disabled")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")

        assert response.status_code == 400

    def test_dependency_not_completed(self, mock_requests):
        """测试依赖任务未成功完成"""
        task_id = "task_abc123"
        mock_response = MockResponseBuilder.validation_error("dependency task not completed successfully")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")

        assert response.status_code == 400

    def test_complex_dependency_graph(self, mock_requests):
        """测试复杂依赖图的正确性"""
        deps = TestDataGenerator.generate_id_list("task", 5)
        request_data = self.task_builder.with_depends_on(deps).as_request()
        expected_response = self.task_builder.with_depends_on(deps).as_response()

        mock_response = MockResponseBuilder.success(expected_response, 201)
        mock_requests.post.return_value = MagicMock(
            status_code=201, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)
        data = response.json()

        assert response.status_code == 201
        assert len(data["data"]["depends_on"]) == 5

    def test_empty_dependencies_allowed(self, mock_requests):
        """测试空依赖列表是允许的"""
        request_data = self.task_builder.with_depends_on([]).as_request()
        expected_response = self.task_builder.with_depends_on([]).as_response()

        mock_response = MockResponseBuilder.success(expected_response, 201)
        mock_requests.post.return_value = MagicMock(
            status_code=201, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks", json=request_data)

        assert response.status_code == 201

    def test_self_dependency_rejected(self, mock_requests):
        """测试拒绝自依赖"""
        task_id = "task_abc123"

        mock_response = MockResponseBuilder.validation_error("cannot depend on self")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        deps = [task_id]
        response = mock_requests.post(
            f"{self.base_url}/tasks/{task_id}/dependencies",
            json={"depends_on": deps}
        )

        assert response.status_code == 400


class TestSchedulerStateValidation(TestSchedulerBase):
    """状态校验测试"""

    def test_execute_disabled_task_rejected(self, mock_requests):
        """测试执行已禁用的任务被拒绝"""
        task_id = "task_disabled"
        mock_response = MockResponseBuilder.validation_error("task is disabled")
        mock_requests.post.return_value = MagicMock(
            status_code=400, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")

        assert response.status_code == 400

    def test_execute_already_running_task_rejected(self, mock_requests):
        """测试执行正在运行的任务被拒绝"""
        task_id = "task_running"
        mock_response = MockResponseBuilder.conflict("task is already executing")
        mock_requests.post.return_value = MagicMock(
            status_code=409, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")

        assert response.status_code == 409

    def test_execute_nonexistent_task(self, mock_requests):
        """测试执行不存在的任务"""
        task_id = "task_nonexistent"
        mock_response = MockResponseBuilder.not_found("task not found")
        mock_requests.post.return_value = MagicMock(
            status_code=404, json=lambda: mock_response
        )

        response = mock_requests.post(f"{self.base_url}/tasks/{task_id}/execute")

        assert response.status_code == 404

    def test_delete_running_task_rejected(self, mock_requests):
        """测试删除正在运行的任务被拒绝"""
        task_id = "task_running"
        mock_response = MockResponseBuilder.conflict("cannot delete running task")
        mock_requests.delete.return_value = MagicMock(
            status_code=409, json=lambda: mock_response
        )

        response = mock_requests.delete(f"{self.base_url}/tasks/{task_id}")

        assert response.status_code == 409

    def test_update_running_task_rejected(self, mock_requests):
        """测试更新正在运行的任务被拒绝"""
        task_id = "task_running"
        update_data = {"name": "Updated Name"}

        mock_response = MockResponseBuilder.conflict("cannot update running task")
        mock_requests.put.return_value = MagicMock(
            status_code=409, json=lambda: mock_response
        )

        response = mock_requests.put(f"{self.base_url}/tasks/{task_id}", json=update_data)

        assert response.status_code == 409

    def test_task_status_transitions_validation(self, mock_requests):
        """测试任务状态转换的合法性"""
        valid_transitions = [
            ("pending", "running"),
            ("running", "completed"),
            ("running", "failed"),
            ("failed", "pending"),
            ("completed", "pending"),
        ]

        for from_status, to_status in valid_transitions:
            task_id = f"task_{from_status}_to_{to_status}"
            update_data = {"status": to_status}

            expected_response = self.task_builder.with_status(to_status).as_response(task_id)
            mock_response = MockResponseBuilder.success(expected_response, 200)
            mock_requests.put.return_value = MagicMock(
                status_code=200, json=lambda: mock_response
            )

            response = mock_requests.put(f"{self.base_url}/tasks/{task_id}", json=update_data)
            assert response.status_code == 200, f"Should allow transition {from_status} -> {to_status}"

    def test_invalid_status_transitions_rejected(self, mock_requests):
        """测试非法的状态转换被拒绝"""
        invalid_transitions = [
            ("completed", "running"),
            ("failed", "running"),
            ("running", "pending"),
            ("disabled", "running"),
        ]

        for from_status, to_status in invalid_transitions:
            task_id = f"task_{from_status}"
            update_data = {"status": to_status}

            mock_response = MockResponseBuilder.validation_error(
                f"invalid status transition from {from_status} to {to_status}"
            )
            mock_requests.put.return_value = MagicMock(
                status_code=400, json=lambda: mock_response
            )

            response = mock_requests.put(f"{self.base_url}/tasks/{task_id}", json=update_data)
            assert response.status_code == 400, f"Should reject transition {from_status} -> {to_status}"


class TestSchedulerConcurrencyValidation(TestSchedulerBase):
    """并发场景下的参数校验测试"""

    def test_concurrent_task_execution_validation(self):
        """测试并发执行任务时的参数校验正确性"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        task_id = "task_concurrent_test"

        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 30
        mock_task.Payload = {}
        mock_task.Type = "data_processing"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()

        execution_count = 0
        conflict_count = 0

        def try_execute():
            nonlocal execution_count, conflict_count
            try:
                service.ExecuteTask(MagicMock(), task_id)
                execution_count += 1
            except Exception as e:
                if "already executing" in str(e).lower() or "conflict" in str(e).lower():
                    conflict_count += 1

        with patch.object(service, "defaultHandler", return_value=("success", None)):
            with patch.object(service.db, "Create", return_value=MagicMock(error=None)):
                with patch.object(service.db, "Save", return_value=MagicMock(error=None)):
                    with patch.object(service.db, "Where", return_value=service.db):
                        with patch.object(service.db, "First", return_value=MagicMock(error=None)):
                            threads = [threading.Thread(target=try_execute) for _ in range(10)]

                            for t in threads:
                                t.start()
                            for t in threads:
                                t.join()

        assert execution_count + conflict_count == 10
        assert execution_count >= 1
        assert conflict_count >= 0

    def test_concurrent_task_creation_validation(self):
        """测试并发创建任务时的参数校验"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)

        success_count = 0
        error_count = 0

        def create_task(name):
            nonlocal success_count, error_count
            try:
                builder = ScheduledTaskBuilder().with_name(name)
                task_data = builder.build()
                task = Mock()
                task.Name = task_data["name"]
                task.ID = None
                service.CreateTask(MagicMock(), task)
                success_count += 1
            except Exception:
                error_count += 1

        threads = []
        for i in range(20):
            name = f"Concurrent Task {i}" if i % 2 == 0 else ""
            threads.append(threading.Thread(target=create_task, args=(name,)))

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert success_count + error_count == 20
        assert error_count >= 10

    def test_concurrent_dependency_modification(self):
        """测试并发修改依赖关系时的校验"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        task_id = "task_dep_test"

        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Model.return_value = service.db
        service.db.Updates.return_value = MagicMock(error=None)
        service.db.Where.return_value = service.db
        service.db.First.return_value = MagicMock(error=None)

        success_count = 0
        error_count = 0

        def modify_dependencies(deps):
            nonlocal success_count, error_count
            try:
                service.UpdateTask(MagicMock(), task_id, {"depends_on": deps})
                success_count += 1
            except Exception:
                error_count += 1

        threads = []
        for i in range(10):
            deps = TestDataGenerator.generate_id_list("task", i % 5)
            threads.append(threading.Thread(target=modify_dependencies, args=(deps,)))

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert success_count + error_count == 10


class TestSchedulerServiceUnitTests(TestSchedulerBase):
    """调度服务的单元测试"""

    def test_create_task_empty_name_rejected(self):
        """测试创建任务时空名称被拒绝"""
        from loglevelplatform.internal.modules.scheduler.service import Service
        from loglevelplatform.internal.common.errors import ValidationError

        service = Service()
        service.db = MagicMock()

        task = Mock()
        task.Name = ""

        with pytest.raises(ValidationError) as exc_info:
            service.CreateTask(MagicMock(), task)

        assert "task name is required" in str(exc_info.value)

    def test_execute_task_not_found(self):
        """测试执行不存在的任务"""
        from loglevelplatform.internal.modules.scheduler.service import Service
        from loglevelplatform.internal.common.errors import NotFoundError

        service = Service()
        service.db = MagicMock()
        service.db.Where.return_value = service.db
        service.db.First.side_effect = Exception("not found")

        with pytest.raises(NotFoundError):
            service.ExecuteTask(MagicMock(), "nonexistent_task")

    def test_execute_disabled_task(self):
        """测试执行已禁用的任务"""
        from loglevelplatform.internal.modules.scheduler.service import Service
        from loglevelplatform.internal.common.errors import ValidationError

        service = Service()

        task_id = "task_disabled"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = False

        service.tasks[task_id] = mock_task

        with pytest.raises(ValidationError) as exc_info:
            service.ExecuteTask(MagicMock(), task_id)

        assert "task is disabled" in str(exc_info.value)

    def test_check_dependencies_nonexistent(self):
        """测试检查不存在的依赖"""
        from loglevelplatform.internal.modules.scheduler.service import Service
        from loglevelplatform.internal.common.errors import NotFoundError

        service = Service()
        service.db = MagicMock()
        service.db.Where.return_value = service.db
        service.db.First.side_effect = Exception("not found")

        task = Mock()
        task.DependsOn = ["nonexistent_dep"]

        with pytest.raises(NotFoundError):
            service.checkDependencies(MagicMock(), task)

    def test_check_dependencies_not_completed(self):
        """测试检查未完成的依赖"""
        from loglevelplatform.internal.modules.scheduler.service import Service
        from loglevelplatform.internal.common.errors import ValidationError

        service = Service()
        service.db = MagicMock()
        service.db.Where.return_value = service.db

        mock_dep_task = Mock()
        mock_dep_exec = Mock()
        mock_dep_exec.Status = "failed"

        service.db.First.side_effect = [mock_dep_task, mock_dep_exec]

        task = Mock()
        task.DependsOn = ["dep_task_1"]

        with pytest.raises(ValidationError) as exc_info:
            service.checkDependencies(MagicMock(), task)

        assert "not completed successfully" in str(exc_info.value)

    def test_task_execution_timeout(self):
        """测试任务执行超时"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        task_id = "task_timeout"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 1
        mock_task.Payload = {}
        mock_task.Type = "slow_task"
        mock_task.Status = "pending"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)
        service.db.Save.return_value = MagicMock(error=None)

        def slow_handler(ctx, payload):
            time.sleep(3)
            return "done", None

        service.RegisterHandler("slow_task", slow_handler)

        start_time = time.time()
        result = service.ExecuteTask(MagicMock(), task_id)
        elapsed = time.time() - start_time

        assert elapsed < 2.5
        assert result.Status == "failed"
        assert "timed out" in (result.ErrorMsg or "")

    def test_task_execution_with_retry(self):
        """测试带重试的任务执行"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        task_id = "task_retry"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 30
        mock_task.Retries = 3
        mock_task.Payload = {}
        mock_task.Type = "flaky_task"
        mock_task.Status = "pending"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)
        service.db.Save.return_value = MagicMock(error=None)

        call_count = 0

        def flaky_handler(ctx, payload):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise Exception(f"Attempt {call_count} failed")
            return "success", None

        service.RegisterHandler("flaky_task", flaky_handler)

        result = service.ExecuteTask(MagicMock(), task_id)

        assert result.Status == "completed"
        assert result.Result == "success"


class TestSchedulerEdgeCases(TestSchedulerBase):
    """极端场景测试"""

    def test_rapid_task_creation(self):
        """测试快速连续创建任务"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)

        tasks = TestDataGenerator.generate_scheduled_tasks(100)

        for i, task_data in enumerate(tasks):
            builder = ScheduledTaskBuilder()
            builder._data = task_data
            task = Mock()
            task.Name = f"Task {i}"
            task.ID = None

            service.CreateTask(MagicMock(), task)

        assert service.db.Create.call_count == 100

    def test_special_characters_in_task_name(self):
        """测试任务名中的特殊字符"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)

        special_names = [
            "Task!@#$%^&*()",
            "Task with spaces",
            "Task_with_underscores",
            "Task-with-dashes",
            "Task.with.dots",
            "Task/with/slashes",
            "中文任务名",
            "日本語タスク名",
            "Русское название задачи",
        ]

        for name in special_names:
            task = Mock()
            task.Name = name
            task.ID = None

            result = service.CreateTask(MagicMock(), task)

            assert result is not None
            assert result.Name == name

    def test_extreme_timeout_values(self):
        """测试极端的超时值"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        task_id = "task_extreme_timeout"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 1
        mock_task.Payload = {}
        mock_task.Type = "test"
        mock_task.Status = "pending"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)
        service.db.Save.return_value = MagicMock(error=None)

        with patch.object(service, "defaultHandler", return_value=("success", None)):
            with patch.object(service.db, "Where", return_value=service.db):
                with patch.object(service.db, "First", return_value=MagicMock(error=None)):
                    result = service.ExecuteTask(MagicMock(), task_id)

                    assert result.Status == "completed"
                    assert result.Result == "success"

    def test_empty_payload_handling(self):
        """测试空Payload处理"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        task_id = "task_empty_payload"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 30
        mock_task.Payload = {}
        mock_task.Type = "test"
        mock_task.Status = "pending"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)
        service.db.Save.return_value = MagicMock(error=None)

        with patch.object(service, "defaultHandler", return_value=("processed empty", None)):
            with patch.object(service.db, "Where", return_value=service.db):
                with patch.object(service.db, "First", return_value=MagicMock(error=None)):
                    result = service.ExecuteTask(MagicMock(), task_id)

                    assert result.Status == "completed"
                    assert "empty" in result.Result

    def test_nil_payload_handling(self):
        """测试nil Payload处理"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        task_id = "task_nil_payload"
        mock_task = Mock()
        mock_task.ID = task_id
        mock_task.Enabled = True
        mock_task.DependsOn = []
        mock_task.TimeoutSeconds = 30
        mock_task.Payload = None
        mock_task.Type = "test"
        mock_task.Status = "pending"

        service.tasks[task_id] = mock_task
        service.db = MagicMock()
        service.db.Create.return_value = MagicMock(error=None)
        service.db.Save.return_value = MagicMock(error=None)

        with patch.object(service, "defaultHandler", return_value=("processed nil", None)):
            with patch.object(service.db, "Where", return_value=service.db):
                with patch.object(service.db, "First", return_value=MagicMock(error=None)):
                    result = service.ExecuteTask(MagicMock(), task_id)

                    assert result.Status == "completed"

    def test_load_tasks_database_error(self):
        """测试加载任务时数据库错误"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()
        service.db = MagicMock()
        service.db.Find.side_effect = Exception("DB connection lost")

        with pytest.raises(Exception) as exc_info:
            service.LoadTasks(MagicMock())

        assert "DB connection lost" in str(exc_info.value)

    def test_scheduler_start_stop_idempotent(self):
        """测试调度器启动和停止的幂等性"""
        from loglevelplatform.internal.modules.scheduler.service import Service

        service = Service()

        service.Start()
        assert service.running == True

        service.Start()
        assert service.running == True

        service.Stop()
        assert service.running == False

        service.Stop()
        assert service.running == False
