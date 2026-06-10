from datetime import datetime
from app import db
from app.models import Template, Dashboard, Chart, TeamMember


def create_template(user_id, name, description=None, category='custom',
                    dashboard_config=None, chart_configs=None, datasource_config=None,
                    thumbnail=None, preview_url=None, is_public=True):
    template = Template(
        name=name,
        description=description,
        category=category,
        thumbnail=thumbnail,
        preview_url=preview_url,
        is_public=is_public,
        created_by=user_id
    )
    if dashboard_config:
        template.set_dashboard_config(dashboard_config)
    if chart_configs:
        template.set_chart_configs(chart_configs)
    if datasource_config:
        template.set_datasource_config(datasource_config)

    db.session.add(template)
    db.session.commit()
    return template


def update_template(template_id, **kwargs):
    template = Template.query.get(template_id)
    if not template:
        raise ValueError('模板不存在')

    allowed_fields = ['name', 'description', 'category', 'thumbnail', 'preview_url', 'is_public']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(template, field, value)

    if 'dashboard_config' in kwargs:
        template.set_dashboard_config(kwargs['dashboard_config'])
    if 'chart_configs' in kwargs:
        template.set_chart_configs(kwargs['chart_configs'])
    if 'datasource_config' in kwargs:
        template.set_datasource_config(kwargs['datasource_config'])

    template.updated_at = datetime.utcnow()
    db.session.add(template)
    db.session.commit()
    return template


def delete_template(template_id):
    template = Template.query.get(template_id)
    if not template:
        raise ValueError('模板不存在')
    if template.is_system:
        raise ValueError('系统模板不能删除')

    db.session.delete(template)
    db.session.commit()


def get_template(template_id):
    return Template.query.get(template_id)


def get_templates(page=1, per_page=20, search=None, category=None, is_system=None, user_id=None):
    query = Template.query
    if search:
        query = query.filter(Template.name.like(f'%{search}%'))
    if category:
        query = query.filter(Template.category == category)
    if is_system is not None:
        query = query.filter(Template.is_system == is_system)

    query = query.filter((Template.is_public == True) | (Template.created_by == user_id))
    return query.order_by(Template.use_count.desc(), Template.created_at.desc()).paginate(page=page, per_page=per_page)


def rate_template(template_id, rating):
    template = Template.query.get(template_id)
    if not template:
        raise ValueError('模板不存在')

    if not (1 <= rating <= 5):
        raise ValueError('评分必须在1-5之间')

    template.add_rating(rating)
    db.session.commit()
    return template


def apply_template(template_id, user_id, new_name=None, datasource_mapping=None):
    template = Template.query.get(template_id)
    if not template:
        raise ValueError('模板不存在')

    template.increment_use_count()

    dashboard_config = template.get_dashboard_config()
    chart_configs = template.get_chart_configs()

    from app.services.dashboard_service import create_dashboard
    from app.services.chart_service import create_chart

    dashboard = create_dashboard(
        user_id=user_id,
        name=new_name or f'{template.name} (副本)',
        description=dashboard_config.get('description', template.description),
        layout_config=dashboard_config.get('layout_config'),
        settings=dashboard_config.get('settings'),
        refresh_interval=dashboard_config.get('refresh_interval', 30)
    )

    created_charts = []
    for chart_config in chart_configs:
        datasource_id = None
        if datasource_mapping and chart_config.get('datasource_id') in datasource_mapping:
            datasource_id = datasource_mapping[chart_config['datasource_id']]

        chart = create_chart(
            user_id=user_id,
            dashboard_id=dashboard.id,
            name=chart_config.get('name', '未命名图表'),
            chart_type=chart_config.get('chart_type', 'line'),
            position=chart_config.get('position'),
            datasource_id=datasource_id,
            query_template=chart_config.get('query_template'),
            query_params=chart_config.get('query_params'),
            chart_config=chart_config.get('chart_config'),
            description=chart_config.get('description'),
            refresh_interval=chart_config.get('refresh_interval')
        )
        created_charts.append(chart)

    db.session.commit()
    return dashboard, created_charts


def create_template_from_dashboard(dashboard_id, user_id, name, description=None,
                                   category='custom', is_public=True):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    dashboard_config = {
        'name': dashboard.name,
        'description': dashboard.description,
        'layout_config': dashboard.get_layout_config(),
        'settings': dashboard.get_settings(),
        'refresh_interval': dashboard.refresh_interval
    }

    chart_configs = []
    datasource_ids = set()

    for chart in dashboard.charts:
        if chart.datasource_id:
            datasource_ids.add(chart.datasource_id)
        chart_configs.append({
            'name': chart.name,
            'description': chart.description,
            'chart_type': chart.chart_type,
            'position': chart.get_position(),
            'datasource_id': chart.datasource_id,
            'query_template': chart.query_template,
            'query_params': chart.get_query_params(),
            'chart_config': chart.get_chart_config(),
            'refresh_interval': chart.refresh_interval
        })

    datasource_config = {
        'required_datasource_ids': list(datasource_ids),
        'datasource_types': {}
    }

    from app.models import DataSource
    for ds_id in datasource_ids:
        ds = DataSource.query.get(ds_id)
        if ds:
            datasource_config['datasource_types'][str(ds_id)] = ds.type

    return create_template(
        user_id=user_id,
        name=name,
        description=description,
        category=category,
        dashboard_config=dashboard_config,
        chart_configs=chart_configs,
        datasource_config=datasource_config,
        is_public=is_public
    )


def seed_default_templates():
    default_templates = [
        {
            'name': '双十一实时销售大屏',
            'description': '双十一活动实时销售额、订单量、用户增长等核心指标监控',
            'category': 'sales',
            'is_system': True,
            'dashboard_config': {
                'layout_config': {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []},
                'settings': {'theme': 'dark'},
                'refresh_interval': 10
            },
            'chart_configs': [
                {'name': '实时销售额', 'chart_type': 'line', 'position': {'x': 0, 'y': 0, 'w': 8, 'h': 4}},
                {'name': '今日销售额', 'chart_type': 'gauge', 'position': {'x': 8, 'y': 0, 'w': 4, 'h': 4}},
                {'name': '订单量趋势', 'chart_type': 'bar', 'position': {'x': 0, 'y': 4, 'w': 6, 'h': 4}},
                {'name': '商品销售排行', 'chart_type': 'bar', 'position': {'x': 6, 'y': 4, 'w': 6, 'h': 4}},
                {'name': '用户增长漏斗', 'chart_type': 'funnel', 'position': {'x': 0, 'y': 8, 'w': 4, 'h': 4}},
                {'name': '地域销售分布', 'chart_type': 'pie', 'position': {'x': 4, 'y': 8, 'w': 4, 'h': 4}},
                {'name': '客服工单热力图', 'chart_type': 'heatmap', 'position': {'x': 8, 'y': 8, 'w': 4, 'h': 4}},
            ]
        },
        {
            'name': '用户增长漏斗分析',
            'description': '从曝光到转化的完整用户旅程漏斗分析',
            'category': 'marketing',
            'is_system': True,
            'dashboard_config': {
                'layout_config': {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []},
                'refresh_interval': 60
            },
            'chart_configs': [
                {'name': '用户漏斗', 'chart_type': 'funnel', 'position': {'x': 0, 'y': 0, 'w': 8, 'h': 8}},
                {'name': '各阶段转化率', 'chart_type': 'bar', 'position': {'x': 8, 'y': 0, 'w': 4, 'h': 4}},
                {'name': '趋势对比', 'chart_type': 'line', 'position': {'x': 8, 'y': 4, 'w': 4, 'h': 4}},
            ]
        },
        {
            'name': '客服工单热力图',
            'description': '按时间段和问题类型展示客服工单分布热力图',
            'category': 'operations',
            'is_system': True,
            'dashboard_config': {
                'layout_config': {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []},
                'refresh_interval': 300
            },
            'chart_configs': [
                {'name': '工单分布热力图', 'chart_type': 'heatmap', 'position': {'x': 0, 'y': 0, 'w': 12, 'h': 8}},
                {'name': '工单类型统计', 'chart_type': 'pie', 'position': {'x': 0, 'y': 8, 'w': 6, 'h': 4}},
                {'name': '日工单量趋势', 'chart_type': 'line', 'position': {'x': 6, 'y': 8, 'w': 6, 'h': 4}},
            ]
        },
        {
            'name': '运营数据总览',
            'description': '核心运营指标综合看板，包含DAU、留存、收入等',
            'category': 'operations',
            'is_system': True,
            'dashboard_config': {
                'layout_config': {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []},
                'refresh_interval': 300
            },
            'chart_configs': [
                {'name': 'DAU趋势', 'chart_type': 'line', 'position': {'x': 0, 'y': 0, 'w': 6, 'h': 4}},
                {'name': '收入统计', 'chart_type': 'bar', 'position': {'x': 6, 'y': 0, 'w': 6, 'h': 4}},
                {'name': '用户留存', 'chart_type': 'line', 'position': {'x': 0, 'y': 4, 'w': 6, 'h': 4}},
                {'name': '用户分布', 'chart_type': 'pie', 'position': {'x': 6, 'y': 4, 'w': 6, 'h': 4}},
                {'name': '核心指标', 'chart_type': 'gauge', 'position': {'x': 0, 'y': 8, 'w': 4, 'h': 4}},
                {'name': '核心指标', 'chart_type': 'gauge', 'position': {'x': 4, 'y': 8, 'w': 4, 'h': 4}},
                {'name': '核心指标', 'chart_type': 'gauge', 'position': {'x': 8, 'y': 8, 'w': 4, 'h': 4}},
            ]
        },
    ]

    for template_data in default_templates:
        existing = Template.query.filter_by(name=template_data['name'], is_system=True).first()
        if not existing:
            template = Template(
                name=template_data['name'],
                description=template_data['description'],
                category=template_data['category'],
                is_system=template_data['is_system'],
                is_public=True,
                use_count=0,
                rating=4.5,
                rating_count=10
            )
            template.set_dashboard_config(template_data['dashboard_config'])
            template.set_chart_configs(template_data['chart_configs'])
            template.set_datasource_config({})
            db.session.add(template)

    db.session.commit()
