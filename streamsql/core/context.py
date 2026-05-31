from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any


@dataclass
class ProcessingContext:
    trace_id: str
    start_time: float = field(default_factory=time.time)
    metadata: dict[str, Any] = field(default_factory=dict)
    errors: list[tuple[str, str]] = field(default_factory=list)
    metrics: dict[str, float] = field(default_factory=dict)
    resources: list[Any] = field(default_factory=list)
    cancelled: bool = False

    def elapsed(self) -> float:
        return time.time() - self.start_time

    def add_metadata(self, key: str, value: Any) -> None:
        self.metadata[key] = value

    def add_metric(self, name: str, value: float) -> None:
        self.metrics[name] = value

    def add_error(self, error_type: str, message: str) -> None:
        self.errors.append((error_type, message))

    def track_resource(self, resource: Any) -> None:
        self.resources.append(resource)

    def cancel(self) -> None:
        self.cancelled = True

    def is_cancelled(self) -> bool:
        return self.cancelled

    def cleanup(self) -> None:
        for resource in self.resources:
            try:
                if hasattr(resource, "close"):
                    resource.close()
                elif hasattr(resource, "release"):
                    resource.release()
            except Exception:
                pass
        self.resources.clear()

    def to_dict(self) -> dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "elapsed": self.elapsed(),
            "metadata": self.metadata,
            "metrics": self.metrics,
            "errors": self.errors,
            "cancelled": self.cancelled,
        }


class ContextManager:
    _contexts: dict[str, ProcessingContext] = {}

    @classmethod
    def create(cls, trace_id: str) -> ProcessingContext:
        ctx = ProcessingContext(trace_id=trace_id)
        cls._contexts[trace_id] = ctx
        return ctx

    @classmethod
    def get(cls, trace_id: str) -> ProcessingContext | None:
        return cls._contexts.get(trace_id)

    @classmethod
    def remove(cls, trace_id: str) -> None:
        if trace_id in cls._contexts:
            ctx = cls._contexts.pop(trace_id)
            ctx.cleanup()

    @classmethod
    def cleanup_all(cls) -> None:
        for ctx in cls._contexts.values():
            ctx.cleanup()
        cls._contexts.clear()
