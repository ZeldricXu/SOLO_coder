from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, EmailStr, ConfigDict

from app.schemas.common import APIResponse


class TokenData(BaseModel):
    user_id: int
    token_type: str


class LoginRequest(BaseModel):
    username: str = Field(description="用户名")
    password: str = Field(description="密码")


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=50, description="用户名")
    email: EmailStr = Field(description="邮箱")
    password: str = Field(min_length=8, max_length=128, description="密码")
    full_name: Optional[str] = Field(default=None, max_length=100, description="全名")
    phone: Optional[str] = Field(default=None, max_length=20, description="手机号")


class TokenResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    access_token: str = Field(description="访问令牌")
    refresh_token: str = Field(description="刷新令牌")
    token_type: str = Field(default="bearer", description="令牌类型")
    expires_in: int = Field(description="过期时间(秒)")


class UserInfo(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    email: str
    full_name: Optional[str]
    phone: Optional[str]
    avatar_url: Optional[str]
    is_active: bool
    is_superuser: bool
    last_login_at: Optional[datetime]
    created_at: datetime


class LoginResponse(APIResponse[TokenResponse]):
    pass


class UserInfoResponse(APIResponse[UserInfo]):
    pass


class ChangePasswordRequest(BaseModel):
    old_password: str = Field(description="旧密码")
    new_password: str = Field(min_length=8, max_length=128, description="新密码")
    confirm_password: str = Field(min_length=8, max_length=128, description="确认密码")


class RefreshTokenRequest(BaseModel):
    refresh_token: str = Field(description="刷新令牌")
