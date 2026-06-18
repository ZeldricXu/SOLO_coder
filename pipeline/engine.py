import logging
import os
from pathlib import Path
from typing import List, Dict, Any, Optional, Set, Tuple
from dataclasses import dataclass, field
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import time

from config.settings import settings
from config.pipeline_config import PipelineStep, PipelineStepType, StepStatus
from pipeline.dag import PipelineDAG
from pipeline.executor import (
    BaseStepExecutor,
    StepExecutorRegistry,
    RetryableStepExecutor,
    StepResult,
    StepExecutionError,
)
from storage.repository import TaskRepository
from db.models import TaskStatus

logger = logging.getLogger(__name__)


@dataclass
class PipelineContext:
    task_id: str
    sample_id: str
    work_dir: Path
    temp_dir: Path
    inputs: Dict[str, Any] = field(default_factory=dict)
    outputs: Dict[str, List[str]] = field(default_factory=dict)
    parameters: Dict[str, Any] = field(default_factory=dict)


class PipelineEngine:
    def __init__(
        self,
        task_id: str,
        sample_id: str,
        steps: List[PipelineStep],
        resume: bool = True,
        max_parallel: int = 4,
    ):
        self.task_id = task_id
        self.sample_id = sample_id
        self.dag = PipelineDAG(steps)
        self.resume = resume
        self.max_parallel = max_parallel

        self.work_dir = Path(settings.pipeline.work_dir) / task_id
        self.temp_dir = Path(settings.pipeline.temp_dir) / task_id
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir.mkdir(parents=True, exist_ok=True)

        self.context = PipelineContext(
            task_id=task_id,
            sample_id=sample_id,
            work_dir=self.work_dir,
            temp_dir=self.temp_dir,
        )

        self._executors: Dict[str, RetryableStepExecutor] = {}
        self._init_executors(steps)

        if resume:
            self._load_checkpoint()

    def _init_executors(self, steps: List[PipelineStep]) -> None:
        for step in steps:
            executor_class = StepExecutorRegistry.get_executor(step.step_type)
            if executor_class:
                base_executor = executor_class(
                    work_dir=str(self.work_dir),
                    temp_dir=str(self.temp_dir),
                )
                retry_executor = RetryableStepExecutor(
                    base_executor,
                    max_retries=step.max_retries,
                )
                self._executors[step.step_id] = retry_executor
            else:
                logger.warning(f"No executor registered for step type: {step.step_type}")

    def _checkpoint_path(self) -> Path:
        return self.work_dir / f"{self.task_id}_checkpoint.json"

    def _save_checkpoint(self) -> None:
        checkpoint = {
            "task_id": self.task_id,
            "sample_id": self.sample_id,
            "timestamp": time.time(),
            "node_states": {
                step_id: {
                    "status": node.status.value,
                    "retry_count": node.retry_count,
                    "error": node.error,
                    "output_files": node.output_files,
                }
                for step_id, node in self.dag.nodes.items()
            },
            "context_outputs": self.context.outputs,
        }
        with open(self._checkpoint_path(), "w") as f:
            json.dump(checkpoint, f, indent=2)
        logger.info(f"Saved checkpoint to {self._checkpoint_path()}")

    def _load_checkpoint(self) -> None:
        checkpoint_path = self._checkpoint_path()
        if not checkpoint_path.exists():
            logger.info("No checkpoint found, starting fresh")
            return

        try:
            with open(checkpoint_path, "r") as f:
                checkpoint = json.load(f)

            for step_id, state in checkpoint.get("node_states", {}).items():
                if step_id in self.dag.nodes:
                    node = self.dag.nodes[step_id]
                    node.status = StepStatus(state["status"])
                    node.retry_count = state.get("retry_count", 0)
                    node.error = state.get("error")
                    node.output_files = state.get("output_files", [])

            self.context.outputs = checkpoint.get("context_outputs", {})

            completed, total = self.dag.get_progress()
            logger.info(
                f"Loaded checkpoint: {completed}/{total} steps completed "
                f"({self.dag.get_progress_percent():.1f}%)"
            )
        except Exception as e:
            logger.error(f"Error loading checkpoint: {e}, starting fresh")

    def _sync_with_database(self) -> None:
        completed_outputs = TaskRepository.get_completed_step_outputs(self.task_id)
        for step_id, outputs in completed_outputs.items():
            if step_id in self.dag.nodes and outputs:
                self.dag.set_step_status(step_id, StepStatus.COMPLETED)
                self.dag.set_step_outputs(step_id, outputs)
                self.context.outputs[step_id] = outputs

    def _get_step_inputs(self, step: PipelineStep) -> List[str]:
        inputs = []
        for input_file in step.inputs:
            found = False
            for dep_id in step.dependencies:
                if dep_id in self.context.outputs:
                    dep_outputs = self.context.outputs[dep_id]
                    for output in dep_outputs:
                        if Path(output).name == input_file or Path(output).name.endswith(input_file):
                            inputs.append(output)
                            found = True
                            break
                    if found:
                        break
            if not found:
                if Path(input_file).is_absolute():
                    inputs.append(input_file)
                else:
                    possible_path = self.work_dir / input_file
                    if possible_path.exists():
                        inputs.append(str(possible_path))
                    else:
                        inputs.append(input_file)
        return inputs

    def _execute_step(self, step: PipelineStep) -> StepResult:
        logger.info(f"Executing step: {step.step_id} ({step.name})")

        TaskRepository.update_step_status(
            self.task_id,
            step.step_id,
            StepStatus.RUNNING,
        )
        self.dag.set_step_status(step.step_id, StepStatus.RUNNING)

        executor = self._executors.get(step.step_id)
        if not executor:
            error_msg = f"No executor found for step {step.step_id}"
            logger.error(error_msg)
            TaskRepository.update_step_status(
                self.task_id,
                step.step_id,
                StepStatus.FAILED,
                error_message=error_msg,
            )
            self.dag.set_step_status(step.step_id, StepStatus.FAILED, error_msg)
            return StepResult(
                success=False,
                step_id=step.step_id,
                error_message=error_msg,
            )

        try:
            inputs = self._get_step_inputs(step)
            params = {
                **step.params,
                "sample_id": self.sample_id,
                "task_id": self.task_id,
            }

            result = executor.execute(step.step_id, params, inputs)

            if result.success:
                self.dag.set_step_status(step.step_id, StepStatus.COMPLETED)
                self.dag.set_step_outputs(step.step_id, result.output_files)
                self.context.outputs[step.step_id] = result.output_files

                TaskRepository.update_step_status(
                    self.task_id,
                    step.step_id,
                    StepStatus.COMPLETED,
                    output_files=result.output_files,
                    metrics=result.metrics,
                    std_out=result.stdout,
                    std_err=result.stderr,
                    duration_seconds=result.duration_seconds,
                )
                self._save_checkpoint()

                progress = self.dag.get_progress_percent()
                TaskRepository.update_progress(self.task_id, step.step_id, progress)

                logger.info(
                    f"Step {step.step_id} completed successfully in "
                    f"{result.duration_seconds:.1f}s"
                )
            else:
                self.dag.set_step_status(step.step_id, StepStatus.FAILED, result.error_message)
                TaskRepository.update_step_status(
                    self.task_id,
                    step.step_id,
                    StepStatus.FAILED,
                    error_message=result.error_message,
                    std_out=result.stdout,
                    std_err=result.stderr,
                    duration_seconds=result.duration_seconds,
                )

            return result

        except Exception as e:
            error_msg = f"Unexpected error executing step {step.step_id}: {str(e)}"
            logger.exception(error_msg)
            self.dag.set_step_status(step.step_id, StepStatus.FAILED, error_msg)
            TaskRepository.update_step_status(
                self.task_id,
                step.step_id,
                StepStatus.FAILED,
                error_message=error_msg,
            )
            return StepResult(
                success=False,
                step_id=step.step_id,
                error_message=error_msg,
            )

    def _execute_parallel_group(self, group_name: str, steps: List[PipelineStep]) -> List[StepResult]:
        logger.info(f"Executing parallel group '{group_name}' with {len(steps)} steps")
        results = []

        with ThreadPoolExecutor(max_workers=self.max_parallel) as executor:
            futures = {
                executor.submit(self._execute_step, step): step for step in steps
            }
            for future in as_completed(futures):
                step = futures[future]
                try:
                    result = future.result()
                    results.append(result)
                except Exception as e:
                    error_msg = f"Parallel step {step.step_id} failed: {str(e)}"
                    logger.error(error_msg)
                    results.append(
                        StepResult(
                            success=False,
                            step_id=step.step_id,
                            error_message=error_msg,
                        )
                    )

        return results

    def run(self) -> bool:
        logger.info(
            f"Starting pipeline execution for task {self.task_id}, "
            f"sample {self.sample_id}"
        )

        TaskRepository.update_status(self.task_id, TaskStatus.RUNNING)

        if self.resume:
            self._sync_with_database()

        parallel_groups = self.dag.get_parallel_groups()
        executed_groups: Set[str] = set()

        while not self.dag.is_complete():
            if self.dag.has_failed():
                failed_steps = self.dag.get_failed_steps()
                error_msg = f"Pipeline failed at steps: {[s.step_id for s in failed_steps]}"
                logger.error(error_msg)
                TaskRepository.update_status(self.task_id, TaskStatus.FAILED, error_msg)
                return False

            ready_steps = self.dag.get_ready_steps()
            if not ready_steps:
                if not self.dag.is_complete():
                    logger.warning("No ready steps but pipeline not complete")
                break

            parallel_ready = {}
            sequential_ready = []

            for step in ready_steps:
                if step.parallel_group and step.parallel_group not in executed_groups:
                    group = step.parallel_group
                    if group not in parallel_ready:
                        parallel_ready[group] = []
                    parallel_ready[group].append(step)
                else:
                    sequential_ready.append(step)

            for group_name, group_steps in parallel_ready.items():
                all_group_steps = [
                    self.dag.nodes[sid].step
                    for sid in parallel_groups.get(group_name, [])
                    if self.dag.nodes[sid].status == StepStatus.PENDING
                ]
                if all_group_steps:
                    results = self._execute_parallel_group(group_name, all_group_steps)
                    if any(not r.success for r in results):
                        error_msg = f"Parallel group '{group_name}' failed"
                        logger.error(error_msg)
                        TaskRepository.update_status(self.task_id, TaskStatus.FAILED, error_msg)
                        return False
                    executed_groups.add(group_name)

            for step in sequential_ready:
                if step.parallel_group and step.parallel_group in executed_groups:
                    continue
                result = self._execute_step(step)
                if not result.success:
                    error_msg = f"Step {step.step_id} failed: {result.error_message}"
                    logger.error(error_msg)
                    TaskRepository.update_status(self.task_id, TaskStatus.FAILED, error_msg)
                    return False

        if self.dag.is_success():
            logger.info(f"Pipeline {self.task_id} completed successfully")
            TaskRepository.update_status(self.task_id, TaskStatus.COMPLETED)
            return True
        else:
            error_msg = "Pipeline did not complete successfully"
            logger.error(error_msg)
            TaskRepository.update_status(self.task_id, TaskStatus.FAILED, error_msg)
            return False

    def get_status(self) -> Dict[str, Any]:
        completed, total = self.dag.get_progress()
        return {
            "task_id": self.task_id,
            "sample_id": self.sample_id,
            "progress_percent": self.dag.get_progress_percent(),
            "completed_steps": completed,
            "total_steps": total,
            "running_steps": [s.step_id for s in self.dag.get_running_steps()],
            "completed_step_ids": [s.step_id for s in self.dag.get_completed_steps()],
            "failed_step_ids": [s.step_id for s in self.dag.get_failed_steps()],
            "is_complete": self.dag.is_complete(),
            "is_success": self.dag.is_success(),
            "outputs": self.context.outputs,
        }

    def reset(self) -> None:
        for node in self.dag.nodes.values():
            node.status = StepStatus.PENDING
            node.retry_count = 0
            node.error = None
            node.output_files = []
        self.context.outputs = {}
        checkpoint_path = self._checkpoint_path()
        if checkpoint_path.exists():
            checkpoint_path.unlink()
        logger.info(f"Pipeline {self.task_id} reset")

    def reset_failed(self) -> int:
        count = self.dag.reset_failed_steps()
        logger.info(f"Reset {count} failed steps for pipeline {self.task_id}")
        return count
