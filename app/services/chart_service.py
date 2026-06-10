from datetime import datetime
from app import db
from app.models import Chart, DataSource


def create_chart(user_id, dashboard_id, name, chart_type, position=None,
                 datasource_id=None, query_template=None, query_params=None,
                 chart_config=None, description=None, refresh_interval=None, is_active=True):
    if chart_type not in Chart.CHART_TYPES:
        raise ValueError(f'不支持的图表类型: {chart_type}')

    chart = Chart(
        name=name,
        description=description,
        dashboard_id=dashboard_id,
        datasource_id=datasource_id,
        owner_id=user_id,
        chart_type=chart_type,
        query_template=query_template,
        refresh_interval=refresh_interval,
        is_active=is_active
    )

    default_config = Chart.DEFAULT_CONFIGS.get(chart_type, {})
    if chart_config:
        merged_config = {**default_config, **chart_config}
        chart.set_chart_config(merged_config)
    else:
        chart.set_chart_config(default_config)

    if query_params:
        chart.set_query_params(query_params)
    if position:
        chart.set_position(position)
    else:
        chart.set_position({'x': 0, 'y': 0, 'w': 6, 'h': 4})

    db.session.add(chart)
    db.session.commit()
    return chart


def update_chart(chart_id, **kwargs):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    allowed_fields = ['name', 'description', 'datasource_id', 'chart_type',
                      'query_template', 'refresh_interval', 'is_active']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(chart, field, value)

    if 'query_params' in kwargs:
        chart.set_query_params(kwargs['query_params'])
    if 'chart_config' in kwargs:
        chart.set_chart_config(kwargs['chart_config'])
    if 'position' in kwargs:
        chart.set_position(kwargs['position'])

    chart.updated_at = datetime.utcnow()
    db.session.add(chart)
    db.session.commit()
    return chart


def update_chart_position(chart_id, position):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    chart.set_position(position)
    chart.updated_at = datetime.utcnow()
    db.session.add(chart)
    db.session.commit()
    return chart


def update_chart_config(chart_id, config):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    chart.set_chart_config(config)
    chart.updated_at = datetime.utcnow()
    db.session.add(chart)
    db.session.commit()
    return chart


def delete_chart(chart_id):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    db.session.delete(chart)
    db.session.commit()


def get_chart(chart_id):
    return Chart.query.get(chart_id)


def get_dashboard_charts(dashboard_id):
    return Chart.query.filter_by(dashboard_id=dashboard_id, is_active=True).order_by(Chart.created_at).all()


def get_chart_data(chart_id, params=None):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    if not chart.datasource_id:
        return {'success': True, 'data': {'categories': [], 'values': [], 'series': []}}

    datasource = DataSource.query.get(chart.datasource_id)
    if not datasource:
        return {'success': False, 'error': '数据源不存在'}

    merged_params = {**(chart.get_query_params() or {}), **(params or {})}
    result = datasource.execute_query(chart.query_template or '', merged_params)

    if result.get('success'):
        echarts_option = chart.get_echarts_option(result.get('data'))
        result['echarts_option'] = echarts_option

    return result


def get_chart_echarts_option(chart_id, data=None):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')
    return chart.get_echarts_option(data)


def batch_update_chart_positions(dashboard_id, positions):
    charts = Chart.query.filter_by(dashboard_id=dashboard_id).all()
    chart_map = {c.id: c for c in charts}

    for pos in positions:
        chart_id = pos.get('id')
        if chart_id in chart_map:
            chart = chart_map[chart_id]
            chart.set_position({
                'x': pos.get('x', 0),
                'y': pos.get('y', 0),
                'w': pos.get('w', 6),
                'h': pos.get('h', 4)
            })
            chart.updated_at = datetime.utcnow()
            db.session.add(chart)

    db.session.commit()
    return True


def copy_chart(source_chart_id, new_dashboard_id, user_id):
    source = Chart.query.get(source_chart_id)
    if not source:
        raise ValueError('源图表不存在')

    new_chart = Chart(
        name=f'{source.name} (副本)',
        description=source.description,
        dashboard_id=new_dashboard_id,
        datasource_id=source.datasource_id,
        owner_id=user_id,
        chart_type=source.chart_type,
        query_template=source.query_template,
        refresh_interval=source.refresh_interval,
        is_active=source.is_active
    )
    new_chart.set_query_params(source.get_query_params())
    new_chart.set_chart_config(source.get_chart_config())
    new_chart.set_position(source.get_position())

    db.session.add(new_chart)
    db.session.commit()
    return new_chart
