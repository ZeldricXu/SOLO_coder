import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set
from datetime import datetime, timezone

from web3 import Web3
from web3.contract import ContractEvent
from web3.types import LogReceipt

from wallethub.core import EventListenerError, EventStatus
from wallethub.config import get_settings
from wallethub.events import get_event_bus, ContractEventTriggeredEvent
from wallethub.utils import generate_id, async_retry

logger = logging.getLogger(__name__)


@dataclass
class EventFilter:
    contract_address: str
    event_name: str
    event_abi: Dict[str, Any]
    from_block: int = 0
    to_block: Optional[int] = None
    filter_params: Dict[str, Any] = field(default_factory=dict)


class ContractEventListener:
    def __init__(
        self,
        w3: Web3,
        chain: str,
        contract_address: str,
        event_name: str,
        event_abi: Dict[str, Any],
        callback: Optional[Callable[[Dict[str, Any]], Awaitable[None]]] = None,
        start_block: Optional[int] = None,
        poll_interval: Optional[float] = None,
    ):
        self.settings = get_settings()
        self.w3 = w3
        self.chain = chain
        self.contract_address = contract_address
        self.event_name = event_name
        self.event_abi = event_abi
        self.callback = callback
        self.poll_interval = poll_interval or self.settings.event_listener_poll_interval
        self.max_blocks_per_poll = self.settings.event_listener_max_blocks_per_poll

        self.listener_id = generate_id("listener")
        self.status = EventStatus.ACTIVE
        self.current_block = start_block or 0
        self.processed_logs: Set[str] = set()
        self._task: Optional[asyncio.Task] = None
        self._stop_event = asyncio.Event()
        self._event_bus = get_event_bus()

        try:
            contract = self.w3.eth.contract(address=contract_address, abi=[event_abi])
            self._event: ContractEvent = getattr(contract.events, event_name)
        except Exception as e:
            raise EventListenerError(f"Failed to create event filter: {str(e)}")

    async def start(self) -> None:
        if self.status == EventStatus.ACTIVE and self._task and not self._task.done():
            return

        if self.current_block == 0:
            self.current_block = await self._get_latest_block() - 1

        self.status = EventStatus.ACTIVE
        self._stop_event.clear()
        self._task = asyncio.create_task(self._poll_loop())
        logger.info(f"Started event listener {self.listener_id} for {self.event_name}")

    async def stop(self) -> None:
        self.status = EventStatus.PAUSED
        self._stop_event.set()
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=5)
            except asyncio.TimeoutError:
                self._task.cancel()
        logger.info(f"Stopped event listener {self.listener_id}")

    async def _poll_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                await self._poll_once()
            except Exception as e:
                logger.error(f"Poll error in listener {self.listener_id}: {str(e)}")
            await asyncio.sleep(self.poll_interval)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def _poll_once(self) -> None:
        latest_block = await self._get_latest_block()
        if latest_block <= self.current_block:
            return

        from_block = self.current_block + 1
        to_block = min(latest_block, from_block + self.max_blocks_per_poll - 1)

        try:
            logs = await self._get_logs(from_block, to_block)
            for log in logs:
                log_id = self._get_log_id(log)
                if log_id in self.processed_logs:
                    continue

                await self._process_log(log)
                self.processed_logs.add(log_id)

                if len(self.processed_logs) > 100000:
                    self.processed_logs = set(list(self.processed_logs)[-50000:])

            self.current_block = to_block
        except Exception as e:
            logger.error(f"Error processing blocks {from_block}-{to_block}: {str(e)}")
            raise

    async def _get_latest_block(self) -> int:
        return await self.w3.eth.block_number

    async def _get_logs(self, from_block: int, to_block: int) -> List[LogReceipt]:
        filter_params = {
            "fromBlock": from_block,
            "toBlock": to_block,
            "address": self.contract_address,
        }
        return await self.w3.eth.get_logs(filter_params)

    async def _process_log(self, log: LogReceipt) -> None:
        try:
            decoded = self._event.process_log(log)
            event_data = {
                "listener_id": self.listener_id,
                "chain": self.chain,
                "block_number": log["blockNumber"],
                "block_hash": log["blockHash"].hex(),
                "transaction_hash": log["transactionHash"].hex(),
                "log_index": log["logIndex"],
                "contract_address": log["address"],
                "event_name": self.event_name,
                "args": dict(decoded["args"]),
                "timestamp": int(datetime.now(timezone.utc).timestamp()),
            }

            await self._event_bus.publish(ContractEventTriggeredEvent(payload=event_data))

            if self.callback:
                await self.callback(event_data)

            logger.debug(f"Processed event {self.event_name} in tx {log['transactionHash'].hex()}")
        except Exception as e:
            logger.error(f"Error processing log: {str(e)}")

    @staticmethod
    def _get_log_id(log: LogReceipt) -> str:
        return f"{log['transactionHash'].hex()}_{log['logIndex']}"

    def get_status(self) -> Dict[str, Any]:
        return {
            "listener_id": self.listener_id,
            "chain": self.chain,
            "contract_address": self.contract_address,
            "event_name": self.event_name,
            "status": self.status.value,
            "current_block": self.current_block,
            "processed_count": len(self.processed_logs),
        }


class EventListenerManager:
    def __init__(self):
        self._listeners: Dict[str, ContractEventListener] = {}

    def create_listener(
        self,
        w3: Web3,
        chain: str,
        contract_address: str,
        event_name: str,
        event_abi: Dict[str, Any],
        callback: Optional[Callable[[Dict[str, Any]], Awaitable[None]]] = None,
        start_block: Optional[int] = None,
    ) -> ContractEventListener:
        listener = ContractEventListener(
            w3=w3,
            chain=chain,
            contract_address=contract_address,
            event_name=event_name,
            event_abi=event_abi,
            callback=callback,
            start_block=start_block,
        )
        self._listeners[listener.listener_id] = listener
        return listener

    def get_listener(self, listener_id: str) -> Optional[ContractEventListener]:
        return self._listeners.get(listener_id)

    async def start_listener(self, listener_id: str) -> None:
        listener = self._listeners.get(listener_id)
        if not listener:
            raise EventListenerError(f"Listener {listener_id} not found")
        await listener.start()

    async def stop_listener(self, listener_id: str) -> None:
        listener = self._listeners.get(listener_id)
        if not listener:
            raise EventListenerError(f"Listener {listener_id} not found")
        await listener.stop()

    async def start_all(self) -> None:
        for listener in self._listeners.values():
            await listener.start()

    async def stop_all(self) -> None:
        for listener in self._listeners.values():
            await listener.stop()

    def list_listeners(self) -> List[Dict[str, Any]]:
        return [listener.get_status() for listener in self._listeners.values()]

    def remove_listener(self, listener_id: str) -> None:
        listener = self._listeners.pop(listener_id, None)
        if listener:
            asyncio.create_task(listener.stop())
