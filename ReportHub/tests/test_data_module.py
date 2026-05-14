import pytest
import asyncio
import time
from typing import Dict, Any, List
from datetime import datetime

from reporthub.modules import DataModule, AsyncDataModule, TemplateModule
from reporthub.models import ReportTemplate, Report
from tests.data import TestDataBuilder


class TestDataQuery:
    def test_parse_template_fields(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        template_data = {
            "fields": [
                {"field_id": "date", "field_name": "日期", "field_type": "date"},
                {"field_id": "sales", "field_name": "销售额", "field_type": "number", "aggregation": "sum"},
                {"field_id": "product", "field_name": "产品", "field_type": "string"}
            ],
            "filters": [{"field": "date", "operator": "range", "value": "last_month"}]
        }
        mock_template = type('MockTemplate', (), template_data)()
        query_structure = data_module._parse_template_fields(mock_template)
        assert "select_fields" in query_structure
        assert "aggregations" in query_structure
        assert "filters" in query_structure
        assert len(query_structure["select_fields"]) == 3
        assert len(query_structure["aggregations"]) == 1
        assert query_structure["aggregations"][0]["field"] == "sales"
        assert query_structure["aggregations"][0]["function"] == "sum"

    def test_execute_data_query(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        data_source = {"source_type": "mysql"}
        query_structure = {
            "select_fields": ["date", "sales", "product"],
            "aggregations": [],
            "filters": []
        }
        result = data_module._execute_data_query(data_source, query_structure)
        assert isinstance(result, list)
        assert len(result) == 10
        assert "date" in result[0]
        assert "sales" in result[0]
        assert "product" in result[0]

    def test_filter_operators(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        test_row = {"id": 1, "name": "test", "amount": 100, "date": "2026-04-10", "category": "A"}
        assert data_module._check_filter(test_row, "amount", "eq", 100, None) is True
        assert data_module._check_filter(test_row, "amount", "ne", 100, None) is False
        assert data_module._check_filter(test_row, "amount", "gt", 50, None) is True
        assert data_module._check_filter(test_row, "amount", "lt", 200, None) is True
        assert data_module._check_filter(test_row, "name", "contains", "TEST", None) is True
        assert data_module._check_filter(test_row, "category", "in", ["A", "B"], None) is True

    def test_apply_filters(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        test_data = [
            {"id": 1, "amount": 100, "category": "A"},
            {"id": 2, "amount": 200, "category": "B"},
            {"id": 3, "amount": 300, "category": "A"},
            {"id": 4, "amount": 400, "category": "C"},
        ]
        filters = [
            {"field": "amount", "operator": "gte", "value": 200},
            {"field": "category", "operator": "in", "value": ["A", "B"]}
        ]
        result = data_module._apply_filters(test_data, filters, None)
        assert len(result) == 2
        assert result[0]["id"] == 2
        assert result[1]["id"] == 3


class TestAggregationCalculation:
    def test_sum_aggregation(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        test_data = [
            {"category": "A", "sales": 100},
            {"category": "A", "sales": 200},
            {"category": "B", "sales": 300},
            {"category": "B", "sales": 400},
        ]
        aggregations = [{"field": "sales", "function": "sum"}]
        result = data_module._apply_aggregations(test_data, aggregations)
        assert len(result) == 2
        a_data = result[result["category"] == "A"]
        b_data = result[result["category"] == "B"]
        assert a_data["sales"].values[0] == 300
        assert b_data["sales"].values[0] == 700

    def test_avg_aggregation(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        test_data = [
            {"category": "A", "sales": 100},
            {"category": "A", "sales": 300},
        ]
        aggregations = [{"field": "sales", "function": "avg"}]
        result = data_module._apply_aggregations(test_data, aggregations)
        assert len(result) == 1
        assert result["sales"].values[0] == 200

    def test_summary_calculation(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules.version_module import VersionModule
        import pandas as pd
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        test_data = [
            {"date": "2026-04-01", "sales": 100},
            {"date": "2026-04-02", "sales": 200},
            {"date": "2026-04-03", "sales": 300},
        ]
        df = pd.DataFrame(test_data)
        aggregations = [{"field": "sales", "function": "sum"}]
        summary = data_module._calculate_summary(df, aggregations)
        assert summary["sales_total"] == 600
        assert summary["sales_avg"] == 200
        assert summary["sales_max"] == 300
        assert summary["sales_min"] == 100
        assert summary["total_rows"] == 3


class TestReportGeneration:
    def test_generate_report(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        template_module = TemplateModule(in_memory_db)
        template = test_builder.create_mock_template(in_memory_db)
        report = data_module.generate_report(template, {"date_range": "2026-04"})
        assert report is not None
        assert report.report_id.startswith("report_")
        assert report.template_id == template.template_id
        assert report.status == "completed"
        assert report.report_data is not None
        assert "columns" in report.report_data
        assert "rows" in report.report_data
        assert "summary" in report.report_data

    def test_generate_report_with_generator(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        generator = "user_123"
        report = data_module.generate_report(template, {"date_range": "2026-04"}, generator=generator)
        assert report.generator == generator

    def test_generate_report_with_custom_name(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        custom_name = "自定义销售报表_2026年4月"
        report = data_module.generate_report(template, {"report_name": custom_name})
        assert report.report_name == custom_name

    def test_generate_report_statistics_update(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        data_module = DataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        initial_stat = statistics_module.get_template_statistics(template.template_id)
        report = data_module.generate_report(template, {"date_range": "2026-04"})
        current_stat = statistics_module.get_template_statistics(template.template_id)
        assert current_stat is not None
        assert current_stat.generate_count >= 1


class TestAsyncDataGeneration:
    @pytest.mark.asyncio
    async def test_async_generate_report(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        task_id = await async_data_module.generate_report_async(
            template.template_id,
            {"date_range": "2026-04"},
            "test_user"
        )
        assert task_id is not None
        assert task_id.startswith("task_")

    @pytest.mark.asyncio
    async def test_async_task_status_transition(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        task_id = await async_data_module.generate_report_async(
            template.template_id,
            {"date_range": "2026-04"}
        )
        progress = async_data_module.get_task_progress(task_id)
        assert progress["exists"] is True
        result = await async_data_module.wait_for_task(task_id, timeout=30)
        assert result is not None
        assert result["status"] == "completed"
        assert result["error"] is None
        assert "report_id" in result["result"]

    @pytest.mark.asyncio
    async def test_async_task_progress_query(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        task_id = await async_data_module.generate_report_async(
            template.template_id,
            {"date_range": "2026-04"}
        )
        await asyncio.sleep(0.1)
        progress = async_data_module.get_task_progress(task_id)
        assert "task_id" in progress
        assert "status" in progress
        assert "progress" in progress
        assert "retry_count" in progress

    @pytest.mark.asyncio
    async def test_queue_stats(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        stats = async_data_module.get_queue_stats()
        assert "pending" in stats
        assert "running" in stats
        assert "completed" in stats
        assert "max_workers" in stats

    @pytest.mark.asyncio
    async def test_async_task_cancel(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        task_id = await async_data_module.generate_report_async(
            template.template_id,
            {"date_range": "2026-04"}
        )
        await asyncio.sleep(0.05)
        status = async_data_module.get_task_status(task_id)
        if status and status.status.value not in ["completed", "failed"]:
            can_cancel = async_data_module.cancel_task(task_id)
            assert can_cancel is True


class TestTaskQueueProcessing:
    def test_task_queue_structure(self):
        from reporthub.modules.task_queue import TaskQueue, TaskStatus
        queue = TaskQueue(max_workers=3)
        stats = queue.get_queue_stats()
        assert stats["max_workers"] == 3
        assert stats["pending"] == 0
        assert stats["running"] == 0

    def test_task_handler_registration(self):
        from reporthub.modules.task_queue import TaskQueue
        queue = TaskQueue()
        def test_handler(payload):
            return {"success": True}
        queue.register_handler("test_task", test_handler)
        assert "test_task" in queue._task_handlers

    @pytest.mark.asyncio
    async def test_task_submission(self):
        from reporthub.modules.task_queue import TaskQueue
        queue = TaskQueue()
        def test_handler(payload):
            return {"result": payload["value"] * 2}
        queue.register_handler("multiply", test_handler)
        task_id = await queue.submit_task("multiply", {"value": 5})
        assert task_id is not None
        assert task_id.startswith("task_")

    @pytest.mark.asyncio
    async def test_task_execution_result(self):
        from reporthub.modules.task_queue import TaskQueue
        queue = TaskQueue()
        def test_handler(payload):
            return {"processed": True, "data": payload}
        queue.register_handler("echo", test_handler)
        test_payload = {"key": "value", "number": 42}
        task_id = await queue.submit_task("echo", test_payload)
        await asyncio.sleep(0.2)
        task_status = queue.get_task_status(task_id)
        assert task_status is not None
        if task_status.status.value == "completed":
            assert task_status.result is not None
            assert task_status.result["processed"] is True
            assert task_status.result["data"] == test_payload

    @pytest.mark.asyncio
    async def test_task_retry_on_failure(self):
        from reporthub.modules.task_queue import TaskQueue
        queue = TaskQueue()
        call_count = [0]
        def fail_handler(payload):
            call_count[0] += 1
            if call_count[0] < 3:
                raise Exception(f"Failure attempt {call_count[0]}")
            return {"success": True, "attempts": call_count[0]}
        queue.register_handler("retry_test", fail_handler)
        task_id = await queue.submit_task("retry_test", {}, max_retries=3)
        await asyncio.sleep(2)
        task_status = queue.get_task_status(task_id)
        assert task_status is not None
        if task_status.status.value == "completed":
            assert call_count[0] >= 3
            assert task_status.retry_count >= 2


class TestMultipleTasks:
    @pytest.mark.asyncio
    async def test_multiple_async_generations(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules.version_module import VersionModule
        version_module = VersionModule(in_memory_db, storage_module)
        async_data_module = AsyncDataModule(in_memory_db, storage_module, version_module, statistics_module)
        template = test_builder.create_mock_template(in_memory_db)
        task_ids = []
        for i in range(3):
            task_id = await async_data_module.generate_report_async(
                template.template_id,
                {"date_range": f"2026-0{i+1}"}
            )
            task_ids.append(task_id)
        assert len(task_ids) == 3
        results = []
        for task_id in task_ids:
            try:
                result = await async_data_module.wait_for_task(task_id, timeout=30)
                results.append(result)
            except Exception as e:
                results.append({"error": str(e)})
        successful = [r for r in results if r.get("status") == "completed"]
        assert len(successful) > 0
