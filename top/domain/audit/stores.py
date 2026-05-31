from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from collections import deque
from datetime import datetime
from typing import Any, Dict, List, Optional

from top.core.models import AuditLogEntry, CommandRecord


class CommandStore(ABC):
    @abstractmethod
    async def append(self, command: CommandRecord) -> CommandRecord:
        pass

    @abstractmethod
    async def get_by_id(self, command_id: str) -> Optional[CommandRecord]:
        pass

    @abstractmethod
    async def get_by_correlation(self, correlation_id: str) -> List[CommandRecord]:
        pass

    @abstractmethod
    async def list_by_type(self, command_type: str, limit: int = 100) -> List[CommandRecord]:
        pass

    @abstractmethod
    async def list_by_time(
        self,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[CommandRecord]:
        pass

    @abstractmethod
    async def count_by_type(self, start: datetime, end: datetime) -> Dict[str, int]:
        pass


class AuditLogStore(ABC):
    @abstractmethod
    async def append(self, entry: AuditLogEntry) -> AuditLogEntry:
        pass

    @abstractmethod
    async def query(
        self,
        actor: Optional[str] = None,
        action: Optional[str] = None,
        resource: Optional[str] = None,
        command_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[AuditLogEntry]:
        pass

    @abstractmethod
    async def count_by_action(self, start: datetime, end: datetime) -> Dict[str, int]:
        pass

    @abstractmethod
    async def count_by_actor(self, start: datetime, end: datetime) -> Dict[str, int]:
        pass


class InMemoryCommandStore(CommandStore):
    def __init__(self, max_commands: int = 10000):
        self._commands: deque[CommandRecord] = deque(maxlen=max_commands)
        self._by_id: Dict[str, CommandRecord] = {}
        self._by_correlation: Dict[str, List[CommandRecord]] = {}
        self._lock = asyncio.Lock()

    async def append(self, command: CommandRecord) -> CommandRecord:
        async with self._lock:
            self._commands.append(command)
            self._by_id[command.command_id] = command
            if command.correlation_id:
                if command.correlation_id not in self._by_correlation:
                    self._by_correlation[command.correlation_id] = []
                self._by_correlation[command.correlation_id].append(command)
        return command

    async def get_by_id(self, command_id: str) -> Optional[CommandRecord]:
        async with self._lock:
            return self._by_id.get(command_id)

    async def get_by_correlation(self, correlation_id: str) -> List[CommandRecord]:
        async with self._lock:
            return list(self._by_correlation.get(correlation_id, []))

    async def list_by_type(self, command_type: str, limit: int = 100) -> List[CommandRecord]:
        async with self._lock:
            result = []
            for cmd in reversed(self._commands):
                if cmd.command_type == command_type:
                    result.append(cmd)
                    if len(result) >= limit:
                        break
            return result

    async def list_by_time(
        self,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[CommandRecord]:
        async with self._lock:
            result = []
            for cmd in reversed(self._commands):
                if start and cmd.issued_at < start:
                    continue
                if end and cmd.issued_at > end:
                    continue
                result.append(cmd)
                if len(result) >= limit:
                    break
            return result

    async def count_by_type(self, start: datetime, end: datetime) -> Dict[str, int]:
        async with self._lock:
            counts: Dict[str, int] = {}
            for cmd in self._commands:
                if cmd.issued_at < start or cmd.issued_at > end:
                    continue
                counts[cmd.command_type] = counts.get(cmd.command_type, 0) + 1
            return counts


class InMemoryAuditLogStore(AuditLogStore):
    def __init__(self, max_entries: int = 50000):
        self._entries: deque[AuditLogEntry] = deque(maxlen=max_entries)
        self._by_command: Dict[str, List[AuditLogEntry]] = {}
        self._by_correlation: Dict[str, List[AuditLogEntry]] = {}
        self._lock = asyncio.Lock()

    async def append(self, entry: AuditLogEntry) -> AuditLogEntry:
        async with self._lock:
            self._entries.append(entry)
            if entry.command_id:
                if entry.command_id not in self._by_command:
                    self._by_command[entry.command_id] = []
                self._by_command[entry.command_id].append(entry)
            if entry.correlation_id:
                if entry.correlation_id not in self._by_correlation:
                    self._by_correlation[entry.correlation_id] = []
                self._by_correlation[entry.correlation_id].append(entry)
        return entry

    async def query(
        self,
        actor: Optional[str] = None,
        action: Optional[str] = None,
        resource: Optional[str] = None,
        command_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[AuditLogEntry]:
        async with self._lock:
            if command_id:
                entries = self._by_command.get(command_id, [])
            elif correlation_id:
                entries = self._by_correlation.get(correlation_id, [])
            else:
                entries = list(self._entries)

            result = []
            for entry in reversed(entries):
                if actor and entry.actor != actor:
                    continue
                if action and entry.action != action:
                    continue
                if resource and entry.resource != resource:
                    continue
                if start and entry.timestamp < start:
                    continue
                if end and entry.timestamp > end:
                    continue
                result.append(entry)
                if len(result) >= limit:
                    break
            return result

    async def count_by_action(self, start: datetime, end: datetime) -> Dict[str, int]:
        async with self._lock:
            counts: Dict[str, int] = {}
            for entry in self._entries:
                if entry.timestamp < start or entry.timestamp > end:
                    continue
                counts[entry.action] = counts.get(entry.action, 0) + 1
            return counts

    async def count_by_actor(self, start: datetime, end: datetime) -> Dict[str, int]:
        async with self._lock:
            counts: Dict[str, int] = {}
            for entry in self._entries:
                if entry.timestamp < start or entry.timestamp > end:
                    continue
                counts[entry.actor] = counts.get(entry.actor, 0) + 1
            return counts
