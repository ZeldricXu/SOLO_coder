"""
单元测试: 代码质量门禁 - 可插拔接口运行时切换
"""

import pytest
import tempfile
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.modules.quality import CodeQualityGate, PluginManager, QualityConfigSnapshot
from src.modules.quality.rule import RuleSet
from src.modules.quality.analyzer import PythonAnalyzer, ConcurrencyAnalyzer
from src.domain.contracts.quality import IsolationLevel


@pytest.fixture
def quality_gate():
    return CodeQualityGate(threshold=80, isolation_level=IsolationLevel.MODULE)


@pytest.fixture
def temp_project():
    with tempfile.TemporaryDirectory() as tmpdir:
        code = '''
password = "hardcoded_secret_12345"

shared_list = []

def test_function():
    global shared_list
    print("Debug output")
    return True
'''
        with open(os.path.join(tmpdir, "test_file.py"), "w") as f:
            f.write(code)
        yield tmpdir


class TestPluginManager:
    def test_register_and_list_rule_set(self):
        """测试注册和列出RuleSet"""
        pm = PluginManager()
        rule_set = RuleSet()
        pm.register_rule_set("strict", rule_set)
        assert "strict" in pm.list_rule_sets()

    def test_switch_rule_set(self):
        """测试运行时切换RuleSet"""
        pm = PluginManager()
        rs1 = RuleSet()
        rs2 = RuleSet()
        pm.register_rule_set("default", rs1)
        pm.register_rule_set("strict", rs2)
        pm.switch_rule_set("strict")
        assert pm.get_active_rule_set() is rs2

    def test_switch_nonexistent_rule_set_raises(self):
        """测试切换不存在的RuleSet抛出异常"""
        pm = PluginManager()
        with pytest.raises(ValueError):
            pm.switch_rule_set("nonexistent")

    def test_unregister_rule_set(self):
        """测试注销RuleSet"""
        pm = PluginManager()
        rs = RuleSet()
        pm.register_rule_set("test", rs)
        pm.switch_rule_set("test")
        pm.unregister_rule_set("test")
        assert "test" not in pm.list_rule_sets()
        assert pm.get_active_rule_set() is None

    def test_register_report_generator(self):
        """测试注册ReportGenerator"""
        from src.modules.quality.report import ReportGenerator
        pm = PluginManager()
        rg = ReportGenerator()
        pm.register_report_generator("custom", rg)
        assert "custom" in pm.list_report_generators()

    def test_switch_report_generator(self):
        """测试切换ReportGenerator"""
        from src.modules.quality.report import ReportGenerator
        pm = PluginManager()
        rg1 = ReportGenerator()
        pm.register_report_generator("custom", rg1)
        pm.switch_report_generator("custom")
        assert pm.get_active_report_generator() is rg1


class TestQualityGatePlugins:
    def test_plugin_manager_property(self, quality_gate):
        """测试获取插件管理器"""
        assert quality_gate.plugin_manager is not None
        assert isinstance(quality_gate.plugin_manager, PluginManager)

    def test_register_rule_set_via_gate(self, quality_gate):
        """通过Gate注册RuleSet"""
        new_rs = RuleSet()
        quality_gate.register_rule_set("custom", new_rs)
        assert "custom" in quality_gate.plugin_manager.list_rule_sets()

    def test_switch_rule_set_via_gate(self, quality_gate):
        """通过Gate切换RuleSet"""
        new_rs = RuleSet()
        quality_gate.register_rule_set("custom", new_rs)
        quality_gate.switch_rule_set("custom")
        assert quality_gate.plugin_manager.get_active_rule_set() is new_rs

    def test_register_report_generator_via_gate(self, quality_gate):
        """通过Gate注册ReportGenerator"""
        from src.modules.quality.report import ReportGenerator
        rg = ReportGenerator()
        quality_gate.register_report_generator("custom", rg)
        assert "custom" in quality_gate.plugin_manager.list_report_generators()


class TestQualityGateConfigSnapshot:
    def test_save_snapshot(self, quality_gate):
        """测试保存配置快照"""
        idx = quality_gate.save_snapshot()
        assert idx == 0

    def test_save_multiple_snapshots(self, quality_gate):
        """测试保存多个快照"""
        idx1 = quality_gate.save_snapshot()
        idx2 = quality_gate.save_snapshot()
        assert idx1 == 0
        assert idx2 == 1

    def test_rollback_to_snapshot(self, quality_gate):
        """测试回滚到快照"""
        old_threshold = quality_gate._threshold
        quality_gate.update_threshold(50)
        assert quality_gate._threshold == 50

        quality_gate.save_snapshot()
        quality_gate.update_threshold(30)
        assert quality_gate._threshold == 30

        quality_gate.rollback(0)
        assert quality_gate._threshold == 50

    def test_rollback_no_snapshots_returns_false(self, quality_gate):
        """无快照时回滚返回False"""
        assert quality_gate.rollback() is False


class TestQualityGateRuntimeChanges:
    def test_update_threshold(self, quality_gate):
        """测试运行时更新阈值"""
        quality_gate.update_threshold(60)
        assert quality_gate._threshold == 60

    def test_update_isolation_level(self, quality_gate):
        """测试运行时更新隔离级别"""
        quality_gate.update_isolation_level(IsolationLevel.PROJECT)
        assert quality_gate._isolation_level == IsolationLevel.PROJECT

    def test_switch_concurrency_analyzer(self, quality_gate):
        """测试运行时替换并发分析器"""
        new_analyzer = ConcurrencyAnalyzer(IsolationLevel.NONE)
        quality_gate.switch_concurrency_analyzer(new_analyzer)
        assert quality_gate._default_concurrency_analyzer is new_analyzer

    @pytest.mark.asyncio
    async def test_check_project_with_plugin_switch(self, quality_gate, temp_project):
        """插件切换后检查项目"""
        report1 = await quality_gate.check_project(temp_project)
        assert report1.total_files == 1

        new_rs = RuleSet()
        new_rs.disable_rule("PY001")

        quality_gate.register_rule_set("no_print", new_rs)
        quality_gate.switch_rule_set("no_print")

        report2 = await quality_gate.check_project(temp_project)
        assert report2.total_files == 1
