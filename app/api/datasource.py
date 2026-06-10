from flask import Blueprint, request, jsonify
from flask_login import current_user
from app.services.datasource_service import (
    create_datasource, update_datasource, delete_datasource,
    get_datasource, get_user_datasources, test_datasource_connection,
    execute_query
)
from app.utils.decorators import (
    login_required_api, permission_required, validate_json, paginate
)

datasource_bp = Blueprint('datasource', __name__)


@datasource_bp.route('', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_datasources(page, per_page):
    search = request.args.get('search')
    type = request.args.get('type')
    team_id = request.args.get('team_id', type=int)

    pagination = get_user_datasources(
        user_id=current_user.id,
        page=page,
        per_page=per_page,
        search=search,
        type=type,
        team_id=team_id
    )

    return jsonify({
        'success': True,
        'data': {
            'items': [d.to_dict(include_config=False) for d in pagination.items],
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'pages': pagination.pages
        }
    })


@datasource_bp.route('/types', methods=['GET'])
@login_required_api
def list_datasource_types():
    from app.models import DataSource
    return jsonify({
        'success': True,
        'data': [{'value': k, 'label': v} for k, v in DataSource.TYPES.items()]
    })


@datasource_bp.route('/<int:datasource_id>', methods=['GET'])
@login_required_api
def get_datasource_detail(datasource_id):
    datasource = get_datasource(datasource_id)
    if not datasource:
        return jsonify({'success': False, 'message': '数据源不存在'}), 404

    if not (datasource.is_public or datasource.owner_id == current_user.id or
            current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    return jsonify({
        'success': True,
        'data': datasource.to_dict(include_config=True)
    })


@datasource_bp.route('', methods=['POST'])
@login_required_api
@permission_required('datasource:create')
@validate_json('name', 'type', 'connection_config')
def create_datasource_api():
    data = request.get_json()
    try:
        datasource = create_datasource(
            user_id=current_user.id,
            name=data['name'],
            type=data['type'],
            connection_config=data['connection_config'],
            description=data.get('description'),
            team_id=data.get('team_id'),
            query_templates=data.get('query_templates'),
            cache_ttl=data.get('cache_ttl'),
            is_public=data.get('is_public', False)
        )
        return jsonify({
            'success': True,
            'message': '创建成功',
            'data': datasource.to_dict(include_config=False)
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@datasource_bp.route('/<int:datasource_id>', methods=['PUT'])
@login_required_api
@validate_json()
def update_datasource_api(datasource_id):
    datasource = get_datasource(datasource_id)
    if not datasource:
        return jsonify({'success': False, 'message': '数据源不存在'}), 404

    if not (datasource.owner_id == current_user.id or current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限修改'}), 403

    data = request.get_json()
    try:
        datasource = update_datasource(datasource_id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': datasource.to_dict(include_config=False)
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@datasource_bp.route('/<int:datasource_id>', methods=['DELETE'])
@login_required_api
def delete_datasource_api(datasource_id):
    datasource = get_datasource(datasource_id)
    if not datasource:
        return jsonify({'success': False, 'message': '数据源不存在'}), 404

    if not (datasource.owner_id == current_user.id or current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限删除'}), 403

    try:
        delete_datasource(datasource_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@datasource_bp.route('/<int:datasource_id>/test', methods=['POST'])
@login_required_api
def test_connection_api(datasource_id):
    datasource = get_datasource(datasource_id)
    if not datasource:
        return jsonify({'success': False, 'message': '数据源不存在'}), 404

    if not (datasource.owner_id == current_user.id or current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    result = test_datasource_connection(datasource)
    return jsonify(result)


@datasource_bp.route('/test', methods=['POST'])
@login_required_api
@validate_json('type', 'connection_config')
def test_connection_new_api():
    data = request.get_json()
    from app.models import DataSource

    if data['type'] not in DataSource.TYPES:
        return jsonify({'success': False, 'error': '不支持的数据源类型'}), 400

    datasource = DataSource(
        type=data['type'],
        owner_id=current_user.id
    )
    datasource.set_connection_config(data['connection_config'])

    result = test_datasource_connection(datasource)
    return jsonify(result)


@datasource_bp.route('/<int:datasource_id>/query', methods=['POST'])
@login_required_api
@validate_json('query')
def execute_query_api(datasource_id):
    datasource = get_datasource(datasource_id)
    if not datasource:
        return jsonify({'success': False, 'message': '数据源不存在'}), 404

    if not (datasource.is_public or datasource.owner_id == current_user.id or
            current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    data = request.get_json()
    params = data.get('params', {})

    try:
        result = execute_query(datasource, data['query'], params)
        return jsonify(result)
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500
