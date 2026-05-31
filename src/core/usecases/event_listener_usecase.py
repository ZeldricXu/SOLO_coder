from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4

from eth_utils import event_abi_to_log_topic

from src.shared.utils import get_abi_element, get_event_data

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.event_listener_port import EventCallback, IEventListenerPort, IEventProcessor
from src.shared.config import settings
from src.shared.errors import EventFilterError, EventListenerError, RetryExhaustedError
from src.shared.logger import LogContext, generate_trace_id, get_logger
from src.shared.types import Address, BlockNumber, EventLog, HexString

logger = get_logger(__name__)


class EventListenerService(IEventListenerPort):
    def __init__(
        self,
        chain_adapter: IChainInteractionPort,
        max_retry: Optional[int] = None,
        poll_interval: Optional[int] = None,
        confirmation_blocks: Optional[int] = None,
    ):
        self._chain = chain_adapter
        self._max_retry = max_retry or settings.event_listener.max_retry
        self._poll_interval = poll_interval or settings.event_listener.poll_interval
        self._confirmation_blocks = confirmation_blocks or settings.event_listener.confirmation_blocks
        self._callbacks: Dict[str, EventCallback] = {}
        self._is_listening = False
        self._listener_task: Optional[asyncio.Task] = None
        self._last_processed_block: Optional[BlockNumber] = None
        self._retry_queue: asyncio.Queue = asyncio.Queue()

    def _generate_callback_id(self, contract_address: Address, event_name: str) -> str:
        raw = f"{contract_address.lower()}:{event_name}:{uuid4().hex[:8]}"
        return hashlib.md5(raw.encode()).hexdigest()[:16]

    async def register_callback(
        self,
        event_name: str,
        contract_address: Address,
        callback: Callable[[EventLog, Dict[str, Any]], None],
        abi: Optional[List[Dict[str, Any]]] = None,
        from_block: Optional[BlockNumber] = None,
        filter_params: Optional[Dict[str, Any]] = None,
    ) -> str:
        callback_id = self._generate_callback_id(contract_address, event_name)
        event_callback = EventCallback(
            event_name=event_name,
            contract_address=contract_address,
            callback=callback,
            abi=abi,
            filter_params=filter_params or {},
        )

        if abi:
            try:
                event_abi = get_abi_element(abi, "event", event_name)
                topic0 = event_abi_to_log_topic(event_abi).hex()
                event_callback.topic0 = "0x" + topic0 if not topic0.startswith("0x") else topic0
            except Exception as e:
                raise EventFilterError(f"Failed to generate event topic: {e}")

        self._callbacks[callback_id] = event_callback

        if from_block is not None:
            asyncio.create_task(self._backfill_events(callback_id, from_block))

        logger.info(
            "Event callback registered",
            callback_id=callback_id,
            event_name=event_name,
            contract_address=contract_address,
        )

        return callback_id

    async def unregister_callback(self, callback_id: str) -> bool:
        if callback_id in self._callbacks:
            del self._callbacks[callback_id]
            logger.info("Event callback unregistered", callback_id=callback_id)
            return True
        return False

    async def _backfill_events(self, callback_id: str, from_block: BlockNumber) -> None:
        callback = self._callbacks.get(callback_id)
        if not callback:
            return

        try:
            current_block = await self._chain.get_block_number()
            to_block = current_block - self._confirmation_blocks

            if from_block > to_block:
                return

            events = await self.fetch_past_events(
                contract_address=callback.contract_address,
                event_name=callback.event_name,
                from_block=from_block,
                to_block=to_block,
                abi=callback.abi,
                filter_params=callback.filter_params,
            )

            for log in events:
                await self._process_log(log, callback)

        except Exception as e:
            logger.error(f"Backfill failed for callback {callback_id}: {e}")

    async def start_listening(self) -> None:
        if self._is_listening:
            return

        self._is_listening = True
        self._last_processed_block = await self._chain.get_block_number()
        self._listener_task = asyncio.create_task(self._listen_loop())
        asyncio.create_task(self._retry_loop())

        logger.info(
            "Event listener started",
            chain=self._chain.chain.value,
            start_block=self._last_processed_block,
        )

    async def stop_listening(self) -> None:
        self._is_listening = False
        if self._listener_task and not self._listener_task.done():
            self._listener_task.cancel()
            try:
                await self._listener_task
            except asyncio.CancelledError:
                pass
        logger.info("Event listener stopped")

    async def _listen_loop(self) -> None:
        while self._is_listening:
            try:
                current_block = await self._chain.get_block_number()
                safe_block = current_block - self._confirmation_blocks

                if safe_block > (self._last_processed_block or 0):
                    from_block = (self._last_processed_block or safe_block - 1) + 1

                    all_addresses = list(
                        set(cb.contract_address for cb in self._callbacks.values())
                    )
                    all_topics = list(
                        set(cb.topic0 for cb in self._callbacks.values() if cb.topic0)
                    ) or None

                    logs = await self._chain.get_logs(
                        from_block=from_block,
                        to_block=safe_block,
                        address=all_addresses if all_addresses else None,
                        topics=[all_topics] if all_topics else None,
                    )

                    for log in logs:
                        await self._dispatch_log(log)

                    self._last_processed_block = safe_block

            except Exception as e:
                logger.error(f"Listener loop error: {e}")

            await asyncio.sleep(self._poll_interval)

    async def _dispatch_log(self, log: EventLog) -> None:
        for callback_id, callback in self._callbacks.items():
            if log.address.lower() != callback.contract_address.lower():
                continue

            if callback.topic0 and log.topics and log.topics[0].lower() != callback.topic0.lower():
                continue

            try:
                await self._process_log(log, callback)
            except Exception as e:
                logger.error(
                    f"Failed to process log {log.transaction_hash}: {e}",
                    callback_id=callback_id,
                )
                await self._queue_retry(log, callback)

    async def _process_log(self, log: EventLog, callback: EventCallback) -> None:
        trace_id = generate_trace_id()
        log_ctx = LogContext(
            trace_id=trace_id,
            tx_hash=log.transaction_hash,
            block_number=log.block_number,
            event_name=callback.event_name,
        )

        decoded_data: Dict[str, Any] = {}
        if callback.abi:
            try:
                decoded_data = await self.decode_event_log(log, callback.abi)
            except Exception as e:
                logger.warning(
                    f"Failed to decode event log: {e}",
                    **log_ctx.get_log_kwargs(),
                )

        if asyncio.iscoroutinefunction(callback.callback):
            await callback.callback(log, decoded_data)
        else:
            await asyncio.to_thread(callback.callback, log, decoded_data)

        logger.info(
            "Event processed successfully",
            **log_ctx.get_log_kwargs(),
        )

    async def _queue_retry(self, log: EventLog, callback: EventCallback) -> None:
        await self._retry_queue.put(
            {
                "log": log,
                "callback": callback,
                "attempt": 0,
                "next_attempt_at": datetime.now().timestamp() + settings.event_listener.retry_delay,
            }
        )

    async def _retry_loop(self) -> None:
        while self._is_listening:
            try:
                item = await self._retry_queue.get()
                now = datetime.now().timestamp()

                if item["next_attempt_at"] > now:
                    await asyncio.sleep(item["next_attempt_at"] - now)

                attempt = item["attempt"] + 1
                if attempt > self._max_retry:
                    raise RetryExhaustedError(
                        f"event_{item['log'].transaction_hash}",
                        self._max_retry,
                        "Max retry attempts reached",
                    )

                try:
                    await self._process_log(item["log"], item["callback"])
                except Exception as e:
                    logger.warning(
                        f"Retry attempt {attempt} failed: {e}",
                        tx_hash=item["log"].transaction_hash,
                    )
                    item["attempt"] = attempt
                    item["next_attempt_at"] = (
                        datetime.now().timestamp()
                        + settings.event_listener.retry_delay * attempt
                    )
                    await self._retry_queue.put(item)

            except RetryExhaustedError as e:
                logger.error(f"Retry exhausted: {e}")
            except Exception as e:
                logger.error(f"Retry loop error: {e}")
                await asyncio.sleep(1)

    async def fetch_past_events(
        self,
        contract_address: Address,
        event_name: str,
        from_block: BlockNumber,
        to_block: Optional[BlockNumber | str] = None,
        abi: Optional[List[Dict[str, Any]]] = None,
        filter_params: Optional[Dict[str, Any]] = None,
    ) -> List[EventLog]:
        topics = None
        if abi:
            try:
                event_abi = get_abi_element(abi, "event", event_name)
                topic0 = event_abi_to_log_topic(event_abi).hex()
                topic0 = "0x" + topic0 if not topic0.startswith("0x") else topic0
                topics = [topic0]
            except Exception as e:
                raise EventFilterError(f"Failed to generate event topic: {e}")

        return await self._chain.get_logs(
            from_block=from_block,
            to_block=to_block or "latest",
            address=contract_address,
            topics=topics,
        )

    async def decode_event_log(self, log: EventLog, abi: List[Dict[str, Any]]) -> Dict[str, Any]:
        try:
            event_abi = get_abi_element(abi, "event", None)
            log_topics = [bytes.fromhex(t[2:] if t.startswith("0x") else t) for t in log.topics]
            log_data = bytes.fromhex(log.data[2:] if log.data.startswith("0x") else log.data)

            decoded = get_event_data(
                abi,
                {
                    "topics": log_topics,
                    "data": log_data,
                    "address": log.address,
                },
            )
            return dict(decoded["args"])
        except Exception as e:
            raise EventListenerError(f"Failed to decode event log: {e}")

    def is_listening(self) -> bool:
        return self._is_listening

    def get_registered_callbacks(self) -> Dict[str, EventCallback]:
        return self._callbacks.copy()


class EventProcessor(IEventProcessor):
    def __init__(self, handlers: Optional[Dict[str, Callable[[EventLog, Dict[str, Any]], Any]]] = None):
        self._handlers = handlers or {}
        self._metrics: Dict[str, Any] = {
            "processed": 0,
            "failed": 0,
            "retried": 0,
        }

    def register_handler(self, event_type: str, handler: Callable[[EventLog, Dict[str, Any]], Any]) -> None:
        self._handlers[event_type] = handler

    async def process_event(self, log: EventLog, decoded_data: Dict[str, Any]) -> None:
        self._metrics["processed"] += 1
        event_type = decoded_data.get("event_type", log.topics[0] if log.topics else "unknown")

        handler = self._handlers.get(event_type) or self._handlers.get("default")
        if handler:
            if asyncio.iscoroutinefunction(handler):
                await handler(log, decoded_data)
            else:
                await asyncio.to_thread(handler, log, decoded_data)

    async def handle_error(self, error: Exception, log: EventLog) -> None:
        self._metrics["failed"] += 1
        logger.error(
            f"Event processing error: {error}",
            tx_hash=log.transaction_hash,
            log_index=log.log_index,
        )

    async def retry_event(self, log: EventLog, decoded_data: Dict[str, Any], attempt: int) -> bool:
        self._metrics["retried"] += 1
        try:
            await self.process_event(log, decoded_data)
            return True
        except Exception as e:
            await self.handle_error(e, log)
            return False

    def get_metrics(self) -> Dict[str, Any]:
        return self._metrics.copy()
