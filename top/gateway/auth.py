from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Dict, Optional, Set

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from top.core.models import BaseModel


class AuthMethod(str, Enum):
    API_KEY = "api_key"
    JWT = "jwt"
    BASIC = "basic"
    NONE = "none"


class PermissionLevel(str, Enum):
    READ = "read"
    WRITE = "write"
    ADMIN = "admin"


class Permission(BaseModel):
    name: str
    level: PermissionLevel
    resource_pattern: str

    def matches(self, resource: str, action: str) -> bool:
        import fnmatch
        if not fnmatch.fnmatch(resource, self.resource_pattern):
            return False
        action_level = PermissionLevel(action)
        level_hierarchy = {
            PermissionLevel.READ: 1,
            PermissionLevel.WRITE: 2,
            PermissionLevel.ADMIN: 3,
        }
        return level_hierarchy[action_level] <= level_hierarchy[self.level]


class Role(BaseModel):
    role_id: str
    name: str
    permissions: list[Permission] = field(default_factory=list)

    def has_permission(self, resource: str, action: str) -> bool:
        for perm in self.permissions:
            if perm.matches(resource, action):
                return True
        return False


class UserPrincipal(BaseModel):
    user_id: str
    username: str
    roles: list[str] = field(default_factory=list)
    claims: Dict[str, Any] = field(default_factory=dict)
    expires_at: Optional[datetime] = None
    token_fingerprint: Optional[str] = None

    def is_expired(self) -> bool:
        if self.expires_at is None:
            return False
        from top.core.models import utc_now
        return utc_now() > self.expires_at


class AuthResult(BaseModel):
    authenticated: bool
    principal: Optional[UserPrincipal] = None
    error: Optional[str] = None
    auth_method: AuthMethod = AuthMethod.NONE
    challenge: Optional[str] = None


@dataclass
class APIKeyEntry:
    key_hash: str
    user_id: str
    created_at: datetime
    expires_at: Optional[datetime]
    scopes: Set[str] = field(default_factory=set)
    revoked: bool = False


class AuthProvider(ABC):
    @abstractmethod
    def authenticate(self, credentials: Dict[str, Any]) -> AuthResult:
        pass

    @abstractmethod
    def authorize(self, principal: UserPrincipal, resource: str, action: str) -> bool:
        pass

    def extract_credentials(self, headers: Dict[str, str]) -> Optional[Dict[str, Any]]:
        return None


class APIKeyAuth(AuthProvider):
    def __init__(
        self,
        header_name: str = "X-API-Key",
        role_resolver: Optional[Dict[str, list[str]]] = None,
    ):
        self._header_name = header_name
        self._api_keys: Dict[str, APIKeyEntry] = {}
        self._role_resolver = role_resolver or {}
        self._roles: Dict[str, Role] = {}

    def _hash_key(self, api_key: str, salt: bytes) -> str:
        kdf = PBKDF2HMAC(
            algorithm=hashes.SHA256(),
            length=32,
            salt=salt,
            iterations=100000,
        )
        return base64.b64encode(kdf.derive(api_key.encode())).decode()

    def register_api_key(
        self,
        user_id: str,
        expires_in_days: int = 365,
        scopes: Optional[Set[str]] = None,
    ) -> str:
        api_key = "sk_" + secrets.token_hex(24)
        salt = secrets.token_bytes(16)
        key_hash = self._hash_key(api_key, salt)
        
        from top.core.models import utc_now
        entry = APIKeyEntry(
            key_hash=base64.b64encode(salt + b":" + key_hash.encode()).decode(),
            user_id=user_id,
            created_at=utc_now(),
            expires_at=utc_now() + timedelta(days=expires_in_days),
            scopes=scopes or set(),
        )
        self._api_keys[api_key[:8]] = entry
        return api_key

    def register_role(self, role: Role) -> None:
        self._roles[role.role_id] = role

    def extract_credentials(self, headers: Dict[str, str]) -> Optional[Dict[str, Any]]:
        api_key = headers.get(self._header_name)
        if api_key:
            return {"api_key": api_key}
        return None

    def authenticate(self, credentials: Dict[str, Any]) -> AuthResult:
        api_key = credentials.get("api_key")
        if not api_key:
            return AuthResult(
                authenticated=False,
                error="Missing API key",
                auth_method=AuthMethod.API_KEY,
            )

        key_prefix = api_key[:8] if len(api_key) >= 8 else api_key
        entry = self._api_keys.get(key_prefix)

        if not entry:
            return AuthResult(
                authenticated=False,
                error="Invalid API key",
                auth_method=AuthMethod.API_KEY,
            )

        if entry.revoked:
            return AuthResult(
                authenticated=False,
                error="API key has been revoked",
                auth_method=AuthMethod.API_KEY,
            )

        from top.core.models import utc_now
        if entry.expires_at and entry.expires_at < utc_now():
            return AuthResult(
                authenticated=False,
                error="API key has expired",
                auth_method=AuthMethod.API_KEY,
            )

        try:
            salt_key = base64.b64decode(entry.key_hash)
            salt, stored_hash_encoded = salt_key.split(b":", 1)
            computed_hash = self._hash_key(api_key, salt)
            
            if not hmac.compare_digest(stored_hash_encoded.decode(), computed_hash):
                return AuthResult(
                    authenticated=False,
                    error="Invalid API key",
                    auth_method=AuthMethod.API_KEY,
                )
        except Exception:
            return AuthResult(
                authenticated=False,
                error="Invalid API key format",
                auth_method=AuthMethod.API_KEY,
            )

        roles = self._role_resolver.get(entry.user_id, [])
        principal = UserPrincipal(
            user_id=entry.user_id,
            username=f"user_{entry.user_id}",
            roles=roles,
            claims={"scopes": list(entry.scopes)},
            expires_at=entry.expires_at,
            token_fingerprint=hashlib.sha256(api_key.encode()).hexdigest()[:16],
        )

        return AuthResult(
            authenticated=True,
            principal=principal,
            auth_method=AuthMethod.API_KEY,
        )

    def authorize(self, principal: UserPrincipal, resource: str, action: str) -> bool:
        for role_id in principal.roles:
            role = self._roles.get(role_id)
            if role and role.has_permission(resource, action):
                return True
        return False

    def revoke_api_key(self, key_prefix: str) -> bool:
        entry = self._api_keys.get(key_prefix)
        if entry:
            entry.revoked = True
            return True
        return False


class JWTAuth(AuthProvider):
    def __init__(
        self,
        secret_key: str,
        algorithm: str = "HS256",
        token_ttl: int = 3600,
        header_name: str = "Authorization",
    ):
        self._secret_key = secret_key
        self._algorithm = algorithm
        self._token_ttl = token_ttl
        self._header_name = header_name
        self._roles: Dict[str, Role] = {}
        self._revoked_tokens: Set[str] = set()

    def register_role(self, role: Role) -> None:
        self._roles[role.role_id] = role

    def create_token(
        self,
        user_id: str,
        username: str,
        roles: Optional[list[str]] = None,
        additional_claims: Optional[Dict[str, Any]] = None,
    ) -> str:
        import jwt
        from top.core.models import utc_now

        now = utc_now()
        payload = {
            "sub": user_id,
            "username": username,
            "iat": now,
            "exp": now + timedelta(seconds=self._token_ttl),
            "jti": secrets.token_hex(16),
            "roles": roles or [],
        }
        if additional_claims:
            payload.update(additional_claims)

        token = jwt.encode(payload, self._secret_key, algorithm=self._algorithm)
        return token

    def extract_credentials(self, headers: Dict[str, str]) -> Optional[Dict[str, Any]]:
        auth_header = headers.get(self._header_name)
        if auth_header and auth_header.startswith("Bearer "):
            return {"token": auth_header[7:]}
        return None

    def authenticate(self, credentials: Dict[str, Any]) -> AuthResult:
        import jwt

        token = credentials.get("token")
        if not token:
            return AuthResult(
                authenticated=False,
                error="Missing bearer token",
                auth_method=AuthMethod.JWT,
                challenge="Bearer",
            )

        try:
            payload = jwt.decode(
                token,
                self._secret_key,
                algorithms=[self._algorithm],
                options={"verify_exp": True},
            )
        except jwt.ExpiredSignatureError:
            return AuthResult(
                authenticated=False,
                error="Token has expired",
                auth_method=AuthMethod.JWT,
                challenge="Bearer",
            )
        except jwt.InvalidTokenError as e:
            return AuthResult(
                authenticated=False,
                error=f"Invalid token: {str(e)}",
                auth_method=AuthMethod.JWT,
                challenge="Bearer",
            )

        jti = payload.get("jti")
        if jti and jti in self._revoked_tokens:
            return AuthResult(
                authenticated=False,
                error="Token has been revoked",
                auth_method=AuthMethod.JWT,
                challenge="Bearer",
            )

        exp_timestamp = payload.get("exp")
        expires_at = datetime.fromtimestamp(exp_timestamp) if exp_timestamp else None

        principal = UserPrincipal(
            user_id=payload.get("sub", ""),
            username=payload.get("username", ""),
            roles=payload.get("roles", []),
            claims={k: v for k, v in payload.items() if k not in ["sub", "username", "roles", "iat", "exp", "jti"]},
            expires_at=expires_at,
            token_fingerprint=jti,
        )

        return AuthResult(
            authenticated=True,
            principal=principal,
            auth_method=AuthMethod.JWT,
        )

    def authorize(self, principal: UserPrincipal, resource: str, action: str) -> bool:
        for role_id in principal.roles:
            role = self._roles.get(role_id)
            if role and role.has_permission(resource, action):
                return True
        return False

    def revoke_token(self, jti: str) -> None:
        self._revoked_tokens.add(jti)


class BasicAuth(AuthProvider):
    def __init__(self, realm: str = "TOP Gateway"):
        self._realm = realm
        self._users: Dict[str, tuple[str, bytes, list[str]]] = {}
        self._roles: Dict[str, Role] = {}

    def register_user(
        self,
        username: str,
        password: str,
        user_id: Optional[str] = None,
        roles: Optional[list[str]] = None,
    ) -> None:
        salt = secrets.token_bytes(16)
        password_hash = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode(),
            salt,
            100000,
        )
        self._users[username] = (
            user_id or username,
            salt + b":" + password_hash,
            roles or [],
        )

    def register_role(self, role: Role) -> None:
        self._roles[role.role_id] = role

    def extract_credentials(self, headers: Dict[str, str]) -> Optional[Dict[str, Any]]:
        auth_header = headers.get("Authorization")
        if auth_header and auth_header.startswith("Basic "):
            return {"basic_token": auth_header[6:]}
        return None

    def authenticate(self, credentials: Dict[str, Any]) -> AuthResult:
        basic_token = credentials.get("basic_token")
        if not basic_token:
            return AuthResult(
                authenticated=False,
                error="Missing basic auth credentials",
                auth_method=AuthMethod.BASIC,
                challenge=f'Basic realm="{self._realm}"',
            )

        try:
            decoded = base64.b64decode(basic_token).decode()
            username, password = decoded.split(":", 1)
        except Exception:
            return AuthResult(
                authenticated=False,
                error="Invalid basic auth format",
                auth_method=AuthMethod.BASIC,
                challenge=f'Basic realm="{self._realm}"',
            )

        user_info = self._users.get(username)
        if not user_info:
            return AuthResult(
                authenticated=False,
                error="Invalid credentials",
                auth_method=AuthMethod.BASIC,
                challenge=f'Basic realm="{self._realm}"',
            )

        user_id, stored_hash, roles = user_info
        salt, password_hash = stored_hash.split(b":", 1)

        computed_hash = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode(),
            salt,
            100000,
        )

        if not hmac.compare_digest(password_hash, computed_hash):
            return AuthResult(
                authenticated=False,
                error="Invalid credentials",
                auth_method=AuthMethod.BASIC,
                challenge=f'Basic realm="{self._realm}"',
            )

        principal = UserPrincipal(
            user_id=user_id,
            username=username,
            roles=roles,
            claims={},
        )

        return AuthResult(
            authenticated=True,
            principal=principal,
            auth_method=AuthMethod.BASIC,
        )

    def authorize(self, principal: UserPrincipal, resource: str, action: str) -> bool:
        for role_id in principal.roles:
            role = self._roles.get(role_id)
            if role and role.has_permission(resource, action):
                return True
        return False


_auth_provider_instance: Optional[AuthProvider] = None


def get_auth_provider(
    method: str = "jwt",
    **kwargs,
) -> AuthProvider:
    global _auth_provider_instance

    if _auth_provider_instance is not None:
        return _auth_provider_instance

    method_map = {
        "api_key": APIKeyAuth,
        "jwt": JWTAuth,
        "basic": BasicAuth,
    }

    provider_class = method_map.get(method, JWTAuth)
    _auth_provider_instance = provider_class(**kwargs)
    return _auth_provider_instance
