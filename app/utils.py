import hashlib
import uuid
import secrets
import string
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from passlib.context import CryptContext
from jose import jwt
import time

from app.config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def generate_uuid() -> str:
    return str(uuid.uuid4())


def generate_short_id(prefix: str = "") -> str:
    random_part = secrets.token_hex(6)
    return f"{prefix}{random_part}" if prefix else random_part


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)


def create_access_token(data: Dict[str, Any], expires_delta: Optional[int] = None) -> str:
    to_encode = data.copy()
    expire = time.time() + (expires_delta or settings.access_token_expire_minutes * 60)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, settings.secret_key, algorithm=settings.algorithm)
    return encoded_jwt


def decode_access_token(token: str) -> Dict[str, Any]:
    return jwt.decode(token, settings.secret_key, algorithms=[settings.algorithm])


def hash_api_key(api_key: str) -> str:
    return hashlib.sha256(api_key.encode()).hexdigest()


def generate_api_key() -> str:
    alphabet = string.ascii_letters + string.digits
    return "sk_" + "".join(secrets.choice(alphabet) for _ in range(48))


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def calculate_checksum(data: str) -> str:
    return hashlib.sha256(data.encode()).hexdigest()
