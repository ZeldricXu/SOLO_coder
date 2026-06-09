from typing import Any, Dict, List, Optional
from sqlalchemy import select, func, and_
from sqlalchemy.orm import Session, joinedload
from fastapi import HTTPException, status

from app.services.crud_base import CRUDBase
from app.models.category import Category
from app.models.product import Product
from app.schemas.product import CategoryCreate, CategoryUpdate


class CategoryService(CRUDBase[Category, CategoryCreate, CategoryUpdate]):
    def __init__(self):
        super().__init__(Category, cache_prefix="category")

    def _calculate_level(self, db: Session, parent_id: Optional[int]) -> int:
        if not parent_id:
            return 1
        parent = self.get(db=db, id=parent_id, use_cache=True)
        if not parent:
            return 1
        return parent.level + 1

    def _check_circular_dependency(self, db: Session, category_id: int, parent_id: Optional[int]) -> bool:
        if not parent_id:
            return False
        if parent_id == category_id:
            return True

        current_parent_id = parent_id
        visited = set()

        while current_parent_id:
            if current_parent_id in visited:
                return True
            if current_parent_id == category_id:
                return True
            visited.add(current_parent_id)

            parent = self.get(db=db, id=current_parent_id, use_cache=True)
            if not parent:
                break
            current_parent_id = parent.parent_id

        return False

    def create(self, db: Session, *, obj_in: CategoryCreate) -> Category:
        if self.exists(db, filters={"code": obj_in.code}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Category code {obj_in.code} already exists",
            )

        if obj_in.parent_id:
            parent = self.get(db=db, id=obj_in.parent_id, use_cache=True)
            if not parent:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"Parent category with id {obj_in.parent_id} not found",
                )

        level = self._calculate_level(db, obj_in.parent_id)
        extra_data = {"level": level}

        return super().create(db, obj_in=obj_in, extra_data=extra_data)

    def update(self, db: Session, *, db_obj: Category, obj_in: CategoryUpdate) -> Category:
        update_data = obj_in.model_dump(exclude_unset=True)

        if "code" in update_data and update_data["code"] != db_obj.code:
            if self.exists(db, filters={"code": update_data["code"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Category code {update_data['code']} already exists",
                )

        if "parent_id" in update_data and update_data["parent_id"] != db_obj.parent_id:
            new_parent_id = update_data["parent_id"]

            if self._check_circular_dependency(db, db_obj.id, new_parent_id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Circular dependency detected in category hierarchy",
                )

            if new_parent_id:
                parent = self.get(db=db, id=new_parent_id, use_cache=True)
                if not parent:
                    raise HTTPException(
                        status_code=status.HTTP_404_NOT_FOUND,
                        detail=f"Parent category with id {new_parent_id} not found",
                    )

            update_data["level"] = self._calculate_level(db, new_parent_id)
            self._update_children_level(db, db_obj.id, update_data["level"] + 1)

        return super().update(db, db_obj=db_obj, obj_in=update_data)

    def _update_children_level(self, db: Session, parent_id: int, new_level: int) -> None:
        children = db.execute(select(Category).where(Category.parent_id == parent_id)).scalars().all()
        for child in children:
            child.level = new_level
            db.flush()
            self._update_children_level(db, child.id, new_level + 1)

    def get_tree(self, db: Session, *, parent_id: Optional[int] = None) -> List[Dict[str, Any]]:
        stmt = (
            select(Category)
            .options(joinedload(Category.children))
            .where(Category.parent_id == parent_id)
            .order_by(Category.sort_order, Category.name)
        )

        categories = list(db.execute(stmt).unique().scalars().all())
        return [self._build_tree_node(cat) for cat in categories]

    def _build_tree_node(self, category: Category) -> Dict[str, Any]:
        return {
            "id": category.id,
            "name": category.name,
            "code": category.code,
            "description": category.description,
            "parent_id": category.parent_id,
            "level": category.level,
            "sort_order": category.sort_order,
            "is_active": category.is_active,
            "created_at": category.created_at,
            "updated_at": category.updated_at,
            "children": [self._build_tree_node(child) for child in category.children],
        }

    def get_descendant_ids(self, db: Session, *, category_id: int) -> List[int]:
        descendant_ids: List[int] = []

        def collect_children(parent_id: int) -> None:
            children = db.execute(
                select(Category.id).where(Category.parent_id == parent_id)
            ).scalars().all()
            for child_id in children:
                descendant_ids.append(child_id)
                collect_children(child_id)

        collect_children(category_id)
        return descendant_ids

    def get_with_children(self, db: Session, *, id: int) -> Category:
        stmt = (
            select(Category)
            .options(joinedload(Category.children))
            .where(Category.id == id)
        )

        result = db.execute(stmt).unique().scalar_one_or_none()
        if not result:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Category with id {id} not found",
            )
        return result

    def get_list_with_product_count(
        self,
        db: Session,
        *,
        page: int = 1,
        page_size: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
        filters: Optional[Dict[str, Any]] = None,
        search_filters: Optional[List[Any]] = None,
    ):
        from app.schemas.common import PaginatedResponse
        from app.schemas.product import CategoryWithProductCount
        from sqlalchemy import desc, asc, or_

        stmt = (
            select(
                Category,
                func.count(Product.id).label("product_count"),
            )
            .outerjoin(Product, Category.id == Product.category_id)
        )

        count_stmt = select(func.count()).select_from(Category)

        where_conditions = []
        if filters:
            for key, value in filters.items():
                if value is None:
                    continue
                if hasattr(Category, key):
                    if isinstance(value, str) and "%" in value:
                        where_conditions.append(getattr(Category, key).like(value))
                    elif isinstance(value, list):
                        where_conditions.append(getattr(Category, key).in_(value))
                    else:
                        where_conditions.append(getattr(Category, key) == value)

        if search_filters:
            where_conditions.append(or_(*search_filters))

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        stmt = stmt.group_by(Category.id)
        total = db.execute(count_stmt).scalar_one() or 0

        if sort_by:
            if hasattr(Category, sort_by):
                sort_column = getattr(Category, sort_by)
            elif sort_by == "product_count":
                sort_column = func.count(Product.id)
            else:
                sort_column = Category.id
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(Category.id))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        results = db.execute(stmt).all()
        items = []
        for category, product_count in results:
            cat_dict = {c.name: getattr(category, c.name) for c in category.__table__.columns}
            cat_dict["product_count"] = product_count
            items.append(CategoryWithProductCount.model_validate(cat_dict))

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

    def get_active_categories(self, db: Session) -> List[Category]:
        stmt = select(Category).where(Category.is_active == True).order_by(Category.sort_order, Category.name)
        return list(db.execute(stmt).scalars().all())


category_service = CategoryService()
