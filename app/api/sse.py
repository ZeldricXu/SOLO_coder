import json
import time
from flask import Blueprint, request, Response, jsonify, current_app
from flask_login import current_user, login_required
from app import redis_client
from app.services.chart_service import get_chart_data
from app.services.dashboard_service import get_dashboard
from app.utils.decorators import login_required_api

sse_bp = Blueprint('sse', __name__)

subscriptions = {}


def get_redis_key(dashboard_id):
    return f"sse:dashboard:{dashboard_id}"


def format_sse(event, data):
    return f"event: {event}\ndata: {json.dumps(data, default=str, ensure_ascii=False)}\n\n"


@sse_bp.route('/dashboard/<int:dashboard_id>')
@login_required
def sse_dashboard(dashboard_id):
    from app.services.dashboard_service import get_dashboard
    dashboard = get_dashboard(dashboard_id)
    if not dashboard:
        return jsonify({'error': '看板不存在'}), 404

    if not current_user.has_dashboard_access(dashboard_id, 'view'):
        return jsonify({'error': '无权限访问'}), 403

    heartbeat_interval = current_app.config['SSE_HEARTBEAT_INTERVAL']
    refresh_interval = dashboard.refresh_interval or 30

    def generate():
        last_ping = time.time()
        last_refresh = time.time()

        yield format_sse('connected', {
            'dashboard_id': dashboard_id,
            'refresh_interval': refresh_interval,
            'heartbeat_interval': heartbeat_interval
        })

        while True:
            now = time.time()

            if now - last_ping >= heartbeat_interval:
                yield format_sse('ping', {'timestamp': now})
                last_ping = now

            if now - last_refresh >= refresh_interval:
                try:
                    from app.services.chart_service import get_dashboard_charts
                    charts = get_dashboard_charts(dashboard_id)
                    updates = {}

                    for chart in charts:
                        try:
                            data = get_chart_data(chart.id)
                            if data.get('success'):
                                updates[chart.id] = {
                                    'echarts_option': data.get('echarts_option'),
                                    'timestamp': now
                                }
                        except Exception:
                            continue

                    if updates:
                        yield format_sse('data_update', {
                            'dashboard_id': dashboard_id,
                            'updates': updates,
                            'timestamp': now
                        })
                except Exception:
                    pass

                last_refresh = now

            if redis_client:
                try:
                    message = redis_client.get(get_redis_key(dashboard_id))
                    if message:
                        yield format_sse('server_push', json.loads(message))
                        redis_client.delete(get_redis_key(dashboard_id))
                except Exception:
                    pass

            time.sleep(1)

    response = Response(
        generate(),
        mimetype='text/event-stream',
        headers={
            'Cache-Control': 'no-cache, no-transform',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no',
        }
    )
    response.timeout = None
    return response


@sse_bp.route('/push/<int:dashboard_id>', methods=['POST'])
@login_required_api
def push_data(dashboard_id):
    from app.models import Dashboard
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        return jsonify({'error': '看板不存在'}), 404

    if not current_user.has_dashboard_access(dashboard_id, 'edit'):
        return jsonify({'error': '无权限推送'}), 403

    data = request.get_json()
    if not data:
        return jsonify({'error': '缺少推送数据'}), 400

    if redis_client:
        try:
            redis_client.setex(
                get_redis_key(dashboard_id),
                60,
                json.dumps(data, default=str, ensure_ascii=False)
            )
            return jsonify({'success': True, 'message': '推送成功'})
        except Exception as e:
            return jsonify({'success': False, 'error': str(e)}), 500

    return jsonify({'success': False, 'error': 'Redis未连接'}), 500


@sse_bp.route('/chart/<int:chart_id>')
@login_required
def sse_chart(chart_id):
    from app.services.chart_service import get_chart
    chart = get_chart(chart_id)
    if not chart:
        return jsonify({'error': '图表不存在'}), 404

    if not current_user.has_dashboard_access(chart.dashboard_id, 'view'):
        return jsonify({'error': '无权限访问'}), 403

    heartbeat_interval = current_app.config['SSE_HEARTBEAT_INTERVAL']
    refresh_interval = chart.refresh_interval or 30

    def generate():
        last_ping = time.time()
        last_refresh = time.time()

        yield format_sse('connected', {
            'chart_id': chart_id,
            'refresh_interval': refresh_interval,
            'heartbeat_interval': heartbeat_interval
        })

        while True:
            now = time.time()

            if now - last_ping >= heartbeat_interval:
                yield format_sse('ping', {'timestamp': now})
                last_ping = now

            if now - last_refresh >= refresh_interval:
                try:
                    data = get_chart_data(chart_id)
                    if data.get('success'):
                        yield format_sse('data_update', {
                            'chart_id': chart_id,
                            'echarts_option': data.get('echarts_option'),
                            'timestamp': now
                        })
                except Exception:
                    pass

                last_refresh = now

            time.sleep(1)

    response = Response(
        generate(),
        mimetype='text/event-stream',
        headers={
            'Cache-Control': 'no-cache, no-transform',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no',
        }
    )
    response.timeout = None
    return response
