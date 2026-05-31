"""
代码质量门禁核心 - 整合analyzer/rule/report，聚焦并发隔离
只依赖 domain.contracts 中的抽象

可插拔接口运行时切换特性：
- Analyzer插件注册/注销
- RuleSet运行时替换
- ReportGenerator可插拔
- ConcurrencyAnalyzer可替换
- 配置快照 + 回滚功能
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Protocol

from src.domain.contracts.tracing import LoggerProtocol
from src.domain.contracts.quality import CodeAnalyzerProtocol, IsolationLevel
from src.domain.errors.quality import QualityCheckError
from src.domain.models.quality import QualityReport
from src.modules.quality.rule import RuleSet
from src.modules.quality.analyzer import (
    PythonAnalyzer,
    JavaScriptAnalyzer,
    JavaAnalyzer,
    ConcurrencyAnalyzer,
    AnalyzerDispatcher,
)
from src.modules.quality.report import ReportGenerator


@dataclass
class QualityConfigSnapshot:
    """配置快照 - 用于回滚"""
    rule_set: RuleSet
    dispatcher: AnalyzerDispatcher
    concurrency_analyzer: ConcurrencyAnalyzer
    report_generator: ReportGenerator
    threshold: int
    isolation_level: IsolationLevel


class QualityPlugin(Protocol):
    """质量插件协议"""
    @property
    def name(self) -> str: ...
    def activate(self) -> None: ...
    def deactivate(self) -> None: ...


class PluginManager:
    """插件管理器 - 运行时管理analyzer/rule/report插件"""

    def __init__(self) -> None:
        self._analyzers: Dict[str, CodeAnalyzerProtocol] = {}
        self._rule_sets: Dict[str, RuleSet] = {}
        self._report_generators: Dict[str, ReportGenerator] = {}
        self._active_analyzer: Optional[str] = None
        self._active_rule_set: Optional[str] = None
        self._active_report_generator: Optional[str] = None

    def register_analyzer(self, name: str, analyzer: CodeAnalyzerProtocol) -> None:
        """注册Analyzer插件"""
        self._analyzers[name] = analyzer

    def unregister_analyzer(self, name: str) -> Optional[CodeAnalyzerProtocol]:
        """注销Analyzer插件"""
        if self._active_analyzer == name:
            self._active_analyzer = None
        return self._analyzers.pop(name, None)

    def switch_analyzer(self, name: str) -> None:
        """运行时切换Analyzer"""
        if name not in self._analyzers:
            raise ValueError(f"Analyzer '{name}' not registered")
        self._active_analyzer = name

    def get_active_analyzer(self) -> Optional[CodeAnalyzerProtocol]:
        if self._active_analyzer:
            return self._analyzers.get(self._active_analyzer)
        return None

    def register_rule_set(self, name: str, rule_set: RuleSet) -> None:
        """注册RuleSet插件"""
        self._rule_sets[name] = rule_set

    def unregister_rule_set(self, name: str) -> Optional[RuleSet]:
        """注销RuleSet插件"""
        if self._active_rule_set == name:
            self._active_rule_set = None
        return self._rule_sets.pop(name, None)

    def switch_rule_set(self, name: str) -> None:
        """运行时切换RuleSet"""
        if name not in self._rule_sets:
            raise ValueError(f"RuleSet '{name}' not registered")
        self._active_rule_set = name

    def get_active_rule_set(self) -> Optional[RuleSet]:
        if self._active_rule_set:
            return self._rule_sets.get(self._active_rule_set)
        return None

    def register_report_generator(self, name: str, generator: ReportGenerator) -> None:
        """注册ReportGenerator插件"""
        self._report_generators[name] = generator

    def unregister_report_generator(self, name: str) -> Optional[ReportGenerator]:
        """注销ReportGenerator插件"""
        if self._active_report_generator == name:
            self._active_report_generator = None
        return self._report_generators.pop(name, None)

    def switch_report_generator(self, name: str) -> None:
        """运行时切换ReportGenerator"""
        if name not in self._report_generators:
            raise ValueError(f"ReportGenerator '{name}' not registered")
        self._active_report_generator = name

    def get_active_report_generator(self) -> Optional[ReportGenerator]:
        if self._active_report_generator:
            return self._report_generators.get(self._active_report_generator)
        return None

    def list_analyzers(self) -> List[str]:
        return list(self._analyzers.keys())

    def list_rule_sets(self) -> List[str]:
        return list(self._rule_sets.keys())

    def list_report_generators(self) -> List[str]:
        return list(self._report_generators.keys())


class CodeQualityGate:
    """
    代码质量门禁 - 依赖注入所有子组件
    整合静态分析、并发安全分析、报告生成

    可插拔接口运行时切换特性：
    - PluginManager管理所有插件
    - Analyzer运行时注册/切换
    - RuleSet运行时替换
    - ReportGenerator可插拔
    - ConcurrencyAnalyzer可替换
    - 配置快照 + 回滚功能
    """

    def __init__(
        self,
        rule_set: Optional[RuleSet] = None,
        dispatcher: Optional[AnalyzerDispatcher] = None,
        concurrency_analyzer: Optional[ConcurrencyAnalyzer] = None,
        report_generator: Optional[ReportGenerator] = None,
        logger: Optional[LoggerProtocol] = None,
        threshold: int = 80,
        isolation_level: IsolationLevel = IsolationLevel.MODULE,
    ) -> None:
        self._logger = logger
        self._threshold = threshold
        self._isolation_level = isolation_level

        self._plugin_manager = PluginManager()

        self._default_rule_set = rule_set or RuleSet()
        self._default_dispatcher = dispatcher or AnalyzerDispatcher()
        self._default_concurrency_analyzer = concurrency_analyzer or ConcurrencyAnalyzer(isolation_level)
        self._default_report_generator = report_generator or ReportGenerator(logger)

        self._init_default_analyzers()
        self._init_default_plugins()

        self._snapshots: List[QualityConfigSnapshot] = []

    def _init_default_analyzers(self) -> None:
        self._default_dispatcher.register_analyzer("python", PythonAnalyzer(self._default_rule_set))
        self._default_dispatcher.register_analyzer("javascript", JavaScriptAnalyzer(self._default_rule_set))
        self._default_dispatcher.register_analyzer("java", JavaAnalyzer(self._default_rule_set))

    def _init_default_plugins(self) -> None:
        self._plugin_manager.register_rule_set("default", self._default_rule_set)
        self._plugin_manager.switch_rule_set("default")

        self._plugin_manager.register_report_generator("default", self._default_report_generator)
        self._plugin_manager.switch_report_generator("default")

    @property
    def plugin_manager(self) -> PluginManager:
        """获取插件管理器"""
        return self._plugin_manager

    def _get_current_rule_set(self) -> RuleSet:
        return self._plugin_manager.get_active_rule_set() or self._default_rule_set

    def _get_current_report_generator(self) -> ReportGenerator:
        return self._plugin_manager.get_active_report_generator() or self._default_report_generator

    def _collect_files(self, path: str) -> List[str]:
        files = []
        if os.path.isfile(path):
            return [path]
        for root, _, filenames in os.walk(path):
            for filename in filenames:
                ext = os.path.splitext(filename)[1].lower()
                if ext in self._default_dispatcher._extensions:
                    files.append(os.path.join(root, filename))
        return files

    def save_snapshot(self) -> int:
        """保存配置快照，用于回滚"""
        import copy
        snapshot = QualityConfigSnapshot(
            rule_set=copy.deepcopy(self._get_current_rule_set()),
            dispatcher=self._default_dispatcher,
            concurrency_analyzer=self._default_concurrency_analyzer,
            report_generator=self._get_current_report_generator(),
            threshold=self._threshold,
            isolation_level=self._isolation_level,
        )
        self._snapshots.append(snapshot)
        if self._logger:
            self._logger.info("Quality config snapshot saved", snapshot_index=len(self._snapshots) - 1)
        return len(self._snapshots) - 1

    def rollback(self, snapshot_index: Optional[int] = None) -> bool:
        """回滚到指定快照"""
        if not self._snapshots:
            return False

        idx = snapshot_index if snapshot_index is not None else len(self._snapshots) - 1
        if idx < 0 or idx >= len(self._snapshots):
            return False

        snapshot = self._snapshots[idx]
        self._threshold = snapshot.threshold
        self._isolation_level = snapshot.isolation_level

        self._plugin_manager.register_rule_set(f"rolled_back_{idx}", snapshot.rule_set)
        self._plugin_manager.switch_rule_set(f"rolled_back_{idx}")

        if self._logger:
            self._logger.info("Quality config rolled back", snapshot_index=idx)
        return True

    def register_analyzer(self, name: str, analyzer: CodeAnalyzerProtocol) -> None:
        """运行时注册Analyzer"""
        self._plugin_manager.register_analyzer(name, analyzer)
        if self._logger:
            self._logger.info("Analyzer registered", name=name)

    def unregister_analyzer(self, name: str) -> Optional[CodeAnalyzerProtocol]:
        """运行时注销Analyzer"""
        result = self._plugin_manager.unregister_analyzer(name)
        if self._logger:
            self._logger.info("Analyzer unregistered", name=name)
        return result

    def switch_analyzer(self, name: str) -> None:
        """运行时切换Analyzer"""
        self._plugin_manager.switch_analyzer(name)
        if self._logger:
            self._logger.info("Analyzer switched", name=name)

    def register_rule_set(self, name: str, rule_set: RuleSet) -> None:
        """运行时注册RuleSet"""
        self._plugin_manager.register_rule_set(name, rule_set)
        if self._logger:
            self._logger.info("RuleSet registered", name=name)

    def switch_rule_set(self, name: str) -> None:
        """运行时切换RuleSet"""
        self._plugin_manager.switch_rule_set(name)
        if self._logger:
            self._logger.info("RuleSet switched", name=name)

    def register_report_generator(self, name: str, generator: ReportGenerator) -> None:
        """运行时注册ReportGenerator"""
        self._plugin_manager.register_report_generator(name, generator)
        if self._logger:
            self._logger.info("ReportGenerator registered", name=name)

    def switch_report_generator(self, name: str) -> None:
        """运行时切换ReportGenerator"""
        self._plugin_manager.switch_report_generator(name)
        if self._logger:
            self._logger.info("ReportGenerator switched", name=name)

    def switch_concurrency_analyzer(self, analyzer: ConcurrencyAnalyzer) -> None:
        """运行时替换ConcurrencyAnalyzer"""
        self._default_concurrency_analyzer = analyzer
        if self._logger:
            self._logger.info("ConcurrencyAnalyzer switched")

    def update_threshold(self, threshold: int) -> None:
        """运行时更新质量阈值"""
        self._threshold = threshold
        if self._logger:
            self._logger.info("Quality threshold updated", threshold=threshold)

    def update_isolation_level(self, level: IsolationLevel) -> None:
        """运行时更新并发隔离级别"""
        self._isolation_level = level
        self._default_concurrency_analyzer = ConcurrencyAnalyzer(level)
        if self._logger:
            self._logger.info("Isolation level updated", level=level.value)

    async def check_project(
        self,
        project_path: str,
        project_name: Optional[str] = None,
        rules: Optional[List[str]] = None,
        check_concurrency: bool = True,
    ) -> QualityReport:
        name = project_name or os.path.basename(os.path.abspath(project_path))
        report = QualityReport(project_name=name)

        try:
            files = self._collect_files(project_path)
            report.total_files = len(files)

            if self._logger:
                self._logger.info(
                    "Starting quality check",
                    project=name,
                    files_count=len(files),
                    isolation_level=self._isolation_level.value,
                    threshold=self._threshold,
                )

            rule_set = self._get_current_rule_set()

            for file_path in files:
                try:
                    issues = self._default_dispatcher.analyze_file(file_path, rules)
                    for issue in issues:
                        report.add_issue(issue)

                    if check_concurrency:
                        language = self._default_dispatcher.get_language_for_file(file_path) or "python"
                        conc_issues = self._default_concurrency_analyzer.analyze_file(file_path, language)
                        for ci in conc_issues:
                            report.add_concurrency_issue(ci)

                except Exception as e:
                    if self._logger:
                        self._logger.warning(
                            "Failed to analyze file", file=file_path, error=str(e)
                        )

            report.passed = report.score >= self._threshold

            if check_concurrency and report.concurrency_issues:
                actual_isolation = self._default_concurrency_analyzer.evaluate_isolation(
                    report.concurrency_issues
                )
                report._concurrency_isolation = actual_isolation.value

            if self._logger:
                self._logger.info(
                    "Quality check completed",
                    project=name,
                    score=report.score,
                    passed=report.passed,
                    issues_count=len(report.issues),
                    concurrency_issues=len(report.concurrency_issues),
                )

        except Exception as e:
            raise QualityCheckError(f"Quality check failed: {e}") from e

        return report

    def generate_report(self, report: QualityReport, format: str = "text") -> str:
        generator = self._get_current_report_generator()
        if format == "json":
            return generator.generate_json_report(report)
        elif format == "html":
            return generator.generate_html_report(report)
        return generator.generate_text_report(report)

    def get_rule_set(self) -> RuleSet:
        return self._get_current_rule_set()
