import hashlib
import random
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional, Tuple
from sqlalchemy import select, func, delete, Integer
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.exceptions import NotFoundError, ConflictError, ValidationError
from ..core.utils import generate_id, utc_now, processing_context
from .models import FeatureFlag, UserSegment, RolloutPhase, FlagEvaluationLog
from .schemas import (
    FeatureFlagCreate,
    FeatureFlagUpdate,
    UserSegmentCreate,
    UserSegmentUpdate,
    RolloutPhaseCreate,
    RolloutPhaseUpdate,
    EvaluationRequest,
    EvaluationResponse,
    Condition,
    Rule,
)


class RuleEngine:
    @staticmethod
    def evaluate_condition(condition: Condition, context: Dict[str, Any]) -> bool:
        field_value = context.get(condition.field)
        op = condition.operator.lower()
        target_value = condition.value

        if op == "eq" or op == "==":
            return field_value == target_value
        elif op == "ne" or op == "!=":
            return field_value != target_value
        elif op == "gt" or op == ">":
            return field_value is not None and field_value > target_value
        elif op == "gte" or op == ">=":
            return field_value is not None and field_value >= target_value
        elif op == "lt" or op == "<":
            return field_value is not None and field_value < target_value
        elif op == "lte" or op == "<=":
            return field_value is not None and field_value <= target_value
        elif op == "in":
            return field_value in target_value if isinstance(target_value, list) else False
        elif op == "not_in":
            return field_value not in target_value if isinstance(target_value, list) else True
        elif op == "contains":
            return isinstance(field_value, str) and target_value in field_value
        elif op == "starts_with":
            return isinstance(field_value, str) and field_value.startswith(str(target_value))
        elif op == "ends_with":
            return isinstance(field_value, str) and field_value.endswith(str(target_value))
        elif op == "regex":
            import re
            return isinstance(field_value, str) and bool(re.match(str(target_value), field_value))
        elif op == "is_none":
            return field_value is None
        elif op == "is_not_none":
            return field_value is not None
        return False

    @staticmethod
    def evaluate_conditions(conditions: List[Condition], context: Dict[str, Any]) -> bool:
        return all(RuleEngine.evaluate_condition(c, context) for c in conditions)

    @staticmethod
    def evaluate_rules(rules: List[Rule], context: Dict[str, Any]) -> Optional[Rule]:
        for rule in rules:
            if rule.enabled and RuleEngine.evaluate_conditions(rule.conditions, context):
                return rule
        return None


class SegmentService:
    @staticmethod
    def user_in_segment(
        segment: UserSegment, user_id: Optional[str], context: Dict[str, Any]
    ) -> bool:
        if user_id and user_id in (segment.user_ids or []):
            return True
        if segment.conditions and RuleEngine.evaluate_conditions(segment.conditions, context):
            return True
        if segment.attributes and RuleEngine.evaluate_conditions(segment.conditions, context):
            return True
        return False


class RolloutService:
    @staticmethod
    def calculate_percentage(flag_key: str, user_id: Optional[str], context: Dict[str, Any]) -> float:
        hash_input = f"{flag_key}:{user_id or context.get('session_id', 'anonymous')}"
        hash_val = int(hashlib.md5(hash_input.encode()).hexdigest(), 16)
        return (hash_val % 10000) / 100.0

    @staticmethod
    def get_effective_rollout_percent(flag: FeatureFlag, now: datetime) -> float:
        if flag.start_time and now < flag.start_time:
            return 0.0
        if flag.end_time and now > flag.end_time:
            return 100.0 if flag.enabled else 0.0
        return flag.rollout_percent

    @staticmethod
    async def get_active_phases(
        db: AsyncSession, flag_id: str, now: datetime
    ) -> List[RolloutPhase]:
        result = await db.execute(
            select(RolloutPhase).where(
                RolloutPhase.flag_id == flag_id,
                RolloutPhase.start_time <= now,
                RolloutPhase.end_time >= now,
                RolloutPhase.status == "active",
            )
        )
        return list(result.scalars().all())

    @staticmethod
    async def update_scheduled_phases(db: AsyncSession) -> int:
        now = utc_now()
        result = await db.execute(
            select(RolloutPhase).where(
                RolloutPhase.status == "scheduled",
                RolloutPhase.start_time <= now,
            )
        )
        phases = list(result.scalars().all())
        for phase in phases:
            phase.status = "active"
        await db.commit()
        return len(phases)


class FeatureFlagService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_flag(self, flag_in: FeatureFlagCreate) -> FeatureFlag:
        result = await self.db.execute(
            select(FeatureFlag).where(
                (FeatureFlag.key == flag_in.key) | (FeatureFlag.name == flag_in.name)
            )
        )
        if result.scalar_one_or_none():
            raise ConflictError("Flag with this key or name already exists")

        flag = FeatureFlag(
            id=generate_id("flag"),
            **flag_in.model_dump(exclude={"variants"}),
            variants={k: v.model_dump() for k, v in flag_in.variants.items()},
            status="active",
        )
        self.db.add(flag)
        await self.db.commit()
        await self.db.refresh(flag)
        return flag

    async def get_flag(self, flag_id: str) -> FeatureFlag:
        result = await self.db.execute(select(FeatureFlag).where(FeatureFlag.id == flag_id))
        flag = result.scalar_one_or_none()
        if not flag:
            raise NotFoundError(f"Feature flag {flag_id} not found")
        return flag

    async def get_flag_by_key(self, key: str) -> FeatureFlag:
        result = await self.db.execute(select(FeatureFlag).where(FeatureFlag.key == key))
        flag = result.scalar_one_or_none()
        if not flag:
            raise NotFoundError(f"Feature flag with key {key} not found")
        return flag

    async def list_flags(
        self,
        namespace: Optional[str] = None,
        enabled: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[FeatureFlag], int]:
        query = select(FeatureFlag)
        if namespace:
            query = query.where(FeatureFlag.namespace == namespace)
        if enabled is not None:
            query = query.where(FeatureFlag.enabled == enabled)

        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()

        result = await self.db.execute(query.offset(skip).limit(limit).order_by(FeatureFlag.created_at.desc()))
        return list(result.scalars().all()), total

    async def update_flag(self, flag_id: str, flag_in: FeatureFlagUpdate) -> FeatureFlag:
        flag = await self.get_flag(flag_id)
        update_data = flag_in.model_dump(exclude_unset=True, exclude={"variants"})
        for key, value in update_data.items():
            setattr(flag, key, value)
        if flag_in.variants is not None:
            flag.variants = {k: v.model_dump() for k, v in flag_in.variants.items()}
        await self.db.commit()
        await self.db.refresh(flag)
        return flag

    async def delete_flag(self, flag_id: str) -> None:
        flag = await self.get_flag(flag_id)
        await self.db.execute(delete(RolloutPhase).where(RolloutPhase.flag_id == flag_id))
        await self.db.execute(delete(FlagEvaluationLog).where(FlagEvaluationLog.flag_id == flag_id))
        await self.db.delete(flag)
        await self.db.commit()

    async def evaluate_flag(
        self,
        request: EvaluationRequest,
        log_evaluation: bool = True,
    ) -> EvaluationResponse:
        async with processing_context() as ctx:
            now = utc_now()
            evaluation_id = generate_id("eval")
            context = request.context or {}
            if request.user_id:
                context["user_id"] = request.user_id

            try:
                flag = await self.get_flag_by_key(request.flag_key)
                ctx.metrics.increment("evaluations.total")
            except NotFoundError:
                ctx.metrics.increment("evaluations.not_found")
                return EvaluationResponse(
                    flag_key=request.flag_key,
                    enabled=False,
                    value=request.default_value,
                    variant=None,
                    reason="flag_not_found",
                    evaluation_id=evaluation_id,
                    timestamp=now,
                )

            if not flag.enabled:
                if log_evaluation:
                    await self._log_evaluation(flag, request, False, None, "flag_disabled", None, 0.0)
                return EvaluationResponse(
                    flag_key=request.flag_key,
                    enabled=False,
                    value=request.default_value,
                    variant=None,
                    reason="flag_disabled",
                    evaluation_id=evaluation_id,
                    timestamp=now,
                )

            if flag.start_time and now < flag.start_time:
                if log_evaluation:
                    await self._log_evaluation(flag, request, False, None, "not_started", None, 0.0)
                return EvaluationResponse(
                    flag_key=request.flag_key,
                    enabled=False,
                    value=request.default_value,
                    variant=None,
                    reason="not_started",
                    evaluation_id=evaluation_id,
                    timestamp=now,
                )

            if flag.end_time and now > flag.end_time:
                if log_evaluation:
                    await self._log_evaluation(flag, request, False, None, "expired", None, 100.0)
                return EvaluationResponse(
                    flag_key=request.flag_key,
                    enabled=False,
                    value=request.default_value,
                    variant=None,
                    reason="expired",
                    evaluation_id=evaluation_id,
                    timestamp=now,
                )

            segment_matched: Optional[str] = None
            if flag.target_segments:
                segments = await self._get_segments(flag.target_segments)
                for segment in segments:
                    if SegmentService.user_in_segment(segment, request.user_id, context):
                        segment_matched = segment.name
                        ctx.metrics.increment("evaluations.segment_matched")
                        break
                if not segment_matched and flag.target_segments:
                    if log_evaluation:
                        await self._log_evaluation(flag, request, False, None, "segment_not_matched", None, 0.0)
                    return EvaluationResponse(
                        flag_key=request.flag_key,
                        enabled=False,
                        value=request.default_value,
                        variant=None,
                        reason="segment_not_matched",
                        evaluation_id=evaluation_id,
                        timestamp=now,
                    )

            if flag.rules:
                matched_rule = RuleEngine.evaluate_rules(flag.rules, context)
                if matched_rule:
                    ctx.metrics.increment("evaluations.rule_matched")
                    variant = matched_rule.variant
                    value = flag.variants.get(variant, {}).get("value", True) if variant else True
                    if log_evaluation:
                        await self._log_evaluation(
                            flag, request, True, variant, f"rule_matched:{matched_rule.name}",
                            segment_matched, flag.rollout_percent
                        )
                    return EvaluationResponse(
                        flag_key=request.flag_key,
                        enabled=True,
                        value=value,
                        variant=variant,
                        reason=f"rule_matched:{matched_rule.name}",
                        segment_matched=segment_matched,
                        rollout_percent=flag.rollout_percent,
                        evaluation_id=evaluation_id,
                        timestamp=now,
                    )

            effective_percent = RolloutService.calculate_percentage(
                flag.key, request.user_id, context
            )
            rollout_percent = RolloutService.get_effective_rollout_percent(flag, now)

            if effective_percent > rollout_percent:
                if log_evaluation:
                    await self._log_evaluation(
                        flag, request, False, None, "rollout_excluded", segment_matched, rollout_percent
                    )
                return EvaluationResponse(
                    flag_key=request.flag_key,
                    enabled=False,
                    value=request.default_value,
                    variant=None,
                    reason="rollout_excluded",
                    segment_matched=segment_matched,
                    rollout_percent=rollout_percent,
                    evaluation_id=evaluation_id,
                    timestamp=now,
                )

            variant = flag.default_variant
            if flag.variants:
                variant = self._select_variant(flag.variants, effective_percent)

            value = flag.variants.get(variant, {}).get("value", True) if variant else True

            if log_evaluation:
                await self._log_evaluation(
                    flag, request, True, variant, "rollout_included", segment_matched, rollout_percent
                )
            ctx.metrics.increment("evaluations.enabled")

            return EvaluationResponse(
                flag_key=request.flag_key,
                enabled=True,
                value=value,
                variant=variant,
                reason="rollout_included",
                segment_matched=segment_matched,
                rollout_percent=rollout_percent,
                evaluation_id=evaluation_id,
                timestamp=now,
            )

    async def batch_evaluate(
        self, flag_keys: List[str], user_id: Optional[str], context: Dict[str, Any]
    ) -> Dict[str, EvaluationResponse]:
        results = {}
        for key in flag_keys:
            req = EvaluationRequest(flag_key=key, user_id=user_id, context=context)
            results[key] = await self.evaluate_flag(req)
        return results

    async def _get_segments(self, segment_ids: List[str]) -> List[UserSegment]:
        result = await self.db.execute(
            select(UserSegment).where(UserSegment.id.in_(segment_ids))
        )
        return list(result.scalars().all())

    def _select_variant(self, variants: Dict[str, Any], percent: float) -> Optional[str]:
        if not variants:
            return None
        total_weight = sum(v.get("weight", 1.0) for v in variants.values())
        if total_weight <= 0:
            return next(iter(variants.keys()))

        cumulative = 0.0
        normalized_percent = (percent / 100.0) * total_weight
        for name, variant in variants.items():
            cumulative += variant.get("weight", 1.0)
            if normalized_percent < cumulative:
                return name
        return next(iter(variants.keys()))

    async def _log_evaluation(
        self,
        flag: FeatureFlag,
        request: EvaluationRequest,
        result: bool,
        variant: Optional[str],
        reason: str,
        segment_matched: Optional[str],
        rollout_percent: float,
    ) -> None:
        log = FlagEvaluationLog(
            flag_id=flag.id,
            flag_key=flag.key,
            user_id=request.user_id,
            user_context=request.context,
            result=result,
            variant=variant,
            reason=reason,
            segment_matched=segment_matched,
            rollout_percent=rollout_percent,
        )
        self.db.add(log)
        await self.db.commit()

    async def get_flag_stats(self, flag_id: str) -> Dict[str, Any]:
        flag = await self.get_flag(flag_id)
        now = utc_now()
        day_ago = now - timedelta(days=1)

        result = await self.db.execute(
            select(
                func.count(FlagEvaluationLog.id),
                func.sum(func.cast(FlagEvaluationLog.result, Integer)),
                func.count(func.distinct(FlagEvaluationLog.user_id)),
            ).where(
                FlagEvaluationLog.flag_id == flag_id,
                FlagEvaluationLog.created_at >= day_ago,
            )
        )
        total_24h, enabled_24h, unique_users = result.one()

        total_result = await self.db.execute(
            select(
                func.count(FlagEvaluationLog.id),
                func.sum(func.cast(FlagEvaluationLog.result, Integer)),
            ).where(FlagEvaluationLog.flag_id == flag_id)
        )
        total_all, enabled_all = total_result.one()

        variant_result = await self.db.execute(
            select(
                FlagEvaluationLog.variant,
                func.count(FlagEvaluationLog.id),
            ).where(
                FlagEvaluationLog.flag_id == flag_id,
                FlagEvaluationLog.result == True,
            ).group_by(FlagEvaluationLog.variant)
        )
        variant_distribution = {v: c for v, c in variant_result.all() if v}

        return {
            "flag_id": flag.id,
            "flag_key": flag.key,
            "total_evaluations": total_all or 0,
            "enabled_count": enabled_all or 0,
            "disabled_count": (total_all or 0) - (enabled_all or 0),
            "variant_distribution": variant_distribution,
            "last_24h_count": total_24h or 0,
            "unique_users": unique_users or 0,
        }


class UserSegmentCRUD:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create(self, segment_in: UserSegmentCreate) -> UserSegment:
        result = await self.db.execute(
            select(UserSegment).where(UserSegment.name == segment_in.name)
        )
        if result.scalar_one_or_none():
            raise ConflictError("Segment with this name already exists")

        segment = UserSegment(
            id=generate_id("seg"),
            **segment_in.model_dump(),
            status="active",
        )
        self.db.add(segment)
        await self.db.commit()
        await self.db.refresh(segment)
        return segment

    async def get(self, segment_id: str) -> UserSegment:
        result = await self.db.execute(select(UserSegment).where(UserSegment.id == segment_id))
        segment = result.scalar_one_or_none()
        if not segment:
            raise NotFoundError(f"User segment {segment_id} not found")
        return segment

    async def list(
        self, namespace: Optional[str] = None, skip: int = 0, limit: int = 100
    ) -> Tuple[List[UserSegment], int]:
        query = select(UserSegment)
        if namespace:
            query = query.where(UserSegment.namespace == namespace)

        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()

        result = await self.db.execute(query.offset(skip).limit(limit).order_by(UserSegment.created_at.desc()))
        return list(result.scalars().all()), total

    async def update(
        self, segment_id: str, segment_in: UserSegmentUpdate
    ) -> UserSegment:
        segment = await self.get(segment_id)
        update_data = segment_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(segment, key, value)
        await self.db.commit()
        await self.db.refresh(segment)
        return segment

    async def delete(self, segment_id: str) -> None:
        segment = await self.get(segment_id)
        await self.db.delete(segment)
        await self.db.commit()

    async def evaluate(
        self, segment_id: str, user_id: Optional[str], context: Dict[str, Any]
    ) -> bool:
        segment = await self.get(segment_id)
        return SegmentService.user_in_segment(segment, user_id, context)


class RolloutPhaseCRUD:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create(self, phase_in: RolloutPhaseCreate) -> RolloutPhase:
        phase = RolloutPhase(
            id=generate_id("phase"),
            **phase_in.model_dump(),
            type="rollout_phase",
        )
        self.db.add(phase)
        await self.db.commit()
        await self.db.refresh(phase)
        return phase

    async def get(self, phase_id: str) -> RolloutPhase:
        result = await self.db.execute(select(RolloutPhase).where(RolloutPhase.id == phase_id))
        phase = result.scalar_one_or_none()
        if not phase:
            raise NotFoundError(f"Rollout phase {phase_id} not found")
        return phase

    async def list_for_flag(self, flag_id: str) -> List[RolloutPhase]:
        result = await self.db.execute(
            select(RolloutPhase).where(RolloutPhase.flag_id == flag_id).order_by(RolloutPhase.start_time)
        )
        return list(result.scalars().all())

    async def update(
        self, phase_id: str, phase_in: RolloutPhaseUpdate
    ) -> RolloutPhase:
        phase = await self.get(phase_id)
        update_data = phase_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(phase, key, value)
        await self.db.commit()
        await self.db.refresh(phase)
        return phase

    async def delete(self, phase_id: str) -> None:
        phase = await self.get(phase_id)
        await self.db.delete(phase)
        await self.db.commit()

    async def create_schedule(
        self, flag_id: str, phases: List[RolloutPhaseCreate]
    ) -> List[RolloutPhase]:
        created = []
        for phase_in in phases:
            phase_in.flag_id = flag_id
            created.append(await self.create(phase_in))
        return created
