from datetime import datetime
from app import db
from app.models import Dashboard, Chart, DashboardShare, User, TeamMember


def create_dashboard(user_id, name, description=None, team_id=None, layout_config=None, settings=None, refresh_interval=30):
    dashboard = Dashboard(
        name=name,
        description=description,
        owner_id=user_id,
        team_id=team_id,
        refresh_interval=refresh_interval
    )
    if layout_config:
        dashboard.set_layout_config(layout_config)
    if settings:
        dashboard.set_settings(settings)

    db.session.add(dashboard)
    db.session.commit()
    return dashboard


def update_dashboard(dashboard_id, **kwargs):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    allowed_fields = ['name', 'description', 'team_id', 'refresh_interval', 'is_public', 'status']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(dashboard, field, value)

    if 'layout_config' in kwargs:
        dashboard.set_layout_config(kwargs['layout_config'])
    if 'settings' in kwargs:
        dashboard.set_settings(kwargs['settings'])

    dashboard.updated_at = datetime.utcnow()
    db.session.add(dashboard)
    db.session.commit()
    return dashboard


def update_dashboard_layout(dashboard_id, layout_config):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    dashboard.set_layout_config(layout_config)
    dashboard.updated_at = datetime.utcnow()
    db.session.add(dashboard)
    db.session.commit()
    return dashboard


def delete_dashboard(dashboard_id):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    db.session.delete(dashboard)
    db.session.commit()


def get_dashboard(dashboard_id, increment_view=False):
    dashboard = Dashboard.query.get(dashboard_id)
    if dashboard and increment_view:
        dashboard.increment_view_count()
        db.session.commit()
    return dashboard


def get_user_dashboards(user_id, page=1, per_page=20, search=None, team_id=None):
    query = Dashboard.query.outerjoin(TeamMember, Dashboard.team_id == TeamMember.team_id).filter(
        (Dashboard.owner_id == user_id) |
        (TeamMember.user_id == user_id)
    ).distinct()

    if search:
        query = query.filter(Dashboard.name.like(f'%{search}%'))
    if team_id:
        query = query.filter(Dashboard.team_id == team_id)

    return query.order_by(Dashboard.updated_at.desc()).paginate(page=page, per_page=per_page)


def get_team_dashboards(team_id, page=1, per_page=20, search=None):
    query = Dashboard.query.filter_by(team_id=team_id)
    if search:
        query = query.filter(Dashboard.name.like(f'%{search}%'))
    return query.order_by(Dashboard.updated_at.desc()).paginate(page=page, per_page=per_page)


def get_public_dashboards(page=1, per_page=20, search=None):
    query = Dashboard.query.filter_by(is_public=True, status='active')
    if search:
        query = query.filter(Dashboard.name.like(f'%{search}%'))
    return query.order_by(Dashboard.view_count.desc()).paginate(page=page, per_page=per_page)


def share_dashboard(dashboard_id, user_id, target_user_id, can_edit=False, can_share=False, shared_by=None):
    existing = DashboardShare.query.filter_by(
        dashboard_id=dashboard_id,
        user_id=target_user_id
    ).first()

    if existing:
        existing.can_edit = can_edit
        existing.can_share = can_share
        existing.shared_by = shared_by
    else:
        share = DashboardShare(
            dashboard_id=dashboard_id,
            user_id=target_user_id,
            can_edit=can_edit,
            can_share=can_share,
            shared_by=shared_by
        )
        db.session.add(share)

    db.session.commit()
    return existing or share


def unshare_dashboard(dashboard_id, user_id):
    share = DashboardShare.query.filter_by(
        dashboard_id=dashboard_id,
        user_id=user_id
    ).first()
    if share:
        db.session.delete(share)
        db.session.commit()


def get_dashboard_shares(dashboard_id):
    return DashboardShare.query.filter_by(dashboard_id=dashboard_id).all()


def copy_dashboard(source_dashboard_id, user_id, new_name=None):
    source = Dashboard.query.get(source_dashboard_id)
    if not source:
        raise ValueError('源看板不存在')

    new_dashboard = Dashboard(
        name=new_name or f'{source.name} (副本)',
        description=source.description,
        owner_id=user_id,
        team_id=source.team_id,
        refresh_interval=source.refresh_interval,
        is_public=False
    )
    new_dashboard.set_layout_config(source.get_layout_config())
    new_dashboard.set_settings(source.get_settings())

    db.session.add(new_dashboard)
    db.session.flush()

    for chart in source.charts:
        new_chart = Chart(
            name=chart.name,
            description=chart.description,
            dashboard_id=new_dashboard.id,
            datasource_id=chart.datasource_id,
            owner_id=user_id,
            chart_type=chart.chart_type,
            query_template=chart.query_template,
            refresh_interval=chart.refresh_interval,
            is_active=chart.is_active
        )
        new_chart.set_query_params(chart.get_query_params())
        new_chart.set_chart_config(chart.get_chart_config())
        new_chart.set_position(chart.get_position())
        db.session.add(new_chart)

    db.session.commit()
    return new_dashboard
