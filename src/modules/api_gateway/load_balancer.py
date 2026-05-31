from typing import Dict, List
from .types import RouteTarget, LoadBalanceStrategy
from collections import defaultdict
import random
import logging
import asyncio

logger = logging.getLogger(__name__)


class LoadBalancer:
    def __init__(self):
        self._connections: Dict[str, int] = defaultdict(int)
        self._rr_counters: Dict[str, int] = defaultdict(int)
        self._lock = asyncio.Lock()

    async def select_target(
        self,
        targets: List[RouteTarget],
        strategy: LoadBalanceStrategy = LoadBalanceStrategy.ROUND_ROBIN,
        service_key: str = "default",
    ) -> RouteTarget:
        healthy_targets = [t for t in targets if t.healthy]
        if not healthy_targets:
            logger.warning("No healthy targets available, using all targets")
            healthy_targets = targets

        if not healthy_targets:
            raise Exception("No targets available")

        if strategy == LoadBalanceStrategy.ROUND_ROBIN:
            return await self._round_robin(healthy_targets, service_key)
        elif strategy == LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN:
            return await self._weighted_round_robin(healthy_targets, service_key)
        elif strategy == LoadBalanceStrategy.LEAST_CONNECTIONS:
            return await self._least_connections(healthy_targets)
        elif strategy == LoadBalanceStrategy.RANDOM:
            return random.choice(healthy_targets)
        else:
            return await self._round_robin(healthy_targets, service_key)

    async def _round_robin(
        self,
        targets: List[RouteTarget],
        service_key: str,
    ) -> RouteTarget:
        async with self._lock:
            idx = self._rr_counters[service_key] % len(targets)
            self._rr_counters[service_key] += 1
            return targets[idx]

    async def _weighted_round_robin(
        self,
        targets: List[RouteTarget],
        service_key: str,
    ) -> RouteTarget:
        weighted_targets = []
        for target in targets:
            weighted_targets.extend([target] * target.weight)

        async with self._lock:
            idx = self._rr_counters[service_key] % len(weighted_targets)
            self._rr_counters[service_key] += 1
            return weighted_targets[idx]

    async def _least_connections(self, targets: List[RouteTarget]) -> RouteTarget:
        async with self._lock:
            return min(
                targets,
                key=lambda t: self._connections.get(f"{t.host}:{t.port}", 0),
            )

    async def increment_connection(self, target: RouteTarget) -> None:
        key = f"{target.host}:{target.port}"
        async with self._lock:
            self._connections[key] += 1

    async def decrement_connection(self, target: RouteTarget) -> None:
        key = f"{target.host}:{target.port}"
        async with self._lock:
            if self._connections[key] > 0:
                self._connections[key] -= 1

    async def get_connection_count(self, target: RouteTarget) -> int:
        key = f"{target.host}:{target.port}"
        async with self._lock:
            return self._connections.get(key, 0)

    def get_all_connection_counts(self) -> Dict[str, int]:
        return dict(self._connections)
