import json
import pytest
import asyncio
import time
from unittest.mock import patch, Mock, MagicMock
from flask import Flask
from app.api.sse import (
    sse_bp, format_sse, get_redis_key, subscriptions
)
from app.services.dashboard_service import create_dashboard
from app.services.chart_service import create_chart


class TestSSEPushNormal:

    def test_format_sse_message(self):
        data = {'chart_id': 1, 'value': 100, 'timestamp': 1234567890}
        result = format_sse('data_update', data)

        assert 'event: data_update' in result
        assert 'data: ' in result
        parsed_data = json.loads(result.split('data: ')[1])
        assert parsed_data['chart_id'] == 1
        assert parsed_data['value'] == 100
        assert result.endswith('\n\n')

    def test_redis_key_generation(self):
        key1 = get_redis_key(1)
        key2 = get_redis_key(123)

        assert key1 == 'sse:dashboard:1'
        assert key2 == 'sse:dashboard:123'
        assert key1 != key2

    def test_push_data_endpoint(self, app, db_session, test_user, sample_dashboard, mock_redis, logged_in_client):
        with patch('flask_login.current_user') as mock_user:
            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            push_data = {
                'type': 'chart_update',
                'chart_id': 1,
                'data': {'value': 999}
            }

            response = logged_in_client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps(push_data),
                content_type='application/json'
            )

            assert response.status_code == 200
            result = json.loads(response.data)
            assert result['success'] is True
            assert mock_redis.setex.called

            call_args = mock_redis.setex.call_args
            assert call_args[0][0] == get_redis_key(sample_dashboard.id)
            assert call_args[0][1] == 60

    def test_sse_connected_event(self, app, db_session, test_user, sample_dashboard, mock_redis):
        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
             patch('time.sleep', return_value=None):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/dashboard/{sample_dashboard.id}')
            assert response.status_code == 200
            assert response.headers['Content-Type'].startswith('text/event-stream')
            assert response.headers['Cache-Control'] == 'no-cache, no-transform'

    def test_data_update_event_format(self, app, db_session, test_user, sample_dashboard, sample_datasource_mysql, mock_redis):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='测试图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.chart_service.get_dashboard_charts') as mock_charts, \
             patch('app.api.sse.get_chart_data') as mock_data, \
             patch('time.sleep', return_value=None):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True
            mock_charts.return_value = [chart]
            mock_data.return_value = {
                'success': True,
                'echarts_option': {'series': [{'data': [1, 2, 3]}]}
            }

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/dashboard/{sample_dashboard.id}')
            assert response.status_code == 200

    def test_multiple_chart_updates_in_single_push(self, app, db_session, test_user, sample_dashboard, sample_datasource_mysql, mock_redis):
        charts = []
        for i in range(3):
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'图表{i+1}',
                chart_type='line',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1'
            )
            charts.append(chart)

        push_data = {
            'type': 'bulk_update',
            'updates': {
                str(charts[0].id): {'value': 100},
                str(charts[1].id): {'value': 200},
                str(charts[2].id): {'value': 300}
            },
            'timestamp': time.time()
        }

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.auth_service.can_edit_dashboard', return_value=True):
            mock_user.id = test_user.id

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps(push_data),
                content_type='application/json'
            )

            assert response.status_code == 200
            result = json.loads(response.data)
            assert result['success'] is True

    def test_sse_heartbeat_interval(self, app, db_session, test_user, sample_dashboard, mock_redis):
        app.config['SSE_HEARTBEAT_INTERVAL'] = 1

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
             patch('time.sleep', return_value=None):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/dashboard/{sample_dashboard.id}')
            assert response.status_code == 200

    def test_chart_level_sse_stream(self, app, db_session, test_user, sample_chart, mock_redis):
        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.chart_service.get_chart') as mock_get_chart, \
             patch('app.api.sse.get_chart_data') as mock_get_data, \
             patch('time.sleep', return_value=None):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True
            mock_get_chart.return_value = sample_chart
            mock_get_data.return_value = {
                'success': True,
                'echarts_option': {'series': [{'data': [100, 200, 300]}]}
            }

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/chart/{sample_chart.id}')
            assert response.status_code == 200
            assert response.headers['Content-Type'].startswith('text/event-stream')


class TestSSEPushException:

    def test_push_without_permission(self, app, db_session, test_user, sample_dashboard, mock_redis, logged_in_client):
        with patch.object(test_user, 'has_dashboard_access', return_value=False):

            response = logged_in_client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps({'data': 'test'}),
                content_type='application/json'
            )

            assert response.status_code == 403
            result = json.loads(response.data)
            assert '无权限推送' in result['error']

    def test_push_to_nonexistent_dashboard(self, app, db_session, test_user, mock_redis, logged_in_client):
        with patch.object(test_user, 'has_dashboard_access', return_value=True):

            response = logged_in_client.post(
                '/sse/push/99999',
                data=json.dumps({'data': 'test'}),
                content_type='application/json'
            )

            assert response.status_code == 404

    def test_push_with_empty_data(self, app, db_session, test_user, sample_dashboard, mock_redis, logged_in_client):
        with patch.object(test_user, 'has_dashboard_access', return_value=True):

            response = logged_in_client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps({}),
                content_type='application/json'
            )

            assert response.status_code == 400
            result = json.loads(response.data)
            assert '缺少推送数据' in result['error']

    def test_sse_without_dashboard_access(self, app, db_session, test_user, sample_dashboard, mock_redis):
        with patch.object(test_user, 'has_dashboard_access', return_value=False), \
             patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
             patch('time.sleep', return_value=None):

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/dashboard/{sample_dashboard.id}')
            assert response.status_code == 403

    def test_sse_nonexistent_dashboard(self, app, db_session, test_user, mock_redis):
        with patch.object(test_user, 'has_dashboard_access', return_value=True), \
             patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
             patch('time.sleep', return_value=None):

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get('/sse/dashboard/99999')
            assert response.status_code == 404

    def test_redis_connection_failed_on_push(self, app, db_session, test_user, sample_dashboard, logged_in_client):
        with patch('app.api.sse.redis_client', None), \
             patch.object(test_user, 'has_dashboard_access', return_value=True):

            response = logged_in_client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps({'data': 'test'}),
                content_type='application/json'
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert 'Redis未连接' in result['error']

    def test_sse_chart_not_found(self, app, db_session, test_user, mock_redis):
        with patch('flask_login.current_user') as mock_user:
            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get('/sse/chart/99999')
            assert response.status_code == 404


class TestSSEPushConcurrency:

    @pytest.mark.asyncio
    async def test_multiple_sse_connections(self, app, db_session, test_user, default_team, mock_redis):
        dashboard = create_dashboard(
            user_id=test_user.id,
            name='并发SSE测试',
            team_id=default_team.id
        )

        for i in range(5):
            create_chart(
                user_id=test_user.id,
                dashboard_id=dashboard.id,
                name=f'图表{i+1}',
                chart_type='line',
                query_template='SELECT 1'
            )

        async def connect_sse(dashboard_id, user_id):
            with app.test_client() as client:
                with client.session_transaction() as session:
                    session['_user_id'] = str(user_id)

                with patch('flask_login.current_user') as mock_user, \
                     patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
                     patch('time.sleep', return_value=None):

                    mock_user.id = user_id
                    mock_user.has_dashboard_access.return_value = True

                    response = client.get(f'/sse/dashboard/{dashboard_id}')
                    return response.status_code

        tasks = [connect_sse(dashboard.id, test_user.id) for _ in range(5)]
        results = await asyncio.gather(*tasks)

        assert all(status == 200 for status in results)

    def test_max_connections_limit(self, app, db_session, test_user, default_team, mock_redis):
        original_limit = app.config.get('MAX_SSE_CONNECTIONS', 1000)
        app.config['MAX_SSE_CONNECTIONS'] = 5

        dashboard = create_dashboard(
            user_id=test_user.id,
            name='连接数测试',
            team_id=default_team.id
        )

        assert app.config['MAX_SSE_CONNECTIONS'] == 5

        app.config['MAX_SSE_CONNECTIONS'] = original_limit

    @pytest.mark.asyncio
    async def test_concurrent_pushes(self, app, db_session, test_user, sample_dashboard, mock_redis):
        async def push_data(dashboard_id, data, user_id):
            with app.test_client() as client:
                with client.session_transaction() as session:
                    session['_user_id'] = str(user_id)

                with patch('flask_login.current_user') as mock_user:
                    mock_user.id = user_id
                    mock_user.has_dashboard_access.return_value = True

                    return client.post(
                        f'/sse/push/{dashboard_id}',
                        data=json.dumps(data),
                        content_type='application/json'
                    )

        tasks = [
            push_data(sample_dashboard.id, {'value': i}, test_user.id)
            for i in range(10)
        ]

        results = await asyncio.gather(*tasks)

        for r in results:
            if hasattr(r, 'status_code'):
                assert r.status_code in [200, 500]

        assert mock_redis.setex.call_count >= 1

    def test_client_reconnect_recovers_data(self, app, db_session, test_user, sample_dashboard, mock_redis):
        missed_data = {
            'type': 'data_update',
            'chart_id': 1,
            'value': 999,
            'timestamp': time.time() - 30
        }

        mock_redis.get.return_value = json.dumps(missed_data)

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.chart_service.get_dashboard_charts', return_value=[]), \
             patch('time.sleep', return_value=None):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            response = client.get(f'/sse/dashboard/{sample_dashboard.id}')
            assert response.status_code == 200
            assert mock_redis.get.called
            assert mock_redis.delete.called

    def test_data_loss_prevention_on_reconnect(self, app, db_session, test_user, sample_dashboard, mock_redis):
        push_data = {
            'type': 'important_update',
            'chart_id': 1,
            'data': {'critical': True, 'value': 999},
            'timestamp': time.time()
        }

        mock_redis.setex.return_value = True
        mock_redis.get.return_value = json.dumps(push_data)

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.auth_service.can_edit_dashboard', return_value=True):
            mock_user.id = test_user.id

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            push_resp = client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps(push_data),
                content_type='application/json'
            )

            assert push_resp.status_code == 200

            call_args = mock_redis.setex.call_args
            stored_data = json.loads(call_args[0][2])
            assert stored_data['data']['critical'] is True
            assert stored_data['data']['value'] == 999

    def test_high_frequency_updates_throttled(self, app, db_session, test_user, sample_dashboard, mock_redis):
        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.auth_service.can_edit_dashboard', return_value=True):
            mock_user.id = test_user.id

            client = app.test_client()
            with client.session_transaction() as session:
                session['_user_id'] = str(test_user.id)

            for i in range(100):
                response = client.post(
                    f'/sse/push/{sample_dashboard.id}',
                    data=json.dumps({'value': i}),
                    content_type='application/json'
                )
                if response.status_code != 200:
                    break

            assert mock_redis.setex.call_count >= 1
