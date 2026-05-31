import asyncio
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional

from eth_utils import keccak

from ..config import get_settings
from ..db.models import EventFilter, EventLog
from ..dataclasses import FilterConfig
from ..interfaces.modules import IEventListenerModule
from ..interfaces.repositories import IEventListenerRepository
from ..interfaces.services import IWebhookSender, ICallbackHandlerRegistry
from ..utils import (
    get_logger,
    generate_id,
    to_checksum_address,
    ValidationError,
    NotFoundError,
)
from .event_listener_strategies import (
    get_strategy_registry,
    EventStrategyRegistry,
    ProcessingStrategyType,
    ProcessingContext,
    ProcessingResult,
    IEventProcessingStrategy,
)

logger = get_logger(__name__)


class EventPollingTaskManager:
    def __init__(
        self,
        repository: IEventListenerRepository,
        chain_adapter: Any,
        webhook_sender: IWebhookSender,
        callback_registry: ICallbackHandlerRegistry,
        strategy_registry: EventStrategyRegistry,
    ):
        self._repository = repository
        self._chain_adapter = chain_adapter
        self._webhook_sender = webhook_sender
        self._callback_registry = callback_registry
        self._strategy_registry = strategy_registry
        self._settings = get_settings()
        self._active_tasks: Dict[str, asyncio.Task] = {}
        self._running = False

    async def start_all(self) -> None:
        if self._running:
            return

        self._running = True
        active_filters = await self._repository.list_active_filters()
        for f in active_filters:
            await self.start_filter(f)

        logger.info(f"Started {len(self._active_tasks)} existing filters")

    async def start_filter(self, filter_config: EventFilter) -> None:
        if not self._running:
            return

        if filter_config.filter_id in self._active_tasks:
            return

        task = asyncio.create_task(self._filter_loop(filter_config))
        self._active_tasks[filter_config.filter_id] = task
        logger.info(f"Started listener for filter {filter_config.filter_id}")

    async def stop_filter(self, filter_id: str) -> None:
        if filter_id in self._active_tasks:
            self._active_tasks[filter_id].cancel()
            del self._active_tasks[filter_id]
            logger.info(f"Stopped listener for filter {filter_id}")

    async def stop_all(self) -> None:
        self._running = False

        for task in self._active_tasks.values():
            task.cancel()

        await asyncio.gather(*self._active_tasks.values(), return_exceptions=True)
        self._active_tasks.clear()

    def is_running(self, filter_id: str) -> bool:
        return filter_id in self._active_tasks

    async def _filter_loop(self, filter_config: EventFilter) -> None:
        filter_id = filter_config.filter_id
        ev_settings = self._settings.events
        retry_interval = ev_settings.retry_interval

        while self._running and filter_config.is_active:
            try:
                await self._poll_filter(filter_config)
                await asyncio.sleep(ev_settings.poll_interval)
                retry_interval = ev_settings.retry_interval
            except asyncio.CancelledError:
                logger.info(f"Filter listener {filter_id} cancelled")
                break
            except Exception as e:
                    logger.error(f"Error in filter {filter_id}: {e}")
                    await self._repository.record_filter_error(filter_id, str(e))
                    await asyncio.sleep(retry_interval)
                    retry_interval = min(
                        retry_interval * ev_settings.backoff_multiplier,
                        ev_settings.max_retry_interval,
                    )

        if filter_id in self._active_tasks:
            del self._active_tasks[filter_id]

        logger.info(f"Stopped listener for filter {filter_id}")

    async def _poll_filter(self, filter_config: EventFilter) -> None:
        ev_settings = self._settings.events

        current_block = await self._chain_adapter.get_block_number(filter_config.chain_id)
        from_block = filter_config.last_processed_block + 1
        to_block = min(
            from_block + ev_settings.max_blocks_per_poll - 1,
            current_block
        )

        if from_block > to_block:
            return

        logs = await self._chain_adapter.get_logs(
            chain_id=filter_config.chain_id,
            from_block=from_block,
            to_block=to_block,
            address=filter_config.contract_address,
            topics=filter_config.topics,
        )

        for log_data in logs:
            await self._process_log(filter_config, log_data)

        await self._repository.update_last_processed_block(filter_config.filter_id, to_block)

    async def _process_log(self, filter_config: EventFilter, log_data: Dict[str, Any]) -> None:
        log_id = generate_id("log")

        tx_hash = log_data.get("transactionHash", "")
        log_index = int(log_data.get("logIndex", "0x0"), 16)

        event_log = EventLog(
            log_id=log_id,
            filter_id=filter_config.filter_id,
            chain_id=filter_config.chain_id,
            block_number=int(log_data.get("blockNumber", "0x0"), 16),
            block_hash=log_data.get("blockHash", ""),
            transaction_hash=tx_hash,
            transaction_index=int(log_data.get("transactionIndex", "0x0"), 16),
            log_index=log_index,
            address=log_data.get("address", ""),
            topics=log_data.get("topics", []),
            data=log_data.get("data", "0x"),
            removed=log_data.get("removed", False),
            processed=False,
        )

        created_log = await self._repository.create_event_log(event_log)

        try:
            strategy = self._strategy_registry.get_strategy(filter_config.filter_id)
            context = ProcessingContext(
                filter_id=filter_config.filter_id,
                chain_id=filter_config.chain_id,
                event_signature=filter_config.event_signature,
                log_data=log_data,
            )

            def handler(ctx):
                return self._execute_callback_handler(filter_config, log_data, created_log)

            result = await strategy.process_event(context, handler)

            if result.success and result.processed:
                await self._repository.mark_log_processed(log_id)
            elif not result.success:
                await self._repository.mark_log_processed(log_id, error=result.error)

        except Exception as e:
            logger.error(f"Failed to process log {log_id}: {e}")
            await self._repository.mark_log_processed(log_id, error=str(e))

    async def _execute_callback_handler(
        self,
        filter_config: EventFilter,
        log_data: Dict[str, Any],
        event_log: EventLog,
    ) -> None:
        event_data = {
            "filter_id": filter_config.filter_id,
            "chain_id": filter_config.chain_id,
            "event_signature": filter_config.event_signature,
            "log": log_data,
            "log_id": event_log.log_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

        if filter_config.callback_url:
            await self._webhook_sender.send_webhook(
                url=filter_config.callback_url,
                headers=filter_config.callback_headers,
                payload=event_data,
            )

        handler = self._callback_registry.get_handler(filter_config.event_signature)
        if handler:
            try:
                if asyncio.iscoroutinefunction(handler):
                    await handler(event_data)
                else:
                    handler(event_data)
            except Exception as e:
                logger.error(f"Callback handler error for {filter_config.event_signature}: {e}")


class EventListenerModule(IEventListenerModule):
    def __init__(
        self,
        repository: IEventListenerRepository,
        chain_adapter: Any,
        webhook_sender: IWebhookSender,
        callback_registry: ICallbackHandlerRegistry,
        strategy_registry: Optional[EventStrategyRegistry] = None,
    ):
        self._repository = repository
        self._chain_adapter = chain_adapter
        self._webhook_sender = webhook_sender
        self._callback_registry = callback_registry
        self._strategy_registry = strategy_registry or get_strategy_registry()
        self._settings = get_settings()
        self._task_manager: Optional[EventPollingTaskManager] = None
        self._initialized = False
        self._running = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing event listener module")

        await self._strategy_registry.initialize()

        self._task_manager = EventPollingTaskManager(
            repository=self._repository,
            chain_adapter=self._chain_adapter,
            webhook_sender=self._webhook_sender,
            callback_registry=self._callback_registry,
            strategy_registry=self._strategy_registry,
        )
        self._initialized = True
        logger.info("Event listener module initialized with pluggable strategies")

    async def start(self) -> None:
        if self._running or not self._task_manager:
            return

        logger.info("Starting event listener module")
        await self._task_manager.start_all()
        self._running = True
        logger.info("Event listener module started")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down event listener module")
        self._running = False

        if self._task_manager:
            await self._task_manager.stop_all()

        await self._strategy_registry.shutdown()

        self._initialized = False
        logger.info("Event listener module shutdown complete")

    async def create_filter(self, config: FilterConfig) -> Dict[str, Any]:
        ev_settings = self._settings.events

        if self._task_manager and len(self._task_manager._active_tasks) >= ev_settings.max_concurrent_filters:
            raise ValidationError(
                f"Maximum concurrent filters ({ev_settings.max_concurrent_filters}) reached"
            )

        contract_address = to_checksum_address(config.contract_address)
        event_signature = config.event_signature

        topic0 = "0x" + keccak(text=event_signature).hex()
        topics = [topic0] + config.topics

        filter_id = generate_id("filter")

        event_filter = EventFilter(
            filter_id=filter_id,
            chain_id=config.chain_id,
            name=config.name or event_signature,
            contract_address=contract_address,
            event_signature=event_signature,
            topics=topics,
            from_block=config.from_block,
            to_block=config.to_block,
            last_processed_block=config.from_block - 1 if config.from_block > 0 else 0,
            callback_url=config.callback_url,
            callback_headers=config.callback_headers,
            is_active=True,
        )

        created_filter = await self._repository.create_filter(event_filter)

        if config.strategy:
            try:
                strategy_enum = ProcessingStrategyType(config.strategy)
                self._strategy_registry.set_filter_strategy(filter_id, strategy_enum)
            except ValueError:
                logger.warning(f"Invalid strategy {config.strategy} for filter {filter_id}, using default")

        if self._running and self._task_manager:
            await self._task_manager.start_filter(created_filter)

        logger.info(f"Created event filter {filter_id} for {contract_address}: {event_signature}")

        return {
            "filter_id": created_filter.filter_id,
            "chain_id": created_filter.chain_id,
            "contract_address": created_filter.contract_address,
            "event_signature": created_filter.event_signature,
            "topics": created_filter.topics,
            "from_block": created_filter.from_block,
            "to_block": created_filter.to_block,
            "callback_url": created_filter.callback_url,
            "is_active": True,
            "strategy": self._strategy_registry.get_filter_strategy_mapping().get(filter_id, ProcessingStrategyType.DEFAULT.value),
        }

    async def get_filter(self, filter_id: str) -> Optional[Dict[str, Any]]:
        f = await self._repository.get_filter(filter_id)
        if not f:
            return None

        strategy = self._strategy_registry.get_filter_strategy_mapping().get(filter_id, ProcessingStrategyType.DEFAULT.value)

        return {
            "filter_id": f.filter_id,
            "chain_id": f.chain_id,
            "name": f.name,
            "contract_address": f.contract_address,
            "event_signature": f.event_signature,
            "topics": f.topics,
            "from_block": f.from_block,
            "to_block": f.to_block,
            "last_processed_block": f.last_processed_block,
            "callback_url": f.callback_url,
            "is_active": f.is_active,
            "error_count": f.error_count,
            "last_error": f.last_error,
            "created_at": f.created_at.isoformat() if f.created_at else None,
            "strategy": strategy,
        }

    async def list_filters(
        self,
        chain_id: Optional[int] = None,
        is_active: Optional[bool] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        filters, total = await self._repository.list_filters(
            chain_id=chain_id,
            is_active=is_active,
            offset=offset,
            limit=limit,
        )

        strategy_mapping = self._strategy_registry.get_filter_strategy_mapping()

        return {
            "filters": [
                {
                    "filter_id": f.filter_id,
                    "chain_id": f.chain_id,
                    "name": f.name,
                    "contract_address": f.contract_address,
                    "event_signature": f.event_signature,
                    "is_active": f.is_active,
                    "last_processed_block": f.last_processed_block,
                    "strategy": strategy_mapping.get(f.filter_id, ProcessingStrategyType.DEFAULT.value),
                }
                for f in filters
            ],
            "total": total,
            "offset": offset,
            "limit": limit,
        }

    async def pause_filter(self, filter_id: str) -> Dict[str, Any]:
        await self._repository.update_filter_status(filter_id, False)

        if self._task_manager:
            await self._task_manager.stop_filter(filter_id)

        logger.info(f"Paused filter {filter_id}")

        return {"filter_id": filter_id, "is_active": False}

    async def resume_filter(self, filter_id: str) -> Dict[str, Any]:
        await self._repository.update_filter_status(filter_id, True)

        if self._running and self._task_manager:
            filter_config = await self._repository.get_filter(filter_id)
            if filter_config:
                await self._task_manager.start_filter(filter_config)

        logger.info(f"Resumed filter {filter_id}")

        return {"filter_id": filter_id, "is_active": True}

    async def delete_filter(self, filter_id: str) -> None:
        f = await self._repository.get_filter(filter_id)
        if not f:
            raise NotFoundError(f"Filter {filter_id} not found")

        await self._repository.delete_filter(filter_id)

        if self._task_manager:
            await self._task_manager.stop_filter(filter_id)

        logger.info(f"Deleted filter {filter_id}")

    def register_callback_handler(self, event_type: str, handler: Callable) -> None:
        self._callback_registry.register_handler(event_type, handler)

    async def get_event_logs(
        self, filter_id: str, offset: int = 0, limit: int = 50
    ) -> Dict[str, Any]:
        logs, total = await self._repository.list_event_logs(
            filter_id=filter_id, offset=offset, limit=limit
        )

        return {
            "logs": [
                {
                    "log_id": l.log_id,
                    "block_number": l.block_number,
                    "transaction_hash": l.transaction_hash,
                    "log_index": l.log_index,
                    "address": l.address,
                    "topics": l.topics,
                    "data": l.data,
                    "decoded_data": l.decoded_data,
                    "processed": l.processed,
                    "processing_error": l.processing_error,
                    "created_at": l.created_at.isoformat() if l.created_at else None,
                }
                for l in logs
            ],
            "total": total,
            "offset": offset,
            "limit": limit,
        }

    async def set_filter_strategy(self, filter_id: str, strategy_type: str) -> Dict[str, Any]:
        f = await self._repository.get_filter(filter_id)
        if not f:
            raise NotFoundError(f"Filter {filter_id} not found")

        try:
            strategy_enum = ProcessingStrategyType(strategy_type)
        except ValueError:
            raise ValidationError(f"Invalid strategy type: {strategy_type}")

        self._strategy_registry.set_filter_strategy(filter_id, strategy_enum)

        return {
            "filter_id": filter_id,
            "strategy": strategy_type,
            "success": True,
        }

    async def set_default_strategy(self, strategy_type: str) -> Dict[str, Any]:
        try:
            strategy_enum = ProcessingStrategyType(strategy_type)
        except ValueError:
            raise ValidationError(f"Invalid strategy type: {strategy_type}")

        self._strategy_registry.set_default_strategy(strategy_enum)

        return {
            "default_strategy": strategy_type,
            "success": True,
        }

    async def get_available_strategies(self) -> Dict[str, Any]:
        strategies = self._strategy_registry.get_available_strategies()
        return {
            "strategies": strategies,
            "default_strategy": self._strategy_registry._default_strategy.value,
        }

    async def get_filter_strategies(self) -> Dict[str, Any]:
        mapping = self._strategy_registry.get_filter_strategy_mapping()
        return {
            "filter_strategies": mapping,
        }


_event_listener_module: Optional[EventListenerModule] = None


async def create_event_listener_module(
    chain_adapter: Any,
    container: Any = None,
) -> EventListenerModule:
    if container is None:
        from ..container import get_container
        container = get_container()

    repo = await container.get_event_listener_repository()
    webhook_sender = await container.get_webhook_sender()
    callback_registry = await container.get_callback_handler_registry()
    strategy_registry = get_strategy_registry()

    return EventListenerModule(
        repository=repo,
        chain_adapter=chain_adapter,
        webhook_sender=webhook_sender,
        callback_registry=callback_registry,
        strategy_registry=strategy_registry,
    )


def get_event_listener_module() -> EventListenerModule:
    global _event_listener_module
    if _event_listener_module is None:
        raise RuntimeError("EventListenerModule not initialized. Call create_event_listener_module first.")
    return _event_listener_module


async def init_event_listener_module(chain_adapter: Any, container: Any = None) -> EventListenerModule:
    global _event_listener_module
    if _event_listener_module is None:
        _event_listener_module = await create_event_listener_module(chain_adapter, container)
        await _event_listener_module.initialize()
    return _event_listener_module
