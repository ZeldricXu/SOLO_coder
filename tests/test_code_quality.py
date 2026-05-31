import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.code_quality import get_code_quality_service


def test_analyze_python_code():
    service = get_code_quality_service()
    code = '''
print("Hello World")
password = "secret123"
import pdb
'''
    report = service.analyze_code(code, "python", "test.py")
    assert report is not None
    assert report.language == "python"
    assert report.total_issues > 0
    assert "critical" in report.issues_by_severity


def test_quality_score():
    service = get_code_quality_service()
    code = '''
def hello():
    return "world"
'''
    report = service.analyze_code(code, "python")
    assert report.quality_score >= 0
    assert report.quality_score <= 100


def test_get_rules():
    service = get_code_quality_service()
    rules = service.get_rules("python")
    assert len(rules) > 0
    assert all("rule_id" in r for r in rules)


def test_threshold_pass():
    service = get_code_quality_service()
    code = '''
def clean():
    x = 1
    return x
'''
    report = service.analyze_code(code, "python")
    assert isinstance(report.threshold_pass, bool)
