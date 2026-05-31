from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, List, Optional

from src.shared.types import Address, BlockNumber, EventLog, Hash, HexString


class EventCallback:
    def __init__(
        self,
        event_name: str,
        contract_address: Address,
        callback: Callable[[EventLog, Dict[str, Any]], None],
        abi: Optional[List[Dict[str, Any]]] = None,
        filter_params: Optional[Dict[str, Any]] = None,
    ):
        self.event_name = event_name
        self.contract_address = contract_address
        self.callback = callback
        self.abi = abi
        self.filter_params = filter_params or {}
        self.topic0: Optional[HexString] = None


class IEventListenerPort(ABC):
    @abstractmethod
    async def register_callback(
        self,
        event_name: str,
        contract_address: Address,
        callback: Callable[[EventLog, Dict[str, Any]], None],
        abi: Optional[List[Dict[str, Any]]] = None,
        from_block: Optional[BlockNumber] = None,
        filter_params: Optional[Dict[str, Any]] = None,
    ) -> str: ...

    @abstractmethod
    async def unregister_callback(self, callback_id: str) -> bool: ...

    @abstractmethod
    async def start_listening(self) -> None: ...

    @abstractmethod
    async def stop_listening(self) -> None: ...

    @abstractmethod
    async def fetch_past_events(
        self,
        contract_address: Address,
        event_name: str,
        from_block: BlockNumber,
        to_block: Optional[BlockNumber | str] = None,
        abi: Optional[List[Dict[str, Any]]] = None,
        filter_params: Optional[Dict[str, Any]] = None,
    ) -> List[EventLog]: ...

    @abstractmethod
    async def decode_event_log(self, log: EventLog, abi: List[Dict[str, Any]]) -> Dict[str, Any]: ...

    @abstractmethod
    def is_listening(self) -> bool: ...

    @abstractmethod
    def get_registered_callbacks(self) -> Dict[str, EventCallback]: ...


class IEventProcessor(ABC):
    @abstractmethod
    async def process_event(self, log: EventLog, decoded_data: Dict[str, Any]) -> None: ...

    @abstractmethod
    async def handle_error(self, error: Exception, log: EventLog) -> None: ...

    @abstractmethod
    async def retry_event(self, log: EventLog, decoded_data: Dict[str, Any], attempt: int) -> bool: ...
