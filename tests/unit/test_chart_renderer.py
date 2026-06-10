import json
import pytest
from unittest.mock import patch, Mock
from app.services.chart_service import (
    create_chart, update_chart, get_chart_data,
    get_chart_echarts_option, get_dashboard_charts
)
from app.models import Chart


class TestChartRendererNormal:

    def test_line_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql, sample_query_result):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='销售趋势',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT date, amount FROM sales'
        )

        option = chart.get_echarts_option(sample_query_result['data'])

        assert option['xAxis']['type'] == 'category'
        assert option['xAxis']['data'] == ['2024-01-01', '2024-01-02', '2024-01-03']
        assert option['series'][0]['type'] == 'line'
        assert option['series'][0]['data'] == [1000, 1500, 2000]
        assert 'tooltip' in option
        assert 'legend' in option

    def test_bar_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql, sample_query_result):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='月度销量',
            chart_type='bar',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT month, sales FROM stats'
        )

        option = chart.get_echarts_option(sample_query_result['data'])

        assert option['series'][0]['type'] == 'bar'
        assert option['xAxis']['data'] == sample_query_result['data']['categories']
        assert option['series'][0]['data'] == [1000, 1500, 2000]

    def test_pie_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql, sample_query_result):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='渠道占比',
            chart_type='pie',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT channel, amount FROM sales_channels'
        )

        option = chart.get_echarts_option(sample_query_result['data'])

        assert option['series'][0]['type'] == 'pie'
        assert len(option['series'][0]['data']) == 3
        assert option['series'][0]['data'][0] == {'name': '2024-01-01', 'value': 1000}
        assert option['series'][0]['data'][1] == {'name': '2024-01-02', 'value': 1500}
        assert option['series'][0]['data'][2] == {'name': '2024-01-03', 'value': 2000}
        assert option['legend']['orient'] == 'vertical'

    def test_heatmap_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='工单热力图',
            chart_type='heatmap',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT hour, day, count FROM tickets'
        )

        heatmap_data = {
            'categories': ['00:00', '04:00', '08:00', '12:00'],
            'yCategories': ['周一', '周二', '周三'],
            'values': [
                [0, 0, 5], [0, 1, 10], [0, 2, 3],
                [1, 0, 8], [1, 1, 15], [1, 2, 6],
                [2, 0, 12], [2, 1, 20], [2, 2, 8],
                [3, 0, 6], [3, 1, 10], [3, 2, 4]
            ],
            'series': []
        }

        option = chart.get_echarts_option(heatmap_data)

        assert option['series'][0]['type'] == 'heatmap'
        assert option['xAxis']['type'] == 'category'
        assert option['yAxis']['type'] == 'category'
        assert 'visualMap' in option
        assert option['visualMap']['min'] == 0

    def test_funnel_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='用户转化漏斗',
            chart_type='funnel',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT stage, users FROM funnel'
        )

        funnel_data = {
            'categories': ['浏览', '注册', '加购', '下单', '支付'],
            'values': [10000, 5000, 2000, 1000, 800],
            'series': []
        }

        option = chart.get_echarts_option(funnel_data)

        assert option['series'][0]['type'] == 'funnel'
        assert len(option['series'][0]['data']) == 5
        assert option['series'][0]['data'][0] == {'name': '浏览', 'value': 10000}
        assert option['series'][0]['data'][4] == {'name': '支付', 'value': 800}

    def test_scatter_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='用户活跃度散点',
            chart_type='scatter',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT x, y FROM user_activity'
        )

        scatter_data = {
            'categories': [],
            'values': [[10, 20], [15, 35], [25, 50], [30, 45], [40, 60]],
            'series': []
        }

        option = chart.get_echarts_option(scatter_data)

        assert option['series'][0]['type'] == 'scatter'
        assert option['series'][0]['symbolSize'] == 10
        assert option['xAxis']['type'] == 'value'
        assert option['yAxis']['type'] == 'value'

    def test_gauge_chart_option_generation(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='完成率',
            chart_type='gauge',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT completion_rate FROM kpi'
        )

        gauge_data = {
            'categories': [],
            'values': [78.5],
            'series': []
        }

        option = chart.get_echarts_option(gauge_data)

        assert option['series'][0]['type'] == 'gauge'
        assert option['series'][0]['progress']['show'] is True
        assert option['series'][0]['data'][0]['value'] == 78.5
        assert option['series'][0]['data'][0]['name'] == '完成率'

    def test_multiple_series_data_binding(self, db_session, test_user, sample_dashboard, sample_datasource_mysql, sample_query_result):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='多指标对比',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT date, amount, orders FROM sales'
        )

        option = chart.get_echarts_option(sample_query_result['data'])

        assert option['series'][0]['type'] == 'line'
        assert option['series'][0]['data'] == [1000, 1500, 2000]
        assert option['xAxis']['data'] == ['2024-01-01', '2024-01-02', '2024-01-03']

    def test_all_seven_chart_types_supported(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart_types = ['line', 'bar', 'pie', 'heatmap', 'funnel', 'scatter', 'gauge']
        for chart_type in chart_types:
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'{chart_type}测试',
                chart_type=chart_type,
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1'
            )
            assert chart.chart_type == chart_type
            assert chart.CHART_TYPES[chart_type] is not None

            config = chart.get_chart_config()
            assert 'series' in config
            assert config['series'][0]['type'] == chart_type

    def test_chart_config_override(self, db_session, test_user, sample_dashboard, sample_datasource_mysql, sample_query_result):
        custom_config = {
            'title': {'text': '自定义标题', 'left': 'center'},
            'tooltip': {'trigger': 'axis', 'backgroundColor': 'rgba(0,0,0,0.8)'},
            'color': ['#ff0000', '#00ff00', '#0000ff']
        }

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='自定义配置图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1',
            chart_config=custom_config
        )

        option = chart.get_echarts_option(sample_query_result['data'])

        assert option['title']['text'] == '自定义标题'
        assert option['title']['left'] == 'center'
        assert option['tooltip']['backgroundColor'] == 'rgba(0,0,0,0.8)'
        assert option['color'] == ['#ff0000', '#00ff00', '#0000ff']

    def test_get_chart_data_returns_echarts_option(self, db_session, test_user, sample_dashboard, sample_chart, mock_mysql_connection):
        with patch('app.models.DataSource.execute_query') as mock_exec:
            mock_exec.return_value = {
                'success': True,
                'data': {
                    'categories': ['A', 'B', 'C'],
                    'values': [10, 20, 30],
                    'series': [{'name': 'value', 'data': [10, 20, 30]}]
                }
            }

            result = get_chart_data(sample_chart.id)

            assert result['success'] is True
            assert 'echarts_option' in result
            assert result['echarts_option']['series'][0]['data'] == [10, 20, 30]
            assert result['echarts_option']['xAxis']['data'] == ['A', 'B', 'C']

    def test_empty_data_handles_gracefully(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='空数据图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1 WHERE 1=0'
        )

        empty_data = {
            'categories': [],
            'values': [],
            'series': []
        }

        option = chart.get_echarts_option(empty_data)

        assert option['xAxis']['data'] == []
        assert option['series'][0]['data'] == []


class TestChartRendererException:

    def test_unsupported_chart_type(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        with pytest.raises(ValueError, match='不支持的图表类型'):
            create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name='不支持的类型',
                chart_type='radar',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1'
            )

    def test_invalid_chart_config_json(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='测试图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        chart.chart_config = 'invalid json'
        db_session.add(chart)
        db_session.commit()

        config = chart.get_chart_config()
        assert 'series' in config
        assert config['series'][0]['type'] == 'line'

    def test_missing_datasource_returns_empty_data(self, db_session, test_user, sample_dashboard):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='无数据源图表',
            chart_type='line',
            datasource_id=None,
            query_template=None
        )

        result = get_chart_data(chart.id)
        assert result['success'] is True
        assert result['data']['categories'] == []
        assert result['data']['values'] == []

    def test_chart_type_mismatch_data_format(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='饼图测试',
            chart_type='pie',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        mismatched_data = {
            'categories': [],
            'values': [],
            'series': []
        }

        option = chart.get_echarts_option(mismatched_data)
        assert option['series'][0]['data'] == []
        assert option['series'][0]['type'] == 'pie'

    def test_update_nonexistent_chart(self, db_session):
        with pytest.raises(ValueError, match='图表不存在'):
            update_chart(99999, name='新名称')

    def test_get_nonexistent_chart_data(self, db_session):
        with pytest.raises(ValueError, match='图表不存在'):
            get_chart_data(99999)

    def test_invalid_position_json(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='测试图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        chart.position = 'not valid json'
        db_session.add(chart)
        db_session.commit()

        position = chart.get_position()
        assert position['x'] == 0
        assert position['y'] == 0
        assert position['w'] == 6
        assert position['h'] == 4

    def test_query_params_invalid_json(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='测试图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        chart.query_params = 'invalid json'
        db_session.add(chart)
        db_session.commit()

        params = chart.get_query_params()
        assert params == {}


class TestChartRendererConcurrency:

    @pytest.mark.asyncio
    async def test_concurrent_chart_creation(self, app, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        async def create_chart_async(name, chart_type):
            with app.app_context():
                return create_chart(
                    user_id=test_user.id,
                    dashboard_id=sample_dashboard.id,
                    name=name,
                    chart_type=chart_type,
                    datasource_id=sample_datasource_mysql.id,
                    query_template='SELECT 1'
                )

        import asyncio
        tasks = [
            create_chart_async(f'图表{i}', 'line')
            for i in range(10)
        ]

        results = await asyncio.gather(*tasks)
        assert len(results) == 10
        for chart in results:
            assert chart.id is not None
            assert chart.chart_type == 'line'

        charts = get_dashboard_charts(sample_dashboard.id)
        assert len(charts) >= 10

    def test_many_charts_per_dashboard(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        charts = []
        for i in range(50):
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'图表{i+1}',
                chart_type='bar',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1',
                position={'x': (i % 10) * 1.2, 'y': (i // 10) * 4, 'w': 1, 'h': 4}
            )
            charts.append(chart)

        assert len(charts) == 50

        dashboard_charts = get_dashboard_charts(sample_dashboard.id)
        assert len(dashboard_charts) == 50

    def test_chart_config_preserves_extra_fields(self, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        custom_config = {
            'title': {'text': '测试'},
            'tooltip': {'trigger': 'axis'},
            'custom_field': 'should_be_preserved',
            'animation': True,
            'legend': {'data': [], 'custom_legend_option': 'preserved'}
        }

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='配置测试',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1',
            chart_config=custom_config
        )

        saved_config = chart.get_chart_config()
        assert saved_config['custom_field'] == 'should_be_preserved'
        assert saved_config['animation'] is True
        assert saved_config['legend']['custom_legend_option'] == 'preserved'
