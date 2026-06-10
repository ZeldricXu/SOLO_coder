from flask import Blueprint, request, jsonify
from flask_login import current_user
from app.services.dashboard_service import (
    create_dashboard, update_dashboard, update_dashboard_layout,
    delete_dashboard, get_dashboard, get_user_dashboards,
    get_public_dashboards, share_dashboard, unshare_dashboard,
    get_dashboard_shares, copy_dashboard
)
from app.services.chart_service import get_dashboard_charts, get_chart_data
from app.utils.decorators import (
    login_required_api, permission_required, dashboard_access_required,
    validate_json, paginate
)

dashboard_bp = Blueprint('dashboard', __name__)


@dashboard_bp.route('', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_dashboards(page, per_page):
    search = request.args.get('search')
    team_id = request.args.get('team_id', type=int)
    is_public = request.args.get('public', 'false').lower() == 'true'

    if is_public:
        pagination = get_public_dashboards(page=page, per_page=per_page, search=search)
    else:
        pagination = get_user_dashboards(
            user_id=current_user.id,
            page=page,
            per_page=per_page,
            search=search,
            team_id=team_id
        )

    return jsonify({
        'success': True,
        'data': {
            'items': [d.to_dict(include_layout=False) for d in pagination.items],
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'pages': pagination.pages
        }
    })


@dashboard_bp.route('/<int:dashboard_id>', methods=['GET'])
@login_required_api
@dashboard_access_required(permission='view')
def get_dashboard_detail(dashboard_id):
    dashboard = get_dashboard(dashboard_id, increment_view=True)
    if not dashboard:
        return jsonify({'success': False, 'message': '看板不存在'}), 404

    charts = get_dashboard_charts(dashboard_id)

    return jsonify({
        'success': True,
        'data': {
            **dashboard.to_dict(include_layout=True),
            'charts': [c.to_dict() for c in charts]
        }
    })


@dashboard_bp.route('', methods=['POST'])
@login_required_api
@permission_required('dashboard:create')
@validate_json('name')
def create_dashboard_api():
    data = request.get_json()
    try:
        dashboard = create_dashboard(
            user_id=current_user.id,
            name=data['name'],
            description=data.get('description'),
            team_id=data.get('team_id'),
            layout_config=data.get('layout_config'),
            settings=data.get('settings'),
            refresh_interval=data.get('refresh_interval', 30)
        )
        return jsonify({
            'success': True,
            'message': '创建成功',
            'data': dashboard.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>', methods=['PUT'])
@login_required_api
@dashboard_access_required(permission='edit')
@validate_json()
def update_dashboard_api(dashboard_id):
    data = request.get_json()
    try:
        dashboard = update_dashboard(dashboard_id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': dashboard.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>/layout', methods=['PUT'])
@login_required_api
@dashboard_access_required(permission='edit')
@validate_json('layout_config')
def update_layout_api(dashboard_id):
    data = request.get_json()
    try:
        dashboard = update_dashboard_layout(dashboard_id, data['layout_config'])
        return jsonify({
            'success': True,
            'message': '布局更新成功',
            'data': dashboard.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>', methods=['DELETE'])
@login_required_api
@dashboard_access_required(permission='edit')
def delete_dashboard_api(dashboard_id):
    try:
        delete_dashboard(dashboard_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>/copy', methods=['POST'])
@login_required_api
@dashboard_access_required(permission='view')
def copy_dashboard_api(dashboard_id):
    data = request.get_json() or {}
    try:
        new_dashboard = copy_dashboard(
            source_dashboard_id=dashboard_id,
            user_id=current_user.id,
            new_name=data.get('name')
        )
        return jsonify({
            'success': True,
            'message': '复制成功',
            'data': new_dashboard.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>/shares', methods=['GET'])
@login_required_api
@dashboard_access_required(permission='edit')
def list_shares_api(dashboard_id):
    shares = get_dashboard_shares(dashboard_id)
    return jsonify({
        'success': True,
        'data': [{
            'id': s.id,
            'user_id': s.user_id,
            'user_name': s.user.name if s.user else None,
            'user_email': s.user.email if s.user else None,
            'can_edit': s.can_edit,
            'can_share': s.can_share,
            'created_at': s.created_at.isoformat() if s.created_at else None
        } for s in shares]
    })


@dashboard_bp.route('/<int:dashboard_id>/shares', methods=['POST'])
@login_required_api
@dashboard_access_required(permission='edit')
@validate_json('user_id')
def add_share_api(dashboard_id):
    data = request.get_json()
    try:
        share = share_dashboard(
            dashboard_id=dashboard_id,
            user_id=current_user.id,
            target_user_id=data['user_id'],
            can_edit=data.get('can_edit', False),
            can_share=data.get('can_share', False),
            shared_by=current_user.id
        )
        return jsonify({
            'success': True,
            'message': '分享成功',
            'data': {
                'id': share.id,
                'user_id': share.user_id,
                'can_edit': share.can_edit,
                'can_share': share.can_share
            }
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>/shares/<int:user_id>', methods=['DELETE'])
@login_required_api
@dashboard_access_required(permission='edit')
def remove_share_api(dashboard_id, user_id):
    try:
        unshare_dashboard(dashboard_id, user_id)
        return jsonify({'success': True, 'message': '已取消分享'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@dashboard_bp.route('/<int:dashboard_id>/data', methods=['GET'])
@login_required_api
@dashboard_access_required(permission='view')
def get_dashboard_data(dashboard_id):
    charts = get_dashboard_charts(dashboard_id)
    results = {}

    for chart in charts:
        try:
            data = get_chart_data(chart.id)
            results[chart.id] = data
        except Exception as e:
            results[chart.id] = {'success': False, 'error': str(e)}

    return jsonify({
        'success': True,
        'data': results
    })
