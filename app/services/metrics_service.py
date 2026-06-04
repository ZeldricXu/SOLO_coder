from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any
import json
import random
import math

from sqlalchemy.orm import Session
import httpx

from app.config import settings
from app.schemas import MetricsQuery, MetricPoint, MetricData


class MetricsService:
    """监控指标查询服务，负责从 Prometheus 获取时序指标数据并格式化。

    主要职责：
    - 时序指标查询：支持 PromQL 查询和 Mock 数据生成
    - 图表数据格式化：将指标数据转换为 Chart.js 可用的格式

    对外接口：
    - query_metrics(query): 查询指标数据
    - get_available_metrics(): 获取可用指标列表
    - get_chart_data_for_frontend(metric_name, hours): 获取前端图表数据

    依赖的外部服务：
    - Prometheus API（/api/v1/query_range）

    注意：get_chart_data_for_frontend 内部使用 asyncio.run()，不可在已运行的事件循环中调用。
    """
    def __init__(self, db: Session):
        self.db = db
        self.use_mock = True

    async def query_metrics(self, query: MetricsQuery) -> List[MetricData]:
        if self.use_mock:
            return self._mock_query(query)
        return await self._prometheus_query(query)

    def _mock_query(self, query: MetricsQuery) -> List[MetricData]:
        end_time = query.end_time or datetime.now()
        start_time = query.start_time or (end_time - timedelta(hours=24))
        step = query.step or 60

        total_points = int((end_time - start_time).total_seconds() / step)
        total_points = min(total_points, 288)

        metric_generators = {
            "cpu_usage": self._generate_cpu_data,
            "memory_usage": self._generate_memory_data,
            "qps": self._generate_qps_data,
            "disk_usage": self._generate_disk_data,
            "network_traffic": self._generate_network_data,
            "error_rate": self._generate_error_rate_data,
        }

        generator = metric_generators.get(query.metric_name, self._generate_default_data)
        return generator(start_time, end_time, step, total_points, query.filters or {})

    def _generate_cpu_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        instances = filters.get("instance", "all")
        if instances == "all":
            instances = ["order-service", "user-service", "pay-service"]

        result = []
        for instance in instances[:3]:
            base_value = random.uniform(30, 50)
            points = []
            for i in range(count):
                t = start + timedelta(seconds=i * step)
                hour_factor = math.sin(i / count * 2 * math.pi) * 15
                noise = random.uniform(-8, 8)
                value = base_value + hour_factor + noise
                value = max(10, min(98, value))
                points.append(MetricPoint(timestamp=t, value=round(value, 2)))
            result.append(MetricData(
                metric="cpu_usage",
                labels={"instance": instance, "job": "microservices"},
                values=points
            ))
        return result

    def _generate_memory_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        instances = filters.get("instance", "all")
        if instances == "all":
            instances = ["order-service", "user-service", "pay-service"]

        result = []
        for instance in instances[:3]:
            base_value = random.uniform(50, 70)
            points = []
            for i in range(count):
                t = start + timedelta(seconds=i * step)
                trend = i / count * 10
                noise = random.uniform(-3, 3)
                value = base_value + trend + noise
                value = max(30, min(95, value))
                points.append(MetricPoint(timestamp=t, value=round(value, 2)))
            result.append(MetricData(
                metric="memory_usage",
                labels={"instance": instance, "job": "microservices"},
                values=points
            ))
        return result

    def _generate_qps_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        instances = filters.get("instance", "all")
        if instances == "all":
            instances = ["order-service", "user-service", "pay-service"]

        result = []
        for idx, instance in enumerate(instances[:3]):
            base_qps = [200, 150, 100][idx]
            points = []
            for i in range(count):
                t = start + timedelta(seconds=i * step)
                hour = t.hour
                time_factor = 1.0
                if 9 <= hour <= 11 or 14 <= hour <= 16 or 20 <= hour <= 22:
                    time_factor = 2.0
                elif 0 <= hour <= 6:
                    time_factor = 0.3
                noise = random.uniform(-20, 20)
                value = base_qps * time_factor + noise
                value = max(10, value)
                points.append(MetricPoint(timestamp=t, value=round(value, 2)))
            result.append(MetricData(
                metric="qps",
                labels={"instance": instance, "job": "microservices"},
                values=points
            ))
        return result

    def _generate_disk_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        mounts = ["/data", "/logs", "/"]
        result = []
        for mount in mounts:
            base_value = random.uniform(40, 75)
            points = []
            for i in range(count):
                t = start + timedelta(seconds=i * step)
                trend = i / count * 3
                noise = random.uniform(-1, 1)
                value = base_value + trend + noise
                value = max(20, min(95, value))
                points.append(MetricPoint(timestamp=t, value=round(value, 2)))
            result.append(MetricData(
                metric="disk_usage",
                labels={"mountpoint": mount, "instance": "db-master"},
                values=points
            ))
        return result

    def _generate_network_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        points = []
        for i in range(count):
            t = start + timedelta(seconds=i * step)
            hour = t.hour
            base = 50 if 0 <= hour <= 6 else 150
            noise = random.uniform(-30, 30)
            value = max(10, base + noise)
            points.append(MetricPoint(timestamp=t, value=round(value, 2)))
        return [MetricData(
            metric="network_traffic",
            labels={"direction": "inbound", "interface": "eth0"},
            values=points
        )]

    def _generate_error_rate_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        points = []
        for i in range(count):
            t = start + timedelta(seconds=i * step)
            value = random.uniform(0, 0.8)
            if i > count * 0.6 and i < count * 0.65:
                value = random.uniform(2.5, 4.0)
            points.append(MetricPoint(timestamp=t, value=round(value, 3)))
        return [MetricData(
            metric="error_rate",
            labels={"instance": "pay-service"},
            values=points
        )]

    def _generate_default_data(self, start: datetime, end: datetime, step: int, count: int, filters: Dict[str, Any]) -> List[MetricData]:
        points = []
        for i in range(count):
            t = start + timedelta(seconds=i * step)
            value = random.uniform(0, 100)
            points.append(MetricPoint(timestamp=t, value=round(value, 2)))
        return [MetricData(
            metric="custom_metric",
            labels=filters,
            values=points
        )]

    async def _prometheus_query(self, query: MetricsQuery) -> List[MetricData]:
        end_time = query.end_time or datetime.now()
        start_time = query.start_time or (end_time - timedelta(hours=24))
        step = query.step or 60

        promql = f'{query.metric_name}'
        if query.filters:
            filter_str = ",".join([f'{k}="{v}"' for k, v in query.filters.items()])
            promql = f'{query.metric_name}{{{filter_str}}}'

        params = {
            "query": promql,
            "start": start_time.timestamp(),
            "end": end_time.timestamp(),
            "step": f"{step}s",
        }

        try:
            async with httpx.AsyncClient(timeout=settings.prometheus_timeout) as client:
                response = await client.get(
                    f"{settings.prometheus_url}/api/v1/query_range",
                    params=params
                )
                data = response.json()

                if data.get("status") != "success":
                    return []

                result = []
                for series in data.get("data", {}).get("result", []):
                    metric = series.get("metric", {})
                    values = []
                    for ts, val in series.get("values", []):
                        values.append(MetricPoint(
                            timestamp=datetime.fromtimestamp(ts),
                            value=float(val)
                        ))
                    result.append(MetricData(
                        metric=query.metric_name,
                        labels=metric,
                        values=values
                    ))
                return result
        except Exception as e:
            print(f"Prometheus query error: {e}")
            return []

    def get_available_metrics(self) -> List[Dict[str, Any]]:
        return [
            {"name": "cpu_usage", "display_name": "CPU使用率", "unit": "%"},
            {"name": "memory_usage", "display_name": "内存使用率", "unit": "%"},
            {"name": "qps", "display_name": "每秒请求数", "unit": "req/s"},
            {"name": "disk_usage", "display_name": "磁盘使用率", "unit": "%"},
            {"name": "network_traffic", "display_name": "网络流量", "unit": "MB/s"},
            {"name": "error_rate", "display_name": "错误率", "unit": "%"},
        ]

    def get_chart_data_for_frontend(self, metric_name: str, hours: int = 24) -> Dict[str, Any]:
        query = MetricsQuery(
            metric_name=metric_name,
            end_time=datetime.now(),
            start_time=datetime.now() - timedelta(hours=hours),
            step=max(60, int(hours * 60 / 144)),
        )
        import asyncio
        metrics = asyncio.run(self.query_metrics(query))

        labels = []
        datasets = []
        colors = ["#3b82f6", "#22c55e", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899"]

        for idx, m in enumerate(metrics):
            if not datasets:
                labels = [p.timestamp.strftime("%Y-%m-%d %H:%M") for p in m.values]
            color = colors[idx % len(colors)]
            datasets.append({
                "label": f"{m.metric} - {list(m.labels.values())[0] if m.labels else 'default'}",
                "data": [p.value for p in m.values],
                "borderColor": color,
                "backgroundColor": f"{color}20",
                "fill": True,
                "tension": 0.4,
                "borderWidth": 2,
                "pointRadius": 0,
                "pointHoverRadius": 4,
            })

        return {
            "labels": labels,
            "datasets": datasets,
            "metric_info": next((m for m in self.get_available_metrics() if m["name"] == metric_name), {}),
        }
