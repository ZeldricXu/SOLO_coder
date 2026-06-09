from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import APIResponse, PaginatedResponse


class PermissionBase(BaseModel):
    name: str = Field(max_length=100, description="权限名称")
    code: str = Field(max_length=100, description="权限编码")
    description: Optional[str] = Field(default=None, max_length=255, description="描述")
    resource_type: Optional[str] = Field(default=None, max_length=50, description="资源类型")
    action: Optional[str] = Field(default=None, max_length=50, description="操作类型")


class PermissionCreate(PermissionBase):
    pass


class PermissionUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=100)
    description: Optional[str] = Field(default=None, max_length=255)
    resource_type: Optional[str] = Field(default=None, max_length=50)
    action: Optional[str] = Field(default=None, max_length=50)


class Permission(PermissionBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime


class RoleBase(BaseModel):
    name: str = Field(max_length=100, description="角色名称")
    code: str = Field(max_length=100, description="角色编码")
    description: Optional[str] = Field(default=None, max_length=255, description="描述")
    is_active: bool = Field(default=True, description="是否启用")


class RoleCreate(RoleBase):
    permission_ids: Optional[list[int]] = Field(default=None, description="权限ID列表")


class RoleUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=100)
    description: Optional[str] = Field(default=None, max_length=255)
    is_active: Optional[bool] = Field(default=None)
    permission_ids: Optional[list[int]] = Field(default=None, description="权限ID列表")


class Role(RoleBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    permissions: list[Permission] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime


class RoleWithUsers(Role):
    user_count: int = Field(default=0, description="用户数量")


class RoleListResponse(APIResponse[PaginatedResponse[Role]]):
    pass


class RoleDetailResponse(APIResponse[Role]):
    pass


class PermissionListResponse(APIResponse[PaginatedResponse[Permission]]):
    pass


class PermissionDetailResponse(APIResponse[Permission]):
    pass
