from fastapi import APIRouter, Depends, Query, Body
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional, Dict, Any

from app.database import get_db
from app.schemas import (
    UserCreate,
    UserLogin,
    UserResponse,
    TokenResponse,
    APIKeyCreate,
    APIKeyResponse,
    BaseResponse,
)
from app.api_gateway.auth import AuthService, get_current_user
from app.logging import LogContext
from app.models import User

router = APIRouter(prefix="/api/v1/auth", tags=["Authentication"])


@router.post("/register", response_model=BaseResponse[UserResponse])
async def register(
    user_in: UserCreate,
    db: AsyncSession = Depends(get_db),
):
    service = AuthService(db)
    user = await service.register(user_in)
    return BaseResponse(
        code=201,
        data=user,
        request_id=LogContext.get_request_id(),
        message="User registered successfully",
    )


@router.post("/login", response_model=BaseResponse[TokenResponse])
async def login(
    login_in: UserLogin,
    db: AsyncSession = Depends(get_db),
):
    service = AuthService(db)
    token = await service.login(login_in)
    return BaseResponse(
        data=token,
        request_id=LogContext.get_request_id(),
        message="Login successful",
    )


@router.get("/me", response_model=BaseResponse[UserResponse])
async def get_current_user_info(
    current_user: User = Depends(get_current_user),
):
    return BaseResponse(
        data=current_user,
        request_id=LogContext.get_request_id(),
    )


@router.post("/api-keys", response_model=BaseResponse[APIKeyResponse])
async def create_api_key(
    key_in: APIKeyCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    service = AuthService(db)
    api_key = await service.create_api_key(current_user.id, key_in)
    return BaseResponse(
        code=201,
        data=api_key,
        request_id=LogContext.get_request_id(),
        message="API key created successfully",
    )
