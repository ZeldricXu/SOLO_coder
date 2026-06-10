from flask import Blueprint, request, jsonify, send_file
from flask_login import current_user
from app.services.report_service import (
    create_report_schedule, update_report_schedule, delete_report_schedule,
    get_report_schedule, get_report_schedules, trigger_report_schedule,
    get_reports, get_report, generate_report, delete_report
)
from app.utils.decorators import (
    login_required_api, dashboard_access_required, validate_json, paginate
)

report_bp = Blueprint('report', __name__)


@report_bp.route('/schedules', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_schedules(page, per_page):
    dashboard_id = request.args.get('dashboard_id', type=int)

    pagination = get_report_schedules(
        user_id=current_user.id,
        dashboard_id=dashboard_id,
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


@report_bp.route('/schedules', methods=['POST'])
@login_required_api
@validate_json('name', 'dashboard_id', 'recipients')
def create_schedule_api():
    data = request.get_json()
    try:
        schedule = create_report_schedule(
            user_id=current_user.id,
            name=data['name'],
            dashboard_id=data['dashboard_id'],
            cron_expression=data.get('cron_expression'),
            interval_minutes=data.get('interval_minutes'),
            recipients=data['recipients'],
            report_type=data.get('report_type', 'pdf'),
            include_snapshot=data.get('include_snapshot', True),
            include_data=data.get('include_data', False),
            timezone=data.get('timezone', 'Asia/Shanghai')
        )
        return jsonify({
            'success': True,
            'message': '定时任务创建成功',
            'data': schedule.to_dict()
        }), 201
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@report_bp.route('/schedules/<int:schedule_id>', methods=['GET'])
@login_required_api
def get_schedule_api(schedule_id):
    schedule = get_report_schedule(schedule_id)
    if not schedule:
        return jsonify({'success': False, 'message': '定时任务不存在'}), 404

    if schedule.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    return jsonify({
        'success': True,
        'data': schedule.to_dict()
    })


@report_bp.route('/schedules/<int:schedule_id>', methods=['PUT'])
@login_required_api
@validate_json()
def update_schedule_api(schedule_id):
    schedule = get_report_schedule(schedule_id)
    if not schedule:
        return jsonify({'success': False, 'message': '定时任务不存在'}), 404

    if schedule.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限修改'}), 403

    data = request.get_json()
    try:
        schedule = update_report_schedule(schedule_id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': schedule.to_dict()
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@report_bp.route('/schedules/<int:schedule_id>', methods=['DELETE'])
@login_required_api
def delete_schedule_api(schedule_id):
    schedule = get_report_schedule(schedule_id)
    if not schedule:
        return jsonify({'success': False, 'message': '定时任务不存在'}), 404

    if schedule.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限删除'}), 403

    try:
        delete_report_schedule(schedule_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@report_bp.route('/schedules/<int:schedule_id>/trigger', methods=['POST'])
@login_required_api
def trigger_schedule_api(schedule_id):
    schedule = get_report_schedule(schedule_id)
    if not schedule:
        return jsonify({'success': False, 'message': '定时任务不存在'}), 404

    if schedule.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限操作'}), 403

    try:
        report = trigger_report_schedule(schedule_id)
        return jsonify({
            'success': True,
            'message': '任务已触发',
            'data': report.to_dict()
        }), 202
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@report_bp.route('', methods=['GET'])
@login_required_api
@paginate(default_per_page=20)
def list_reports(page, per_page):
    dashboard_id = request.args.get('dashboard_id', type=int)
    schedule_id = request.args.get('schedule_id', type=int)
    status = request.args.get('status')

    pagination = get_reports(
        user_id=current_user.id,
        dashboard_id=dashboard_id,
        schedule_id=schedule_id,
        status=status,
        page=page,
        per_page=per_page
    )

    return jsonify({
        'success': True,
        'data': {
            'items': [r.to_dict() for r in pagination.items],
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'pages': pagination.pages
        }
    })


@report_bp.route('/generate', methods=['POST'])
@login_required_api
@validate_json('dashboard_id')
def generate_report_api():
    data = request.get_json()
    try:
        report = generate_report(
            dashboard_id=data['dashboard_id'],
            user_id=current_user.id,
            report_type=data.get('report_type', 'pdf'),
            include_snapshot=data.get('include_snapshot', True),
            include_data=data.get('include_data', False),
            title=data.get('title')
        )
        return jsonify({
            'success': True,
            'message': '报表生成中',
            'data': report.to_dict()
        }), 202
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@report_bp.route('/<int:report_id>', methods=['GET'])
@login_required_api
def get_report_api(report_id):
    report = get_report(report_id)
    if not report:
        return jsonify({'success': False, 'message': '报表不存在'}), 404

    if report.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    return jsonify({
        'success': True,
        'data': report.to_dict()
    })


@report_bp.route('/<int:report_id>/download', methods=['GET'])
@login_required_api
def download_report(report_id):
    report = get_report(report_id)
    if not report:
        return jsonify({'success': False, 'message': '报表不存在'}), 404

    if report.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限访问'}), 403

    if report.status != 'completed':
        return jsonify({'success': False, 'message': '报表尚未生成完成'}), 400

    if not report.file_path:
        return jsonify({'success': False, 'message': '报表文件不存在'}), 404

    import os
    if not os.path.exists(report.file_path):
        return jsonify({'success': False, 'message': '报表文件不存在'}), 404

    filename = f"{report.title or 'report'}.{report.file_type}"
    return send_file(
        report.file_path,
        as_attachment=True,
        download_name=filename,
        mimetype=f'application/{report.file_type}'
    )


@report_bp.route('/<int:report_id>', methods=['DELETE'])
@login_required_api
def delete_report_api(report_id):
    report = get_report(report_id)
    if not report:
        return jsonify({'success': False, 'message': '报表不存在'}), 404

    if report.owner_id != current_user.id and not current_user.has_permission('all'):
        return jsonify({'success': False, 'message': '无权限删除'}), 403

    try:
        delete_report(report_id)
        return jsonify({'success': True, 'message': '删除成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
