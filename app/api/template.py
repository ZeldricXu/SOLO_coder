from flask import Blueprint, request, jsonify
from flask_login import current_user
from app.services.template_service import (
    create_template, update_template, delete_template, get_template,
    get_templates, rate_template, apply_template, create_template_from_dashboard
)
from app.utils.decorators import (
    login_required_api, permission_required, validate_json, paginate
)

template_bp = Blueprint('template', __name__)


@template_bp.route('', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_templates(page, per_page):
    search = request.args.get('search')
    category = request.args.get('category')
    is_system = request.args.get('system')
    if is_system is not None:
        is_system = is_system.lower() == 'true'

    pagination = get_templates(
        page=page,
        per_page=per_page,
        search=search,
        category=category,
        is_system=is_system,
        user_id=current_user.id
    )

    return jsonify({
        'success': True,
        'data': {
            'items': [t.to_dict() for t in pagination.items],
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'pages': pagination.pages
        }
    })


@template_bp.route('/categories', methods=['GET'])
@login_required_api
def list_categories():
    from app.models import Template
    return jsonify({
        'success': True,
        'data': [{'value': k, 'label': v} for k, v in Template.CATEGORIES.items()]
    })


@template_bp.route('/<int:template_id>', methods=['GET'])
@login_required_api
def get_template_detail(template_id):
    template = get_template(template_id)
    if not template:
        return jsonify({'success': False, 'message': '模板不存在'}), 404

    if not (template.is_public or template.created_by == current_user.id or
            current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    return jsonify({
        'success': True,
        'data': template.to_dict()
    })


@template_bp.route('', methods=['POST'])
@login_required_api
@permission_required('template:use')
@validate_json('name')
def create_template_api():
    data = request.get_json()
    try:
        template = create_template(
            user_id=current_user.id,
            name=data['name'],
            description=data.get('description'),
            category=data.get('category', 'custom'),
            dashboard_config=data.get('dashboard_config'),
            chart_configs=data.get('chart_configs'),
            datasource_config=data.get('datasource_config'),
            thumbnail=data.get('thumbnail'),
            preview_url=data.get('preview_url'),
            is_public=data.get('is_public', True)
        )
        return jsonify({
            'success': True,
            'message': '创建成功',
            'data': template.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@template_bp.route('/from-dashboard', methods=['POST'])
@login_required_api
@permission_required('template:use')
@validate_json('dashboard_id', 'name')
def create_from_dashboard_api():
    data = request.get_json()
    try:
        template = create_template_from_dashboard(
            dashboard_id=data['dashboard_id'],
            user_id=current_user.id,
            name=data['name'],
            description=data.get('description'),
            category=data.get('category', 'custom'),
            is_public=data.get('is_public', True)
        )
        return jsonify({
            'success': True,
            'message': '创建成功',
            'data': template.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@template_bp.route('/<int:template_id>', methods=['PUT'])
@login_required_api
@validate_json()
def update_template_api(template_id):
    template = get_template(template_id)
    if not template:
        return jsonify({'success': False, 'message': '模板不存在'}), 404

    if not (template.created_by == current_user.id or current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限修改'}), 403

    if template.is_system:
        return jsonify({'success': False, 'message': '系统模板不能修改'}), 403

    data = request.get_json()
    try:
        template = update_template(template_id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': template.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@template_bp.route('/<int:template_id>', methods=['DELETE'])
@login_required_api
def delete_template_api(template_id):
    template = get_template(template_id)
    if not template:
        return jsonify({'success': False, 'message': '模板不存在'}), 404

    if not (template.created_by == current_user.id or current_user.has_permission('all')):
        return jsonify({'success': False, 'message': '无权限删除'}), 403

    try:
        delete_template(template_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@template_bp.route('/<int:template_id>/apply', methods=['POST'])
@login_required_api
def apply_template_api(template_id):
    data = request.get_json() or {}
    try:
        dashboard, charts = apply_template(
            template_id=template_id,
            user_id=current_user.id,
            new_name=data.get('name'),
            datasource_mapping=data.get('datasource_mapping')
        )
        return jsonify({
            'success': True,
            'message': '模板应用成功',
            'data': {
                'dashboard': dashboard.to_dict(),
                'charts': [c.to_dict() for c in charts]
            }
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@template_bp.route('/<int:template_id>/rate', methods=['POST'])
@login_required_api
@validate_json('rating')
def rate_template_api(template_id):
    data = request.get_json()
    try:
        template = rate_template(template_id, data['rating'])
        return jsonify({
            'success': True,
            'message': '评分成功',
            'data': {'rating': template.rating, 'rating_count': template.rating_count}
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
