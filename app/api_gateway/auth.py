from typing import Optional, Dict, Any
from uuid import UUID
from datetime import datetime, timezone, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
import time
import hashlib

from app.models import User
from app.schemas import UserCreate, UserLogin, TokenResponse, APIKeyCreate, APIKeyResponse
from app.exceptions import AuthenticationError, AuthorizationError, ConflictError, NotFoundError
from app.logging import get_logger
from app.config import settings
from app.utils import hash_password, verify_password, create_access_token, decode_access_token, generate_api_key, hash_api_key
from app.database import get_db

logger = get_logger(__name__)

security = HTTPBearer(auto_error=False)


class RateLimiter:
    _instance = None
    _request_counts: Dict[str, list] = {}

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def check_rate_limit(
        self,
        identifier: str,
        max_requests: int = None,
        window_seconds: int = None,
    ) -> bool:
        max_requests = max_requests or settings.rate_limit_requests
        window_seconds = window_seconds or settings.rate_limit_window_seconds

        now = time.time()
        if identifier not in self._request_counts:
            self._request_counts[identifier] = []

        self._request_counts[identifier] = [
            t for t in self._request_counts[identifier]
            if now - t < window_seconds
        ]

        if len(self._request_counts[identifier]) >= max_requests:
            return False

        self._request_counts[identifier].append(now)
        return True

    def get_remaining_requests(
        self,
        identifier: str,
        max_requests: int = None,
        window_seconds: int = None,
    ) -> int:
        max_requests = max_requests or settings.rate_limit_requests
        window_seconds = window_seconds or settings.rate_limit_window_seconds

        now = time.time()
        if identifier not in self._request_counts:
            return max_requests

        recent_requests = [
            t for t in self._request_counts[identifier]
            if now - t < window_seconds
        ]
        return max(0, max_requests - len(recent_requests))


class AuthService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.rate_limiter = RateLimiter()

    async def register(self, user_in: UserCreate) -> User:
        stmt = select(User).where(
            or_(
                User.username == user_in.username,
                User.email == user_in.email,
            )
        )
        result = await self.db.execute(stmt)
        existing = result.scalar_one_or_none()

        if existing:
            if existing.username == user_in.username:
                raise ConflictError(f"Username '{user_in.username}' already exists")
            else:
                raise ConflictError(f"Email '{user_in.email}' already exists")

        user = User(
            username=user_in.username,
            email=user_in.email,
            hashed_password=hash_password(user_in.password),
            role=user_in.role,
        )
        self.db.add(user)
        await self.db.commit()
        await self.db.refresh(user)

        logger.info(
            "User registered",
            user_id=str(user.id),
            username=user.username,
        )
        return user

    async def login(self, login_in: UserLogin) -> TokenResponse:
        stmt = select(User).where(
            or_(
                User.username == login_in.username,
                User.email == login_in.username,
            )
        )
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

        if not user or not verify_password(login_in.password, user.hashed_password):
            raise AuthenticationError("Invalid username or password")

        if not user.is_active:
            raise AuthorizationError("User account is disabled")

        access_token = create_access_token(
            data={"sub": str(user.id), "username": user.username, "role": user.role},
        )

        logger.info(
            "User logged in",
            user_id=str(user.id),
            username=user.username,
        )

        return TokenResponse(
            access_token=access_token,
            expires_in=settings.access_token_expire_minutes * 60,
        )

    async def authenticate_token(self, token: str) -> User:
        try:
            payload = decode_access_token(token)
            user_id = payload.get("sub")
            if not user_id:
                raise AuthenticationError("Invalid token payload")
        except Exception as e:
            raise AuthenticationError(f"Invalid token: {str(e)}")

        stmt = select(User).where(User.id == UUID(user_id))
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

        if not user:
            raise AuthenticationError("User not found")

        if not user.is_active:
            raise AuthorizationError("User account is disabled")

        return user

    async def authenticate_api_key(self, api_key: str) -> User:
        key_hash = hash_api_key(api_key)
        stmt = select(User).where(User.api_key_hash == key_hash)
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

        if not user:
            raise AuthenticationError("Invalid API key")

        if not user.is_active:
            raise AuthorizationError("User account is disabled")

        return user

    async def create_api_key(self, user_id: UUID, key_in: APIKeyCreate) -> APIKeyResponse:
        api_key = generate_api_key()
        key_hash = hash_api_key(api_key)

        stmt = select(User).where(User.id == user_id)
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

        if not user:
            raise NotFoundError(f"User {user_id} not found")

        user.api_key_hash = key_hash
        await self.db.commit()

        logger.info(
            "API key created",
            user_id=str(user_id),
            key_name=key_in.name,
        )

        return APIKeyResponse(
            id=uuid.uuid4(),
            name=key_in.name,
            api_key=api_key,
            expires_at=key_in.expires_at,
            scopes=key_in.scopes,
            created_at=datetime.now(timezone.utc),
        )

    async def get_user(self, user_id: UUID) -> User:
        stmt = select(User).where(User.id == user_id)
        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

        if not user:
            raise NotFoundError(f"User {user_id} not found")

        return user

    def require_role(self, user: User, *roles: str) -> None:
        if user.role not in roles:
            raise AuthorizationError(
                f"Insufficient permissions. Required roles: {', '.join(roles)}"
            )


import uuid


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    db: AsyncSession = Depends(get_db),
) -> Optional[User]:
    if not credentials:
        return None

    auth_service = AuthService(db)

    if credentials.scheme.lower() == "bearer":
        return await auth_service.authenticate_token(credentials.credentials)
    elif credentials.scheme.lower() == "apikey":
        return await auth_service.authenticate_api_key(credentials.credentials)
    else:
        raise AuthenticationError(f"Unsupported authentication scheme: {credentials.scheme}")
