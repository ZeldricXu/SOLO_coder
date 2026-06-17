from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from etl_engine.orchestrator.dag import DAG, DAGNode

logger = logging.getLogger(__name__)


class DAGExecutor:
    def __init__(self, dag: DAG, pipeline_id: str, execution_id: str) -> None:
        self.dag = dag
        self.pipeline_id = pipeline_id
        self.execution_id = execution_id

    async def execute(self, context: dict | None = None) -> dict:
        if context is None:
            context = {}

        started_at = datetime.now(timezone.utc)
        timeline: list[dict] = []
        node_results: dict[str, dict] = {}
        context["node_results"] = node_results
        dag_status = "success"

        layers = self.dag.get_execution_order()

        for layer_idx, layer in enumerate(layers):
            layer_start = datetime.now(timezone.utc)
            tasks = []
            for node_id in layer:
                node = self.dag.get_node(node_id)
                upstream_ids = self.dag.get_upstream(node_id)
                upstream_results = {
                    uid: node_results.get(uid, {}) for uid in upstream_ids
                }
                task_context = self._build_task_context(node, context, upstream_results)
                tasks.append(self._execute_node(node, task_context))

            layer_results = await asyncio.gather(*tasks, return_exceptions=True)

            for node_id, result in zip(layer, layer_results):
                node = self.dag.get_node(node_id)
                if isinstance(result, Exception):
                    handled = self._handle_failure(node, result, context)
                    node_results[node_id] = handled
                    if handled["status"] == "failed":
                        dag_status = "failed"
                        timeline.append({
                            "node_id": node_id,
                            "node_type": node.type,
                            "status": "failed",
                            "started_at": layer_start.isoformat(),
                            "finished_at": datetime.now(timezone.utc).isoformat(),
                            "error": str(result),
                        })
                        break
                    else:
                        timeline.append({
                            "node_id": node_id,
                            "node_type": node.type,
                            "status": handled["status"],
                            "started_at": layer_start.isoformat(),
                            "finished_at": datetime.now(timezone.utc).isoformat(),
                        })
                else:
                    node_results[node_id] = result
                    timeline.append({
                        "node_id": node_id,
                        "node_type": node.type,
                        "status": result.get("status", "success"),
                        "started_at": layer_start.isoformat(),
                        "finished_at": datetime.now(timezone.utc).isoformat(),
                    })

            if dag_status == "failed":
                break

            logger.info(
                "Layer %d completed: %s", layer_idx, [nid for nid in layer]
            )

        finished_at = datetime.now(timezone.utc)
        data_summary = self._build_data_summary(node_results)

        return {
            "pipeline_id": self.pipeline_id,
            "execution_id": self.execution_id,
            "status": dag_status,
            "started_at": started_at.isoformat(),
            "finished_at": finished_at.isoformat(),
            "timeline": timeline,
            "data_summary": data_summary,
        }

    async def _execute_node(self, node: DAGNode, context: dict) -> dict:
        node_start = datetime.now(timezone.utc)
        try:
            match node.type:
                case "extract":
                    result = await self._execute_extract(node, context)
                case "transform":
                    result = await self._execute_transform(node, context)
                case "quality_check":
                    result = await self._execute_quality_check(node, context)
                case "load":
                    result = await self._execute_load(node, context)
                case _:
                    raise ValueError(f"Unknown node type: {node.type}")
            result["status"] = "success"
            return result
        except Exception as exc:
            if node.on_failure == "retry" and node.retry_count < node.max_retries:
                return await self._retry_node(node, context, exc)
            raise

    async def _execute_extract(self, node: DAGNode, context: dict) -> dict:
        from etl_engine.connectors.base import get_source

        source_type = node.config.get("source_type", "")
        source_config = node.config.get("source_config", {})
        query = node.config.get("query")
        source = get_source(source_type, source_config)
        await source.connect()
        try:
            df = await source.read(query=query)
        finally:
            await source.disconnect()
        return {
            "data": df,
            "rows_read": len(df),
            "columns": list(df.columns),
            "data_interval": node.data_interval,
        }

    async def _execute_transform(self, node: DAGNode, context: dict) -> dict:
        from etl_engine.transform.engine import TransformEngine

        engine = TransformEngine(
            use_dask=node.config.get("use_dask", False),
            dask_n_workers=node.config.get("dask_n_workers", 4),
        )
        transformations = node.config.get("transformations", [])
        input_data = self._resolve_input_data(node, context)
        df = engine.apply(input_data, transformations)
        return {
            "data": df,
            "rows_output": len(df),
            "columns": list(df.columns),
        }

    async def _execute_quality_check(self, node: DAGNode, context: dict) -> dict:
        from etl_engine.quality.rules import QualityRule
        from etl_engine.quality.validator import QualityValidator

        rules_config = node.config.get("rules", [])
        rules = [QualityRule(**r) for r in rules_config]
        validator = QualityValidator(rules)
        input_data = self._resolve_input_data(node, context)
        reference_df = None
        reference_node_id = node.config.get("reference_node_id")
        if reference_node_id and reference_node_id in context.get("node_results", {}):
            ref_result = context["node_results"][reference_node_id]
            reference_df = ref_result.get("data")
        validation_result = validator.validate(input_data, reference_df)
        return {
            "quality_passed": validation_result.passed,
            "quality_blocked": validation_result.blocked,
            "quality_summary": validation_result.summary,
            "data": input_data,
        }

    async def _execute_load(self, node: DAGNode, context: dict) -> dict:
        input_data = self._resolve_input_data(node, context)
        target_config = node.config.get("target_config", {})
        rows_written = len(input_data)
        return {
            "rows_written": rows_written,
            "target": target_config.get("name", "unknown"),
        }

    async def _retry_node(
        self, node: DAGNode, context: dict, error: Exception
    ) -> dict:
        node.retry_count += 1
        logger.warning(
            "Retrying node '%s' (attempt %d/%d): %s",
            node.id,
            node.retry_count,
            node.max_retries,
            str(error),
        )
        await asyncio.sleep(node.retry_delay_seconds)
        try:
            result = await self._execute_node(node, context)
            result["retry_count"] = node.retry_count
            return result
        except Exception as retry_exc:
            return self._handle_failure(node, retry_exc, context)

    def _handle_failure(
        self, node: DAGNode, error: Exception, context: dict
    ) -> dict:
        match node.on_failure:
            case "skip":
                logger.warning(
                    "Skipping node '%s' after failure: %s", node.id, str(error)
                )
                return {
                    "status": "skipped",
                    "error": str(error),
                    "retry_count": node.retry_count,
                }
            case "fail":
                logger.error(
                    "Node '%s' failed, aborting DAG: %s", node.id, str(error)
                )
                return {
                    "status": "failed",
                    "error": str(error),
                    "retry_count": node.retry_count,
                }
            case "retry":
                if node.retry_count < node.max_retries:
                    logger.warning(
                        "Node '%s' failed, retry exhausted: %s",
                        node.id,
                        str(error),
                    )
                    return {
                        "status": "failed",
                        "error": str(error),
                        "retry_count": node.retry_count,
                    }
                logger.error(
                    "Node '%s' failed after %d retries: %s",
                    node.id,
                    node.max_retries,
                    str(error),
                )
                return {
                    "status": "failed",
                    "error": str(error),
                    "retry_count": node.retry_count,
                }
            case _:
                return {
                    "status": "failed",
                    "error": str(error),
                    "retry_count": node.retry_count,
                }

    def _build_task_context(
        self,
        node: DAGNode,
        parent_context: dict,
        upstream_results: dict,
    ) -> dict:
        task_context: dict = {
            "pipeline_id": self.pipeline_id,
            "execution_id": self.execution_id,
            "node_id": node.id,
            "node_type": node.type,
            "node_config": node.config,
        }
        if node.data_interval is not None:
            task_context["data_interval"] = node.data_interval.copy()
        for uid, uresult in upstream_results.items():
            if isinstance(uresult, dict):
                data_interval = uresult.get("data_interval")
                if data_interval and isinstance(data_interval, dict):
                    existing = task_context.get("data_interval", {})
                    if isinstance(existing, dict):
                        merged = {**existing, **data_interval}
                        task_context["data_interval"] = merged
        task_context["node_results"] = parent_context.get("node_results", {})
        if parent_context:
            for key, value in parent_context.items():
                if key not in task_context:
                    task_context[key] = value
        return task_context

    def _resolve_input_data(self, node: DAGNode, context: dict):
        upstream_ids = self.dag.get_upstream(node.id)
        node_results = context.get("node_results", {})
        for uid in reversed(upstream_ids):
            result = node_results.get(uid)
            if isinstance(result, dict) and "data" in result:
                return result["data"]
        config_data = node.config.get("input_data")
        if config_data is not None:
            return config_data
        raise ValueError(
            f"No input data available for node '{node.id}'"
        )

    def _build_data_summary(self, node_results: dict) -> dict:
        summary: dict = {
            "nodes_total": len(self.dag.definition.nodes),
            "nodes_success": 0,
            "nodes_failed": 0,
            "nodes_skipped": 0,
            "rows_read": 0,
            "rows_written": 0,
        }
        for nid, result in node_results.items():
            if not isinstance(result, dict):
                continue
            status = result.get("status", "success")
            if status == "success":
                summary["nodes_success"] += 1
            elif status == "failed":
                summary["nodes_failed"] += 1
            elif status == "skipped":
                summary["nodes_skipped"] += 1
            rows_read = result.get("rows_read")
            if rows_read is not None:
                summary["rows_read"] += rows_read
            rows_written = result.get("rows_written")
            if rows_written is not None:
                summary["rows_written"] += rows_written
        return summary
