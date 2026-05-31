from datetime import datetime
from typing import Optional, Dict, Any
from uuid import UUID
from pydantic import BaseModel, Field, EmailStr, ConfigDict


class UserCreate(BaseModel):
    username: str = Field(..., min_length=3, max_length=50, description="用户名")
    email: EmailStr = Field(..., description="邮箱")
    password: str = Field(..., min_length=8, max_length=100, description="密码")
    role: str = Field("user", description="角色")


class UserLogin(BaseModel):
    username: str = Field(..., description="用户名或邮箱")
    password: str = Field(..., description="密码")


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="用户ID")
    username: str = Field(..., description="用户名")
    email: str = Field(..., description="邮箱")
    role: str = Field(..., description="角色")
    is_active: bool = Field(..., description="是否激活")
    rate_limit_tier: str = Field(..., description="速率限制层级")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class TokenResponse(BaseModel):
    access_token: str = Field(..., description="访问令牌")
    token_type: str = Field("bearer", description="令牌类型")
    expires_in: int = Field(..., description="过期时间(秒)")


class APIKeyCreate(BaseModel):
    name: str = Field(..., description="API Key名称")
    expires_at: Optional[datetime] = Field(None, description="过期时间")
    scopes: Optional[list[str]] = Field(None, description="权限范围")


class APIKeyResponse(BaseModel):
    id: UUID = Field(..., description="API Key ID")
    name: str = Field(..., description="API Key名称")
    api_key: str = Field(..., description="API Key(仅创建时可见)")
    expires_at: Optional[datetime] = Field(None, description="过期时间")
    scopes: Optional[list[str]] = Field(None, description="权限范围")
    created_at: datetime = Field(..., description="创建时间")
