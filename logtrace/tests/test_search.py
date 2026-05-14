import pytest
from unittest.mock import MagicMock, patch, call
from datetime import datetime

from logtrace.modules.search import LogSearcher


class TestLogSearcher:
    def test_searcher_initialization(self, mock_config):
        searcher = LogSearcher(mock_config)

        assert searcher.host == 'localhost'
        assert searcher.port == 9200
        assert searcher.index_prefix == 'logtrace'
        assert searcher.client is None

    def test_get_index_pattern(self, mock_config):
        searcher = LogSearcher(mock_config)

        pattern = searcher._get_index_pattern()

        assert pattern == 'logtrace-logs-*'

    def test_search_logs_no_client_returns_empty(self, mock_config):
        searcher = LogSearcher(mock_config)
        searcher.client = None

        result = searcher.search_logs(keyword='test')

        assert result['logs'] == []
        assert result['total'] == 0
        assert result['page'] == 1
        assert result['page_size'] == 50

    def test_search_logs_with_keyword(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 2},
                'hits': [
                    {'_source': {'log_id': '1', 'log_content': 'test log 1'}},
                    {'_source': {'log_id': '2', 'log_content': 'test log 2'}}
                ]
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(keyword='database')

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'query' in call_args.kwargs
        assert call_args.kwargs['index'] == 'logtrace-logs-*'
        assert result['total'] == 2
        assert len(result['logs']) == 2

    def test_search_logs_with_time_range(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 1},
                'hits': [
                    {'_source': {'log_id': '1', 'log_content': 'test log'}}
                ]
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(
            start_time='2026-05-01T00:00:00',
            end_time='2026-05-04T23:59:59'
        )

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'range' in str(call_args.kwargs['query'])

    def test_search_logs_with_log_level(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 5},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(log_level='ERROR')

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'term' in str(call_args.kwargs['query'])
        assert 'error' in str(call_args.kwargs['query']).lower()

    def test_search_logs_with_node_id(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 3},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(node_id='node_01')

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'node_id' in str(call_args.kwargs['query'])

    def test_search_logs_pagination(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 100},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(page=3, page_size=20)

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert call_args.kwargs['from_'] == 40
        assert call_args.kwargs['size'] == 20
        assert result['page'] == 3
        assert result['page_size'] == 20

    def test_search_logs_combined_filters(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 1},
                'hits': [
                    {'_source': {'log_id': '1', 'log_content': 'error test'}}
                ]
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(
            keyword='error',
            log_level='error',
            node_id='node_01',
            start_time='2026-05-01T00:00:00',
            end_time='2026-05-04T23:59:59'
        )

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        query = call_args.kwargs['query']
        assert 'bool' in query
        assert 'must' in query['bool']
        assert len(query['bool']['must']) == 4

    def test_search_logs_no_filters_match_all(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 10},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs()

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'match_all' in call_args.kwargs['query']

    def test_search_logs_exception_returns_empty(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.side_effect = Exception("Elasticsearch error")

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(keyword='test')

        assert result['logs'] == []
        assert result['total'] == 0

    def test_search_logs_sort_by_timestamp(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 2},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs()

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert 'sort' in call_args.kwargs
        assert 'timestamp' in str(call_args.kwargs['sort'])
        assert 'desc' in str(call_args.kwargs['sort']).lower()

    def test_search_exceptions_no_client_returns_empty(self, mock_config):
        searcher = LogSearcher(mock_config)
        searcher.client = None

        result = searcher.search_exceptions()

        assert result['exceptions'] == []
        assert result['exception_count'] == 0
        assert result['page'] == 1
        assert result['page_size'] == 50

    def test_search_exceptions_with_time_range(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.return_value = {'count': 5}
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 5},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_exceptions(
            start_time='2026-05-01T00:00:00',
            end_time='2026-05-04T23:59:59'
        )

        mock_client.count.assert_called_once()
        mock_client.search.assert_called_once()
        assert result['exception_count'] == 5

    def test_search_exceptions_with_node_filter(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.return_value = {'count': 3}
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 3},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_exceptions(node_id='node_01')

        mock_client.count.assert_called_once()
        mock_client.search.assert_called_once()
        count_call_args = mock_client.count.call_args
        query = count_call_args.kwargs['query']
        assert 'bool' in query
        must_clauses = query['bool']['must']
        assert len(must_clauses) == 2
        assert any('is_exception' in str(c) for c in must_clauses)
        assert any('node_id' in str(c) for c in must_clauses)

    def test_search_exceptions_pagination(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.return_value = {'count': 50}
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 50},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_exceptions(page=2, page_size=10)

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        assert call_args.kwargs['from_'] == 10
        assert call_args.kwargs['size'] == 10
        assert result['page'] == 2
        assert result['page_size'] == 10

    def test_search_exceptions_always_filters_is_exception(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.return_value = {'count': 1}
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 1},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_exceptions()

        mock_client.count.assert_called_once()
        count_call_args = mock_client.count.call_args
        query = count_call_args.kwargs['query']
        assert 'bool' in query
        assert 'must' in query['bool']
        must_clauses = query['bool']['must']
        assert len(must_clauses) == 1
        assert 'is_exception' in str(must_clauses[0])

    def test_search_exceptions_exception_returns_empty(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.count.side_effect = Exception("Elasticsearch error")

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_exceptions()

        assert result['exceptions'] == []
        assert result['exception_count'] == 0

    def test_search_logs_invalid_time_format_ignored(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 0},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(
            start_time='invalid-date-format',
            end_time='also-invalid'
        )

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        query = call_args.kwargs['query']
        assert 'range' not in str(query)

    def test_search_logs_upper_case_log_level_converted(self, mock_config):
        mock_client = MagicMock()
        mock_client.ping.return_value = True
        mock_client.search.return_value = {
            'hits': {
                'total': {'value': 0},
                'hits': []
            }
        }

        searcher = LogSearcher(mock_config)
        searcher.client = mock_client

        result = searcher.search_logs(log_level='ERROR')

        mock_client.search.assert_called_once()
        call_args = mock_client.search.call_args
        query = call_args.kwargs['query']
        assert 'error' in str(query).lower()

    def test_get_client_ping_failure_returns_none(self, mock_config):
        searcher = LogSearcher(mock_config)
        searcher.client = MagicMock()
        searcher.client.ping.return_value = False

        client = searcher._get_client()

        assert client is None

    def test_get_client_existing_valid_client_returns(self, mock_config):
        searcher = LogSearcher(mock_config)
        searcher.client = MagicMock()
        searcher.client.ping.return_value = True

        client = searcher._get_client()

        assert client is searcher.client
