from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List

from logtrace.core.config import ConfigManager
from logtrace.modules.stats import StatsAnalyzer

try:
    from elasticsearch import Elasticsearch
except ImportError:
    Elasticsearch = None


class VisualizationService:
    LOGS_INDEX_PREFIX = 'logs'

    def __init__(self, config: ConfigManager):
        self.config = config
        es_config = config.get_elasticsearch_config()
        self.host = es_config.get('host', 'localhost')
        self.port = es_config.get('port', 9200)
        self.index_prefix = es_config.get('index_prefix', 'logtrace')
        self.stats_analyzer = StatsAnalyzer(config)
        self.client: Optional[Elasticsearch] = None

    def _get_client(self) -> Optional[Elasticsearch]:
        if self.client and self.client.ping():
            return self.client
        if Elasticsearch is None:
            return None
        try:
            self.client = Elasticsearch([f"http://{self.host}:{self.port}"])
            if self.client.ping():
                return self.client
        except Exception:
            pass
        return None

    def get_log_level_distribution(self, hours: int = 24) -> Dict[str, int]:
        client = self._get_client()
        if not client:
            return {'error': 0, 'warning': 0, 'info': 0, 'debug': 0, 'fatal': 0}

        start_time = datetime.utcnow() - timedelta(hours=hours)
        index = f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"
        query = {
            'range': {
                'timestamp': {'gte': start_time.isoformat() + 'Z'}
            }
        }

        try:
            aggs = {
                'by_level': {
                    'terms': {'field': 'log_level'}
                }
            }

            response = client.search(
                index=index,
                query=query,
                size=0,
                aggregations=aggs
            )

            buckets = response.get('aggregations', {}).get('by_level', {}).get('buckets', [])
            result = {'error': 0, 'warning': 0, 'info': 0, 'debug': 0, 'fatal': 0}
            for bucket in buckets:
                level = bucket['key']
                if level in result:
                    result[level] = bucket['doc_count']
            return result
        except Exception as e:
            print(f"Error getting log level distribution: {e}")
            return {'error': 0, 'warning': 0, 'info': 0, 'debug': 0, 'fatal': 0}

    def get_node_log_distribution(self, hours: int = 24) -> Dict[str, int]:
        client = self._get_client()
        if not client:
            return {}

        start_time = datetime.utcnow() - timedelta(hours=hours)
        index = f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"
        query = {
            'range': {
                'timestamp': {'gte': start_time.isoformat() + 'Z'}
            }
        }

        try:
            aggs = {
                'by_node': {
                    'terms': {'field': 'node_id'}
                }
            }

            response = client.search(
                index=index,
                query=query,
                size=0,
                aggregations=aggs
            )

            buckets = response.get('aggregations', {}).get('by_node', {}).get('buckets', [])
            result = {}
            for bucket in buckets:
                result[bucket['key']] = bucket['doc_count']
            return result
        except Exception as e:
            print(f"Error getting node log distribution: {e}")
            return {}

    def get_hourly_trend(self, hours: int = 24) -> List[Dict[str, Any]]:
        client = self._get_client()
        if not client:
            return []

        start_time = datetime.utcnow() - timedelta(hours=hours)
        index = f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"
        query = {
            'range': {
                'timestamp': {'gte': start_time.isoformat() + 'Z'}
            }
        }

        try:
            aggs = {
                'hourly': {
                    'date_histogram': {
                        'field': 'timestamp',
                        'fixed_interval': '1h'
                    }
                }
            }

            response = client.search(
                index=index,
                query=query,
                size=0,
                aggregations=aggs
            )

            buckets = response.get('aggregations', {}).get('hourly', {}).get('buckets', [])
            result = []
            for bucket in buckets:
                result.append({
                    'hour': bucket['key_as_string'][:13],
                    'count': bucket['doc_count']
                })
            return result
        except Exception as e:
            print(f"Error getting hourly trend: {e}")
            return []

    def get_exception_count(self, hours: int = 24) -> int:
        client = self._get_client()
        if not client:
            return 0

        start_time = datetime.utcnow() - timedelta(hours=hours)
        index = f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"
        query = {
            'bool': {
                'must': [
                    {'term': {'is_exception': True}},
                    {'range': {'timestamp': {'gte': start_time.isoformat() + 'Z'}}}
                ]
            }
        }

        try:
            response = client.count(index=index, query=query)
            return response.get('count', 0)
        except Exception as e:
            print(f"Error getting exception count: {e}")
            return 0

    def get_dashboard_data(self) -> Dict[str, Any]:
        return {
            'log_level_distribution': self.get_log_level_distribution(),
            'node_distribution': self.get_node_log_distribution(),
            'hourly_trend': self.get_hourly_trend(),
            'exception_count_24h': self.get_exception_count(),
            'overview': self.stats_analyzer.get_stats()
        }
