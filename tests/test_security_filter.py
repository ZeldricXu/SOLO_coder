import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from typing import Any, Dict, List, Optional

from starlette.requests import Request
from starlette.responses import JSONResponse

from gateway.security.rules import (
    SecurityRule,
    SecurityRuleSet,
    get_security_rule_set,
    OWASP_SQL_INJECTION_RULES,
    OWASP_XSS_RULES,
    OWASP_PATH_TRAVERSAL_RULES,
    OWASP_COMMAND_INJECTION_RULES,
)
from gateway.security.filter import (
    SecurityFilter,
    SecurityScanResult,
    get_security_filter,
)
from gateway.security.middleware import SecurityFilterMiddleware
from gateway.config import get_settings
from tests.conftest import *


pytestmark = pytest.mark.asyncio


class TestSecurityRule:
    def test_rule_creation(self):
        rule = SecurityRule(
            id="test-001",
            name="Test Rule",
            category="test",
            description="Test rule",
            pattern=r"\bdrop\b",
            severity="high",
        )
        assert rule.id == "test-001"
        assert rule.name == "Test Rule"
        assert rule.category == "test"
        assert rule.enabled is True

    def test_rule_compile_and_match(self):
        rule = SecurityRule(
            id="sql-001",
            name="SQL Injection Test",
            category="sql_injection",
            pattern=r"\bunion\s+select\b",
            severity="high",
        )
        rule.compile()

        assert rule.match("1 UNION SELECT * FROM users") is True
        assert rule.match("select * from users") is False

    def test_rule_disabled_no_match(self):
        rule = SecurityRule(
            id="test-001",
            name="Test",
            category="test",
            pattern=r"test",
            enabled=False,
        )
        rule.compile()

        assert rule.match("this is a test") is False

    def test_rule_sanitize(self):
        rule = SecurityRule(
            id="xss-001",
            name="XSS Test",
            category="xss",
            pattern=r"<script[^>]*>.*?<\/script>",
            action="sanitize",
        )
        rule.compile()

        result = rule.sanitize("hello <script>alert(1)</script> world")
        assert "<script>" not in result
        assert "[REDACTED]" in result

    def test_rule_invalid_pattern_handled(self):
        rule = SecurityRule(
            id="bad-001",
            name="Bad Pattern",
            category="test",
            pattern=r"[invalid",
            severity="low",
        )
        rule.compile()
        assert rule._compiled_pattern is None
        assert rule.match("test") is False


class TestSecurityRuleSet:
    def test_load_default_rules(self):
        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()

        assert len(rule_set.get_all_rules()) > 0
        assert len(rule_set.get_rules_by_category("sql_injection")) > 0
        assert len(rule_set.get_rules_by_category("xss")) > 0
        assert len(rule_set.get_rules_by_category("path_traversal")) > 0

    def test_load_default_rules_selective(self):
        rule_set = SecurityRuleSet()
        rule_set.load_default_rules(
            sql_injection=True,
            xss=False,
            path_traversal=False,
            command_injection=False,
            ssrf=False,
        )

        assert len(rule_set.get_rules_by_category("sql_injection")) > 0
        assert len(rule_set.get_rules_by_category("xss")) == 0

    def test_add_and_remove_rule(self):
        rule_set = SecurityRuleSet()

        rule = SecurityRule(
            id="custom-001",
            name="Custom Rule",
            category="custom",
            pattern=r"custom_pattern",
        )

        rule_set.add_rule(rule)
        assert rule_set.get_rule("custom-001") is not None
        assert len(rule_set.get_rules_by_category("custom")) == 1

        removed = rule_set.remove_rule("custom-001")
        assert removed is True
        assert rule_set.get_rule("custom-001") is None

    def test_scan_text_matches(self):
        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()

        sql_text = "1' OR '1'='1; DROP TABLE users;--"
        matches = rule_set.scan_text(sql_text)

        assert len(matches) > 0
        categories = {r.category for r in matches}
        assert "sql_injection" in categories

    def test_scan_text_with_targets(self):
        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()

        sql_text = "1 UNION SELECT * FROM users"

        matches_body = rule_set.scan_text(sql_text, ["body"])
        matches_headers = rule_set.scan_text(sql_text, ["headers"])

        assert len(matches_body) > 0

    def test_sanitize_text(self):
        rule_set = SecurityRuleSet()

        rule = SecurityRule(
            id="sanitize-001",
            name="Sanitize Rule",
            category="test",
            pattern=r"<script>",
            action="sanitize",
        )
        rule_set.add_rule(rule)

        result = rule_set.sanitize_text("hello <script> world")
        assert "<script>" not in result

    def test_json_roundtrip(self):
        rule_set = SecurityRuleSet()
        rule_set.load_default_rules(
            sql_injection=True,
            xss=True,
            path_traversal=False,
            command_injection=False,
            ssrf=False,
        )

        json_str = rule_set.to_json()
        assert "version" in json_str
        assert "rules" in json_str

        new_rule_set = SecurityRuleSet()
        new_rule_set.load_from_json(json_str)

        assert len(new_rule_set.get_all_rules()) == len(rule_set.get_all_rules())

    def test_load_from_invalid_json(self):
        rule_set = SecurityRuleSet()
        rule_set.load_from_json("invalid json {{{")

        assert len(rule_set.get_all_rules()) == 0


class TestSecurityFilter:
    async def test_filter_disabled_no_scan(self):
        settings = get_settings()
        settings.security_filter.enabled = False

        filter_obj = SecurityFilter()
        filter_obj.sf_settings = settings.security_filter
        filter_obj.rule_set = get_security_rule_set()

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/health"
        request.url.query = ""
        request.headers = {}

        result = await filter_obj.scan_request(request)

        assert result.blocked is False
        assert len(result.matched_rules) == 0

    async def test_filter_block_sql_injection(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.mode = "block"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()
        filter_obj.rule_set = rule_set

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.url.query = "id=1' OR '1'='1"
        request.headers = {"Content-Type": "application/json"}
        request.method = "GET"

        result = await filter_obj.scan_request(request)

        assert result.is_suspicious is True
        assert result.blocked is True
        assert len(result.matched_rules) > 0

    async def test_filter_xss_in_body(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.mode = "block"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()
        filter_obj.rule_set = rule_set

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.url.query = ""
        request.headers = {"Content-Type": "application/json"}
        request.method = "POST"

        body = b'{"name": "<script>alert(1)</script>"}'
        result = await filter_obj.scan_request(request, body)

        assert result.is_suspicious is True
        assert result.blocked is True
        categories = {r.category for r in result.matched_rules}
        assert "xss" in categories

    async def test_filter_path_traversal(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.mode = "block"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()
        filter_obj.rule_set = rule_set

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/file"
        request.url.query = "path=../../etc/passwd"
        request.headers = {}
        request.method = "GET"

        result = await filter_obj.scan_request(request)

        assert result.is_suspicious is True
        assert result.blocked is True

    async def test_sanitize_mode(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.mode = "sanitize"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        sanitize_rule = SecurityRule(
            id="sanitize-xss-001",
            name="XSS Sanitize",
            category="xss",
            pattern=r"<script[^>]*>.*?<\/script>",
            action="sanitize",
            severity="medium",
            targets=["query", "body", "headers"],
        )
        sanitize_rule.compile()
        rule_set.add_rule(sanitize_rule)
        filter_obj.rule_set = rule_set

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.url.query = "q=<script>test</script>"
        request.headers = {}
        request.method = "GET"

        result = await filter_obj.scan_request(request)

        assert result.is_suspicious is True
        assert result.blocked is False
        assert result.sanitized_query is not None
        assert "<script>" not in result.sanitized_query

    async def test_highest_severity(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.mode = "block"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()

        critical_rule = SecurityRule(
            id="crit-001",
            name="Critical Rule",
            category="test",
            pattern=r"critical_pattern",
            severity="critical",
        )
        medium_rule = SecurityRule(
            id="med-001",
            name="Medium Rule",
            category="test",
            pattern=r"medium_pattern",
            severity="medium",
        )
        rule_set.add_rule(critical_rule)
        rule_set.add_rule(medium_rule)
        filter_obj.rule_set = rule_set

        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.url.query = "q=critical_pattern_and_medium_pattern"
        request.headers = {}
        request.method = "GET"

        result = await filter_obj.scan_request(request)

        assert result.highest_severity == "critical"

    async def test_blocked_response(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        settings.security_filter.blocked_response_code = 403
        settings.security_filter.blocked_response_message = "Request blocked"
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()
        filter_obj.rule_set = rule_set

        result = SecurityScanResult(
            blocked=True,
            matched_rules=[
                SecurityRule(id="test-001", name="Test Rule", category="test", severity="high")
            ],
            highest_severity="high",
        )

        response = filter_obj.get_blocked_response(result)

        assert isinstance(response, JSONResponse)
        assert response.status_code == 403
        assert "X-Security-Filter" in response.headers
        assert response.headers["X-Security-Filter"] == "blocked"


class TestSecurityFilterMiddleware:
    async def test_middleware_disabled_passthrough(self):
        settings = get_settings()
        settings.security_filter.enabled = False

        with patch("gateway.security.middleware.get_security_filter", return_value=get_security_filter()):
            middleware = SecurityFilterMiddleware(None)

            request = MagicMock(spec=Request)
            request.url = MagicMock()
            request.url.path = "/api/test"
            request.url.query = "bad=1' OR 1=1"
            request.headers = {}
            request.method = "GET"
            request.state = MagicMock()
            request.body = AsyncMock(return_value=b"")

            call_next = AsyncMock(return_value=JSONResponse({"ok": True}))

            response = await middleware.dispatch(request, call_next)

            call_next.assert_called_once()

    async def test_middleware_skip_health(self):
        filter_obj = SecurityFilter()

        settings = get_settings()
        settings.security_filter.enabled = True
        filter_obj.sf_settings = settings.security_filter

        rule_set = SecurityRuleSet()
        rule_set.load_default_rules()
        filter_obj.rule_set = rule_set

        with patch("gateway.security.middleware.get_security_filter", return_value=filter_obj):
            middleware = SecurityFilterMiddleware(None)

            request = MagicMock(spec=Request)
            request.url = MagicMock()
            request.url.path = "/health"
            request.url.query = ""
            request.headers = {}
            request.method = "GET"
            request.state = MagicMock()

            call_next = AsyncMock(return_value=JSONResponse({"status": "ok"}))

            response = await middleware.dispatch(request, call_next)

            call_next.assert_called_once()
