from flask import Blueprint, request, jsonify, render_template, redirect, url_for, flash, session
from flask_login import current_user, login_required
from app.services.share_service import (
    create_share_link, get_share_link, get_share_links,
    revoke_share_link, access_share_link, get_dashboard_by_share_token
)
from app.utils.decorators import login_required_api, validate_json, paginate

share_bp = Blueprint('share', __name__)


@share_bp.route('/api/links', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_share_links(page, per_page):
    dashboard_id = request.args.get('dashboard_id', type=int)

    pagination = get_share_links(
        dashboard_id=dashboard_id,
        user_id=current_user.id,
        page=page,
        per_page=per_page
    )

    return jsonify({
        'success': True,
        'data': {
            'items': [s.to_dict() for s in pagination.items],
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'pages': pagination.pages
        }
    })


@share_bp.route('/api/links', methods=['POST'])
@login_required_api
@validate_json('dashboard_id')
def create_share_link_api():
    data = request.get_json()
    try:
        share = create_share_link(
            dashboard_id=data['dashboard_id'],
            user_id=current_user.id,
            permission=data.get('permission', 'view'),
            expires_hours=data.get('expires_hours', 24),
            password=data.get('password'),
            max_views=data.get('max_views')
        )
        return jsonify({
            'success': True,
            'message': '分享链接创建成功',
            'data': {
                **share.to_dict(),
                'full_url': f"{request.host_url.rstrip('/')}/share/{share.token}"
            }
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@share_bp.route('/api/links/<int:share_id>/revoke', methods=['POST'])
@login_required_api
def revoke_share_link_api(share_id):
    try:
        share = revoke_share_link(share_id, current_user.id)
        return jsonify({
            'success': True,
            'message': '已撤销分享',
            'data': share.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@share_bp.route('/<token>', methods=['GET'])
def view_shared_dashboard(token):
    password = request.args.get('password') or session.get(f'share_password_{token}')

    share, error = access_share_link(token, password)
    if error:
        if '密码' in error:
            return render_template('share/password.html', token=token, error=error)
        return render_template('share/invalid.html', error=error), 404

    session[f'share_password_{token}'] = password

    dashboard = share.dashboard
    if not dashboard:
        return render_template('share/invalid.html', error='看板不存在'), 404

    return render_template('share/view.html',
                           dashboard=dashboard,
                           share=share,
                           can_edit=share.permission == 'edit')


@share_bp.route('/<token>', methods=['POST'])
def submit_shared_password(token):
    password = request.form.get('password')

    share, error = access_share_link(token, password)
    if error:
        return render_template('share/password.html', token=token, error=error)

    session[f'share_password_{token}'] = password
    return redirect(url_for('share.view_shared_dashboard', token=token))


@share_bp.route('/api/<token>/data', methods=['GET'])
def get_shared_dashboard_data(token):
    share = get_share_link(token)
    if not share or not share.is_valid():
        return jsonify({'success': False, 'message': '分享链接无效'}), 404

    dashboard = share.dashboard
    if not dashboard:
        return jsonify({'success': False, 'message': '看板不存在'}), 404

    from app.services.chart_service import get_dashboard_charts, get_chart_data
    charts = get_dashboard_charts(dashboard.id)
    results = {}

    for chart in charts:
        try:
            data = get_chart_data(chart.id)
            results[chart.id] = data
        except Exception as e:
            results[chart.id] = {'success': False, 'error': str(e)}

    return jsonify({
        'success': True,
        'data': {
            'dashboard': dashboard.to_dict(include_layout=True),
            'charts': [c.to_dict() for c in charts],
            'chart_data': results
        }
    })


@share_bp.route('/api/<token>/chart/<int:chart_id>/data', methods=['GET'])
def get_shared_chart_data(token, chart_id):
    share = get_share_link(token)
    if not share or not share.is_valid():
        return jsonify({'success': False, 'message': '分享链接无效'}), 404

    from app.services.chart_service import get_chart, get_chart_data
    chart = get_chart(chart_id)
    if not chart or chart.dashboard_id != share.dashboard_id:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    params = request.args.to_dict()
    result = get_chart_data(chart_id, params)
    return jsonify(result)
