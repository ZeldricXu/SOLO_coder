from typing import Any, Callable, Dict, Optional

from ..interfaces.services import ICallbackHandlerRegistry
from ..utils import get_logger

logger = get_logger(__name__)


class CallbackHandlerRegistry(ICallbackHandlerRegistry):
    def __init__(self):
        self._handlers: Dict[str, Callable] = {}

    def register_handler(self, event_type: str, handler: Callable) -> None:
        self._handlers[event_type] = handler
        logger.info(f"Registered callback handler for {event_type}")

    def get_handler(self, event_type: str) -> Optional[Callable]:
        return self._handlers.get(event_type)
