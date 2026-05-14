import hashlib
import json
import threading
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, OrderedDict
from dateutil import parser as date_parser
from collections import OrderedDict

from logtrace.core.config import ConfigManager

try:
    from elasticsearch import Elasticsearch
except ImportError:
    Elasticsearch = None


class CacheEntry:
    def __init__(self, data: Any, ttl_seconds: int = 30):
        self.data = data
        self.created_at = datetime.utcnow()
        self.ttl_seconds = ttl_seconds

    def is_expired(self) -> bool:
        return (datetime.utcnow() - self.created_at).total_seconds() > self.ttl_seconds


class SearchCache:
    def __init__(self, max_size: int = 100, default_ttl_seconds: int = 30):
        self.max_size = max_size
        self.default_ttl_seconds = default_ttl_seconds
        self._cache: OrderedDict[str, CacheEntry] = OrderedDict()
        self._lock = threading.Lock()
        self._hits = 0
        self._misses = 0

    def _generate_key(self, **kwargs) -> str:
        sorted_kwargs = sorted(kwargs.items(), key=lambda x: x[0])
        key_str = json.dumps(sorted_kwargs, sort_keys=True, default=str)
        return hashlib.sha256(key_str.encode('utf-8')).hexdigest()

    def get(self, **kwargs) -> Optional[Any]:
        key = self._generate_key(**kwargs)
        with self._lock:
            entry = self._cache.get(key)
            if entry:
                if entry.is_expired():
                    del self._cache[key]
                    self._misses += 1
                    return None
                self._cache.move_to_end(key)
                self._hits += 1
                return entry.data
            self._misses += 1
            return None

    def put(self, data: Any, ttl_seconds: Optional[int] = None, **kwargs):
        key = self._generate_key(**kwargs)
        ttl = ttl_seconds if ttl_seconds is not None else self.default_ttl_seconds
        with self._lock:
            if key in self._cache:
                del self._cache[key]
            elif len(self._cache) >= self.max_size:
                self._cache.popitem(last=False)
            self._cache[key] = CacheEntry(data, ttl)

    def clear(self):
        with self._lock:
            self._cache.clear()
            self._hits = 0
            self._misses = 0

    def invalidate(self, **kwargs):
        key = self._generate_key(**kwargs)
        with self._lock:
            if key in self._cache:
                del self._cache[key]

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total_requests = self._hits + self._misses
            hit_rate = (self._hits / total_requests * 100) if total_requests > 0 else 0
            return {
                'cache_size': len(self._cache),
                'max_size': self.max_size,
                'hits': self._hits,
                'misses': self._misses,
                'total_requests': total_requests,
                'hit_rate_percent': round(hit_rate, 2)
            }

    def cleanup_expired(self):
        with self._lock:
            expired_keys = [
                key for key, entry in self._cache.items()
                if entry.is_expired()
            ]
            for key in expired_keys:
                del self._cache[key]


class LogSearcher:
    LOGS_INDEX_PREFIX = 'logs'

    def __init__(self, config: ConfigManager, enable_cache: bool = True):
        self.config = config
        es_config = config.get_elasticsearch_config()
        self.host = es_config.get('host', 'localhost')
        self.port = es_config.get('port', 9200)
        self.index_prefix = es_config.get('index_prefix', 'logtrace')
        self.client: Optional[Elasticsearch] = None
        self.enable_cache = enable_cache
        if enable_cache:
            self.cache = SearchCache(max_size=200, default_ttl_seconds=30)
        else:
            self.cache = None

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

    def _get_index_pattern(self) -> str:
        return f"{self.index_prefix}-{self.LOGS_INDEX_PREFIX}-*"

    def search_logs(
        self,
        keyword: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        log_level: Optional[str] = None,
        node_id: Optional[str] = None,
        page: int = 1,
        page_size: int = 50,
        use_cache: Optional[bool] = None,
        cache_ttl: Optional[int] = None
    ) -> Dict[str, Any]:
        use_cache_actual = use_cache if use_cache is not None else self.enable_cache

        if use_cache_actual and self.cache:
            cached_result = self.cache.get(
                operation='search_logs',
                keyword=keyword,
                start_time=start_time,
                end_time=end_time,
                log_level=log_level,
                node_id=node_id,
                page=page,
                page_size=page_size
            )
            if cached_result is not None:
                return cached_result

        client = self._get_client()
        if not client:
            result = {'logs': [], 'total': 0, 'page': page, 'page_size': page_size}
            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_logs',
                               keyword=keyword, start_time=start_time, end_time=end_time,
                               log_level=log_level, node_id=node_id, page=page, page_size=page_size)
            return result

        must_clauses = []

        if keyword:
            must_clauses.append({
                'match': {
                    'log_content': keyword
                }
            })

        if log_level:
            must_clauses.append({
                'term': {
                    'log_level': log_level.lower()
                }
            })

        if node_id:
            must_clauses.append({
                'term': {
                    'node_id': node_id
                }
            })

        if start_time or end_time:
            range_clause = {'timestamp': {}}
            if start_time:
                try:
                    st = date_parser.parse(start_time)
                    range_clause['timestamp']['gte'] = st.isoformat()
                except Exception:
                    pass
            if end_time:
                try:
                    et = date_parser.parse(end_time)
                    range_clause['timestamp']['lte'] = et.isoformat()
                except Exception:
                    pass
            if range_clause['timestamp']:
                must_clauses.append({'range': range_clause})

        query = {'bool': {'must': must_clauses}} if must_clauses else {'match_all': {}}

        from_offset = (page - 1) * page_size
        index = self._get_index_pattern()

        try:
            response = client.search(
                index=index,
                query=query,
                from_=from_offset,
                size=page_size,
                sort=[{'timestamp': {'order': 'desc'}}]
            )

            total = response.get('hits', {}).get('total', {}).get('value', 0)
            hits = response.get('hits', {}).get('hits', [])
            logs = [hit['_source'] for hit in hits]

            result = {
                'logs': logs,
                'total': total,
                'page': page,
                'page_size': page_size
            }

            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_logs',
                               keyword=keyword, start_time=start_time, end_time=end_time,
                               log_level=log_level, node_id=node_id, page=page, page_size=page_size)

            return result
        except Exception as e:
            print(f"Error searching logs: {e}")
            result = {'logs': [], 'total': 0, 'page': page, 'page_size': page_size}
            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_logs',
                               keyword=keyword, start_time=start_time, end_time=end_time,
                               log_level=log_level, node_id=node_id, page=page, page_size=page_size)
            return result

    def search_exceptions(
        self,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        node_id: Optional[str] = None,
        page: int = 1,
        page_size: int = 50,
        use_cache: Optional[bool] = None,
        cache_ttl: Optional[int] = None
    ) -> Dict[str, Any]:
        use_cache_actual = use_cache if use_cache is not None else self.enable_cache

        if use_cache_actual and self.cache:
            cached_result = self.cache.get(
                operation='search_exceptions',
                start_time=start_time,
                end_time=end_time,
                node_id=node_id,
                page=page,
                page_size=page_size
            )
            if cached_result is not None:
                return cached_result

        client = self._get_client()
        if not client:
            result = {'exceptions': [], 'exception_count': 0, 'page': page, 'page_size': page_size}
            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_exceptions',
                               start_time=start_time, end_time=end_time,
                               node_id=node_id, page=page, page_size=page_size)
            return result

        must_clauses = [
            {'term': {'is_exception': True}}
        ]

        if node_id:
            must_clauses.append({'term': {'node_id': node_id}})

        if start_time or end_time:
            range_clause = {'timestamp': {}}
            if start_time:
                try:
                    st = date_parser.parse(start_time)
                    range_clause['timestamp']['gte'] = st.isoformat()
                except Exception:
                    pass
            if end_time:
                try:
                    et = date_parser.parse(end_time)
                    range_clause['timestamp']['lte'] = et.isoformat()
                except Exception:
                    pass
            if range_clause['timestamp']:
                must_clauses.append({'range': range_clause})

        query = {'bool': {'must': must_clauses}}
        from_offset = (page - 1) * page_size
        index = self._get_index_pattern()

        try:
            count_response = client.count(index=index, query=query)
            total_count = count_response.get('count', 0)

            response = client.search(
                index=index,
                query=query,
                from_=from_offset,
                size=page_size,
                sort=[{'timestamp': {'order': 'desc'}}]
            )

            hits = response.get('hits', {}).get('hits', [])
            exceptions = [hit['_source'] for hit in hits]

            result = {
                'exceptions': exceptions,
                'exception_count': total_count,
                'page': page,
                'page_size': page_size
            }

            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_exceptions',
                               start_time=start_time, end_time=end_time,
                               node_id=node_id, page=page, page_size=page_size)

            return result
        except Exception as e:
            print(f"Error searching exceptions: {e}")
            result = {'exceptions': [], 'exception_count': 0, 'page': page, 'page_size': page_size}
            if use_cache_actual and self.cache:
                self.cache.put(result, ttl_seconds=cache_ttl, operation='search_exceptions',
                               start_time=start_time, end_time=end_time,
                               node_id=node_id, page=page, page_size=page_size)
            return result

    def clear_cache(self):
        if self.cache:
            self.cache.clear()

    def invalidate_cache(self, **kwargs):
        if self.cache:
            self.cache.invalidate(**kwargs)

    def get_cache_stats(self) -> Dict[str, Any]:
        if self.cache:
            return self.cache.get_stats()
        return {'cache_enabled': False}

    def cleanup_expired_cache(self):
        if self.cache:
            self.cache.cleanup_expired()
