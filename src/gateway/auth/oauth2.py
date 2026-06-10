from abc import ABC, abstractmethod
from typing import Any, Dict, Optional, Tuple
import httpx
from jose import jwt, JWTError
from jose.backends import RSAKey

from gateway.config import get_settings
from gateway.db.repository import IdPConfigRepository
from gateway.logger import get_logger

logger = get_logger("oauth2")


class OAuth2Plugin(ABC):
    def __init__(self, config: Dict[str, Any]):
        self.config = config
        self._jwks: Dict[str, RSAKey] = {}
        self._http_client = httpx.AsyncClient(timeout=10.0)

    @abstractmethod
    async def validate_token(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        pass

    async def _fetch_jwks(self) -> Dict[str, RSAKey]:
        jwks_url = self.config.get("jwks_url")
        if not jwks_url:
            return {}

        try:
            response = await self._http_client.get(jwks_url)
            response.raise_for_status()
            jwks = response.json()

            keys = {}
            for key in jwks.get("keys", []):
                if key.get("use") == "sig":
                    kid = key.get("kid")
                    if kid:
                        keys[kid] = RSAKey(key, algorithm=key.get("alg", "RS256"))

            self._jwks = keys
            return keys
        except Exception as e:
            logger.error("Failed to fetch JWKS", url=jwks_url, error=str(e))
            return {}

    async def _validate_with_jwks(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        try:
            unverified_header = jwt.get_unverified_header(token)
            kid = unverified_header.get("kid")

            if kid not in self._jwks:
                await self._fetch_jwks()

            if kid not in self._jwks:
                return False, None, "Unknown signing key"

            key = self._jwks[kid]
            payload = jwt.decode(
                token,
                key,
                algorithms=[unverified_header.get("alg", "RS256")],
                issuer=self.config.get("issuer"),
                audience=self.config.get("audience"),
                options={"verify_aud": bool(self.config.get("audience"))},
            )

            return True, self._extract_claims(payload), None

        except JWTError as e:
            return False, None, f"Token validation failed: {str(e)}"
        except Exception as e:
            logger.error("Token validation error", error=str(e))
            return False, None, "Validation error"

    def _extract_claims(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "user_id": payload.get("sub", ""),
            "username": payload.get("preferred_username", payload.get("username", "")),
            "email": payload.get("email", ""),
            "roles": payload.get("roles", payload.get("realm_access", {}).get("roles", [])),
            "scopes": payload.get("scope", "").split(" ") if payload.get("scope") else [],
            "tenant_id": payload.get("tenant_id", payload.get("org_id", "")),
        }

    async def introspect_token(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        introspect_url = self.config.get("introspect_url")
        client_id = self.config.get("client_id")
        client_secret = self.config.get("client_secret")

        if not introspect_url or not client_id or not client_secret:
            return False, None, "Introspection not configured"

        try:
            response = await self._http_client.post(
                introspect_url,
                data={"token": token, "token_type_hint": "access_token"},
                auth=(client_id, client_secret),
            )
            response.raise_for_status()
            data = response.json()

            if data.get("active", False):
                return True, self._extract_claims(data), None
            return False, None, "Token is not active"

        except Exception as e:
            logger.error("Token introspection failed", error=str(e))
            return False, None, "Introspection failed"

    async def close(self) -> None:
        await self._http_client.aclose()


class KeycloakPlugin(OAuth2Plugin):
    async def validate_token(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        if self.config.get("introspect_url"):
            return await self.introspect_token(token)
        return await self._validate_with_jwks(token)


class Auth0Plugin(OAuth2Plugin):
    async def validate_token(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        return await self._validate_with_jwks(token)


class CustomOAuthPlugin(OAuth2Plugin):
    async def validate_token(self, token: str) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        if self.config.get("validation_type") == "introspect":
            return await self.introspect_token(token)
        return await self._validate_with_jwks(token)


_plugin_registry: Dict[str, type] = {
    "keycloak": KeycloakPlugin,
    "auth0": Auth0Plugin,
    "custom": CustomOAuthPlugin,
}

_plugins: Dict[str, OAuth2Plugin] = {}


async def get_oauth2_plugin(idp_name: str, repo: Optional[IdPConfigRepository] = None) -> Optional[OAuth2Plugin]:
    if idp_name in _plugins:
        return _plugins[idp_name]

    if not repo:
        return None

    idp_config = await repo.get_by_name(idp_name)
    if not idp_config:
        return None

    plugin_class = _plugin_registry.get(idp_config.provider)
    if not plugin_class:
        logger.error("Unknown IdP provider", provider=idp_config.provider)
        return None

    plugin = plugin_class(idp_config.config)
    _plugins[idp_name] = plugin
    return plugin
