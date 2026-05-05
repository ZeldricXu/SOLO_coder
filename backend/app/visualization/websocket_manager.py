from typing import Dict, Any, List, Optional, Set
from datetime import datetime
import asyncio
import json
import logging
import uuid

from fastapi import WebSocket

from app.core.models import MetricResult, WebSocketMetricMessage, MetricConfig
from app.metrics.manager import metric_manager

logger = logging.getLogger(__name__)


class WebSocketManager:
    def __init__(self):
        self._active_connections: Dict[str, WebSocket] = {}
        self._subscriptions: Dict[str, Set[str]] = {}
        self._client_subscriptions: Dict[str, Set[str]] = {}
        self._connection_lock = asyncio.Lock()
        self._message_queue: asyncio.Queue = asyncio.Queue()
        self._broadcast_task: Optional[asyncio.Task] = None
        self._is_running = False

    async def start(self):
        self._is_running = True
        self._broadcast_task = asyncio.create_task(self._process_queue())
        logger.info("WebSocket manager started")

    async def stop(self):
        self._is_running = False

        if self._broadcast_task and not self._broadcast_task.done():
            self._broadcast_task.cancel()
            try:
                await self._broadcast_task
            except asyncio.CancelledError:
                pass

        for client_id, websocket in list(self._active_connections.items()):
            try:
                await websocket.close()
            except Exception as e:
                logger.warning(f"Error closing WebSocket {client_id}: {e}")

        self._active_connections.clear()
        self._subscriptions.clear()
        self._client_subscriptions.clear()
        logger.info("WebSocket manager stopped")

    async def connect(self, websocket: WebSocket) -> str:
        client_id = str(uuid.uuid4().hex[:12])

        async with self._connection_lock:
            self._active_connections[client_id] = websocket
            self._client_subscriptions[client_id] = set()

        logger.info(f"WebSocket client connected: {client_id}")
        return client_id

    async def disconnect(self, client_id: str):
        async with self._connection_lock:
            if client_id in self._active_connections:
                del self._active_connections[client_id]

            if client_id in self._client_subscriptions:
                for metric_id in self._client_subscriptions[client_id]:
                    if metric_id in self._subscriptions:
                        self._subscriptions[metric_id].discard(client_id)
                        if not self._subscriptions[metric_id]:
                            del self._subscriptions[metric_id]
                del self._client_subscriptions[client_id]

        logger.info(f"WebSocket client disconnected: {client_id}")

    async def subscribe(self, client_id: str, metric_id: str) -> bool:
        async with self._connection_lock:
            if client_id not in self._active_connections:
                logger.warning(f"Client {client_id} not connected")
                return False

            if metric_id not in self._subscriptions:
                self._subscriptions[metric_id] = set()
            self._subscriptions[metric_id].add(client_id)

            if client_id not in self._client_subscriptions:
                self._client_subscriptions[client_id] = set()
            self._client_subscriptions[client_id].add(metric_id)

        logger.info(f"Client {client_id} subscribed to metric {metric_id}")
        return True

    async def unsubscribe(self, client_id: str, metric_id: str) -> bool:
        async with self._connection_lock:
            if client_id in self._client_subscriptions:
                self._client_subscriptions[client_id].discard(metric_id)

            if metric_id in self._subscriptions:
                self._subscriptions[metric_id].discard(client_id)
                if not self._subscriptions[metric_id]:
                    del self._subscriptions[metric_id]

        logger.info(f"Client {client_id} unsubscribed from metric {metric_id}")
        return True

    def _build_message(self, result: MetricResult, chart_type: str = "line") -> str:
        message = WebSocketMetricMessage(
            event="metric_update",
            data=result
        )

        message_dict = message.model_dump()
        message_dict['data']['chart_type'] = chart_type
        message_dict['data']['timestamp'] = result.timestamp.isoformat() + "Z"
        if 'window_start' in message_dict['data']:
            message_dict['data']['window_start'] = result.window_start.isoformat() + "Z"

        return json.dumps(message_dict, default=str)

    async def broadcast_metric(self, result: MetricResult, chart_type: str = "line"):
        if not self._is_running:
            return

        message = self._build_message(result, chart_type)
        await self._message_queue.put({
            'metric_id': result.metric_id,
            'message': message
        })

    async def broadcast_to_client(self, client_id: str, message: str) -> bool:
        async with self._connection_lock:
            websocket = self._active_connections.get(client_id)

        if not websocket:
            return False

        try:
            await websocket.send_text(message)
            return True
        except Exception as e:
            logger.error(f"Error sending message to {client_id}: {e}")
            await self.disconnect(client_id)
            return False

    async def _process_queue(self):
        while self._is_running:
            try:
                item = await asyncio.wait_for(
                    self._message_queue.get(),
                    timeout=1.0
                )

                metric_id = item['metric_id']
                message = item['message']

                async with self._connection_lock:
                    subscribers = self._subscriptions.get(metric_id, set())
                    connections = {
                        cid: ws for cid, ws in self._active_connections.items()
                        if cid in subscribers
                    }

                for client_id, websocket in connections.items():
                    try:
                        await websocket.send_text(message)
                    except Exception as e:
                        logger.error(f"Error broadcasting to {client_id}: {e}")
                        await self.disconnect(client_id)

            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in WebSocket queue processing: {e}")

    async def get_client_stats(self, client_id: str) -> Optional[Dict[str, Any]]:
        async with self._connection_lock:
            if client_id not in self._active_connections:
                return None

            return {
                'client_id': client_id,
                'subscriptions': list(self._client_subscriptions.get(client_id, []))
            }

    def get_status(self) -> Dict[str, Any]:
        return {
            'active_connections': len(self._active_connections),
            'total_subscriptions': sum(len(s) for s in self._subscriptions.values()),
            'metrics_subscribed': len(self._subscriptions),
            'queue_size': self._message_queue.qsize()
        }

    async def handle_message(self, client_id: str, message_text: str):
        try:
            message = json.loads(message_text)
            action = message.get('action')

            if action == 'subscribe':
                metric_id = message.get('metric_id')
                if metric_id:
                    await self.subscribe(client_id, metric_id)

            elif action == 'unsubscribe':
                metric_id = message.get('metric_id')
                if metric_id:
                    await self.unsubscribe(client_id, metric_id)

            elif action == 'list_metrics':
                metrics = metric_manager.get_all_metrics()
                response = {
                    'event': 'metrics_list',
                    'data': [
                        {
                            'metric_id': mid,
                            'metric_name': cfg.metric_name,
                            'source': cfg.source,
                            'aggregation': cfg.aggregation.value,
                            'chart_type': cfg.chart_type,
                            'is_active': cfg.is_active
                        }
                        for mid, cfg in metrics.items()
                    ]
                }
                await self.broadcast_to_client(client_id, json.dumps(response))

        except json.JSONDecodeError:
            logger.warning(f"Invalid JSON message from {client_id}")
        except Exception as e:
            logger.error(f"Error handling message from {client_id}: {e}")


websocket_manager = WebSocketManager()
