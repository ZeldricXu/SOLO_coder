from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import uuid4

from top.core.models import AuditLogEntry, CommandRecord
from top.domain.audit.models import ComplianceReport, CommandQueryResult
from top.domain.audit.stores import AuditLogStore, CommandStore


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class AuditCorrelator:
    def __init__(
        self,
        command_store: CommandStore,
        audit_store: AuditLogStore,
    ):
        self._command_store = command_store
        self._audit_store = audit_store

    async def query_by_correlation(
        self,
        correlation_id: str,
    ) -> CommandQueryResult:
        commands = await self._command_store.get_by_correlation(correlation_id)
        audit_logs = await self._audit_store.query(
            correlation_id=correlation_id,
            limit=500,
        )
        return CommandQueryResult(
            commands=commands,
            audit_logs=audit_logs,
            correlation_id=correlation_id,
        )

    async def query_by_command(self, command_id: str) -> CommandQueryResult:
        command = await self._command_store.get_by_id(command_id)
        commands = [command] if command else []
        audit_logs = await self._audit_store.query(
            command_id=command_id,
            limit=500,
        )
        return CommandQueryResult(
            commands=commands,
            audit_logs=audit_logs,
            command_id=command_id,
        )

    async def check_correlation(self, correlation_id: str) -> Dict[str, Any]:
        commands = await self._command_store.get_by_correlation(correlation_id)
        audit_logs = await self._audit_store.query(
            correlation_id=correlation_id,
            limit=500,
        )

        command_ids = {cmd.command_id for cmd in commands}
        linked_logs = [log for log in audit_logs if log.command_id in command_ids]
        unlinked_logs = [log for log in audit_logs if not log.command_id]

        return {
            "correlation_id": correlation_id,
            "command_count": len(commands),
            "audit_log_count": len(audit_logs),
            "linked_log_count": len(linked_logs),
            "unlinked_log_count": len(unlinked_logs),
            "commands": [cmd.command_id for cmd in commands],
        }

    async def trace_chain(self, correlation_id: str) -> List[Dict[str, Any]]:
        query_result = await self.query_by_correlation(correlation_id)
        chain = []

        for cmd in query_result.commands:
            chain.append({
                "type": "command",
                "id": cmd.command_id,
                "timestamp": cmd.issued_at.isoformat(),
                "command_type": cmd.command_type,
            })

        for log in query_result.audit_logs:
            chain.append({
                "type": "audit",
                "id": log.log_id,
                "timestamp": log.timestamp.isoformat(),
                "action": log.action,
            })

        chain.sort(key=lambda x: x["timestamp"])
        return chain


class ComplianceReporter:
    def __init__(
        self,
        command_store: CommandStore,
        audit_store: AuditLogStore,
        correlator: Optional[AuditCorrelator] = None,
    ):
        self._command_store = command_store
        self._audit_store = audit_store
        self._correlator = correlator or AuditCorrelator(command_store, audit_store)

    async def generate_report(
        self,
        period_start: datetime,
        period_end: datetime,
    ) -> ComplianceReport:
        command_breakdown = await self._command_store.count_by_type(
            period_start, period_end
        )
        action_breakdown = await self._audit_store.count_by_action(
            period_start, period_end
        )
        actor_breakdown = await self._audit_store.count_by_actor(
            period_start, period_end
        )

        total_commands = sum(command_breakdown.values())
        total_audit_logs = sum(action_breakdown.values())

        commands = await self._command_store.list_by_time(
            start=period_start,
            end=period_end,
            limit=5000,
        )

        uncorrelated = 0
        for cmd in commands:
            if cmd.correlation_id:
                check = await self._correlator.check_correlation(cmd.correlation_id)
                if check["unlinked_log_count"] > 0:
                    uncorrelated += 1

        return ComplianceReport(
            report_id=generate_id("report"),
            period_start=period_start,
            period_end=period_end,
            total_commands=total_commands,
            total_audit_logs=total_audit_logs,
            command_breakdown=command_breakdown,
            action_breakdown=action_breakdown,
            actor_breakdown=actor_breakdown,
            uncorrelated_count=uncorrelated,
        )

    async def generate_daily_report(self, report_date: Optional[datetime] = None) -> ComplianceReport:
        from datetime import timedelta

        date = report_date or datetime.now()
        period_start = date.replace(hour=0, minute=0, second=0, microsecond=0)
        period_end = period_start + timedelta(days=1)

        return await self.generate_report(period_start, period_end)

    async def generate_weekly_report(self, week_start: Optional[datetime] = None) -> ComplianceReport:
        from datetime import timedelta

        start = week_start or datetime.now()
        period_start = start - timedelta(days=start.weekday())
        period_start = period_start.replace(hour=0, minute=0, second=0, microsecond=0)
        period_end = period_start + timedelta(days=7)

        return await self.generate_report(period_start, period_end)

    async def check_command_compliance(self, command_id: str) -> Dict[str, Any]:
        command = await self._command_store.get_by_id(command_id)
        if not command:
            return {"compliant": False, "reason": "Command not found"}

        audit_logs = await self._audit_store.query(
            command_id=command_id,
            limit=100,
        )

        has_issued = any(log.action == "command.issued" for log in audit_logs)
        has_result = any(log.action in ["command.executed", "command.failed"] for log in audit_logs)

        return {
            "compliant": has_issued and has_result,
            "command_id": command_id,
            "has_issued_log": has_issued,
            "has_result_log": has_result,
            "audit_count": len(audit_logs),
        }
