from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional


@dataclass
class BurnRateResult:
    burn_rate: float
    window_minutes: int
    error_count: int
    total_count: int
    error_rate: float
    target_error_rate: float
    timestamp: datetime


class BurnRateCalculator:
    def __init__(self, slo_target: float = 0.999):
        self._slo_target = slo_target
        self._target_error_rate = 1 - slo_target
        self._windows = [5, 60, 360, 1440]

    def calculate(
        self,
        errors_window: int,
        total_window: int,
        window_minutes: int,
    ) -> BurnRateResult:
        if total_window == 0:
            return BurnRateResult(
                burn_rate=0.0,
                window_minutes=window_minutes,
                error_count=0,
                total_count=0,
                error_rate=0.0,
                target_error_rate=self._target_error_rate,
                timestamp=datetime.now(timezone.utc),
            )
        error_rate = errors_window / total_window
        burn_rate = error_rate / self._target_error_rate if self._target_error_rate > 0 else 0
        return BurnRateResult(
            burn_rate=burn_rate,
            window_minutes=window_minutes,
            error_count=errors_window,
            total_count=total_window,
            error_rate=error_rate,
            target_error_rate=self._target_error_rate,
            timestamp=datetime.now(timezone.utc),
        )

    def calculate_multiple_windows(
        self,
        error_counts: Dict[int, int],
        total_counts: Dict[int, int],
    ) -> List[BurnRateResult]:
        results = []
        for window in self._windows:
            errors = error_counts.get(window, 0)
            total = total_counts.get(window, 0)
            results.append(self.calculate(errors, total, window))
        return results

    def should_alert(self, burn_rate: float, window_minutes: int) -> bool:
        thresholds = {
            5: 14.4,
            60: 6.0,
            360: 3.0,
            1440: 1.0,
        }
        threshold = thresholds.get(window_minutes, 2.0)
        return burn_rate >= threshold

    def get_slo_target(self) -> float:
        return self._slo_target

    def set_slo_target(self, target: float) -> None:
        self._slo_target = max(0.0, min(1.0, target))
        self._target_error_rate = 1 - self._slo_target
