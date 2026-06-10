from datetime import datetime
from app import db
from app.models import Chart, DataSource


def create_chart(user_id, dashboard_id, name, chart_type, position=None,
                 datasource_id=None, query_template=None, query_params=None,
                 chart_config=None, link_config=None, description=None,
                 refresh_interval=None, is_active=True):
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
    if link_config:
        chart.set_link_config(link_config)

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
    if 'link_config' in kwargs:
        chart.set_link_config(kwargs['link_config'])

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


def get_chart_data(chart_id, params=None, sample=None, sample_points=None,
                  sample_method='avg', page=None, per_page=None):
    chart = Chart.query.get(chart_id)
    if not chart:
        raise ValueError('图表不存在')

    if not chart.datasource_id:
        result = {'success': True, 'data': {'categories': [], 'values': [], 'series': []}}
        if page is not None:
            result['data']['paginated'] = True
            result['data']['page'] = page
            result['data']['per_page'] = per_page or 20
            result['data']['total'] = 0
            result['data']['pages'] = 0
            result['data']['has_next'] = False
            result['data']['has_prev'] = False
        return result

    datasource = DataSource.query.get(chart.datasource_id)
    if not datasource:
        return {'success': False, 'error': '数据源不存在'}

    merged_params = {**(chart.get_query_params() or {}), **(params or {})}

    if sample or page is not None:
        from app.services.datasource_service import execute_query_with_options
        result = execute_query_with_options(
            datasource,
            chart.query_template or '',
            merged_params,
            sample=sample,
            sample_points=sample_points or 100,
            sample_method=sample_method,
            page=page,
            per_page=per_page or 20
        )
    else:
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


def trigger_chart_link(source_chart_id, event_data):
    source_chart = Chart.query.get(source_chart_id)
    if not source_chart:
        raise ValueError('源图表不存在')

    link_config = source_chart.get_link_config()
    if not link_config:
        return {'success': True, 'linked_charts': [], 'message': '未配置联动关系'}

    target_chart_ids = link_config.get('target_charts', [])
    source_field = link_config.get('source_field', 'value')
    target_param = link_config.get('target_param', 'filter')

    if not target_chart_ids:
        return {'success': True, 'linked_charts': [], 'message': '未配置目标图表'}

    event_value = event_data.get(source_field) if isinstance(event_data, dict) else event_data
    if event_value is None:
        return {'success': False, 'error': f'缺少联动字段: {source_field}'}

    updated_charts = []
    failed_charts = []

    for chart_id in target_chart_ids:
        try:
            target_chart = Chart.query.get(chart_id)
            if not target_chart:
                failed_charts.append({'chart_id': chart_id, 'error': '图表不存在'})
                continue

            if target_chart.dashboard_id != source_chart.dashboard_id:
                failed_charts.append({'chart_id': chart_id, 'error': '不在同一看板'})
                continue

            current_params = target_chart.get_query_params() or {}
            current_params[target_param] = event_value
            target_chart.set_query_params(current_params)
            target_chart.updated_at = datetime.utcnow()
            db.session.add(target_chart)
            db.session.commit()

            data_result = get_chart_data(chart_id)
            if data_result.get('success'):
                try:
                    from app.api.sse import push_sse_update
                    push_sse_update(
                        target_chart.dashboard_id,
                        'chart_link_update',
                        {
                            'chart_id': chart_id,
                            'source_chart_id': source_chart_id,
                            'event_data': event_data,
                            'data': data_result.get('data'),
                            'echarts_option': data_result.get('echarts_option')
                        }
                    )
                except Exception:
                    pass

            updated_charts.append({
                'chart_id': chart_id,
                'chart_name': target_chart.name,
                'param': target_param,
                'value': event_value
            })

        except Exception as e:
            failed_charts.append({'chart_id': chart_id, 'error': str(e)})
            db.session.rollback()

    return {
        'success': True,
        'linked_charts': updated_charts,
        'failed_charts': failed_charts,
        'source_chart': source_chart_id,
        'event_data': event_data
    }
