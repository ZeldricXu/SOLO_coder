import logging
from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any

from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS

from app.models.metric import Metric
from app import config

logger = logging.getLogger(__name__)


class InfluxDBStorage:
    def __init__(self, influx_config=None):
        if influx_config is None:
            influx_config = config['influxdb']
        
        self.url = influx_config['url']
        self.token = influx_config['token']
        self.org = influx_config['org']
        self.bucket = influx_config['bucket']
        
        self._client = None
        self._write_api = None
        self._query_api = None
        
        self._initialize_client()
    
    def _initialize_client(self):
        try:
            self._client = InfluxDBClient(
                url=self.url,
                token=self.token,
                org=self.org
            )
            self._write_api = self._client.write_api(write_options=SYNCHRONOUS)
            self._query_api = self._client.query_api()
            logger.info(f"Connected to InfluxDB at {self.url}")
        except Exception as e:
            logger.error(f"Failed to connect to InfluxDB: {e}")
            raise
    
    def write_metric(self, metric: Metric) -> bool:
        try:
            point = self._metric_to_point(metric)
            self._write_api.write(
                bucket=self.bucket,
                org=self.org,
                record=point
            )
            logger.debug(f"Written metric: {metric.metric_id} = {metric.value}")
            return True
        except Exception as e:
            logger.error(f"Failed to write metric {metric.metric_id}: {e}")
            return False
    
    def write_metrics_batch(self, metrics: List[Metric]) -> int:
        if not metrics:
            return 0
        
        points = [self._metric_to_point(m) for m in metrics]
        try:
            self._write_api.write(
                bucket=self.bucket,
                org=self.org,
                record=points
            )
            logger.info(f"Batch written {len(points)} metrics")
            return len(points)
        except Exception as e:
            logger.error(f"Failed to batch write metrics: {e}")
            success_count = 0
            for m in metrics:
                if self.write_metric(m):
                    success_count += 1
            return success_count
    
    def query_by_time_range(
        self,
        server_id: str,
        metric_type: str,
        start_time: datetime,
        end_time: Optional[datetime] = None,
        limit: int = 1000
    ) -> List[Metric]:
        if end_time is None:
            end_time = datetime.utcnow()
        
        flux_query = f'''
            from(bucket: "{self.bucket}")
                |> range(start: {self._to_rfc3339(start_time)}, stop: {self._to_rfc3339(end_time)})
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["server_id"] == "{server_id}")
                |> filter(fn: (r) => r["metric_type"] == "{metric_type}")
                |> filter(fn: (r) => r["_field"] == "value")
                |> sort(columns: ["_time"], desc: true)
                |> limit(n: {limit})
        '''
        
        try:
            tables = self._query_api.query(query=flux_query, org=self.org)
            metrics = []
            
            for table in tables:
                for record in table.records:
                    metric = Metric(
                        metric_id=record.values.get('metric_id', f"{server_id}_{metric_type}"),
                        server_id=server_id,
                        metric_type=metric_type,
                        value=record.get_value(),
                        unit=record.values.get('unit', 'unknown'),
                        collected_at=record.get_time()
                    )
                    metrics.append(metric)
            
            logger.debug(f"Query returned {len(metrics)} metrics for {server_id}/{metric_type}")
            return metrics
        except Exception as e:
            logger.error(f"Failed to query metrics: {e}")
            return []
    
    def query_latest(
        self,
        server_id: str,
        metric_type: str,
        lookback_minutes: int = 5
    ) -> Optional[Metric]:
        start_time = datetime.utcnow() - timedelta(minutes=lookback_minutes)
        
        metrics = self.query_by_time_range(
            server_id=server_id,
            metric_type=metric_type,
            start_time=start_time,
            limit=1
        )
        
        return metrics[0] if metrics else None
    
    def query_servers(self) -> List[str]:
        flux_query = f'''
            import "influxdata/influxdb/schema"
            schema.tagValues(
                bucket: "{self.bucket}",
                tag: "server_id",
                start: -30d
            )
        '''
        
        try:
            tables = self._query_api.query(query=flux_query, org=self.org)
            servers = []
            for table in tables:
                for record in table.records:
                    servers.append(record.get_value())
            return sorted(servers)
        except Exception as e:
            logger.error(f"Failed to query servers: {e}")
            return []
    
    def query_metric_types(self, server_id: Optional[str] = None) -> List[str]:
        if server_id:
            flux_query = f'''
                import "influxdata/influxdb/schema"
                schema.tagValues(
                    bucket: "{self.bucket}",
                    tag: "metric_type",
                    predicate: (r) => r.server_id == "{server_id}",
                    start: -30d
                )
            '''
        else:
            flux_query = f'''
                import "influxdata/influxdb/schema"
                schema.tagValues(
                    bucket: "{self.bucket}",
                    tag: "metric_type",
                    start: -30d
                )
            '''
        
        try:
            tables = self._query_api.query(query=flux_query, org=self.org)
            metric_types = []
            for table in tables:
                for record in table.records:
                    metric_types.append(record.get_value())
            return sorted(metric_types)
        except Exception as e:
            logger.error(f"Failed to query metric types: {e}")
            return []
    
    def _metric_to_point(self, metric: Metric) -> Point:
        point_data = metric.to_influx_point()
        
        point = Point(point_data["measurement"])
        
        for key, value in point_data["tags"].items():
            point = point.tag(key, value)
        
        for key, value in point_data["fields"].items():
            point = point.field(key, value)
        
        point = point.time(point_data["time"], WritePrecision.NS)
        
        return point
    
    def _to_rfc3339(self, dt: datetime) -> str:
        return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    
    def close(self):
        if self._client:
            self._client.close()
            logger.info("InfluxDB connection closed")
    
    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
