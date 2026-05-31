import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Callable
from enum import Enum
from abc import ABC, abstractmethod
from .logging_module import get_logger

logger = get_logger(__name__)


class Language(str, Enum):
    PYTHON = "python"
    JAVASCRIPT = "javascript"
    TYPESCRIPT = "typescript"
    GO = "go"
    JAVA = "java"


class Severity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


@dataclass
class AnalysisRule:
    rule_id: str
    name: str
    description: str
    language: Language
    severity: Severity
    pattern: Optional[str] = None
    enabled: bool = True


@dataclass
class AnalysisIssue:
    rule_id: str
    line: int
    column: int
    message: str
    severity: Severity
    snippet: Optional[str] = None


@dataclass
class AnalysisReport:
    report_id: str
    language: Language
    total_files: int = 0
    total_issues: int = 0
    issues_by_severity: Dict[str, int] = field(default_factory=dict)
    issues: List[AnalysisIssue] = field(default_factory=list)
    quality_score: float = 0.0
    threshold_pass: bool = False


class StaticAnalyzer(ABC):
    def __init__(self, language: Language):
        self.language = language
        self.rules: List[AnalysisRule] = []
        self._load_default_rules()

    @abstractmethod
    def _load_default_rules(self) -> None:
        pass

    def add_rule(self, rule: AnalysisRule) -> None:
        self.rules.append(rule)

    def analyze(self, code: str, filename: str = "untitled") -> List[AnalysisIssue]:
        issues: List[AnalysisIssue] = []
        lines = code.split('\n')

        for rule in self.rules:
            if not rule.enabled or not rule.pattern:
                continue
            try:
                issues.extend(self._check_pattern(code, lines, rule))
            except Exception as e:
                logger.error(f"Error checking rule {rule.rule_id}: {e}")
        return issues

    def _check_pattern(self, code: str, lines: List[str], rule: AnalysisRule) -> List[AnalysisIssue]:
        issues: List[AnalysisIssue] = []
        try:
            regex = re.compile(rule.pattern, re.MULTILINE)
            for match in regex.finditer(code):
                line_num = code[:match.start()].count('\n') + 1
                col_num = match.start() - code[:match.start()].rfind('\n')
                snippet = lines[line_num - 1].strip() if line_num <= len(lines) else None
                issues.append(AnalysisIssue(
                    rule_id=rule.rule_id,
                    line=line_num,
                    column=col_num,
                    message=rule.description,
                    severity=rule.severity,
                    snippet=snippet
                ))
        except re.error as e:
            logger.error(f"Invalid regex in rule {rule.rule_id}: {e}")
        return issues


class PythonAnalyzer(StaticAnalyzer):
    def __init__(self):
        super().__init__(Language.PYTHON)

    def _load_default_rules(self) -> None:
        self.rules = [
            AnalysisRule("PY001", "PrintStatement", "Use logging instead of print()",
                        Language.PYTHON, Severity.WARNING, r'^\s*print\s*\('),
            AnalysisRule("PY002", "TodoComment", "TODO comment found",
                        Language.PYTHON, Severity.INFO, r'#.*(TODO|FIXME)'),
            AnalysisRule("PY003", "LongLine", "Line exceeds 120 characters",
                        Language.PYTHON, Severity.WARNING, r'^.{121,}$'),
            AnalysisRule("PY004", "DebugImport", "Debug import found (pdb)",
                        Language.PYTHON, Severity.ERROR, r'import\s+(pdb|debugpy)'),
            AnalysisRule("PY005", "HardcodedSecret", "Potential hardcoded secret",
                        Language.PYTHON, Severity.CRITICAL,
                        r'(password|secret|api_key|token)\s*=\s*["\'][^"\']+["\']'),
        ]


class JavaScriptAnalyzer(StaticAnalyzer):
    def __init__(self):
        super().__init__(Language.JAVASCRIPT)

    def _load_default_rules(self) -> None:
        self.rules = [
            AnalysisRule("JS001", "ConsoleLog", "Avoid console.log in production",
                        Language.JAVASCRIPT, Severity.WARNING, r'console\s*\.\s*log\s*\('),
            AnalysisRule("JS002", "EvalUsage", "Avoid eval() - security risk",
                        Language.JAVASCRIPT, Severity.CRITICAL, r'\beval\s*\('),
            AnalysisRule("JS003", "VarDeclaration", "Use const/let instead of var",
                        Language.JAVASCRIPT, Severity.WARNING, r'\bvar\s+\w+'),
        ]


class GoAnalyzer(StaticAnalyzer):
    def __init__(self):
        super().__init__(Language.GO)

    def _load_default_rules(self) -> None:
        self.rules = [
            AnalysisRule("GO001", "PrintInCode", "Avoid fmt.Print in production",
                        Language.GO, Severity.WARNING, r'fmt\.Print'),
        ]


class AnalyzerFactory:
    _analyzers: Dict[Language, StaticAnalyzer] = {}

    @classmethod
    def get_analyzer(cls, language: Language) -> StaticAnalyzer:
        if language not in cls._analyzers:
            analyzer_map = {
                Language.PYTHON: PythonAnalyzer,
                Language.JAVASCRIPT: JavaScriptAnalyzer,
                Language.TYPESCRIPT: JavaScriptAnalyzer,
                Language.GO: GoAnalyzer,
            }
            analyzer_class = analyzer_map.get(language)
            if not analyzer_class:
                raise ValueError(f"No analyzer for language: {language}")
            cls._analyzers[language] = analyzer_class()
        return cls._analyzers[language]


class QualityGate:
    def __init__(self, thresholds: Optional[Dict[str, float]] = None):
        self.thresholds = thresholds or {
            "critical": 0,
            "error": 3,
            "warning": 10,
            "quality_score": 70.0,
        }

    def evaluate(self, report: AnalysisReport) -> AnalysisReport:
        score = self._calculate_score(report)
        report.quality_score = score

        critical_count = report.issues_by_severity.get(Severity.CRITICAL, 0)
        error_count = report.issues_by_severity.get(Severity.ERROR, 0)
        warning_count = report.issues_by_severity.get(Severity.WARNING, 0)

        report.threshold_pass = (
            critical_count <= self.thresholds["critical"]
            and error_count <= self.thresholds["error"]
            and warning_count <= self.thresholds["warning"]
            and score >= self.thresholds["quality_score"]
        )
        return report

    def _calculate_score(self, report: AnalysisReport) -> float:
        if report.total_files == 0:
            return 100.0

        weights = {Severity.CRITICAL: 30, Severity.ERROR: 15, Severity.WARNING: 5, Severity.INFO: 1}
        penalty = sum(report.issues_by_severity.get(s, 0) * w for s, w in weights.items())
        max_penalty = report.total_files * 50
        normalized = min(penalty / max_penalty, 1.0) if max_penalty > 0 else 0
        return max(0.0, 100.0 * (1.0 - normalized))


class CodeQualityService:
    def __init__(self):
        self.quality_gate = QualityGate()

    def analyze_code(self, code: str, language: str, filename: str = "untitled") -> AnalysisReport:
        lang = Language(language.lower())
        analyzer = AnalyzerFactory.get_analyzer(lang)
        issues = analyzer.analyze(code, filename)

        issues_by_severity: Dict[str, int] = {}
        for issue in issues:
            issues_by_severity[issue.severity] = issues_by_severity.get(issue.severity, 0) + 1

        report = AnalysisReport(
            report_id=f"report_{uuid.uuid4().hex[:8]}",
            language=lang,
            total_files=1,
            total_issues=len(issues),
            issues_by_severity=issues_by_severity,
            issues=issues,
        )
        return self.quality_gate.evaluate(report)

    def get_rules(self, language: str) -> List[Dict[str, Any]]:
        lang = Language(language.lower())
        analyzer = AnalyzerFactory.get_analyzer(lang)
        return [{"rule_id": r.rule_id, "name": r.name, "description": r.description,
                 "severity": r.severity, "enabled": r.enabled} for r in analyzer.rules]

    def update_thresholds(self, thresholds: Dict[str, float]) -> None:
        self.quality_gate.thresholds.update(thresholds)

    def get_thresholds(self) -> Dict[str, float]:
        return self.quality_gate.thresholds.copy()


def get_code_quality_service() -> CodeQualityService:
    return CodeQualityService()
