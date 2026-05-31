from __future__ import annotations

from typing import Optional

from top.domain.scheduling.workflow import WorkflowEngine


_engine_instance: Optional[WorkflowEngine] = None


def get_workflow_engine() -> WorkflowEngine:
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = WorkflowEngine()
    return _engine_instance


def set_workflow_engine(engine: WorkflowEngine) -> None:
    global _engine_instance
    _engine_instance = engine
