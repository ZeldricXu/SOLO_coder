from typing import Dict, Any, Optional, List, Callable
from datetime import datetime
import uuid
import logging

from app.metrics.engine import MetricEngine
from app.core.models import (
    MetricConfig,
    MetricResult,
    CleanedDataEvent,
    AlertRule
)

logger = logging.getLogger(__name__)


class MetricManager:
    def __init__(self):
        self._engines: Dict[str, MetricEngine] = {}
        self._configs: Dict[str, MetricConfig] = {}
        self._on_result_callback: Optional[Callable[[MetricResult], None]] = None
        self._source_to_metrics: Dict[str, List[str]] = {}

    def set_result_callback(self, callback: Callable[[MetricResult], None]):
        self._on_result_callback = callback
        for engine in self._engines.values():
            engine.set_result_callback(callback)

    def _generate_metric_id(self) -> str:
        return f"m_{uuid.uuid4().hex[:8]}"

    async def register_metric(self, config: MetricConfig) -> Optional[str]:
        if config.metric_id:
            if config.metric_id in self._engines:
                logger.warning(f"Metric {config.metric_id} already registered")
                return None
            metric_id = config.metric_id
        else:
            metric_id = self._generate_metric_id()
            config.metric_id = metric_id

        try:
            engine = MetricEngine(config)
            if self._on_result_callback:
                engine.set_result_callback(self._on_result_callback)

            self._engines[metric_id] = engine
            self._configs[metric_id] = config

            if config.source not in self._source_to_metrics:
                self._source_to_metrics[config.source] = []
            self._source_to_metrics[config.source].append(metric_id)

            logger.info(f"Registered metric: {metric_id} ({config.metric_name})")
            return metric_id

        except Exception as e:
            logger.error(f"Failed to register metric: {e}")
            return None

    async def unregister_metric(self, metric_id: str) -> bool:
        if metric_id not in self._engines:
            logger.warning(f"Metric {metric_id} not found")
            return False

        try:
            config = self._configs[metric_id]

            if config.source in self._source_to_metrics:
                if metric_id in self._source_to_metrics[config.source]:
                    self._source_to_metrics[config.source].remove(metric_id)
                if not self._source_to_metrics[config.source]:
                    del self._source_to_metrics[config.source]

            del self._engines[metric_id]
            del self._configs[metric_id]

            logger.info(f"Unregistered metric: {metric_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to unregister metric {metric_id}: {e}")
            return False

    def process_event(self, event: CleanedDataEvent) -> List[MetricResult]:
        results = []

        metric_ids = self._source_to_metrics.get(event.source, [])
        for metric_id in metric_ids:
            engine = self._engines.get(metric_id)
            if engine and self._configs[metric_id].is_active:
                result = engine.process_event(event)
                if result:
                    results.append(result)

        return results

    def get_metric_config(self, metric_id: str) -> Optional[MetricConfig]:
        return self._configs.get(metric_id)

    def get_all_metrics(self) -> Dict[str, MetricConfig]:
        return self._configs.copy()

    def get_metrics_by_source(self, source: str) -> List[MetricConfig]:
        metric_ids = self._source_to_metrics.get(source, [])
        return [
            self._configs[mid] for mid in metric_ids
            if mid in self._configs
        ]

    def get_current_value(self, metric_id: str, group_key: Dict[str, Any] = None) -> Optional[float]:
        engine = self._engines.get(metric_id)
        if not engine:
            return None
        return engine.get_current_value(group_key)

    def flush_all_windows(self, current_time: datetime = None):
        if current_time is None:
            current_time = datetime.utcnow()

        for engine in self._engines.values():
            engine.flush_window(current_time)

    async def update_metric_config(self, metric_id: str, config: MetricConfig) -> bool:
        if metric_id not in self._engines:
            logger.warning(f"Metric {metric_id} not found")
            return False

        if config.metric_id and config.metric_id != metric_id:
            logger.error("Cannot change metric_id")
            return False

        try:
            old_config = self._configs[metric_id]

            if old_config.source != config.source:
                if old_config.source in self._source_to_metrics:
                    if metric_id in self._source_to_metrics[old_config.source]:
                        self._source_to_metrics[old_config.source].remove(metric_id)
                    if not self._source_to_metrics[old_config.source]:
                        del self._source_to_metrics[old_config.source]

                if config.source not in self._source_to_metrics:
                    self._source_to_metrics[config.source] = []
                self._source_to_metrics[config.source].append(metric_id)

            config.metric_id = metric_id
            engine = self._engines[metric_id]
            engine.update_config(config)
            self._configs[metric_id] = config

            logger.info(f"Updated metric config: {metric_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to update metric config {metric_id}: {e}")
            return False

    async def add_alert_rule(self, metric_id: str, rule: AlertRule) -> bool:
        if metric_id not in self._configs:
            logger.warning(f"Metric {metric_id} not found")
            return False

        self._configs[metric_id].alert_rules.append(rule)
        logger.info(f"Added alert rule to metric {metric_id}")
        return True

    async def remove_alert_rule(self, metric_id: str, index: int) -> bool:
        if metric_id not in self._configs:
            logger.warning(f"Metric {metric_id} not found")
            return False

        rules = self._configs[metric_id].alert_rules
        if 0 <= index < len(rules):
            rules.pop(index)
            logger.info(f"Removed alert rule {index} from metric {metric_id}")
            return True
        return False

    def get_stats(self) -> Dict[str, Any]:
        return {
            "total_metrics": len(self._engines),
            "active_metrics": sum(
                1 for c in self._configs.values() if c.is_active
            ),
            "sources": list(self._source_to_metrics.keys()),
            "metrics_by_source": {
                source: len(metrics)
                for source, metrics in self._source_to_metrics.items()
            }
        }


metric_manager = MetricManager()
