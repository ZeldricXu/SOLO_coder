from typing import Optional
from fastapi import APIRouter, Depends, Request, status
from sqlalchemy import select, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import (
    PaginatedParams,
    SuccessResponse,
    IdResponse,
)
from app.schemas.product import (
    ProductCreate,
    ProductUpdate,
    ProductDetail,
    ProductListResponse,
    ProductDetailResponse,
    CategoryCreate,
    CategoryUpdate,
    CategoryTree,
    CategoryListResponse,
    CategoryDetailResponse,
    CategoryTreeResponse,
)
from app.services.product_service import product_service
from app.services.category_service import category_service
from app.models.user import User as UserModel
from app.models.product import ProductStatus

router = APIRouter()


@router.get(
    "/products",
    response_model=ProductListResponse,
    summary="获取商品列表",
    dependencies=[Depends(PermissionChecker(["product:list"]))],
)
async def get_products(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
    category_id: Optional[int] = None,
    brand: Optional[str] = None,
    status: Optional[ProductStatus] = None,
    barcode: Optional[str] = None,
):
    filters = {}
    if name:
        filters["name"] = f"%{name}%"
    if category_id is not None:
        filters["category_id"] = category_id
    if brand:
        filters["brand"] = f"%{brand}%"
    if status:
        filters["status"] = status
    if barcode:
        filters["barcode"] = f"%{barcode}%"

    search_filters = []
    from app.models.product import Product as ProductModel
    if name:
        search_filters.append(ProductModel.name.like(f"%{name}%"))
    if brand:
        search_filters.append(ProductModel.brand.like(f"%{brand}%"))
    if barcode:
        search_filters.append(ProductModel.barcode.like(f"%{barcode}%"))

    result = product_service.get_list_with_details(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
        search_filters=search_filters if search_filters else None,
    )

    return ProductListResponse(data=result)


@router.get(
    "/products/{product_id}",
    response_model=ProductDetailResponse,
    summary="获取商品详情",
    dependencies=[Depends(PermissionChecker(["product:read"]))],
)
async def get_product(
    db: Session = Depends(get_db),
    *,
    product_id: int,
):
    product_data = product_service.get_with_details(db, id=product_id)
    return ProductDetailResponse(data=ProductDetail.model_validate(product_data))


@router.post(
    "/products",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建商品",
    dependencies=[Depends(PermissionChecker(["product:create"]))],
)
async def create_product(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    product_data: ProductCreate,
):
    product = product_service.create(db, obj_in=product_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="product",
        resource_id=product.id,
        new_value=product_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=product.id)


@router.put(
    "/products/{product_id}",
    response_model=SuccessResponse,
    summary="更新商品",
    dependencies=[Depends(PermissionChecker(["product:update"]))],
)
async def update_product(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    product_id: int,
    product_data: ProductUpdate,
):
    db_product = product_service.get_or_404(db, id=product_id, use_cache=False)
    old_data = {c.name: getattr(db_product, c.name) for c in db_product.__table__.columns}

    product_service.update(db, db_obj=db_product, obj_in=product_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="product",
        resource_id=product_id,
        old_value=old_data,
        new_value=product_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Product updated successfully")


@router.delete(
    "/products/{product_id}",
    response_model=SuccessResponse,
    summary="删除商品",
    dependencies=[Depends(PermissionChecker(["product:delete"]))],
)
async def delete_product(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    product_id: int,
):
    from app.models.sku import SKU
    sku_count = db.execute(
        select(func.count()).select_from(SKU).where(SKU.product_id == product_id)
    ).scalar_one() or 0

    if sku_count > 0:
        from fastapi import HTTPException
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot delete product with {sku_count} SKUs",
        )

    db_product = product_service.get_or_404(db, id=product_id, use_cache=False)
    old_data = {c.name: getattr(db_product, c.name) for c in db_product.__table__.columns}

    product_service.delete(db, id=product_id)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="product",
        resource_id=product_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Product deleted successfully")


@router.post(
    "/products/{product_id}/apply-template",
    response_model=SuccessResponse,
    summary="应用属性模板到商品",
    dependencies=[Depends(PermissionChecker(["product:update", "attribute_template:apply"]))],
)
async def apply_template_to_product(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    product_id: int,
    template_id: int,
    override_existing: bool = False,
):
    product_service.apply_attribute_template(
        db,
        product_id=product_id,
        template_id=template_id,
        override_existing=override_existing,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="product",
        resource_id=product_id,
        old_value={"action": "apply_template"},
        new_value={"template_id": template_id, "override_existing": override_existing},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute template applied to product successfully")


@router.get(
    "/products/{product_id}/skus",
    summary="获取商品的SKU列表",
    dependencies=[Depends(PermissionChecker(["product:read", "sku:list"]))],
)
async def get_product_skus(
    db: Session = Depends(get_db),
    *,
    product_id: int,
):
    skus = product_service.get_product_skus(db, product_id=product_id)
    from app.schemas.product import Sku
    return {"data": [Sku.model_validate(sku) for sku in skus]}


@router.get(
    "/categories",
    response_model=CategoryListResponse,
    summary="获取分类列表",
    dependencies=[Depends(PermissionChecker(["category:list"]))],
)
async def get_categories(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
    code: Optional[str] = None,
    parent_id: Optional[int] = None,
    is_active: Optional[bool] = None,
):
    filters = {}
    if name:
        filters["name"] = f"%{name}%"
    if code:
        filters["code"] = f"%{code}%"
    if parent_id is not None:
        filters["parent_id"] = parent_id
    if is_active is not None:
        filters["is_active"] = is_active

    result = category_service.get_list_with_product_count(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
    )

    return CategoryListResponse(data=result)


@router.get(
    "/categories/tree",
    response_model=CategoryTreeResponse,
    summary="获取分类树形结构",
    dependencies=[Depends(PermissionChecker(["category:list"]))],
)
async def get_category_tree(
    db: Session = Depends(get_db),
    *,
    parent_id: Optional[int] = None,
):
    tree_data = category_service.get_tree(db, parent_id=parent_id)
    return CategoryTreeResponse(data=[CategoryTree.model_validate(item) for item in tree_data])


@router.get(
    "/categories/{category_id}",
    response_model=CategoryDetailResponse,
    summary="获取分类详情",
    dependencies=[Depends(PermissionChecker(["category:read"]))],
)
async def get_category(
    db: Session = Depends(get_db),
    *,
    category_id: int,
):
    category = category_service.get_with_children(db, id=category_id)
    tree_dict = category_service._build_tree_node(category)
    return CategoryDetailResponse(data=CategoryTree.model_validate(tree_dict))


@router.post(
    "/categories",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建分类",
    dependencies=[Depends(PermissionChecker(["category:create"]))],
)
async def create_category(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    category_data: CategoryCreate,
):
    category = category_service.create(db, obj_in=category_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="category",
        resource_id=category.id,
        new_value=category_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=category.id)


@router.put(
    "/categories/{category_id}",
    response_model=SuccessResponse,
    summary="更新分类",
    dependencies=[Depends(PermissionChecker(["category:update"]))],
)
async def update_category(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    category_id: int,
    category_data: CategoryUpdate,
):
    db_category = category_service.get_or_404(db, id=category_id, use_cache=False)
    old_data = {c.name: getattr(db_category, c.name) for c in db_category.__table__.columns}

    category_service.update(db, db_obj=db_category, obj_in=category_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="category",
        resource_id=category_id,
        old_value=old_data,
        new_value=category_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Category updated successfully")


@router.delete(
    "/categories/{category_id}",
    response_model=SuccessResponse,
    summary="删除分类",
    dependencies=[Depends(PermissionChecker(["category:delete"]))],
)
async def delete_category(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    category_id: int,
):
    from app.models.product import Product
    product_count = db.execute(
        select(func.count()).select_from(Product).where(Product.category_id == category_id)
    ).scalar_one() or 0

    if product_count > 0:
        from fastapi import HTTPException
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot delete category with {product_count} products",
        )

    descendant_ids = category_service.get_descendant_ids(db, category_id=category_id)
    if descendant_ids:
        from fastapi import HTTPException
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot delete category with {len(descendant_ids)} subcategories",
        )

    db_category = category_service.get_or_404(db, id=category_id, use_cache=False)
    old_data = {c.name: getattr(db_category, c.name) for c in db_category.__table__.columns}

    category_service.delete(db, id=category_id)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="category",
        resource_id=category_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Category deleted successfully")
