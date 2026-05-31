"""
报告生成组件 - 独立于分析逻辑
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from src.domain.contracts.tracing import LoggerProtocol
from src.domain.models.quality import QualityReport


class ReportGenerator:
    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger

    def generate_text_report(self, report: QualityReport) -> str:
        lines = [
            "=" * 60,
            f"Code Quality Report: {report.project_name}",
            "=" * 60,
            f"Generated at: {report.generated_at}",
            f"Total files analyzed: {report.total_files}",
            f"Quality Score: {report.score}/100",
            f"Status: {'PASSED' if report.passed else 'FAILED'}",
            "",
            f"Static Issues: {len(report.issues)}",
            f"Concurrency Issues: {len(report.concurrency_issues)}",
            "-" * 60,
        ]

        if report.concurrency_issues:
            lines.append("\nCONCURRENCY ISSUES:")
            for issue in report.concurrency_issues:
                lines.append(
                    f"  [{issue.severity.upper()}] {issue.file}:{issue.line}"
                    f" - {issue.issue_type}: {issue.description}"
                    f" (isolation: {issue.isolation_level})"
                )

        severity_groups: Dict[str, List] = {"critical": [], "major": [], "minor": []}
        for issue in report.issues:
            severity_groups[issue.severity].append(issue)

        for severity in ["critical", "major", "minor"]:
            issues = severity_groups[severity]
            if issues:
                lines.append(f"\n{severity.upper()} ({len(issues)}):")
                for issue in issues:
                    lines.append(
                        f"  [{issue.rule_id}] {issue.file}:{issue.line}:{issue.column}"
                        f" - {issue.message}"
                    )

        return "\n".join(lines)

    def generate_json_report(self, report: QualityReport) -> str:
        return json.dumps(
            {
                "project_name": report.project_name,
                "generated_at": report.generated_at,
                "total_files": report.total_files,
                "score": report.score,
                "passed": report.passed,
                "issues": [
                    {
                        "file": i.file, "line": i.line, "column": i.column,
                        "severity": i.severity, "rule_id": i.rule_id,
                        "message": i.message, "language": i.language,
                    }
                    for i in report.issues
                ],
                "concurrency_issues": [
                    {
                        "file": ci.file, "line": ci.line, "issue_type": ci.issue_type,
                        "severity": ci.severity, "description": ci.description,
                        "isolation_level": ci.isolation_level, "shared_resource": ci.shared_resource,
                    }
                    for ci in report.concurrency_issues
                ],
            },
            indent=2,
            ensure_ascii=False,
        )

    def generate_html_report(self, report: QualityReport) -> str:
        status_color = "#28a745" if report.passed else "#dc3545"
        severity_colors = {"critical": "#dc3545", "major": "#fd7e14", "minor": "#ffc107"}

        issues_html = ""
        for issue in report.issues:
            color = severity_colors.get(issue.severity, "#6c757d")
            issues_html += f"""
            <tr>
                <td><span style="color: {color};">{issue.severity.upper()}</span></td>
                <td>{issue.rule_id}</td>
                <td>{issue.file}</td>
                <td>{issue.line}:{issue.column}</td>
                <td>{issue.message}</td>
            </tr>"""

        conc_html = ""
        for ci in report.concurrency_issues:
            color = severity_colors.get(ci.severity, "#6c757d")
            conc_html += f"""
            <tr>
                <td><span style="color: {color};">{ci.severity.upper()}</span></td>
                <td>{ci.issue_type}</td>
                <td>{ci.file}:{ci.line}</td>
                <td>{ci.description}</td>
                <td>{ci.isolation_level}</td>
            </tr>"""

        return f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>Code Quality Report - {report.project_name}</title>
            <style>
                body {{ font-family: Arial, sans-serif; margin: 20px; }}
                .header {{ background: #f8f9fa; padding: 20px; border-radius: 5px; }}
                .score {{ font-size: 48px; font-weight: bold; color: {status_color}; }}
                table {{ width: 100%; border-collapse: collapse; margin-top: 20px; }}
                th, td {{ padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }}
                th {{ background: #f8f9fa; }}
            </style>
        </head>
        <body>
            <div class="header">
                <h1>Code Quality Report: {report.project_name}</h1>
                <div class="score">{report.score}/100</div>
                <p>Status: <strong style="color: {status_color};">{'PASSED' if report.passed else 'FAILED'}</strong></p>
                <p>Total files: {report.total_files} | Issues: {len(report.issues)} | Concurrency: {len(report.concurrency_issues)}</p>
            </div>
            <h2>Concurrency Issues</h2>
            <table>
                <thead><tr><th>Severity</th><th>Type</th><th>Location</th><th>Description</th><th>Isolation</th></tr></thead>
                <tbody>{conc_html}</tbody>
            </table>
            <h2>Static Analysis Issues</h2>
            <table>
                <thead><tr><th>Severity</th><th>Rule</th><th>File</th><th>Location</th><th>Message</th></tr></thead>
                <tbody>{issues_html}</tbody>
            </table>
        </body>
        </html>"""
