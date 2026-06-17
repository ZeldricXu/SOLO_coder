import subprocess
import logging
import os
import json
import time
from typing import List, Dict, Any, Optional, Callable, Tuple
from dataclasses import dataclass, field
from pathlib import Path
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

logger = logging.getLogger(__name__)


class StepExecutionError(Exception):
    def __init__(self, message: str, return_code: int = -1, stdout: str = "", stderr: str = ""):
        super().__init__(message)
        self.return_code = return_code
        self.stdout = stdout
        self.stderr = stderr


@dataclass
class StepResult:
    success: bool
    step_id: str
    output_files: List[str] = field(default_factory=list)
    metrics: Dict[str, Any] = field(default_factory=dict)
    stdout: str = ""
    stderr: str = ""
    duration_seconds: float = 0.0
    error_message: Optional[str] = None


class BaseStepExecutor:
    """Base class for all pipeline step executors."""

    def __init__(self, work_dir: str, temp_dir: Optional[str] = None):
        self.work_dir = Path(work_dir)
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir = Path(temp_dir) if temp_dir else self.work_dir / "tmp"
        self.temp_dir.mkdir(parents=True, exist_ok=True)

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        raise NotImplementedError

    def _run_command(
        self,
        cmd: List[str],
        cwd: Optional[str] = None,
        env: Optional[Dict[str, str]] = None,
        timeout: Optional[int] = None,
    ) -> Tuple[int, str, str]:
        logger.info(f"Running command: {' '.join(cmd)}")

        full_env = os.environ.copy()
        if env:
            full_env.update(env)

        try:
            result = subprocess.run(
                cmd,
                cwd=cwd or str(self.work_dir),
                env=full_env,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            return result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired as e:
            raise StepExecutionError(
                f"Command timed out after {timeout} seconds",
                stdout=e.stdout or "",
                stderr=e.stderr or "",
            )
        except FileNotFoundError as e:
            raise StepExecutionError(f"Command not found: {e}")

    def _check_files_exist(self, files: List[str]) -> bool:
        return all(Path(f).exists() for f in files)

    def _save_metrics(self, step_id: str, metrics: Dict[str, Any]) -> str:
        metrics_file = self.work_dir / f"{step_id}_metrics.json"
        with open(metrics_file, "w") as f:
            json.dump(metrics, f, indent=2)
        return str(metrics_file)


class StepExecutorRegistry:
    """Registry for step executors."""

    _executors: Dict[str, type] = {}

    @classmethod
    def register(cls, step_type: str, executor_class: type) -> None:
        cls._executors[step_type] = executor_class

    @classmethod
    def get_executor(cls, step_type: str) -> Optional[type]:
        return cls._executors.get(step_type)

    @classmethod
    def get_all_types(cls) -> List[str]:
        return list(cls._executors.keys())


def register_executor(step_type: str):
    """Decorator to register a step executor class."""
    def decorator(cls):
        StepExecutorRegistry.register(step_type, cls)
        return cls
    return decorator


class RetryableStepExecutor:
    """Wrapper that adds retry capability to step executors."""

    def __init__(self, executor: BaseStepExecutor, max_retries: int = 3):
        self.executor = executor
        self.max_retries = max_retries

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        last_error = None
        for attempt in range(self.max_retries):
            try:
                logger.info(f"Executing step '{step_id}' (attempt {attempt + 1}/{self.max_retries})")
                result = self.executor.execute(step_id, params, input_files)
                if result.success:
                    return result
                last_error = result.error_message or "Unknown error"
            except StepExecutionError as e:
                last_error = str(e)
                logger.warning(f"Step '{step_id}' attempt {attempt + 1} failed: {e}")
            except Exception as e:
                last_error = str(e)
                logger.warning(f"Step '{step_id}' attempt {attempt + 1} unexpected error: {e}")

            if attempt < self.max_retries - 1:
                wait_time = 2 ** attempt
                logger.info(f"Retrying step '{step_id}' in {wait_time} seconds...")
                time.sleep(wait_time)

        return StepResult(
            success=False,
            step_id=step_id,
            error_message=last_error,
        )
