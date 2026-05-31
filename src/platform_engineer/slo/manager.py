import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from ..core.events import DomainEvent, EventBus, get_global_event_bus


class BurnDownState(Enum):
    NORMAL = "normal"
    FAST_BURN = "fast_burn"
    SLOW_BURN = "slow_burn"
    EXHAUSTED = "exhausted"


@dataclass
class AlertRule:
    name: str
    severity: str
    burn_rate_threshold: float
    window_minutes: int
    message_template: str = "Error budget burning too fast: {burn_rate:.2f}x, remaining: {remaining:.2f}%"


@dataclass
class SLIMetric:
    name: str
    good_count: int = 0
    total_count: int = 0
    last_updated: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    @property
    def sli_value(self) -> float:
        if self.total_count == 0:
            return 1.0
        return self.good_count / self.total_count

    def record(self, is_good: bool = True) -> None:
        self.total_count += 1
        if is_good:
            self.good_count += 1
        self.last_updated = datetime.now(timezone.utc)

    def record_batch(self, good_count: int, total_count: int) -> None:
        self.good_count += good_count
        self.total_count += total_count
        self.last_updated = datetime.now(timezone.utc)


@dataclass
class ErrorBudget:
    total_budget: float
    remaining_budget: float = 0.0
    budget_consumed: float = 0.0
    window_start: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    window_end: Optional[datetime] = None
    burn_rate: float = 0.0

    @property
    def remaining_percent(self) -> float:
        if self.total_budget == 0:
            return 0.0
        return (self.remaining_budget / self.total_budget) * 100

    @property
    def consumed_percent(self) -> float:
        if self.total_budget == 0:
            return 0.0
        return (self.budget_consumed / self.total_budget) * 100


@dataclass
class SLO:
    name: str
    target: float
    window_days: int = 28
    description: str = ""
    sli_metrics: List[SLIMetric] = field(default_factory=list)
    error_budget: Optional[ErrorBudget] = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def add_sli_metric(self, name: str) -> SLIMetric:
        metric = SLIMetric(name=name)
        self.sli_metrics.append(metric)
        return metric

    def get_combined_sli(self) -> float:
        if not self.sli_metrics:
            return 1.0
        total_good = sum(m.good_count for m in self.sli_metrics)
        total = sum(m.total_count for m in self.sli_metrics)
        return total_good / total if total > 0 else 1.0

    def initialize_error_budget(self) -> None:
        total_requests = sum(m.total_count for m in self.sli_metrics) or 1
        error_budget_value = total_requests * (1 - self.target)
        window_start = datetime.now(timezone.utc)
        window_end = window_start + timedelta(days=self.window_days)
        self.error_budget = ErrorBudget(
            total_budget=error_budget_value,
            remaining_budget=error_budget_value,
            window_start=window_start,
            window_end=window_end,
        )


class SLOManager:
    def __init__(
        self,
        event_bus: Optional[EventBus] = None,
        logger=None,
    ):
        self._slos: Dict[str, SLO] = {}
        self._event_bus = event_bus or get_global_event_bus()
        self._logger = logger
        self._alert_rules: List[AlertRule] = [
            AlertRule(name="fast_burn_critical", severity="critical", burn_rate_threshold=14.4, window_minutes=5),
            AlertRule(name="fast_burn_high", severity="high", burn_rate_threshold=6.0, window_minutes=60),
            AlertRule(name="slow_burn_medium", severity="medium", burn_rate_threshold=2.0, window_minutes=360),
        ]
        self._alert_history: Dict[str, List[Dict[str, Any]]] = {}

    def create_slo(
        self,
        name: str,
        target: float,
        window_days: int = 28,
        description: str = "",
        sli_names: Optional[List[str]] = None,
    ) -> SLO:
        slo = SLO(
            name=name,
            target=target,
            window_days=window_days,
            description=description,
        )
        if sli_names:
            for sli_name in sli_names:
                slo.add_sli_metric(sli_name)
        self._slos[name] = slo
        return slo

    def get_slo(self, name: str) -> Optional[SLO]:
        return self._slos.get(name)

    def list_slos(self) -> List[SLO]:
        return list(self._slos.values())

    def record_sli(self, slo_name: str, sli_name: str, is_good: bool = True) -> None:
        slo = self._slos.get(slo_name)
        if not slo:
            return
        metric = None
        for m in slo.sli_metrics:
            if m.name == sli_name:
                metric = m
                break
        if metric is None:
            metric = slo.add_sli_metric(sli_name)
        metric.record(is_good)
        self._update_error_budget(slo)

    def record_sli_batch(self, slo_name: str, sli_name: str, good_count: int, total_count: int) -> None:
        slo = self._slos.get(slo_name)
        if not slo:
            return
        metric = None
        for m in slo.sli_metrics:
            if m.name == sli_name:
                metric = m
                break
        if metric is None:
            metric = slo.add_sli_metric(sli_name)
        metric.record_batch(good_count, total_count)
        self._update_error_budget(slo)

    def _update_error_budget(self, slo: SLO) -> None:
        if slo.error_budget is None:
            slo.initialize_error_budget()
            return
        sli = slo.get_combined_sli()
        total = sum(m.total_count for m in slo.sli_metrics)
        error_count = total - sum(m.good_count for m in slo.sli_metrics)
        slo.error_budget.budget_consumed = error_count
        slo.error_budget.remaining_budget = slo.error_budget.total_budget - error_count

    def get_burn_down_state(self, slo_name: str) -> BurnDownState:
        slo = self._slos.get(slo_name)
        if not slo or not slo.error_budget:
            return BurnDownState.NORMAL
        eb = slo.error_budget
        if eb.remaining_budget <= 0:
            return BurnDownState.EXHAUSTED
        if eb.burn_rate >= 14.4:
            return BurnDownState.FAST_BURN
        if eb.burn_rate >= 2.0:
            return BurnDownState.SLOW_BURN
        return BurnDownState.NORMAL

    def check_alerts(self) -> List[Dict[str, Any]]:
        fired_alerts = []
        for name, slo in self._slos.items():
            state = self.get_burn_down_state(name)
            if state in [BurnDownState.FAST_BURN, BurnDownState.SLOW_BURN]:
                for rule in self._alert_rules:
                    if slo.error_budget and slo.error_budget.burn_rate >= rule.burn_rate_threshold:
                        alert = {
                            "slo_name": name,
                            "rule_name": rule.name,
                            "severity": rule.severity,
                            "burn_rate": slo.error_budget.burn_rate if slo.error_budget else 0,
                            "remaining_percent": slo.error_budget.remaining_percent if slo.error_budget else 100,
                            "state": state.value,
                            "message": rule.message_template.format(
                                burn_rate=slo.error_budget.burn_rate if slo.error_budget else 0,
                                remaining=slo.error_budget.remaining_percent if slo.error_budget else 100,
                            ),
                            "timestamp": datetime.now(timezone.utc).isoformat(),
                        }
                        fired_alerts.append(alert)
                        if name not in self._alert_history:
                            self._alert_history[name] = []
                        self._alert_history[name].append(alert)
                        event = DomainEvent(
                            event_type="slo.alert",
                            payload=alert,
                            source="slo_manager",
                        )
                        asyncio.create_task(self._event_bus.publish(event))
        return fired_alerts

    def get_status(self) -> Dict[str, Any]:
        return {
            "slo_count": len(self._slos),
            "slos": [
                {
                    "name": s.name,
                    "target": s.target,
                    "window_days": s.window_days,
                    "sli": s.get_combined_sli(),
                    "error_budget": {
                        "total": s.error_budget.total_budget if s.error_budget else 0,
                        "remaining": s.error_budget.remaining_budget if s.error_budget else 0,
                        "remaining_percent": s.error_budget.remaining_percent if s.error_budget else 100,
                        "burn_rate": s.error_budget.burn_rate if s.error_budget else 0,
                    } if s.error_budget else None,
                    "state": self.get_burn_down_state(s.name).value,
                }
                for s in self._slos.values()
            ],
        }


_global_slo_manager: Optional[SLOManager] = None


def get_slo_manager() -> SLOManager:
    global _global_slo_manager
    if _global_slo_manager is None:
        _global_slo_manager = SLOManager()
    return _global_slo_manager


def set_slo_manager(manager: SLOManager) -> None:
    global _global_slo_manager
    _global_slo_manager = manager
