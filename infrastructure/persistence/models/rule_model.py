from sqlalchemy import Column, String, DateTime, JSON, Integer, Boolean
from datetime import datetime

from infrastructure.persistence.database import Base


class RuleModel(Base):
    __tablename__ = "rules"

    rule_id = Column(String, primary_key=True, index=True)
    rule_name = Column(String, nullable=False)
    rule_type = Column(String, nullable=False)
    description = Column(String)

    condition = Column(JSON, default=dict)
    actions = Column(JSON, default=list)

    enabled = Column(Boolean, default=True)
    priority = Column(Integer, default=0)
    trigger_limit = Column(Integer, default=0)
    trigger_count = Column(Integer, default=0)
    cooldown_period = Column(Integer, default=0)
    last_triggered = Column(DateTime)

    device_ids = Column(JSON, default=list)
    device_tags = Column(JSON, default=list)

    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
