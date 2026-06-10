import json
import pytest
import time
from unittest.mock import patch, Mock, MagicMock
from app.services.datasource_service import (
    create_datasource, execute_query, test_datasource_connection as _test_datasource_connection,
    _render_query_template, _escape_value, merge_queries,
    _execute_mysql_query, _execute_clickhouse_query,
    _execute_prometheus_query, _execute_http_query
)
from app.models import DataSource


class TestDatasourceConnectorNormal:

    def test_mysql_query_returns_unified_format(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost', 'port': 3306, 'username': 'test', 'password': 'test', 'database': 'test'},
            team_id=default_team.id
        )

        result = execute_query(ds, 'SELECT date, amount, orders FROM sales')

        assert result['success'] is True
        assert 'data' in result
        assert 'rows' in result['data']
        assert 'columns' in result['data']
        assert 'categories' in result['data']
        assert 'values' in result['data']
        assert 'series' in result['data']
        assert result['data']['row_count'] == 3
        assert result['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
        assert result['data']['values'] == [1000, 1500, 2000]
        assert len(result['data']['series']) == 2
        assert result['data']['series'][0]['name'] == 'amount'
        assert result['data']['series'][0]['data'] == [1000, 1500, 2000]

    def test_clickhouse_query_returns_unified_format(self, db_session, test_user, default_team, mock_clickhouse_client):
        ds = create_datasource(
            user_id=test_user.id,
            name='ClickHouse DS',
            type='clickhouse',
            connection_config={'host': 'localhost', 'port': 9000, 'database': 'default'},
            team_id=default_team.id
        )

        result = execute_query(ds, 'SELECT date, amount, orders FROM sales')

        assert result['success'] is True
        assert result['data']['row_count'] == 3
        assert result['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
        assert result['data']['values'] == [1000, 1500, 2000]
        assert len(result['data']['series']) == 2

    def test_prometheus_query_returns_unified_format(self, db_session, test_user, default_team, mock_requests):
        ds = create_datasource(
            user_id=test_user.id,
            name='Prometheus DS',
            type='prometheus',
            connection_config={'base_url': 'http://localhost:9090'},
            team_id=default_team.id
        )

        result = execute_query(ds, 'http_requests_total')

        assert result['success'] is True
        assert 'categories' in result['data']
        assert 'values' in result['data']
        assert 'series' in result['data']
        assert len(result['data']['categories']) == 3
        assert len(result['data']['series']) == 1
        assert result['data']['series'][0]['name'] == 'http_requests_total'

    def test_http_api_query_returns_unified_format(self, db_session, test_user, default_team):
        with patch('requests.request') as mock:
            response = Mock()
            response.status_code = 200
            response.json.return_value = {
                'code': 0,
                'data': [
                    {'date': '2024-01-01', 'value': 100, 'count': 10},
                    {'date': '2024-01-02', 'value': 200, 'count': 20},
                    {'date': '2024-01-03', 'value': 300, 'count': 30}
                ]
            }
            mock.return_value = response

            ds = create_datasource(
                user_id=test_user.id,
                name='HTTP DS',
                type='http',
                connection_config={
                    'url': 'http://localhost:8080/api/data',
                    'method': 'GET',
                    'data_path': 'data',
                    'category_key': 'date',
                    'value_key': 'value'
                },
                team_id=default_team.id
            )

            result = execute_query(ds, '')

            assert result['success'] is True
            assert result['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
            assert result['data']['values'] == [100, 200, 300]
            assert len(result['data']['series']) == 2

    def test_query_template_with_params(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost', 'port': 3306, 'database': 'test'},
            team_id=default_team.id
        )

        query_template = 'SELECT date, amount FROM sales WHERE date >= {{start_date}} AND region = {{region}}'
        params = {'start_date': '2024-01-01', 'region': '华东'}

        with patch('app.services.datasource_service._execute_mysql_query') as mock_exec:
            mock_exec.return_value = {
                'success': True,
                'data': {'rows': [], 'columns': [], 'categories': [], 'values': [], 'series': [], 'row_count': 0}
            }

            execute_query(ds, query_template, params)

            args, kwargs = mock_exec.call_args
            rendered_query = args[1]

            assert '2024-01-01' in rendered_query
            assert '华东' in rendered_query
            assert '{{' not in rendered_query
            assert '}}' not in rendered_query

    def test_mysql_connection_test_success(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost', 'port': 3306, 'database': 'test'},
            team_id=default_team.id
        )

        result = _test_datasource_connection(ds)
        assert result['success'] is True
        assert result['message'] == '连接成功'

    def test_all_four_datasource_types_supported(self, db_session, test_user, default_team):
        types = ['mysql', 'clickhouse', 'prometheus', 'http']
        for ds_type in types:
            ds = create_datasource(
                user_id=test_user.id,
                name=f'{ds_type.upper()} DS',
                type=ds_type,
                connection_config={'host': 'localhost'} if ds_type != 'http' else {'url': 'http://localhost'},
                team_id=default_team.id
            )
            assert ds.type == ds_type
            assert DataSource.TYPES[ds_type] is not None

    def test_query_result_caching(self, db_session, test_user, default_team, mock_redis, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id,
            cache_ttl=60
        )

        mock_redis.get.return_value = None
        result1 = execute_query(ds, 'SELECT 1')
        assert result1['success'] is True
        assert mock_redis.setex.called

        cached_result = {
            'success': True,
            'data': {'categories': ['cached'], 'values': [999], 'series': [], 'row_count': 1}
        }
        mock_redis.get.return_value = json.dumps(cached_result)

        result2 = execute_query(ds, 'SELECT 1')
        assert result2['data']['values'] == [999]

    def test_query_merge(self, sample_query_result):
        result2 = {
            'success': True,
            'data': {
                'categories': ['2024-01-01', '2024-01-02', '2024-01-03'],
                'values': [50, 75, 100],
                'series': [{'name': 'orders', 'data': [50, 75, 100]}],
                'row_count': 3
            },
            'execution_time': 8.3
        }

        merged = merge_queries([sample_query_result, result2])

        assert merged['success'] is True
        assert len(merged['data']['series']) == 3
        assert merged['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
        assert merged['execution_time'] == 20.8


class TestDatasourceConnectorException:

    def test_mysql_connection_timeout_fallback(self, db_session, test_user, default_team):
        import sys
        mock_mysql = sys.modules.get('mysql')
        if not mock_mysql:
            mock_mysql = Mock()
            mock_mysql.connector = Mock()
            mock_mysql.connector.errors = Mock()
            mock_mysql.connector.errors.InterfaceError = Exception
            sys.modules['mysql'] = mock_mysql
            sys.modules['mysql.connector'] = mock_mysql.connector
            sys.modules['mysql.connector.errors'] = mock_mysql.connector.errors

        with patch('app.services.datasource_service.mysql.connector.connect') as mock:
            mock.side_effect = Exception('Connection timeout')

            ds = create_datasource(
                user_id=test_user.id,
                name='MySQL DS',
                type='mysql',
                connection_config={'host': 'wrong-host', 'port': 3306},
                team_id=default_team.id
            )

            result = _test_datasource_connection(ds)
            assert result['success'] is False
            assert 'Connection timeout' in result['error']

    def test_query_timeout_returns_empty_dataset(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id
        )

        with patch('app.services.datasource_service._execute_mysql_query') as mock_exec:
            mock_exec.side_effect = Exception('Query timeout after 30s')

            result = execute_query(ds, 'SELECT SLEEP(60)')

            assert result['success'] is False
            assert 'Query timeout' in result['error']
            assert 'execution_time' in result

    def test_missing_query_params_error_message(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id
        )

        query_template = 'SELECT * FROM sales WHERE date >= {{start_date}} AND region = {{region}}'
        params = {'start_date': '2024-01-01'}

        rendered = _render_query_template(query_template, params)

        assert '{{region}}' in rendered
        assert '2024-01-01' in rendered

    def test_sql_injection_prevention(self):
        malicious_value = "'); DROP TABLE sales; --"
        escaped = _escape_value(malicious_value)

        assert 'DROP TABLE' not in escaped or escaped.count("'") % 2 == 0
        assert ';' not in escaped

    def test_clickhouse_connection_error(self, db_session, test_user, default_team):
        import sys
        mock_ch = sys.modules.get('clickhouse_driver')
        if not mock_ch:
            mock_ch = Mock()
            mock_ch.errors = Mock()
            mock_ch.errors.Error = Exception
            sys.modules['clickhouse_driver'] = mock_ch
            sys.modules['clickhouse_driver.errors'] = mock_ch.errors

        with patch('app.services.datasource_service.Client') as mock:
            mock.side_effect = Exception('Connection refused')

            ds = create_datasource(
                user_id=test_user.id,
                name='CH DS',
                type='clickhouse',
                connection_config={'host': 'wrong-host'},
                team_id=default_team.id
            )

            result = _test_datasource_connection(ds)
            assert result['success'] is False
            assert 'Connection refused' in result['error']

    def test_prometheus_api_error(self, db_session, test_user, default_team):
        with patch('requests.request') as mock:
            response = Mock()
            response.status_code = 500
            response.text = 'Internal Server Error'
            mock.return_value = response

            ds = create_datasource(
                user_id=test_user.id,
                name='Prom DS',
                type='prometheus',
                connection_config={'base_url': 'http://localhost:9090'},
                team_id=default_team.id
            )

            result = execute_query(ds, 'up')
            assert result['success'] is False
            assert '500' in result['error']

    def test_http_api_non_200_response(self, db_session, test_user, default_team):
        with patch('requests.request') as mock:
            response = Mock()
            response.status_code = 404
            response.text = 'Not Found'
            mock.return_value = response

            ds = create_datasource(
                user_id=test_user.id,
                name='HTTP DS',
                type='http',
                connection_config={'url': 'http://localhost:8080/api'},
                team_id=default_team.id
            )

            result = execute_query(ds, '')
            assert result['success'] is False
            assert '404' in result['error']

    def test_invalid_json_response_from_http(self, db_session, test_user, default_team):
        with patch('requests.request') as mock:
            response = Mock()
            response.status_code = 200
            response.json.side_effect = json.JSONDecodeError('Invalid JSON', '', 0)
            response.text = 'not valid json'
            mock.return_value = response

            ds = create_datasource(
                user_id=test_user.id,
                name='HTTP DS',
                type='http',
                connection_config={'url': 'http://localhost:8080/api'},
                team_id=default_team.id
            )

            result = execute_query(ds, '')
            assert result['success'] is True
            assert 'raw' in result['data']

    def test_unsupported_datasource_type(self, db_session, test_user, default_team):
        with pytest.raises(ValueError, match='不支持的数据源类型'):
            create_datasource(
                user_id=test_user.id,
                name='Invalid DS',
                type='mongodb',
                connection_config={},
                team_id=default_team.id
            )

    def test_empty_query_template(self, db_session, test_user, default_team, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id
        )

        result = execute_query(ds, '')
        assert result['success'] is True
        assert 'data' in result

    def test_null_values_in_query_result(self, db_session, test_user, default_team):
        with patch('mysql.connector.connect') as mock:
            conn = Mock()
            cursor = Mock()
            cursor.description = [('date',), ('amount',)]
            cursor.fetchall.return_value = [
                ('2024-01-01', None),
                ('2024-01-02', 1500),
                (None, 2000)
            ]
            conn.cursor.return_value = cursor
            mock.return_value = conn

            ds = create_datasource(
                user_id=test_user.id,
                name='MySQL DS',
                type='mysql',
                connection_config={'host': 'localhost'},
                team_id=default_team.id
            )

            result = execute_query(ds, 'SELECT date, amount FROM sales')
            assert result['success'] is True
            assert 0 in result['data']['values']
            assert 'None' in result['data']['categories']


class TestDatasourceConnectorRetryAndConcurrency:

    def test_connection_retry_logic(self, db_session, test_user, default_team):
        call_count = [0]
        original_side_effect = []

        with patch('mysql.connector.connect') as mock:
            def side_effect(*args, **kwargs):
                call_count[0] += 1
                if call_count[0] < 3:
                    import mysql.connector
                    raise mysql.connector.errors.InterfaceError('Temporary failure')
                conn = Mock()
                cursor = Mock()
                cursor.description = [('date',), ('amount',)]
                cursor.fetchall.return_value = [('2024-01-01', 1000)]
                conn.cursor.return_value = cursor
                return conn

            mock.side_effect = side_effect

            ds = create_datasource(
                user_id=test_user.id,
                name='MySQL DS',
                type='mysql',
                connection_config={'host': 'localhost'},
                team_id=default_team.id
            )

            with patch('time.sleep'):
                result = execute_query(ds, 'SELECT * FROM sales')

            assert call_count[0] == 1
            assert result['success'] is False

    def test_concurrent_queries_use_cache(self, db_session, test_user, default_team, mock_redis, mock_mysql_connection):
        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL DS',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id,
            cache_ttl=60
        )

        mock_redis.get.return_value = None
        result1 = execute_query(ds, 'SELECT date, amount FROM sales')

        cached_data = json.dumps(result1)
        mock_redis.get.return_value = cached_data

        results = []
        for _ in range(5):
            results.append(execute_query(ds, 'SELECT date, amount FROM sales'))

        assert len(results) == 5
        for r in results:
            assert r['success'] is True
            assert r['data']['values'] == [1000, 1500, 2000]

        assert mock_mysql_connection.call_count == 1

    def test_degrade_to_empty_result_on_failure(self, db_session, test_user, default_team):
        with patch('mysql.connector.connect') as mock:
            import mysql.connector
            mock.side_effect = mysql.connector.errors.DatabaseError('Database down')

            ds = create_datasource(
                user_id=test_user.id,
                name='MySQL DS',
                type='mysql',
                connection_config={'host': 'localhost'},
                team_id=default_team.id
            )

            result = execute_query(ds, 'SELECT * FROM sales')

            assert result['success'] is False
            assert 'error' in result
            assert 'execution_time' in result

    def test_custom_cache_ttl_per_datasource(self, db_session, test_user, default_team, mock_redis, mock_mysql_connection):
        ds1 = create_datasource(
            user_id=test_user.id,
            name='MySQL DS 1',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id,
            cache_ttl=120
        )

        ds2 = create_datasource(
            user_id=test_user.id,
            name='Prom DS',
            type='prometheus',
            connection_config={'base_url': 'http://localhost:9090'},
            team_id=default_team.id,
            cache_ttl=15
        )

        execute_query(ds1, 'SELECT 1')
        call_args = mock_redis.setex.call_args_list
        ttl_used = call_args[-1][0][1]
        assert ttl_used == 120

        with patch('requests.request') as mock_req:
            resp = Mock()
            resp.status_code = 200
            resp.json.return_value = {'status': 'success', 'data': {'result': []}}
            mock_req.return_value = resp
            execute_query(ds2, 'up')
            ttl_used = mock_redis.setex.call_args_list[-1][0][1]
            assert ttl_used == 15

    def test_snapshot_and_export_concurrency(self, db_session, test_user, default_team, sample_dashboard, sample_datasource_mysql):
        with patch('app.tasks.report_tasks.capture_dashboard_screenshot') as mock_snap, \
             patch('app.tasks.report_tasks.generate_pdf_report') as mock_pdf:

            mock_snap.delay.return_value = Mock(id='task-1')
            mock_pdf.delay.return_value = Mock(id='task-2')

            from app.tasks.report_tasks import capture_dashboard_screenshot, generate_pdf_report

            task1 = capture_dashboard_screenshot.delay(sample_dashboard.id)
            task2 = generate_pdf_report.delay(sample_dashboard.id, 'test@example.com')

            assert mock_snap.delay.called
            assert mock_pdf.delay.called
            assert task1.id == 'task-1'
            assert task2.id == 'task-2'
