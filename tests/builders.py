"""
Test data builders for constructing test fixtures in a fluent, type-safe manner.

Design Pattern: Builder Pattern (similar to JUnit 5 Test Builder utilities)

Usage:
    task = TaskBuilder().with_id("task_001").with_name("my_task").build()
    template = TemplateBuilder().with_id("python-service").build()
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, TypeVar

from src.models import (
    Notification,
    NotificationChannel,
    NotificationStatus,
    RunPhase,
    RunInstance,
    Task,
    TaskGraph,
)
from src.scheduler.scheduler import (
    CircuitBreaker,
    DependencyGraph,
    ExecutionResult,
    ScheduledTask,
    TaskStatus,
)
from src.scaffolder.scaffolder import (
    GeneratedFile,
    InteractivePrompter,
    Template,
    TemplateManager,
    TemplateVariable,
)

T = TypeVar("T")


class Builder:
    """Base builder with fluent API support."""

    def _copy(self) -> "Builder":
        import copy
        return copy.deepcopy(self)

    def build(self) -> Any:
        raise NotImplementedError


# ============================================================================
# Task & TaskGraph Builders
# ============================================================================


class TaskBuilder(Builder):
    """Builder for Task model objects."""

    def __init__(self) -> None:
        self._task_id: str = f"task_{id(self)}"
        self._name: str = "default_task"
        self._description: Optional[str] = None
        self._dependencies: List[str] = []
        self._parameters: Dict[str, Any] = {"sleep_time": 0.01}
        self._timeout: int = 60
        self._retries: int = 1

    def with_id(self, task_id: str) -> "TaskBuilder":
        self._task_id = task_id
        return self

    def with_name(self, name: str) -> "TaskBuilder":
        self._name = name
        return self

    def with_description(self, description: str) -> "TaskBuilder":
        self._description = description
        return self

    def with_dependencies(self, *dependencies: str) -> "TaskBuilder":
        self._dependencies = list(dependencies)
        return self

    def with_parameters(self, **params: Any) -> "TaskBuilder":
        self._parameters = dict(params)
        return self

    def with_timeout(self, timeout: int) -> "TaskBuilder":
        self._timeout = timeout
        return self

    def with_retries(self, retries: int) -> "TaskBuilder":
        self._retries = retries
        return self

    def with_handler(self, handler: Callable) -> "TaskBuilder":
        self._parameters["handler"] = handler
        return self

    def with_sleep_time(self, seconds: float) -> "TaskBuilder":
        self._parameters["sleep_time"] = seconds
        return self

    def build(self) -> Task:
        return Task(
            task_id=self._task_id,
            name=self._name,
            description=self._description,
            dependencies=self._dependencies.copy(),
            parameters=self._parameters.copy(),
            timeout=self._timeout,
            retries=self._retries,
        )


class TaskGraphBuilder(Builder):
    """Builder for TaskGraph model objects."""

    def __init__(self) -> None:
        self._graph_id: str = f"graph_{id(self)}"
        self._name: str = "default_graph"
        self._tasks: List[Task] = []

    def with_id(self, graph_id: str) -> "TaskGraphBuilder":
        self._graph_id = graph_id
        return self

    def with_name(self, name: str) -> "TaskGraphBuilder":
        self._name = name
        return self

    def with_tasks(self, *tasks: Task) -> "TaskGraphBuilder":
        self._tasks = list(tasks)
        return self

    def add_task(self, task: Task) -> "TaskGraphBuilder":
        self._tasks.append(task)
        return self

    def add_chain(self, *names: str, base_params: Optional[Dict[str, Any]] = None) -> "TaskGraphBuilder":
        """Add a linear chain of tasks: A -> B -> C"""
        tasks = []
        prev_id = None
        for i, name in enumerate(names):
            task_id = f"task_{name.lower()}"
            deps = [prev_id] if prev_id else []
            task = TaskBuilder() \
                .with_id(task_id) \
                .with_name(name) \
                .with_dependencies(*deps) \
                .with_parameters(**(base_params or {"sleep_time": 0.01})) \
                .build()
            tasks.append(task)
            prev_id = task_id
        self._tasks.extend(tasks)
        return self

    def add_fanout(
        self,
        source: str,
        *targets: str,
        base_params: Optional[Dict[str, Any]] = None,
    ) -> "TaskGraphBuilder":
        """Add a fan-out pattern: source -> [target1, target2, ...]"""
        params = base_params or {"sleep_time": 0.01}
        source_task = TaskBuilder() \
            .with_id(f"task_{source.lower()}") \
            .with_name(source) \
            .with_parameters(**params) \
            .build()
        self._tasks.append(source_task)

        for target in targets:
            target_task = TaskBuilder() \
                .with_id(f"task_{target.lower()}") \
                .with_name(target) \
                .with_dependencies(source_task.task_id) \
                .with_parameters(**params) \
                .build()
            self._tasks.append(target_task)

        return self

    def add_fanin(
        self,
        *sources: str,
        target: str,
        base_params: Optional[Dict[str, Any]] = None,
    ) -> "TaskGraphBuilder":
        """Add a fan-in pattern: [source1, source2, ...] -> target"""
        params = base_params or {"sleep_time": 0.01}
        source_ids = []

        for source in sources:
            source_task = TaskBuilder() \
                .with_id(f"task_{source.lower()}") \
                .with_name(source) \
                .with_parameters(**params) \
                .build()
            self._tasks.append(source_task)
            source_ids.append(source_task.task_id)

        target_task = TaskBuilder() \
            .with_id(f"task_{target.lower()}") \
            .with_name(target) \
            .with_dependencies(*source_ids) \
            .with_parameters(**params) \
            .build()
        self._tasks.append(target_task)

        return self

    def build(self) -> TaskGraph:
        return TaskGraph(
            graph_id=self._graph_id,
            name=self._name,
            tasks=self._tasks.copy(),
        )


# ============================================================================
# Execution Result Builder
# ============================================================================


class ExecutionResultBuilder(Builder):
    """Builder for ExecutionResult objects."""

    def __init__(self) -> None:
        self._task_id: str = f"task_{id(self)}"
        self._status: TaskStatus = TaskStatus.COMPLETED
        self._result: Optional[Any] = None
        self._error: Optional[str] = None
        self._started_at: Optional[float] = None
        self._completed_at: Optional[float] = None
        self._retry_count: int = 0
        self._metadata: Dict[str, Any] = {}

    def for_task(self, task_id: str) -> "ExecutionResultBuilder":
        self._task_id = task_id
        return self

    def with_status(self, status: TaskStatus) -> "ExecutionResultBuilder":
        self._status = status
        return self

    def completed(self) -> "ExecutionResultBuilder":
        self._status = TaskStatus.COMPLETED
        self._started_at = time.time() - 1.0
        self._completed_at = time.time()
        return self

    def failed(self, error: str = "Test error") -> "ExecutionResultBuilder":
        self._status = TaskStatus.FAILED
        self._error = error
        self._started_at = time.time() - 0.5
        self._completed_at = time.time()
        return self

    def skipped(self, reason: str = "Dependency failed") -> "ExecutionResultBuilder":
        self._status = TaskStatus.SKIPPED
        self._error = reason
        return self

    def timed_out(self) -> "ExecutionResultBuilder":
        self._status = TaskStatus.TIMEOUT
        self._error = "Task timed out"
        return self

    def with_result(self, result: Any) -> "ExecutionResultBuilder":
        self._result = result
        return self

    def with_retry_count(self, count: int) -> "ExecutionResultBuilder":
        self._retry_count = count
        return self

    def with_metadata(self, **kwargs: Any) -> "ExecutionResultBuilder":
        self._metadata = dict(kwargs)
        return self

    def build(self) -> ExecutionResult:
        return ExecutionResult(
            task_id=self._task_id,
            status=self._status,
            result=self._result,
            error=self._error,
            started_at=self._started_at,
            completed_at=self._completed_at,
            retry_count=self._retry_count,
            metadata=self._metadata.copy(),
        )


# ============================================================================
# Circuit Breaker Builder
# ============================================================================


class CircuitBreakerBuilder(Builder):
    """Builder for CircuitBreaker objects."""

    def __init__(self) -> None:
        self._failure_threshold: int = 5
        self._recovery_timeout: int = 30

    def with_failure_threshold(self, threshold: int) -> "CircuitBreakerBuilder":
        self._failure_threshold = threshold
        return self

    def with_recovery_timeout(self, timeout: int) -> "CircuitBreakerBuilder":
        self._recovery_timeout = timeout
        return self

    def lenient(self) -> "CircuitBreakerBuilder":
        self._failure_threshold = 100
        self._recovery_timeout = 1
        return self

    def strict(self) -> "CircuitBreakerBuilder":
        self._failure_threshold = 1
        self._recovery_timeout = 300
        return self

    def build(self) -> CircuitBreaker:
        return CircuitBreaker(
            failure_threshold=self._failure_threshold,
            recovery_timeout=self._recovery_timeout,
        )


# ============================================================================
# Dependency Graph Builder
# ============================================================================


class DependencyGraphBuilder(Builder):
    """Builder for DependencyGraph objects."""

    def __init__(self) -> None:
        self._tasks: List[Task] = []

    def with_task(self, task: Task) -> "DependencyGraphBuilder":
        self._tasks.append(task)
        return self

    def with_tasks(self, *tasks: Task) -> "DependencyGraphBuilder":
        self._tasks.extend(tasks)
        return self

    def add_chain(self, *names: str) -> "DependencyGraphBuilder":
        prev_id = None
        for name in names:
            task_id = f"task_{name.lower()}"
            deps = [prev_id] if prev_id else []
            task = TaskBuilder() \
                .with_id(task_id) \
                .with_name(name) \
                .with_dependencies(*deps) \
                .build()
            self._tasks.append(task)
            prev_id = task_id
        return self

    def build(self) -> DependencyGraph:
        graph = DependencyGraph()
        for task in self._tasks:
            graph.add_task(task)
        return graph


# ============================================================================
# Template & Scaffolding Builders
# ============================================================================


class TemplateVariableBuilder(Builder):
    """Builder for TemplateVariable objects."""

    def __init__(self) -> None:
        self._name: str = "variable"
        self._type: str = "string"
        self._description: Optional[str] = None
        self._default: Optional[Any] = None
        self._required: bool = True
        self._choices: Optional[List[Any]] = None
        self._validation: Optional[str] = None

    def with_name(self, name: str) -> "TemplateVariableBuilder":
        self._name = name
        return self

    def of_type(self, var_type: str) -> "TemplateVariableBuilder":
        self._type = var_type
        return self

    def with_description(self, description: str) -> "TemplateVariableBuilder":
        self._description = description
        return self

    def with_default(self, default: Any) -> "TemplateVariableBuilder":
        self._default = default
        return self

    def required(self, is_required: bool = True) -> "TemplateVariableBuilder":
        self._required = is_required
        return self

    def optional(self) -> "TemplateVariableBuilder":
        self._required = False
        return self

    def with_choices(self, *choices: Any) -> "TemplateVariableBuilder":
        self._choices = list(choices)
        return self

    def with_validation(self, pattern: str) -> "TemplateVariableBuilder":
        self._validation = pattern
        return self

    def build(self) -> TemplateVariable:
        return TemplateVariable(
            name=self._name,
            type=self._type,
            description=self._description,
            default=self._default,
            required=self._required,
            choices=self._choices,
            validation=self._validation,
        )


class TemplateBuilder(Builder):
    """Builder for Template objects."""

    def __init__(self) -> None:
        self._template_id: str = f"tpl_{id(self)}"
        self._name: str = "Default Template"
        self._description: str = "A test template"
        self._version: str = "1.0.0"
        self._variables: List[TemplateVariable] = []
        self._tags: List[str] = []
        self._directory: str = "default"

    def with_id(self, template_id: str) -> "TemplateBuilder":
        self._template_id = template_id
        return self

    def with_name(self, name: str) -> "TemplateBuilder":
        self._name = name
        return self

    def with_description(self, description: str) -> "TemplateBuilder":
        self._description = description
        return self

    def with_version(self, version: str) -> "TemplateBuilder":
        self._version = version
        return self

    def with_variables(self, *variables: TemplateVariable) -> "TemplateBuilder":
        self._variables = list(variables)
        return self

    def add_variable(self, variable: TemplateVariable) -> "TemplateBuilder":
        self._variables.append(variable)
        return self

    def with_tags(self, *tags: str) -> "TemplateBuilder":
        self._tags = list(tags)
        return self

    def with_directory(self, directory: str) -> "TemplateBuilder":
        self._directory = directory
        return self

    def python_service(self) -> "TemplateBuilder":
        self._template_id = "python-service"
        self._name = "Python Microservice"
        self._description = "Standard Python microservice template"
        self._tags = ["python", "fastapi"]
        self._directory = "python-service"
        self._variables = [
            TemplateVariableBuilder()
            .with_name("project_name")
            .with_description("Project name")
            .with_validation(r"^[a-z][a-z0-9-]+$")
            .build(),
            TemplateVariableBuilder()
            .with_name("author")
            .with_description("Author name")
            .optional()
            .with_default("Developer")
            .build(),
        ]
        return self

    def build(self) -> Template:
        return Template(
            template_id=self._template_id,
            name=self._name,
            description=self._description,
            version=self._version,
            variables=self._variables.copy(),
            tags=self._tags.copy(),
            directory=self._directory,
        )


class GeneratedFileBuilder(Builder):
    """Builder for GeneratedFile objects."""

    def __init__(self) -> None:
        self._path: str = "output.txt"
        self._content: str = "Hello, World!"
        self._template_source: Optional[str] = None
        self._is_binary: bool = False

    def with_path(self, path: str) -> "GeneratedFileBuilder":
        self._path = path
        return self

    def with_content(self, content: str) -> "GeneratedFileBuilder":
        self._content = content
        return self

    def from_template(self, template_source: str) -> "GeneratedFileBuilder":
        self._template_source = template_source
        return self

    def binary(self) -> "GeneratedFileBuilder":
        self._is_binary = True
        return self

    def build(self) -> GeneratedFile:
        return GeneratedFile(
            path=self._path,
            content=self._content,
            template_source=self._template_source,
            is_binary=self._is_binary,
        )


# ============================================================================
# Notification Builder
# ============================================================================


class NotificationBuilder(Builder):
    """Builder for Notification objects."""

    def __init__(self) -> None:
        self._channel: NotificationChannel = NotificationChannel.EMAIL
        self._recipient: str = "test@example.com"
        self._content: str = "Test notification content"
        self._subject: Optional[str] = None
        self._max_retries: int = 3
        self._metadata: Dict[str, Any] = {}

    def via(self, channel: NotificationChannel) -> "NotificationBuilder":
        self._channel = channel
        return self

    def to(self, recipient: str) -> "NotificationBuilder":
        self._recipient = recipient
        return self

    def with_content(self, content: str) -> "NotificationBuilder":
        self._content = content
        return self

    def with_subject(self, subject: str) -> "NotificationBuilder":
        self._subject = subject
        return self

    def with_max_retries(self, retries: int) -> "NotificationBuilder":
        self._max_retries = retries
        return self

    def with_metadata(self, **kwargs: Any) -> "NotificationBuilder":
        self._metadata = dict(kwargs)
        return self

    def email(self) -> "NotificationBuilder":
        return self.via(NotificationChannel.EMAIL).to("test@example.com")

    def sms(self) -> "NotificationBuilder":
        return self.via(NotificationChannel.SMS).to("+1234567890")

    def webhook(self) -> "NotificationBuilder":
        return self.via(NotificationChannel.WEBHOOK).to("https://example.com/webhook")

    def slack(self) -> "NotificationBuilder":
        return self.via(NotificationChannel.SLACK).to("#general")

    def build(self) -> Notification:
        return Notification(
            channel=self._channel,
            recipient=self._recipient,
            content=self._content,
            subject=self._subject,
            max_retries=self._max_retries,
            metadata=self._metadata.copy(),
        )


# ============================================================================
# Run Instance Builder
# ============================================================================


class RunInstanceBuilder(Builder):
    """Builder for RunInstance objects."""

    def __init__(self) -> None:
        self._run_id: str = f"run_{id(self)}"
        self._entity_id: str = "entity_001"
        self._phase: RunPhase = RunPhase.INITIALIZING
        self._progress: float = 0.0
        self._started_at: Optional[datetime] = None
        self._completed_at: Optional[datetime] = None
        self._error_detail: Optional[str] = None

    def with_id(self, run_id: str) -> "RunInstanceBuilder":
        self._run_id = run_id
        return self

    def for_entity(self, entity_id: str) -> "RunInstanceBuilder":
        self._entity_id = entity_id
        return self

    def in_phase(self, phase: RunPhase) -> "RunInstanceBuilder":
        self._phase = phase
        return self

    def with_progress(self, progress: float) -> "RunInstanceBuilder":
        self._progress = progress
        return self

    def started(self) -> "RunInstanceBuilder":
        self._started_at = datetime.utcnow()
        self._phase = RunPhase.EXECUTING
        return self

    def completed(self) -> "RunInstanceBuilder":
        self._completed_at = datetime.utcnow()
        self._progress = 1.0
        self._phase = RunPhase.COMPLETED
        return self

    def failed(self, error: str = "Test failure") -> "RunInstanceBuilder":
        self._completed_at = datetime.utcnow()
        self._error_detail = error
        self._phase = RunPhase.FAILED
        return self

    def build(self) -> RunInstance:
        return RunInstance(
            run_id=self._run_id,
            entity_id=self._entity_id,
            phase=self._phase,
            progress=self._progress,
            started_at=self._started_at,
            completed_at=self._completed_at,
            error_detail=self._error_detail,
        )


# ============================================================================
# Convenience Functions
# ============================================================================


def create_simple_task(task_id: str = "task_simple", name: str = "simple_task") -> Task:
    """Create a simple task for quick testing."""
    return TaskBuilder() \
        .with_id(task_id) \
        .with_name(name) \
        .build()


def create_complex_graph() -> TaskGraph:
    """Create a complex task graph with dependencies: A -> (B, C) -> D."""
    return TaskGraphBuilder() \
        .with_id("graph_complex") \
        .with_name("Complex Graph") \
        .add_chain("A", "D") \
        .add_fanout("A", "B", "C") \
        .add_fanin("B", "C", target="D") \
        .build()


def create_template_variables_dict() -> Dict[str, Any]:
    """Create valid template variables for python-service template."""
    return {
        "project_name": "test-project",
        "author": "Test Developer",
        "python_version": "3.10",
        "use_database": True,
        "database_type": "postgresql",
        "use_redis": False,
        "use_docker": True,
        "license": "MIT",
    }


# ============================================================================
# Async Test Helpers
# ============================================================================


async def async_success_handler(params: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """A simple async handler that always succeeds."""
    await asyncio.sleep(params.get("sleep_time", 0.01))
    return {"status": "success", "params": params}


async def async_failing_handler(params: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """A simple async handler that always fails."""
    await asyncio.sleep(params.get("sleep_time", 0.01))
    raise ValueError("Intentional failure for testing")


async def async_slow_handler(params: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """A slow async handler for timeout testing."""
    await asyncio.sleep(params.get("sleep_time", 5))
    return {"status": "slow_success"}


def sync_handler(params: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """A synchronous handler."""
    return {"status": "sync_success", "params": params}


class Counter:
    """Thread-safe counter for tracking handler invocations."""

    def __init__(self) -> None:
        self._count = 0
        self._lock = asyncio.Lock()

    async def increment(self) -> None:
        async with self._lock:
            self._count += 1

    async def decrement(self) -> None:
        async with self._lock:
            self._count -= 1

    @property
    def count(self) -> int:
        return self._count

    def reset(self) -> None:
        self._count = 0
