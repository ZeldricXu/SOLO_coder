from typing import List, Optional
from sqlalchemy import select, or_, and_, desc, asc
from sqlalchemy.orm import Session
from fastapi import HTTPException, status

from app.services.crud_base import CRUDBase
from app.models.user import User, user_role
from app.models.role import Role
from app.schemas.user import UserCreate, UserUpdate
from app.core.security import get_password_hash, verify_password
from app.core.cache import cache


class UserService(CRUDBase[User, UserCreate, UserUpdate]):
    def __init__(self):
        super().__init__(User, cache_prefix="user")

    def get_by_username(self, db: Session, username: str) -> Optional[User]:
        stmt = select(User).where(User.username == username)
        return db.execute(stmt).scalar_one_or_none()

    def get_by_email(self, db: Session, email: str) -> Optional[User]:
        stmt = select(User).where(User.email == email)
        return db.execute(stmt).scalar_one_or_none()

    def get_by_phone(self, db: Session, phone: str) -> Optional[User]:
        stmt = select(User).where(User.phone == phone)
        return db.execute(stmt).scalar_one_or_none()

    def create(self, db: Session, *, obj_in: UserCreate) -> User:
        if self.exists(db, filters={"username": obj_in.username}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Username already exists",
            )
        if self.exists(db, filters={"email": obj_in.email}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Email already exists",
            )
        if obj_in.phone and self.exists(db, filters={"phone": obj_in.phone}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Phone already exists",
            )

        hashed_password = get_password_hash(obj_in.password)
        user_data = obj_in.model_dump(exclude={"password", "role_ids"})
        user_data["hashed_password"] = hashed_password

        db_obj = User(**user_data)

        if obj_in.role_ids:
            roles = db.execute(select(Role).where(Role.id.in_(obj_in.role_ids))).scalars().all()
            db_obj.roles = roles

        db.add(db_obj)
        db.flush()
        db.refresh(db_obj)

        return db_obj

    def update(self, db: Session, *, db_obj: User, obj_in: UserUpdate) -> User:
        update_data = obj_in.model_dump(exclude_unset=True)

        if "username" in update_data and update_data["username"] != db_obj.username:
            if self.exists(db, filters={"username": update_data["username"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Username already exists",
                )

        if "email" in update_data and update_data["email"] != db_obj.email:
            if self.exists(db, filters={"email": update_data["email"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Email already exists",
                )

        if "phone" in update_data and update_data["phone"] and update_data["phone"] != db_obj.phone:
            if self.exists(db, filters={"phone": update_data["phone"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Phone already exists",
                )

        if "role_ids" in update_data and update_data["role_ids"] is not None:
            role_ids = update_data.pop("role_ids")
            roles = db.execute(select(Role).where(Role.id.in_(role_ids))).scalars().all()
            db_obj.roles = roles

        return super().update(db, db_obj=db_obj, obj_in=update_data)

    def assign_roles(self, db: Session, *, user_id: int, role_ids: List[int]) -> User:
        db_obj = self.get_or_404(db, id=user_id, use_cache=False)
        roles = db.execute(select(Role).where(Role.id.in_(role_ids))).scalars().all()

        if len(roles) != len(role_ids):
            found_ids = {r.id for r in roles}
            missing_ids = set(role_ids) - found_ids
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Roles not found: {missing_ids}",
            )

        db_obj.roles = roles
        db.flush()
        db.refresh(db_obj)

        cache.delete(self._get_cache_key(user_id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def reset_password(self, db: Session, *, user_id: int, new_password: str) -> User:
        db_obj = self.get_or_404(db, id=user_id, use_cache=False)
        db_obj.hashed_password = get_password_hash(new_password)
        db.flush()
        db.refresh(db_obj)

        cache.delete(self._get_cache_key(user_id))

        return db_obj

    def change_password(
        self,
        db: Session,
        *,
        user_id: int,
        old_password: str,
        new_password: str,
    ) -> User:
        db_obj = self.get_or_404(db, id=user_id, use_cache=False)

        if not verify_password(old_password, db_obj.hashed_password):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Old password is incorrect",
            )

        db_obj.hashed_password = get_password_hash(new_password)
        db.flush()
        db.refresh(db_obj)

        cache.delete(self._get_cache_key(user_id))

        return db_obj

    def toggle_status(self, db: Session, *, user_id: int, is_active: bool) -> User:
        db_obj = self.get_or_404(db, id=user_id, use_cache=False)
        db_obj.is_active = is_active
        db.flush()
        db.refresh(db_obj)

        cache.delete(self._get_cache_key(user_id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def authenticate(self, db: Session, *, username: str, password: str) -> Optional[User]:
        user = self.get_by_username(db, username=username)
        if not user:
            user = self.get_by_email(db, email=username)
        if not user:
            return None
        if not verify_password(password, user.hashed_password):
            return None
        return user

    def get_with_role_count(self, db: Session, *, page: int = 1, page_size: int = 20, sort_by: Optional[str] = None, sort_order: str = "desc", filters: Optional[dict] = None, search_filters: Optional[list] = None):
        from app.schemas.common import PaginatedResponse
        from sqlalchemy import func

        stmt = select(User, func.count(user_role.c.role_id).label("role_count")).outerjoin(user_role, User.id == user_role.c.user_id).group_by(User.id)
        count_stmt = select(func.count()).select_from(User)

        where_conditions = []
        if filters:
            for key, value in filters.items():
                if value is None:
                    continue
                if hasattr(User, key):
                    if isinstance(value, str) and "%" in value:
                        where_conditions.append(getattr(User, key).like(value))
                    elif isinstance(value, list):
                        where_conditions.append(getattr(User, key).in_(value))
                    else:
                        where_conditions.append(getattr(User, key) == value)

        if search_filters:
            where_conditions.append(or_(*search_filters))

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        total = db.execute(count_stmt).scalar_one() or 0

        if sort_by and hasattr(User, sort_by):
            sort_column = getattr(User, sort_by)
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(User.id))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        results = db.execute(stmt).all()
        items = []
        for user, role_count in results:
            user_dict = {c.name: getattr(user, c.name) for c in user.__table__.columns}
            user_dict["role_count"] = role_count
            from app.schemas.user import UserWithRoleCount
            items.append(UserWithRoleCount.model_validate(user_dict))

        total_pages = (total + page_size - 1) // page_size

        return PaginatedResponse(
            items=items,
            page=page,
            page_size=page_size,
            total=total,
            total_pages=total_pages,
            has_next=page < total_pages,
            has_prev=page > 1,
        )


user_service = UserService()
