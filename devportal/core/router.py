from datetime import timedelta
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
from pydantic import BaseModel

from .config import settings
from .database import get_db
from .models import User
from .schemas import APIResponse, BatchRequest, BatchResponse, BatchResult, EntityResponse
from .dependencies import (
    get_current_user,
    verify_password,
    create_access_token,
    hash_password,
    PermissionChecker,
)
from .utils import generate_id, processing_context
from .exceptions import NotFoundError, ConflictError, ValidationError

router = APIRouter()


class LoginRequest(BaseModel):
    username: str
    password: str


class UserCreate(BaseModel):
    username: str
    email: str
    password: str
    roles: List[str] = []
    permissions: List[str] = []


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


@router.post("/auth/token", response_model=APIResponse[TokenResponse])
async def login(
    request: LoginRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.username == request.username))
    user = result.scalar_one_or_none()
    if not user or not verify_password(request.password, user.hashed_password):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = create_access_token(
        user.id,
        expires_delta=timedelta(minutes=settings.access_token_expire_minutes),
        extra={"username": user.username, "roles": user.roles},
    )
    return APIResponse(
        code=200,
        data=TokenResponse(
            access_token=token, expires_in=settings.access_token_expire_minutes * 60
        ),
    )


@router.post("/users", response_model=APIResponse[EntityResponse])
async def create_user(
    user_in: UserCreate,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(User).where((User.username == user_in.username) | (User.email == user_in.email))
    )
    if result.scalar_one_or_none():
        raise ConflictError("Username or email already exists")
    user = User(
        id=generate_id("usr"),
        username=user_in.username,
        email=user_in.email,
        hashed_password=hash_password(user_in.password),
        roles=user_in.roles,
        permissions=user_in.permissions,
        status="active",
        type="user",
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return APIResponse(code=201, data=user)


@router.get("/users/me", response_model=APIResponse[EntityResponse])
async def get_current_user_info(user: User = Depends(get_current_user)):
    return APIResponse(code=200, data=user)


@router.post("/resources/batch", response_model=BatchResponse)
async def batch_operations(
    request: BatchRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    results: List[BatchResult] = []
    async with processing_context() as ctx:
        for op in request.operations:
            try:
                if op.action == "restart":
                    ctx.metrics.increment("restart_operations")
                    results.append(
                        BatchResult(id=op.id, action=op.action, success=True, message="Restart initiated")
                    )
                elif op.action == "delete":
                    ctx.metrics.increment("delete_operations")
                    results.append(
                        BatchResult(id=op.id, action=op.action, success=True, message="Deleted")
                    )
                else:
                    results.append(
                        BatchResult(
                            id=op.id,
                            action=op.action,
                            success=False,
                            message=f"Unknown action: {op.action}",
                        )
                    )
            except Exception as e:
                ctx.record_error(e, {"operation": op.model_dump()})
                results.append(
                    BatchResult(id=op.id, action=op.action, success=False, message=str(e))
                )
    return BatchResponse(
        code=200,
        batch_id=generate_id("batch"),
        results=results,
    )


@router.get("/health")
async def health_check():
    return {"status": "healthy", "service": settings.app_name}
