import threading
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Dict, List, Optional


class PluginStatus(str, Enum):
    UNLOADED = "unloaded"
    LOADED = "loaded"
    RUNNING = "running"
    PAUSED = "paused"
    ERROR = "error"


@dataclass
class PluginInfo:
    name: str
    version: str
    description: str = ""
    author: str = ""
    priority: int = 0
    enabled: bool = True
    status: PluginStatus = PluginStatus.UNLOADED
    metadata: Dict[str, Any] = field(default_factory=dict)
    load_time: Optional[datetime] = None
    last_error: Optional[str] = None


class MetricsPlugin(ABC):
    @property
    @abstractmethod
    def info(self) -> PluginInfo:
        pass

    @abstractmethod
    def on_load(self) -> None:
        pass

    @abstractmethod
    def on_unload(self) -> None:
        pass

    @abstractmethod
    def on_counter(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass

    @abstractmethod
    def on_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass

    @abstractmethod
    def on_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass

    def on_snapshot(self, snapshot: Dict[str, Any]) -> None:
        pass

    def is_enabled(self) -> bool:
        return self.info.enabled and self.info.status in (
            PluginStatus.LOADED, PluginStatus.RUNNING
        )


class BaseMetricsPlugin(MetricsPlugin):
    def __init__(
        self,
        name: str,
        version: str = "1.0.0",
        description: str = "",
        author: str = "",
        priority: int = 0
    ):
        self._info = PluginInfo(
            name=name,
            version=version,
            description=description,
            author=author,
            priority=priority
        )

    @property
    def info(self) -> PluginInfo:
        return self._info

    def on_load(self) -> None:
        self._info.status = PluginStatus.LOADED
        self._info.load_time = datetime.utcnow()

    def on_unload(self) -> None:
        self._info.status = PluginStatus.UNLOADED

    def on_counter(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass

    def on_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass

    def on_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        pass


class ConsoleLoggingPlugin(BaseMetricsPlugin):
    def __init__(self, prefix: str = "[METRICS]"):
        super().__init__(
            name="console_logging",
            version="1.0.0",
            description="日志指标到控制台",
            priority=10
        )
        self.prefix = prefix

    def on_counter(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        print(f"{self.prefix} COUNTER {name}{labels}: +{value}")

    def on_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        print(f"{self.prefix} GAUGE {name}{labels} = {value}")

    def on_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        print(f"{self.prefix} HISTOGRAM {name}{labels}: {value}")


class StatsFilePlugin(BaseMetricsPlugin):
    def __init__(self, file_path: str = "/tmp/metrics_stats.log", rotate_size_mb: float = 10.0):
        super().__init__(
            name="stats_file",
            version="1.0.0",
            description="记录指标到文件",
            priority=20
        )
        self.file_path = file_path
        self.rotate_size_mb = rotate_size_mb
        self._file_lock = threading.Lock()
        self._stats: Dict[str, Dict[str, Any]] = {}

    def on_counter(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        key = self._make_key("counter", name, labels)
        if key not in self._stats:
            self._stats[key] = {
                "type": "counter",
                "name": name,
                "labels": labels,
                "total": 0.0,
                "count": 0,
                "last_update": None
            }
        self._stats[key]["total"] += value
        self._stats[key]["count"] += 1
        self._stats[key]["last_update"] = timestamp.isoformat()
        self._flush_if_needed()

    def on_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        key = self._make_key("gauge", name, labels)
        self._stats[key] = {
            "type": "gauge",
            "name": name,
            "labels": labels,
            "value": value,
            "last_update": timestamp.isoformat()
        }
        self._flush_if_needed()

    def on_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        key = self._make_key("histogram", name, labels)
        if key not in self._stats:
            self._stats[key] = {
                "type": "histogram",
                "name": name,
                "labels": labels,
                "count": 0,
                "sum": 0.0,
                "min": float("inf"),
                "max": float("-inf"),
                "last_update": None
            }
        self._stats[key]["count"] += 1
        self._stats[key]["sum"] += value
        self._stats[key]["min"] = min(self._stats[key]["min"], value)
        self._stats[key]["max"] = max(self._stats[key]["max"], value)
        self._stats[key]["last_update"] = timestamp.isoformat()
        self._flush_if_needed()

    def _make_key(self, metric_type: str, name: str, labels: Dict[str, str]) -> str:
        labels_key = ",".join(f"{k}={v}" for k, v in sorted(labels.items()))
        return f"{metric_type}:{name}[{labels_key}]"

    def _flush_if_needed(self) -> None:
        import os
        try:
            if os.path.exists(self.file_path):
                size_mb = os.path.getsize(self.file_path) / (1024 * 1024)
                if size_mb >= self.rotate_size_mb:
                    import shutil
                    shutil.move(self.file_path, f"{self.file_path}.{datetime.utcnow().strftime('%Y%m%d%H%M%S')}")
        except Exception:
            pass

        with self._file_lock:
            try:
                import json
                with open(self.file_path, "w", encoding="utf-8") as f:
                    json.dump({
                        "generated_at": datetime.utcnow().isoformat(),
                        "metrics": self._stats
                    }, f, indent=2, ensure_ascii=False)
            except Exception:
                pass


class ThresholdAlertPlugin(BaseMetricsPlugin):
    def __init__(self, thresholds: Optional[Dict[str, float]] = None):
        super().__init__(
            name="threshold_alert",
            version="1.0.0",
            description="阈值告警插件",
            priority=30
        )
        self.thresholds = thresholds or {}
        self._alerts: List[Dict[str, Any]] = []
        self._max_alerts = 100

    def set_threshold(self, name: str, value: float) -> None:
        self.thresholds[name] = value

    def on_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        if name in self.thresholds and value >= self.thresholds[name]:
            self._record_alert(
                metric_type="gauge",
                metric_name=name,
                current_value=value,
                threshold=self.thresholds[name],
                labels=labels,
                timestamp=timestamp
            )

    def on_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        if name in self.thresholds and value >= self.thresholds[name]:
            self._record_alert(
                metric_type="histogram",
                metric_name=name,
                current_value=value,
                threshold=self.thresholds[name],
                labels=labels,
                timestamp=timestamp
            )

    def _record_alert(
        self,
        metric_type: str,
        metric_name: str,
        current_value: float,
        threshold: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        alert = {
            "alert_id": f"alert_{int(time.time() * 1000000)}",
            "metric_type": metric_type,
            "metric_name": metric_name,
            "current_value": current_value,
            "threshold": threshold,
            "labels": labels,
            "timestamp": timestamp.isoformat(),
            "exceeded_by": current_value - threshold
        }
        self._alerts.append(alert)
        if len(self._alerts) > self._max_alerts:
            self._alerts = self._alerts[-self._max_alerts:]

    def get_recent_alerts(self, limit: int = 20) -> List[Dict[str, Any]]:
        return list(self._alerts[-limit:])

    def clear_alerts(self) -> int:
        count = len(self._alerts)
        self._alerts.clear()
        return count


class PluginManager:
    _instance: Optional["PluginManager"] = None
    _lock = threading.Lock()

    def __init__(self):
        self._plugins: Dict[str, MetricsPlugin] = {}
        self._lock = threading.Lock()

    @classmethod
    def get_instance(cls) -> "PluginManager":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    def register(self, plugin: MetricsPlugin) -> PluginInfo:
        with self._lock:
            if plugin.info.name in self._plugins:
                raise ValueError(f"Plugin '{plugin.info.name}' already registered")
            plugin.on_load()
            self._plugins[plugin.info.name] = plugin
            return plugin.info

    def unregister(self, plugin_name: str) -> bool:
        with self._lock:
            if plugin_name in self._plugins:
                plugin = self._plugins[plugin_name]
                plugin.on_unload()
                del self._plugins[plugin_name]
                return True
            return False

    def get(self, plugin_name: str) -> Optional[MetricsPlugin]:
        with self._lock:
            return self._plugins.get(plugin_name)

    def list_all(self) -> List[PluginInfo]:
        with self._lock:
            return [plugin.info for plugin in sorted(
                self._plugins.values(),
                key=lambda p: -p.info.priority
            )]

    def enable(self, plugin_name: str) -> bool:
        with self._lock:
            plugin = self._plugins.get(plugin_name)
            if plugin:
                plugin.info.enabled = True
                return True
            return False

    def disable(self, plugin_name: str) -> bool:
        with self._lock:
            plugin = self._plugins.get(plugin_name)
            if plugin:
                plugin.info.enabled = False
                return True
            return False

    def notify_counter(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        with self._lock:
            plugins = sorted(
                self._plugins.values(),
                key=lambda p: -p.info.priority
            )
        for plugin in plugins:
            try:
                if plugin.is_enabled():
                    plugin.on_counter(name, value, labels, timestamp)
            except Exception as exc:
                plugin.info.status = PluginStatus.ERROR
                plugin.info.last_error = str(exc)

    def notify_gauge(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        with self._lock:
            plugins = sorted(
                self._plugins.values(),
                key=lambda p: -p.info.priority
            )
        for plugin in plugins:
            try:
                if plugin.is_enabled():
                    plugin.on_gauge(name, value, labels, timestamp)
            except Exception as exc:
                plugin.info.status = PluginStatus.ERROR
                plugin.info.last_error = str(exc)

    def notify_histogram(
        self,
        name: str,
        value: float,
        labels: Dict[str, str],
        timestamp: datetime
    ) -> None:
        with self._lock:
            plugins = sorted(
                self._plugins.values(),
                key=lambda p: -p.info.priority
            )
        for plugin in plugins:
            try:
                if plugin.is_enabled():
                    plugin.on_histogram(name, value, labels, timestamp)
            except Exception as exc:
                plugin.info.status = PluginStatus.ERROR
                plugin.info.last_error = str(exc)

    def notify_snapshot(self, snapshot: Dict[str, Any]) -> None:
        with self._lock:
            plugins = sorted(
                self._plugins.values(),
                key=lambda p: -p.info.priority
            )
        for plugin in plugins:
            try:
                if plugin.is_enabled():
                    plugin.on_snapshot(snapshot)
            except Exception as exc:
                plugin.info.status = PluginStatus.ERROR
                plugin.info.last_error = str(exc)


def get_plugin_manager() -> PluginManager:
    return PluginManager.get_instance()


def register_plugin(plugin: MetricsPlugin) -> PluginInfo:
    return get_plugin_manager().register(plugin)


def unregister_plugin(plugin_name: str) -> bool:
    return get_plugin_manager().unregister(plugin_name)


def get_plugin(plugin_name: str) -> Optional[MetricsPlugin]:
    return get_plugin_manager().get(plugin_name)


def list_plugins() -> List[PluginInfo]:
    return get_plugin_manager().list_all()
