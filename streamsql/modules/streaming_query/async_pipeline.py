from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Optional

from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.models import generate_id

from streamsql.modules.streaming_query.logical_plan import LogicalPlan, LogicalPlanner
from streamsql.modules.streaming_query.optimizer import LogicalPlanOptimizer
from streamsql.modules.streaming_query.physical_plan import PhysicalPlan, PhysicalPlanTranslator


class QueryStatus(str, Enum):
    PENDING = "pending"
    PARSING = "parsing"
    OPTIMIZING = "optimizing"
    PLANNING = "planning"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class AsyncQueryResult:
    """Result of an asynchronous query parsing operation."""

    query_id: str
    status: QueryStatus = QueryStatus.PENDING
    raw_sql: str = ""
    parsed_query: Optional["ParsedQuery"] = None
    logical_plan: Optional[LogicalPlan] = None
    optimized_plan: Optional[LogicalPlan] = None
    physical_plan: Optional[PhysicalPlan] = None
    error: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[float] = None
    metadata: dict[str, Any] = field(default_factory=dict)
    callbacks: list[Callable[["AsyncQueryResult"], Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "query_id": self.query_id,
            "status": self.status.value,
            "raw_sql": self.raw_sql,
            "error": self.error,
            "created_at": self.created_at.isoformat(),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "duration_ms": self.duration_ms,
            "has_parsed_query": self.parsed_query is not None,
            "has_logical_plan": self.logical_plan is not None,
            "has_optimized_plan": self.optimized_plan is not None,
            "has_physical_plan": self.physical_plan is not None,
            "metadata": self.metadata,
        }

    async def notify_callbacks(self) -> None:
        """Notify all registered callbacks about the result."""
        for callback in self.callbacks:
            try:
                result = callback(self)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass

    def add_callback(self, callback: Callable[["AsyncQueryResult"], Any]) -> None:
        """Add a callback to be notified when the query completes."""
        self.callbacks.append(callback)


@dataclass
class ParsePipelineOptions:
    """Options for the async parse pipeline."""

    generate_logical_plan: bool = True
    optimize_plan: bool = True
    generate_physical_plan: bool = False
    timeout: int = 30
    max_retries: int = 3
    retry_delay_ms: int = 100
    priority: int = 5
    queue_name: str = "default"


class AsyncParsePipeline:
    """
    Asynchronous pipeline for query parsing, optimization, and planning.

    Supports:
    - Async parsing with callback notifications
    - Event-driven result delivery
    - Query queue management
    - Pipeline stage execution (parse → optimize → physical plan)
    """

    def __init__(
        self,
        parser: Optional[Any] = None,
        optimizer: Optional[LogicalPlanOptimizer] = None,
        event_bus: Optional[EventBus] = None,
        max_concurrent: int = 10,
    ):
        from streamsql.modules.streaming_query.parser import StreamingQueryParser

        self._parser = parser or StreamingQueryParser()
        self._optimizer = optimizer or LogicalPlanOptimizer()
        self._event_bus = event_bus or EventBus()
        self._max_concurrent = max_concurrent
        self._semaphore = asyncio.Semaphore(max_concurrent)
        self._active_queries: dict[str, AsyncQueryResult] = {}
        self._queue: asyncio.Queue[tuple[str, str, ParsePipelineOptions, Optional[Callable]]] = asyncio.Queue()
        self._queue_task: Optional[asyncio.Task] = None
        self._running = False

    async def start(self) -> None:
        """Start the queue processing task."""
        if self._running:
            return
        self._running = True
        self._queue_task = asyncio.create_task(self._process_queue())

    async def stop(self) -> None:
        """Stop the queue processing task."""
        self._running = False
        if self._queue_task and not self._queue_task.done():
            self._queue_task.cancel()
            try:
                await self._queue_task
            except asyncio.CancelledError:
                pass

    async def parse_async(
        self,
        sql: str,
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> AsyncQueryResult:
        """
        Submit a SQL query for asynchronous parsing.

        Args:
            sql: The SQL query to parse
            options: Pipeline options
            callback: Optional callback to invoke when parsing completes

        Returns:
            AsyncQueryResult object that will be updated as parsing progresses
        """
        query_id = generate_id("sql")
        options = options or ParsePipelineOptions()

        result = AsyncQueryResult(
            query_id=query_id,
            raw_sql=sql,
            status=QueryStatus.PENDING,
            created_at=datetime.utcnow(),
        )

        if callback:
            result.add_callback(callback)

        self._active_queries[query_id] = result

        await self._queue.put((query_id, sql, options, callback))

        self._event_bus.emit(
            Event(
                EventType.QUERY_SUBMITTED,
                {
                    "query_id": query_id,
                    "sql": sql[:200] + "..." if len(sql) > 200 else sql,
                    "queue_size": self._queue.qsize(),
                },
            )
        )

        return result

    async def parse_now(
        self,
        sql: str,
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> AsyncQueryResult:
        """
        Parse a SQL query immediately (bypassing queue) and wait for completion.

        Args:
            sql: The SQL query to parse
            options: Pipeline options
            callback: Optional callback to invoke when parsing completes

        Returns:
            AsyncQueryResult with the final result
        """
        query_id = generate_id("sql")
        options = options or ParsePipelineOptions()

        result = AsyncQueryResult(
            query_id=query_id,
            raw_sql=sql,
            status=QueryStatus.PENDING,
            created_at=datetime.utcnow(),
        )

        if callback:
            result.add_callback(callback)

        self._active_queries[query_id] = result

        return await self._execute_pipeline(query_id, sql, options)

    async def parse_many_async(
        self,
        sqls: list[str],
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> list[AsyncQueryResult]:
        """Submit multiple SQL queries for asynchronous parsing."""
        results: list[AsyncQueryResult] = []
        for sql in sqls:
            result = await self.parse_async(sql, options, callback)
            results.append(result)
        return results

    async def parse_many(
        self,
        sqls: list[str],
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
        max_concurrent: Optional[int] = None,
    ) -> list[AsyncQueryResult]:
        """
        Parse multiple SQL queries concurrently and wait for all to complete.

        Args:
            sqls: List of SQL queries to parse
            options: Pipeline options
            callback: Optional callback for each query
            max_concurrent: Maximum concurrent executions (overrides instance default)

        Returns:
            List of AsyncQueryResult objects
        """
        sem = asyncio.Semaphore(max_concurrent or self._max_concurrent)

        async def parse_one(sql: str) -> AsyncQueryResult:
            async with sem:
                return await self.parse_now(sql, options, callback)

        tasks = [parse_one(sql) for sql in sqls]
        return await asyncio.gather(*tasks)

    def get_query_result(self, query_id: str) -> Optional[AsyncQueryResult]:
        """Get the current result for a query."""
        return self._active_queries.get(query_id)

    def get_active_queries(self) -> list[AsyncQueryResult]:
        """Get all active queries (pending or in progress)."""
        return [
            q for q in self._active_queries.values()
            if q.status in (QueryStatus.PENDING, QueryStatus.PARSING, QueryStatus.OPTIMIZING, QueryStatus.PLANNING)
        ]

    def get_completed_queries(self, limit: int = 100) -> list[AsyncQueryResult]:
        """Get recently completed queries."""
        completed = [
            q for q in self._active_queries.values()
            if q.status in (QueryStatus.COMPLETED, QueryStatus.FAILED, QueryStatus.CANCELLED)
        ]
        return sorted(completed, key=lambda q: q.completed_at or q.created_at, reverse=True)[:limit]

    def cancel_query(self, query_id: str) -> bool:
        """Cancel a pending query."""
        result = self._active_queries.get(query_id)
        if result and result.status == QueryStatus.PENDING:
            result.status = QueryStatus.CANCELLED
            result.completed_at = datetime.utcnow()
            return True
        return False

    async def _process_queue(self) -> None:
        """Background task to process the query queue."""
        while self._running:
            try:
                query_id, sql, options, callback = await self._queue.get()

                if query_id not in self._active_queries:
                    continue

                result = self._active_queries[query_id]
                if result.status == QueryStatus.CANCELLED:
                    del self._active_queries[query_id]
                    continue

                async with self._semaphore:
                    await self._execute_pipeline(query_id, sql, options)

                self._queue.task_done()

            except asyncio.CancelledError:
                break
            except Exception:
                continue

    async def _execute_pipeline(
        self,
        query_id: str,
        sql: str,
        options: ParsePipelineOptions,
    ) -> AsyncQueryResult:
        """Execute the full parsing pipeline for a query."""
        result = self._active_queries.get(query_id)
        if not result:
            result = AsyncQueryResult(query_id=query_id, raw_sql=sql)
            self._active_queries[query_id] = result

        result.started_at = datetime.utcnow()
        result.status = QueryStatus.PARSING

        try:
            self._event_bus.emit(
                Event(
                    EventType.QUERY_PARSING,
                    {"query_id": query_id, "sql": sql[:100]},
                )
            )

            parsed = self._parser.parse(sql)
            result.parsed_query = parsed

            if parsed.query_type is None:
                raise ValueError("No valid query type found (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER)")

            if options.generate_logical_plan:
                result.status = QueryStatus.OPTIMIZING
                self._event_bus.emit(
                    Event(EventType.QUERY_OPTIMIZING, {"query_id": query_id}),
                )

                logical_plan = self._build_logical_plan(parsed)
                result.logical_plan = logical_plan

                if options.optimize_plan:
                    optimized_plan = self._optimizer.optimize(logical_plan)
                    result.optimized_plan = optimized_plan

                if options.generate_physical_plan:
                    result.status = QueryStatus.PLANNING
                    physical_plan = self._build_physical_plan(
                        result.optimized_plan or result.logical_plan
                    )
                    result.physical_plan = physical_plan

            result.status = QueryStatus.COMPLETED
            result.error = None

        except Exception as e:
            result.status = QueryStatus.FAILED
            result.error = str(e)
            self._event_bus.emit(
                Event(EventType.QUERY_FAILED, {"query_id": query_id, "error": str(e)}),
            )

        finally:
            result.completed_at = datetime.utcnow()
            if result.started_at and result.completed_at:
                result.duration_ms = (
                    result.completed_at - result.started_at
                ).total_seconds() * 1000

            self._event_bus.emit(
                Event(
                    EventType.QUERY_COMPLETED,
                    {
                        "query_id": query_id,
                        "status": result.status.value,
                        "duration_ms": result.duration_ms,
                        "error": result.error,
                    },
                )
            )

            await result.notify_callbacks()

        return result

    def _build_logical_plan(self, parsed: "ParsedQuery") -> LogicalPlan:
        """Build a logical plan from a parsed query."""
        planner = LogicalPlanner()
        return planner.plan(parsed)

    def _build_physical_plan(self, logical_plan: LogicalPlan) -> PhysicalPlan:
        """Build a physical plan from a logical plan."""
        translator = PhysicalPlanTranslator()
        return translator.translate(logical_plan)

    def get_queue_size(self) -> int:
        """Get the current size of the parsing queue."""
        return self._queue.qsize()

    def get_stats(self) -> dict[str, Any]:
        """Get pipeline statistics."""
        all_queries = list(self._active_queries.values())
        completed = [
            q for q in all_queries
            if q.status == QueryStatus.COMPLETED
        ]
        failed = [
            q for q in all_queries
            if q.status == QueryStatus.FAILED
        ]

        avg_duration = None
        if completed:
            durations = [q.duration_ms for q in completed if q.duration_ms is not None]
            if durations:
                avg_duration = sum(durations) / len(durations)

        return {
            "queue_size": self._queue.qsize(),
            "active_queries": len(self.get_active_queries()),
            "completed_queries": len(completed),
            "failed_queries": len(failed),
            "avg_duration_ms": avg_duration,
            "max_concurrent": self._max_concurrent,
        }
