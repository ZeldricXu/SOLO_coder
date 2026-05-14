import pytest
import asyncio
import os
from typing import Dict, Any, List
from datetime import datetime
from io import BytesIO

from reporthub.modules import ExportModule, RetryExportModule, AsyncExportModule
from reporthub.models import Report
from tests.data import TestDataBuilder


class TestExportModule:
    def test_supported_formats(self, export_module):
        assert "xlsx" in ExportModule.SUPPORTED_FORMATS
        assert "pdf" in ExportModule.SUPPORTED_FORMATS
        assert "csv" in ExportModule.SUPPORTED_FORMATS
        assert len(ExportModule.SUPPORTED_FORMATS) == 3

    def test_export_unsupported_format(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        with pytest.raises(ValueError) as exc_info:
            export_module.export_report(report, "unsupported_format")
        assert "不支持的导出格式" in str(exc_info.value)

    def test_create_export_config(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        config = export_module.create_export_config(
            template_id=template.template_id,
            export_formats=["xlsx", "pdf", "csv"],
            export_options={
                "xlsx": {"sheet_name": "测试数据"},
                "pdf": {"page_size": "A4"}
            }
        )
        assert config is not None
        assert config.export_id.startswith("export_")
        assert config.template_id == template.template_id
        assert len(config.export_formats) == 3
        assert "xlsx" in config.export_options

    def test_get_export_config(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        created_config = export_module.create_export_config(
            template_id=template.template_id,
            export_formats=["xlsx"]
        )
        retrieved_config = export_module.get_export_config(template.template_id)
        assert retrieved_config is not None
        assert retrieved_config.export_id == created_config.export_id


class TestRetryExportModule:
    def test_calculate_retry_delay_basic(self, in_memory_db, storage_module, statistics_module, test_builder):
        from reporthub.modules import RetryExportModule
        from reporthub.config.settings import settings
        original_config = settings.RETRY_COMPLEXITY_CONFIGS.copy()
        try:
            settings.update_retry_config(1, {"base_delay": 1.0, "backoff_multiplier": 2.0})
            retry_module = RetryExportModule(
                in_memory_db,
                storage_module,
                statistics_module
            )
            delay_1 = retry_module._calculate_retry_delay(0, 1)
            delay_2 = retry_module._calculate_retry_delay(1, 1)
            delay_3 = retry_module._calculate_retry_delay(2, 1)
            assert delay_2 > delay_1
            assert delay_3 > delay_2
        finally:
            for level, config in original_config.items():
                settings.update_retry_config(level, {
                    "base_delay": config.base_delay,
                    "backoff_multiplier": config.backoff_multiplier,
                    "max_retries": config.max_retries
                })

    def test_calculate_retry_delay_complexity_factor(self, in_memory_db, storage_module, statistics_module):
        from reporthub.modules import RetryExportModule
        from reporthub.config.settings import settings
        original_config = settings.RETRY_COMPLEXITY_CONFIGS.copy()
        try:
            settings.update_retry_config(1, {"base_delay": 1.0})
            settings.update_retry_config(5, {"base_delay": 1.0})
            retry_module = RetryExportModule(
                in_memory_db,
                storage_module,
                statistics_module
            )
            delay_simple = retry_module._calculate_retry_delay(1, 1)
            delay_complex = retry_module._calculate_retry_delay(1, 5)
            assert delay_complex > delay_simple
        finally:
            for level, config in original_config.items():
                settings.update_retry_config(level, {
                    "base_delay": config.base_delay,
                    "backoff_multiplier": config.backoff_multiplier,
                    "max_retries": config.max_retries
                })

    def test_report_complexity_calculation(self, retry_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report_small = test_builder.create_mock_report(in_memory_db, template.template_id, row_count=50)
        report_medium = test_builder.create_mock_report(in_memory_db, template.template_id, row_count=500)
        report_large = test_builder.create_mock_report(in_memory_db, template.template_id, row_count=15000)
        complexity_small = retry_export_module._get_report_complexity(report_small)
        complexity_medium = retry_export_module._get_report_complexity(report_medium)
        complexity_large = retry_export_module._get_report_complexity(report_large)
        assert complexity_small == 1
        assert complexity_medium == 2
        assert complexity_large == 4

    def test_export_with_retry_success_first_attempt(self, retry_export_module, in_memory_db, storage_module, statistics_module, test_builder, temp_storage_path):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        file_path = retry_export_module.export_with_retry(report, "xlsx")
        assert file_path is not None
        assert os.path.exists(file_path)
        attempts = retry_export_module.get_export_attempts(report.report_id)
        assert attempts == 0

    def test_export_with_retry_failure_notification(self, retry_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        original_export = retry_export_module.export_module.export_report
        call_count = [0]
        def fail_export(report_obj, format_str, options=None):
            call_count[0] += 1
            raise Exception(f"Mock failure attempt {call_count[0]}")
        retry_export_module.export_module.export_report = fail_export
        with pytest.raises(RuntimeError) as exc_info:
            retry_export_module.export_with_retry(report, "xlsx", custom_max_retries=2)
        assert "Export failed after 2 retries" in str(exc_info.value)
        assert call_count[0] == 3
        notifications = retry_export_module.get_failed_notifications()
        assert len(notifications) == 1
        assert notifications[0]["attempt_count"] == 2
        assert notifications[0]["report_id"] == report.report_id
        retry_export_module.export_module.export_report = original_export

    def test_clear_failed_notifications(self, retry_export_module):
        retry_export_module._failed_notifications = [
            {"report_id": "test_1", "attempt_count": 3},
            {"report_id": "test_2", "attempt_count": 3}
        ]
        assert len(retry_export_module.get_failed_notifications()) == 2
        retry_export_module.clear_failed_notifications()
        assert len(retry_export_module.get_failed_notifications()) == 0

    def test_export_with_custom_max_retries(self, retry_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        original_export = retry_export_module.export_module.export_report
        call_count = [0]
        def fail_then_succeed(report_obj, format_str, options=None):
            call_count[0] += 1
            if call_count[0] <= 2:
                raise Exception(f"Mock failure attempt {call_count[0]}")
            return original_export(report_obj, format_str, options)
        retry_export_module.export_module.export_report = fail_then_succeed
        file_path = retry_export_module.export_with_retry(report, "xlsx", custom_max_retries=3)
        assert file_path is not None
        assert call_count[0] == 3
        retry_export_module.export_module.export_report = original_export


class TestAsyncExportModule:
    @pytest.mark.asyncio
    async def test_async_export_submit(self, async_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        task_id = await async_export_module.export_report_async(
            report.report_id,
            "xlsx"
        )
        assert task_id is not None
        assert task_id.startswith("task_")

    @pytest.mark.asyncio
    async def test_async_export_completion(self, async_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        task_id = await async_export_module.export_report_async(
            report.report_id,
            "xlsx"
        )
        result = await async_export_module.wait_for_task(task_id, timeout=30)
        assert result is not None
        assert result["status"] == "completed"
        assert result["error"] is None
        assert "export_file" in result["result"]
        assert os.path.exists(result["result"]["export_file"])

    @pytest.mark.asyncio
    async def test_async_export_with_options(self, async_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        task_id = await async_export_module.export_report_async(
            report.report_id,
            "xlsx",
            export_options={
                "xlsx": {
                    "sheet_name": "CustomSheet"
                }
            }
        )
        result = await async_export_module.wait_for_task(task_id, timeout=30)
        assert result is not None
        assert result["status"] == "completed"

    @pytest.mark.asyncio
    async def test_async_export_task_progress(self, async_export_module, in_memory_db, storage_module, statistics_module, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report = test_builder.create_mock_report(in_memory_db, template.template_id)
        task_id = await async_export_module.export_report_async(
            report.report_id,
            "xlsx"
        )
        await asyncio.sleep(0.1)
        progress = async_export_module.get_task_progress(task_id)
        assert "task_id" in progress
        assert "status" in progress
        assert "progress" in progress


class TestExportConfigLoading:
    def test_export_config_creation(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        config = export_module.create_export_config(
            template_id=template.template_id,
            export_formats=["xlsx", "pdf"],
            export_options={
                "xlsx": {
                    "sheet_name": "销售数据",
                    "header_style": "bold",
                    "column_width": 20
                },
                "pdf": {
                    "page_size": "A4",
                    "orientation": "landscape"
                },
                "csv": {
                    "delimiter": ",",
                    "encoding": "utf-8"
                }
            }
        )
        assert config is not None
        assert len(config.export_formats) == 2
        assert "xlsx" in config.export_options
        assert "sheet_name" in config.export_options["xlsx"]
        assert config.export_options["xlsx"]["sheet_name"] == "销售数据"

    def test_export_config_retrieval(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        created_config = export_module.create_export_config(
            template_id=template.template_id,
            export_formats=["csv"],
            export_options={"csv": {"delimiter": ";"}}
        )
        retrieved = export_module.get_export_config(template.template_id)
        assert retrieved is not None
        assert retrieved.export_id == created_config.export_id
        assert "csv" in retrieved.export_formats


class TestBatchExport:
    def test_batch_export_success(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report1 = test_builder.create_mock_report(in_memory_db, template.template_id)
        report2 = test_builder.create_mock_report(in_memory_db, template.template_id)
        report3 = test_builder.create_mock_report(in_memory_db, template.template_id)
        results = export_module.batch_export(
            [report1, report2, report3],
            "csv"
        )
        assert len(results) == 3
        for report_id, result in results.items():
            assert "失败" not in result
            assert os.path.exists(result)

    def test_batch_export_partial_failure(self, export_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        report1 = test_builder.create_mock_report(in_memory_db, template.template_id)
        report2 = test_builder.create_mock_report(in_memory_db, template.template_id)
        original_export = export_module.export_report
        call_count = [0]
        def fail_second(report_obj, format_str, options=None):
            call_count[0] += 1
            if call_count[0] == 2:
                raise Exception("Mock failure for second report")
            return original_export(report_obj, format_str, options)
        export_module.export_report = fail_second
        results = export_module.batch_export(
            [report1, report2],
            "xlsx"
        )
        assert len(results) == 2
        failures = [r for r in results.values() if "失败" in str(r)]
        assert len(failures) == 1
        export_module.export_report = original_export
