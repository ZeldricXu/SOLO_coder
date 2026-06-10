import asyncio
from typing import Optional
from redis.asyncio import Redis
from redis.exceptions import RedisError

from gateway.config import get_settings
from gateway.db import get_db
from gateway.db.repository import RouteRepository
from gateway.routing.router import get_router
from gateway.db.redis_client import get_redis
from gateway.logger import get_logger

logger = get_logger("route-watcher")

ROUTE_UPDATE_CHANNEL = "gateway:route_updates"


class RouteWatcher:
    def __init__(self):
        self.settings = get_settings()
        self.router = get_router()
        self.redis: Optional[Redis] = None
        self._task: Optional[asyncio.Task] = None
        self._pubsub_task: Optional[asyncio.Task] = None
        self._running = False

    async def start(self) -> None:
        if self._running:
            return

        self._running = True
        self.redis = get_redis()

        logger.info("Starting route watcher", reload_interval=self.settings.gateway.route_reload_interval)

        await self._initial_load()

        self._task = asyncio.create_task(self._polling_loop())
        self._pubsub_task = asyncio.create_task(self._pubsub_listener())

    async def stop(self) -> None:
        self._running = False

        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

        if self._pubsub_task:
            self._pubsub_task.cancel()
            try:
                await self._pubsub_task
            except asyncio.CancelledError:
                pass
            self._pubsub_task = None

        logger.info("Route watcher stopped")

    async def _initial_load(self) -> None:
        try:
            async for session in get_db():
                repo = RouteRepository(session)
                await self.router.load_routes(repo)
                break
        except Exception as e:
            logger.error("Failed to load initial routes", error=str(e), exc_info=True)

    async def _polling_loop(self) -> None:
        while self._running:
            try:
                await asyncio.sleep(self.settings.gateway.route_reload_interval)
                await self._check_and_reload_routes()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Error in route polling loop", error=str(e), exc_info=True)

    async def _pubsub_listener(self) -> None:
        while self._running:
            try:
                async with self.redis.pubsub() as pubsub:
                    await pubsub.subscribe(ROUTE_UPDATE_CHANNEL)
                    logger.info("Subscribed to route updates channel", channel=ROUTE_UPDATE_CHANNEL)

                    async for message in pubsub.listen():
                        if message["type"] == "message":
                            logger.info("Received route update notification", data=message["data"])
                            await self._check_and_reload_routes()

            except asyncio.CancelledError:
                break
            except RedisError as e:
                logger.error("Redis pubsub error", error=str(e))
                await asyncio.sleep(5)
            except Exception as e:
                logger.error("Error in route pubsub listener", error=str(e), exc_info=True)
                await asyncio.sleep(5)

    async def _check_and_reload_routes(self) -> None:
        try:
            async for session in get_db():
                repo = RouteRepository(session)
                current_version = await repo.get_max_version()

                if current_version > self.router.version:
                    logger.info("Route version changed, reloading",
                                old_version=self.router.version,
                                new_version=current_version)
                    await self.router.load_routes(repo)
                    await self._broadcast_update(current_version)
                break
        except Exception as e:
            logger.error("Failed to check and reload routes", error=str(e), exc_info=True)

    async def _broadcast_update(self, version: int) -> None:
        try:
            if self.redis:
                await self.redis.publish(ROUTE_UPDATE_CHANNEL, f"version:{version}")
                logger.info("Broadcasted route update", version=version)
        except Exception as e:
            logger.error("Failed to broadcast route update", error=str(e))

    async def notify_update(self) -> None:
        await self._check_and_reload_routes()


_watcher_instance: Optional[RouteWatcher] = None


def get_route_watcher() -> RouteWatcher:
    global _watcher_instance
    if _watcher_instance is None:
        _watcher_instance = RouteWatcher()
    return _watcher_instance
