import influxdb_client
from influxdb_client.client.write_api import SYNCHRONOUS
from app.config import settings
import logging
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional

logger = logging.getLogger(__name__)


class InfluxDBManager:
    _instance = None
    _client = None
    _write_api = None
    _query_api = None
    _delete_api = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._connect()
        return cls._instance

    def _connect(self):
        try:
            self._client = influxdb_client.InfluxDBClient(
                url=settings.INFLUXDB_URL,
                token=settings.INFLUXDB_TOKEN,
                org=settings.INFLUXDB_ORG,
            )
            self._write_api = self._client.write_api(write_options=SYNCHRONOUS)
            self._query_api = self._client.query_api()
            self._delete_api = self._client.delete_api()
            logger.info("InfluxDB connection established successfully")
        except Exception as e:
            logger.error(f"Failed to connect to InfluxDB: {e}")
            self._client = None

    def write_point(self, measurement: str, tags: Dict, fields: Dict,
                    time: Optional[datetime] = None, bucket: str = None):
        if not self._write_api:
            logger.warning("InfluxDB write API not available")
            return False

        try:
            bucket = bucket or settings.INFLUXDB_BUCKET
            point = influxdb_client.Point(measurement)

            for k, v in tags.items():
                point = point.tag(k, str(v))

            for k, v in fields.items():
                point = point.field(k, v)

            if time:
                point = point.time(time)

            self._write_api.write(bucket=bucket, record=point)
            return True
        except Exception as e:
            logger.error(f"Failed to write point to InfluxDB: {e}")
            return False

    def write_points(self, points: List[influxdb_client.Point], bucket: str = None):
        if not self._write_api:
            logger.warning("InfluxDB write API not available")
            return False

        try:
            bucket = bucket or settings.INFLUXDB_BUCKET
            self._write_api.write(bucket=bucket, record=points)
            return True
        except Exception as e:
            logger.error(f"Failed to write points to InfluxDB: {e}")
            return False

    def query(self, flux_query: str):
        if not self._query_api:
            logger.warning("InfluxDB query API not available")
            return None

        try:
            result = self._query_api.query(flux_query)
            return result
        except Exception as e:
            logger.error(f"Failed to query InfluxDB: {e}")
            return None

    def query_data_frame(self, flux_query: str):
        if not self._query_api:
            logger.warning("InfluxDB query API not available")
            return None

        try:
            df = self._query_api.query_data_frame(flux_query)
            return df
        except Exception as e:
            logger.error(f"Failed to query DataFrame from InfluxDB: {e}")
            return None

    def get_traffic_data(self, sensor_id: str, start_time: datetime,
                         end_time: datetime, aggregation: str = "5m",
                         field: str = "vehicle_count"):
        flux_query = f'''
        from(bucket: "{settings.INFLUXDB_BUCKET}")
          |> range(start: {start_time.isoformat()}Z, stop: {end_time.isoformat()}Z)
          |> filter(fn: (r) => r["_measurement"] == "traffic_flow")
          |> filter(fn: (r) => r["sensor_id"] == "{sensor_id}")
          |> filter(fn: (r) => r["_field"] == "{field}")
          |> aggregateWindow(every: {aggregation}, fn: mean, createEmpty: false)
          |> yield(name: "mean")
        '''
        return self.query_data_frame(flux_query)

    def close(self):
        if self._client:
            self._client.close()


influxdb_manager = InfluxDBManager()
