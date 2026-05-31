"""Metadata crawler module for schema extraction, statistics, and sample data."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, AsyncGenerator, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus, SchemaInfo
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from .schema_extractor import SchemaExtractor, ExtractionResult
from .statistics_collector import StatisticsCollector, TableStatistics


@dataclass
class CrawlTask:
    id: UUID = field(default_factory=uuid4)
    source: str
    source_type: str
    table_name: Optional[str] = None
    options: Dict[str, Any] = field(default_factory=dict)
    status: str = "pending"
    created_at: datetime = field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    schema: Optional[SchemaInfo] = None
    statistics: Optional[TableStatistics] = None
    error_message: Optional[str] = None


class MetadataCrawler:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._schema_extractor = SchemaExtractor()
        self._statistics_collector = StatisticsCollector()
        self._tasks: Dict[UUID, CrawlTask] = {}
        self._logger = LogManager().get_logger(__name__)

    @property
    def schema_extractor(self) -> SchemaExtractor:
        return self._schema_extractor

    @property
    def statistics_collector(self) -> StatisticsCollector:
        return self._statistics_collector

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "crawl.file":
                crawl_result = await self._handle_crawl_file(payload)
                result.results = [crawl_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "File crawl completed successfully"

            elif event_type == "crawl.directory":
                crawl_result = await self._handle_crawl_directory(payload)
                result.results = [crawl_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Directory crawl completed successfully"

            elif event_type == "crawl.data":
                crawl_result = await self._handle_crawl_data(payload)
                result.results = [crawl_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Data crawl completed successfully"

            elif event_type == "crawl.status":
                status_result = self._handle_get_status(payload)
                result.results = [status_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Crawl status retrieved"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Metadata crawl event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Metadata crawl event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    async def _handle_crawl_file(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        file_path = payload.get("file_path")
        table_name = payload.get("table_name")
        options = payload.get("options", {})

        if not file_path:
            raise ValidationError(
                message="File path is required",
                suggestion="Provide file_path in the payload.",
            )

        if not os.path.exists(file_path):
            raise ValidationError(
                message=f"File not found: {file_path}",
                suggestion="Check that the file path is correct.",
            )

        source_type = self._detect_source_type(file_path)
        task = self._create_task(file_path, source_type, table_name, options)

        return await self._execute_crawl_task(task)

    async def _handle_crawl_directory(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        directory_path = payload.get("directory_path")
        recursive = payload.get("recursive", True)
        file_patterns = payload.get("file_patterns", ["*.csv", "*.json"])
        options = payload.get("options", {})

        if not directory_path:
            raise ValidationError(
                message="Directory path is required",
                suggestion="Provide directory_path in the payload.",
            )

        if not os.path.exists(directory_path) or not os.path.isdir(directory_path):
            raise ValidationError(
                message=f"Directory not found: {directory_path}",
                suggestion="Check that the directory path is correct.",
            )

        files = self._find_matching_files(directory_path, recursive, file_patterns)
        results: List[Dict[str, Any]] = []

        for file_path in files:
            try:
                source_type = self._detect_source_type(file_path)
                table_name = os.path.splitext(os.path.basename(file_path))[0]
                task = self._create_task(file_path, source_type, table_name, options)
                crawl_result = await self._execute_crawl_task(task)
                results.append(crawl_result)
            except Exception as e:
                results.append({
                    "file_path": file_path,
                    "error": str(e),
                })

        return {
            "directory_path": directory_path,
            "files_found": len(files),
            "files_processed": len(results),
            "results": results,
        }

    async def _handle_crawl_data(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        data = payload.get("data")
        table_name = payload.get("table_name")
        source = payload.get("source", "inline")
        options = payload.get("options", {})

        if not data:
            raise ValidationError(
                message="Data is required",
                suggestion="Provide data in the payload.",
            )

        if not table_name:
            raise ValidationError(
                message="Table name is required",
                suggestion="Provide table_name in the payload.",
            )

        task = self._create_task(source, "dict", table_name, options)
        task.options["data"] = data

        return await self._execute_crawl_task(task)

    def _handle_get_status(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")

        if task_id:
            task = self._tasks.get(UUID(task_id))
            if not task:
                raise ValidationError(
                    message=f"Task not found: {task_id}",
                    suggestion="Check that the task ID is correct.",
                )
            return self._task_to_dict(task)

        return {
            "total_tasks": len(self._tasks),
            "tasks": [self._task_to_dict(t) for t in self._tasks.values()],
        }

    def _create_task(
        self,
        source: str,
        source_type: str,
        table_name: Optional[str],
        options: Dict[str, Any],
    ) -> CrawlTask:
        task = CrawlTask(
            source=source,
            source_type=source_type,
            table_name=table_name,
            options=options,
        )
        self._tasks[task.id] = task
        return task

    async def _execute_crawl_task(self, task: CrawlTask) -> Dict[str, Any]:
        task.status = "running"
        self._logger.info(f"Starting crawl task: {task.source}")

        try:
            if task.source_type == "csv":
                extraction_result = self._schema_extractor.extract_from_csv(
                    task.source,
                    table_name=task.table_name,
                    delimiter=task.options.get("delimiter", ","),
                    has_header=task.options.get("has_header", True),
                    encoding=task.options.get("encoding", "utf-8"),
                )
                if extraction_result.schema:
                    stats = self._statistics_collector.collect_from_csv(
                        task.source,
                        schema=extraction_result.schema,
                        delimiter=task.options.get("delimiter", ","),
                        has_header=task.options.get("has_header", True),
                        encoding=task.options.get("encoding", "utf-8"),
                    )
                    task.statistics = stats

            elif task.source_type == "json":
                extraction_result = self._schema_extractor.extract_from_json(
                    task.source,
                    table_name=task.table_name,
                    encoding=task.options.get("encoding", "utf-8"),
                )
                if extraction_result.schema:
                    stats = self._statistics_collector.collect_from_json(
                        task.source,
                        schema=extraction_result.schema,
                        encoding=task.options.get("encoding", "utf-8"),
                    )
                    task.statistics = stats

            elif task.source_type == "dict":
                data = task.options.get("data", [])
                extraction_result = self._schema_extractor.extract_from_dict(
                    data,
                    table_name=task.table_name or "unknown",
                    source=task.source,
                )
                if extraction_result.schema:
                    stats = self._statistics_collector.collect_from_dict(
                        data,
                        table_name=task.table_name or "unknown",
                        schema=extraction_result.schema,
                    )
                    task.statistics = stats

            else:
                raise ValidationError(
                    message=f"Unsupported source type: {task.source_type}",
                    suggestion="Supported types: csv, json",
                )

            if extraction_result.errors:
                raise ValidationError(
                    message=f"Schema extraction failed: {', '.join(extraction_result.errors)}",
                    suggestion="Check the source file format and content.",
                )

            task.schema = extraction_result.schema
            task.status = "completed"
            task.completed_at = datetime.utcnow()

            self._logger.info(
                f"Crawl task completed: {task.source}",
                table_name=task.table_name,
                fields_count=len(task.schema.fields) if task.schema else 0,
            )

            return self._crawl_result_to_dict(task)

        except Exception as e:
            task.status = "failed"
            task.completed_at = datetime.utcnow()
            task.error_message = str(e)

            self._logger.error(f"Crawl task failed: {task.source}", error=str(e))
            raise

    async def crawl_file(
        self,
        file_path: str,
        table_name: Optional[str] = None,
        **options: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="crawl.file",
            payload={
                "file_path": file_path,
                "table_name": table_name,
                "options": options,
            },
            source="metadata_crawler",
        )
        result = await self.process_event(event)
        return result.results[0] if result.results else {}

    async def crawl_directory(
        self,
        directory_path: str,
        recursive: bool = True,
        file_patterns: Optional[List[str]] = None,
        **options: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="crawl.directory",
            payload={
                "directory_path": directory_path,
                "recursive": recursive,
                "file_patterns": file_patterns or ["*.csv", "*.json"],
                "options": options,
            },
            source="metadata_crawler",
        )
        result = await self.process_event(event)
        return result.results[0] if result.results else {}

    async def crawl_data(
        self,
        data: List[Dict[str, Any]],
        table_name: str,
        source: str = "inline",
        **options: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="crawl.data",
            payload={
                "data": data,
                "table_name": table_name,
                "source": source,
                "options": options,
            },
            source="metadata_crawler",
        )
        result = await self.process_event(event)
        return result.results[0] if result.results else {}

    def get_crawl_result(self, task_id: str) -> Dict[str, Any]:
        return self._handle_get_status({"task_id": task_id})

    def list_tasks(self, status: Optional[str] = None) -> List[Dict[str, Any]]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return [self._task_to_dict(t) for t in tasks]

    def get_data_quality_report(self, task_id: str) -> Dict[str, Any]:
        task = self._tasks.get(UUID(task_id))
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        if not task.statistics:
            raise ValidationError(
                message="No statistics available for this task",
                suggestion="Ensure the crawl task completed successfully.",
            )

        return self._statistics_collector.get_data_quality_report(task.statistics)

    def export_metadata(self, task_id: str, format: str = "json") -> str:
        task = self._tasks.get(UUID(task_id))
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        if not task.schema or not task.statistics:
            raise ValidationError(
                message="No metadata available for this task",
                suggestion="Ensure the crawl task completed successfully.",
            )

        metadata = {
            "schema": self._schema_extractor.export_schema(task.schema),
            "statistics": self._statistics_collector.export_statistics(task.statistics, format="json"),
        }

        if format == "json":
            import json
            return json.dumps(metadata, indent=2, ensure_ascii=False)
        else:
            raise ValidationError(
                message=f"Unsupported format: {format}",
                suggestion="Use 'json' format.",
            )

    async def batch_crawl(
        self,
        sources: List[Dict[str, Any]],
        stop_on_error: bool = False,
    ) -> AsyncGenerator[Dict[str, Any], None]:
        for source_config in sources:
            try:
                if "file_path" in source_config:
                    result = await self.crawl_file(**source_config)
                elif "directory_path" in source_config:
                    result = await self.crawl_directory(**source_config)
                elif "data" in source_config:
                    result = await self.crawl_data(**source_config)
                else:
                    raise ValidationError(
                        message="Invalid source configuration",
                        suggestion="Provide file_path, directory_path, or data.",
                    )

                yield {
                    "source": source_config,
                    "status": "success",
                    "result": result,
                }

            except Exception as e:
                yield {
                    "source": source_config,
                    "status": "failed",
                    "error": str(e),
                }
                if stop_on_error:
                    break

    def _detect_source_type(self, file_path: str) -> str:
        ext = os.path.splitext(file_path)[1].lower()
        if ext == ".csv":
            return "csv"
        elif ext == ".json":
            return "json"
        else:
            raise ValidationError(
                message=f"Unsupported file type: {ext}",
                suggestion="Supported types: .csv, .json",
            )

    def _find_matching_files(
        self,
        directory_path: str,
        recursive: bool,
        file_patterns: List[str],
    ) -> List[str]:
        import fnmatch

        matching_files: List[str] = []

        for root, _, files in os.walk(directory_path):
            for file in files:
                for pattern in file_patterns:
                    if fnmatch.fnmatch(file, pattern):
                        matching_files.append(os.path.join(root, file))
                        break
            if not recursive:
                break

        return matching_files

    def _task_to_dict(self, task: CrawlTask) -> Dict[str, Any]:
        return {
            "task_id": str(task.id),
            "source": task.source,
            "source_type": task.source_type,
            "table_name": task.table_name,
            "status": task.status,
            "created_at": task.created_at.isoformat(),
            "completed_at": task.completed_at.isoformat() if task.completed_at else None,
            "error_message": task.error_message,
            "has_schema": task.schema is not None,
            "has_statistics": task.statistics is not None,
        }

    def _crawl_result_to_dict(self, task: CrawlTask) -> Dict[str, Any]:
        result = self._task_to_dict(task)

        if task.schema:
            result["schema"] = {
                "table_name": task.schema.table_name,
                "version": task.schema.version,
                "fields": [
                    {
                        "name": f.name,
                        "data_type": f.data_type,
                        "nullable": f.nullable,
                        "description": f.description,
                    }
                    for f in task.schema.fields
                ],
                "row_count": task.schema.row_count,
                "size_bytes": task.schema.size_bytes,
            }

        if task.statistics:
            quality_report = self._statistics_collector.get_data_quality_report(task.statistics)
            result["statistics"] = {
                "row_count": task.statistics.row_count,
                "col_count": task.statistics.col_count,
                "size_bytes": task.statistics.size_bytes,
                "field_statistics": {
                    name: {
                        "null_count": fs.null_count,
                        "null_percentage": round(fs.null_count / fs.count * 100, 2) if fs.count > 0 else 0,
                        "unique_count": fs.unique_count,
                        "min_value": str(fs.min_value) if fs.min_value else None,
                        "max_value": str(fs.max_value) if fs.max_value else None,
                    }
                    for name, fs in task.statistics.field_stats.items()
                },
                "data_quality": quality_report,
            }

        return result
