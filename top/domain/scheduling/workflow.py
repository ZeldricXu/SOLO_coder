from __future__ import annotations

import asyncio
import time
from collections import deque
from typing import Any, AsyncGenerator, Callable, Dict, List, Optional, Set
from uuid import uuid4

from top.core.models import TaskDefinition, WorkflowDefinition
from top.domain.scheduling.graph import DependencyResolver, TaskGraph
from top.domain.scheduling.models import (
    ExecutionContext,
    ExecutionResult,
    ExecutionStatus,
    RetryPolicy,
    TaskResult,
    utc_now,
)
from top.domain.scheduling.tasks import TaskExecutor


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class WorkflowRunner:
    def __init__(
        self,
        executor: TaskExecutor,
        retry_policy: Optional[RetryPolicy] = None,
        continue_on_failure: bool = False,
    ):
        self._executor = executor
        self._retry_policy = retry_policy or RetryPolicy.default()
        self._continue_on_failure = continue_on_failure

    async def run(
        self,
        workflow: WorkflowDefinition,
        trace_id: Optional[str] = None,
        context_data: Optional[Dict[str, Any]] = None,
        graph_factory: Callable[[List[TaskDefinition]], DependencyResolver] = TaskGraph,
    ) -> ExecutionResult:
        run_id = generate_id("run")
        trace_id = trace_id or generate_id("trace")

        result = ExecutionResult(
            run_id=run_id,
            workflow_id=workflow.workflow_id,
            status=ExecutionStatus.RUNNING,
        )

        graph = graph_factory(workflow.tasks)

        if graph.has_cycle():
            result.status = ExecutionStatus.FAILED
            result.error = "Circular dependency detected"
            result.completed_at = utc_now()
            return result

        completed: Set[str] = set()
        failed: Set[str] = set()
        task_results: Dict[str, TaskResult] = {}
        shared_context = context_data or {}

        start_time = time.time()

        try:
            while len(completed) + len(failed) < graph.task_count():
                ready = graph.get_ready_tasks(completed, failed)

                if not ready:
                    break

                async def run_task(task_id: str) -> TaskResult:
                    task = graph.get_task(task_id)
                    if not task:
                        return TaskResult(
                            task_id=task_id,
                            status=ExecutionStatus.SKIPPED,
                            error="Task not found",
                        )

                    ctx = ExecutionContext(
                        run_id=run_id,
                        trace_id=trace_id,
                        workflow_id=workflow.workflow_id,
                        task_id=task_id,
                        context_data=dict(shared_context),
                    )
                    policy = RetryPolicy(
                        max_retries=task.retries
                        if task.retries
                        else self._retry_policy.max_retries
                    )
                    return await self._executor.execute(task, ctx, policy)

                tasks = [run_task(tid) for tid in ready]
                results = await asyncio.gather(*tasks)

                for task_result in results:
                    task_results[task_result.task_id] = task_result
                    if task_result.is_success:
                        completed.add(task_result.task_id)
                        if task_result.result:
                            shared_context[f"result_{task_result.task_id}"] = (
                                task_result.result
                            )
                    else:
                        failed.add(task_result.task_id)
                        if not self._continue_on_failure:
                            for tid in [
                                t.task_id
                                for t in graph.all_tasks()
                            ]:
                                if tid not in completed and tid not in failed:
                                    skipped = TaskResult(
                                        task_id=tid,
                                        status=ExecutionStatus.SKIPPED,
                                        error="Dependency failed",
                                    )
                                    task_results[tid] = skipped
                                    failed.add(tid)
                            break

        except Exception as e:
            result.error = str(e)
            result.status = ExecutionStatus.FAILED

        finally:
            result.task_results = list(task_results.values())
            result.total_duration_ms = (time.time() - start_time) * 1000
            result.completed_at = utc_now()

            if failed:
                result.status = ExecutionStatus.FAILED
            else:
                result.status = ExecutionStatus.SUCCESS

        return result

    async def run_streaming(
        self,
        workflow: WorkflowDefinition,
        trace_id: Optional[str] = None,
        context_data: Optional[Dict[str, Any]] = None,
        graph_factory: Callable[[List[TaskDefinition]], DependencyResolver] = TaskGraph,
    ) -> AsyncGenerator[TaskResult, None]:
        run_id = generate_id("run")
        trace_id = trace_id or generate_id("trace")

        graph = graph_factory(workflow.tasks)

        if graph.has_cycle():
            yield TaskResult(
                task_id="__workflow__",
                status=ExecutionStatus.FAILED,
                error="Circular dependency detected",
            )
            return

        completed: Set[str] = set()
        failed: Set[str] = set()
        shared_context = context_data or {}

        while len(completed) + len(failed) < graph.task_count():
            ready = graph.get_ready_tasks(completed, failed)

            if not ready:
                break

            for task_id in ready:
                task = graph.get_task(task_id)
                if not task:
                    continue

                ctx = ExecutionContext(
                    run_id=run_id,
                    trace_id=trace_id,
                    workflow_id=workflow.workflow_id,
                    task_id=task_id,
                    context_data=dict(shared_context),
                )
                policy = RetryPolicy(
                    max_retries=task.retries
                    if task.retries
                    else self._retry_policy.max_retries
                )

                task_result = await self._executor.execute(task, ctx, policy)
                yield task_result

                if task_result.is_success:
                    completed.add(task_result.task_id)
                    if task_result.result:
                        shared_context[f"result_{task_result.task_id}"] = task_result.result
                else:
                    failed.add(task_result.task_id)
                    if not self._continue_on_failure:
                        break

            if not self._continue_on_failure and failed:
                break


class WorkflowEngine:
    def __init__(
        self,
        executor: Optional[TaskExecutor] = None,
        runner: Optional[WorkflowRunner] = None,
        max_history: int = 1000,
    ):
        self._executor = executor or TaskExecutor()
        self._runner = runner or WorkflowRunner(self._executor)
        self._workflows: Dict[str, WorkflowDefinition] = {}
        self._history: deque[ExecutionResult] = deque(maxlen=max_history)

    @property
    def executor(self) -> TaskExecutor:
        return self._executor

    @property
    def runner(self) -> WorkflowRunner:
        return self._runner

    def register_task_handler(
        self,
        handler_name: str,
        handler: Callable[..., Any],
    ) -> None:
        self._executor.register_handler(handler_name, handler)

    def register_workflow(self, workflow: WorkflowDefinition) -> None:
        self._workflows[workflow.workflow_id] = workflow

    def get_workflow(self, workflow_id: str) -> Optional[WorkflowDefinition]:
        return self._workflows.get(workflow_id)

    def list_workflows(self) -> List[str]:
        return list(self._workflows.keys())

    async def run_workflow(
        self,
        workflow_id: str,
        trace_id: Optional[str] = None,
        context_data: Optional[Dict[str, Any]] = None,
    ) -> ExecutionResult:
        workflow = self._workflows.get(workflow_id)
        if not workflow:
            raise ValueError(f"Workflow '{workflow_id}' not registered")

        result = await self._runner.run(workflow, trace_id, context_data)
        self._history.append(result)
        return result

    async def run_workflow_definition(
        self,
        workflow: WorkflowDefinition,
        trace_id: Optional[str] = None,
        context_data: Optional[Dict[str, Any]] = None,
    ) -> ExecutionResult:
        result = await self._runner.run(workflow, trace_id, context_data)
        self._history.append(result)
        return result

    async def stream_workflow(
        self,
        workflow: WorkflowDefinition,
        trace_id: Optional[str] = None,
        context_data: Optional[Dict[str, Any]] = None,
    ) -> AsyncGenerator[TaskResult, None]:
        async for task_result in self._runner.run_streaming(workflow, trace_id, context_data):
            yield task_result

    def get_execution_history(
        self,
        workflow_id: Optional[str] = None,
        limit: int = 100,
    ) -> List[ExecutionResult]:
        results = list(self._history)
        if workflow_id:
            results = [r for r in results if r.workflow_id == workflow_id]
        return results[-limit:]

    def get_execution(self, run_id: str) -> Optional[ExecutionResult]:
        for r in self._history:
            if r.run_id == run_id:
                return r
        return None
