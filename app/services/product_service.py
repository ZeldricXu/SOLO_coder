from typing import Any, Dict, List, Optional
from sqlalchemy import select, func, and_
from sqlalchemy.orm import Session
from fastapi import HTTPException, status

from app.services.crud_base import CRUDBase
from app.models.product import Product
from app.models.sku import SKU
from app.models.inventory import Inventory
from app.models.attribute import AttributeTemplate
from app.schemas.product import ProductCreate, ProductUpdate
from app.core.cache import cache


class ProductService(CRUDBase[Product, ProductCreate, ProductUpdate]):
    def __init__(self):
        super().__init__(Product, cache_prefix="product")

    def create(self, db: Session, *, obj_in: ProductCreate) -> Product:
        if self.exists(db, filters={"name": obj_in.name}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Product name already exists",
            )

        if obj_in.barcode and self.exists(db, filters={"barcode": obj_in.barcode}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Barcode already exists",
            )

        product_data = obj_in.model_dump(exclude={"attribute_template_id", "attributes"})
        db_obj = Product(**product_data)

        if obj_in.attribute_template_id:
            template = db.execute(
                select(AttributeTemplate).where(AttributeTemplate.id == obj_in.attribute_template_id)
            ).scalar_one_or_none()
            if template:
                db_obj.attributes = template.attributes

        if obj_in.attributes:
            db_obj.attributes = obj_in.attributes

        db.add(db_obj)
        db.flush()
        db.refresh(db_obj)

        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return db_obj

    def get_with_details(self, db: Session, *, id: int) -> Dict[str, Any]:
        stmt = (
            select(
                Product,
                func.count(SKU.id).label("sku_count"),
                func.coalesce(func.sum(Inventory.quantity), 0).label("total_stock"),
            )
            .outerjoin(SKU, Product.id == SKU.product_id)
            .outerjoin(Inventory, SKU.id == Inventory.sku_id)
            .where(Product.id == id)
            .group_by(Product.id)
        )

        result = db.execute(stmt).first()
        if not result:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Product with id {id} not found",
            )

        product, sku_count, total_stock = result

        return {
            **{c.name: getattr(product, c.name) for c in product.__table__.columns},
            "sku_count": sku_count,
            "total_stock": total_stock,
        }

    def get_list_with_details(
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
        from app.schemas.product import ProductWithSkuCount
        from sqlalchemy import desc, asc, or_

        stmt = (
            select(
                Product,
                func.count(SKU.id).label("sku_count"),
                func.coalesce(func.sum(Inventory.quantity), 0).label("total_stock"),
            )
            .outerjoin(SKU, Product.id == SKU.product_id)
            .outerjoin(Inventory, SKU.id == Inventory.sku_id)
        )

        count_stmt = select(func.count()).select_from(Product)

        where_conditions = []
        if filters:
            for key, value in filters.items():
                if value is None:
                    continue
                if hasattr(Product, key):
                    if isinstance(value, str) and "%" in value:
                        where_conditions.append(getattr(Product, key).like(value))
                    elif isinstance(value, list):
                        where_conditions.append(getattr(Product, key).in_(value))
                    else:
                        where_conditions.append(getattr(Product, key) == value)

        if search_filters:
            where_conditions.append(or_(*search_filters))

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        stmt = stmt.group_by(Product.id)
        total = db.execute(count_stmt).scalar_one() or 0

        if sort_by:
            if hasattr(Product, sort_by):
                sort_column = getattr(Product, sort_by)
            elif sort_by == "sku_count":
                sort_column = func.count(SKU.id)
            elif sort_by == "total_stock":
                sort_column = func.coalesce(func.sum(Inventory.quantity), 0)
            else:
                sort_column = Product.id
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(Product.id))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        results = db.execute(stmt).all()
        items = []
        for product, sku_count, total_stock in results:
            product_dict = {c.name: getattr(product, c.name) for c in product.__table__.columns}
            product_dict["sku_count"] = sku_count
            product_dict["total_stock"] = total_stock
            items.append(ProductWithSkuCount.model_validate(product_dict))

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

    def apply_attribute_template(
        self,
        db: Session,
        *,
        product_id: int,
        template_id: int,
        override_existing: bool = False,
    ) -> Product:
        product = self.get_or_404(db, id=product_id, use_cache=False)
        template = db.execute(
            select(AttributeTemplate).where(AttributeTemplate.id == template_id)
        ).scalar_one_or_none()

        if not template:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Attribute template with id {template_id} not found",
            )

        template_attrs = template.attributes if isinstance(template.attributes, list) else []
        existing_attrs = product.attributes or []

        if override_existing:
            merged_attrs = template_attrs
        else:
            existing_codes = {
                (a.get("code") or a.get("attribute_code"))
                for a in existing_attrs
                if isinstance(a, dict)
            }
            merged_attrs = list(existing_attrs)
            for attr in template_attrs:
                attr_code = attr.get("code") or attr.get("attribute_code")
                if attr_code not in existing_codes:
                    merged_attrs.append(attr)

        product.attributes = merged_attrs
        db.flush()
        db.refresh(product)

        cache.delete(self._get_cache_key(product_id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return product

    def get_product_skus(
        self,
        db: Session,
        *,
        product_id: int,
    ) -> List[SKU]:
        product = self.get_or_404(db, id=product_id, use_cache=True)
        return product.skus


product_service = ProductService()
