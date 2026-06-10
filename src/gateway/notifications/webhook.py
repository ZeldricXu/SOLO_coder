from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import asyncio
import hashlib
import hmac
import json
import time

import httpx

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("webhook")


@dataclass
class WebhookEvent:
    event_type: str
    payload: Dict[str, Any]
    timestamp: int = field(default_factory=lambda: int(time.time()))
    event_id: str = field(default_factory=lambda: __import__("uuid").uuid4().hex)


class WebhookNotifier:
    def __init__(self):
        self.settings = get_settings()
        self.wh_settings = self.settings.webhook
        self._pending_events: List[WebhookEvent] = []
        self._worker_task: Optional[asyncio.Task] = None
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        if not self.wh_settings.enabled:
            logger.info("Webhook notifier disabled")
            self._initialized = True
            return

        self._worker_task = asyncio.create_task(self._delivery_worker())
        self._initialized = True
        logger.info("Webhook notifier initialized", url=self.wh_settings.url)

    async def notify(self, event_type: str, payload: Dict[str, Any]) -> None:
        if not self.wh_settings.enabled or not self.wh_settings.url:
            return

        if event_type not in self.wh_settings.events:
            return

        event = WebhookEvent(event_type=event_type, payload=payload)
        self._pending_events.append(event)
        logger.info("Webhook event queued", event_type=event_type, event_id=event.event_id)

    async def _delivery_worker(self) -> None:
        while True:
            if not self._pending_events:
                await asyncio.sleep(1)
                continue

            event = self._pending_events.pop(0)
            success = await self._deliver_event(event)

            if not success:
                retry_count = 0
                while retry_count < self.wh_settings.max_retries:
                    await asyncio.sleep(
                        self.wh_settings.retry_backoff * (2 ** retry_count)
                    )
                    success = await self._deliver_event(event)
                    if success:
                        break
                    retry_count += 1

                if not success:
                    logger.error("Webhook delivery failed after retries",
                                 event_type=event.event_type,
                                 event_id=event.event_id,
                                 retries=retry_count)

    async def _deliver_event(self, event: WebhookEvent) -> bool:
        if not self.wh_settings.url:
            return False

        payload = {
            "event_id": event.event_id,
            "event_type": event.event_type,
            "timestamp": event.timestamp,
            "data": event.payload,
        }

        payload_str = json.dumps(payload, sort_keys=True)
        headers = {
            "Content-Type": "application/json",
            "X-Webhook-Event": event.event_type,
            "X-Webhook-Event-ID": event.event_id,
            "X-Webhook-Timestamp": str(event.timestamp),
        }

        if self.wh_settings.secret:
            signature = hmac.new(
                self.wh_settings.secret.encode("utf-8"),
                payload_str.encode("utf-8"),
                hashlib.sha256
            ).hexdigest()
            headers["X-Webhook-Signature"] = f"sha256={signature}"

        try:
            async with httpx.AsyncClient(timeout=self.wh_settings.timeout) as client:
                response = await client.post(
                    self.wh_settings.url,
                    content=payload_str,
                    headers=headers,
                )
                if 200 <= response.status_code < 300:
                    logger.info("Webhook delivered successfully",
                                event_type=event.event_type,
                                event_id=event.event_id)
                    return True
                else:
                    logger.warning("Webhook delivery returned non-2xx status",
                                   event_type=event.event_type,
                                   event_id=event.event_id,
                                   status_code=response.status_code)
                    return False
        except Exception as e:
            logger.error("Webhook delivery failed",
                         event_type=event.event_type,
                         event_id=event.event_id,
                         error=str(e))
            return False

    async def shutdown(self) -> None:
        if self._worker_task and not self._worker_task.done():
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
            self._worker_task = None
        self._initialized = False
        logger.info("Webhook notifier shutdown")


_notifier_instance: Optional[WebhookNotifier] = None


def get_webhook_notifier() -> WebhookNotifier:
    global _notifier_instance
    if _notifier_instance is None:
        _notifier_instance = WebhookNotifier()
    return _notifier_instance
