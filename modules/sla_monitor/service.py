from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, utc_now
from .models import (
    SLAPolicy,
    SLAPolicyCreate,
    SLAPolicyResponse,
    SLATracker,
    SLATrackerCreate,
    SLATrackerResponse,
    SLAEvent,
    SLAEventResponse,
    SLASeverity,
    SLATargetType,
    EscalationLevel,
)


class SLAPolicyService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_policy(self, policy_data: SLAPolicyCreate) -> SLAPolicyResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "target_duration_seconds": lambda x: x is not None and x > 0,
            "warning_threshold_percent": lambda x: 0 < x < 100,
            "critical_threshold_percent": lambda x: 0 < x < 100,
        }
        validate_params(policy_data.model_dump(), validation_rules)

        if policy_data.warning_threshold_percent >= policy_data.critical_threshold_percent:
            raise ValidationError("警告阈值必须小于严重阈值")

        policy = SLAPolicy(**policy_data.model_dump())
        self.db.add(policy)
        await self.db.flush()

        return SLAPolicyResponse.model_validate(policy)

    async def get_policy(self, policy_id: str, tenant_id: Optional[str] = None) -> SLAPolicyResponse:
        query = select(SLAPolicy).where(SLAPolicy.policy_id == policy_id)
        if tenant_id:
            query = query.where(SLAPolicy.tenant_id == tenant_id)

        result = await self.db.execute(query)
        policy = result.scalar_one_or_none()

        if not policy:
            raise NotFoundError(f"SLA策略 {policy_id} 不存在")

        return SLAPolicyResponse.model_validate(policy)

    async def get_active_policies(
        self,
        target_type: Optional[SLATargetType] = None,
        tenant_id: Optional[str] = None,
    ) -> List[SLAPolicyResponse]:
        query = select(SLAPolicy).where(SLAPolicy.is_active == True)
        if target_type:
            query = query.where(SLAPolicy.target_type == target_type)
        if tenant_id:
            query = query.where(SLAPolicy.tenant_id == tenant_id)

        result = await self.db.execute(query)
        policies = result.scalars().all()

        return [SLAPolicyResponse.model_validate(p) for p in policies]


class SLATrackerService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.policy_service = SLAPolicyService(db)

    def calculate_elapsed_time(
        self, tracker: SLATracker, current_time: Optional[datetime] = None
    ) -> int:
        now = current_time or utc_now()

        if tracker.is_paused and tracker.paused_at:
            effective_end = tracker.paused_at
        elif tracker.is_completed and tracker.completed_at:
            effective_end = tracker.completed_at
        else:
            effective_end = now

        elapsed = (effective_end - tracker.start_time).total_seconds()
        return int(elapsed) - tracker.paused_duration_seconds

    def calculate_remaining_time(
        self, tracker: SLATracker, current_time: Optional[datetime] = None
    ) -> int:
        elapsed = self.calculate_elapsed_time(tracker, current_time)
        total = (tracker.deadline - tracker.start_time).total_seconds()
        return max(0, int(total - elapsed))

    def calculate_progress(
        self, tracker: SLATracker, current_time: Optional[datetime] = None
    ) -> float:
        total = (tracker.deadline - tracker.start_time).total_seconds()
        if total <= 0:
            return 100.0
        elapsed = self.calculate_elapsed_time(tracker, current_time)
        return min(100.0, (elapsed / total) * 100)

    def determine_severity(
        self, tracker: SLATracker, progress: float
    ) -> SLASeverity:
        if tracker.is_completed:
            return SLASeverity.OK if progress <= 100 else SLASeverity.BREACHED

        if progress >= 100:
            return SLASeverity.BREACHED
        elif progress >= tracker.critical_threshold_percent:
            return SLASeverity.CRITICAL
        elif progress >= tracker.warning_threshold_percent:
            return SLASeverity.WARNING
        else:
            return SLASeverity.OK

    async def create_tracker(self, tracker_data: SLATrackerCreate) -> SLATrackerResponse:
        validation_rules = {
            "entity_id": lambda x: x is not None and len(x) > 0,
            "policy_id": lambda x: x is not None and len(x) > 0,
        }
        validate_params(tracker_data.model_dump(), validation_rules)

        policy = await self.policy_service.get_policy(
            tracker_data.policy_id, tracker_data.tenant_id
        )

        start_time = tracker_data.start_time or utc_now()
        deadline = start_time + timedelta(seconds=policy.target_duration_seconds)

        tracker = SLATracker(
            **tracker_data.model_dump(exclude={"start_time"}),
            start_time=start_time,
            deadline=deadline,
            warning_threshold_percent=policy.warning_threshold_percent,
            critical_threshold_percent=policy.critical_threshold_percent,
        )
        self.db.add(tracker)
        await self.db.flush()

        return self._build_tracker_response(tracker)

    def _build_tracker_response(self, tracker: SLATracker) -> SLATrackerResponse:
        elapsed = self.calculate_elapsed_time(tracker)
        remaining = self.calculate_remaining_time(tracker)
        progress = self.calculate_progress(tracker)
        severity = self.determine_severity(tracker, progress)

        return SLATrackerResponse(
            tracker_id=tracker.tracker_id,
            entity_id=tracker.entity_id,
            entity_type=tracker.entity_type,
            policy_id=tracker.policy_id,
            start_time=tracker.start_time,
            deadline=tracker.deadline,
            current_status=severity,
            current_escalation_level=tracker.current_escalation_level,
            is_paused=tracker.is_paused,
            is_completed=tracker.is_completed,
            completed_at=tracker.completed_at,
            breach_count=tracker.breach_count,
            tenant_id=tracker.tenant_id,
            elapsed_seconds=elapsed,
            remaining_seconds=remaining,
            progress_percent=round(progress, 2),
            metadata=tracker.metadata,
        )

    async def get_tracker(
        self, tracker_id: str, tenant_id: Optional[str] = None
    ) -> SLATrackerResponse:
        query = select(SLATracker).where(SLATracker.tracker_id == tracker_id)
        if tenant_id:
            query = query.where(SLATracker.tenant_id == tenant_id)

        result = await self.db.execute(query)
        tracker = result.scalar_one_or_none()

        if not tracker:
            raise NotFoundError(f"SLA追踪器 {tracker_id} 不存在")

        return self._build_tracker_response(tracker)

    async def pause_tracker(
        self, tracker_id: str, tenant_id: Optional[str] = None
    ) -> SLATrackerResponse:
        query = select(SLATracker).where(SLATracker.tracker_id == tracker_id)
        if tenant_id:
            query = query.where(SLATracker.tenant_id == tenant_id)

        result = await self.db.execute(query)
        tracker = result.scalar_one_or_none()

        if not tracker:
            raise NotFoundError(f"SLA追踪器 {tracker_id} 不存在")

        if tracker.is_paused:
            raise ConflictError("追踪器已暂停")

        if tracker.is_completed:
            raise ConflictError("追踪器已完成，无法暂停")

        tracker.is_paused = True
        tracker.paused_at = utc_now()
        self.db.add(tracker)
        await self.db.flush()

        return self._build_tracker_response(tracker)

    async def resume_tracker(
        self, tracker_id: str, tenant_id: Optional[str] = None
    ) -> SLATrackerResponse:
        query = select(SLATracker).where(SLATracker.tracker_id == tracker_id)
        if tenant_id:
            query = query.where(SLATracker.tenant_id == tenant_id)

        result = await self.db.execute(query)
        tracker = result.scalar_one_or_none()

        if not tracker:
            raise NotFoundError(f"SLA追踪器 {tracker_id} 不存在")

        if not tracker.is_paused:
            raise ConflictError("追踪器未暂停")

        if tracker.paused_at:
            paused_duration = int((utc_now() - tracker.paused_at).total_seconds())
            tracker.paused_duration_seconds += paused_duration
            tracker.deadline = tracker.deadline + timedelta(seconds=paused_duration)

        tracker.is_paused = False
        tracker.resumed_at = utc_now()
        tracker.paused_at = None
        self.db.add(tracker)
        await self.db.flush()

        return self._build_tracker_response(tracker)

    async def complete_tracker(
        self, tracker_id: str, tenant_id: Optional[str] = None
    ) -> SLATrackerResponse:
        query = select(SLATracker).where(SLATracker.tracker_id == tracker_id)
        if tenant_id:
            query = query.where(SLATracker.tenant_id == tenant_id)

        result = await self.db.execute(query)
        tracker = result.scalar_one_or_none()

        if not tracker:
            raise NotFoundError(f"SLA追踪器 {tracker_id} 不存在")

        if tracker.is_completed:
            raise ConflictError("追踪器已完成")

        tracker.is_completed = True
        tracker.completed_at = utc_now()

        progress = self.calculate_progress(tracker)
        if progress > 100:
            tracker.breach_count += 1

        self.db.add(tracker)
        await self.db.flush()

        return self._build_tracker_response(tracker)

    async def check_and_update_sla(
        self, tracker: SLATracker
    ) -> Tuple[SLATrackerResponse, Optional[SLAEvent]]:
        progress = self.calculate_progress(tracker)
        current_severity = self.determine_severity(tracker, progress)

        event = None
        if current_severity != tracker.current_status:
            tracker.current_status = current_severity

            if current_severity in [SLASeverity.WARNING, SLASeverity.CRITICAL, SLASeverity.BREACHED]:
                event = await self._create_escalation_event(tracker, current_severity)

            self.db.add(tracker)
            await self.db.flush()

        return self._build_tracker_response(tracker), event

    async def _create_escalation_event(
        self, tracker: SLATracker, severity: SLASeverity
    ) -> SLAEvent:
        levels = list(EscalationLevel)
        current_idx = levels.index(tracker.current_escalation_level)

        if severity == SLASeverity.BREACHED and current_idx < len(levels) - 1:
            tracker.current_escalation_level = levels[current_idx + 1]
        elif severity == SLASeverity.CRITICAL and current_idx < 1:
            tracker.current_escalation_level = levels[1]

        event = SLAEvent(
            tracker_id=tracker.tracker_id,
            event_type=f"sla_{severity.value}",
            severity=severity,
            description=f"SLA {severity.value} 触发: 实体 {tracker.entity_id}",
            elapsed_seconds=self.calculate_elapsed_time(tracker),
            remaining_seconds=self.calculate_remaining_time(tracker),
            escalation_level=tracker.current_escalation_level,
            tenant_id=tracker.tenant_id,
        )
        self.db.add(event)
        await self.db.flush()

        return event

    async def get_active_trackers(
        self,
        entity_id: Optional[str] = None,
        tenant_id: Optional[str] = None,
        status: Optional[SLASeverity] = None,
    ) -> List[SLATrackerResponse]:
        query = select(SLATracker).where(SLATracker.is_completed == False)
        if entity_id:
            query = query.where(SLATracker.entity_id == entity_id)
        if tenant_id:
            query = query.where(SLATracker.tenant_id == tenant_id)
        if status:
            query = query.where(SLATracker.current_status == status)

        result = await self.db.execute(query)
        trackers = result.scalars().all()

        return [self._build_tracker_response(t) for t in trackers]

    async def get_tracker_events(
        self, tracker_id: str, tenant_id: Optional[str] = None
    ) -> List[SLAEventResponse]:
        query = select(SLAEvent).where(SLAEvent.tracker_id == tracker_id)
        if tenant_id:
            query = query.where(SLAEvent.tenant_id == tenant_id)

        query = query.order_by(SLAEvent.timestamp.desc())
        result = await self.db.execute(query)
        events = result.scalars().all()

        return [SLAEventResponse.model_validate(e) for e in events]


class SLAMonitorService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.policy_service = SLAPolicyService(db)
        self.tracker_service = SLATrackerService(db)

    async def run_sla_check(self) -> List[Dict[str, Any]]:
        query = select(SLATracker).where(
            and_(SLATracker.is_completed == False, SLATracker.is_paused == False)
        )
        result = await self.db.execute(query)
        trackers = result.scalars().all()

        triggered_events = []
        for tracker in trackers:
            _, event = await self.tracker_service.check_and_update_sla(tracker)
            if event:
                triggered_events.append(
                    {
                        "tracker_id": tracker.tracker_id,
                        "event_id": event.event_id,
                        "severity": event.severity,
                        "escalation_level": event.escalation_level,
                    }
                )

        return triggered_events
