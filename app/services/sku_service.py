import itertools
import hashlib
from typing import Any, Dict, List, Optional, Tuple
from sqlalchemy import select, func, and_
from sqlalchemy.orm import Session
from fastapi import HTTPException, status

from app.services.crud_base import CRUDBase
from app.models.sku import SKU, SkuStatus, SkuLifecycleStatus
from app.models.product import Product
from app.models.attribute import AttributeTemplate
from app.models.inventory import Inventory
from app.schemas.product import SkuCreate, SkuUpdate, SkuGenerateRequest
from app.core.cache import cache
from app.core.config import settings


class SkuService(CRUDBase[SKU, SkuCreate, SkuUpdate]):
    def __init__(self):
        super().__init__(SKU, cache_prefix="sku")
        self.lifecycle_transitions: Dict[SkuLifecycleStatus, List[SkuLifecycleStatus]] = {
            SkuLifecycleStatus.CONCEPT: [SkuLifecycleStatus.SAMPLE, SkuLifecycleStatus.END_OF_LIFE],
            SkuLifecycleStatus.SAMPLE: [SkuLifecycleStatus.CONCEPT, SkuLifecycleStatus.PRODUCTION, SkuLifecycleStatus.END_OF_LIFE],
            SkuLifecycleStatus.PRODUCTION: [SkuLifecycleStatus.SAMPLE, SkuLifecycleStatus.END_OF_LIFE],
            SkuLifecycleStatus.END_OF_LIFE: [],
        }

    def _generate_sku_code(self, product_id: int, attributes: Dict[str, Any], prefix: Optional[str] = None) -> str:
        attr_parts = []
        for key in sorted(attributes.keys()):
            value = str(attributes[key])
            attr_parts.append(f"{key}:{value}")

        attr_str = "|".join(attr_parts)
        hash_suffix = hashlib.md5(attr_str.encode()).hexdigest()[:8].upper()

        base_code = f"PRD{product_id:06d}"
        if prefix:
            base_code = f"{prefix.upper()}{product_id:06d}"

        return f"{base_code}-{hash_suffix}"

    def _get_cartesian_combinations(self, attributes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        if not attributes:
            return []

        attr_lists = []
        for attr_item in attributes:
            attr_code = attr_item["attribute_code"]
            attr_name = attr_item["attribute_name"]
            values = attr_item["values"]
            attr_lists.append([(attr_code, attr_name, v) for v in values])

        combinations = list(itertools.product(*attr_lists))

        result = []
        for combo in combinations:
            attr_dict = {}
            for attr_code, attr_name, value in combo:
                attr_dict[attr_code] = {
                    "value": value,
                    "name": attr_name,
                }
            result.append(attr_dict)

        return result

    def _can_transition(self, current: SkuLifecycleStatus, target: SkuLifecycleStatus) -> bool:
        return target in self.lifecycle_transitions.get(current, [])

    def generate_skus(
        self,
        db: Session,
        *,
        request: SkuGenerateRequest,
    ) -> Dict[str, Any]:
        product = db.execute(select(Product).where(Product.id == request.product_id)).scalar_one_or_none()
        if not product:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Product with id {request.product_id} not found",
            )

        attribute_dicts = [item.model_dump() for item in request.attributes]
        combinations = self._get_cartesian_combinations(attribute_dicts)

        if not combinations:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="No attribute combinations generated",
            )

        generated_skus: List[SKU] = []
        errors: List[Dict[str, Any]] = []

        for attrs in combinations:
            try:
                sku_code = self._generate_sku_code(
                    product_id=request.product_id,
                    attributes=attrs,
                    prefix=request.prefix,
                )

                existing = db.execute(select(SKU).where(SKU.sku_code == sku_code)).scalar_one_or_none()
                if existing:
                    errors.append({
                        "attributes": attrs,
                        "error": f"SKU code {sku_code} already exists",
                    })
                    continue

                sku_data = {
                    "sku_code": sku_code,
                    "product_id": request.product_id,
                    "attributes": attrs,
                    "cost_price": request.cost_price or 0.0,
                    "selling_price": request.selling_price or 0.0,
                    "status": SkuStatus.DRAFT,
                    "lifecycle_status": SkuLifecycleStatus.CONCEPT,
                }

                db_sku = SKU(**sku_data)
                db.add(db_sku)
                db.flush()
                db.refresh(db_sku)
                generated_skus.append(db_sku)

            except Exception as e:
                errors.append({
                    "attributes": attrs,
                    "error": str(e),
                })

        cache.delete_pattern(f"{self.cache_prefix}:list:*")
        cache.delete_pattern(f"product:list:*")
        cache.delete(f"product:{request.product_id}")

        return {
            "success_count": len(generated_skus),
            "failed_count": len(errors),
            "generated_skus": generated_skus,
            "errors": errors,
        }

    def transition_lifecycle(
        self,
        db: Session,
        *,
        sku_id: int,
        target_status: SkuLifecycleStatus,
    ) -> SKU:
        sku = self.get_or_404(db, id=sku_id, use_cache=False)

        if not self._can_transition(sku.lifecycle_status, target_status):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Cannot transition from {sku.lifecycle_status} to {target_status}. "
                       f"Valid transitions: {self.lifecycle_transitions.get(sku.lifecycle_status, [])}",
            )

        old_status = sku.lifecycle_status
        sku.lifecycle_status = target_status

        if target_status == SkuLifecycleStatus.END_OF_LIFE:
            sku.status = SkuStatus.DISCONTINUED
        elif target_status == SkuLifecycleStatus.PRODUCTION:
            sku.status = SkuStatus.ACTIVE

        db.flush()
        db.refresh(sku)

        cache.delete(self._get_cache_key(sku_id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return sku

    def batch_update(
        self,
        db: Session,
        *,
        items: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        success_count = 0
        failed_count = 0
        failed_ids: List[int] = []
        errors: List[Dict[str, Any]] = []

        for item in items:
            sku_id = item.get("id")
            if not sku_id:
                failed_count += 1
                errors.append({"error": "Missing SKU id"})
                continue

            try:
                sku = self.get_or_404(db=db, id=sku_id, use_cache=False)
                update_data = {k: v for k, v in item.items() if k != "id" and v is not None}

                for field, value in update_data.items():
                    if hasattr(sku, field):
                        setattr(sku, field, value)

                db.flush()
                db.refresh(sku)
                success_count += 1

                cache.delete(self._get_cache_key(sku_id))

            except HTTPException as e:
                failed_count += 1
                failed_ids.append(sku_id)
                errors.append({"id": sku_id, "error": e.detail})
            except Exception as e:
                failed_count += 1
                failed_ids.append(sku_id)
                errors.append({"id": sku_id, "error": str(e)})

        db.flush()
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return {
            "success_count": success_count,
            "failed_count": failed_count,
            "failed_ids": failed_ids,
            "errors": errors,
        }

    def apply_attribute_template(
        self,
        db: Session,
        *,
        sku_id: int,
        template_id: int,
    ) -> SKU:
        sku = self.get_or_404(db, id=sku_id, use_cache=False)
        template = db.execute(
            select(AttributeTemplate).where(AttributeTemplate.id == template_id)
        ).scalar_one_or_none()

        if not template:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Attribute template with id {template_id} not found",
            )

        template_attrs = template.attributes if isinstance(template.attributes, list) else []
        existing_attrs = sku.attributes or {}

        merged_attrs = dict(existing_attrs)
        for attr in template_attrs:
            attr_code = attr.get("code") or attr.get("attribute_code")
            if attr_code and attr_code not in merged_attrs:
                merged_attrs[attr_code] = {
                    "value": attr.get("default_value"),
                    "name": attr.get("name") or attr.get("attribute_name"),
                    "from_template": template_id,
                }

        sku.attributes = merged_attrs
        db.flush()
        db.refresh(sku)

        cache.delete(self._get_cache_key(sku_id))

        return sku

    def get_with_details(
        self,
        db: Session,
        *,
        id: int,
    ) -> Dict[str, Any]:
        stmt = (
            select(
                SKU,
                Product.name.label("product_name"),
                func.coalesce(func.sum(Inventory.quantity), 0).label("current_stock"),
            )
            .join(Product, SKU.product_id == Product.id)
            .outerjoin(Inventory, SKU.id == Inventory.sku_id)
            .where(SKU.id == id)
            .group_by(SKU.id, Product.name)
        )

        result = db.execute(stmt).first()
        if not result:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"SKU with id {id} not found",
            )

        sku, product_name, current_stock = result
        stock_value = current_stock * sku.cost_price

        category_name = None
        if sku.product and sku.product.category_obj:
            category_name = sku.product.category_obj.name

        return {
            **{c.name: getattr(sku, c.name) for c in sku.__table__.columns},
            "product_name": product_name,
            "category_name": category_name,
            "current_stock": current_stock,
            "stock_value": stock_value,
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
        from app.schemas.product import SkuListItem
        from sqlalchemy import desc, asc, or_

        stmt = (
            select(
                SKU,
                Product.name.label("product_name"),
                func.coalesce(func.sum(Inventory.quantity), 0).label("current_stock"),
            )
            .join(Product, SKU.product_id == Product.id)
            .outerjoin(Inventory, SKU.id == Inventory.sku_id)
        )

        count_stmt = select(func.count()).select_from(SKU).join(Product, SKU.product_id == Product.id)

        where_conditions = []
        if filters:
            for key, value in filters.items():
                if value is None:
                    continue
                if hasattr(SKU, key):
                    if isinstance(value, str) and "%" in value:
                        where_conditions.append(getattr(SKU, key).like(value))
                    elif isinstance(value, list):
                        where_conditions.append(getattr(SKU, key).in_(value))
                    else:
                        where_conditions.append(getattr(SKU, key) == value)
                elif hasattr(Product, key):
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

        stmt = stmt.group_by(SKU.id, Product.name)
        total = db.execute(count_stmt).scalar_one() or 0

        if sort_by:
            if hasattr(SKU, sort_by):
                sort_column = getattr(SKU, sort_by)
            elif sort_by == "product_name":
                sort_column = Product.name
            elif sort_by == "current_stock":
                sort_column = func.coalesce(func.sum(Inventory.quantity), 0)
            else:
                sort_column = SKU.id
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(SKU.id))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        results = db.execute(stmt).all()
        items = []
        for sku, product_name, current_stock in results:
            sku_dict = {c.name: getattr(sku, c.name) for c in sku.__table__.columns}
            sku_dict["product_name"] = product_name
            sku_dict["category_name"] = sku.product.category_obj.name if sku.product and sku.product.category_obj else None
            sku_dict["current_stock"] = current_stock
            items.append(SkuListItem.model_validate(sku_dict))

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

    def get_lifecycle_transitions(self) -> Dict[str, List[str]]:
        return {k.value: [v.value for v in vs] for k, vs in self.lifecycle_transitions.items()}


sku_service = SkuService()
