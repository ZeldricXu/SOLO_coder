import time
import hashlib
import asyncio
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Callable, Deque
from collections import deque
from fastapi import Request, HTTPException, status, Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import JWTError, jwt
from passlib.context import CryptContext
from app.config import settings
from app.logger import logger


pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
security = HTTPBearer(auto_error=False)


class AuthError(Exception):
    pass


class RateLimitError(Exception):
    pass


class CircuitBreakerOpenError(Exception):
    pass


class RateLimiter:
    def __init__(self):
        self._storage: Dict[str, Dict[str, Any]] = {}
    
    def check_rate_limit(
        self,
        key: str,
        limit: int = 100,
        window_seconds: int = 60
    ) -> Dict[str, Any]:
        now = time.time()
        window_start = now - window_seconds
        
        if key not in self._storage:
            self._storage[key] = {"requests": [], "limit": limit, "window": window_seconds}
        
        storage = self._storage[key]
        storage["requests"] = [
            ts for ts in storage["requests"]
            if ts > window_start
        ]
        
        remaining = limit - len(storage["requests"])
        
        if remaining <= 0:
            oldest_request = min(storage["requests"])
            retry_after = int(window_start + window_seconds - oldest_request)
            raise RateLimitError(f"Rate limit exceeded. Retry after {retry_after} seconds.")
        
        storage["requests"].append(now)
        
        return {
            "limit": limit,
            "remaining": remaining - 1,
            "reset": int(window_start + window_seconds)
        }
    
    def reset(self, key: str):
        if key in self._storage:
            del self._storage[key]
    
    def get_metrics(self) -> Dict[str, Any]:
        total_keys = len(self._storage)
        total_requests = sum(len(v["requests"]) for v in self._storage.values())
        return {
            "total_keys": total_keys,
            "total_tracked_requests": total_requests
        }


rate_limiter = RateLimiter()


class CircuitBreaker:
    def __init__(
        self,
        name: str,
        failure_threshold: int = None,
        recovery_timeout: int = None
    ):
        self.name = name
        self.failure_threshold = failure_threshold or settings.CIRCUIT_BREAKER_FAILURE_THRESHOLD
        self.recovery_timeout = recovery_timeout or settings.CIRCUIT_BREAKER_RECOVERY_TIMEOUT
        
        self._state = "closed"
        self._failures: Deque[float] = deque(maxlen=self.failure_threshold)
        self._last_failure_time: Optional[float] = None
        self._open_time: Optional[float] = None
        self._success_count = 0
        self._metrics = {
            "total_requests": 0,
            "total_successes": 0,
            "total_failures": 0,
            "state_changes": 0
        }
    
    @property
    def state(self) -> str:
        if self._state == "open" and time.time() - self._open_time >= self.recovery_timeout:
            self._state = "half_open"
            self._success_count = 0
        return self._state
    
    def allow_request(self) -> bool:
        self._metrics["total_requests"] += 1
        
        state = self.state
        if state == "open":
            return False
        return True
    
    def record_success(self):
        self._metrics["total_successes"] += 1
        
        if self._state == "half_open":
            self._success_count += 1
            if self._success_count >= 3:
                self._state = "closed"
                self._failures.clear()
                self._metrics["state_changes"] += 1
                logger.info("Circuit breaker closed", name=self.name)
        
        elif self._state == "closed":
            if self._failures:
                self._failures.pop() if len(self._failures) > 0 else None
    
    def record_failure(self):
        self._metrics["total_failures"] += 1
        
        now = time.time()
        self._failures.append(now)
        self._last_failure_time = now
        
        if len(self._failures) >= self.failure_threshold:
            recent_failures = [f for f in self._failures if now - f < 60]
            if len(recent_failures) >= self.failure_threshold:
                self._open()
    
    def _open(self):
        self._state = "open"
        self._open_time = time.time()
        self._metrics["state_changes"] += 1
        logger.warning(
            "Circuit breaker opened",
            name=self.name,
            threshold=self.failure_threshold
        )
    
    def force_close(self):
        self._state = "closed"
        self._failures.clear()
        self._success_count = 0
        self._metrics["state_changes"] += 1
        logger.info("Circuit breaker forced closed", name=self.name)
    
    def get_metrics(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "state": self.state,
            "failure_threshold": self.failure_threshold,
            "recovery_timeout": self.recovery_timeout,
            "current_failures": len(self._failures),
            **self._metrics
        }


class CircuitBreakerRegistry:
    def __init__(self):
        self._breakers: Dict[str, CircuitBreaker] = {}
    
    def get(self, name: str) -> CircuitBreaker:
        if name not in self._breakers:
            self._breakers[name] = CircuitBreaker(name)
        return self._breakers[name]
    
    def get_all_metrics(self) -> Dict[str, Any]:
        return {
            name: breaker.get_metrics()
            for name, breaker in self._breakers.items()
        }
    
    def reset_all(self):
        for breaker in self._breakers.values():
            breaker.force_close()


circuit_breaker_registry = CircuitBreakerRegistry()


class InstanceMetrics:
    def __init__(self, window_seconds: int = 60):
        self.window_seconds = window_seconds
        self._latencies: Deque[float] = deque(maxlen=1000)
        self._timestamps: Deque[float] = deque(maxlen=1000)
        self._errors: Deque[float] = deque(maxlen=1000)
        self._cpu_samples: Deque[float] = deque(maxlen=60)
        self._memory_samples: Deque[float] = deque(maxlen=60)
        self._active_requests = 0
        self._lock = asyncio.Lock()
    
    async def record_request(self, latency_ms: float, success: bool = True):
        async with self._lock:
            now = time.time()
            self._latencies.append(latency_ms)
            self._timestamps.append(now)
            if not success:
                self._errors.append(now)
    
    async def record_cpu(self, cpu_percent: float):
        async with self._lock:
            self._cpu_samples.append(cpu_percent)
    
    async def record_memory(self, memory_percent: float):
        async with self._lock:
            self._memory_samples.append(memory_percent)
    
    async def increment_active(self):
        async with self._lock:
            self._active_requests += 1
    
    async def decrement_active(self):
        async with self._lock:
            self._active_requests -= 1
    
    def get_metrics(self) -> Dict[str, Any]:
        now = time.time()
        window_start = now - self.window_seconds
        
        recent_latencies = [
            l for l, t in zip(self._latencies, self._timestamps)
            if t > window_start
        ]
        recent_errors = [e for e in self._errors if e > window_start]
        
        avg_latency = sum(recent_latencies) / len(recent_latencies) if recent_latencies else 0
        p95_latency = self._percentile(recent_latencies, 95) if recent_latencies else 0
        p99_latency = self._percentile(recent_latencies, 99) if recent_latencies else 0
        
        error_rate = len(recent_errors) / max(len(recent_latencies), 1) * 100
        
        avg_cpu = sum(self._cpu_samples) / len(self._cpu_samples) if self._cpu_samples else 0
        avg_memory = sum(self._memory_samples) / len(self._memory_samples) if self._memory_samples else 0
        
        return {
            "requests_per_second": len(recent_latencies) / self.window_seconds,
            "avg_latency_ms": round(avg_latency, 2),
            "p95_latency_ms": round(p95_latency, 2),
            "p99_latency_ms": round(p99_latency, 2),
            "error_rate_percent": round(error_rate, 2),
            "active_requests": self._active_requests,
            "avg_cpu_percent": round(avg_cpu, 2),
            "avg_memory_percent": round(avg_memory, 2)
        }
    
    def _percentile(self, data: List[float], percentile: int) -> float:
        if not data:
            return 0
        sorted_data = sorted(data)
        index = min(int(len(sorted_data) * percentile / 100), len(sorted_data) - 1)
        return sorted_data[index]


class InstanceManager:
    def __init__(self):
        self._instances: Dict[str, InstanceMetrics] = {}
        self._instance_weights: Dict[str, float] = {}
        self._lock = asyncio.Lock()
    
    async def register_instance(self, instance_id: str, initial_weight: float = 1.0):
        async with self._lock:
            if instance_id not in self._instances:
                self._instances[instance_id] = InstanceMetrics()
                self._instance_weights[instance_id] = initial_weight
                logger.info("Instance registered", instance_id=instance_id)
    
    async def deregister_instance(self, instance_id: str):
        async with self._lock:
            if instance_id in self._instances:
                del self._instances[instance_id]
                del self._instance_weights[instance_id]
                logger.info("Instance deregistered", instance_id=instance_id)
    
    async def update_weight(self, instance_id: str, weight: float):
        async with self._lock:
            if instance_id in self._instance_weights:
                self._instance_weights[instance_id] = max(0.1, min(5.0, weight))
    
    def get_instance_ids(self) -> List[str]:
        return list(self._instances.keys())
    
    def get_instance_metrics(self, instance_id: str) -> Optional[Dict[str, Any]]:
        if instance_id in self._instances:
            return self._instances[instance_id].get_metrics()
        return None
    
    def get_all_metrics(self) -> Dict[str, Any]:
        return {
            instance_id: metrics.get_metrics()
            for instance_id, metrics in self._instances.items()
        }
    
    def get_weight(self, instance_id: str) -> float:
        return self._instance_weights.get(instance_id, 1.0)
    
    def select_instance(self, routing_key: str = None) -> str:
        if not self._instances:
            raise RuntimeError("No instances available")
        
        instance_ids = list(self._instances.keys())
        
        if routing_key:
            hash_value = hash(routing_key)
            idx = hash_value % len(instance_ids)
            return instance_ids[idx]
        
        total_weight = sum(self._instance_weights.values())
        if total_weight <= 0:
            return instance_ids[0]
        
        pick = time.time() * total_weight % total_weight
        current = 0
        for instance_id in instance_ids:
            current += self._instance_weights[instance_id]
            if current >= pick:
                return instance_id
        
        return instance_ids[0]
    
    def count(self) -> int:
        return len(self._instances)


instance_manager = InstanceManager()


class Autoscaler:
    def __init__(self, instance_manager: InstanceManager):
        self.instance_manager = instance_manager
        self.enabled = settings.AUTOSCALE_ENABLED
        self.min_instances = settings.AUTOSCALE_MIN_INSTANCES
        self.max_instances = settings.AUTOSCALE_MAX_INSTANCES
        self.target_cpu = settings.AUTOSCALE_TARGET_CPU
        self.target_latency = settings.AUTOSCALE_TARGET_LATENCY
        self.cooldown_period = settings.AUTOSCALE_COOLDOWN_PERIOD
        self.check_interval = settings.AUTOSCALE_CHECK_INTERVAL
        
        self._last_scale_time: float = 0
        self._task: Optional[asyncio.Task] = None
        self._metrics = {
            "scale_ups": 0,
            "scale_downs": 0,
            "target_instances": self.min_instances
        }
        self._lock = asyncio.Lock()
    
    def start(self):
        if self._task is None:
            self._task = asyncio.create_task(self._monitoring_loop())
            logger.info("Autoscaler started", min_instances=self.min_instances, max_instances=self.max_instances)
    
    def stop(self):
        if self._task:
            self._task.cancel()
            self._task = None
            logger.info("Autoscaler stopped")
    
    async def _monitoring_loop(self):
        while True:
            try:
                await self._check_and_scale()
                await asyncio.sleep(self.check_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Autoscaler monitoring error", error=str(e))
                await asyncio.sleep(5)
    
    async def _check_and_scale(self):
        if not self.enabled:
            return
        
        async with self._lock:
            now = time.time()
            if now - self._last_scale_time < self.cooldown_period:
                return
            
            current_count = self.instance_manager.count()
            if current_count == 0:
                await self._scale_to(self.min_instances, reason="initialization")
                return
            
            all_metrics = self.instance_manager.get_all_metrics()
            if not all_metrics:
                return
            
            avg_cpu = sum(m["avg_cpu_percent"] for m in all_metrics.values()) / len(all_metrics)
            avg_latency = sum(m["avg_latency_ms"] for m in all_metrics.values()) / len(all_metrics)
            avg_requests = sum(m["requests_per_second"] for m in all_metrics.values()) / len(all_metrics)
            
            should_scale_up = (
                avg_cpu > self.target_cpu or
                avg_latency > self.target_latency or
                avg_requests > 100
            )
            
            should_scale_down = (
                avg_cpu < self.target_cpu * 0.5 and
                avg_latency < self.target_latency * 0.5 and
                avg_requests < 50
            )
            
            if should_scale_up and current_count < self.max_instances:
                target_count = min(current_count + 1, self.max_instances)
                await self._scale_to(target_count, reason="high_load")
            
            elif should_scale_down and current_count > self.min_instances:
                target_count = max(current_count - 1, self.min_instances)
                await self._scale_to(target_count, reason="low_load")
    
    async def _scale_to(self, target_count: int, reason: str):
        current_count = self.instance_manager.count()
        
        if target_count > current_count:
            for i in range(current_count, target_count):
                instance_id = f"instance_{int(time.time() * 1000)}_{i}"
                await self.instance_manager.register_instance(instance_id)
            self._metrics["scale_ups"] += (target_count - current_count)
            logger.info("Scaled up instances", from_count=current_count, to_count=target_count, reason=reason)
        
        elif target_count < current_count:
            instance_ids = self.instance_manager.get_instance_ids()
            for instance_id in instance_ids[target_count:]:
                await self.instance_manager.deregister_instance(instance_id)
            self._metrics["scale_downs"] += (current_count - target_count)
            logger.info("Scaled down instances", from_count=current_count, to_count=target_count, reason=reason)
        
        self._metrics["target_instances"] = target_count
        self._last_scale_time = time.time()
    
    def get_metrics(self) -> Dict[str, Any]:
        return {
            "enabled": self.enabled,
            "current_instances": self.instance_manager.count(),
            "min_instances": self.min_instances,
            "max_instances": self.max_instances,
            "target_cpu_percent": self.target_cpu,
            "target_latency_ms": self.target_latency,
            **self._metrics
        }
    
    def force_scale(self, target_count: int):
        target_count = max(self.min_instances, min(self.max_instances, target_count))
        asyncio.create_task(self._scale_to(target_count, reason="manual"))


def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    return pwd_context.hash(password)


def create_access_token(
    data: Dict[str, Any],
    expires_delta: Optional[timedelta] = None
) -> str:
    to_encode = data.copy()
    
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=settings.JWT_EXPIRE_MINUTES)
    
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)
    return encoded_jwt


def decode_token(token: str) -> Dict[str, Any]:
    try:
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
        return payload
    except JWTError:
        raise AuthError("Invalid token")


def generate_api_key(user_id: str) -> str:
    raw = f"{user_id}:{time.time()}:{settings.JWT_SECRET}"
    return hashlib.sha256(raw.encode()).hexdigest()


class Permission:
    READ = "read"
    WRITE = "write"
    EXECUTE = "execute"
    ADMIN = "admin"


class RBAC:
    def __init__(self):
        self._roles: Dict[str, List[str]] = {
            "admin": [Permission.READ, Permission.WRITE, Permission.EXECUTE, Permission.ADMIN],
            "operator": [Permission.READ, Permission.WRITE, Permission.EXECUTE],
            "viewer": [Permission.READ]
        }
    
    def add_role(self, role_name: str, permissions: List[str]):
        self._roles[role_name] = permissions
    
    def has_permission(self, user_role: str, required_permission: str) -> bool:
        if user_role not in self._roles:
            return False
        return required_permission in self._roles[user_role]
    
    def get_role_permissions(self, role: str) -> List[str]:
        return self._roles.get(role, [])


rbac = RBAC()


class APIGateway:
    def __init__(self):
        self._routes: Dict[str, Dict[str, Any]] = {}
        self._middleware: List[Callable] = []
        self._autoscaler: Optional[Autoscaler] = None
    
    @property
    def autoscaler(self) -> Autoscaler:
        if self._autoscaler is None:
            self._autoscaler = Autoscaler(instance_manager)
        return self._autoscaler
    
    def add_middleware(self, middleware: Callable):
        self._middleware.append(middleware)
        logger.info("Added middleware", middleware=middleware.__name__ if hasattr(middleware, '__name__') else str(middleware))
    
    def register_route(
        self,
        path: str,
        method: str,
        handler: Callable,
        auth_required: bool = True,
        permissions: List[str] = None,
        rate_limit: int = 100,
        rate_window: int = 60,
        circuit_breaker: bool = True
    ):
        key = f"{method}:{path}"
        self._routes[key] = {
            "handler": handler,
            "auth_required": auth_required,
            "permissions": permissions or [],
            "rate_limit": rate_limit,
            "rate_window": rate_window,
            "circuit_breaker": circuit_breaker
        }
        logger.info("Registered route", path=path, method=method)
    
    async def process_request(
        self,
        request: Request,
        path: str,
        method: str,
        credentials: HTTPAuthorizationCredentials = None
    ) -> Any:
        start_time = time.time()
        instance_id = instance_manager.select_instance(f"{method}:{path}")
        instance_metrics = instance_manager._instances.get(instance_id) if instance_id in instance_manager._instances else None
        
        if instance_metrics:
            await instance_metrics.increment_active()
        
        try:
            key = f"{method}:{path}"
            route_config = self._routes.get(key)
            
            if not route_config:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Route not found"
                )
            
            if route_config.get("circuit_breaker"):
                cb = circuit_breaker_registry.get(key)
                if not cb.allow_request():
                    raise HTTPException(
                        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                        detail="Service temporarily unavailable (circuit breaker open)"
                    )
            
            client_ip = self._get_client_ip(request)
            rate_key = f"{client_ip}:{path}"
            
            try:
                rate_info = rate_limiter.check_rate_limit(
                    rate_key,
                    limit=route_config["rate_limit"],
                    window_seconds=route_config["rate_window"]
                )
            except RateLimitError as e:
                if route_config.get("circuit_breaker"):
                    circuit_breaker_registry.get(key).record_failure()
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=str(e),
                    headers={"Retry-After": str(60)}
                )
            
            user = None
            if route_config["auth_required"]:
                if not credentials:
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail="Authentication required"
                    )
                
                try:
                    user = await self._authenticate(credentials.credentials)
                except AuthError as e:
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail=str(e)
                    )
                
                if route_config["permissions"]:
                    if not user:
                        raise HTTPException(
                            status_code=status.HTTP_403_FORBIDDEN,
                            detail="Access denied"
                        )
                    
                    for permission in route_config["permissions"]:
                        if not rbac.has_permission(user.get("role", "viewer"), permission):
                            raise HTTPException(
                                status_code=status.HTTP_403_FORBIDDEN,
                                detail=f"Missing required permission: {permission}"
                            )
            
            for middleware in self._middleware:
                try:
                    result = middleware(request, user)
                    if hasattr(result, '__await__'):
                        await result
                except Exception as e:
                    logger.error("Middleware error", error=str(e))
                    raise HTTPException(
                        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                        detail="Middleware error"
                    )
            
            if route_config.get("circuit_breaker"):
                circuit_breaker_registry.get(key).record_success()
            
            latency_ms = (time.time() - start_time) * 1000
            if instance_metrics:
                await instance_metrics.record_request(latency_ms, success=True)
            
            return user, rate_info, instance_id
        
        except HTTPException:
            latency_ms = (time.time() - start_time) * 1000
            if instance_metrics:
                await instance_metrics.record_request(latency_ms, success=False)
            raise
        
        finally:
            if instance_metrics:
                await instance_metrics.decrement_active()
    
    async def _authenticate(self, token: str) -> Dict[str, Any]:
        try:
            payload = decode_token(token)
            user_id = payload.get("sub")
            if not user_id:
                raise AuthError("Invalid token payload")
            
            return {
                "user_id": user_id,
                "username": payload.get("username"),
                "role": payload.get("role", "viewer"),
                "email": payload.get("email")
            }
        except AuthError:
            raise
    
    def _get_client_ip(self, request: Request) -> str:
        x_forwarded_for = request.headers.get("X-Forwarded-For")
        if x_forwarded_for:
            return x_forwarded_for.split(",")[0].strip()
        return request.client.host if request.client else "unknown"


api_gateway = APIGateway()


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security)
) -> Dict[str, Any]:
    if not credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required"
        )
    
    try:
        payload = decode_token(credentials.credentials)
        return {
            "user_id": payload.get("sub"),
            "username": payload.get("username"),
            "role": payload.get("role", "viewer"),
            "email": payload.get("email")
        }
    except AuthError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(e)
        )


def require_permission(permission: str):
    def dependency(user: Dict[str, Any] = Depends(get_current_user)):
        if not rbac.has_permission(user.get("role", "viewer"), permission):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Missing required permission: {permission}"
            )
        return user
    return dependency


async def rate_limit_dependency(request: Request):
    client_ip = request.client.host if request.client else "unknown"
    path = request.url.path
    key = f"{client_ip}:{path}"
    
    try:
        return rate_limiter.check_rate_limit(key, limit=100, window_seconds=60)
    except RateLimitError as e:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=str(e)
        )


def get_autoscaler_metrics() -> Dict[str, Any]:
    return api_gateway.autoscaler.get_metrics()


def get_instance_metrics() -> Dict[str, Any]:
    return {
        "instances": instance_manager.get_all_metrics(),
        "instance_count": instance_manager.count()
    }


def get_circuit_breaker_metrics() -> Dict[str, Any]:
    return circuit_breaker_registry.get_all_metrics()


def get_rate_limiter_metrics() -> Dict[str, Any]:
    return rate_limiter.get_metrics()
