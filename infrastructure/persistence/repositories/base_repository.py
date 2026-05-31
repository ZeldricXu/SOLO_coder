from typing import Generic, TypeVar, Type, List, Optional, Any, Dict
from sqlalchemy.orm import Session
from pydantic import BaseModel

ModelType = TypeVar("ModelType")
DomainType = TypeVar("DomainType", bound=BaseModel)


class BaseRepository(Generic[ModelType, DomainType]):
    def __init__(self, db: Session, model: Type[ModelType], domain_class: Type[DomainType]):
        self.db = db
        self.model = model
        self.domain_class = domain_class

    def _to_domain(self, db_obj: ModelType) -> DomainType:
        data = {c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}
        return self.domain_class(**data)

    def _to_db(self, domain_obj: DomainType) -> Dict[str, Any]:
        col_key_map = {c.name: c.key for c in self.model.__table__.columns}
        return {col_key_map.get(k, k): v for k, v in domain_obj.model_dump().items()}

    def get_by_id(self, id: Any) -> Optional[DomainType]:
        db_obj = self.db.query(self.model).filter(self.model.id == id).first()
        return self._to_domain(db_obj) if db_obj else None

    def get_all(self, skip: int = 0, limit: int = 100) -> List[DomainType]:
        db_objs = self.db.query(self.model).offset(skip).limit(limit).all()
        return [self._to_domain(obj) for obj in db_objs]

    def create(self, domain_obj: DomainType) -> DomainType:
        db_obj = self.model(**self._to_db(domain_obj))
        self.db.add(db_obj)
        self.db.commit()
        self.db.refresh(db_obj)
        return self._to_domain(db_obj)

    def update(self, id: Any, update_data: Dict[str, Any]) -> Optional[DomainType]:
        db_obj = self.db.query(self.model).filter(self.model.id == id).first()
        if db_obj:
            col_key_map = {c.name: c.key for c in self.model.__table__.columns}
            for key, value in update_data.items():
                setattr(db_obj, col_key_map.get(key, key), value)
            self.db.commit()
            self.db.refresh(db_obj)
            return self._to_domain(db_obj)
        return None

    def delete(self, id: Any) -> bool:
        db_obj = self.db.query(self.model).filter(self.model.id == id).first()
        if db_obj:
            self.db.delete(db_obj)
            self.db.commit()
            return True
        return False
