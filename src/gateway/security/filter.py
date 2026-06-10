from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import asyncio
import json
import httpx

from starlette.requests import Request
from starlette.responses import JSONResponse

from gateway.config import get_settings
from gateway.security.rules import SecurityRule, SecurityRuleSet, get_security_rule_set
from gateway.logger import get_logger

logger = get_logger("security-filter")


@dataclass
class SecurityScanResult:
    blocked: bool = False
    matched_rules: List[SecurityRule] = field(default_factory=list)
    sanitized_query: Optional[str] = None
    sanitized_headers: Optional[Dict[str, str]] = None
    sanitized_body: Optional[bytes] = None
    highest_severity: str = "low"

    @property
    def is_suspicious(self) -> bool:
        return len(self.matched_rules) > 0


class SecurityFilter:
    def __init__(self):
        self.settings = get_settings()
        self.sf_settings = self.settings.security_filter
        self.rule_set: SecurityRuleSet = get_security_rule_set()
        self._remote_update_task: Optional[asyncio.Task] = None
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        if self.sf_settings.owasp_top10_enabled:
            self.rule_set.load_default_rules(
                sql_injection=self.sf_settings.sql_injection_enabled,
                xss=self.sf_settings.xss_enabled,
                path_traversal=self.sf_settings.path_traversal_enabled,
                command_injection=self.sf_settings.command_injection_enabled,
                ssrf=self.sf_settings.ssrf_enabled,
            )

        if self.sf_settings.custom_rules_path:
            await self._load_custom_rules()

        if self.sf_settings.remote_rules_enabled and self.sf_settings.remote_rules_url:
            await self._start_remote_rules_updater()

        self._initialized = True
        logger.info("Security filter initialized",
                    enabled=self.sf_settings.enabled,
                    mode=self.sf_settings.mode,
                    total_rules=len(self.rule_set.get_all_rules()))

    async def _load_custom_rules(self) -> None:
        try:
            import os
            if os.path.exists(self.sf_settings.custom_rules_path):
                with open(self.sf_settings.custom_rules_path, "r") as f:
                    rules_json = f.read()
                self.rule_set.load_from_json(rules_json)
                logger.info("Custom security rules loaded",
                            path=self.sf_settings.custom_rules_path)
        except Exception as e:
            logger.error("Failed to load custom security rules", error=str(e))

    async def _start_remote_rules_updater(self) -> None:
        if self._remote_update_task and not self._remote_update_task.done():
            return

        async def update_loop():
            while True:
                try:
                    await self._fetch_remote_rules()
                except Exception as e:
                    logger.error("Remote rules update failed", error=str(e))
                await asyncio.sleep(self.sf_settings.remote_rules_refresh_interval)

        self._remote_update_task = asyncio.create_task(update_loop())
        logger.info("Remote rules updater started",
                    url=self.sf_settings.remote_rules_url,
                    interval=self.sf_settings.remote_rules_refresh_interval)

    async def _fetch_remote_rules(self) -> None:
        if not self.sf_settings.remote_rules_url:
            return

        headers = {}
        if self.sf_settings.remote_rules_auth_token:
            headers["Authorization"] = f"Bearer {self.sf_settings.remote_rules_auth_token}"

        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(self.sf_settings.remote_rules_url, headers=headers)
            response.raise_for_status()
            rules_json = response.text
            self.rule_set.load_from_json(rules_json)
            logger.info("Remote security rules updated", version=self.rule_set.version)

    async def scan_request(self, request: Request, body: Optional[bytes] = None) -> SecurityScanResult:
        result = SecurityScanResult()

        if not self.sf_settings.enabled:
            return result

        if self.sf_settings.scan_query:
            query_string = request.url.query
            if query_string:
                query_rules = self.rule_set.scan_text(query_string, ["query"])
                result.matched_rules.extend(query_rules)

        if self.sf_settings.scan_headers:
            headers_str = "\n".join(f"{k}: {v}" for k, v in request.headers.items())
            header_rules = self.rule_set.scan_text(headers_str, ["headers"])
            result.matched_rules.extend(header_rules)

        if self.sf_settings.scan_body and body:
            try:
                body_str = body.decode("utf-8", errors="ignore")
                body_rules = self.rule_set.scan_text(body_str, ["body"])
                result.matched_rules.extend(body_rules)
            except Exception:
                pass

        if result.matched_rules:
            severity_order = {"critical": 4, "high": 3, "medium": 2, "low": 1}
            result.highest_severity = max(
                (rule.severity for rule in result.matched_rules),
                key=lambda s: severity_order.get(s, 0)
            )

            if self.sf_settings.mode == "block":
                blockable = [r for r in result.matched_rules if r.action == "block"]
                result.blocked = len(blockable) > 0
            elif self.sf_settings.mode == "sanitize":
                result.blocked = False
                result = await self._sanitize_request(request, body, result)
            else:
                blockable = [r for r in result.matched_rules if r.action == "block"]
                result.blocked = len(blockable) > 0 and self.sf_settings.default_action == "block"
                if not result.blocked:
                    result = await self._sanitize_request(request, body, result)

        if self.sf_settings.log_blocked_requests and result.blocked:
            logger.warning("Security filter blocked request",
                           path=request.url.path,
                           method=request.method,
                           matched_rules=[r.id for r in result.matched_rules],
                           severity=result.highest_severity)

        return result

    async def _sanitize_request(self, request: Request, body: Optional[bytes],
                                  result: SecurityScanResult) -> SecurityScanResult:
        if self.sf_settings.scan_query and request.url.query:
            result.sanitized_query = self.rule_set.sanitize_text(request.url.query, ["query"])

        if self.sf_settings.scan_headers:
            sanitized_headers = {}
            for k, v in request.headers.items():
                sanitized_headers[k] = self.rule_set.sanitize_text(v, ["headers"])
            result.sanitized_headers = sanitized_headers

        if self.sf_settings.scan_body and body:
            try:
                body_str = body.decode("utf-8", errors="ignore")
                sanitized_body_str = self.rule_set.sanitize_text(body_str, ["body"])
                result.sanitized_body = sanitized_body_str.encode("utf-8")
            except Exception:
                pass

        return result

    def get_blocked_response(self, result: SecurityScanResult) -> JSONResponse:
        return JSONResponse(
            status_code=self.sf_settings.blocked_response_code,
            content={
                "error": {
                    "code": self.sf_settings.blocked_response_code,
                    "message": self.sf_settings.blocked_response_message,
                    "detail": f"Request blocked by security policy. Matched {len(result.matched_rules)} rule(s).",
                    "severity": result.highest_severity,
                    "matched_rules": [
                        {"id": r.id, "name": r.name, "category": r.category, "severity": r.severity}
                        for r in result.matched_rules
                    ],
                }
            },
            headers={
                "X-Security-Filter": "blocked",
                "X-Security-Severity": result.highest_severity,
            }
        )

    async def shutdown(self) -> None:
        if self._remote_update_task and not self._remote_update_task.done():
            self._remote_update_task.cancel()
            try:
                await self._remote_update_task
            except asyncio.CancelledError:
                pass
            self._remote_update_task = None
        logger.info("Security filter shutdown")


_filter_instance: Optional[SecurityFilter] = None


def get_security_filter() -> SecurityFilter:
    global _filter_instance
    if _filter_instance is None:
        _filter_instance = SecurityFilter()
    return _filter_instance
