from typing import Any, Dict, Generic, List, Optional, Type, TypeVar, Union
from sqlalchemy import select, func, and_, or_, desc, asc
from sqlalchemy.orm import Session
from fastapi import HTTPException, status

from app.schemas.common import PaginatedResponse
from app.core.cache import cache
from app.core.config import settings

ModelType = TypeVar("ModelType")
CreateSchemaType = TypeVar("CreateSchemaType")
UpdateSchemaType = TypeVar("UpdateSchemaType")


class CRUDBase(Generic[ModelType, CreateSchemaType, UpdateSchemaType]):
    def __init__(self, model: Type[ModelType], cache_prefix: str = ""):
        self.model = model
        self.cache_prefix = cache_prefix or model.__tablename__

    def _get_cache_key(self, id: int) -> str:
        return f"{self.cache_prefix}:{id}"

    def _get_list_cache_key(self, **filters) -> str:
        filter_str = "&".join(f"{k}={v}" for k, v in sorted(filters.items()))
        return f"{self.cache_prefix}:list:{filter_str}"

    def get(
        self,
        db: Session,
        id: int,
        use_cache: bool = True,
    ) -> Optional[ModelType]:
        cache_key = self._get_cache_key(id)
        if use_cache:
            cached = cache.get(cache_key)
            if cached is not None:
                return cached

        stmt = select(self.model).where(self.model.id == id)
        result = db.execute(stmt).scalar_one_or_none()

        if result and use_cache:
            cache.set(cache_key, result, ttl=settings.CACHE_TTL_DEFAULT)

        return result

    def get_or_404(
        self,
        db: Session,
        id: int,
        use_cache: bool = True,
    ) -> ModelType:
        result = self.get(db=db, id=id, use_cache=use_cache)
        if not result:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"{self.model.__name__} with id {id} not found",
            )
        return result

    def get_multi(
        self,
        db: Session,
        *,
        page: int = 1,
        page_size: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
        filters: Optional[Dict[str, Any]] = None,
        search_filters: Optional[List[Any]] = None,
        use_cache: bool = False,
    ) -> PaginatedResponse[ModelType]:
        cache_key = None
        if use_cache:
            cache_filters = dict(filters or {})
            cache_filters.update({
                "page": page,
                "page_size": page_size,
                "sort_by": sort_by,
                "sort_order": sort_order,
            })
            cache_key = self._get_list_cache_key(**cache_filters)
            cached = cache.get(cache_key)
            if cached is not None:
                return cached

        stmt = select(self.model)
        count_stmt = select(func.count()).select_from(self.model)

        where_conditions = []
        if filters:
            for key, value in filters.items():
                if value is None:
                    continue
                if hasattr(self.model, key):
                    if isinstance(value, str) and "%" in value:
                        where_conditions.append(getattr(self.model, key).like(value))
                    elif isinstance(value, list):
                        where_conditions.append(getattr(self.model, key).in_(value))
                    else:
                        where_conditions.append(getattr(self.model, key) == value)

        if search_filters:
            where_conditions.append(or_(*search_filters))

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        total = db.execute(count_stmt).scalar_one() or 0

        if sort_by and hasattr(self.model, sort_by):
            sort_column = getattr(self.model, sort_by)
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(self.model.id))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        items = list(db.execute(stmt).scalars().all())

        total_pages = (total + page_size - 1) // page_size

        result = PaginatedResponse[ModelType](
            items=items,
            page=page,
            page_size=page_size,
            total=total,
            total_pages=total_pages,
            has_next=page < total_pages,
            has_prev=page > 1,
        )

        if use_cache and cache_key:
            cache.set(cache_key, result, ttl=settings.CACHE_TTL_SHORT)

        return result

    def create(
        self,
        db: Session,
        *,
        obj_in: CreateSchemaType,
        extra_data: Optional[Dict[str, Any]] = None,
    ) -> ModelType:
        obj_data = obj_in.model_dump() if hasattr(obj_in, "model_dump") else dict(obj_in)
        if extra_data:
            obj_data.update(extra_data)

        db_obj = self.model(**obj_data)
        db.add(db_obj)
        db.flush()
        db.refresh(db_obj)

        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def update(
        self,
        db: Session,
        *,
        db_obj: ModelType,
        obj_in: Union[UpdateSchemaType, Dict[str, Any]],
    ) -> ModelType:
        old_data = {c.name: getattr(db_obj, c.name) for c in db_obj.__table__.columns}

        if isinstance(obj_in, dict):
            update_data = obj_in
        else:
            update_data = obj_in.model_dump(exclude_unset=True) if hasattr(obj_in, "model_dump") else dict(obj_in)

        for field, value in update_data.items():
            if hasattr(db_obj, field):
                setattr(db_obj, field, value)

        db.flush()
        db.refresh(db_obj)

        cache.delete(self._get_cache_key(db_obj.id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def delete(
        self,
        db: Session,
        *,
        id: int,
        soft_delete: bool = False,
    ) -> ModelType:
        db_obj = self.get_or_404(db=db, id=id, use_cache=False)

        old_data = {c.name: getattr(db_obj, c.name) for c in db_obj.__table__.columns}

        if soft_delete and hasattr(db_obj, "is_active"):
            setattr(db_obj, "is_active", False)
            db.flush()
            db.refresh(db_obj)
        else:
            db.delete(db_obj)
            db.flush()

        cache.delete(self._get_cache_key(id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def bulk_delete(
        self,
        db: Session,
        *,
        ids: List[int],
        soft_delete: bool = False,
    ) -> Dict[str, Any]:
        success_count = 0
        failed_count = 0
        failed_ids: List[int] = []
        errors: List[Dict[str, Any]] = []

        for id in ids:
            try:
                self.delete(db=db, id=id, soft_delete=soft_delete)
                success_count += 1
            except HTTPException as e:
                failed_count += 1
                failed_ids.append(id)
                errors.append({"id": id, "error": e.detail})
            except Exception as e:
                failed_count += 1
                failed_ids.append(id)
                errors.append({"id": id, "error": str(e)})

        db.flush()

        return {
            "success_count": success_count,
            "failed_count": failed_count,
            "failed_ids": failed_ids,
            "errors": errors,
        }

    def bulk_update(
        self,
        db: Session,
        *,
        updates: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        success_count = 0
        failed_count = 0
        failed_ids: List[int] = []
        errors: List[Dict[str, Any]] = []

        for update_data in updates:
            id = update_data.get("id")
            if not id:
                failed_count += 1
                errors.append({"error": "Missing id in update data"})
                continue

            try:
                db_obj = self.get_or_404(db=db, id=id, use_cache=False)
                self.update(db=db, db_obj=db_obj, obj_in=update_data)
                success_count += 1
            except HTTPException as e:
                failed_count += 1
                failed_ids.append(id)
                errors.append({"id": id, "error": e.detail})
            except Exception as e:
                failed_count += 1
                failed_ids.append(id)
                errors.append({"id": id, "error": str(e)})

        db.flush()

        return {
            "success_count": success_count,
            "failed_count": failed_count,
            "failed_ids": failed_ids,
            "errors": errors,
        }

    def exists(
        self,
        db: Session,
        *,
        filters: Dict[str, Any],
        exclude_id: Optional[int] = None,
    ) -> bool:
        stmt = select(func.count()).select_from(self.model)

        where_conditions = []
        for key, value in filters.items():
            if hasattr(self.model, key):
                where_conditions.append(getattr(self.model, key) == value)

        if exclude_id:
            where_conditions.append(self.model.id != exclude_id)

        if where_conditions:
            stmt = stmt.where(and_(*where_conditions))

        count = db.execute(stmt).scalar_one() or 0
        return count > 0

    def count(
        self,
        db: Session,
        *,
        filters: Optional[Dict[str, Any]] = None,
    ) -> int:
        stmt = select(func.count()).select_from(self.model)

        if filters:
            where_conditions = []
            for key, value in filters.items():
                if hasattr(self.model, key):
                    if isinstance(value, list):
                        where_conditions.append(getattr(self.model, key).in_(value))
                    else:
                        where_conditions.append(getattr(self.model, key) == value)

            if where_conditions:
                stmt = stmt.where(and_(*where_conditions))

        return db.execute(stmt).scalar_one() or 0
