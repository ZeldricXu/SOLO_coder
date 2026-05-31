from typing import Any, Dict, List, Optional

from aiohttp import ClientSession

from ..interfaces.services import IWebhookSender
from ..utils import get_logger

logger = get_logger(__name__)


class WebhookSenderService(IWebhookSender):
    def __init__(self, session: Optional[ClientSession] = None, timeout: int = 30):
        self._session = session
        self._timeout = timeout

    async def send_webhook(self, url: str, headers: Dict[str, str], payload: Dict[str, Any]) -> None:
        if not self._session:
            return

        try:
            async with self._session.post(url, json=payload, headers=headers, timeout=self._timeout) as response:
                if response.status >= 400:
                    logger.warning(f"Webhook {url} returned status {response.status}")
        except Exception as e:
            logger.error(f"Failed to send webhook to {url}: {e}")
