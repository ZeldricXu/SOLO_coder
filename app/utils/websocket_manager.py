import json
import asyncio
import threading
from typing import Dict, Set, Any, Optional
from fastapi import WebSocket, WebSocketDisconnect

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.services.storage import StorageService

logger = get_logger(__name__)
settings = get_settings()


class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, Set[WebSocket]] = {}
        self._lock = threading.Lock()
        self._listener_task: Optional[asyncio.Task] = None
        self._channels: Set[str] = set()

    async def connect(self, websocket: WebSocket, client_id: str):
        await websocket.accept()
        with self._lock:
            if client_id not in self.active_connections:
                self.active_connections[client_id] = set()
            self.active_connections[client_id].add(websocket)
        logger.info(f"WebSocket connected: {client_id}")

    def disconnect(self, websocket: WebSocket, client_id: str):
        with self._lock:
            if client_id in self.active_connections:
                self.active_connections[client_id].discard(websocket)
                if not self.active_connections[client_id]:
                    del self.active_connections[client_id]
        logger.info(f"WebSocket disconnected: {client_id}")

    async def send_personal_message(self, message: Dict[str, Any], client_id: str):
        with self._lock:
            connections = self.active_connections.get(client_id, set()).copy()

        for connection in connections:
            try:
                await connection.send_json(message)
            except Exception as e:
                logger.warning(f"Failed to send message to {client_id}: {e}")

    async def broadcast(self, message: Dict[str, Any], channel: str):
        with self._lock:
            connections = self.active_connections.get(channel, set()).copy()

        for connection in connections:
            try:
                await connection.send_json(message)
            except Exception as e:
                logger.warning(f"Failed to broadcast to channel {channel}: {e}")

    async def subscribe_to_channel(self, channel: str, client_id: str):
        with self._lock:
            if channel not in self.active_connections:
                self.active_connections[channel] = set()

        self._channels.add(channel)
        logger.info(f"Client {client_id} subscribed to channel: {channel}")

    async def unsubscribe_from_channel(self, channel: str, client_id: str):
        with self._lock:
            if channel in self.active_connections:
                pass

        self._channels.discard(channel)
        logger.info(f"Client {client_id} unsubscribed from channel: {channel}")


class ProgressWebSocketManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.manager = ConnectionManager()
        self._storage = StorageService()
        self._listening = False

    async def start_listener(self):
        if self._listening:
            return

        self._listening = True
        asyncio.create_task(self._listen_to_redis())

    async def _listen_to_redis(self):
        try:
            import redis.asyncio as redis_async

            redis_client = redis_async.from_url(settings.REDIS_URL)
            pubsub = redis_client.pubsub()

            await pubsub.psubscribe("batch:*:progress", "doc:*:progress")

            async for message in pubsub.listen():
                if message["type"] == "pmessage":
                    channel = message["channel"].decode()
                    data = message["data"].decode()

                    try:
                        progress_data = json.loads(data)

                        if channel.startswith("batch:"):
                            batch_id = channel.split(":")[1]
                            await self.manager.broadcast(
                                {
                                    "type": "batch_progress",
                                    "channel": channel,
                                    "data": progress_data,
                                },
                                channel,
                            )
                        elif channel.startswith("doc:"):
                            document_id = channel.split(":")[1]
                            await self.manager.broadcast(
                                {
                                    "type": "document_progress",
                                    "channel": channel,
                                    "data": progress_data,
                                },
                                channel,
                            )
                    except json.JSONDecodeError:
                        logger.warning(f"Invalid JSON in WebSocket message: {data}")

        except Exception as e:
            logger.error(f"Redis listener error: {e}", exc_info=True)
            self._listening = False

    async def handle_websocket(
        self,
        websocket: WebSocket,
        client_id: str,
        batch_id: Optional[str] = None,
        document_id: Optional[str] = None,
    ):
        await self.manager.connect(websocket, client_id)

        try:
            if batch_id:
                channel = f"batch:{batch_id}:progress"
                await self.manager.subscribe_to_channel(channel, client_id)

                initial_progress = await self._get_batch_progress(batch_id)
                if initial_progress:
                    await websocket.send_json({
                        "type": "batch_progress",
                        "channel": channel,
                        "data": initial_progress,
                    })

            if document_id:
                channel = f"doc:{document_id}:progress"
                await self.manager.subscribe_to_channel(channel, client_id)

                initial_progress = await self._get_document_progress(document_id)
                if initial_progress:
                    await websocket.send_json({
                        "type": "document_progress",
                        "channel": channel,
                        "data": initial_progress,
                    })

            while True:
                try:
                    data = await websocket.receive_text()
                    message = json.loads(data)

                    if message.get("action") == "subscribe":
                        channel = message.get("channel")
                        if channel:
                            await self.manager.subscribe_to_channel(channel, client_id)

                    elif message.get("action") == "unsubscribe":
                        channel = message.get("channel")
                        if channel:
                            await self.manager.unsubscribe_from_channel(channel, client_id)

                    elif message.get("action") == "ping":
                        await websocket.send_json({"type": "pong"})

                except WebSocketDisconnect:
                    break
                except json.JSONDecodeError:
                    await websocket.send_json({"error": "Invalid JSON"})

        except WebSocketDisconnect:
            pass
        finally:
            if batch_id:
                self.manager.unsubscribe_from_channel(f"batch:{batch_id}:progress", client_id)
            if document_id:
                self.manager.unsubscribe_from_channel(f"doc:{document_id}:progress", client_id)
            self.manager.disconnect(websocket, client_id)

    async def _get_batch_progress(self, batch_id: str) -> Optional[Dict[str, Any]]:
        from app.services.batch_service import BatchService

        try:
            batch_service = BatchService()
            return batch_service.get_batch_progress(int(batch_id))
        except Exception as e:
            logger.warning(f"Failed to get batch progress: {e}")
            return None

    async def _get_document_progress(self, document_id: str) -> Optional[Dict[str, Any]]:
        cache_key = f"doc:progress:{document_id}"
        try:
            cached = self._storage.cache_get(cache_key)
            if cached:
                return json.loads(cached)

            from app.services.document_service import DocumentService
            doc_service = DocumentService()
            return doc_service.get_processing_status(int(document_id))
        except Exception as e:
            logger.warning(f"Failed to get document progress: {e}")
            return None
