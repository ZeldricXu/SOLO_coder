import pytest
from datetime import datetime, timedelta
from unittest.mock import Mock, patch, MagicMock
from app.models.chart import Chart
from app.models.report import ReportSchedule
from app.services.datasource_service import sample_time_series_data, paginate_data


class TestChartLink:
    def test_chart_link_config_default_empty(self, db_session, test_user, sample_dashboard):
        chart = Chart(
            name='Test Chart',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            chart_type='line'
        )
        db_session.add(chart)
        db_session.commit()

        assert chart.get_link_config() == {}

    def test_chart_set_link_config(self, db_session, test_user, sample_dashboard):
        chart = Chart(
            name='Test Chart',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            chart_type='line'
        )
        chart.set_link_config({
            'target_charts': [2, 3],
            'source_field': 'category',
            'target_param': 'filter_category'
        })
        db_session.add(chart)
        db_session.commit()

        config = chart.get_link_config()
        assert config['target_charts'] == [2, 3]
        assert config['source_field'] == 'category'
        assert config['target_param'] == 'filter_category'

    def test_chart_to_dict_includes_link_config(self, db_session, test_user, sample_dashboard):
        chart = Chart(
            name='Test Chart',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            chart_type='line'
        )
        chart.set_link_config({'target_charts': [2]})
        db_session.add(chart)
        db_session.commit()

        data = chart.to_dict()
        assert 'link_config' in data
        assert data['link_config']['target_charts'] == [2]

    def test_link_config_string_handling(self, db_session, test_user, sample_dashboard):
        chart = Chart(
            name='Test Chart',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            chart_type='line'
        )
        chart.link_config = '{"target_charts": [1,2]}'
        db_session.add(chart)
        db_session.commit()

        config = chart.get_link_config()
        assert isinstance(config, dict)
        assert config['target_charts'] == [1, 2]

    def test_link_config_invalid_json(self, db_session, test_user, sample_dashboard):
        chart = Chart(
            name='Test Chart',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            chart_type='line'
        )
        chart.link_config = 'invalid json'
        db_session.add(chart)
        db_session.commit()

        config = chart.get_link_config()
        assert config == {}

    def test_create_chart_with_link_config(self, db_session, test_user, sample_dashboard):
        from app.services.chart_service import create_chart

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='Linked Chart',
            chart_type='pie',
            link_config={
                'target_charts': [],
                'source_field': 'name',
                'target_param': 'category'
            }
        )

        assert chart is not None
        assert chart.get_link_config()['source_field'] == 'name'

    def test_update_chart_link_config(self, db_session, test_user, sample_dashboard):
        from app.services.chart_service import create_chart, update_chart

        chart = create_chart(
            user_id=test_user.id,
            dashboard_id=sample_dashboard.id,
            name='Test Chart',
            chart_type='line'
        )

        updated = update_chart(
            chart.id,
            link_config={'target_charts': [99], 'source_field': 'value'}
        )

        assert updated.get_link_config()['target_charts'] == [99]
        assert updated.get_link_config()['source_field'] == 'value'


class TestReportScheduleTimeWindow:
    def test_schedule_default_no_window(self, db_session, test_user, sample_dashboard):
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60
        )
        db_session.add(schedule)
        db_session.commit()

        assert schedule.start_time is None
        assert schedule.end_time is None
        assert schedule.is_within_window() is True

    def test_schedule_within_window(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            start_time=now - timedelta(hours=1),
            end_time=now + timedelta(hours=1)
        )
        db_session.add(schedule)
        db_session.commit()

        assert schedule.is_within_window() is True

    def test_schedule_before_start_time(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            start_time=now + timedelta(hours=1),
            end_time=now + timedelta(hours=2)
        )
        db_session.add(schedule)
        db_session.commit()

        assert schedule.is_within_window() is False

    def test_schedule_after_end_time(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            start_time=now - timedelta(hours=2),
            end_time=now - timedelta(hours=1)
        )
        db_session.add(schedule)
        db_session.commit()

        assert schedule.is_within_window() is False

    def test_next_run_respects_start_time(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        future_start = now + timedelta(hours=5)
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            start_time=future_start
        )
        db_session.add(schedule)
        db_session.commit()

        next_run = schedule.get_next_run_time()
        assert next_run is not None
        assert next_run >= future_start

    def test_next_run_past_end_time_returns_none(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            end_time=now - timedelta(hours=1)
        )
        db_session.add(schedule)
        db_session.commit()

        next_run = schedule.get_next_run_time()
        assert next_run is None

    def test_schedule_to_dict_includes_window(self, db_session, test_user, sample_dashboard):
        now = datetime.utcnow()
        schedule = ReportSchedule(
            name='Test Schedule',
            dashboard_id=sample_dashboard.id,
            owner_id=test_user.id,
            interval_minutes=60,
            start_time=now - timedelta(hours=1),
            end_time=now + timedelta(hours=1)
        )
        db_session.add(schedule)
        db_session.commit()

        data = schedule.to_dict()
        assert 'start_time' in data
        assert 'end_time' in data
        assert 'is_within_window' in data
        assert data['is_within_window'] is True

    def test_create_schedule_with_window(self, db_session, test_user, sample_dashboard):
        from app.services.report_service import create_report_schedule

        start = datetime.utcnow() + timedelta(hours=1)
        end = datetime.utcnow() + timedelta(hours=5)

        schedule = create_report_schedule(
            user_id=test_user.id,
            name='Activity Campaign',
            dashboard_id=sample_dashboard.id,
            recipients=['test@example.com'],
            interval_minutes=5,
            start_time=start,
            end_time=end
        )

        assert schedule.start_time == start
        assert schedule.end_time == end
        assert schedule.is_within_window() is False

    def test_create_schedule_invalid_window_raises(self, db_session, test_user, sample_dashboard):
        from app.services.report_service import create_report_schedule

        start = datetime.utcnow() + timedelta(hours=5)
        end = datetime.utcnow() + timedelta(hours=1)

        with pytest.raises(ValueError, match='开始时间必须早于结束时间'):
            create_report_schedule(
                user_id=test_user.id,
                name='Invalid',
                dashboard_id=sample_dashboard.id,
                recipients=['test@example.com'],
                interval_minutes=5,
                start_time=start,
                end_time=end
            )

    def test_update_schedule_window(self, db_session, test_user, sample_dashboard):
        from app.services.report_service import create_report_schedule, update_report_schedule

        schedule = create_report_schedule(
            user_id=test_user.id,
            name='Test',
            dashboard_id=sample_dashboard.id,
            recipients=['test@example.com'],
            interval_minutes=60
        )

        new_end = datetime.utcnow() + timedelta(days=7)
        updated = update_report_schedule(
            schedule.id,
            end_time=new_end
        )

        assert updated.end_time == new_end
        assert updated.is_within_window() is True


class TestDataSampling:
    def test_sample_data_within_target_returns_original(self):
        data = {
            'categories': ['A', 'B', 'C'],
            'values': [10, 20, 30],
            'row_count': 3
        }

        result = sample_time_series_data(data, target_points=10)
        assert result['values'] == [10, 20, 30]
        assert 'sampled' not in result or result.get('sampled') is False

    def test_sample_avg_method(self):
        categories = [f'point_{i}' for i in range(100)]
        values = [float(i) for i in range(100)]

        data = {
            'categories': categories,
            'values': values,
            'row_count': 100
        }

        result = sample_time_series_data(data, target_points=10, method='avg')

        assert result['sampled'] is True
        assert result['original_count'] == 100
        assert len(result['values']) <= 10
        assert len(result['values']) > 0
        assert len(result['categories']) == len(result['values'])

    def test_sample_max_method(self):
        categories = [f'p_{i}' for i in range(50)]
        values = [float(i) for i in range(50)]

        data = {
            'categories': categories,
            'values': values,
            'row_count': 50
        }

        result = sample_time_series_data(data, target_points=5, method='max')

        assert result['sampled'] is True
        for v in result['values']:
            assert isinstance(v, float)

    def test_sample_min_method(self):
        categories = [f'p_{i}' for i in range(50)]
        values = [float(i) for i in range(50, 100)]

        data = {
            'categories': categories,
            'values': values,
            'row_count': 50
        }

        result = sample_time_series_data(data, target_points=5, method='min')

        assert result['sampled'] is True

    def test_sample_with_series_data(self):
        categories = [f'p_{i}' for i in range(100)]
        values = [float(i) for i in range(100)]
        series = [
            {'name': 's1', 'data': [float(i) for i in range(100)]},
            {'name': 's2', 'data': [float(i * 2) for i in range(100)]}
        ]

        data = {
            'categories': categories,
            'values': values,
            'series': series,
            'row_count': 100
        }

        result = sample_time_series_data(data, target_points=10)

        assert result['sampled'] is True
        assert len(result['series']) == 2
        assert len(result['series'][0]['data']) <= 10
        assert len(result['series'][1]['data']) <= 10

    def test_sample_empty_data_returns_empty(self):
        data = {'values': [], 'categories': [], 'row_count': 0}

        result = sample_time_series_data(data, target_points=10)
        assert result['values'] == []

    def test_sample_none_data_returns_none(self):
        result = sample_time_series_data(None)
        assert result is None

    def test_sample_with_rows_and_columns(self):
        categories = [f'p_{i}' for i in range(100)]
        values = [float(i) for i in range(100)]
        rows = [{'ts': c, 'val': v} for c, v in zip(categories, values)]

        data = {
            'categories': categories,
            'values': values,
            'rows': rows,
            'columns': ['ts', 'val'],
            'row_count': 100
        }

        result = sample_time_series_data(data, target_points=10)

        assert result['sampled'] is True
        assert 'rows' in result
        assert 'columns' in result


class TestDataPagination:
    def test_paginate_first_page(self):
        rows = [{'id': i, 'val': f'item_{i}'} for i in range(100)]
        data = {'rows': rows, 'row_count': 100}

        result = paginate_data(data, page=1, per_page=10)

        assert result['paginated'] is True
        assert result['page'] == 1
        assert result['per_page'] == 10
        assert result['total'] == 100
        assert result['pages'] == 10
        assert len(result['rows']) == 10
        assert result['rows'][0]['id'] == 0
        assert result['rows'][9]['id'] == 9
        assert result['has_next'] is True
        assert result['has_prev'] is False

    def test_paginate_middle_page(self):
        rows = [{'id': i} for i in range(100)]
        data = {'rows': rows, 'row_count': 100}

        result = paginate_data(data, page=5, per_page=10)

        assert result['page'] == 5
        assert len(result['rows']) == 10
        assert result['rows'][0]['id'] == 40
        assert result['has_next'] is True
        assert result['has_prev'] is True

    def test_paginate_last_page(self):
        rows = [{'id': i} for i in range(100)]
        data = {'rows': rows, 'row_count': 100}

        result = paginate_data(data, page=10, per_page=10)

        assert result['page'] == 10
        assert len(result['rows']) == 10
        assert result['rows'][0]['id'] == 90
        assert result['has_next'] is False
        assert result['has_prev'] is True

    def test_paginate_with_values_and_categories(self):
        categories = [f'cat_{i}' for i in range(50)]
        values = [float(i) for i in range(50)]
        rows = [{'c': c, 'v': v} for c, v in zip(categories, values)]

        data = {
            'rows': rows,
            'categories': categories,
            'values': values,
            'row_count': 50
        }

        result = paginate_data(data, page=2, per_page=10)

        assert len(result['values']) == 10
        assert len(result['categories']) == 10
        assert result['values'][0] == 10.0
        assert result['categories'][0] == 'cat_10'

    def test_paginate_with_series(self):
        rows = [{'id': i} for i in range(50)]
        series = [
            {'name': 's1', 'data': [float(i) for i in range(50)]},
            {'name': 's2', 'data': [float(i * 3) for i in range(50)]}
        ]

        data = {
            'rows': rows,
            'series': series,
            'row_count': 50
        }

        result = paginate_data(data, page=3, per_page=10)

        assert len(result['series']) == 2
        assert len(result['series'][0]['data']) == 10
        assert result['series'][0]['data'][0] == 20.0

    def test_paginate_empty_rows(self):
        data = {'rows': [], 'row_count': 0}

        result = paginate_data(data, page=1, per_page=20)

        assert result['paginated'] is True
        assert result['total'] == 0
        assert result['pages'] == 0
        assert result['has_next'] is False
        assert result['has_prev'] is False

    def test_paginate_page_out_of_range_clamps(self):
        rows = [{'id': i} for i in range(25)]
        data = {'rows': rows, 'row_count': 25}

        result = paginate_data(data, page=100, per_page=10)

        assert result['page'] == 3
        assert len(result['rows']) == 5

    def test_paginate_page_zero_returns_first(self):
        rows = [{'id': i} for i in range(50)]
        data = {'rows': rows, 'row_count': 50}

        result = paginate_data(data, page=0, per_page=10)

        assert result['page'] == 1
        assert result['rows'][0]['id'] == 0

    def test_paginate_keeps_original_fields(self):
        data = {
            'rows': [{'id': 1}],
            'columns': ['id', 'name'],
            'row_count': 1,
            'extra_field': 'value'
        }

        result = paginate_data(data, page=1, per_page=10)

        assert 'extra_field' in result
        assert result['extra_field'] == 'value'
        assert 'columns' in result
