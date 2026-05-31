import secrets
from typing import Dict, List, Optional, Tuple
from pydantic import BaseModel, Field

from .config import settings
from .utils import generate_id


class Share(BaseModel):
    share_id: str = Field(..., description="分片ID")
    index: int = Field(..., description="分片索引")
    value: str = Field(..., description="分片值(十六进制)")
    key_id: str = Field(..., description="密钥ID")
    holder: Optional[str] = Field(None, description="持有者标识")
    created_at: str = Field(..., description="创建时间")


class KeyMetadata(BaseModel):
    key_id: str = Field(..., description="密钥ID")
    threshold: int = Field(..., description="恢复阈值")
    total_shares: int = Field(..., description="总分片数")
    original_length: int = Field(..., description="原始密钥长度")
    created_at: str = Field(..., description="创建时间")
    holders: List[str] = Field(default_factory=list, description="持有者列表")


class ShamirSecretSharing:
    def __init__(self, prime: int = None):
        self.prime = prime or (2 ** 521 - 1)

    def _eval_polynomial(self, coefficients: List[int], x: int) -> int:
        result = 0
        for coeff in reversed(coefficients):
            result = (result * x + coeff) % self.prime
        return result

    def split_secret(self, secret: bytes, threshold: int, total: int) -> List[Tuple[int, int]]:
        if threshold > total:
            raise ValueError("Threshold cannot be greater than total shares")
        if threshold < 2:
            raise ValueError("Threshold must be at least 2")

        secret_int = int.from_bytes(secret, byteorder="big")

        if secret_int >= self.prime:
            raise ValueError("Secret too large for the prime field")

        coefficients = [secret_int]
        for _ in range(threshold - 1):
            coefficients.append(secrets.randbelow(self.prime - 1) + 1)

        shares = []
        for i in range(1, total + 1):
            y = self._eval_polynomial(coefficients, i)
            shares.append((i, y))

        return shares

    def _mod_inverse(self, a: int, m: int) -> int:
        def extended_gcd(a: int, b: int) -> Tuple[int, int, int]:
            if a == 0:
                return b, 0, 1
            gcd, x1, y1 = extended_gcd(b % a, a)
            x = y1 - (b // a) * x1
            y = x1
            return gcd, x, y

        _, x, _ = extended_gcd(a % m, m)
        return (x % m + m) % m

    def _lagrange_interpolate(self, x: int, points: List[Tuple[int, int]]) -> int:
        n = len(points)
        result = 0

        for i in range(n):
            xi, yi = points[i]
            numerator = 1
            denominator = 1

            for j in range(n):
                if i == j:
                    continue
                xj, _ = points[j]
                numerator = (numerator * (x - xj)) % self.prime
                denominator = (denominator * (xi - xj)) % self.prime

            lagrange = (numerator * self._mod_inverse(denominator, self.prime)) % self.prime
            result = (result + yi * lagrange) % self.prime

        return result

    def reconstruct_secret(self, shares: List[Tuple[int, int]], original_length: int) -> bytes:
        if len(shares) < 2:
            raise ValueError("At least 2 shares are required")

        indices = [s[0] for s in shares]
        if len(set(indices)) != len(indices):
            raise ValueError("Duplicate share indices")

        secret_int = self._lagrange_interpolate(0, shares)
        return secret_int.to_bytes(original_length, byteorder="big")


class KeyShardManager:
    def __init__(self):
        self.shamir = ShamirSecretSharing()
        self.keys: Dict[str, KeyMetadata] = {}
        self.shares: Dict[str, List[Share]] = {}

    def generate_and_split_key(
        self,
        key_length: int = 32,
        threshold: int = None,
        total: int = None,
        holders: Optional[List[str]] = None
    ) -> Tuple[KeyMetadata, List[Share], bytes]:
        threshold = threshold or settings.shamir_default_threshold
        total = total or settings.shamir_default_total
        holders = holders or []

        secret_key = secrets.token_bytes(key_length)
        raw_shares = self.shamir.split_secret(secret_key, threshold, total)

        from datetime import datetime
        key_id = generate_id("key_")
        now = datetime.utcnow().isoformat()

        key_metadata = KeyMetadata(
            key_id=key_id,
            threshold=threshold,
            total_shares=total,
            original_length=key_length,
            created_at=now,
            holders=holders
        )

        share_objects = []
        for idx, (index, value) in enumerate(raw_shares):
            holder = holders[idx] if holders and idx < len(holders) else None
            share = Share(
                share_id=generate_id("share_"),
                index=index,
                value=hex(value),
                key_id=key_id,
                holder=holder,
                created_at=now
            )
            share_objects.append(share)

        self.keys[key_id] = key_metadata
        self.shares[key_id] = share_objects

        return key_metadata, share_objects, secret_key

    def split_existing_key(
        self,
        secret_key: bytes,
        threshold: int = None,
        total: int = None,
        holders: Optional[List[str]] = None
    ) -> Tuple[KeyMetadata, List[Share]]:
        threshold = threshold or settings.shamir_default_threshold
        total = total or settings.shamir_default_total
        holders = holders or []

        raw_shares = self.shamir.split_secret(secret_key, threshold, total)

        from datetime import datetime
        key_id = generate_id("key_")
        now = datetime.utcnow().isoformat()

        key_metadata = KeyMetadata(
            key_id=key_id,
            threshold=threshold,
            total_shares=total,
            original_length=len(secret_key),
            created_at=now,
            holders=holders
        )

        share_objects = []
        for idx, (index, value) in enumerate(raw_shares):
            holder = holders[idx] if holders and idx < len(holders) else None
            share = Share(
                share_id=generate_id("share_"),
                index=index,
                value=hex(value),
                key_id=key_id,
                holder=holder,
                created_at=now
            )
            share_objects.append(share)

        self.keys[key_id] = key_metadata
        self.shares[key_id] = share_objects

        return key_metadata, share_objects

    def reconstruct_key(self, shares: List[Share]) -> bytes:
        if not shares:
            raise ValueError("No shares provided")

        key_id = shares[0].key_id
        if not all(s.key_id == key_id for s in shares):
            raise ValueError("Shares from different keys cannot be combined")

        if key_id not in self.keys:
            raise ValueError(f"Key {key_id} not found")

        key_metadata = self.keys[key_id]
        if len(shares) < key_metadata.threshold:
            raise ValueError(
                f"Insufficient shares: need {key_metadata.threshold}, got {len(shares)}"
            )

        raw_shares = [(s.index, int(s.value, 16)) for s in shares]
        return self.shamir.reconstruct_secret(raw_shares, key_metadata.original_length)

    def get_key_metadata(self, key_id: str) -> Optional[KeyMetadata]:
        return self.keys.get(key_id)

    def get_shares_by_key(self, key_id: str) -> List[Share]:
        return self.shares.get(key_id, [])

    def get_share_by_holder(self, key_id: str, holder: str) -> Optional[Share]:
        if key_id not in self.shares:
            return None
        for share in self.shares[key_id]:
            if share.holder == holder:
                return share
        return None

    def assign_holder(self, key_id: str, share_index: int, holder: str) -> bool:
        if key_id not in self.shares:
            return False
        for share in self.shares[key_id]:
            if share.index == share_index:
                share.holder = holder
                if key_id in self.keys and holder not in self.keys[key_id].holders:
                    self.keys[key_id].holders.append(holder)
                return True
        return False

    def verify_shares(self, shares: List[Share]) -> bool:
        if not shares:
            return False
        try:
            self.reconstruct_key(shares)
            return True
        except Exception:
            return False

    def list_keys(self) -> List[KeyMetadata]:
        return list(self.keys.values())

    def delete_key(self, key_id: str) -> bool:
        if key_id in self.keys:
            del self.keys[key_id]
            if key_id in self.shares:
                del self.shares[key_id]
            return True
        return False


_shard_manager_instance: Optional[KeyShardManager] = None


def get_shard_manager() -> KeyShardManager:
    global _shard_manager_instance
    if _shard_manager_instance is None:
        _shard_manager_instance = KeyShardManager()
    return _shard_manager_instance
