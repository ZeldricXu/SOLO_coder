from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import re
import fnmatch
import json

from gateway.logger import get_logger

logger = get_logger("security-rules")


@dataclass
class SecurityRule:
    id: str
    name: str
    category: str
    description: str = ""
    pattern: str = ""
    severity: str = "medium"
    action: str = "block"
    enabled: bool = True
    targets: List[str] = field(default_factory=lambda: ["body", "query", "headers"])
    _compiled_pattern: Optional[re.Pattern] = None

    def compile(self) -> None:
        if self.pattern:
            try:
                self._compiled_pattern = re.compile(self.pattern, re.IGNORECASE | re.MULTILINE)
            except re.error as e:
                logger.error("Failed to compile security rule pattern", rule_id=self.id, error=str(e))
                self._compiled_pattern = None

    def match(self, text: str) -> bool:
        if not self._compiled_pattern or not self.enabled:
            return False
        return bool(self._compiled_pattern.search(text))

    def sanitize(self, text: str) -> str:
        if not self._compiled_pattern or not self.enabled:
            return text
        return self._compiled_pattern.sub("[REDACTED]", text)


OWASP_SQL_INJECTION_RULES = [
    {
        "id": "sql-001",
        "name": "SQL Injection - Classic OR",
        "category": "sql_injection",
        "description": "Detects classic SQL injection patterns using OR statements",
        "pattern": r"(\bor\b\s*['\"]?\d+['\"]?\s*=\s*['\"]?\d+)",
        "severity": "high",
    },
    {
        "id": "sql-002",
        "name": "SQL Injection - UNION SELECT",
        "category": "sql_injection",
        "description": "Detects UNION SELECT injection attempts",
        "pattern": r"(\bunion\b\s+select\b)",
        "severity": "high",
    },
    {
        "id": "sql-003",
        "name": "SQL Injection - DROP TABLE",
        "category": "sql_injection",
        "description": "Detects DROP TABLE injection attempts",
        "pattern": r"(\bdrop\b\s+table\b)",
        "severity": "critical",
    },
    {
        "id": "sql-004",
        "name": "SQL Injection - Comment",
        "category": "sql_injection",
        "description": "Detects SQL comment sequences used for injection",
        "pattern": r"(--|\/\*|\*\/)",
        "severity": "medium",
    },
    {
        "id": "sql-005",
        "name": "SQL Injection - Semicolon",
        "category": "sql_injection",
        "description": "Detects SQL command termination with semicolon",
        "pattern": r";\s*(select|insert|update|delete|drop|create|alter|exec|execute)\b",
        "severity": "high",
    },
]

OWASP_XSS_RULES = [
    {
        "id": "xss-001",
        "name": "XSS - Script Tag",
        "category": "xss",
        "description": "Detects script tag injection",
        "pattern": r"(<script[^>]*>.*?<\/script>)",
        "severity": "high",
    },
    {
        "id": "xss-002",
        "name": "XSS - Event Handler",
        "category": "xss",
        "description": "Detects event handler injection like onerror, onload",
        "pattern": r"\bon\w+\s*=\s*[\"']?javascript:",
        "severity": "high",
    },
    {
        "id": "xss-003",
        "name": "XSS - javascript: URI",
        "category": "xss",
        "description": "Detects javascript: URI scheme injection",
        "pattern": r"(javascript:)",
        "severity": "medium",
    },
    {
        "id": "xss-004",
        "name": "XSS - iframe",
        "category": "xss",
        "description": "Detects iframe injection",
        "pattern": r"(<iframe[^>]*>)",
        "severity": "medium",
    },
    {
        "id": "xss-005",
        "name": "XSS - eval()",
        "category": "xss",
        "description": "Detects eval() function calls",
        "pattern": r"(\beval\s*\()",
        "severity": "medium",
    },
]

OWASP_PATH_TRAVERSAL_RULES = [
    {
        "id": "path-001",
        "name": "Path Traversal - ../",
        "category": "path_traversal",
        "description": "Detects path traversal with ../ sequences",
        "pattern": r"(\.\.\/|\.\.\\)",
        "severity": "high",
    },
    {
        "id": "path-002",
        "name": "Path Traversal - /etc/passwd",
        "category": "path_traversal",
        "description": "Detects /etc/passwd access attempts",
        "pattern": r"(\/etc\/passwd|\/etc\/shadow)",
        "severity": "critical",
    },
    {
        "id": "path-003",
        "name": "Path Traversal - Windows System",
        "category": "path_traversal",
        "description": "Detects Windows system file access",
        "pattern": r"(\\windows\\system32|\\winnt\\system32)",
        "severity": "high",
    },
]

OWASP_COMMAND_INJECTION_RULES = [
    {
        "id": "cmd-001",
        "name": "Command Injection - Semicolon",
        "category": "command_injection",
        "description": "Detects command injection via semicolon",
        "pattern": r";\s*(ls|cat|rm|cp|mv|mkdir|chmod|chown|wget|curl|nc|bash|sh|cmd|powershell)\b",
        "severity": "high",
    },
    {
        "id": "cmd-002",
        "name": "Command Injection - Pipe",
        "category": "command_injection",
        "description": "Detects command injection via pipe",
        "pattern": r"\|\s*(ls|cat|rm|cp|mv|id|whoami|uname|netstat|ps)\b",
        "severity": "high",
    },
    {
        "id": "cmd-003",
        "name": "Command Injection - Backtick",
        "category": "command_injection",
        "description": "Detects command injection via backticks",
        "pattern": r"`[^`]+`",
        "severity": "medium",
    },
    {
        "id": "cmd-004",
        "name": "Command Injection - $()",
        "category": "command_injection",
        "description": "Detects command substitution with $()",
        "pattern": r"\$\([^)]+\)",
        "severity": "medium",
    },
]

OWASP_SSRF_RULES = [
    {
        "id": "ssrf-001",
        "name": "SSRF - Internal IP",
        "category": "ssrf",
        "description": "Detects SSRF attempts targeting internal IP ranges",
        "pattern": r"(10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|192\.168\.\d+\.\d+|127\.0\.0\.1|localhost)",
        "severity": "high",
    },
    {
        "id": "ssrf-002",
        "name": "SSRF - Cloud Metadata",
        "category": "ssrf",
        "description": "Detects SSRF attempts targeting cloud metadata services",
        "pattern": r"(169\.254\.169\.254|metadata\.google\.internal)",
        "severity": "critical",
    },
]


class SecurityRuleSet:
    def __init__(self):
        self._rules: Dict[str, SecurityRule] = {}
        self._rules_by_category: Dict[str, List[SecurityRule]] = {}
        self.version: str = "default"

    def load_default_rules(self, sql_injection: bool = True,
                           xss: bool = True,
                           path_traversal: bool = True,
                           command_injection: bool = True,
                           ssrf: bool = False) -> None:
        if sql_injection:
            self._load_rules_from_config(OWASP_SQL_INJECTION_RULES)
        if xss:
            self._load_rules_from_config(OWASP_XSS_RULES)
        if path_traversal:
            self._load_rules_from_config(OWASP_PATH_TRAVERSAL_RULES)
        if command_injection:
            self._load_rules_from_config(OWASP_COMMAND_INJECTION_RULES)
        if ssrf:
            self._load_rules_from_config(OWASP_SSRF_RULES)

        self._compile_all_rules()
        logger.info("Default security rules loaded",
                    total_rules=len(self._rules),
                    categories=list(self._rules_by_category.keys()))

    def _load_rules_from_config(self, rules_config: List[Dict[str, Any]]) -> None:
        for rule_config in rules_config:
            rule = SecurityRule(**rule_config)
            self._rules[rule.id] = rule
            if rule.category not in self._rules_by_category:
                self._rules_by_category[rule.category] = []
            self._rules_by_category[rule.category].append(rule)

    def _compile_all_rules(self) -> None:
        for rule in self._rules.values():
            rule.compile()

    def add_rule(self, rule: SecurityRule) -> None:
        rule.compile()
        self._rules[rule.id] = rule
        if rule.category not in self._rules_by_category:
            self._rules_by_category[rule.category] = []
        self._rules_by_category[rule.category].append(rule)
        logger.info("Security rule added", rule_id=rule.id, category=rule.category)

    def remove_rule(self, rule_id: str) -> bool:
        if rule_id in self._rules:
            rule = self._rules.pop(rule_id)
            if rule.category in self._rules_by_category:
                self._rules_by_category[rule.category] = [
                    r for r in self._rules_by_category[rule.category] if r.id != rule_id
                ]
            logger.info("Security rule removed", rule_id=rule_id)
            return True
        return False

    def get_rule(self, rule_id: str) -> Optional[SecurityRule]:
        return self._rules.get(rule_id)

    def get_rules_by_category(self, category: str) -> List[SecurityRule]:
        return self._rules_by_category.get(category, [])

    def get_all_rules(self) -> List[SecurityRule]:
        return list(self._rules.values())

    def scan_text(self, text: str, targets: Optional[List[str]] = None) -> List[SecurityRule]:
        matched_rules = []
        for rule in self._rules.values():
            if not rule.enabled:
                continue
            if targets and not any(t in rule.targets for t in targets):
                continue
            if rule.match(text):
                matched_rules.append(rule)
        return matched_rules

    def sanitize_text(self, text: str, targets: Optional[List[str]] = None) -> str:
        sanitized = text
        for rule in self._rules.values():
            if not rule.enabled:
                continue
            if targets and not any(t in rule.targets for t in targets):
                continue
            if rule.action == "sanitize":
                sanitized = rule.sanitize(sanitized)
        return sanitized

    def load_from_json(self, rules_json: str) -> None:
        try:
            data = json.loads(rules_json)
            self._rules.clear()
            self._rules_by_category.clear()

            rules_data = data.get("rules", [])
            for rule_data in rules_data:
                rule = SecurityRule(**rule_data)
                self._rules[rule.id] = rule
                if rule.category not in self._rules_by_category:
                    self._rules_by_category[rule.category] = []
                self._rules_by_category[rule.category].append(rule)

            self.version = data.get("version", "custom")
            self._compile_all_rules()
            logger.info("Security rules loaded from JSON",
                        total_rules=len(self._rules),
                        version=self.version)
        except (json.JSONDecodeError, TypeError) as e:
            logger.error("Failed to load security rules from JSON", error=str(e))

    def to_json(self) -> str:
        return json.dumps({
            "version": self.version,
            "rules": [
                {
                    "id": r.id,
                    "name": r.name,
                    "category": r.category,
                    "description": r.description,
                    "pattern": r.pattern,
                    "severity": r.severity,
                    "action": r.action,
                    "enabled": r.enabled,
                    "targets": r.targets,
                }
                for r in self._rules.values()
            ]
        }, indent=2)


_rule_set_instance: Optional[SecurityRuleSet] = None


def get_security_rule_set() -> SecurityRuleSet:
    global _rule_set_instance
    if _rule_set_instance is None:
        _rule_set_instance = SecurityRuleSet()
    return _rule_set_instance
