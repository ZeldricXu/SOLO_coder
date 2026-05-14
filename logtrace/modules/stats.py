from datetime import datetime
from typing import Optional, Dict, Any, List
from dateutil import parser as date_parser

from logtrace.core.config import ConfigManager

try:
    from elasticsearch import Elasticsearch
except ImportError:
    Elasticsearch = None


class StatsAnalyzer:
    STATS_INDEX = 'log_stats'

    def __init__(self, config: ConfigManager):
        self.config = config
        es_config = config.get_elasticsearch_config()
        self.host = es_config.get('host', 'localhost')
        self.port = es_config.get('port', 9200)
        self.index_prefix = es_config.get('index_prefix', 'logtrace')
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

    def get_stats(
        self,
        node_id: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        client = self._get_client()
        if not client:
            return {'total_logs': 0, 'error_count': 0, 'warning_count': 0, 'info_count': 0, 'by_node': {}}

        must_clauses = []

        if node_id:
            must_clauses.append({'term': {'node_id': node_id}})

        if start_date or end_date:
            range_clause = {'stat_date': {}}
            if start_date:
                try:
                    st = date_parser.parse(start_date)
                    range_clause['stat_date']['gte'] = st.strftime('%Y-%m-%d')
                except Exception:
                    pass
            if end_date:
                try:
                    et = date_parser.parse(end_date)
                    range_clause['stat_date']['lte'] = et.strftime('%Y-%m-%d')
                except Exception:
                    pass
            if range_clause['stat_date']:
                must_clauses.append({'range': range_clause})

        query = {'bool': {'must': must_clauses}} if must_clauses else {'match_all': {}}
        index = f"{self.index_prefix}-{self.STATS_INDEX}"

        try:
            aggs = {
                'total_logs': {'sum': {'field': 'total_logs'}},
                'error_count': {'sum': {'field': 'error_count'}},
                'warning_count': {'sum': {'field': 'warning_count'}},
                'info_count': {'sum': {'field': 'info_count'}},
                'by_node': {
                    'terms': {'field': 'node_id'},
                    'aggs': {
                        'total_logs': {'sum': {'field': 'total_logs'}},
                        'error_count': {'sum': {'field': 'error_count'}},
                        'warning_count': {'sum': {'field': 'warning_count'}},
                        'info_count': {'sum': {'field': 'info_count'}}
                    }
                }
            }

            response = client.search(
                index=index,
                query=query,
                size=0,
                aggregations=aggs
            )

            aggs_result = response.get('aggregations', {})
            by_node_buckets = aggs_result.get('by_node', {}).get('buckets', [])

            by_node = {}
            for bucket in by_node_buckets:
                by_node[bucket['key']] = {
                    'total_logs': int(bucket['total_logs']['value']),
                    'error_count': int(bucket['error_count']['value']),
                    'warning_count': int(bucket['warning_count']['value']),
                    'info_count': int(bucket['info_count']['value'])
                }

            return {
                'total_logs': int(aggs_result.get('total_logs', {}).get('value', 0)),
                'error_count': int(aggs_result.get('error_count', {}).get('value', 0)),
                'warning_count': int(aggs_result.get('warning_count', {}).get('value', 0)),
                'info_count': int(aggs_result.get('info_count', {}).get('value', 0)),
                'by_node': by_node
            }
        except Exception as e:
            print(f"Error getting stats: {e}")
            return {'total_logs': 0, 'error_count': 0, 'warning_count': 0, 'info_count': 0, 'by_node': {}}

    def get_daily_stats(self, days: int = 7) -> List[Dict[str, Any]]:
        client = self._get_client()
        if not client:
            return []

        index = f"{self.index_prefix}-{self.STATS_INDEX}"
        query = {'match_all': {}}

        try:
            aggs = {
                'daily': {
                    'date_histogram': {
                        'field': 'stat_date',
                        'calendar_interval': 'day'
                    },
                    'aggs': {
                        'total_logs': {'sum': {'field': 'total_logs'}},
                        'error_count': {'sum': {'field': 'error_count'}},
                        'warning_count': {'sum': {'field': 'warning_count'}},
                        'info_count': {'sum': {'field': 'info_count'}}
                    }
                }
            }

            response = client.search(
                index=index,
                query=query,
                size=0,
                aggregations=aggs
            )

            buckets = response.get('aggregations', {}).get('daily', {}).get('buckets', [])
            result = []
            for bucket in buckets:
                result.append({
                    'date': bucket['key_as_string'][:10],
                    'total_logs': int(bucket['total_logs']['value']),
                    'error_count': int(bucket['error_count']['value']),
                    'warning_count': int(bucket['warning_count']['value']),
                    'info_count': int(bucket['info_count']['value'])
                })
            return result
        except Exception as e:
            print(f"Error getting daily stats: {e}")
            return []
