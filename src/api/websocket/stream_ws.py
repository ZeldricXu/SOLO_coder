import asyncio
import json
import logging
from typing import Any, Dict, Optional, Set

logger = logging.getLogger(__name__)


class StreamWebSocketHandler:
    def __init__(self):
        self._connections: Set[Any] = set()
        self._subscriptions: Dict[str, Set[Any]] = {}
        self._running = False

    async def handle_connection(self, websocket) -> None:
        self._connections.add(websocket)
        logger.info(f"WebSocket client connected. Total: {len(self._connections)}")

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    await self._handle_message(websocket, data)
                except json.JSONDecodeError:
                    await websocket.send(json.dumps({"type": "error", "message": "Invalid JSON"}))
                except Exception as e:
                    logger.error(f"WebSocket message handling error: {e}")
                    await websocket.send(json.dumps({"type": "error", "message": str(e)}))
        except Exception as e:
            logger.error(f"WebSocket connection error: {e}")
        finally:
            self._connections.discard(websocket)
            for topic in list(self._subscriptions.keys()):
                self._subscriptions[topic].discard(websocket)
                if not self._subscriptions[topic]:
                    del self._subscriptions[topic]
            logger.info(f"WebSocket client disconnected. Total: {len(self._connections)}")

    async def _handle_message(self, websocket, data: Dict[str, Any]) -> None:
        msg_type = data.get("type", "")

        if msg_type == "query":
            await self._handle_query(websocket, data)
        elif msg_type == "subscribe":
            await self._handle_subscribe(websocket, data)
        elif msg_type == "unsubscribe":
            await self._handle_unsubscribe(websocket, data)
        elif msg_type == "ping":
            await websocket.send(json.dumps({"type": "pong"}))
        else:
            await websocket.send(json.dumps({"type": "error", "message": f"Unknown message type: {msg_type}"}))

    async def _handle_query(self, websocket, data: Dict[str, Any]) -> None:
        sql = data.get("sql", "")
        if not sql:
            await websocket.send(json.dumps({"type": "error", "message": "Missing SQL"}))
            return

        try:
            from src.service.query_service import QueryService
            service = QueryService()
            result = service.execute_query(sql)
            await websocket.send(json.dumps({
                "type": "query_result",
                "request_id": data.get("request_id"),
                "result": result,
            }, default=str))
        except Exception as e:
            await websocket.send(json.dumps({
                "type": "query_error",
                "request_id": data.get("request_id"),
                "error": str(e),
            }))

    async def _handle_subscribe(self, websocket, data: Dict[str, Any]) -> None:
        topic = data.get("topic", "")
        if not topic:
            await websocket.send(json.dumps({"type": "error", "message": "Missing topic"}))
            return

        if topic not in self._subscriptions:
            self._subscriptions[topic] = set()
        self._subscriptions[topic].add(websocket)

        await websocket.send(json.dumps({
            "type": "subscribed",
            "topic": topic,
        }))

    async def _handle_unsubscribe(self, websocket, data: Dict[str, Any]) -> None:
        topic = data.get("topic", "")
        if topic in self._subscriptions:
            self._subscriptions[topic].discard(websocket)
            if not self._subscriptions[topic]:
                del self._subscriptions[topic]

        await websocket.send(json.dumps({
            "type": "unsubscribed",
            "topic": topic,
        }))

    async def broadcast(self, topic: str, message: Dict[str, Any]) -> None:
        if topic not in self._subscriptions:
            return
        payload = json.dumps(message, default=str)
        dead_connections = set()

        for ws in self._subscriptions[topic]:
            try:
                await ws.send(payload)
            except Exception:
                dead_connections.add(ws)

        for ws in dead_connections:
            self._subscriptions[topic].discard(ws)
            self._connections.discard(ws)

    async def broadcast_cdc_event(self, event: Dict[str, Any]) -> None:
        await self.broadcast("cdc", {"type": "cdc_event", "event": event})

    async def broadcast_quality_alert(self, alert: Dict[str, Any]) -> None:
        await self.broadcast("quality", {"type": "quality_alert", "alert": alert})

    @property
    def connection_count(self) -> int:
        return len(self._connections)

    @property
    def subscription_count(self) -> Dict[str, int]:
        return {topic: len(conns) for topic, conns in self._subscriptions.items()}
