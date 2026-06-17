import uuid
from datetime import datetime
from typing import Optional

import psutil
from pydantic import BaseModel


class ExecutionLog(BaseModel):
    execution_id: str
    pipeline_id: str
    pipeline_name: str
    task_name: str
    task_type: str
    status: str
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    duration_seconds: Optional[float] = None
    input_rows: Optional[int] = None
    output_rows: Optional[int] = None
    memory_peak_mb: Optional[float] = None
    quality_passed: Optional[bool] = None
    quality_report_summary: Optional[dict] = None
    error_message: Optional[str] = None


class MetricsCollector:
    def __init__(self) -> None:
        self._active_runs: dict[str, dict] = {}

    def start_task(
        self,
        execution_id: str,
        pipeline_id: str,
        pipeline_name: str,
        task_name: str,
        task_type: str,
    ) -> str:
        run_id = uuid.uuid4().hex
        self._active_runs[run_id] = {
            "execution_id": execution_id,
            "pipeline_id": pipeline_id,
            "pipeline_name": pipeline_name,
            "task_name": task_name,
            "task_type": task_type,
            "started_at": datetime.now(),
            "memory_samples": [],
        }
        self._active_runs[run_id]["memory_samples"].append(self._track_memory())
        return run_id

    def finish_task(
        self,
        run_id: str,
        status: str,
        input_rows: Optional[int] = None,
        output_rows: Optional[int] = None,
        quality_passed: Optional[bool] = None,
        quality_report: Optional[dict] = None,
        error: Optional[str] = None,
    ) -> ExecutionLog:
        run_data = self._active_runs.pop(run_id)
        finished_at = datetime.now()
        started_at = run_data["started_at"]
        duration = (finished_at - started_at).total_seconds()

        run_data["memory_samples"].append(self._track_memory())
        memory_peak = max(run_data["memory_samples"])

        log = ExecutionLog(
            execution_id=run_data["execution_id"],
            pipeline_id=run_data["pipeline_id"],
            pipeline_name=run_data["pipeline_name"],
            task_name=run_data["task_name"],
            task_type=run_data["task_type"],
            status=status,
            started_at=started_at,
            finished_at=finished_at,
            duration_seconds=duration,
            input_rows=input_rows,
            output_rows=output_rows,
            memory_peak_mb=memory_peak,
            quality_passed=quality_passed,
            quality_report_summary=quality_report,
            error_message=error,
        )
        return log

    def _track_memory(self) -> float:
        return psutil.Process().memory_info().rss / 1024 / 1024
