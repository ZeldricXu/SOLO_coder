"""
单元测试: 代码质量门禁模块 - 含并发隔离
"""

import pytest
import tempfile
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.modules.quality import CodeQualityGate
from src.modules.quality.rule import RuleSet
from src.modules.quality.analyzer import PythonAnalyzer, ConcurrencyAnalyzer, AnalyzerDispatcher
from src.domain.contracts.quality import IsolationLevel
from src.domain.models.quality import QualityRule, ConcurrencyIssue


@pytest.fixture
def rule_set():
    return RuleSet()


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
    # TODO: fix this
    shared_list.append(1)
    return True
'''
        with open(os.path.join(tmpdir, "test_file.py"), "w") as f:
            f.write(code)
        yield tmpdir


def test_rule_set_default_rules(rule_set):
    rules = rule_set.get_all_rules()
    assert len(rules) >= 8

    python_rules = rule_set.get_rules_for_language("python")
    assert len(python_rules) >= 4


def test_rule_set_add_rule(rule_set):
    rule = QualityRule(
        id="TEST001", name="Test Rule", description="Test",
        severity="major", language="python", pattern=r"test_pattern",
    )
    rule_set.add_rule(rule)
    assert rule_set.get_rule("TEST001") is not None


def test_rule_set_disable_enable(rule_set):
    rule_set.disable_rule("PY001")
    assert rule_set.get_rule("PY001").enabled is False
    rule_set.enable_rule("PY001")
    assert rule_set.get_rule("PY001").enabled is True


def test_python_analyzer(rule_set, temp_project):
    analyzer = PythonAnalyzer(rule_set)
    issues = analyzer.analyze(os.path.join(temp_project, "test_file.py"), [])
    rule_ids = [i.rule_id for i in issues]
    assert "PY001" in rule_ids
    assert "PY003" in rule_ids
    assert "PY004" in rule_ids


def test_concurrency_analyzer(temp_project):
    analyzer = ConcurrencyAnalyzer(isolation_level=IsolationLevel.MODULE)
    issues = analyzer.analyze_file(os.path.join(temp_project, "test_file.py"))
    assert len(issues) > 0
    issue_types = [i.issue_type for i in issues]
    assert "shared_mutable_state" in issue_types


def test_concurrency_isolation_evaluation():
    analyzer = ConcurrencyAnalyzer()
    issues = [
        ConcurrencyIssue(file="a.py", line=1, issue_type="shared_mutable_state", severity="critical", description="test"),
    ]
    level = analyzer.evaluate_isolation(issues)
    assert level == IsolationLevel.NONE

    issues_minor = [
        ConcurrencyIssue(file="a.py", line=1, issue_type="shared_state_access", severity="major", description="test"),
    ]
    level = analyzer.evaluate_isolation(issues_minor)
    assert level in (IsolationLevel.MODULE, IsolationLevel.FILE)

    level = analyzer.evaluate_isolation([])
    assert level == IsolationLevel.PROJECT


@pytest.mark.asyncio
async def test_quality_gate_check_project(quality_gate, temp_project):
    report = await quality_gate.check_project(temp_project)
    assert report.total_files == 1
    assert len(report.issues) >= 3
    assert report.score < 100


@pytest.mark.asyncio
async def test_quality_gate_with_concurrency(quality_gate, temp_project):
    report = await quality_gate.check_project(temp_project, check_concurrency=True)
    assert len(report.concurrency_issues) > 0


@pytest.mark.asyncio
async def test_report_generation(quality_gate, temp_project):
    report = await quality_gate.check_project(temp_project)
    text = quality_gate.generate_report(report, format="text")
    assert "Code Quality Report" in text

    json_report = quality_gate.generate_report(report, format="json")
    import json
    data = json.loads(json_report)
    assert data["score"] == report.score

    html = quality_gate.generate_report(report, format="html")
    assert "<html>" in html


def test_isolation_level_enum():
    assert IsolationLevel.NONE.value == "none"
    assert IsolationLevel.FILE.value == "file"
    assert IsolationLevel.MODULE.value == "module"
    assert IsolationLevel.PROJECT.value == "project"
