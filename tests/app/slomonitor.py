from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from .exceptions import ValidationError, NotFoundError, DatabaseError

@dataclass
class SLO:
    id: str
    name: str
    service_name: str
    sli: str
    target_percent: float
    error_budget: float
    burn_rate: float = 0.0
    window_days: int = 30
    remaining_budget: Optional[float] = None
    total_requests: int = 0
    failed_requests: int = 0
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)

    def __post_init__(self):
        if self.remaining_budget is None:
            self.remaining_budget = self.error_budget

@dataclass
class MetricEvent:
    service_name: str
    sli: str
    success: bool
    timestamp: datetime = field(default_factory=datetime.utcnow)

class SLOMonitor:
    def __init__(self, db_session=None, alerter=None):
        self.db_session = db_session
        self.alerter = alerter
        self._slos: Dict[str, SLO] = {}
        self._service_slos: Dict[str, Dict[str, List[SLO]]] = {}
        self._alerts_fired: List[Dict[str, Any]] = []

    def _validate_slo(self, slo: SLO, for_update: bool = False) -> None:
        if not for_update:
            if not slo.name or not isinstance(slo.name, str):
                raise ValidationError("name", "SLO name is required")
            if len(slo.name) > 255:
                raise ValidationError("name", "SLO name must be less than 255 characters")
            if not slo.service_name or not isinstance(slo.service_name, str):
                raise ValidationError("service_name", "Service name is required")
            if not slo.sli or not isinstance(slo.sli, str):
                raise ValidationError("sli", "SLI indicator is required")

        if not isinstance(slo.target_percent, (int, float)):
            raise ValidationError("target_percent", "Target percent must be a number")
        if slo.target_percent <= 0 or slo.target_percent > 100:
            raise ValidationError("target_percent", "Target percent must be between 0 and 100")

        if not isinstance(slo.error_budget, (int, float)):
            raise ValidationError("error_budget", "Error budget must be a number")
        if slo.error_budget < 0 or slo.error_budget > 1:
            raise ValidationError("error_budget", "Error budget must be between 0 and 1")

        if not isinstance(slo.window_days, int):
            raise ValidationError("window_days", "Window days must be an integer")
        if slo.window_days <= 0 or slo.window_days > 365:
            raise ValidationError("window_days", "Window days must be between 1 and 365")

    def create_slo(self, slo_data: Dict[str, Any]) -> SLO:
        slo = SLO(
            id=slo_data.get("id") or str(uuid.uuid4()),
            name=slo_data["name"],
            service_name=slo_data["service_name"],
            sli=slo_data["sli"],
            target_percent=float(slo_data["target_percent"]),
            error_budget=float(slo_data["error_budget"]),
            window_days=int(slo_data.get("window_days", 30)),
        )

        self._validate_slo(slo)

        if slo.id in self._slos:
            raise ValidationError("id", f"SLO with id {slo.id} already exists")

        if self.db_session:
            try:
                self.db_session.add(slo)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("create slo", e)

        self._slos[slo.id] = slo
        if slo.service_name not in self._service_slos:
            self._service_slos[slo.service_name] = {}
        if slo.sli not in self._service_slos[slo.service_name]:
            self._service_slos[slo.service_name][slo.sli] = []
        self._service_slos[slo.service_name][slo.sli].append(slo)

        return slo

    def get_slo(self, slo_id: str) -> SLO:
        slo = self._slos.get(slo_id)
        if not slo:
            raise NotFoundError("SLO", slo_id)
        return slo

    def list_slos(self, service_name: Optional[str] = None) -> List[SLO]:
        slos = list(self._slos.values())
        if service_name:
            slos = [s for s in slos if s.service_name == service_name]
        return sorted(slos, key=lambda s: s.created_at, reverse=True)

    def update_slo(self, slo_id: str, update_data: Dict[str, Any]) -> SLO:
        slo = self.get_slo(slo_id)

        for key, value in update_data.items():
            if hasattr(slo, key):
                setattr(slo, key, value)

        self._validate_slo(slo, for_update=True)
        slo.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update slo", e)

        return slo

    def delete_slo(self, slo_id: str) -> None:
        slo = self.get_slo(slo_id)

        if self.db_session:
            try:
                self.db_session.delete(slo)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("delete slo", e)

        del self._slos[slo_id]
        if (slo.service_name in self._service_slos and
            slo.sli in self._service_slos[slo.service_name]):
            self._service_slos[slo.service_name][slo.sli] = [
                s for s in self._service_slos[slo.service_name][slo.sli] if s.id != slo_id
            ]

    def record_metric(self, event: MetricEvent) -> None:
        slos = self._find_slos(event.service_name, event.sli)
        for slo in slos:
            self._update_slo_with_event(slo, event)

    def _find_slos(self, service_name: str, sli: str) -> List[SLO]:
        if service_name in self._service_slos and sli in self._service_slos[service_name]:
            return self._service_slos[service_name][sli]
        return []

    def _update_slo_with_event(self, slo: SLO, event: MetricEvent) -> None:
        slo.total_requests += 1
        if not event.success:
            slo.failed_requests += 1

        if slo.total_requests > 0:
            error_rate = slo.failed_requests / slo.total_requests
            slo.remaining_budget = max(0.0, slo.error_budget - error_rate)

            expected_budget = slo.error_budget * slo.window_days
            if expected_budget > 0:
                actual_consumed = slo.error_budget - slo.remaining_budget
                slo.burn_rate = actual_consumed / expected_budget

        slo.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update slo metrics", e)

        if slo.remaining_budget <= 0:
            self._fire_burn_alert(slo)

    def _fire_burn_alert(self, slo: SLO) -> None:
        alert_data = {
            "rule_name": "slo_burn_rate_exceeded",
            "labels": {
                "service": slo.service_name,
                "slo": slo.name,
                "sli": slo.sli,
                "severity": "critical",
            },
            "value": slo.burn_rate,
            "timestamp": datetime.utcnow(),
        }
        self._alerts_fired.append(alert_data)

        if self.alerter:
            try:
                self.alerter.fire_alert(
                    rule_name="slo_burn_rate_exceeded",
                    labels=alert_data["labels"],
                    value=slo.burn_rate,
                )
            except Exception as e:
                raise DatabaseError("fire alert", e)

    def get_slo_status(self, slo_id: str) -> Dict[str, Any]:
        slo = self.get_slo(slo_id)

        sli_value = 0.0
        if slo.total_requests > 0:
            sli_value = 100.0 * (1.0 - slo.failed_requests / slo.total_requests)

        return {
            "slo_id": slo.id,
            "name": slo.name,
            "service": slo.service_name,
            "sli": slo.sli,
            "sli_value": round(sli_value, 4),
            "target_percent": slo.target_percent,
            "error_budget": slo.error_budget,
            "remaining_budget": round(slo.remaining_budget, 6),
            "burn_rate": round(slo.burn_rate, 4),
            "total_requests": slo.total_requests,
            "failed_requests": slo.failed_requests,
            "budget_exhausted": slo.remaining_budget <= 0,
            "budget_remaining_percent": round((slo.remaining_budget / slo.error_budget) * 100, 2) if slo.error_budget > 0 else 0,
        }

    def reset_budget(self, slo_id: str) -> None:
        slo = self.get_slo(slo_id)

        slo.remaining_budget = slo.error_budget
        slo.total_requests = 0
        slo.failed_requests = 0
        slo.burn_rate = 0.0
        slo.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("reset budget", e)

    def _create_slo_instance(self, slo_data: Dict[str, Any]) -> SLO:
        return SLO(
            id=slo_data.get("id") or str(uuid.uuid4()),
            name=slo_data["name"],
            service_name=slo_data["service_name"],
            sli=slo_data["sli"],
            target_percent=float(slo_data["target_percent"]),
            error_budget=float(slo_data["error_budget"]),
            window_days=int(slo_data.get("window_days", 30)),
        )

    def check_high_burn_rates(self, threshold: float = 1.0) -> List[SLO]:
        return [
            slo for slo in self._slos.values()
            if slo.remaining_budget < 0.2 * slo.error_budget and slo.burn_rate > threshold
        ]
