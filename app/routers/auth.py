from datetime import datetime, timedelta
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.models import User
from app.modules.api_gateway import (
    create_access_token,
    get_password_hash,
    verify_password,
    get_current_user,
    Permission,
    require_permission
)
from app.schemas import UserCreate, UserLogin, AuthResponse, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/auth", tags=["Authentication"])


@router.post("/register", response_model=APIResponse)
async def register(
    user_data: UserCreate,
    db: AsyncSession = Depends(get_async_db)
):
    stmt = select(User).where(User.username == user_data.username)
    result = await db.execute(stmt)
    if result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Username already exists"
        )
    
    stmt = select(User).where(User.email == user_data.email)
    result = await db.execute(stmt)
    if result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already exists"
        )
    
    user = User(
        username=user_data.username,
        email=user_data.email,
        password_hash=get_password_hash(user_data.password),
        is_active=True,
        is_admin=False
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    
    access_token = create_access_token(
        data={
            "sub": user.id,
            "username": user.username,
            "email": user.email,
            "role": "admin" if user.is_admin else "operator"
        },
        expires_delta=timedelta(minutes=60)
    )
    
    logger.info("User registered", username=user.username)
    
    return APIResponse(
        code=201,
        data=AuthResponse(
            access_token=access_token,
            user={
                "id": user.id,
                "username": user.username,
                "email": user.email,
                "role": "admin" if user.is_admin else "operator"
            }
        )
    )


@router.post("/login", response_model=APIResponse)
async def login(
    credentials: UserLogin,
    db: AsyncSession = Depends(get_async_db)
):
    stmt = select(User).where(User.username == credentials.username)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()
    
    if not user or not verify_password(credentials.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password"
        )
    
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is disabled"
        )
    
    access_token = create_access_token(
        data={
            "sub": user.id,
            "username": user.username,
            "email": user.email,
            "role": "admin" if user.is_admin else "operator"
        },
        expires_delta=timedelta(minutes=60)
    )
    
    logger.info("User logged in", username=user.username)
    
    return APIResponse(
        code=200,
        data=AuthResponse(
            access_token=access_token,
            user={
                "id": user.id,
                "username": user.username,
                "email": user.email,
                "role": "admin" if user.is_admin else "operator"
            }
        )
    )


@router.get("/me", response_model=APIResponse)
async def get_current_user_info(
    user: dict = Depends(get_current_user)
):
    return APIResponse(
        code=200,
        data=user
    )


@router.post("/refresh", response_model=APIResponse)
async def refresh_token(
    user: dict = Depends(get_current_user)
):
    access_token = create_access_token(
        data={
            "sub": user["user_id"],
            "username": user.get("username"),
            "email": user.get("email"),
            "role": user.get("role", "viewer")
        },
        expires_delta=timedelta(minutes=60)
    )
    
    return APIResponse(
        code=200,
        data=AuthResponse(
            access_token=access_token,
            user=user
        )
    )
