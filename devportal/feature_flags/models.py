from datetime import datetime, timezone
from sqlalchemy import Column, String, Integer, DateTime, JSON, Float, Boolean, ForeignKey, Text
from sqlalchemy.orm import relationship

from ..core.database import Base
from ..core.models import CoreEntity, ConfigModel, generate_id


class FeatureFlag(CoreEntity):
    __tablename__ = "feature_flags"

    type = Column(String, nullable=False, default="feature_flag")
    name = Column(String, nullable=False, unique=True, index=True)
    key = Column(String, nullable=False, unique=True, index=True)
    description = Column(Text, nullable=True)
    enabled = Column(Boolean, default=False)
    namespace = Column(String, default="default", index=True)
    rollout_percent = Column(Float, default=0.0)
    rollout_strategy = Column(String, default="incremental")
    target_segments = Column(JSON, default=list)
    rules = Column(JSON, default=list)
    variants = Column(JSON, default=dict)
    default_variant = Column(String, nullable=True)
    start_time = Column(DateTime, nullable=True)
    end_time = Column(DateTime, nullable=True)


class UserSegment(CoreEntity):
    __tablename__ = "user_segments"

    type = Column(String, nullable=False, default="user_segment")
    name = Column(String, nullable=False, unique=True, index=True)
    description = Column(Text, nullable=True)
    namespace = Column(String, default="default", index=True)
    conditions = Column(JSON, default=list)
    user_ids = Column(JSON, default=list)
    attributes = Column(JSON, default=dict)


class RolloutPhase(CoreEntity):
    __tablename__ = "rollout_phases"

    type = Column(String, nullable=False, default="rollout_phase")
    flag_id = Column(String, ForeignKey("feature_flags.id"), nullable=False)
    name = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    start_percent = Column(Float, default=0.0)
    end_percent = Column(Float, default=100.0)
    start_time = Column(DateTime, nullable=False)
    end_time = Column(DateTime, nullable=False)
    criteria = Column(JSON, default=dict)
    status = Column(String, default="scheduled")


class FlagEvaluationLog(Base):
    __tablename__ = "flag_evaluation_logs"

    id = Column(String, primary_key=True, default=lambda: generate_id("log"))
    flag_id = Column(String, ForeignKey("feature_flags.id"), nullable=False)
    flag_key = Column(String, index=True)
    user_id = Column(String, index=True, nullable=True)
    user_context = Column(JSON, default=dict)
    result = Column(Boolean, default=False)
    variant = Column(String, nullable=True)
    reason = Column(String, nullable=True)
    segment_matched = Column(String, nullable=True)
    rollout_percent = Column(Float, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), index=True)
