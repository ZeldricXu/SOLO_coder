from __future__ import annotations

from datetime import datetime, timezone

from croniter import croniter

from etl_engine.orchestrator.dag import DAG


class DAGScheduler:
    def __init__(self) -> None:
        pass

    def should_trigger(
        self,
        dag: DAG,
        trigger_type: str,
        context: dict | None = None,
    ) -> bool:
        if trigger_type == "manual":
            return True
        if trigger_type == "schedule":
            if dag.definition.schedule is None:
                return False
            last_run: datetime | None = None
            if context:
                last_run = context.get("last_run")
            next_run = self.get_next_run(dag.definition.schedule, last_run)
            now = datetime.now(timezone.utc)
            return now >= next_run
        return False

    def get_next_run(
        self,
        cron_expression: str,
        last_run: datetime | None = None,
    ) -> datetime:
        if last_run is None:
            last_run = datetime.now(timezone.utc)
        elif last_run.tzinfo is None:
            last_run = last_run.replace(tzinfo=timezone.utc)
        cron = croniter(cron_expression, last_run)
        return cron.get_next(datetime)

    def check_sla(self, started_at: datetime, sla_seconds: int) -> bool:
        if started_at.tzinfo is None:
            started_at = started_at.replace(tzinfo=timezone.utc)
        now = datetime.now(timezone.utc)
        elapsed = (now - started_at).total_seconds()
        return elapsed > sla_seconds

    def resolve_data_interval(self, dag: DAG, context: dict) -> dict:
        result: dict[str, dict] = {}
        for node in dag.definition.nodes:
            if node.data_interval is not None:
                result[node.id] = node.data_interval.copy()
                for key, value in node.data_interval.items():
                    if isinstance(value, str) and value.startswith("$"):
                        ref_path = value[1:].split(".")
                        resolved = context
                        for part in ref_path:
                            if isinstance(resolved, dict) and part in resolved:
                                resolved = resolved[part]
                            else:
                                resolved = None
                                break
                        if resolved is not None:
                            result[node.id][key] = resolved
            else:
                upstream_ids = dag.get_upstream(node.id)
                if upstream_ids:
                    merged: dict = {}
                    for uid in upstream_ids:
                        upstream_result = context.get("node_results", {}).get(uid, {})
                        if isinstance(upstream_result, dict):
                            data_interval = upstream_result.get("data_interval", {})
                            if isinstance(data_interval, dict):
                                merged.update(data_interval)
                    if merged:
                        result[node.id] = merged
        return result
