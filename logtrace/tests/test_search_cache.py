import pytest
import time
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

from logtrace.modules.search import SearchCache, CacheEntry, LogSearcher
from logtrace.tests.data_generator import builder as data_builder


class TestCacheEntry:
    def test_cache_entry_initialization(self):
        data = {'key': 'value'}
        entry = CacheEntry(data, ttl_seconds=30)

        assert entry.data == data
        assert entry.ttl_seconds == 30
        assert entry.created_at is not None

    def test_cache_entry_not_expired(self):
        data = {'key': 'value'}
        entry = CacheEntry(data, ttl_seconds=60)

        assert entry.is_expired() == False

    def test_cache_entry_expired(self):
        data = {'key': 'value'}
        entry = CacheEntry(data, ttl_seconds=0)

        time.sleep(0.01)

        assert entry.is_expired() == True


class TestSearchCache:
    def test_cache_initialization(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        assert cache.max_size == 100
        assert cache.default_ttl_seconds == 30

    def test_put_and_get(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        test_data = {'result': 'test_data'}
        cache.put(test_data, operation='search', keyword='test')

        result = cache.get(operation='search', keyword='test')

        assert result == test_data

    def test_get_miss_returns_none(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        result = cache.get(operation='search', keyword='nonexistent')

        assert result is None

    def test_put_with_custom_ttl(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        test_data = {'result': 'test_data'}
        cache.put(test_data, ttl_seconds=60, operation='search', keyword='test')

        result = cache.get(operation='search', keyword='test')

        assert result == test_data

    def test_cache_key_order_independent(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        test_data = {'result': 'test_data'}
        cache.put(test_data, operation='search', keyword='test', level='error')

        result = cache.get(level='error', operation='search', keyword='test')

        assert result == test_data

    def test_cache_lru_eviction(self):
        cache = SearchCache(max_size=3, default_ttl_seconds=30)

        for i in range(5):
            cache.put({'data': f'value_{i}'}, key=f'key_{i}')

        stats = cache.get_stats()
        assert stats['cache_size'] == 3

    def test_cache_moves_to_end_on_access(self):
        cache = SearchCache(max_size=3, default_ttl_seconds=30)

        cache.put({'data': 'A'}, key='A')
        cache.put({'data': 'B'}, key='B')
        cache.put({'data': 'C'}, key='C')

        cache.get(key='A')

        cache.put({'data': 'D'}, key='D')

        result = cache.get(key='A')
        assert result is not None

        result = cache.get(key='B')
        assert result is None

    def test_cache_clear(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        for i in range(10):
            cache.put({'data': f'value_{i}'}, key=f'key_{i}')

        assert cache.get_stats()['cache_size'] == 10

        cache.clear()

        assert cache.get_stats()['cache_size'] == 0
        assert cache.get_stats()['hits'] == 0
        assert cache.get_stats()['misses'] == 0

    def test_cache_invalidate(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        cache.put({'data': 'value'}, key='test_key')
        assert cache.get(key='test_key') is not None

        cache.invalidate(key='test_key')
        assert cache.get(key='test_key') is None

    def test_cache_stats_hit_rate(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=30)

        cache.put({'data': 'value'}, key='test')

        cache.get(key='test')
        cache.get(key='test')
        cache.get(key='test')
        cache.get(key='missing')
        cache.get(key='missing')

        stats = cache.get_stats()

        assert stats['hits'] == 3
        assert stats['misses'] == 2
        assert stats['total_requests'] == 5
        assert stats['hit_rate_percent'] == 60.0

    def test_cache_expired_entry_not_returned(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=1)

        cache.put({'data': 'value'}, key='test')

        result1 = cache.get(key='test')
        assert result1 is not None

        time.sleep(1.1)

        result2 = cache.get(key='test')
        assert result2 is None

    def test_cleanup_expired(self):
        cache = SearchCache(max_size=100, default_ttl_seconds=1)

        cache.put({'data': 'value1'}, key='expired_key')
        cache.put({'data': 'value2'}, ttl_seconds=3600, key='valid_key')

        time.sleep(1.1)

        cache.cleanup_expired()

        stats = cache.get_stats()
        assert stats['cache_size'] == 1


class TestLogSearcherWithCache:
    def test_searcher_with_cache_enabled(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        assert searcher.enable_cache == True
        assert searcher.cache is not None

    def test_searcher_with_cache_disabled(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=False)

        assert searcher.enable_cache == False
        assert searcher.cache is None

    def test_clear_cache(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        searcher.cache.put({'data': 'test'}, operation='test')
        assert searcher.get_cache_stats()['cache_size'] == 1

        searcher.clear_cache()

        assert searcher.get_cache_stats()['cache_size'] == 0

    def test_invalidate_cache(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        searcher.cache.put({'data': 'test'}, operation='search_logs', keyword='test')
        assert searcher.get_cache_stats()['cache_size'] == 1

        searcher.invalidate_cache(operation='search_logs', keyword='test')

        assert searcher.get_cache_stats()['cache_size'] == 0

    def test_get_cache_stats_enabled(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        stats = searcher.get_cache_stats()

        assert 'cache_size' in stats
        assert 'max_size' in stats
        assert 'hits' in stats
        assert 'misses' in stats

    def test_get_cache_stats_disabled(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=False)

        stats = searcher.get_cache_stats()

        assert stats == {'cache_enabled': False}

    def test_cleanup_expired_cache(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        searcher.cleanup_expired_cache()

    def test_search_logs_use_cache_parameter(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = data_builder.build_mock_es_search_response([])

        searcher.client = mock_client

        result1 = searcher.search_logs(keyword='test', use_cache=True)
        result2 = searcher.search_logs(keyword='test', use_cache=True)

        mock_client.search.assert_called_once()

    def test_search_logs_bypass_cache(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = data_builder.build_mock_es_search_response([])

        searcher.client = mock_client

        result1 = searcher.search_logs(keyword='test', use_cache=False)
        result2 = searcher.search_logs(keyword='test', use_cache=False)

        assert mock_client.search.call_count == 2

    def test_search_exceptions_use_cache(self, mock_config):
        searcher = LogSearcher(mock_config, enable_cache=True)

        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.return_value = data_builder.build_mock_es_count_response(0)
        mock_client.search.return_value = data_builder.build_mock_es_search_response([])

        searcher.client = mock_client

        result1 = searcher.search_exceptions(use_cache=True)
        result2 = searcher.search_exceptions(use_cache=True)

        mock_client.count.assert_called_once()
        mock_client.search.assert_called_once()
