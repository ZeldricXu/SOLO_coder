from typing import Dict, Optional
from prometheus_client import (
    Counter,
    Histogram,
    Gauge,
    CollectorRegistry,
    generate_latest,
    CONTENT_TYPE_LATEST,
    REGISTRY,
)

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("metrics")

_metrics_registry = REGISTRY

http_requests_total = Counter(
    "gateway_http_requests_total",
    "Total number of HTTP requests processed by the gateway",
    ["method", "route", "status_code", "user_id", "tenant_id"],
)

http_request_duration_seconds = Histogram(
    "gateway_http_request_duration_seconds",
    "HTTP request duration in seconds",
    ["method", "route"],
    buckets=[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0],
)

http_request_size_bytes = Histogram(
    "gateway_http_request_size_bytes",
    "HTTP request size in bytes",
    ["method", "route"],
)

http_response_size_bytes = Histogram(
    "gateway_http_response_size_bytes",
    "HTTP response size in bytes",
    ["method", "route", "status_code"],
)

rate_limit_rejections_total = Counter(
    "gateway_rate_limit_rejections_total",
    "Total number of requests rejected due to rate limiting",
    ["route", "key", "reason"],
)

rate_limit_remaining_gauge = Gauge(
    "gateway_rate_limit_remaining",
    "Remaining rate limit tokens",
    ["route", "key"],
)

circuit_breaker_triggers_total = Counter(
    "gateway_circuit_breaker_triggers_total",
    "Total number of circuit breaker state transitions",
    ["route", "target", "from_state", "to_state"],
)

circuit_breaker_state_gauge = Gauge(
    "gateway_circuit_breaker_state",
    "Current circuit breaker state (0=closed, 1=half-open, 2=open)",
    ["route", "target"],
)

auth_failures_total = Counter(
    "gateway_auth_failures_total",
    "Total number of authentication failures",
    ["route", "reason", "auth_type"],
)

auth_success_total = Counter(
    "gateway_auth_success_total",
    "Total number of successful authentications",
    ["route", "auth_type"],
)

upstream_request_duration_seconds = Histogram(
    "gateway_upstream_request_duration_seconds",
    "Upstream service request duration in seconds",
    ["route", "target", "status_code"],
)

upstream_errors_total = Counter(
    "gateway_upstream_errors_total",
    "Total number of upstream errors",
    ["route", "target", "error_type"],
)

security_blocked_requests_total = Counter(
    "gateway_security_blocked_requests_total",
    "Total number of requests blocked by security filter",
    ["route", "category", "severity", "rule_id"],
)

security_cleaned_requests_total = Counter(
    "gateway_security_cleaned_requests_total",
    "Total number of requests cleaned by security filter",
    ["route", "category"],
)

active_requests_gauge = Gauge(
    "gateway_active_requests",
    "Number of currently active requests being processed",
    ["method", "route"],
)


def _route_label(route_name: Optional[str]) -> str:
    return route_name or "unknown"


def record_request_start(method: str, route: Optional[str]) -> None:
    try:
        active_requests_gauge.labels(method=method, route=_route_label(route)).inc()
    except Exception as e:
        logger.warning("Failed to record request start metric", error=str(e))


def record_request_end(
    method: str,
    route: Optional[str],
    status_code: int,
    duration_seconds: float,
    user_id: str = "",
    tenant_id: str = "",
    request_size: Optional[int] = None,
    response_size: Optional[int] = None,
) -> None:
    try:
        route_label = _route_label(route)
        status_label = str(status_code)

        http_requests_total.labels(
            method=method,
            route=route_label,
            status_code=status_label,
            user_id=user_id or "anonymous",
            tenant_id=tenant_id or "unknown",
        ).inc()

        http_request_duration_seconds.labels(
            method=method,
            route=route_label,
        ).observe(duration_seconds)

        if request_size is not None:
            http_request_size_bytes.labels(
                method=method,
                route=route_label,
            ).observe(request_size)

        if response_size is not None:
            http_response_size_bytes.labels(
                method=method,
                route=route_label,
                status_code=status_label,
            ).observe(response_size)

        active_requests_gauge.labels(
            method=method,
            route=route_label,
        ).dec()
    except Exception as e:
        logger.warning("Failed to record request metrics", error=str(e))


def record_rate_limit_rejection(route: Optional[str], key: str, reason: str = "quota_exceeded") -> None:
    try:
        rate_limit_rejections_total.labels(
            route=_route_label(route),
            key=key,
            reason=reason,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record rate limit rejection metric", error=str(e))


def record_rate_limit_remaining(route: Optional[str], key: str, remaining: int) -> None:
    try:
        rate_limit_remaining_gauge.labels(
            route=_route_label(route),
            key=key,
        ).set(remaining)
    except Exception as e:
        logger.warning("Failed to record rate limit remaining metric", error=str(e))


def record_circuit_breaker_transition(
    route: Optional[str],
    target: str,
    from_state: str,
    to_state: str,
) -> None:
    try:
        circuit_breaker_triggers_total.labels(
            route=_route_label(route),
            target=target,
            from_state=from_state,
            to_state=to_state,
        ).inc()

        state_value = {"closed": 0, "half_open": 1, "open": 2}.get(to_state, 0)
        circuit_breaker_state_gauge.labels(
            route=_route_label(route),
            target=target,
        ).set(state_value)
    except Exception as e:
        logger.warning("Failed to record circuit breaker metric", error=str(e))


def record_auth_failure(route: Optional[str], reason: str, auth_type: str = "unknown") -> None:
    try:
        auth_failures_total.labels(
            route=_route_label(route),
            reason=reason,
            auth_type=auth_type,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record auth failure metric", error=str(e))


def record_auth_success(route: Optional[str], auth_type: str) -> None:
    try:
        auth_success_total.labels(
            route=_route_label(route),
            auth_type=auth_type,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record auth success metric", error=str(e))


def record_upstream_request(
    route: Optional[str],
    target: str,
    status_code: int,
    duration_seconds: float,
) -> None:
    try:
        upstream_request_duration_seconds.labels(
            route=_route_label(route),
            target=target,
            status_code=str(status_code),
        ).observe(duration_seconds)
    except Exception as e:
        logger.warning("Failed to record upstream duration metric", error=str(e))


def record_upstream_error(route: Optional[str], target: str, error_type: str) -> None:
    try:
        upstream_errors_total.labels(
            route=_route_label(route),
            target=target,
            error_type=error_type,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record upstream error metric", error=str(e))


def record_security_blocked(
    route: Optional[str],
    category: str,
    severity: str,
    rule_id: str,
) -> None:
    try:
        security_blocked_requests_total.labels(
            route=_route_label(route),
            category=category,
            severity=severity,
            rule_id=rule_id,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record security blocked metric", error=str(e))


def record_security_cleaned(route: Optional[str], category: str) -> None:
    try:
        security_cleaned_requests_total.labels(
            route=_route_label(route),
            category=category,
        ).inc()
    except Exception as e:
        logger.warning("Failed to record security cleaned metric", error=str(e))


def get_latest_metrics() -> bytes:
    return generate_latest(_metrics_registry)


def get_metrics_content_type() -> str:
    return CONTENT_TYPE_LATEST
