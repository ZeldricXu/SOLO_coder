from typing import Any, Dict, Optional, Tuple
import secrets
import hashlib

from gateway.db import get_db
from gateway.db.repository import APIKeyRepository
from gateway.logger import get_logger

logger = get_logger("api-key")


class APIKeyValidator:
    def __init__(self):
        self._key_cache: Dict[str, Dict[str, Any]] = {}
        self._cache_ttl = 60

    def generate_api_key(self) -> str:
        return "sk_" + secrets.token_urlsafe(32)

    def hash_key(self, api_key: str) -> str:
        return hashlib.sha256(api_key.encode()).hexdigest()

    async def validate(self, api_key: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        if not api_key:
            return False, None, "API Key not provided"

        try:
            async for session in get_db():
                repo = APIKeyRepository(session)
                key_record = await repo.get_by_key(self.hash_key(api_key))

                if not key_record:
                    return False, None, "Invalid API Key"

                await repo.update_last_used(key_record.id)

                user_info = {
                    "user_id": key_record.user_id,
                    "tenant_id": key_record.tenant_id or "",
                    "api_key_id": str(key_record.id),
                    "scopes": key_record.scopes or [],
                    "allowed_paths": key_record.allowed_paths or [],
                    "rate_limit_quota": key_record.rate_limit_quota,
                    "auth_type": "api_key",
                }

                return True, user_info, None

        except Exception as e:
            logger.error("API Key validation error", error=str(e), exc_info=True)
            return False, None, f"Validation error: {str(e)}"

    def _check_path_allowed(self, allowed_paths: list, request_path: str) -> bool:
        if not allowed_paths:
            return True

        for pattern in allowed_paths:
            if request_path.startswith(pattern):
                return True

        return False


_api_key_instance: Optional[APIKeyValidator] = None


def get_api_key_validator() -> APIKeyValidator:
    global _api_key_instance
    if _api_key_instance is None:
        _api_key_instance = APIKeyValidator()
    return _api_key_instance
