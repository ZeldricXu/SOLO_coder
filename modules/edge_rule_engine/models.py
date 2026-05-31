from sqlalchemy import Column, String, JSON, Integer, Boolean
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON

from models import EntityModel, generate_uuid


class Rule(EntityModel):
    __tablename__ = "edge_rules"

    rule_id = Column(String, default=generate_uuid, index=True)
    name = Column(String, nullable=False)
    description = Column(String, nullable=True)
    trigger_type = Column(String, nullable=False)
    trigger_config = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    conditions = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=list)
    actions = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=list)
    priority = Column(Integer, default=0)
    enabled = Column(Boolean, default=True)
    edge_node_id = Column(String, nullable=True, index=True)
