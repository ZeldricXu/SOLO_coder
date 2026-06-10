from flask import Blueprint, request, jsonify
from flask_login import current_user
from app.services.chart_service import (
    create_chart, update_chart, update_chart_position, update_chart_config,
    delete_chart, get_chart, get_dashboard_charts, get_chart_data,
    batch_update_chart_positions, copy_chart
)
from app.services.dashboard_service import get_dashboard
from app.utils.decorators import (
    login_required_api, permission_required, validate_json
)

chart_bp = Blueprint('chart', __name__)


@chart_bp.route('/dashboard/<int:dashboard_id>', methods=['GET'])
@login_required_api
def list_charts(dashboard_id):
    dashboard = get_dashboard(dashboard_id)
    if not dashboard:
        return jsonify({'success': False, 'message': '看板不存在'}), 404

    if not current_user.has_dashboard_access(dashboard_id, 'view'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    charts = get_dashboard_charts(dashboard_id)
    return jsonify({
        'success': True,
        'data': [c.to_dict() for c in charts]
    })


@chart_bp.route('/types', methods=['GET'])
@login_required_api
def list_chart_types():
    from app.models import Chart
    return jsonify({
        'success': True,
        'data': [{'value': k, 'label': v} for k, v in Chart.CHART_TYPES.items()]
    })


@chart_bp.route('/<int:chart_id>', methods=['GET'])
@login_required_api
def get_chart_detail(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'view'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    return jsonify({
        'success': True,
        'data': chart.to_dict()
    })


@chart_bp.route('', methods=['POST'])
@login_required_api
@permission_required('chart:create')
@validate_json('name', 'chart_type', 'dashboard_id')
def create_chart_api():
    data = request.get_json()

    if not current_user.has_dashboard_access(data['dashboard_id'], 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑此看板'}), 403

    try:
        chart = create_chart(
            user_id=current_user.id,
            dashboard_id=data['dashboard_id'],
            name=data['name'],
            chart_type=data['chart_type'],
            position=data.get('position'),
            datasource_id=data.get('datasource_id'),
            query_template=data.get('query_template'),
            query_params=data.get('query_params'),
            chart_config=data.get('chart_config'),
            description=data.get('description'),
            refresh_interval=data.get('refresh_interval')
        )
        return jsonify({
            'success': True,
            'message': '创建成功',
            'data': chart.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/<int:chart_id>', methods=['PUT'])
@login_required_api
@validate_json()
def update_chart_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑'}), 403

    data = request.get_json()
    try:
        chart = update_chart(chart_id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': chart.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/<int:chart_id>/position', methods=['PUT'])
@login_required_api
@validate_json('position')
def update_position_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑'}), 403

    data = request.get_json()
    try:
        chart = update_chart_position(chart_id, data['position'])
        return jsonify({
            'success': True,
            'message': '位置更新成功',
            'data': {'id': chart.id, 'position': chart.get_position()}
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/dashboard/<int:dashboard_id>/positions', methods=['PUT'])
@login_required_api
@validate_json('positions')
def batch_update_positions_api(dashboard_id):
    if not current_user.has_dashboard_access(dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑此看板'}), 403

    data = request.get_json()
    try:
        batch_update_chart_positions(dashboard_id, data['positions'])
        return jsonify({'success': True, 'message': '批量更新成功'})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/<int:chart_id>/config', methods=['PUT'])
@login_required_api
@validate_json('config')
def update_config_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑'}), 403

    data = request.get_json()
    try:
        chart = update_chart_config(chart_id, data['config'])
        return jsonify({
            'success': True,
            'message': '配置更新成功',
            'data': {'id': chart.id, 'chart_config': chart.get_chart_config()}
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/<int:chart_id>', methods=['DELETE'])
@login_required_api
def delete_chart_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限删除'}), 403

    try:
        delete_chart(chart_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@chart_bp.route('/<int:chart_id>/data', methods=['GET'])
@login_required_api
def get_chart_data_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'view'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    params = request.args.to_dict()
    try:
        result = get_chart_data(chart_id, params)
        return jsonify(result)
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


@chart_bp.route('/<int:chart_id>/data', methods=['POST'])
@login_required_api
def get_chart_data_post_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'view'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    data = request.get_json() or {}
    params = data.get('params', {})
    try:
        result = get_chart_data(chart_id, params)
        return jsonify(result)
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


@chart_bp.route('/<int:chart_id>/copy', methods=['POST'])
@login_required_api
def copy_chart_api(chart_id):
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'success': False, 'message': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'view'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    data = request.get_json() or {}
    new_dashboard_id = data.get('dashboard_id', chart.dashboard_id)

    if not current_user.has_dashboard_access(new_dashboard_id, 'edit'):
        return jsonify({'success': False, 'message': '无权限编辑目标看板'}), 403

    try:
        new_chart = copy_chart(chart_id, new_dashboard_id, current_user.id)
        return jsonify({
            'success': True,
            'message': '复制成功',
            'data': new_chart.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
