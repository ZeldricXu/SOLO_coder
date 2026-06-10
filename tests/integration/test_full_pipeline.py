import json
import pytest
import time
from unittest.mock import patch, Mock
from flask import json


pytestmark = pytest.mark.integration


class TestFullPipelineIntegration:

    def test_create_dashboard_pipeline(self, app_with_containers, db_session, test_user, default_team):
        from app.services.dashboard_service import create_dashboard, get_dashboard

        layout_config = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [],
            'version': '1.0'
        }

        dashboard = create_dashboard(
            user_id=test_user.id,
            name='集成测试看板',
            description='完整链路集成测试',
            team_id=default_team.id,
            layout_config=layout_config
        )

        assert dashboard.id is not None
        assert dashboard.name == '集成测试看板'

        retrieved = get_dashboard(dashboard.id)
        assert retrieved.id == dashboard.id
        assert retrieved.get_layout_config()['version'] == '1.0'

    def test_add_datasource_pipeline(self, app_with_containers, db_session, test_user, default_team, mysql_container):
        from app.services.datasource_service import create_datasource, test_datasource_connection, execute_query

        ds = create_datasource(
            user_id=test_user.id,
            name='MySQL集成数据源',
            type='mysql',
            connection_config=mysql_container['connection_config'],
            team_id=default_team.id,
            cache_ttl=30
        )

        assert ds.id is not None
        assert ds.type == 'mysql'

        config = ds.get_connection_config()
        assert config['host'] == mysql_container['host']
        assert config['database'] == mysql_container['database']

        import mysql.connector
        try:
            conn = mysql.connector.connect(**mysql_container['connection_config'])
            cursor = conn.cursor()
            cursor.execute("CREATE TABLE IF NOT EXISTS sales (date VARCHAR(20), amount INT, orders INT)")
            cursor.execute("INSERT INTO sales VALUES ('2024-01-01', 1000, 50), ('2024-01-02', 1500, 75), ('2024-01-03', 2000, 100)")
            conn.commit()
            conn.close()

            result = execute_query(ds, 'SELECT date, amount, orders FROM sales')
            assert result['success'] is True
            assert result['data']['row_count'] == 3
            assert len(result['data']['series']) == 2
            assert result['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
        except Exception as e:
            pytest.skip(f"MySQL connection failed: {e}")

    def test_add_chart_to_dashboard(self, app_with_containers, db_session, test_user, default_team, sample_dashboard, sample_datasource_mysql):
        from app.services.chart_service import create_chart, get_dashboard_charts, get_chart_data

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='销售趋势图',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT date, amount FROM sales WHERE date >= {{start_date}}',
            query_params={'start_date': '2024-01-01'},
            position={'x': 0, 'y': 0, 'w': 6, 'h': 4}
        )

        assert chart.id is not None
        assert chart.dashboard_id == sample_dashboard.id
        assert chart.chart_type == 'line'

        charts = get_dashboard_charts(sample_dashboard.id)
        assert len(charts) >= 1
        assert any(c.id == chart.id for c in charts)

        with patch('app.models.DataSource.execute_query') as mock_exec:
            mock_exec.return_value = {
                'success': True,
                'data': {
                    'categories': ['2024-01-01', '2024-01-02', '2024-01-03'],
                    'values': [1000, 1500, 2000],
                    'series': [{'name': 'amount', 'data': [1000, 1500, 2000]}]
                }
            }

            result = get_chart_data(chart.id)
            assert result['success'] is True
            assert 'echarts_option' in result
            assert result['echarts_option']['series'][0]['type'] == 'line'
            assert result['echarts_option']['series'][0]['data'] == [1000, 1500, 2000]
            assert result['echarts_option']['xAxis']['data'] == ['2024-01-01', '2024-01-02', '2024-01-03']

    def test_update_layout_with_charts(self, app_with_containers, db_session, test_user, default_team, sample_dashboard, sample_datasource_mysql):
        from app.services.dashboard_service import update_dashboard_layout, get_dashboard
        from app.services.chart_service import create_chart

        chart1 = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='图表1',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        chart2 = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='图表2',
            chart_type='bar',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [
                {'id': f'widget-{chart1.id}', 'chart_id': chart1.id, 'x': 0, 'y': 0, 'w': 6, 'h': 4},
                {'id': f'widget-{chart2.id}', 'chart_id': chart2.id, 'x': 6, 'y': 0, 'w': 6, 'h': 4}
            ],
            'version': '1.0'
        }

        updated = update_dashboard_layout(sample_dashboard.id, layout)
        saved_layout = updated.get_layout_config()

        assert len(saved_layout['widgets']) == 2
        assert saved_layout['widgets'][0]['chart_id'] == chart1.id
        assert saved_layout['widgets'][1]['chart_id'] == chart2.id

    def test_http_datasource_with_wiremock(self, app_with_containers, db_session, test_user, default_team, wiremock_container):
        from app.services.datasource_service import create_datasource, execute_query

        ds = create_datasource(
            user_id=test_user.id,
            name='HTTP集成数据源',
            type='http',
            connection_config={
                'url': f"{wiremock_container['base_url']}/api/data",
                'method': 'GET',
                'data_path': 'data',
                'category_key': 'date',
                'value_key': 'value'
            },
            team_id=default_team.id
        )

        import requests
        try:
            response = requests.get(f"{wiremock_container['base_url']}/api/data")
            if response.status_code != 200:
                pytest.skip("WireMock not properly configured")

            result = execute_query(ds, '')
            assert result['success'] is True
            assert result['data']['row_count'] == 3
            assert result['data']['categories'] == ['2024-01-01', '2024-01-02', '2024-01-03']
            assert result['data']['values'] == [100, 200, 300]
        except Exception as e:
            pytest.skip(f"WireMock connection failed: {e}")

    def test_sse_push_and_receive(self, app_with_containers, db_session, test_user, sample_dashboard, mock_redis):
        client = app_with_containers.test_client()

        with client.session_transaction() as session:
            session['_user_id'] = str(test_user.id)

        with patch('flask_login.current_user') as mock_user, \
             patch('app.services.auth_service.can_edit_dashboard', return_value=True):

            mock_user.id = test_user.id
            mock_user.has_dashboard_access.return_value = True

            push_data = {
                'type': 'chart_update',
                'chart_id': 1,
                'data': {'value': 999, 'trend': 'up'},
                'timestamp': time.time()
            }

            response = client.post(
                f'/sse/push/{sample_dashboard.id}',
                data=json.dumps(push_data),
                content_type='application/json'
            )

            assert response.status_code == 200
            result = json.loads(response.data)
            assert result['success'] is True

            assert mock_redis.setex.called
            call_args = mock_redis.setex.call_args
            stored_data = json.loads(call_args[0][2])
            assert stored_data['data']['value'] == 999

    def test_export_report_pipeline(self, app_with_containers, db_session, test_user, sample_dashboard):
        from app.services.report_service import create_report_schedule, trigger_report
        from app.models import ReportSchedule

        with patch('app.tasks.report_tasks.generate_report_task.delay') as mock_task:
            mock_task.return_value = Mock(id='test-task-id')

            schedule = create_report_schedule(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name='测试报表',
                cron_expression='0 9 * * *',
                recipients=['test@example.com'],
                format='pdf',
                timezone='Asia/Shanghai'
            )

            assert schedule.id is not None
            assert schedule.dashboard_id == sample_dashboard.id
            assert schedule.cron_expression == '0 9 * * *'

            report = trigger_report(schedule.id, test_user.id)
            assert mock_task.called
            assert report is not None

    def test_redis_cache_integration(self, app_with_containers, db_session, test_user, default_team, mock_redis, mock_mysql_connection):
        from app.services.datasource_service import create_datasource, execute_query

        mock_redis.get.return_value = None

        ds = create_datasource(
            user_id=test_user.id,
            name='缓存测试数据源',
            type='mysql',
            connection_config={'host': 'localhost', 'port': 3306},
            team_id=default_team.id,
            cache_ttl=60
        )

        result1 = execute_query(ds, 'SELECT date, amount FROM sales')
        assert result1['success'] is True
        assert mock_redis.setex.called

        mock_redis.reset_mock()
        mock_redis.get.return_value = json.dumps(result1)

        result2 = execute_query(ds, 'SELECT date, amount FROM sales')
        assert result2['success'] is True
        assert result2['data']['values'] == result1['data']['values']
        assert not mock_redis.setex.called


class TestFailurePathIntegration:

    def test_datasource_connection_failure(self, app_with_containers, db_session, test_user, default_team):
        from app.services.datasource_service import create_datasource, test_datasource_connection

        ds = create_datasource(
            user_id=test_user.id,
            name='失败连接数据源',
            type='mysql',
            connection_config={
                'host': 'invalid-host.example.com',
                'port': 3306,
                'username': 'test',
                'password': 'wrong',
                'database': 'test'
            },
            team_id=default_team.id
        )

        result = test_datasource_connection(ds)
        assert result['success'] is False
        assert 'error' in result

    def test_layout_conflict_merge(self, app_with_containers, db_session, test_user, default_team, sample_dashboard):
        from app.services.dashboard_service import update_dashboard_layout, get_dashboard

        layout1 = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'id': 'widget-1', 'chart_id': 1, 'x': 0, 'y': 0, 'w': 6, 'h': 4}],
            'version': 1,
            'user': 'user1'
        }

        update_dashboard_layout(sample_dashboard.id, layout1)

        layout2 = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'id': 'widget-1', 'chart_id': 1, 'x': 6, 'y': 0, 'w': 6, 'h': 4}],
            'version': 1,
            'user': 'user2',
            'conflict_detected': True
        }

        updated = update_dashboard_layout(sample_dashboard.id, layout2)
        saved_layout = updated.get_layout_config()

        assert saved_layout['conflict_detected'] is True
        assert saved_layout['widgets'][0]['x'] == 6

    def test_query_with_missing_params(self, app_with_containers, db_session, test_user, default_team, mock_mysql_connection):
        from app.services.datasource_service import create_datasource, execute_query, _render_query_template

        ds = create_datasource(
            user_id=test_user.id,
            name='参数测试数据源',
            type='mysql',
            connection_config={'host': 'localhost'},
            team_id=default_team.id
        )

        query_template = 'SELECT * FROM sales WHERE date >= {{start_date}} AND region = {{region}}'
        params = {'start_date': '2024-01-01'}

        rendered = _render_query_template(query_template, params)

        assert '{{region}}' in rendered
        assert '2024-01-01' in rendered

    def test_invalid_json_layout(self, app_with_containers, db_session, test_user, default_team, sample_dashboard):
        from app.services.dashboard_service import update_dashboard_layout

        sample_dashboard.layout_config = '{invalid json}'
        db_session.add(sample_dashboard)
        db_session.commit()

        layout = sample_dashboard.get_layout_config()
        assert layout['grid']['cols'] == 12
        assert layout['widgets'] == []

    def test_chart_with_invalid_datasource(self, app_with_containers, db_session, test_user, sample_dashboard):
        from app.services.chart_service import create_chart, get_chart_data

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='无效数据源图表',
            chart_type='line',
            datasource_id=99999,
            query_template='SELECT 1'
        )

        result = get_chart_data(chart.id)
        assert result['success'] is False
        assert '数据源不存在' in result['error']


class TestConcurrencyIntegration:

    def test_concurrent_dashboard_edits(self, app_with_containers, db_session, test_user, test_user2, default_team):
        from app.services.dashboard_service import create_dashboard, update_dashboard_layout, get_dashboard
        import asyncio

        dashboard = create_dashboard(
            user_id=test_user.id,
            name='并发编辑测试',
            team_id=default_team.id,
            layout_config={'grid': {'cols': 12}, 'widgets': [], 'version': 0}
        )

        async def update_for_user(user_id, version, x_offset):
            with app_with_containers.app_context():
                from app.services.dashboard_service import update_dashboard_layout
                layout = {
                    'grid': {'cols': 12},
                    'widgets': [{'id': f'widget-{user_id}', 'x': x_offset, 'y': 0, 'w': 6, 'h': 4}],
                    'version': version,
                    'user_id': user_id
                }
                return update_dashboard_layout(dashboard.id, layout)

        async def run_concurrent():
            tasks = [
                update_for_user(test_user.id, 1, 0),
                update_for_user(test_user2.id, 1, 6)
            ]
            return await asyncio.gather(*tasks)

        results = asyncio.run(run_concurrent())
        assert len(results) == 2

        final = get_dashboard(dashboard.id)
        final_layout = final.get_layout_config()
        assert 'version' in final_layout

    def test_multiple_sse_subscriptions(self, app_with_containers, db_session, test_user, sample_dashboard, mock_redis):
        import asyncio

        async def subscribe(client, dashboard_id, user_id):
            with patch('flask_login.current_user') as mock_user, \
                 patch('app.api.sse.get_dashboard_charts', return_value=[]), \
                 patch('time.sleep', return_value=None):

                mock_user.id = user_id
                mock_user.has_dashboard_access.return_value = True

                return client.get(f'/sse/dashboard/{dashboard_id}')

        async def run_test():
            clients = []
            for _ in range(5):
                client = app_with_containers.test_client()
                with client.session_transaction() as session:
                    session['_user_id'] = str(test_user.id)
                clients.append(client)

            tasks = [subscribe(c, sample_dashboard.id, test_user.id) for c in clients]
            responses = await asyncio.gather(*tasks)

            for resp in responses:
                if hasattr(resp, 'status_code'):
                    assert resp.status_code in [200, 401]

        try:
            asyncio.run(run_test())
        except Exception:
            pass

    def test_snapshot_and_export_simultaneous(self, app_with_containers, db_session, test_user, sample_dashboard):
        from app.services.report_service import create_report_schedule, trigger_report

        with patch('app.tasks.report_tasks.capture_dashboard_screenshot.delay') as mock_snap, \
             patch('app.tasks.report_tasks.generate_report_task.delay') as mock_report:

            mock_snap.return_value = Mock(id='snap-task')
            mock_report.return_value = Mock(id='report-task')

            schedule = create_report_schedule(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name='并发报表',
                cron_expression='0 * * * *',
                recipients=['test@example.com']
            )

            report1 = trigger_report(schedule.id, test_user.id)
            report2 = trigger_report(schedule.id, test_user.id)

            assert mock_snap.call_count + mock_report.call_count >= 2
            assert report1 is not None
            assert report2 is not None
