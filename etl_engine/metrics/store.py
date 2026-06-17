from datetime import datetime, timedelta
from typing import Optional

from sqlalchemy import func, select

from etl_engine.metrics.collector import ExecutionLog
from etl_engine.models.task import TaskExecution


class ExecutionLogStore:
    def __init__(self, session_factory) -> None:
        self._session_factory = session_factory

    async def save(self, log: ExecutionLog) -> None:
        async with self._session_factory() as session:
            row = TaskExecution(
                pipeline_id=log.pipeline_id,
                task_name=log.task_name,
                task_type=log.task_type,
                status=log.status,
                started_at=log.started_at,
                finished_at=log.finished_at,
                input_rows=log.input_rows,
                output_rows=log.output_rows,
                memory_peak_mb=log.memory_peak_mb,
                error_message=log.error_message,
                quality_report=log.quality_report_summary,
            )
            session.add(row)
            await session.commit()

    async def get_by_pipeline(
        self, pipeline_id: str, limit: int = 100
    ) -> list[ExecutionLog]:
        async with self._session_factory() as session:
            stmt = (
                select(TaskExecution)
                .where(TaskExecution.pipeline_id == pipeline_id)
                .order_by(TaskExecution.created_at.desc())
                .limit(limit)
            )
            result = await session.execute(stmt)
            rows = result.scalars().all()
            return [self._row_to_log(r) for r in rows]

    async def get_by_execution(self, execution_id: str) -> list[ExecutionLog]:
        async with self._session_factory() as session:
            stmt = (
                select(TaskExecution)
                .where(TaskExecution.pipeline_id == execution_id)
                .order_by(TaskExecution.started_at.asc())
            )
            result = await session.execute(stmt)
            rows = result.scalars().all()
            return [self._row_to_log(r) for r in rows]

    async def get_latest(
        self, pipeline_id: str, task_name: Optional[str] = None
    ) -> Optional[ExecutionLog]:
        async with self._session_factory() as session:
            stmt = (
                select(TaskExecution)
                .where(TaskExecution.pipeline_id == pipeline_id)
                .order_by(TaskExecution.created_at.desc())
                .limit(1)
            )
            if task_name is not None:
                stmt = stmt.where(TaskExecution.task_name == task_name)
            result = await session.execute(stmt)
            row = result.scalar_one_or_none()
            if row is None:
                return None
            return self._row_to_log(row)

    async def get_summary(self, pipeline_id: str, days: int = 7) -> dict:
        async with self._session_factory() as session:
            cutoff = datetime.now() - timedelta(days=days)
            base_filter = (
                TaskExecution.pipeline_id == pipeline_id,
                TaskExecution.created_at >= cutoff,
            )
            total_stmt = select(func.count()).select_from(TaskExecution).where(*base_filter)
            total_result = await session.execute(total_stmt)
            total_runs = total_result.scalar() or 0

            success_stmt = (
                select(func.count())
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.status == "success")
            )
            success_result = await session.execute(success_stmt)
            success_count = success_result.scalar() or 0

            avg_duration_stmt = (
                select(
                    func.avg(
                        func.extract("epoch", TaskExecution.finished_at)
                        - func.extract("epoch", TaskExecution.started_at)
                    )
                )
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.started_at.isnot(None), TaskExecution.finished_at.isnot(None))
            )
            avg_duration_result = await session.execute(avg_duration_stmt)
            avg_duration = avg_duration_result.scalar()

            avg_input_stmt = (
                select(func.avg(TaskExecution.input_rows))
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.input_rows.isnot(None))
            )
            avg_input_result = await session.execute(avg_input_stmt)
            avg_input_rows = avg_input_result.scalar()

            avg_output_stmt = (
                select(func.avg(TaskExecution.output_rows))
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.output_rows.isnot(None))
            )
            avg_output_result = await session.execute(avg_output_stmt)
            avg_output_rows = avg_output_result.scalar()

            quality_total_stmt = (
                select(func.count())
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.quality_report.isnot(None))
            )
            quality_total_result = await session.execute(quality_total_stmt)
            quality_total = quality_total_result.scalar() or 0

            quality_passed_stmt = (
                select(func.count())
                .select_from(TaskExecution)
                .where(*base_filter, TaskExecution.quality_report.isnot(None))
            )
            quality_passed_result = await session.execute(quality_passed_stmt)
            quality_passed_count = quality_passed_result.scalar() or 0

            success_rate = (success_count / total_runs * 100) if total_runs > 0 else 0.0
            quality_pass_rate = (quality_passed_count / quality_total * 100) if quality_total > 0 else 0.0

            return {
                "total_runs": total_runs,
                "success_rate": round(success_rate, 2),
                "avg_duration": round(avg_duration, 3) if avg_duration is not None else None,
                "avg_input_rows": round(avg_input_rows, 2) if avg_input_rows is not None else None,
                "avg_output_rows": round(avg_output_rows, 2) if avg_output_rows is not None else None,
                "quality_pass_rate": round(quality_pass_rate, 2),
            }

    def _row_to_log(self, row: TaskExecution) -> ExecutionLog:
        duration = None
        if row.started_at and row.finished_at:
            duration = (row.finished_at - row.started_at).total_seconds()

        quality_passed = None
        quality_summary = None
        if row.quality_report is not None:
            quality_summary = row.quality_report
            quality_passed = row.quality_report.get("passed")

        return ExecutionLog(
            execution_id=str(row.id),
            pipeline_id=str(row.pipeline_id),
            pipeline_name="",
            task_name=row.task_name,
            task_type=row.task_type,
            status=row.status,
            started_at=row.started_at,
            finished_at=row.finished_at,
            duration_seconds=duration,
            input_rows=row.input_rows,
            output_rows=row.output_rows,
            memory_peak_mb=row.memory_peak_mb,
            quality_passed=quality_passed,
            quality_report_summary=quality_summary,
            error_message=row.error_message,
        )
