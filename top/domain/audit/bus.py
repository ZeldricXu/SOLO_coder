from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any, Callable, Dict, Optional
from uuid import uuid4

from top.core.models import AuditLogEntry, CommandRecord
from top.domain.audit.stores import AuditLogStore, CommandStore, InMemoryAuditLogStore, InMemoryCommandStore


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


def utc_now() -> datetime:
    from datetime import timezone
    return datetime.now(timezone.utc)


class CommandHandler:
    async def handle(self, command: CommandRecord) -> Any:
        raise NotImplementedError("Subclasses must implement handle method")


class CommandBus:
    def __init__(
        self,
        command_store: Optional[CommandStore] = None,
        audit_store: Optional[AuditLogStore] = None,
    ):
        self._command_store = command_store or InMemoryCommandStore()
        self._audit_store = audit_store or InMemoryAuditLogStore()
        self._handlers: Dict[str, Callable[..., Any]] = {}
        self._instance_handlers: Dict[str, CommandHandler] = {}
        self._lock = asyncio.Lock()

    @property
    def command_store(self) -> CommandStore:
        return self._command_store

    @property
    def audit_store(self) -> AuditLogStore:
        return self._audit_store

    def register_handler(
        self, command_type: str, handler: Callable[..., Any]
    ) -> None:
        if isinstance(handler, CommandHandler):
            self._instance_handlers[command_type] = handler
        else:
            self._handlers[command_type] = handler

    def unregister_handler(self, command_type: str) -> None:
        self._handlers.pop(command_type, None)
        self._instance_handlers.pop(command_type, None)

    def has_handler(self, command_type: str) -> bool:
        return command_type in self._handlers or command_type in self._instance_handlers

    def _get_handler(self, command_type: str) -> Optional[Callable[..., Any]]:
        if command_type in self._instance_handlers:
            return self._instance_handlers[command_type]
        return self._handlers.get(command_type)

    async def send(
        self,
        command_type: str,
        payload: Dict[str, Any],
        issued_by: str = "system",
        correlation_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        command = CommandRecord(
            command_id=generate_id("cmd"),
            command_type=command_type,
            payload=payload,
            issued_by=issued_by,
            issued_at=utc_now(),
            correlation_id=correlation_id or generate_id("corr"),
        )

        await self._command_store.append(command)

        await self._audit_store.append(
            AuditLogEntry(
                log_id=generate_id("audit"),
                action="command.issued",
                actor=issued_by,
                resource=f"command:{command.command_type}",
                details={
                    "command_id": command.command_id,
                    "payload": payload,
                },
                command_id=command.command_id,
                correlation_id=command.correlation_id,
            )
        )

        handler = self._get_handler(command_type)
        if handler:
            try:
                if isinstance(handler, CommandHandler):
                    result = await handler.handle(command)
                elif asyncio.iscoroutinefunction(handler):
                    result = await handler(command)
                else:
                    result = handler(command)

                await self._audit_store.append(
                    AuditLogEntry(
                        log_id=generate_id("audit"),
                        action="command.executed",
                        actor=issued_by,
                        resource=f"command:{command.command_type}",
                        details={
                            "command_id": command.command_id,
                            "success": True,
                        },
                        command_id=command.command_id,
                        correlation_id=command.correlation_id,
                    )
                )

                return {
                    "command_id": command.command_id,
                    "correlation_id": command.correlation_id,
                    "status": "executed",
                    "result": result,
                }
            except Exception as e:
                await self._audit_store.append(
                    AuditLogEntry(
                        log_id=generate_id("audit"),
                        action="command.failed",
                        actor=issued_by,
                        resource=f"command:{command.command_type}",
                        details={
                            "command_id": command.command_id,
                            "error": str(e),
                        },
                        command_id=command.command_id,
                        correlation_id=command.correlation_id,
                    )
                )
                raise

        return {
            "command_id": command.command_id,
            "correlation_id": command.correlation_id,
            "status": "stored",
        }

    async def publish(
        self,
        command_type: str,
        payload: Dict[str, Any],
        issued_by: str = "system",
        correlation_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        command = CommandRecord(
            command_id=generate_id("cmd"),
            command_type=command_type,
            payload=payload,
            issued_by=issued_by,
            issued_at=utc_now(),
            correlation_id=correlation_id or generate_id("corr"),
        )

        await self._command_store.append(command)

        await self._audit_store.append(
            AuditLogEntry(
                log_id=generate_id("audit"),
                action="command.published",
                actor=issued_by,
                resource=f"command:{command.command_type}",
                details={
                    "command_id": command.command_id,
                    "payload": payload,
                    "async": True,
                },
                command_id=command.command_id,
                correlation_id=command.correlation_id,
            )
        )

        return {
            "command_id": command.command_id,
            "correlation_id": command.correlation_id,
            "status": "queued",
        }


_bus_instance: Optional[CommandBus] = None


def get_command_bus() -> CommandBus:
    global _bus_instance
    if _bus_instance is None:
        _bus_instance = CommandBus()
    return _bus_instance
