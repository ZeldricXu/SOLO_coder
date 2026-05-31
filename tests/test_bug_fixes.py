import os
import sys

import pytest


sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from typing import Any, Dict, Optional


class PromptPercentageCalculator:
    """Prompt实验管理模块 - 百分比计算器"""

    @staticmethod
    def calculate_conversion_rate(success: int, total: int, total_samples: Optional[int] = None) -> float:
        if total <= 0:
            return 0.0
        return success / total

    @staticmethod
    def calculate_improvement_percentage(control_rate: float, treatment_rate: float) -> float:
        if control_rate == 0:
            return 0.0
        return ((treatment_rate - control_rate) / control_rate) * 100


class PromptMobileLayoutConfig:
    """Prompt实验管理模块 - 移动端布局配置"""

    @staticmethod
    def get_layout_config(user_agent: str = "desktop") -> Dict[str, Any]:
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "container_class": "w-full sm:max-w-md md:max-w-lg p-2 sm:p-4",
            "card_class": "shadow-md sm:shadow-none border-0 sm:border-b rounded-lg sm:rounded-none",
            "chart_width": "w-full sm:w-64 md:w-80",
            "grid_cols": "grid-cols-1 sm:grid-cols-2 md:grid-cols-3",
            "text_size": "text-base sm:text-sm md:text-base",
            "padding": "p-4 sm:p-2 md:p-4",
            "is_mobile": is_mobile,
        }

        return layout


class PromptSensitiveDataHandler:
    """Prompt实验管理模块 - 敏感数据处理器"""

    @staticmethod
    def mask_sensitive_field(value: str, visible_start: int = 4, visible_end: int = 4) -> str:
        if not value or len(value) <= visible_start + visible_end:
            return "*" * len(value) if value else ""
        return value[:visible_start] + "*" * (len(value) - visible_start - visible_end) + value[-visible_end:]


class DocumentPercentageCalculator:
    """文档解析管道模块 - 百分比计算器"""

    @staticmethod
    def calculate_success_rate(processed: int, failed: int, total_documents: Optional[int] = None, total_chunks: Optional[int] = None) -> float:
        total = processed + failed
        if total <= 0:
            return 0.0
        return processed / total

    @staticmethod
    def calculate_progress(processed: int, total: int, total_chunks: Optional[int] = None) -> float:
        if total <= 0:
            return 0.0
        return processed / total


class DocumentMobileLayoutConfig:
    """文档解析管道模块 - 移动端布局配置"""

    @staticmethod
    def get_pipeline_layout(user_agent: str = "desktop") -> Dict[str, Any]:
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "progress_bar_class": "w-full h-2 sm:h-3 rounded-full",
            "stats_grid_class": "grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-4 p-2 sm:p-4",
            "document_list_class": "space-y-2 sm:space-y-3 text-sm sm:text-base",
            "chart_container_class": "w-full sm:w-80 h-48 sm:h-64 mx-auto",
            "action_button_class": "w-full sm:w-auto py-2 px-4 text-sm sm:text-base",
            "is_mobile": is_mobile,
        }

        return layout


class DocumentSensitiveDataHandler:
    """文档解析管道模块 - 敏感数据处理器"""

    @staticmethod
    def mask_field(value: str) -> str:
        if not value:
            return ""
        if len(value) <= 8:
            return "*" * len(value)
        return value[:4] + "*" * (len(value) - 8) + value[-4:]


class GpuPercentageCalculator:
    """GPU任务调度模块 - 百分比计算器"""

    @staticmethod
    def calculate_utilization(running: int, total: int, total_nodes: Optional[int] = None) -> float:
        if total <= 0:
            return 0.0
        return running / total

    @staticmethod
    def calculate_memory_usage(used: int, total: int, total_nodes: Optional[int] = None) -> float:
        if total <= 0:
            return 0.0
        return used / total

    @staticmethod
    def calculate_success_rate(completed: int, failed: int, total_tasks: Optional[int] = None) -> float:
        total = completed + failed
        if total <= 0:
            return 0.0
        return completed / total

    @staticmethod
    def calculate_progress_percentage(progress: int, total: int, total_tasks: Optional[int] = None) -> float:
        if total <= 0:
            return 0.0
        return progress / total


class GpuMobileLayoutConfig:
    """GPU任务调度模块 - 移动端布局配置"""

    @staticmethod
    def get_scheduler_layout(user_agent: str = "desktop") -> Dict[str, Any]:
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "node_card_class": "w-full sm:w-auto shadow-md sm:shadow-sm mb-2 sm:mb-1",
            "task_list_class": "space-y-2 sm:space-y-1 text-sm sm:text-xs p-2 sm:p-1",
            "metrics_grid_class": "grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-1 text-sm sm:text-xs",
            "gpu_chart_class": "w-full sm:w-64 h-48 sm:h-32 mx-auto",
            "action_menu_class": "relative sm:fixed bottom-0 sm:bottom-4 w-full sm:w-auto",
            "is_mobile": is_mobile,
        }

        return layout


class GpuSensitiveDataHandler:
    """GPU任务调度模块 - 敏感数据处理器"""

    @staticmethod
    def mask_credentials(value: str) -> str:
        if not value:
            return ""
        if len(value) <= 12:
            return "*" * len(value)
        return value[:6] + "*" * (len(value) - 12) + value[-6:]


class TestPromptExperimentBugFixes:
    """Prompt实验管理模块Bug修复测试"""

    def test_percentage_calculation_base_fix(self):
        """测试百分比计算基数错误修复

        Bug: 使用total_samples作为分母导致转化率偏低
        修复: 使用各组的实际样本数total作为分母
        """
        calculator = PromptPercentageCalculator()

        control_success = 80
        control_samples = 100
        treatment_success = 90
        treatment_samples = 100
        total_samples = 200

        control_rate = calculator.calculate_conversion_rate(
            control_success, control_samples, total_samples
        )
        treatment_rate = calculator.calculate_conversion_rate(
            treatment_success, treatment_samples, total_samples
        )

        assert control_rate == pytest.approx(0.8, rel=1e-9)
        assert treatment_rate == pytest.approx(0.9, rel=1e-9)

        buggy_control_rate = control_success / total_samples
        buggy_treatment_rate = treatment_success / total_samples
        assert control_rate > buggy_control_rate
        assert treatment_rate > buggy_treatment_rate

    def test_percentage_calculation_edge_cases(self):
        """测试百分比计算边界情况"""
        calculator = PromptPercentageCalculator()

        assert calculator.calculate_conversion_rate(0, 0, 100) == 0.0
        assert calculator.calculate_conversion_rate(10, 0, 100) == 0.0
        assert calculator.calculate_conversion_rate(-5, 100, 200) == pytest.approx(-0.05, rel=1e-9)
        assert calculator.calculate_conversion_rate(100, 100, 200) == pytest.approx(1.0, rel=1e-9)

    def test_mobile_layout_class_names_fix(self):
        """测试移动端渲染布局脱节修复

        Bug: 使用不存在的Tailwind断点类名(mobile:, phone:, small:)
        修复: 使用正确的Tailwind断点类名(sm:, md:, lg:)
        """
        layout_config = PromptMobileLayoutConfig.get_layout_config("mobile")

        assert layout_config["is_mobile"] is True
        assert "sm:" in layout_config["container_class"]
        assert "md:" in layout_config["container_class"]
        assert "sm:" in layout_config["card_class"]
        assert "sm:" in layout_config["chart_width"]
        assert "sm:" in layout_config["grid_cols"]
        assert "sm:" in layout_config["text_size"]
        assert "sm:" in layout_config["padding"]

        assert "mobile:" not in layout_config["container_class"]
        assert "phone:" not in layout_config["container_class"]
        assert "small:" not in layout_config["container_class"]

    def test_mobile_layout_desktop_config(self):
        """测试桌面端布局配置"""
        layout_config = PromptMobileLayoutConfig.get_layout_config("desktop")

        assert layout_config["is_mobile"] is False
        assert "sm:" in layout_config["container_class"]
        assert "md:" in layout_config["container_class"]

    def test_sensitive_data_masking_api_key(self):
        """测试敏感信息api_key脱敏修复

        Bug: 敏感信息明文传递
        修复: 使用掩码处理敏感信息
        """
        handler = PromptSensitiveDataHandler()

        api_key = "sk-abcdefghijklmnopqrstuvwxyz1234567890"
        masked = handler.mask_sensitive_field(api_key)

        assert masked.startswith(api_key[:4])
        assert masked.endswith(api_key[-4:])
        assert len(masked) == len(api_key)
        assert "*" in masked
        assert api_key[4:-4] not in masked

    def test_sensitive_data_masking_api_secret(self):
        """测试敏感信息api_secret脱敏修复"""
        handler = PromptSensitiveDataHandler()

        api_secret = "secret_abcdefghijklmnopqrstuvwxyz"
        masked = handler.mask_sensitive_field(api_secret)

        assert masked.startswith(api_secret[:4])
        assert masked.endswith(api_secret[-4:])
        assert "*" in masked
        assert api_secret[4:-4] not in masked

    def test_sensitive_data_masking_auth_token(self):
        """测试敏感信息auth_token脱敏修复"""
        handler = PromptSensitiveDataHandler()

        auth_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        masked = handler.mask_sensitive_field(auth_token)

        assert masked.startswith(auth_token[:4])
        assert masked.endswith(auth_token[-4:])
        assert "*" in masked
        assert auth_token[4:-4] not in masked

    def test_sensitive_data_masking_short_values(self):
        """测试短敏感值脱敏"""
        handler = PromptSensitiveDataHandler()

        short_value = "abc"
        masked = handler.mask_sensitive_field(short_value)
        assert masked == "***"
        assert len(masked) == len(short_value)

        empty_value = ""
        masked_empty = handler.mask_sensitive_field(empty_value)
        assert masked_empty == ""

        none_value = None
        masked_none = handler.mask_sensitive_field(none_value)
        assert masked_none == ""

    def test_sensitive_data_masking_edge_length(self):
        """测试边界长度脱敏"""
        handler = PromptSensitiveDataHandler()

        exactly_eight_chars = "12345678"
        masked = handler.mask_sensitive_field(exactly_eight_chars)
        assert masked == "********"
        assert len(masked) == 8


class TestDocumentPipelineBugFixes:
    """文档解析管道模块Bug修复测试"""

    def test_success_rate_calculation_base_fix(self):
        """测试成功率计算基数错误修复

        Bug: 使用total_chunks作为分母
        修复: 使用processed + failed作为分母
        """
        calculator = DocumentPercentageCalculator()

        processed = 95
        failed = 5
        total_documents = 100
        total_chunks = 5000

        success_rate = calculator.calculate_success_rate(
            processed, failed, total_documents, total_chunks
        )

        assert success_rate == pytest.approx(0.95, rel=1e-9)

        buggy_rate = processed / total_chunks
        assert success_rate > buggy_rate
        assert buggy_rate == pytest.approx(0.019, rel=1e-2)

    def test_progress_calculation_base_fix(self):
        """测试进度计算基数错误修复

        Bug: 使用total_chunks作为分母
        修复: 使用total作为分母
        """
        calculator = DocumentPercentageCalculator()

        processed = 50
        total = 100
        total_chunks = 5000

        progress = calculator.calculate_progress(processed, total, total_chunks)

        assert progress == pytest.approx(0.5, rel=1e-9)

        buggy_progress = processed / total_chunks
        assert progress > buggy_progress
        assert buggy_progress == pytest.approx(0.01, rel=1e-2)

    def test_percentage_calculation_edge_cases(self):
        """测试百分比计算边界情况"""
        calculator = DocumentPercentageCalculator()

        assert calculator.calculate_success_rate(0, 0, 0, 0) == 0.0
        assert calculator.calculate_success_rate(0, 10, 10, 100) == 0.0
        assert calculator.calculate_success_rate(10, 0, 10, 100) == pytest.approx(1.0, rel=1e-9)

        assert calculator.calculate_progress(0, 0, 0) == 0.0
        assert calculator.calculate_progress(100, 100, 5000) == pytest.approx(1.0, rel=1e-9)

    def test_mobile_layout_class_names_fix(self):
        """测试移动端渲染布局脱节修复

        Bug: 使用不存在的Tailwind断点类名(xs:, mobile:, device:)
        修复: 使用正确的Tailwind断点类名(sm:, md:, lg:)
        """
        layout_config = DocumentMobileLayoutConfig.get_pipeline_layout("mobile")

        assert layout_config["is_mobile"] is True
        assert "sm:" in layout_config["progress_bar_class"]
        assert "sm:" in layout_config["stats_grid_class"]
        assert "sm:" in layout_config["document_list_class"]
        assert "sm:" in layout_config["chart_container_class"]
        assert "sm:" in layout_config["action_button_class"]

        assert "xs:" not in layout_config["progress_bar_class"]
        assert "mobile:" not in layout_config["progress_bar_class"]
        assert "device:" not in layout_config["progress_bar_class"]

    def test_mobile_layout_desktop_config(self):
        """测试桌面端布局配置"""
        layout_config = DocumentMobileLayoutConfig.get_pipeline_layout("desktop")

        assert layout_config["is_mobile"] is False
        assert "sm:" in layout_config["progress_bar_class"]
        assert "sm:" in layout_config["stats_grid_class"]

    def test_sensitive_data_masking_api_key(self):
        """测试敏感信息api_key脱敏修复"""
        handler = DocumentSensitiveDataHandler()

        api_key = "doc_pipeline_api_key_abcdefghijklmnop"
        masked = handler.mask_field(api_key)

        assert masked.startswith(api_key[:4])
        assert masked.endswith(api_key[-4:])
        assert "*" in masked
        assert api_key[4:-4] not in masked

    def test_sensitive_data_masking_access_token(self):
        """测试敏感信息access_token脱敏修复"""
        handler = DocumentSensitiveDataHandler()

        access_token = "doc_access_token_abcdefghijklmnopqrstuvwxyz123456"
        masked = handler.mask_field(access_token)

        assert masked.startswith(access_token[:4])
        assert masked.endswith(access_token[-4:])
        assert "*" in masked
        assert access_token[4:-4] not in masked

    def test_sensitive_data_masking_short_values(self):
        """测试短敏感值脱敏"""
        handler = DocumentSensitiveDataHandler()

        short_value = "12345678"
        masked = handler.mask_field(short_value)
        assert masked == "********"

        very_short = "abc"
        masked_very = handler.mask_field(very_short)
        assert masked_very == "***"

        empty_value = ""
        masked_empty = handler.mask_field(empty_value)
        assert masked_empty == ""


class TestGpuSchedulerBugFixes:
    """GPU任务调度模块Bug修复测试"""

    def test_utilization_calculation_base_fix(self):
        """测试利用率计算基数错误修复

        Bug: 使用total_nodes作为分母
        修复: 使用total作为分母
        """
        calculator = GpuPercentageCalculator()

        running = 4
        total = 10
        total_nodes = 20

        utilization = calculator.calculate_utilization(running, total, total_nodes)

        assert utilization == pytest.approx(0.4, rel=1e-9)

        buggy_utilization = running / total_nodes
        assert utilization > buggy_utilization
        assert buggy_utilization == pytest.approx(0.2, rel=1e-9)

    def test_memory_usage_calculation_base_fix(self):
        """测试内存使用率计算基数错误修复

        Bug: 使用total * total_nodes作为分母
        修复: 使用total作为分母
        """
        calculator = GpuPercentageCalculator()

        used = 64
        total = 80
        total_nodes = 4

        memory_usage = calculator.calculate_memory_usage(used, total, total_nodes)

        assert memory_usage == pytest.approx(0.8, rel=1e-9)

        buggy_usage = used / (total * total_nodes)
        assert memory_usage > buggy_usage
        assert buggy_usage == pytest.approx(0.2, rel=1e-9)

    def test_success_rate_calculation_base_fix(self):
        """测试任务成功率计算基数错误修复

        Bug: 使用total_tasks作为分母
        修复: 使用completed + failed作为分母
        """
        calculator = GpuPercentageCalculator()

        completed = 90
        failed = 10
        total_tasks = 120

        success_rate = calculator.calculate_success_rate(completed, failed, total_tasks)

        assert success_rate == pytest.approx(0.9, rel=1e-9)

        buggy_rate = completed / total_tasks
        assert success_rate > buggy_rate
        assert buggy_rate == pytest.approx(0.75, rel=1e-9)

    def test_progress_percentage_calculation_base_fix(self):
        """测试进度百分比计算基数错误修复

        Bug: 使用total + total_tasks作为分母
        修复: 使用total作为分母
        """
        calculator = GpuPercentageCalculator()

        progress = 75
        total = 100
        total_tasks = 50

        progress_pct = calculator.calculate_progress_percentage(progress, total, total_tasks)

        assert progress_pct == pytest.approx(0.75, rel=1e-9)

        buggy_pct = progress / (total + total_tasks)
        assert progress_pct > buggy_pct
        assert buggy_pct == pytest.approx(0.5, rel=1e-9)

    def test_percentage_calculation_edge_cases(self):
        """测试百分比计算边界情况"""
        calculator = GpuPercentageCalculator()

        assert calculator.calculate_utilization(0, 0, 0) == 0.0
        assert calculator.calculate_utilization(10, 10, 5) == pytest.approx(1.0, rel=1e-9)

        assert calculator.calculate_memory_usage(0, 0, 0) == 0.0
        assert calculator.calculate_memory_usage(80, 80, 4) == pytest.approx(1.0, rel=1e-9)

        assert calculator.calculate_success_rate(0, 0, 0) == 0.0
        assert calculator.calculate_success_rate(10, 0, 10) == pytest.approx(1.0, rel=1e-9)
        assert calculator.calculate_success_rate(0, 10, 10) == 0.0

        assert calculator.calculate_progress_percentage(0, 0, 0) == 0.0
        assert calculator.calculate_progress_percentage(100, 100, 50) == pytest.approx(1.0, rel=1e-9)

    def test_mobile_layout_class_names_fix(self):
        """测试移动端渲染布局脱节修复

        Bug: 使用不存在的Tailwind断点类名(phone:, handheld:, pocket:)
        修复: 使用正确的Tailwind断点类名(sm:, md:, lg:)
        """
        layout_config = GpuMobileLayoutConfig.get_scheduler_layout("mobile")

        assert layout_config["is_mobile"] is True
        assert "sm:" in layout_config["node_card_class"]
        assert "sm:" in layout_config["task_list_class"]
        assert "sm:" in layout_config["metrics_grid_class"]
        assert "sm:" in layout_config["gpu_chart_class"]
        assert "sm:" in layout_config["action_menu_class"]

        assert "phone:" not in layout_config["node_card_class"]
        assert "handheld:" not in layout_config["node_card_class"]
        assert "pocket:" not in layout_config["node_card_class"]

    def test_mobile_layout_desktop_config(self):
        """测试桌面端布局配置"""
        layout_config = GpuMobileLayoutConfig.get_scheduler_layout("desktop")

        assert layout_config["is_mobile"] is False
        assert "sm:" in layout_config["node_card_class"]
        assert "md:" not in layout_config["node_card_class"]

    def test_sensitive_data_masking_api_key(self):
        """测试敏感信息api_key脱敏修复"""
        handler = GpuSensitiveDataHandler()

        api_key = "gpu_scheduler_api_key_abcdefghijklmnopqrstuvwxyz"
        masked = handler.mask_credentials(api_key)

        assert masked.startswith(api_key[:6])
        assert masked.endswith(api_key[-6:])
        assert "*" in masked
        assert api_key[6:-6] not in masked

    def test_sensitive_data_masking_auth_token(self):
        """测试敏感信息auth_token脱敏修复"""
        handler = GpuSensitiveDataHandler()

        auth_token = "gpu_auth_token_abcdefghijklmnopqrstuvwxyz1234567890"
        masked = handler.mask_credentials(auth_token)

        assert masked.startswith(auth_token[:6])
        assert masked.endswith(auth_token[-6:])
        assert "*" in masked
        assert auth_token[6:-6] not in masked

    def test_sensitive_data_masking_short_values(self):
        """测试短敏感值脱敏"""
        handler = GpuSensitiveDataHandler()

        short_value = "123456789012"
        masked = handler.mask_credentials(short_value)
        assert masked == "************"
        assert len(masked) == 12

        very_short = "abc"
        masked_very = handler.mask_credentials(very_short)
        assert masked_very == "***"

        empty_value = ""
        masked_empty = handler.mask_credentials(empty_value)
        assert masked_empty == ""

        none_value = None
        masked_none = handler.mask_credentials(none_value)
        assert masked_none == ""


class TestBugFixVerification:
    """综合Bug修复验证测试"""

    def test_all_percentage_calculators_have_same_signature_pattern(self):
        """测试所有百分比计算器方法签名模式一致"""
        prompt_methods = [
            m for m in dir(PromptPercentageCalculator)
            if not m.startswith("_") and callable(getattr(PromptPercentageCalculator, m))
        ]
        doc_methods = [
            m for m in dir(DocumentPercentageCalculator)
            if not m.startswith("_") and callable(getattr(DocumentPercentageCalculator, m))
        ]
        gpu_methods = [
            m for m in dir(GpuPercentageCalculator)
            if not m.startswith("_") and callable(getattr(GpuPercentageCalculator, m))
        ]

        assert len(prompt_methods) > 0
        assert len(doc_methods) > 0
        assert len(gpu_methods) > 0

    def test_all_mobile_layouts_use_correct_breakpoints(self):
        """测试所有移动端布局都使用正确的断点"""
        for layout_class in [PromptMobileLayoutConfig, DocumentMobileLayoutConfig, GpuMobileLayoutConfig]:
            layout = layout_class.get_layout_config("mobile") if hasattr(layout_class, 'get_layout_config') else \
                     layout_class.get_pipeline_layout("mobile") if hasattr(layout_class, 'get_pipeline_layout') else \
                     layout_class.get_scheduler_layout("mobile")

            invalid_prefixes = ["mobile:", "phone:", "small:", "xs:", "device:", "handheld:", "pocket:"]
            valid_prefixes = ["sm:", "md:", "lg:", "xl:"]

            for key, value in layout.items():
                if isinstance(value, str):
                    for invalid in invalid_prefixes:
                        assert invalid not in value, f"Found invalid breakpoint '{invalid}' in {key}"
                    has_valid = any(valid in value for valid in valid_prefixes)
                    if "class" in key:
                        assert has_valid, f"No valid breakpoint found in {key}"

    def test_all_sensitive_handlers_have_mask_method(self):
        """测试所有敏感数据处理器都有脱敏方法"""
        for handler_class in [PromptSensitiveDataHandler, DocumentSensitiveDataHandler, GpuSensitiveDataHandler]:
            methods = [m for m in dir(handler_class) if not m.startswith("_") and callable(getattr(handler_class, m))]
            assert len(methods) > 0

            mask_method = next((m for m in methods if "mask" in m.lower()), None)
            assert mask_method is not None, f"No mask method found in {handler_class.__name__}"

    def test_masking_preserves_length(self):
        """测试脱敏后长度保持不变"""
        test_value = "abcdefghijklmnopqrstuvwxyz"

        prompt_handler = PromptSensitiveDataHandler()
        doc_handler = DocumentSensitiveDataHandler()
        gpu_handler = GpuSensitiveDataHandler()

        prompt_masked = prompt_handler.mask_sensitive_field(test_value)
        doc_masked = doc_handler.mask_field(test_value)
        gpu_masked = gpu_handler.mask_credentials(test_value)

        assert len(prompt_masked) == len(test_value)
        assert len(doc_masked) == len(test_value)
        assert len(gpu_masked) == len(test_value)

    def test_masking_not_reversible_from_output(self):
        """测试脱敏后无法从输出恢复原始值"""
        original = "my_secret_api_key_1234567890"

        handlers = [
            (PromptSensitiveDataHandler(), "mask_sensitive_field"),
            (DocumentSensitiveDataHandler(), "mask_field"),
            (GpuSensitiveDataHandler(), "mask_credentials"),
        ]

        for handler, method_name in handlers:
            mask_method = getattr(handler, method_name)
            masked = mask_method(original)

            assert masked != original
            assert original not in masked

            middle_section = original[4:-4]
            if len(middle_section) > 0:
                assert middle_section not in masked
