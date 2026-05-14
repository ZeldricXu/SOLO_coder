from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any

from logtrace.core.config import ConfigManager
from logtrace.core.models import LogRecord, AlertRecord, LogStats

try:
    from elasticsearch import Elasticsearch
    from elasticsearch.helpers import bulk
except ImportError:
    Elasticsearch = None
    bulk = None


class ElasticsearchStorage:
    LOGS_INDEX_PREFIX = 'logs'
    ALERTS_INDEX_PREFIX = 'alerts'
    STATS_INDEX = 'log_stats'

    def __init__(self, config: ConfigManager):
        self.config = config
        es_config = config.get_elasticsearch_config()
        self.host = es_config.get('host', 'localhost')
        self.port = es_config.get('port', 9200)
        self.index_prefix = es_config.get('index_prefix', 'logtrace')
        self.client: Optional[Elasticsearch] = None
        self._connected = False

    def connect(self) -> bool:
        if Elasticsearch is None:
            print("Elasticsearch client not installed. Running in mock mode.")
            self._connected = False
            return False
        try:
            self.client = Elasticsearch([f"http://{self.host}:{self.port}"])
            if self.client.ping():
                self._create_indices()
                self._connected = True
                print(f"Connected to Elasticsearch at {self.host}:{self.port}")
                return True
            print("Elasticsearch ping failed")
            self._connected = False
            return False
        except Exception as e:
            print(f"Failed to connect to Elasticsearch: {e}")
            self._connected = False
            return False

    def _create_indices(self):
        if not self.client:
            return
        logs_index = f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"
        alerts_index = f"{self.index_prefix}-{self.ALERTS_INDEX_PREFIX}"
        stats_index = f"{self.index_prefix}-{self.STATS_INDEX}"

        if not self.client.indices.exists(index=alerts_index):
            self.client.indices.create(index=alerts_index, body=self._get_alerts_mapping())

        if not self.client.indices.exists(index=stats_index):
            self.client.indices.create(index=stats_index, body=self._get_stats_mapping())

    def _get_alerts_mapping(self) -> dict:
        return {
            "mappings": {
                "properties": {
                    "alert_id": {"type": "keyword"},
                    "rule_id": {"type": "keyword"},
                    "node_id": {"type": "keyword"},
                    "exception_count": {"type": "integer"},
                    "alert_time": {"type": "date"},
                    "status": {"type": "keyword"},
                    "notify_channels": {"type": "keyword"}
                }
            }
        }

    def _get_stats_mapping(self) -> dict:
        return {
            "mappings": {
                "properties": {
                    "stat_id": {"type": "keyword"},
                    "stat_date": {"type": "date"},
                    "node_id": {"type": "keyword"},
                    "total_logs": {"type": "integer"},
                    "error_count": {"type": "integer"},
                    "warning_count": {"type": "integer"},
                    "info_count": {"type": "integer"}
                }
            }
        }

    def _get_logs_index(self) -> str:
        today = datetime.utcnow().strftime('%Y.%m.%d')
        return f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-{today}"

    def store_logs(self, logs: List[LogRecord]) -> int:
        if not self._connected or not self.client:
            return len(logs)
        try:
            index = self._get_logs_index()
            actions = [
                {
                    "_index": index,
                    "_id": log.log_id,
                    "_source": log.to_dict()
                }
                for log in logs
            ]
            if actions:
                success, failed = bulk(self.client, actions)
                return success
            return 0
        except Exception as e:
            print(f"Error storing logs: {e}")
            return 0

    def store_alert(self, alert: AlertRecord):
        if not self._connected or not self.client:
            return
        try:
            index = f"{self.index_prefix}-{self.ALERTS_INDEX_PREFIX}"
            self.client.index(index=index, id=alert.alert_id, document=alert.to_dict())
        except Exception as e:
            print(f"Error storing alert: {e}")

    def store_stats(self, stats: LogStats):
        if not self._connected or not self.client:
            return
        try:
            index = f"{self.index_prefix}-{self.STATS_INDEX}"
            self.client.index(index=index, id=stats.stat_id, document=stats.to_dict())
        except Exception as e:
            print(f"Error storing stats: {e}")

    def update_stats(self, node_id: str, log_level: str):
        if not self._connected or not self.client:
            return
        try:
            index = f"{self.index_prefix}-{self.STATS_INDEX}"
            today = datetime.utcnow().strftime('%Y-%m-%d')
            query = {
                "query": {
                    "bool": {
                        "must": [
                            {"term": {"node_id": node_id}},
                            {"term": {"stat_date": today}}
                        ]
                    }
                }
            }
            response = self.client.search(index=index, query=query['query'], size=1)
            hits = response.get('hits', {}).get('hits', [])
            if hits:
                doc = hits[0]['_source']
                doc_id = hits[0]['_id']
                doc['total_logs'] = doc.get('total_logs', 0) + 1
                if log_level in ['error', 'fatal']:
                    doc['error_count'] = doc.get('error_count', 0) + 1
                elif log_level == 'warning':
                    doc['warning_count'] = doc.get('warning_count', 0) + 1
                else:
                    doc['info_count'] = doc.get('info_count', 0) + 1
                self.client.update(index=index, id=doc_id, doc=doc)
            else:
                new_stats = LogStats.create(node_id=node_id, stat_date=today)
                new_stats.update(log_level)
                self.store_stats(new_stats)
        except Exception as e:
            print(f"Error updating stats: {e}")

    def cleanup_old_logs(self, retention_days: int = 30):
        if not self._connected or not self.client:
            return
        try:
            cutoff_date = datetime.utcnow() - timedelta(days=retention_days)
            indices = self.client.indices.get_alias(index=f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*")
            for index_name in indices:
                try:
                    date_str = index_name.split('-')[-1]
                    index_date = datetime.strptime(date_str, '%Y.%m.%d')
                    if index_date < cutoff_date:
                        self.client.indices.delete(index=index_name)
                        print(f"Deleted old index: {index_name}")
                except Exception as e:
                    continue
        except Exception as e:
            print(f"Error cleaning up old logs: {e}")
