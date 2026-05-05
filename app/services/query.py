import logging
from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any, Tuple

from app.models.metric import Metric
from app.services.storage import InfluxDBStorage
from app import config

logger = logging.getLogger(__name__)


class MetricQueryService:
    def __init__(self, storage: InfluxDBStorage = None):
        if storage is None:
            storage = InfluxDBStorage(config['influxdb'])
        self.storage = storage
    
    def query_metrics(
        self,
        server_id: str,
        metric_type: str,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        time_range_minutes: Optional[int] = None,
        limit: int = 1000
    ) -> List[Metric]:
        if time_range_minutes is not None:
            end_time = datetime.utcnow()
            start_time = end_time - timedelta(minutes=time_range_minutes)
        
        if start_time is None:
            start_time = datetime.utcnow() - timedelta(minutes=60)
        
        if end_time is None:
            end_time = datetime.utcnow()
        
        return self.storage.query_by_time_range(
            server_id=server_id,
            metric_type=metric_type,
            start_time=start_time,
            end_time=end_time,
            limit=limit
        )
    
    def query_latest_metrics(
        self,
        server_id: str,
        metric_types: Optional[List[str]] = None,
        lookback_minutes: int = 5
    ) -> Dict[str, Optional[Metric]]:
        if metric_types is None:
            metric_types = self.storage.query_metric_types(server_id)
        
        results = {}
        for metric_type in metric_types:
            results[metric_type] = self.storage.query_latest(
                server_id=server_id,
                metric_type=metric_type,
                lookback_minutes=lookback_minutes
            )
        
        return results
    
    def query_aggregated(
        self,
        server_id: str,
        metric_type: str,
        start_time: datetime,
        end_time: datetime,
        interval_seconds: int = 60,
        aggregator: str = "mean"
    ) -> List[Dict[str, Any]]:
        aggregator_map = {
            "mean": "mean",
            "avg": "mean",
            "max": "max",
            "min": "min",
            "sum": "sum",
            "count": "count",
            "median": "median"
        }
        
        flux_aggregator = aggregator_map.get(aggregator, "mean")
        
        flux_query = f'''
            from(bucket: "{self.storage.bucket}")
                |> range(start: {self._to_rfc3339(start_time)}, stop: {self._to_rfc3339(end_time)})
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["server_id"] == "{server_id}")
                |> filter(fn: (r) => r["metric_type"] == "{metric_type}")
                |> filter(fn: (r) => r["_field"] == "value")
                |> aggregateWindow(every: {interval_seconds}s, fn: {flux_aggregator})
                |> sort(columns: ["_time"], desc: false)
        '''
        
        try:
            tables = self.storage._query_api.query(query=flux_query, org=self.storage.org)
            results = []
            
            for table in tables:
                for record in table.records:
                    results.append({
                        "time": record.get_time().isoformat() if record.get_time() else None,
                        "value": round(record.get_value(), 4) if record.get_value() is not None else None,
                        "server_id": server_id,
                        "metric_type": metric_type
                    })
            
            return results
        except Exception as e:
            logger.error(f"Failed to query aggregated metrics: {e}")
            return []
    
    def query_servers_overview(
        self,
        lookback_minutes: int = 5
    ) -> List[Dict[str, Any]]:
        servers = self.storage.query_servers()
        overview = []
        
        for server_id in servers:
            latest = self.query_latest_metrics(
                server_id=server_id,
                lookback_minutes=lookback_minutes
            )
            
            server_info = {
                "server_id": server_id,
                "metrics": {},
                "last_seen": None
            }
            
            for metric_type, metric in latest.items():
                if metric:
                    server_info["metrics"][metric_type] = {
                        "value": metric.value,
                        "unit": metric.unit,
                        "collected_at": metric.collected_at.isoformat() if metric.collected_at else None
                    }
                    
                    if metric.collected_at:
                        if server_info["last_seen"] is None or metric.collected_at > server_info["last_seen"]:
                            server_info["last_seen"] = metric.collected_at
            
            if server_info["last_seen"]:
                server_info["last_seen"] = server_info["last_seen"].isoformat()
            
            overview.append(server_info)
        
        return overview
    
    def query_metric_statistics(
        self,
        server_id: str,
        metric_type: str,
        start_time: datetime,
        end_time: datetime
    ) -> Dict[str, Any]:
        flux_query = f'''
            data = from(bucket: "{self.storage.bucket}")
                |> range(start: {self._to_rfc3339(start_time)}, stop: {self._to_rfc3339(end_time)})
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["server_id"] == "{server_id}")
                |> filter(fn: (r) => r["metric_type"] == "{metric_type}")
                |> filter(fn: (r) => r["_field"] == "value")

            mean = data |> mean(column: "_value") |> findRecord(fn: (key) => true, idx: 0)
            max_val = data |> max(column: "_value") |> findRecord(fn: (key) => true, idx: 0)
            min_val = data |> min(column: "_value") |> findRecord(fn: (key) => true, idx: 0)
            count = data |> count(column: "_value") |> findRecord(fn: (key) => true, idx: 0)

            {{mean: mean._value, max: max_val._value, min: min_val._value, count: count._value}}
        '''
        
        try:
            tables = self.storage._query_api.query(query=flux_query, org=self.storage.org)
            stats = {
                "server_id": server_id,
                "metric_type": metric_type,
                "start_time": start_time.isoformat(),
                "end_time": end_time.isoformat(),
                "count": 0,
                "mean": None,
                "max": None,
                "min": None
            }
            
            for table in tables:
                for record in table.records:
                    stats["count"] = record.values.get("count", 0)
                    stats["mean"] = round(record.values.get("mean"), 4) if record.values.get("mean") is not None else None
                    stats["max"] = round(record.values.get("max"), 4) if record.values.get("max") is not None else None
                    stats["min"] = round(record.values.get("min"), 4) if record.values.get("min") is not None else None
            
            return stats
        except Exception as e:
            logger.error(f"Failed to query metric statistics: {e}")
            return self._calc_stats_locally(server_id, metric_type, start_time, end_time)
    
    def _calc_stats_locally(
        self,
        server_id: str,
        metric_type: str,
        start_time: datetime,
        end_time: datetime
    ) -> Dict[str, Any]:
        metrics = self.storage.query_by_time_range(
            server_id=server_id,
            metric_type=metric_type,
            start_time=start_time,
            end_time=end_time,
            limit=100000
        )
        
        if not metrics:
            return {
                "server_id": server_id,
                "metric_type": metric_type,
                "start_time": start_time.isoformat(),
                "end_time": end_time.isoformat(),
                "count": 0,
                "mean": None,
                "max": None,
                "min": None
            }
        
        values = [m.value for m in metrics if m.value is not None]
        
        return {
            "server_id": server_id,
            "metric_type": metric_type,
            "start_time": start_time.isoformat(),
            "end_time": end_time.isoformat(),
            "count": len(values),
            "mean": round(sum(values) / len(values), 4) if values else None,
            "max": round(max(values), 4) if values else None,
            "min": round(min(values), 4) if values else None
        }
    
    def _to_rfc3339(self, dt: datetime) -> str:
        return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    
    def list_servers(self) -> List[str]:
        return self.storage.query_servers()
    
    def list_metric_types(self, server_id: Optional[str] = None) -> List[str]:
        return self.storage.query_metric_types(server_id)
