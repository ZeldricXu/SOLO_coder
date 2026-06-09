from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, EmailStr, ConfigDict

from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.role import Role


class UserBase(BaseModel):
    username: str = Field(min_length=3, max_length=50, description="用户名")
    email: EmailStr = Field(description="邮箱")
    full_name: Optional[str] = Field(default=None, max_length=100, description="全名")
    phone: Optional[str] = Field(default=None, max_length=20, description="手机号")
    avatar_url: Optional[str] = Field(default=None, max_length=500, description="头像URL")


class UserCreate(UserBase):
    password: str = Field(min_length=8, max_length=128, description="密码")
    role_ids: Optional[list[int]] = Field(default=None, description="角色ID列表")


class UserUpdate(BaseModel):
    username: Optional[str] = Field(default=None, min_length=3, max_length=50, description="用户名")
    email: Optional[EmailStr] = Field(default=None, description="邮箱")
    full_name: Optional[str] = Field(default=None, max_length=100, description="全名")
    phone: Optional[str] = Field(default=None, max_length=20, description="手机号")
    avatar_url: Optional[str] = Field(default=None, max_length=500, description="头像URL")
    is_active: Optional[bool] = Field(default=None, description="是否启用")
    role_ids: Optional[list[int]] = Field(default=None, description="角色ID列表")


class User(UserBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    is_active: bool
    is_superuser: bool
    roles: list[Role] = Field(default_factory=list, description="角色列表")
    last_login_at: Optional[datetime]
    created_at: datetime
    updated_at: datetime


class UserWithRoleCount(UserBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    is_active: bool
    is_superuser: bool
    role_count: int = Field(default=0, description="角色数量")
    last_login_at: Optional[datetime]
    created_at: datetime


class UserDetailResponse(APIResponse[User]):
    pass


class UserListResponse(APIResponse[PaginatedResponse[UserWithRoleCount]]):
    pass


class AssignRolesRequest(BaseModel):
    role_ids: list[int] = Field(description="角色ID列表")


class ResetPasswordRequest(BaseModel):
    new_password: str = Field(min_length=8, max_length=128, description="新密码")


class ToggleUserStatusRequest(BaseModel):
    is_active: bool = Field(description="是否启用")
