import uuid
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from datetime import datetime, timedelta
from enum import Enum
from ..config import settings
from .logging_module import get_logger

logger = get_logger(__name__)


class EnvironmentStatus(str, Enum):
    PROVISIONING = "provisioning"
    READY = "ready"
    BUSY = "busy"
    STOPPED = "stopped"
    ERROR = "error"
    EXPIRED = "expired"


class EnvironmentType(str, Enum):
    PREVIEW = "preview"
    STAGING = "staging"
    DEVELOPMENT = "development"


@dataclass
class PreviewEnvironment:
    env_id: str
    name: str
    type: EnvironmentType
    owner: str
    status: EnvironmentStatus
    config: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.utcnow)
    expires_at: Optional[datetime] = None
    last_used_at: Optional[datetime] = None
    endpoints: Dict[str, str] = field(default_factory=dict)
    cpu_hours: float = 0.0
    memory_gb_hours: float = 0.0
    requests_served: int = 0


@dataclass
class UsageStats:
    total_created: int = 0
    total_deleted: int = 0
    total_cpu_hours: float = 0.0
    total_memory_gb_hours: float = 0.0
    per_user: Dict[str, Dict[str, Any]] = field(default_factory=dict)


class EnvironmentManager:
    def __init__(self):
        self._envs: Dict[str, PreviewEnvironment] = {}
        self._user_envs: Dict[str, List[str]] = {}
        self._stats = UsageStats()
        self._max_per_user = settings.max_environments_per_user
        self._ttl = settings.environment_ttl_hours

    def create(self, name: str, owner: str, env_type: EnvironmentType = EnvironmentType.PREVIEW,
               config: Optional[Dict] = None, ttl_hours: Optional[int] = None) -> PreviewEnvironment:
        active = sum(1 for eid in self._user_envs.get(owner, [])
                     if self._envs.get(eid) and self._envs[eid].status not in
                     [EnvironmentStatus.STOPPED, EnvironmentStatus.EXPIRED])
        if active >= self._max_per_user:
            raise ValueError(f"User {owner} has reached max environments ({self._max_per_user})")

        env_id = f"env_{uuid.uuid4().hex[:8]}"
        actual_ttl = ttl_hours or self._ttl
        env = PreviewEnvironment(
            env_id=env_id, name=name, type=env_type, owner=owner,
            status=EnvironmentStatus.PROVISIONING,
            config=config or {},
            expires_at=datetime.utcnow() + timedelta(hours=actual_ttl),
            endpoints={
                "web": f"https://{name}-{env_id[:8]}.preview.example.com",
                "api": f"https://api-{name}-{env_id[:8]}.preview.example.com",
            },
        )
        self._envs[env_id] = env
        self._user_envs.setdefault(owner, []).append(env_id)
        self._stats.total_created += 1
        env.status = EnvironmentStatus.READY
        logger.info(f"Created environment {env_id} for {owner}")
        return env

    def get(self, env_id: str) -> Optional[PreviewEnvironment]:
        return self._envs.get(env_id)

    def list(self, owner: Optional[str] = None, status: Optional[EnvironmentStatus] = None) -> List[PreviewEnvironment]:
        envs = list(self._envs.values())
        if owner:
            envs = [e for e in envs if e.owner == owner]
        if status:
            envs = [e for e in envs if e.status == status]
        return envs

    def stop(self, env_id: str) -> bool:
        env = self._envs.get(env_id)
        if not env:
            return False
        env.status = EnvironmentStatus.STOPPED
        logger.info(f"Stopped {env_id}")
        return True

    def delete(self, env_id: str) -> bool:
        env = self._envs.pop(env_id, None)
        if not env:
            return False
        self._stats.total_deleted += 1
        self._stats.total_cpu_hours += env.cpu_hours
        self._stats.total_memory_gb_hours += env.memory_gb_hours
        if env.owner in self._user_envs:
            self._user_envs[env.owner] = [e for e in self._user_envs[env.owner] if e != env_id]
        logger.info(f"Deleted {env_id}")
        return True

    def extend_ttl(self, env_id: str, hours: int) -> bool:
        env = self._envs.get(env_id)
        if not env or env.status in [EnvironmentStatus.STOPPED, EnvironmentStatus.ERROR]:
            return False
        if env.expires_at:
            env.expires_at += timedelta(hours=hours)
        return True

    def check_expired(self) -> List[str]:
        now = datetime.utcnow()
        expired = []
        for env_id, env in self._envs.items():
            if env.expires_at and env.expires_at < now and env.status not in [EnvironmentStatus.STOPPED, EnvironmentStatus.EXPIRED]:
                env.status = EnvironmentStatus.EXPIRED
                expired.append(env_id)
                logger.info(f"Expired: {env_id}")
        return expired

    def record_usage(self, env_id: str, cpu: float, memory: float, requests: int = 1) -> None:
        env = self._envs.get(env_id)
        if env:
            env.last_used_at = datetime.utcnow()
            env.cpu_hours += cpu
            env.memory_gb_hours += memory
            env.requests_served += requests

    def get_stats(self) -> UsageStats:
        return self._stats

    def get_user_usage(self, owner: str) -> Dict[str, Any]:
        envs = [e for e in self._envs.values() if e.owner == owner]
        return {
            "owner": owner,
            "active_count": sum(1 for e in envs if e.status == EnvironmentStatus.READY),
            "total_count": len(envs),
            "max_allowed": self._max_per_user,
            "total_cpu_hours": sum(e.cpu_hours for e in envs),
            "total_memory_gb_hours": sum(e.memory_gb_hours for e in envs),
        }


_env_manager: Optional[EnvironmentManager] = None


def get_environment_manager() -> EnvironmentManager:
    global _env_manager
    if _env_manager is None:
        _env_manager = EnvironmentManager()
    return _env_manager
