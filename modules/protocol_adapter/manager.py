from typing import Any, Dict, List, Optional, Callable
import asyncio
from datetime import datetime, timedelta

from core import emit_event, EventTypes
from .drivers import DriverFactory, ProtocolDriver
from .normalizer import DataNormalizer


class DeviceEndpoint:
    def __init__(
        self,
        endpoint_id: str,
        driver_type: str,
        config: Dict[str, Any],
        polling_interval: int = 1000,
        points: Optional[List[Dict[str, Any]]] = None,
        transformations: Optional[Dict[str, Any]] = None,
    ):
        self.endpoint_id = endpoint_id
        self.driver_type = driver_type
        self.config = config
        self.polling_interval = polling_interval
        self.points = points or []
        self.transformations = transformations or {}
        self.driver: Optional[ProtocolDriver] = None
        self.last_values: Dict[str, Any] = {}
        self.last_poll: Optional[datetime] = None
        self.enabled = True
        self.callbacks: List[Callable] = []

    async def connect(self) -> bool:
        if self.driver is None:
            self.driver = DriverFactory.create(self.driver_type, self.config)
        return await self.driver.connect()

    async def disconnect(self) -> None:
        if self.driver:
            await self.driver.disconnect()

    def is_connected(self) -> bool:
        return self.driver is not None and self.driver.is_connected()

    async def poll(self) -> Dict[str, Any]:
        if not self.driver or not self.enabled:
            return {}

        results = {}
        for point in self.points:
            address = point.get("address")
            if not address:
                continue

            try:
                value = await self.driver.read(
                    address,
                    **point.get("read_params", {}),
                )

                point_id = point.get("id", address)
                transformation = point.get("transformation")
                if transformation and "value" in value:
                    value["value"] = DataNormalizer.transform_value(
                        value["value"],
                        transformation,
                        self.transformations.get(transformation),
                    )

                normalized = DataNormalizer.normalize(
                    value,
                    self.driver_type,
                    options={"point": point},
                )

                results[point_id] = normalized
                self.last_values[point_id] = normalized

                for callback in self.callbacks:
                    try:
                        if asyncio.iscoroutinefunction(callback):
                            await callback(point_id, normalized)
                        else:
                            callback(point_id, normalized)
                    except Exception:
                        pass

            except Exception as e:
                results[point.get("id", address)] = {"error": str(e)}

        self.last_poll = datetime.utcnow()
        return results

    async def write(self, point_id: str, value: Any) -> bool:
        if not self.driver or not self.enabled:
            return False

        point = next((p for p in self.points if p.get("id") == point_id), None)
        if not point:
            return False

        return await self.driver.write(
            point["address"],
            value,
            **point.get("write_params", {}),
        )

    def add_callback(self, callback: Callable) -> None:
        self.callbacks.append(callback)

    def remove_callback(self, callback: Callable) -> None:
        if callback in self.callbacks:
            self.callbacks.remove(callback)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "endpoint_id": self.endpoint_id,
            "driver_type": self.driver_type,
            "connected": self.is_connected(),
            "enabled": self.enabled,
            "polling_interval": self.polling_interval,
            "points_count": len(self.points),
            "last_poll": self.last_poll,
            "config": {k: v for k, v in self.config.items() if k not in ["password", "secret"]},
        }


class ProtocolAdapterManager:
    def __init__(self):
        self._endpoints: Dict[str, DeviceEndpoint] = {}
        self._polling_tasks: Dict[str, asyncio.Task] = {}
        self._stop_event = asyncio.Event()

    def add_endpoint(
        self,
        endpoint_id: str,
        driver_type: str,
        config: Dict[str, Any],
        polling_interval: int = 1000,
        points: Optional[List[Dict[str, Any]]] = None,
        transformations: Optional[Dict[str, Any]] = None,
    ) -> DeviceEndpoint:
        if endpoint_id in self._endpoints:
            raise ValueError(f"Endpoint {endpoint_id} already exists")

        endpoint = DeviceEndpoint(
            endpoint_id=endpoint_id,
            driver_type=driver_type,
            config=config,
            polling_interval=polling_interval,
            points=points,
            transformations=transformations,
        )
        self._endpoints[endpoint_id] = endpoint
        return endpoint

    def remove_endpoint(self, endpoint_id: str) -> None:
        if endpoint_id in self._polling_tasks:
            self._polling_tasks[endpoint_id].cancel()
            del self._polling_tasks[endpoint_id]

        if endpoint_id in self._endpoints:
            del self._endpoints[endpoint_id]

    def get_endpoint(self, endpoint_id: str) -> Optional[DeviceEndpoint]:
        return self._endpoints.get(endpoint_id)

    def list_endpoints(self) -> List[Dict[str, Any]]:
        return [ep.to_dict() for ep in self._endpoints.values()]

    async def connect_endpoint(self, endpoint_id: str) -> bool:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            raise ValueError(f"Endpoint {endpoint_id} not found")

        return await endpoint.connect()

    async def disconnect_endpoint(self, endpoint_id: str) -> None:
        endpoint = self.get_endpoint(endpoint_id)
        if endpoint:
            await endpoint.disconnect()

    async def start_polling(self, endpoint_id: str) -> None:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            raise ValueError(f"Endpoint {endpoint_id} not found")

        if endpoint_id in self._polling_tasks and not self._polling_tasks[endpoint_id].done():
            return

        if not endpoint.is_connected():
            await endpoint.connect()

        endpoint.enabled = True

        async def polling_loop():
            while endpoint.enabled and not self._stop_event.is_set():
                try:
                    results = await endpoint.poll()
                    if results:
                        emit_event(
                            EventTypes.METRICS_REPORTED,
                            "protocol_adapter",
                            {
                                "endpoint_id": endpoint_id,
                                "data_points": results,
                            },
                        )
                except Exception as e:
                    print(f"Polling error for {endpoint_id}: {e}")

                await asyncio.sleep(endpoint.polling_interval / 1000.0)

        self._polling_tasks[endpoint_id] = asyncio.create_task(polling_loop())

    async def stop_polling(self, endpoint_id: str) -> None:
        endpoint = self.get_endpoint(endpoint_id)
        if endpoint:
            endpoint.enabled = False

        if endpoint_id in self._polling_tasks:
            self._polling_tasks[endpoint_id].cancel()
            del self._polling_tasks[endpoint_id]

    async def read_point(self, endpoint_id: str, point_id: str) -> Any:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            raise ValueError(f"Endpoint {endpoint_id} not found")

        if not endpoint.is_connected():
            await endpoint.connect()

        point = next((p for p in endpoint.points if p.get("id") == point_id), None)
        if not point:
            raise ValueError(f"Point {point_id} not found")

        value = await endpoint.driver.read(point["address"], **point.get("read_params", {}))
        normalized = DataNormalizer.normalize(value, endpoint.driver_type)
        endpoint.last_values[point_id] = normalized
        return normalized

    async def write_point(self, endpoint_id: str, point_id: str, value: Any) -> bool:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            raise ValueError(f"Endpoint {endpoint_id} not found")

        if not endpoint.is_connected():
            await endpoint.connect()

        return await endpoint.write(point_id, value)

    async def read_all(self, endpoint_id: str) -> Dict[str, Any]:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            raise ValueError(f"Endpoint {endpoint_id} not found")

        if not endpoint.is_connected():
            await endpoint.connect()

        return await endpoint.poll()

    def get_last_values(self, endpoint_id: str) -> Dict[str, Any]:
        endpoint = self.get_endpoint(endpoint_id)
        if not endpoint:
            return {}
        return dict(endpoint.last_values)

    async def stop_all(self) -> None:
        self._stop_event.set()

        for endpoint_id in list(self._polling_tasks.keys()):
            await self.stop_polling(endpoint_id)

        for endpoint in self._endpoints.values():
            await endpoint.disconnect()


protocol_adapter_manager = ProtocolAdapterManager()
