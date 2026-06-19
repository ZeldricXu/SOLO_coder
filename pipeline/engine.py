import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import json
import time
import logging
from dataclasses import dataclass, field, asdict
from typing import List, Dict, Any, Optional, Callable
from concurrent.futures import ThreadPoolExecutor, Future, as_completed
from enum import Enum

from config.pipeline_config import PipelineStep, PipelineDefinition, StepStatus
from pipeline.dag import PipelineDAG, DAGNode
from pipeline.executor import (
    BaseStepExecutor,
    StepExecutorRegistry,
    RetryableStepExecutor,
    StepResult,
    StepExecutionError,
)

logger = logging.getLogger(__name__)


class EngineStatus(str, Enum):
    IDLE = "idle"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class EngineCheckpoint:
    dag_state: Dict[str, Any]
    completed_steps: List[str]
    output_map: Dict[str, List[str]]
    step_metrics: Dict[str, Dict[str, Any]]
    timestamp: float = field(default_factory=time.time)

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2)

    @classmethod
    def from_json(cls, data: str) -> "EngineCheckpoint":
        parsed = json.loads(data)
        return cls(**parsed)


@dataclass
class EngineResult:
    success: bool
    total_steps: int
    completed_steps: int
    failed_steps: List[str]
    skipped_steps: List[str]
    output_map: Dict[str, List[str]]
    step_metrics: Dict[str, Dict[str, Any]]
    duration_seconds: float
    error_message: Optional[str] = None


class PipelineEngine:
    """Orchestrates pipeline step execution with DAG, retries, and checkpointing."""

    def __init__(
        self,
        steps: List[PipelineStep],
        work_dir: str,
        temp_dir: Optional[str] = None,
        max_workers: int = 4,
        checkpoint_path: Optional[str] = None,
        on_step_start: Optional[Callable[[PipelineStep], None]] = None,
        on_step_complete: Optional[Callable[[PipelineStep, StepResult], None]] = None,
        on_step_fail: Optional[Callable[[PipelineStep, str], None]] = None,
    ):
        self.dag = PipelineDAG(steps)
        self.work_dir = Path(work_dir)
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir = Path(temp_dir) if temp_dir else self.work_dir / "tmp"
        self.temp_dir.mkdir(parents=True, exist_ok=True)
        self.max_workers = max_workers
        self.checkpoint_path = Path(checkpoint_path) if checkpoint_path else self.work_dir / "checkpoint.json"
        self.status = EngineStatus.IDLE

        self.on_step_start = on_step_start
        self.on_step_complete = on_step_complete
        self.on_step_fail = on_step_fail

        self.step_executors: Dict[str, RetryableStepExecutor] = {}
        self.step_metrics: Dict[str, Dict[str, Any]] = {}
        self._start_time: float = 0.0
        self._cancel_requested: bool = False

    def _get_executor(self, step: PipelineStep) -> RetryableStepExecutor:
        step_type_str = step.step_type if isinstance(step.step_type, str) else step.step_type.value

        if step.step_id not in self.step_executors:
            executor_class = StepExecutorRegistry.get_executor(step_type_str)
            if executor_class is None:
                raise ValueError(f"No executor registered for step type: {step_type_str}")

            base_executor = executor_class(
                work_dir=str(self.work_dir),
                temp_dir=str(self.temp_dir),
            )
            self.step_executors[step.step_id] = RetryableStepExecutor(
                executor=base_executor,
                max_retries=step.max_retries,
            )

        return self.step_executors[step.step_id]

    def _collect_input_files(self, step: PipelineStep) -> List[str]:
        inputs_map = self.dag.get_step_outputs_map()
        resolved = []

        for inp in step.inputs:
            found = False
            for _, outs in inputs_map.items():
                if inp in outs:
                    resolved.append(inp)
                    found = True
                    break
            if not found:
                resolved.append(inp)

        return resolved

    def _execute_single_step(self, step: PipelineStep) -> StepResult:
        node = self.dag.get_step(step.step_id)
        if node is None:
            return StepResult(
                success=False,
                step_id=step.step_id,
                error_message=f"Step not found in DAG: {step.step_id}",
            )

        if self.on_step_start:
            self.on_step_start(step)

        self.dag.set_step_status(step.step_id, StepStatus.RUNNING)

        try:
            retryable = self._get_executor(step)
            input_files = self._collect_input_files(step)

            start_time = time.time()
            result = retryable.execute(
                step_id=step.step_id,
                params=step.params,
                input_files=input_files,
            )
            duration = time.time() - start_time
            result.duration_seconds = duration

            if result.success:
                self.dag.set_step_status(step.step_id, StepStatus.COMPLETED)
                self.dag.set_step_outputs(step.step_id, result.output_files)

                if result.metrics:
                    self.step_metrics[step.step_id] = result.metrics

                if self.on_step_complete:
                    self.on_step_complete(step, result)

                self._save_checkpoint()
            else:
                self.dag.set_step_status(step.step_id, StepStatus.FAILED, error=result.error_message)
                if self.on_step_fail:
                    self.on_step_fail(step, result.error_message or "Unknown error")

            return result

        except Exception as e:
            error_msg = str(e)
            self.dag.set_step_status(step.step_id, StepStatus.FAILED, error=error_msg)
            if self.on_step_fail:
                self.on_step_fail(step, error_msg)

            return StepResult(
                success=False,
                step_id=step.step_id,
                error_message=error_msg,
            )

    def _run_parallel_group(self, group_steps: List[PipelineStep]) -> Dict[str, StepResult]:
        results: Dict[str, StepResult] = {}

        with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
            futures: Dict[Future, PipelineStep] = {}

            for step in group_steps:
                future = pool.submit(self._execute_single_step, step)
                futures[future] = step

            for future in as_completed(futures):
                step = futures[future]
                try:
                    results[step.step_id] = future.result()
                except Exception as e:
                    results[step.step_id] = StepResult(
                        success=False,
                        step_id=step.step_id,
                        error_message=str(e),
                    )

        return results

    def _run_ready_steps(self) -> bool:
        ready = self.dag.get_ready_steps()
        if not ready:
            return False

        parallel_groups = self.dag.get_parallel_groups()
        processed = set()
        any_ran = False

        for step in ready:
            if step.step_id in processed:
                continue

            if step.parallel_group:
                group_name = step.parallel_group
                group_ids = parallel_groups.get(group_name, [])

                group_steps = [s for s in ready if s.step_id in group_ids]
                group_steps += [
                    self.dag.nodes[s_id].step
                    for s_id in group_ids
                    if s_id in self.dag.nodes
                    and self.dag.nodes[s_id].status == StepStatus.PENDING
                    and s_id not in {s.step_id for s in group_steps}
                ]

                for gs in group_steps:
                    preds = list(self.dag.graph.predecessors(gs.step_id))
                    if all(
                        self.dag.nodes[p].status == StepStatus.COMPLETED
                        for p in preds
                    ):
                        self.dag.set_step_status(gs.step_id, StepStatus.RUNNING)

                self._run_parallel_group(group_steps)
                for gs in group_steps:
                    processed.add(gs.step_id)
                any_ran = True
            else:
                self._execute_single_step(step)
                processed.add(step.step_id)
                any_ran = True

        return any_ran

    def _save_checkpoint(self) -> None:
        try:
            dag_state = {
                sid: {
                    "status": node.status.value,
                    "retry_count": node.retry_count,
                    "error": node.error,
                    "output_files": node.output_files,
                }
                for sid, node in self.dag.nodes.items()
            }

            checkpoint = EngineCheckpoint(
                dag_state=dag_state,
                completed_steps=[s.step_id for s in self.dag.get_completed_steps()],
                output_map=self.dag.get_step_outputs_map(),
                step_metrics=dict(self.step_metrics),
            )

            with open(self.checkpoint_path, "w") as f:
                f.write(checkpoint.to_json())
        except Exception as e:
            logger.warning(f"Failed to save checkpoint: {e}")

    def load_checkpoint(self, checkpoint_path: Optional[str] = None) -> bool:
        path = Path(checkpoint_path) if checkpoint_path else self.checkpoint_path
        if not path.exists():
            return False

        try:
            with open(path, "r") as f:
                checkpoint = EngineCheckpoint.from_json(f.read())

            for sid, state in checkpoint.dag_state.items():
                if sid in self.dag.nodes:
                    status_val = state.get("status", "pending")
                    self.dag.nodes[sid].status = StepStatus(status_val)
                    self.dag.nodes[sid].retry_count = state.get("retry_count", 0)
                    self.dag.nodes[sid].error = state.get("error")
                    self.dag.nodes[sid].output_files = state.get("output_files", [])

            self.step_metrics.update(checkpoint.step_metrics)
            return True
        except Exception as e:
            logger.warning(f"Failed to load checkpoint: {e}")
            return False

    def pause(self) -> None:
        self._cancel_requested = True
        self.status = EngineStatus.PAUSED

    def run(self) -> EngineResult:
        self._start_time = time.time()
        self.status = EngineStatus.RUNNING
        self._cancel_requested = False

        try:
            while not self.dag.is_complete():
                if self._cancel_requested:
                    break

                if self.dag.has_failed():
                    break

                ran = self._run_ready_steps()
                if not ran:
                    break

            if self.dag.has_failed():
                self.status = EngineStatus.FAILED
            elif self.dag.is_success():
                self.status = EngineStatus.COMPLETED
            else:
                self.status = EngineStatus.PAUSED

            failed_ids = [s.step_id for s in self.dag.get_failed_steps()]
            skipped_ids = [sid for sid, n in self.dag.nodes.items() if n.status == StepStatus.SKIPPED]

            return EngineResult(
                success=self.status == EngineStatus.COMPLETED,
                total_steps=len(self.dag.nodes),
                completed_steps=len(self.dag.get_completed_steps()),
                failed_steps=failed_ids,
                skipped_steps=skipped_ids,
                output_map=self.dag.get_step_outputs_map(),
                step_metrics=dict(self.step_metrics),
                duration_seconds=time.time() - self._start_time,
                error_message=None if not failed_ids else f"Failed steps: {failed_ids}",
            )

        except Exception as e:
            self.status = EngineStatus.FAILED
            return EngineResult(
                success=False,
                total_steps=len(self.dag.nodes),
                completed_steps=len(self.dag.get_completed_steps()),
                failed_steps=list(self.dag.nodes.keys()),
                skipped_steps=[],
                output_map={},
                step_metrics={},
                duration_seconds=time.time() - self._start_time,
                error_message=str(e),
            )
