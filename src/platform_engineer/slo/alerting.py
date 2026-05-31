from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional

from .burnrate import BurnRateCalculator, BurnRateResult


@dataclass
class BurnDownAlert:
    slo_name: str
    severity: str
    burn_rate: float
    window_minutes: int
    remaining_budget_percent: float
    message: str
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    acknowledged: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return {
            "slo_name": self.slo_name,
            "severity": self.severity,
            "burn_rate": self.burn_rate,
            "window_minutes": self.window_minutes,
            "remaining_budget_percent": self.remaining_budget_percent,
            "message": self.message,
            "timestamp": self.timestamp.isoformat(),
            "acknowledged": self.acknowledged,
        }


class BurnDownAlertEngine:
    def __init__(self, calculator: Optional[BurnRateCalculator] = None, logger=None):
        self._calculator = calculator or BurnRateCalculator()
        self._logger = logger
        self._alerts: List[BurnDownAlert] = []
        self._cooldown_period: Dict[str, datetime] = {}
        self._handlers: List[Callable[[BurnDownAlert], Any]] = []
        self._active_alerts: Dict[str, BurnDownAlert] = {}

    def add_handler(self, handler: Callable[[BurnDownAlert], Any]) -> None:
        self._handlers.append(handler)

    def remove_handler(self, handler: Callable[[BurnDownAlert], Any]) -> bool:
        if handler in self._handlers:
            self._handlers.remove(handler)
            return True
        return False

    def check_and_alert(
        self,
        slo_name: str,
        error_counts: Dict[int, int],
        total_counts: Dict[int, int],
        remaining_budget_percent: float,
    ) -> List[BurnDownAlert]:
        results = self._calculator.calculate_multiple_windows(error_counts, total_counts)
        fired_alerts = []
        cooldown_key = slo_name
        now = datetime.now(timezone.utc)
        if cooldown_key in self._cooldown_period:
            if (now - self._cooldown_period[cooldown_key]) < timedelta(minutes=5):
                return []
        for result in results:
            if self._calculator.should_alert(result.burn_rate, result.window_minutes):
                severity = self._get_severity(result.burn_rate, result.window_minutes)
                alert = BurnDownAlert(
                    slo_name=slo_name,
                    severity=severity,
                    burn_rate=result.burn_rate,
                    window_minutes=result.window_minutes,
                    remaining_budget_percent=remaining_budget_percent,
                    message=self._build_message(slo_name, result, remaining_budget_percent),
                )
                self._alerts.append(alert)
                self._active_alerts[f"{slo_name}_{result.window_minutes}"] = alert
                fired_alerts.append(alert)
                for handler in self._handlers:
                    try:
                        import asyncio
                        result_handler = handler(alert)
                        if asyncio.iscoroutine(result_handler):
                            asyncio.create_task(result_handler)
                    except Exception as e:
                        if self._logger:
                            self._logger.error(f"Alert handler error: {e}")
        if fired_alerts:
            self._cooldown_period[cooldown_key] = now
        return fired_alerts

    def _get_severity(self, burn_rate: float, window_minutes: int) -> str:
        if window_minutes <= 5 and burn_rate >= 14.4:
            return "critical"
        if window_minutes <= 60 and burn_rate >= 6.0:
            return "high"
        if window_minutes <= 360 and burn_rate >= 3.0:
            return "medium"
        return "low"

    def _build_message(
        self,
        slo_name: str,
        result: BurnRateResult,
        remaining_percent: float,
    ) -> str:
        return (
            f"SLO '{slo_name}' burn rate alert: {result.burn_rate:.2f}x "
            f"(window: {result.window_minutes}min), "
            f"remaining budget: {remaining_percent:.2f}%"
        )

    def acknowledge_alert(self, slo_name: str, window_minutes: Optional[int] = None) -> bool:
        if window_minutes:
            key = f"{slo_name}_{window_minutes}"
            if key in self._active_alerts:
                self._active_alerts[key].acknowledged = True
                return True
            return False
        found = False
        for key, alert in self._active_alerts.items():
            if key.startswith(f"{slo_name}_"):
                alert.acknowledged = True
                found = True
        return found

    def get_active_alerts(self, slo_name: Optional[str] = None) -> List[BurnDownAlert]:
        if slo_name:
            return [
                alert for key, alert in self._active_alerts.items()
                if key.startswith(f"{slo_name}_")
            ]
        return list(self._active_alerts.values())

    def get_alert_history(self, limit: int = 100) -> List[BurnDownAlert]:
        return self._alerts[-limit:]

    def clear_active_alerts(self, slo_name: Optional[str] = None) -> int:
        if slo_name:
            to_remove = [k for k in self._active_alerts if k.startswith(f"{slo_name}_")]
            count = len(to_remove)
            for k in to_remove:
                del self._active_alerts[k]
            return count
        count = len(self._active_alerts)
        self._active_alerts.clear()
        return count
