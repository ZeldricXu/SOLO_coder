"""
单元测试: 代码质量门禁模块
"""

import pytest
import tempfile
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.modules.code_quality import (
    CodeQualityGate,
    RuleSet,
    PythonAnalyzer,
    QualityRule,
)


@pytest.fixture
def rule_set():
    return RuleSet()


@pytest.fixture
def quality_gate():
    return CodeQualityGate(threshold=80)


@pytest.fixture
def temp_project():
    with tempfile.TemporaryDirectory() as tmpdir:
        code = '''
password = "hardcoded_secret_12345"

def test_function():
    print("Debug output")
    # TODO: fix this
    return True
'''
        with open(os.path.join(tmpdir, "test_file.py"), "w") as f:
            f.write(code)
        yield tmpdir


def test_rule_set_default_rules(rule_set):
    """测试默认规则"""
    rules = rule_set.get_all_rules()
    assert len(rules) >= 8

    python_rules = rule_set.get_rules_for_language("python")
    assert len(python_rules) >= 4


def test_rule_set_add_rule(rule_set):
    """测试添加自定义规则"""
    rule = QualityRule(
        id="TEST001",
        name="Test Rule",
        description="Test rule",
        severity="major",
        language="python",
        pattern=r"test_pattern",
    )
    rule_set.add_rule(rule)

    assert rule_set.get_rule("TEST001") is not None
    assert rule_set.get_rule("TEST001").name == "Test Rule"


def test_rule_set_disable_rule(rule_set):
    """测试禁用规则"""
    rule_set.disable_rule("PY001")
    rule = rule_set.get_rule("PY001")
    assert rule.enabled is False

    rule_set.enable_rule("PY001")
    rule = rule_set.get_rule("PY001")
    assert rule.enabled is True


def test_python_analyzer(rule_set, temp_project):
    """测试Python分析器"""
    analyzer = PythonAnalyzer(rule_set)
    issues = analyzer.analyze(
        os.path.join(temp_project, "test_file.py"),
        [],
    )

    issue_rule_ids = [i.rule_id for i in issues]
    assert "PY001" in issue_rule_ids
    assert "PY003" in issue_rule_ids
    assert "PY004" in issue_rule_ids


@pytest.mark.asyncio
async def test_quality_gate_check_project(quality_gate, temp_project):
    """测试质量门禁检查"""
    report = await quality_gate.check_project(temp_project)

    assert report.total_files == 1
    assert len(report.issues) >= 3
    assert report.score < 100
    assert report.passed is True or report.passed is False


@pytest.mark.asyncio
async def test_report_generation(quality_gate, temp_project):
    """测试报告生成"""
    report = await quality_gate.check_project(temp_project)

    text_report = quality_gate.generate_report(report, format="text")
    assert "Code Quality Report" in text_report
    assert str(report.score) in text_report

    json_report = quality_gate.generate_report(report, format="json")
    import json
    json_data = json.loads(json_report)
    assert json_data["score"] == report.score
    assert len(json_data["issues"]) == len(report.issues)

    html_report = quality_gate.generate_report(report, format="html")
    assert "<html>" in html_report
    assert str(report.score) in html_report
