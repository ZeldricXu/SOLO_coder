from typing import List, Optional
from sqlalchemy.orm import Session

from domain.models.rule import Rule
from infrastructure.persistence.models.rule_model import RuleModel


class RuleRepository:
    def __init__(self, db: Session):
        self.db = db

    def _to_domain(self, db_obj: RuleModel) -> Rule:
        return Rule(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns})

    def get_all(self, enabled_only: bool = False) -> List[Rule]:
        query = self.db.query(RuleModel)
        if enabled_only:
            query = query.filter(RuleModel.enabled == True)
        return [self._to_domain(obj) for obj in query.all()]

    def get_by_id(self, rule_id: str) -> Optional[Rule]:
        db_obj = self.db.query(RuleModel).filter(RuleModel.rule_id == rule_id).first()
        return self._to_domain(db_obj) if db_obj else None

    def create(self, rule: Rule) -> Rule:
        col_key_map = {c.name: c.key for c in RuleModel.__table__.columns}
        db_obj = RuleModel(**{col_key_map.get(k, k): v for k, v in rule.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return rule

    def update(self, rule_id: str, update_data: dict) -> Optional[Rule]:
        db_obj = self.db.query(RuleModel).filter(RuleModel.rule_id == rule_id).first()
        if db_obj:
            col_key_map = {c.name: c.key for c in RuleModel.__table__.columns}
            for key, value in update_data.items():
                setattr(db_obj, col_key_map.get(key, key), value)
            self.db.commit()
            return self._to_domain(db_obj)
        return None

    def delete(self, rule_id: str) -> bool:
        db_obj = self.db.query(RuleModel).filter(RuleModel.rule_id == rule_id).first()
        if db_obj:
            self.db.delete(db_obj)
            self.db.commit()
            return True
        return False
