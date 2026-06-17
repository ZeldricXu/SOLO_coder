import logging
import threading
import time
from typing import List, Dict, Any, Optional, Callable
from dataclasses import dataclass, field
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import json

from pipeline.dag import PipelineDAG, DAGNode
from pipeline.executor import (
    BaseStepExecutor, StepExecutorRegistry, StepResult,
    RetryableStepExecutor, StepExecutionError
)
from config.pipeline_config import PipelineStep, StepStatus
from config.settings import settings

logger = logging.getLogger(__name__)


@dataclass
class PipelineContext:
    """Context passed to step executors during pipeline execution."""
    sample_id: str
    work_dir: Path
    temp_dir: Path
    log_dir: Path
    fastq_r1: str = ""
    fastq_r2: str = ""
    reference_genome: str = ""
    input_files_map: Dict[str, str] = field(default_factory=dict)
    output_files_map: Dict[str, List[str]] = field(default_factory=dict)
    params: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def resolve_path(self, filename: str) -> str:
        if filename in self.input_files_map:
            return self.input_files_map[filename]
        resolved = self.work_dir / filename
        if resolved.exists():
            return str(resolved)
        return str(resolved)

    def get_input_paths(self, inputs: List[str]) -> List[str]:
        return [self.resolve_path(f) for f in inputs]


@dataclass
class PipelineResult:
    """Result of pipeline execution."""
    success: bool
    sample_id: str
    output_files: List[str] = field(default_factory=list)
    summary: Dict[str, Any] = field(default_factory=dict)
    duration_seconds: float = 0.0
    error_message: Optional[str] = None
    step_results: Dict[str, StepResult] = field(default_factory=dict)


@dataclass
class PipelineExecutionState:
    """Mutable state tracking pipeline execution."""
    is_running: bool = False
    is_paused: bool = False
    is_cancelled: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)


class PipelineRunner:
    """
    Main pipeline orchestration engine.

    Supports:
    - DAG-based step scheduling
    - Checkpoint/resume (skip completed steps)
    - Parallel step execution
    - Automatic retry on failure
    - Progress tracking and callbacks
    """

    def __init__(
        self,
        dag: Optional[PipelineDAG] = None,
        context: Optional[PipelineContext] = None,
        steps: Optional[List[PipelineStep]] = None,
        output_dir: Optional[str] = None,
        max_parallel: int = 4,
        resume: bool = True,
        progress_callback: Optional[Callable] = None,
        step_callback: Optional[Callable] = None,
    ):
        if dag is not None and context is not None:
            self.dag = dag
            self.context = context
        elif steps is not None and output_dir is not None:
            self.dag = PipelineDAG(steps)
            work_path = Path(output_dir)
            work_path.mkdir(parents=True, exist_ok=True)
            self.context = PipelineContext(
                sample_id=steps[0].step_id.split("_")[0] if steps else "unknown",
                work_dir=work_path,
                temp_dir=work_path / "tmp",
                log_dir=work_path / "logs",
            )
            self.context.temp_dir.mkdir(parents=True, exist_ok=True)
            self.context.log_dir.mkdir(parents=True, exist_ok=True)
        else:
            raise ValueError("Must provide either (dag, context) or (steps, output_dir)")

        self.max_parallel = max_parallel
        self.resume = resume
        self.state = PipelineExecutionState()
        self._checkpoint_file = self.context.work_dir / "pipeline_checkpoint.json"
        self._progress_callback = progress_callback
        self._step_callback = step_callback
        self._step_results: Dict[str, StepResult] = {}
        self._start_time = 0.0

    def pause(self) -> None:
        with self.state.lock:
            self.state.is_paused = True
            logger.info("Pipeline paused")

    def resume_pipeline(self) -> None:
        with self.state.lock:
            self.state.is_paused = False
            logger.info("Pipeline resumed")

    def cancel(self) -> None:
        with self.state.lock:
            self.state.is_cancelled = True
            logger.info("Pipeline cancelled")

    def run(self, context: Optional[PipelineContext] = None) -> PipelineResult:
        """
        Execute the pipeline.

        Args:
            context: Optional override context (for backward compatibility)

        Returns:
            PipelineResult with execution details
        """
        if context is not None:
            self.context = context

        self._start_time = time.time()

        with self.state.lock:
            self.state.is_running = True
            self.state.is_cancelled = False
            self.state.is_paused = False

        if self.resume:
            self._load_checkpoint()
            self._skip_completed_steps()

        logger.info(f"Starting pipeline execution for sample {self.context.sample_id}")
        logger.info(f"Total steps: {len(self.dag.nodes)}")

        try:
            while not self.dag.is_complete() and not self.state.is_cancelled:
                if self.state.is_paused:
                    time.sleep(1)
                    continue

                ready_steps = self.dag.get_ready_steps()
                running_steps = self.dag.get_running_steps()

                if not ready_steps and not running_steps:
                    if self.dag.has_failed():
                        logger.error("Pipeline has failed steps and no more ready steps")
                        break
                    logger.warning("No ready steps but pipeline not complete")
                    break

                available_slots = self.max_parallel - len(running_steps)
                steps_to_run = ready_steps[:available_slots]

                if steps_to_run:
                    self._run_steps_parallel(steps_to_run)

                self._save_checkpoint()
                self._report_progress()

                time.sleep(0.5)

        except Exception as e:
            logger.error(f"Pipeline execution error: {e}", exc_info=True)
            return PipelineResult(
                success=False,
                sample_id=self.context.sample_id,
                error_message=str(e),
                duration_seconds=time.time() - self._start_time,
                step_results=self._step_results,
            )
        finally:
            with self.state.lock:
                self.state.is_running = False

        success = self.dag.is_success()
        duration = time.time() - self._start_time

        all_output_files = []
        for node in self.dag.nodes.values():
            all_output_files.extend(node.output_files)

        summary = self._build_summary()

        if not success:
            failed_steps = self.dag.get_failed_steps()
            error_msgs = []
            for step_id, node in self.dag.nodes.items():
                if node.status == StepStatus.FAILED and node.error:
                    error_msgs.append(f"{step_id}: {node.error}")
            error_message = "; ".join(error_msgs) if error_msgs else "Pipeline failed"
        else:
            error_message = None

        logger.info(f"Pipeline {'completed successfully' if success else 'failed'} in {duration:.1f}s")

        return PipelineResult(
            success=success,
            sample_id=self.context.sample_id,
            output_files=all_output_files,
            summary=summary,
            duration_seconds=duration,
            error_message=error_message,
            step_results=self._step_results,
        )

    def _run_steps_parallel(self, steps: List[PipelineStep]) -> None:
        with ThreadPoolExecutor(max_workers=len(steps)) as executor:
            futures = {}
            for step in steps:
                self.dag.set_step_status(step.step_id, StepStatus.RUNNING)
                if self._step_callback:
                    self._step_callback(step.step_id, StepStatus.RUNNING, None)

                future = executor.submit(self._execute_single_step, step)
                futures[future] = step

            for future in as_completed(futures):
                step = futures[future]
                try:
                    result = future.result()
                    self._handle_step_result(step, result)
                except Exception as e:
                    logger.error(f"Unexpected error in step {step.step_id}: {e}")
                    result = StepResult(
                        success=False,
                        step_id=step.step_id,
                        error_message=str(e),
                    )
                    self._handle_step_result(step, result)

    def _execute_single_step(self, step: PipelineStep) -> StepResult:
        logger.info(f"Executing step: {step.name} ({step.step_id})")

        executor_class = StepExecutorRegistry.get_executor(step.step_type)
        if not executor_class:
            return StepResult(
                success=False,
                step_id=step.step_id,
                error_message=f"No executor registered for step type: {step.step_type}",
            )

        base_executor = executor_class(
            work_dir=str(self.context.work_dir),
            temp_dir=str(self.context.temp_dir),
        )

        retryable_executor = RetryableStepExecutor(
            executor=base_executor,
            max_retries=step.max_retries,
        )

        input_paths = self.context.get_input_paths(step.inputs)

        params = {}
        params.update(step.params)
        params.update(self.context.params)
        params["sample_id"] = self.context.sample_id
        params["step_id"] = step.step_id
        params["fastq_r1"] = self.context.fastq_r1
        params["fastq_r2"] = self.context.fastq_r2
        params["reference_genome"] = self.context.reference_genome

        start_time = time.time()
        result = retryable_executor.execute(step.step_id, params, input_paths)
        result.duration_seconds = time.time() - start_time

        return result

    def _handle_step_result(self, step: PipelineStep, result: StepResult) -> None:
        dag_node = self.dag.get_step(step.step_id)
        if not dag_node:
            return

        self._step_results[step.step_id] = result

        if result.success:
            self.dag.set_step_status(step.step_id, StepStatus.COMPLETED)
            self.dag.set_step_outputs(step.step_id, result.output_files)

            for output_file in result.output_files:
                self.context.output_files_map[output_file] = result.output_files
                self.context.input_files_map[Path(output_file).name] = output_file

            logger.info(f"Step {step.step_id} completed in {result.duration_seconds:.1f}s")
        else:
            dag_node.retry_count += 1
            if dag_node.retry_count < step.max_retries:
                self.dag.set_step_status(step.step_id, StepStatus.PENDING)
                logger.warning(
                    f"Step {step.step_id} failed (attempt {dag_node.retry_count}/{step.max_retries}), "
                    f"will retry: {result.error_message}"
                )
            else:
                self.dag.set_step_status(step.step_id, StepStatus.FAILED, result.error_message)
                logger.error(
                    f"Step {step.step_id} failed after {dag_node.retry_count} attempts: {result.error_message}"
                )

        if self._step_callback:
            self._step_callback(step.step_id, dag_node.status, result)

        if self._progress_callback:
            progress = self.dag.get_progress_percent() / 100.0
            self._progress_callback(
                step.step_id, step.name,
                "completed" if result.success else "failed",
                progress,
                result.error_message or "",
            )

        self._save_checkpoint()

    def _skip_completed_steps(self) -> None:
        skipped_count = 0
        for step_id, node in self.dag.nodes.items():
            if node.status == StepStatus.PENDING:
                output_paths = [self.context.resolve_path(f) for f in node.step.outputs]
                if all(Path(p).exists() for p in output_paths):
                    self.dag.set_step_status(step_id, StepStatus.SKIPPED)
                    self.dag.set_step_outputs(step_id, output_paths)
                    for output_file in output_paths:
                        self.context.input_files_map[Path(output_file).name] = output_file
                    skipped_count += 1
                    logger.info(f"Skipping completed step: {step_id}")

        if skipped_count > 0:
            logger.info(f"Skipped {skipped_count} already completed steps")

    def _save_checkpoint(self) -> None:
        checkpoint = {
            "sample_id": self.context.sample_id,
            "timestamp": time.time(),
            "steps": {},
            "output_files": self.context.output_files_map,
        }

        for step_id, node in self.dag.nodes.items():
            checkpoint["steps"][step_id] = {
                "status": node.status.value,
                "retry_count": node.retry_count,
                "error": node.error,
                "output_files": node.output_files,
            }

        try:
            with open(self._checkpoint_file, "w") as f:
                json.dump(checkpoint, f, indent=2)
        except Exception as e:
            logger.warning(f"Failed to save checkpoint: {e}")

    def _load_checkpoint(self) -> None:
        if not self._checkpoint_file.exists():
            return

        try:
            with open(self._checkpoint_file, "r") as f:
                checkpoint = json.load(f)

            for step_id, step_data in checkpoint.get("steps", {}).items():
                if step_id in self.dag.nodes:
                    status = StepStatus(step_data["status"])
                    self.dag.set_step_status(step_id, status, step_data.get("error"))
                    self.dag.nodes[step_id].retry_count = step_data.get("retry_count", 0)
                    self.dag.set_step_outputs(step_id, step_data.get("output_files", []))

            self.context.output_files_map.update(checkpoint.get("output_files", {}))
            for output_files in self.context.output_files_map.values():
                if isinstance(output_files, list):
                    for f in output_files:
                        self.context.input_files_map[Path(f).name] = f

            logger.info("Loaded pipeline checkpoint")
        except Exception as e:
            logger.warning(f"Failed to load checkpoint: {e}")

    def _report_progress(self) -> None:
        if not self._progress_callback:
            return

        completed, total = self.dag.get_progress()
        percent = self.dag.get_progress_percent()

        self._progress_callback(
            "", "",
            "progress",
            percent / 100.0,
            f"{completed}/{total} steps completed",
        )

    def _build_summary(self) -> Dict[str, Any]:
        completed, total = self.dag.get_progress()
        return {
            "sample_id": self.context.sample_id,
            "total_steps": total,
            "completed_steps": completed,
            "failed_steps": len(self.dag.get_failed_steps()),
            "progress_percent": self.dag.get_progress_percent(),
            "is_success": self.dag.is_success(),
            "step_statuses": {
                step_id: node.status.value
                for step_id, node in self.dag.nodes.items()
            },
            "output_files_count": sum(len(n.output_files) for n in self.dag.nodes.values()),
        }

    def get_summary(self) -> Dict[str, Any]:
        return self._build_summary()
