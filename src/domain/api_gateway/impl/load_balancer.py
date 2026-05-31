from typing import List, Dict, Any
from collections import defaultdict
from ..models import RouteTarget, LoadBalanceStrategy
from ..interfaces import LoadBalancerPort
import random
import logging

logger = logging.getLogger(__name__)


class LoadBalancer(LoadBalancerPort):
    def __init__(self):
        self._rr_counters: Dict[str, int] = defaultdict(int)
        self._connections: Dict[str, int] = defaultdict(int)

    async def select_target(
        self,
        targets: List[RouteTarget],
        strategy: LoadBalanceStrategy,
        service_name: str,
    ) -> RouteTarget:
        if not targets:
            raise ValueError("No targets available")

        if len(targets) == 1:
            return targets[0]

        if strategy == LoadBalanceStrategy.ROUND_ROBIN:
            return await self._round_robin(targets, service_name)
        elif strategy == LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN:
            return await self._weighted_round_robin(targets, service_name)
        elif strategy == LoadBalanceStrategy.LEAST_CONN:
            return await self._least_connections(targets)
        elif strategy == LoadBalanceStrategy.RANDOM:
            return random.choice(targets)
        else:
            return random.choice(targets)

    async def increment_connection(self, target: RouteTarget) -> None:
        key = f"{target.host}:{target.port}"
        self._connections[key] += 1
        logger.debug(f"Incremented connections for {key}: {self._connections[key]}")

    async def decrement_connection(self, target: RouteTarget) -> None:
        key = f"{target.host}:{target.port}"
        if self._connections[key] > 0:
            self._connections[key] -= 1
        logger.debug(f"Decremented connections for {key}: {self._connections[key]}")

    async def _round_robin(
        self, targets: List[RouteTarget], service_name: str
    ) -> RouteTarget:
        idx = self._rr_counters[service_name] % len(targets)
        self._rr_counters[service_name] += 1
        return targets[idx]

    async def _weighted_round_robin(
        self, targets: List[RouteTarget], service_name: str
    ) -> RouteTarget:
        total_weight = sum(t.weight for t in targets)
        counter = self._rr_counters[service_name] % total_weight
        self._rr_counters[service_name] += 1

        cumulative = 0
        for target in targets:
            cumulative += target.weight
            if counter < cumulative:
                return target

        return targets[-1]

    async def _least_connections(self, targets: List[RouteTarget]) -> RouteTarget:
        def get_conn_count(t: RouteTarget) -> int:
            return self._connections.get(f"{t.host}:{t.port}", 0)

        return min(targets, key=get_conn_count)

    def get_connection_count(self, target: RouteTarget) -> int:
        return self._connections.get(f"{target.host}:{target.port}", 0)
