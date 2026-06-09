from typing import Any, Dict, List, Optional
from sqlalchemy import select
from sqlalchemy.orm import Session
from fastapi import HTTPException, status

from app.services.crud_base import CRUDBase
from app.models.attribute import Attribute, AttributeTemplate
from app.models.product import Product
from app.schemas.product import AttributeCreate, AttributeUpdate, AttributeTemplateCreate, AttributeTemplateUpdate
from app.core.cache import cache


class AttributeService(CRUDBase[Attribute, AttributeCreate, AttributeUpdate]):
    def __init__(self):
        super().__init__(Attribute, cache_prefix="attribute")

    def create(self, db: Session, *, obj_in: AttributeCreate) -> Attribute:
        if self.exists(db, filters={"code": obj_in.code}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Attribute code {obj_in.code} already exists",
            )

        return super().create(db, obj_in=obj_in)

    def update(self, db: Session, *, db_obj: Attribute, obj_in: AttributeUpdate) -> Attribute:
        update_data = obj_in.model_dump(exclude_unset=True)
        if "code" in update_data and update_data["code"] != db_obj.code:
            if self.exists(db, filters={"code": update_data["code"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Attribute code {update_data['code']} already exists",
                )

        return super().update(db, db_obj=db_obj, obj_in=update_data)

    def get_by_code(self, db: Session, *, code: str) -> Optional[Attribute]:
        stmt = select(Attribute).where(Attribute.code == code)
        return db.execute(stmt).scalar_one_or_none()

    def get_searchable(self, db: Session) -> List[Attribute]:
        stmt = select(Attribute).where(Attribute.is_searchable == True)
        return list(db.execute(stmt).scalars().all())

    def get_filterable(self, db: Session) -> List[Attribute]:
        stmt = select(Attribute).where(Attribute.is_filterable == True)
        return list(db.execute(stmt).scalars().all())


class AttributeTemplateService(CRUDBase[AttributeTemplate, AttributeTemplateCreate, AttributeTemplateUpdate]):
    def __init__(self):
        super().__init__(AttributeTemplate, cache_prefix="attribute_template")

    def create(self, db: Session, *, obj_in: AttributeTemplateCreate) -> AttributeTemplate:
        if self.exists(db, filters={"name": obj_in.name}):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Attribute template name {obj_in.name} already exists",
            )

        return super().create(db, obj_in=obj_in)

    def update(
        self,
        db: Session,
        *,
        db_obj: AttributeTemplate,
        obj_in: AttributeTemplateUpdate,
    ) -> AttributeTemplate:
        update_data = obj_in.model_dump(exclude_unset=True)
        if "name" in update_data and update_data["name"] != db_obj.name:
            if self.exists(db, filters={"name": update_data["name"]}, exclude_id=db_obj.id):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Attribute template name {update_data['name']} already exists",
                )

        return super().update(db, db_obj=db_obj, obj_in=update_data)

    def apply_to_product(
        self,
        db: Session,
        *,
        template_id: int,
        product_id: int,
        override_existing: bool = False,
    ) -> Product:
        template = self.get_or_404(db, id=template_id, use_cache=False)
        product = db.execute(select(Product).where(Product.id == product_id)).scalar_one_or_none()

        if not product:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Product with id {product_id} not found",
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

        cache.delete(f"product:{product_id}")
        cache.delete_pattern("product:list:*")

        return product

    def apply_to_products(
        self,
        db: Session,
        *,
        template_id: int,
        product_ids: List[int],
        override_existing: bool = False,
    ) -> Dict[str, Any]:
        success_count = 0
        failed_count = 0
        failed_ids: List[int] = []
        errors: List[Dict[str, Any]] = []

        for product_id in product_ids:
            try:
                self.apply_to_product(
                    db,
                    template_id=template_id,
                    product_id=product_id,
                    override_existing=override_existing,
                )
                success_count += 1
            except HTTPException as e:
                failed_count += 1
                failed_ids.append(product_id)
                errors.append({"id": product_id, "error": e.detail})
            except Exception as e:
                failed_count += 1
                failed_ids.append(product_id)
                errors.append({"id": product_id, "error": str(e)})

        db.flush()

        return {
            "success_count": success_count,
            "failed_count": failed_count,
            "failed_ids": failed_ids,
            "errors": errors,
        }

    def inherit_template(
        self,
        db: Session,
        *,
        target_template_id: int,
        source_template_id: int,
        override_existing: bool = False,
    ) -> AttributeTemplate:
        target = self.get_or_404(db, id=target_template_id, use_cache=False)
        source = self.get_or_404(db, id=source_template_id, use_cache=True)

        source_attrs = source.attributes if isinstance(source.attributes, list) else []
        target_attrs = target.attributes if isinstance(target.attributes, list) else []

        if override_existing:
            merged_attrs = source_attrs
        else:
            existing_codes = {
                (a.get("code") or a.get("attribute_code"))
                for a in target_attrs
                if isinstance(a, dict)
            }
            merged_attrs = list(target_attrs)
            for attr in source_attrs:
                attr_code = attr.get("code") or attr.get("attribute_code")
                if attr_code not in existing_codes:
                    merged_attrs.append(attr)

        target.attributes = merged_attrs
        db.flush()
        db.refresh(target)

        cache.delete(self._get_cache_key(target_template_id))
        cache.delete_pattern(f"{self.cache_prefix}:list:*")

        return target


attribute_service = AttributeService()
attribute_template_service = AttributeTemplateService()
