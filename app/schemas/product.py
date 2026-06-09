from datetime import datetime
from typing import Any, Optional
from pydantic import BaseModel, Field, ConfigDict, field_validator

from app.schemas.common import APIResponse, PaginatedResponse
from app.models.sku import SkuStatus, SkuLifecycleStatus
from app.models.product import ProductStatus
from app.models.attribute import AttributeDataType


class CategoryBase(BaseModel):
    name: str = Field(max_length=100, description="分类名称")
    code: str = Field(max_length=50, description="分类编码")
    description: Optional[str] = Field(default=None, max_length=255, description="分类描述")
    parent_id: Optional[int] = Field(default=None, description="父分类ID")
    sort_order: int = Field(default=0, description="排序")
    is_active: bool = Field(default=True, description="是否启用")


class CategoryCreate(CategoryBase):
    pass


class CategoryUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=100, description="分类名称")
    code: Optional[str] = Field(default=None, max_length=50, description="分类编码")
    description: Optional[str] = Field(default=None, max_length=255, description="分类描述")
    parent_id: Optional[int] = Field(default=None, description="父分类ID")
    sort_order: Optional[int] = Field(default=None, description="排序")
    is_active: Optional[bool] = Field(default=None, description="是否启用")


class Category(CategoryBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    level: int
    created_at: datetime
    updated_at: datetime


class CategoryTree(Category):
    model_config = ConfigDict(from_attributes=True)

    children: list["CategoryTree"] = Field(default_factory=list, description="子分类列表")


class CategoryWithProductCount(CategoryBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    level: int
    product_count: int = Field(default=0, description="商品数量")
    created_at: datetime


class CategoryListResponse(APIResponse[PaginatedResponse[CategoryWithProductCount]]):
    pass


class CategoryDetailResponse(APIResponse[CategoryTree]):
    pass


class CategoryTreeResponse(APIResponse[list[CategoryTree]]):
    pass


class AttributeBase(BaseModel):
    name: str = Field(max_length=100, description="属性名称")
    code: str = Field(max_length=50, description="属性编码")
    data_type: AttributeDataType = Field(description="数据类型")
    options: Optional[list[Any]] = Field(default=None, description="选项列表")
    is_required: bool = Field(default=False, description="是否必填")
    is_searchable: bool = Field(default=False, description="是否可搜索")
    is_filterable: bool = Field(default=False, description="是否可筛选")


class AttributeCreate(AttributeBase):
    pass


class AttributeUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=100, description="属性名称")
    data_type: Optional[AttributeDataType] = Field(default=None, description="数据类型")
    options: Optional[list[Any]] = Field(default=None, description="选项列表")
    is_required: Optional[bool] = Field(default=None, description="是否必填")
    is_searchable: Optional[bool] = Field(default=None, description="是否可搜索")
    is_filterable: Optional[bool] = Field(default=None, description="是否可筛选")


class Attribute(AttributeBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime


class AttributeListResponse(APIResponse[PaginatedResponse[Attribute]]):
    pass


class AttributeDetailResponse(APIResponse[Attribute]):
    pass


class AttributeTemplateBase(BaseModel):
    name: str = Field(max_length=100, description="模板名称")
    description: Optional[str] = Field(default=None, max_length=255, description="模板描述")
    attributes: list[dict[str, Any]] = Field(description="属性列表")


class AttributeTemplateCreate(AttributeTemplateBase):
    pass


class AttributeTemplateUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=100, description="模板名称")
    description: Optional[str] = Field(default=None, max_length=255, description="模板描述")
    attributes: Optional[list[dict[str, Any]]] = Field(default=None, description="属性列表")


class AttributeTemplate(AttributeTemplateBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class AttributeTemplateListResponse(APIResponse[PaginatedResponse[AttributeTemplate]]):
    pass


class AttributeTemplateDetailResponse(APIResponse[AttributeTemplate]):
    pass


class ApplyTemplateRequest(BaseModel):
    template_id: int = Field(description="模板ID")
    product_id: int = Field(description="商品ID")


class ProductBase(BaseModel):
    name: str = Field(max_length=200, description="商品名称")
    category: Optional[str] = Field(default=None, max_length=100, description="分类")
    brand: Optional[str] = Field(default=None, max_length=100, description="品牌")
    description: Optional[str] = Field(default=None, description="商品描述")
    barcode: Optional[str] = Field(default=None, max_length=50, description="条形码")
    main_image: Optional[str] = Field(default=None, max_length=500, description="主图URL")
    images: Optional[list[str]] = Field(default=None, description="图片列表")
    weight: Optional[float] = Field(default=None, description="重量")
    volume: Optional[float] = Field(default=None, description="体积")
    status: ProductStatus = Field(default=ProductStatus.DRAFT, description="状态")
    is_virtual: bool = Field(default=False, description="是否虚拟商品")
    category_id: Optional[int] = Field(default=None, description="分类ID")


class ProductCreate(ProductBase):
    attribute_template_id: Optional[int] = Field(default=None, description="属性模板ID")
    attributes: Optional[list[dict[str, Any]]] = Field(default=None, description="属性列表")


class ProductUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=200, description="商品名称")
    category: Optional[str] = Field(default=None, max_length=100, description="分类")
    brand: Optional[str] = Field(default=None, max_length=100, description="品牌")
    description: Optional[str] = Field(default=None, description="商品描述")
    barcode: Optional[str] = Field(default=None, max_length=50, description="条形码")
    main_image: Optional[str] = Field(default=None, max_length=500, description="主图URL")
    images: Optional[list[str]] = Field(default=None, description="图片列表")
    weight: Optional[float] = Field(default=None, description="重量")
    volume: Optional[float] = Field(default=None, description="体积")
    status: Optional[ProductStatus] = Field(default=None, description="状态")
    is_virtual: Optional[bool] = Field(default=None, description="是否虚拟商品")
    category_id: Optional[int] = Field(default=None, description="分类ID")


class Product(ProductBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class ProductDetail(Product):
    model_config = ConfigDict(from_attributes=True)

    sku_count: int = Field(default=0, description="SKU数量")
    total_stock: int = Field(default=0, description="总库存")
    attributes: Optional[list[dict[str, Any]]] = Field(default=None, description="属性列表")


class ProductWithSkuCount(ProductBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    sku_count: int = Field(default=0, description="SKU数量")
    total_stock: int = Field(default=0, description="总库存")
    created_at: datetime


class ProductListResponse(APIResponse[PaginatedResponse[ProductWithSkuCount]]):
    pass


class ProductDetailResponse(APIResponse[ProductDetail]):
    pass


class SkuBase(BaseModel):
    sku_code: str = Field(max_length=100, description="SKU编码")
    product_id: int = Field(description="商品ID")
    attributes: Optional[dict[str, Any]] = Field(default=None, description="属性组合")
    cost_price: float = Field(default=0.0, ge=0, description="成本价")
    selling_price: float = Field(default=0.0, ge=0, description="售价")
    weight: Optional[float] = Field(default=None, ge=0, description="重量")
    volume: Optional[float] = Field(default=None, ge=0, description="体积")
    status: SkuStatus = Field(default=SkuStatus.DRAFT, description="状态")
    lifecycle_status: SkuLifecycleStatus = Field(default=SkuLifecycleStatus.CONCEPT, description="生命周期状态")
    minimum_stock: int = Field(default=0, ge=0, description="最低库存")
    maximum_stock: int = Field(default=0, ge=0, description="最高库存")
    reorder_point: int = Field(default=0, ge=0, description="重订货点")
    safety_stock: int = Field(default=0, ge=0, description="安全库存")
    lead_time_days: int = Field(default=0, ge=0, description="交货周期(天)")


class SkuCreate(SkuBase):
    pass


class SkuUpdate(BaseModel):
    sku_code: Optional[str] = Field(default=None, max_length=100, description="SKU编码")
    attributes: Optional[dict[str, Any]] = Field(default=None, description="属性组合")
    cost_price: Optional[float] = Field(default=None, ge=0, description="成本价")
    selling_price: Optional[float] = Field(default=None, ge=0, description="售价")
    weight: Optional[float] = Field(default=None, ge=0, description="重量")
    volume: Optional[float] = Field(default=None, ge=0, description="体积")
    status: Optional[SkuStatus] = Field(default=None, description="状态")
    minimum_stock: Optional[int] = Field(default=None, ge=0, description="最低库存")
    maximum_stock: Optional[int] = Field(default=None, ge=0, description="最高库存")
    reorder_point: Optional[int] = Field(default=None, ge=0, description="重订货点")
    safety_stock: Optional[int] = Field(default=None, ge=0, description="安全库存")
    lead_time_days: Optional[int] = Field(default=None, ge=0, description="交货周期(天)")


class Sku(SkuBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class SkuDetail(Sku):
    model_config = ConfigDict(from_attributes=True)

    product_name: str = Field(description="商品名称")
    category_name: Optional[str] = Field(default=None, description="分类名称")
    current_stock: int = Field(default=0, description="当前库存")
    stock_value: float = Field(default=0.0, description="库存价值")


class SkuListItem(SkuBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    product_name: str = Field(description="商品名称")
    category_name: Optional[str] = Field(default=None, description="分类名称")
    current_stock: int = Field(default=0, description="当前库存")
    created_at: datetime


class SkuListResponse(APIResponse[PaginatedResponse[SkuListItem]]):
    pass


class SkuDetailResponse(APIResponse[SkuDetail]):
    pass


class AttributeValueItem(BaseModel):
    attribute_code: str = Field(description="属性编码")
    attribute_name: str = Field(description="属性名称")
    values: list[Any] = Field(description="属性值列表")


class SkuGenerateRequest(BaseModel):
    product_id: int = Field(description="商品ID")
    attributes: list[AttributeValueItem] = Field(description="属性列表和对应的值")
    cost_price: Optional[float] = Field(default=None, ge=0, description="默认成本价")
    selling_price: Optional[float] = Field(default=None, ge=0, description="默认售价")
    prefix: Optional[str] = Field(default=None, max_length=20, description="SKU编码前缀")


class SkuGenerateResult(BaseModel):
    success_count: int = Field(description="成功生成数量")
    failed_count: int = Field(description="失败数量")
    generated_skus: list[Sku] = Field(default_factory=list, description="生成的SKU列表")
    errors: list[dict[str, Any]] = Field(default_factory=list, description="错误详情")


class SkuGenerateResponse(APIResponse[SkuGenerateResult]):
    pass


class SkuBatchUpdateItem(BaseModel):
    id: int = Field(description="SKU ID")
    cost_price: Optional[float] = Field(default=None, ge=0, description="成本价")
    selling_price: Optional[float] = Field(default=None, ge=0, description="售价")
    minimum_stock: Optional[int] = Field(default=None, ge=0, description="最低库存")
    maximum_stock: Optional[int] = Field(default=None, ge=0, description="最高库存")
    reorder_point: Optional[int] = Field(default=None, ge=0, description="重订货点")
    safety_stock: Optional[int] = Field(default=None, ge=0, description="安全库存")
    status: Optional[SkuStatus] = Field(default=None, description="状态")


class SkuBatchUpdateRequest(BaseModel):
    items: list[SkuBatchUpdateItem] = Field(description="批量更新的SKU列表")


class SkuBatchUpdateResponse(APIResponse):
    data: Optional[dict[str, Any]] = Field(default=None)


class SkuLifecycleTransitionResponse(APIResponse[Sku]):
    pass


CategoryTree.model_rebuild()
