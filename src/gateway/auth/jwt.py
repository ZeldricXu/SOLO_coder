from typing import Any, Dict, Optional, Tuple
from datetime import datetime, timezone
import httpx
from jose import JWTError, jwt
from jose.exceptions import ExpiredSignatureError, JWTClaimsError

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("jwt")


class JWTValidator:
    def __init__(self):
        self.settings = get_settings()
        self.jwt_settings = self.settings.jwt
        self._public_keys: Dict[str, str] = {}
        self._jwks_client: Optional[httpx.AsyncClient] = None

    def _get_algorithm(self) -> str:
        return self.jwt_settings.algorithm

    def _get_secret_key(self) -> str:
        return self.jwt_settings.secret_key

    async def validate(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        try:
            payload = jwt.decode(
                token,
                self._get_secret_key(),
                algorithms=[self._get_algorithm()],
                issuer=self.jwt_settings.issuer,
                audience=self.jwt_settings.audience,
                options={
                    "verify_signature": True,
                    "verify_exp": True,
                    "verify_nbf": True,
                    "verify_iat": True,
                    "verify_iss": True,
                    "verify_aud": True,
                    "require": ["exp", "iat", "sub"],
                },
            )

            user_info = self._extract_user_info(payload)
            return True, user_info, None

        except ExpiredSignatureError:
            logger.warning("JWT token expired")
            return False, None, "Token expired"
        except JWTClaimsError as e:
            logger.warning("JWT claims error", error=str(e))
            return False, None, f"Invalid claims: {str(e)}"
        except JWTError as e:
            logger.warning("JWT validation error", error=str(e))
            return False, None, f"Invalid token: {str(e)}"
        except Exception as e:
            logger.error("Unexpected JWT validation error", error=str(e), exc_info=True)
            return False, None, "Validation failed"

    def _extract_user_info(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "user_id": payload.get("sub", ""),
            "username": payload.get("preferred_username", payload.get("username", "")),
            "email": payload.get("email", ""),
            "roles": payload.get("roles", payload.get("realm_access", {}).get("roles", [])),
            "scopes": payload.get("scope", "").split(" ") if payload.get("scope") else [],
            "tenant_id": payload.get("tenant_id", payload.get("org_id", "")),
            "exp": payload.get("exp"),
            "iat": payload.get("iat"),
            "iss": payload.get("iss"),
        }

    def create_token(self, user_id: str, **claims) -> str:
        now = datetime.now(timezone.utc)
        payload = {
            "sub": user_id,
            "iat": int(now.timestamp()),
            "exp": int(now.timestamp()) + self.jwt_settings.access_token_expire_minutes * 60,
            "iss": self.jwt_settings.issuer,
            "aud": self.jwt_settings.audience,
            **claims,
        }
        return jwt.encode(payload, self._get_secret_key(), algorithm=self._get_algorithm())


_jwt_instance: Optional[JWTValidator] = None


def get_jwt_validator() -> JWTValidator:
    global _jwt_instance
    if _jwt_instance is None:
        _jwt_instance = JWTValidator()
    return _jwt_instance
