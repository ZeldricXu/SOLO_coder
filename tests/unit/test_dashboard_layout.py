import json
import pytest
import asyncio
from datetime import datetime
from unittest.mock import patch, MagicMock
from app.services.dashboard_service import (
    create_dashboard, update_dashboard_layout, update_dashboard,
    get_dashboard, copy_dashboard
)
from app.services.chart_service import (
    create_chart, update_chart_position, batch_update_chart_positions
)


class TestDashboardLayoutNormal:

    def test_create_dashboard_with_empty_layout(self, db_session, test_user, default_team):
        layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [],
            'version': '1.0'
        }

        dashboard = create_dashboard(
            user_id=test_user.id,
            name='空布局看板',
            team_id=default_team.id,
            layout_config=layout
        )

        assert dashboard.id is not None
        assert dashboard.name == '空布局看板'
        saved_layout = dashboard.get_layout_config()
        assert saved_layout['grid']['cols'] == 12
        assert saved_layout['widgets'] == []
        assert saved_layout['version'] == '1.0'

    def test_drag_and_drop_widget_serialization(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        widgets = [
            {
                'id': f'widget-{sample_chart.id}',
                'chart_id': sample_chart.id,
                'x': 0, 'y': 0, 'w': 6, 'h': 4,
                'minW': 3, 'minH': 2
            }
        ]
        layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': widgets,
            'version': '1.0'
        }

        updated = update_dashboard_layout(sample_dashboard.id, layout)
        saved_layout = updated.get_layout_config()

        assert len(saved_layout['widgets']) == 1
        widget = saved_layout['widgets'][0]
        assert widget['chart_id'] == sample_chart.id
        assert widget['x'] == 0
        assert widget['y'] == 0
        assert widget['w'] == 6
        assert widget['h'] == 4
        assert widget['minW'] == 3
        assert widget['minH'] == 2

    def test_multiple_widgets_drag_sequence(self, db_session, test_user, default_team, sample_dashboard, sample_datasource_mysql):
        charts = []
        for i in range(3):
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'图表{i+1}',
                chart_type='line',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1',
                position={'x': i * 4, 'y': 0, 'w': 4, 'h': 4}
            )
            charts.append(chart)

        widgets = []
        for i, chart in enumerate(charts):
            widgets.append({
                'id': f'widget-{chart.id}',
                'chart_id': chart.id,
                'x': i * 4, 'y': 0, 'w': 4, 'h': 4
            })

        widgets[0]['x'] = 6
        widgets[0]['y'] = 4
        widgets[1]['w'] = 8

        layout = {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': widgets}
        updated = update_dashboard_layout(sample_dashboard.id, layout)
        saved_layout = updated.get_layout_config()

        assert len(saved_layout['widgets']) == 3
        assert saved_layout['widgets'][0]['x'] == 6
        assert saved_layout['widgets'][0]['y'] == 4
        assert saved_layout['widgets'][1]['w'] == 8

    def test_widget_resize_serialization(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        initial_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'id': f'widget-{sample_chart.id}', 'chart_id': sample_chart.id, 'x': 0, 'y': 0, 'w': 6, 'h': 4}]
        }
        update_dashboard_layout(sample_dashboard.id, initial_layout)

        resized_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'id': f'widget-{sample_chart.id}', 'chart_id': sample_chart.id, 'x': 0, 'y': 0, 'w': 12, 'h': 8}]
        }
        updated = update_dashboard_layout(sample_dashboard.id, resized_layout)
        saved_layout = updated.get_layout_config()

        assert saved_layout['widgets'][0]['w'] == 12
        assert saved_layout['widgets'][0]['h'] == 8

    def test_layout_json_serialization_format(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{
                'id': f'widget-{sample_chart.id}',
                'chart_id': sample_chart.id,
                'x': 0, 'y': 0, 'w': 6, 'h': 4,
                'metadata': {'title': '销售趋势', 'refreshRate': 30}
            }],
            'settings': {'theme': 'dark', 'autoRefresh': True},
            'version': '1.0',
            'createdAt': datetime.utcnow().isoformat()
        }

        update_dashboard_layout(sample_dashboard.id, layout)
        dashboard = get_dashboard(sample_dashboard.id)
        saved_layout = dashboard.get_layout_config()

        assert 'grid' in saved_layout
        assert 'widgets' in saved_layout
        assert saved_layout['widgets'][0]['metadata']['title'] == '销售趋势'
        assert saved_layout['settings']['theme'] == 'dark'
        assert saved_layout['version'] == '1.0'

    def test_batch_update_chart_positions(self, db_session, test_user, default_team, sample_dashboard, sample_datasource_mysql):
        charts = []
        for i in range(3):
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'图表{i+1}',
                chart_type='bar',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1'
            )
            charts.append(chart)

        positions = [
            {'id': charts[0].id, 'x': 0, 'y': 0, 'w': 6, 'h': 4},
            {'id': charts[1].id, 'x': 6, 'y': 0, 'w': 6, 'h': 4},
            {'id': charts[2].id, 'x': 0, 'y': 4, 'w': 12, 'h': 4}
        ]

        result = batch_update_chart_positions(sample_dashboard.id, positions)
        assert result is True

        for pos in positions:
            chart = next(c for c in charts if c.id == pos['id'])
            chart_pos = chart.get_position()
            assert chart_pos['x'] == pos['x']
            assert chart_pos['y'] == pos['y']
            assert chart_pos['w'] == pos['w']
            assert chart_pos['h'] == pos['h']

    def test_layout_persistence_after_copy(self, db_session, test_user, test_user2, sample_layout_config, default_team):
        source = create_dashboard(
            user_id=test_user.id,
            name='源看板',
            team_id=default_team.id,
            layout_config=sample_layout_config
        )

        copied = copy_dashboard(source.id, test_user2.id, '复制的看板')
        copied_layout = copied.get_layout_config()

        assert copied_layout['grid']['cols'] == sample_layout_config['grid']['cols']
        assert len(copied_layout['widgets']) == len(sample_layout_config['widgets'])
        assert copied_layout['version'] == sample_layout_config['version']


class TestDashboardLayoutException:

    def test_invalid_json_layout_format(self, db_session, test_user, default_team, sample_dashboard):
        sample_dashboard.layout_config = 'invalid json here'
        db_session.add(sample_dashboard)
        db_session.commit()

        layout = sample_dashboard.get_layout_config()
        assert layout['grid']['cols'] == 12
        assert layout['widgets'] == []

    def test_missing_widget_id_in_layout(self, db_session, test_user, default_team, sample_dashboard):
        invalid_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'chart_id': 1, 'x': 0, 'y': 0, 'w': 6, 'h': 4}]
        }

        updated = update_dashboard_layout(sample_dashboard.id, invalid_layout)
        saved_layout = updated.get_layout_config()

        assert len(saved_layout['widgets']) == 1
        widget = saved_layout['widgets'][0]
        assert 'chart_id' in widget
        assert widget['x'] == 0

    def test_widget_position_out_of_bounds(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        invalid_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{
                'id': f'widget-{sample_chart.id}',
                'chart_id': sample_chart.id,
                'x': 10, 'y': 0, 'w': 6, 'h': 4
            }]
        }

        updated = update_dashboard_layout(sample_dashboard.id, invalid_layout)
        saved_layout = updated.get_layout_config()
        assert saved_layout['widgets'][0]['x'] == 10
        assert saved_layout['widgets'][0]['w'] == 6

    def test_negative_widget_dimensions(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        invalid_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{
                'id': f'widget-{sample_chart.id}',
                'chart_id': sample_chart.id,
                'x': 0, 'y': 0, 'w': -1, 'h': -2
            }]
        }

        updated = update_dashboard_layout(sample_dashboard.id, invalid_layout)
        saved_layout = updated.get_layout_config()
        assert saved_layout['widgets'][0]['w'] == -1
        assert saved_layout['widgets'][0]['h'] == -2

    def test_layout_with_none_value(self, db_session, test_user, default_team, sample_dashboard):
        layout_with_none = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': None,
            'version': '1.0'
        }

        updated = update_dashboard_layout(sample_dashboard.id, layout_with_none)
        saved_layout = updated.get_layout_config()
        assert saved_layout['widgets'] is None

    def test_layout_with_extra_fields(self, db_session, test_user, default_team, sample_dashboard, sample_chart):
        layout_with_extra = {
            'grid': {'cols': 12, 'rowHeight': 50, 'extra_field': 'should_be_preserved'},
            'widgets': [{
                'id': f'widget-{sample_chart.id}',
                'chart_id': sample_chart.id,
                'x': 0, 'y': 0, 'w': 6, 'h': 4,
                'custom_data': {'key': 'value'}
            }],
            'custom_root_field': 'preserved'
        }

        updated = update_dashboard_layout(sample_dashboard.id, layout_with_extra)
        saved_layout = updated.get_layout_config()
        assert saved_layout['grid']['extra_field'] == 'should_be_preserved'
        assert saved_layout['widgets'][0]['custom_data']['key'] == 'value'
        assert saved_layout['custom_root_field'] == 'preserved'

    def test_update_nonexistent_dashboard_layout(self, db_session):
        with pytest.raises(ValueError, match='看板不存在'):
            update_dashboard_layout(99999, {'grid': {'cols': 12}, 'widgets': []})


class TestDashboardLayoutConcurrency:

    @pytest.mark.asyncio
    async def test_concurrent_layout_updates(self, app, db_session, test_user, default_team):
        from app.services.dashboard_service import create_dashboard

        dashboard = create_dashboard(
            user_id=test_user.id,
            name='并发测试看板',
            team_id=default_team.id,
            layout_config={'grid': {'cols': 12}, 'widgets': [], 'version': 0}
        )

        async def update_layout(dashboard_id, widget_id, version):
            with app.app_context():
                from app.services.dashboard_service import update_dashboard_layout
                layout = {
                    'grid': {'cols': 12},
                    'widgets': [{'id': f'widget-{widget_id}', 'x': widget_id * 2, 'y': 0, 'w': 2, 'h': 4}],
                    'version': version
                }
                return update_dashboard_layout(dashboard_id, layout)

        tasks = [update_layout(dashboard.id, i, i) for i in range(5)]
        results = await asyncio.gather(*tasks)

        assert len(results) == 5
        final_dashboard = get_dashboard(dashboard.id)
        final_layout = final_dashboard.get_layout_config()
        assert len(final_layout['widgets']) >= 1
        assert 'version' in final_layout

    @pytest.mark.asyncio
    async def test_concurrent_widget_position_updates(self, app, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='并发测试图表',
            chart_type='line',
            datasource_id=sample_datasource_mysql.id,
            query_template='SELECT 1'
        )

        async def update_position(chart_id, x, y):
            with app.app_context():
                return update_chart_position(chart_id, {'x': x, 'y': y, 'w': 6, 'h': 4})

        tasks = [update_position(chart.id, i * 2, i) for i in range(5)]
        results = await asyncio.gather(*tasks)

        assert len(results) == 5
        updated_chart = results[-1]
        pos = updated_chart.get_position()
        assert pos['x'] in [0, 2, 4, 6, 8]
        assert pos['y'] in [0, 1, 2, 3, 4]

    def test_layout_conflict_detection(self, db_session, test_user, default_team, sample_dashboard):
        initial_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [],
            'version': 1
        }
        update_dashboard_layout(sample_dashboard.id, initial_layout)

        conflicting_layout = {
            'grid': {'cols': 12, 'rowHeight': 50},
            'widgets': [{'id': 'widget-1', 'x': 0, 'y': 0, 'w': 6, 'h': 4}],
            'version': 1,
            'conflict': True
        }

        updated = update_dashboard_layout(sample_dashboard.id, conflicting_layout)
        saved_layout = updated.get_layout_config()
        assert saved_layout['conflict'] is True
        assert len(saved_layout['widgets']) == 1

    @pytest.mark.asyncio
    async def test_concurrent_batch_updates(self, app, db_session, test_user, sample_dashboard, sample_datasource_mysql):
        charts = []
        for i in range(4):
            chart = create_chart(
                user_id=test_user.id,
                dashboard_id=sample_dashboard.id,
                name=f'图表{i+1}',
                chart_type='bar',
                datasource_id=sample_datasource_mysql.id,
                query_template='SELECT 1'
            )
            charts.append(chart)

        async def batch_update(dashboard_id, start_idx):
            with app.app_context():
                positions = [
                    {'id': charts[start_idx].id, 'x': start_idx * 3, 'y': 0, 'w': 3, 'h': 4},
                    {'id': charts[start_idx + 1].id, 'x': start_idx * 3 + 3, 'y': 0, 'w': 3, 'h': 4}
                ]
                return batch_update_chart_positions(dashboard_id, positions)

        tasks = [batch_update(sample_dashboard.id, 0), batch_update(sample_dashboard.id, 2)]
        results = await asyncio.gather(*tasks)

        assert all(results)
        for chart in charts:
            db_session.refresh(chart)
            pos = chart.get_position()
            assert pos['x'] in [0, 3, 6, 9]
            assert pos['w'] == 3

    def test_layout_version_increment(self, db_session, test_user, default_team, sample_dashboard):
        for i in range(5):
            layout = {
                'grid': {'cols': 12},
                'widgets': [{'id': f'widget-{i}', 'x': 0, 'y': i * 4, 'w': 12, 'h': 4}],
                'version': i
            }
            updated = update_dashboard_layout(sample_dashboard.id, layout)
            db_session.refresh(updated)
            saved_layout = updated.get_layout_config()
            assert saved_layout['version'] == i
