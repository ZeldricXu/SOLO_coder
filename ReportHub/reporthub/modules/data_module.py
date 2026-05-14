import time
import uuid
import asyncio
from typing import Optional, List, Dict, Any
from datetime import datetime
from sqlalchemy.orm import Session
import pandas as pd

from reporthub.models import ReportTemplate, Report
from reporthub.modules.storage_module import StorageModule
from reporthub.modules.version_module import VersionModule
from reporthub.modules.statistics_module import StatisticsModule
from reporthub.modules.task_queue import TaskQueue, TaskStatus
from reporthub.modules.redis_module import (
    RedisGenerationQueue,
    is_redis_available
)
from reporthub.config.settings import settings


class DataModule:
    def __init__(self, db: Session, storage_module: StorageModule, version_module: VersionModule,
                 statistics_module: StatisticsModule):
        self.db = db
        self.storage_module = storage_module
        self.version_module = version_module
        self.statistics_module = statistics_module

    def _parse_template_fields(self, template: ReportTemplate) -> Dict[str, Any]:
        query_structure = {
            "select_fields": [],
            "aggregations": [],
            "filters": []
        }
        for field in template.fields:
            query_structure["select_fields"].append(field["field_id"])
            if field.get("aggregation"):
                query_structure["aggregations"].append({
                    "field": field["field_id"],
                    "function": field["aggregation"]
                })
        if template.filters:
            query_structure["filters"] = template.filters
        return query_structure

    def _connect_data_source(self, data_source: Dict[str, Any]) -> bool:
        return True

    def _execute_data_query(self, data_source: Dict[str, Any], query_structure: Dict[str, Any],
                            report_params: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        if data_source.get("source_type") == "mysql":
            return self._query_mysql(data_source, query_structure, report_params)
        else:
            return self._generate_sample_data(query_structure, report_params)

    def _generate_sample_data(self, query_structure: Dict[str, Any],
                              report_params: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        sample_data = []
        for i in range(10):
            row = {}
            for field in query_structure["select_fields"]:
                if field == "date":
                    row[field] = f"2026-04-{10 + i:02d}"
                elif field in ["sales", "amount", "quantity", "revenue"]:
                    row[field] = 1000 + i * 500
                elif field in ["product", "category"]:
                    row[field] = f"Product_{i + 1}"
                elif field == "region":
                    row[field] = f"Region_{(i % 4) + 1}"
                else:
                    row[field] = i
            sample_data.append(row)
        return sample_data

    def _query_mysql(self, data_source: Dict[str, Any], query_structure: Dict[str, Any],
                     report_params: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        return self._generate_sample_data(query_structure, report_params)

    def _apply_aggregations(self, data: List[Dict[str, Any]], aggregations: List[Dict[str, Any]]) -> pd.DataFrame:
        if not data:
            return pd.DataFrame()
        df = pd.DataFrame(data)
        if aggregations:
            agg_dict = {}
            for agg in aggregations:
                if agg["function"] == "sum":
                    agg_dict[agg["field"]] = "sum"
                elif agg["function"] == "avg":
                    agg_dict[agg["field"]] = "mean"
                elif agg["function"] == "count":
                    agg_dict[agg["field"]] = "count"
                elif agg["function"] == "max":
                    agg_dict[agg["field"]] = "max"
                elif agg["function"] == "min":
                    agg_dict[agg["field"]] = "min"
            if agg_dict:
                group_by_cols = [f for f in df.columns if f not in agg_dict]
                if group_by_cols:
                    df = df.groupby(group_by_cols, as_index=False).agg(agg_dict)
        return df

    def _apply_filters(self, data: List[Dict[str, Any]], filters: List[Dict[str, Any]],
                       report_params: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        if not data or not filters:
            return data
        result = data
        for f in filters:
            field = f.get("field")
            operator = f.get("operator")
            value = f.get("value")
            if field and operator:
                result = [r for r in result if self._check_filter(r, field, operator, value, report_params)]
        return result

    def _check_filter(self, row: Dict[str, Any], field: str, operator: str, value: Any,
                      report_params: Optional[Dict[str, Any]]) -> bool:
        if field not in row:
            return True
        row_value = row[field]
        if operator == "eq":
            return row_value == value
        elif operator == "ne":
            return row_value != value
        elif operator == "gt":
            return row_value > value
        elif operator == "lt":
            return row_value < value
        elif operator == "gte":
            return row_value >= value
        elif operator == "lte":
            return row_value <= value
        elif operator == "in":
            return row_value in (value if isinstance(value, list) else [value])
        elif operator == "contains":
            return str(value).lower() in str(row_value).lower()
        elif operator == "range":
            if value == "last_month":
                return True
            if isinstance(value, list) and len(value) == 2:
                return value[0] <= row_value <= value[1]
        return True

    def generate_report(self, template: ReportTemplate, report_params: Optional[Dict[str, Any]] = None,
                        generator: Optional[str] = None) -> Report:
        start_time = time.time()
        query_structure = self._parse_template_fields(template)
        if not self._connect_data_source(template.data_source):
            raise Exception("数据源连接失败")
        raw_data = self._execute_data_query(template.data_source, query_structure, report_params)
        filtered_data = self._apply_filters(raw_data, query_structure["filters"], report_params)
        aggregated_df = self._apply_aggregations(filtered_data, query_structure["aggregations"])
        report_data = {
            "columns": list(aggregated_df.columns),
            "rows": aggregated_df.to_dict(orient="records"),
            "summary": self._calculate_summary(aggregated_df, query_structure["aggregations"]),
            "query_structure": query_structure,
            "generated_at": datetime.utcnow().isoformat()
        }
        report_id = f"report_{uuid.uuid4().hex[:12]}"
        report_name = report_params.get("report_name", f"{template.template_name}_{datetime.utcnow().strftime('%Y%m%d')}") if report_params else f"{template.template_name}_{datetime.utcnow().strftime('%Y%m%d')}"
        default_format = "xlsx"
        report = Report(
            report_id=report_id,
            template_id=template.template_id,
            report_name=report_name,
            report_data=report_data,
            report_file=None,
            report_format=default_format,
            generated_at=datetime.utcnow(),
            generator=generator,
            status="completed",
            report_params=report_params
        )
        self.db.add(report)
        self.db.commit()
        self.db.refresh(report)
        self.version_module.create_version(report, "v1", "初始版本")
        elapsed_time = int((time.time() - start_time) * 1000)
        total_rows = len(report_data["rows"])
        self.statistics_module.update_generate_stats(template.template_id, elapsed_time, total_rows)
        return report

    def _calculate_summary(self, df: pd.DataFrame, aggregations: List[Dict[str, Any]]) -> Dict[str, Any]:
        summary = {}
        if df.empty:
            return summary
        for agg in aggregations:
            field = agg["field"]
            if field in df.columns:
                summary[f"{field}_total"] = float(df[field].sum())
                summary[f"{field}_avg"] = float(df[field].mean())
                summary[f"{field}_max"] = float(df[field].max())
                summary[f"{field}_min"] = float(df[field].min())
        summary["total_rows"] = len(df)
        return summary


class AsyncDataModule:
    def __init__(self, db: Session, storage_module: StorageModule, version_module: VersionModule,
                 statistics_module: StatisticsModule, task_queue: Optional[TaskQueue] = None,
                 use_redis: bool = None):
        self.db = db
        self.storage_module = storage_module
        self.version_module = version_module
        self.statistics_module = statistics_module
        if use_redis is None:
            use_redis = is_redis_available()
        self.use_redis = use_redis
        if use_redis:
            self.redis_queue = RedisGenerationQueue()
            self.task_queue = None
        else:
            self.redis_queue = None
            self.task_queue = task_queue or TaskQueue(max_workers=5)
        self._register_handlers()

    def _register_handlers(self):
        if self.use_redis:
            return
        async def generate_handler(payload):
            template_id = payload["template_id"]
            report_params = payload.get("report_params", {})
            generator = payload.get("generator")
            from reporthub.modules.template_module import TemplateModule
            template_module = TemplateModule(self.db)
            template = template_module.get_template(template_id)
            if not template:
                raise Exception(f"Template not found: {template_id}")
            data_module = DataModule(
                self.db,
                self.storage_module,
                self.version_module,
                self.statistics_module
            )
            report = data_module.generate_report(template, report_params, generator)
            return {
                "report_id": report.report_id,
                "status": report.status,
                "report_name": report.report_name
            }
        self.task_queue.register_handler("generate_report", generate_handler)

    async def generate_report_async(self, template_id: str,
                                    report_params: Optional[Dict[str, Any]] = None,
                                    generator: Optional[str] = None,
                                    max_retries: int = 3,
                                    priority: bool = False) -> str:
        if self.use_redis:
            return self.redis_queue.submit_task(
                template_id=template_id,
                report_params=report_params,
                generator=generator,
                max_retries=max_retries,
                priority=priority
            )
        payload = {
            "template_id": template_id,
            "report_params": report_params or {},
            "generator": generator
        }
        return await self.task_queue.submit_task(
            task_type="generate_report",
            payload=payload,
            max_retries=max_retries
        )

    def get_task_status(self, task_id: str):
        if self.use_redis:
            task_data = self.redis_queue.get_task_status(task_id)
            if not task_data:
                return None
            class RedisTaskStatus:
                def __init__(self, data: Dict[str, Any]):
                    self.task_id = data.get("task_id")
                    self.status = TaskStatus(data.get("status", "pending"))
                    self.progress = 0.0
                    self.result = data.get("result")
                    self.error = data.get("error")
                    self.retry_count = data.get("retry_count", 0)
                    self.duration_ms = 0.0
                    self.message = data.get("message", "")
                    self.created_at = data.get("created_at")
                    self.started_at = data.get("started_at")
                    self.completed_at = data.get("completed_at")
            return RedisTaskStatus(task_data)
        return self.task_queue.get_task_status(task_id)

    def get_task_progress(self, task_id: str) -> Dict[str, Any]:
        if self.use_redis:
            task = self.get_task_status(task_id)
            if not task:
                return {"exists": False}
            return {
                "exists": True,
                "task_id": task.task_id,
                "status": task.status.value,
                "progress": task.progress,
                "message": task.message,
                "retry_count": task.retry_count,
                "created_at": task.created_at,
                "started_at": task.started_at,
                "completed_at": task.completed_at,
                "duration_ms": task.duration_ms
            }
        return self.task_queue.get_task_progress(task_id)

    def get_queue_stats(self) -> Dict[str, Any]:
        if self.use_redis:
            return {
                "pending": self.redis_queue.get_queue_size(),
                "running": 0,
                "retrying": 0,
                "failed": 0,
                "completed": 0,
                "max_workers": 0,
                "active_workers": 0,
                "using_redis": True
            }
        return self.task_queue.get_queue_stats()

    def cancel_task(self, task_id: str) -> bool:
        if self.use_redis:
            return self.redis_queue.task_store.update_task_status(task_id, "cancelled")
        return self.task_queue.cancel_task(task_id)

    async def wait_for_task(self, task_id: str, timeout: int = 300) -> Optional[Dict[str, Any]]:
        import asyncio
        start_time = time.time()
        while time.time() - start_time < timeout:
            status = self.get_task_status(task_id)
            if status and status.status in [TaskStatus.COMPLETED, TaskStatus.FAILED]:
                return {
                    "status": status.status.value,
                    "result": status.result,
                    "error": status.error,
                    "retry_count": status.retry_count,
                    "duration_ms": status.duration_ms
                }
            await asyncio.sleep(0.5)
        raise TimeoutError(f"Task {task_id} timed out after {timeout} seconds")

    def get_queue_size(self) -> int:
        if self.use_redis:
            return self.redis_queue.get_queue_size()
        stats = self.task_queue.get_queue_stats()
        return stats.get("pending", 0)


class ReportGenerationWorker:
    def __init__(self, db: Session, storage_module: StorageModule, version_module: VersionModule,
                 statistics_module: StatisticsModule):
        self.db = db
        self.storage_module = storage_module
        self.version_module = version_module
        self.statistics_module = statistics_module
        self.redis_queue = RedisGenerationQueue()
        self.running = False

    def start(self, max_tasks: Optional[int] = None):
        self.running = True
        processed = 0
        while self.running:
            if max_tasks and processed >= max_tasks:
                break
            task = self.redis_queue.get_next_task(timeout=1)
            if not task:
                continue
            try:
                result = self._process_task(task)
                self.redis_queue.complete_task(task["task_id"], result)
                processed += 1
            except Exception as e:
                self.redis_queue.fail_task(task["task_id"], str(e))
                processed += 1

    def stop(self):
        self.running = False

    def _process_task(self, task: Dict[str, Any]) -> Dict[str, Any]:
        template_id = task["template_id"]
        report_params = task.get("report_params", {})
        generator = task.get("generator")
        from reporthub.modules.template_module import TemplateModule
        template_module = TemplateModule(self.db)
        template = template_module.get_template(template_id)
        if not template:
            raise Exception(f"Template not found: {template_id}")
        data_module = DataModule(
            self.db,
            self.storage_module,
            self.version_module,
            self.statistics_module
        )
        report = data_module.generate_report(template, report_params, generator)
        return {
            "report_id": report.report_id,
            "status": report.status,
            "report_name": report.report_name
        }
