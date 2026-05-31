from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Set

from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, SecretStr

from src.common.exceptions import UnauthorizedError, ForbiddenError

logger = logging.getLogger(__name__)


class User(BaseModel):
    user_id: str
    username: str
    email: str
    roles: List[str] = []
    permissions: List[str] = []
    tenant_id: Optional[str] = None
    is_active: bool = True


class TokenData(BaseModel):
    user_id: str
    username: str
    roles: List[str] = []
    permissions: List[str] = []
    tenant_id: Optional[str] = None
    exp: Optional[datetime] = None


class AuthService:
    def __init__(
        self,
        secret_key: str,
        algorithm: str = "HS256",
        access_token_expire_minutes: int = 30,
    ) -> None:
        self.secret_key = secret_key
        self.algorithm = algorithm
        self.access_token_expire_minutes = access_token_expire_minutes
        self.pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
        self._users: Dict[str, Dict[str, Any]] = {}
        self._api_keys: Dict[str, Dict[str, Any]] = {}

    def hash_password(self, password: str) -> str:
        return self.pwd_context.hash(password)

    def verify_password(self, plain_password: str, hashed_password: str) -> bool:
        return self.pwd_context.verify(plain_password, hashed_password)

    def create_access_token(
        self,
        user: User,
        expires_delta: Optional[timedelta] = None,
    ) -> str:
        expire = datetime.now(timezone.utc) + (expires_delta or timedelta(minutes=self.access_token_expire_minutes))
        to_encode = {
            "sub": user.user_id,
            "username": user.username,
            "roles": user.roles,
            "permissions": user.permissions,
            "tenant_id": user.tenant_id,
            "exp": expire,
        }
        encoded_jwt = jwt.encode(to_encode, self.secret_key, algorithm=self.algorithm)
        return encoded_jwt

    def decode_token(self, token: str) -> TokenData:
        try:
            payload = jwt.decode(token, self.secret_key, algorithms=[self.algorithm])
            user_id: str = payload.get("sub", "")
            username: str = payload.get("username", "")
            if not user_id:
                raise UnauthorizedError("Invalid token: missing subject")
            return TokenData(
                user_id=user_id,
                username=username,
                roles=payload.get("roles", []),
                permissions=payload.get("permissions", []),
                tenant_id=payload.get("tenant_id"),
                exp=datetime.fromtimestamp(payload["exp"], timezone.utc) if "exp" in payload else None,
            )
        except JWTError as e:
            logger.error(f"JWT decode error: {e}")
            raise UnauthorizedError(f"Invalid token: {e}")

    def register_user(self, user: User, password: str) -> None:
        self._users[user.username] = {
            "user": user,
            "hashed_password": self.hash_password(password),
        }

    def authenticate_user(self, username: str, password: str) -> User:
        user_data = self._users.get(username)
        if not user_data:
            raise UnauthorizedError("Invalid credentials")
        if not self.verify_password(password, user_data["hashed_password"]):
            raise UnauthorizedError("Invalid credentials")
        user: User = user_data["user"]
        if not user.is_active:
            raise UnauthorizedError("User is inactive")
        return user

    def register_api_key(self, api_key: str, user: User, scopes: List[str]) -> None:
        self._api_keys[api_key] = {"user": user, "scopes": scopes}

    def authenticate_api_key(self, api_key: str) -> User:
        key_data = self._api_keys.get(api_key)
        if not key_data:
            raise UnauthorizedError("Invalid API key")
        return key_data["user"]


class PermissionChecker:
    def __init__(self) -> None:
        self._role_permissions: Dict[str, Set[str]] = {}
        self._user_permissions: Dict[str, Set[str]] = {}

    def add_role_permissions(self, role: str, permissions: List[str]) -> None:
        if role not in self._role_permissions:
            self._role_permissions[role] = set()
        self._role_permissions[role].update(permissions)

    def add_user_permissions(self, user_id: str, permissions: List[str]) -> None:
        if user_id not in self._user_permissions:
            self._user_permissions[user_id] = set()
        self._user_permissions[user_id].update(permissions)

    def get_user_permissions(self, token_data: TokenData) -> Set[str]:
        permissions: Set[str] = set(token_data.permissions)
        for role in token_data.roles:
            permissions.update(self._role_permissions.get(role, set()))
        permissions.update(self._user_permissions.get(token_data.user_id, set()))
        return permissions

    def has_permission(self, token_data: TokenData, required_permission: str) -> bool:
        user_permissions = self.get_user_permissions(token_data)
        if "admin" in token_data.roles or "*" in user_permissions:
            return True
        if required_permission in user_permissions:
            return True
        if ":" in required_permission:
            parts = required_permission.split(":")
            for i in range(1, len(parts)):
                wildcard = ":".join(parts[:i] + ["*"])
                if wildcard in user_permissions:
                    return True
        return False

    def has_role(self, token_data: TokenData, required_role: str) -> bool:
        if "admin" in token_data.roles:
            return True
        return required_role in token_data.roles

    def require_permission(self, token_data: TokenData, permission: str) -> None:
        if not self.has_permission(token_data, permission):
            raise ForbiddenError(f"Missing required permission: {permission}")

    def require_role(self, token_data: TokenData, role: str) -> None:
        if not self.has_role(token_data, role):
            raise ForbiddenError(f"Missing required role: {role}")
