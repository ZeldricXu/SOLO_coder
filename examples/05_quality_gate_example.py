"""
示例5: 代码质量门禁模块
"""

import asyncio
import sys
import os
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import LogLevel
from src.infrastructure.logging import StructuredLogger, ConsoleHandler, TextFormatter
from src.modules.code_quality import CodeQualityGate, QualityRule


async def main():
    print("=== 代码质量门禁示例 ===\n")

    logger = StructuredLogger(
        service_name="quality-gate",
        handlers=[ConsoleHandler(level=LogLevel.INFO, formatter=TextFormatter())],
    )

    gate = CodeQualityGate(logger=logger, threshold=80)

    rule_set = gate.get_rule_set()
    print("1. 可用规则:")
    for rule in rule_set.get_all_rules():
        print(f"   [{rule.id}] {rule.name} ({rule.severity}) - {rule.language}")

    rule_set.add_rule(QualityRule(
        id="CUSTOM001",
        name="No debug statements",
        description="Avoid using debug() in production code",
        severity="major",
        language="python",
        pattern=r"\.debug\s*\(",
    ))
    print("\n   已添加自定义规则: CUSTOM001")

    print("\n2. 创建测试项目进行检查:")
    with tempfile.TemporaryDirectory() as tmpdir:
        bad_code = '''
import os

password = "my_secret_password_123"
db_host = "192.168.1.100"

# TODO: 修复这个bug
def process_data(data):
    print("Processing data:", data)
    logger.debug("Debug info: %s", data)
    return data * 2
'''

        good_code = '''
import os

def process_data(data):
    return data * 2
'''

        with open(os.path.join(tmpdir, "bad_code.py"), "w") as f:
            f.write(bad_code)

        with open(os.path.join(tmpdir, "good_code.py"), "w") as f:
            f.write(good_code)

        print(f"   测试目录: {tmpdir}")

        report = await gate.check_project(tmpdir, project_name="test-project")

        print(f"\n3. 质量检查结果:")
        print(f"   总分: {report.score}/100")
        print(f"   通过: {'是' if report.passed else '否'}")
        print(f"   检查文件数: {report.total_files}")
        print(f"   发现问题数: {len(report.issues)}")

        if report.issues:
            print("\n4. 问题详情:")
            for issue in report.issues:
                print(f"   [{issue.severity.upper()}] {issue.rule_id}")
                print(f"     文件: {os.path.basename(issue.file)}:{issue.line}")
                print(f"     描述: {issue.message}")

        print("\n5. 生成的报告 (JSON):")
        json_report = gate.generate_report(report, format="json")
        print(json_report[:500] + "..." if len(json_report) > 500 else json_report)

        html_report = gate.generate_report(report, format="html")
        report_path = os.path.join(tmpdir, "quality_report.html")
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(html_report)
        print(f"\n6. HTML报告已生成: {report_path}")


if __name__ == "__main__":
    asyncio.run(main())
