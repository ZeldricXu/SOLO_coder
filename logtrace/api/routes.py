from flask import Flask, jsonify, request
from typing import Optional, Dict, Any


def create_app(
    searcher,
    stats_analyzer,
    viz_service,
    alert_manager,
    config
) -> Flask:
    app = Flask(__name__)

    @app.route('/health', methods=['GET'])
    def health():
        return jsonify({'code': 200, 'data': {'status': 'ok'}})

    @app.route('/api/v1/logs/search', methods=['GET'])
    def search_logs():
        keyword = request.args.get('keyword')
        start_time = request.args.get('start_time')
        end_time = request.args.get('end_time')
        log_level = request.args.get('log_level')
        node_id = request.args.get('node_id')
        page = int(request.args.get('page', 1))
        page_size = int(request.args.get('page_size', 50))

        result = searcher.search_logs(
            keyword=keyword,
            start_time=start_time,
            end_time=end_time,
            log_level=log_level,
            node_id=node_id,
            page=page,
            page_size=page_size
        )

        return jsonify({
            'code': 200,
            'data': result
        })

    @app.route('/api/v1/logs/exceptions', methods=['GET'])
    def search_exceptions():
        start_time = request.args.get('start_time')
        end_time = request.args.get('end_time')
        node_id = request.args.get('node_id')
        page = int(request.args.get('page', 1))
        page_size = int(request.args.get('page_size', 50))

        result = searcher.search_exceptions(
            start_time=start_time,
            end_time=end_time,
            node_id=node_id,
            page=page,
            page_size=page_size
        )

        return jsonify({
            'code': 200,
            'data': result
        })

    @app.route('/api/v1/logs/stats', methods=['GET'])
    def get_stats():
        node_id = request.args.get('node_id')
        start_date = request.args.get('start_date')
        end_date = request.args.get('end_date')

        result = stats_analyzer.get_stats(
            node_id=node_id,
            start_date=start_date,
            end_date=end_date
        )

        return jsonify({
            'code': 200,
            'data': {'stats': result}
        })

    @app.route('/api/v1/stats/daily', methods=['GET'])
    def get_daily_stats():
        days = int(request.args.get('days', 7))

        result = stats_analyzer.get_daily_stats(days=days)

        return jsonify({
            'code': 200,
            'data': {'daily_stats': result}
        })

    @app.route('/api/v1/visualization/dashboard', methods=['GET'])
    def get_dashboard():
        result = viz_service.get_dashboard_data()

        return jsonify({
            'code': 200,
            'data': result
        })

    @app.route('/api/v1/visualization/level-distribution', methods=['GET'])
    def get_level_distribution():
        hours = int(request.args.get('hours', 24))
        result = viz_service.get_log_level_distribution(hours=hours)

        return jsonify({
            'code': 200,
            'data': {'distribution': result}
        })

    @app.route('/api/v1/visualization/node-distribution', methods=['GET'])
    def get_node_distribution():
        hours = int(request.args.get('hours', 24))
        result = viz_service.get_node_log_distribution(hours=hours)

        return jsonify({
            'code': 200,
            'data': {'distribution': result}
        })

    @app.route('/api/v1/visualization/hourly-trend', methods=['GET'])
    def get_hourly_trend():
        hours = int(request.args.get('hours', 24))
        result = viz_service.get_hourly_trend(hours=hours)

        return jsonify({
            'code': 200,
            'data': {'trend': result}
        })

    @app.route('/api/v1/alerts/history', methods=['GET'])
    def get_alert_history():
        limit = int(request.args.get('limit', 100))
        result = alert_manager.get_alert_history(limit=limit)

        return jsonify({
            'code': 200,
            'data': {'alerts': result, 'count': len(result)}
        })

    @app.route('/api/v1/nodes', methods=['GET'])
    def get_nodes():
        nodes = config.get_nodes()

        return jsonify({
            'code': 200,
            'data': {'nodes': nodes}
        })

    @app.route('/api/v1/rules', methods=['GET'])
    def get_rules():
        rules = config.get_exception_rules()

        return jsonify({
            'code': 200,
            'data': {'rules': rules}
        })

    @app.errorhandler(404)
    def not_found(error):
        return jsonify({'code': 404, 'data': {'message': 'Not Found'}}), 404

    @app.errorhandler(500)
    def internal_error(error):
        return jsonify({'code': 500, 'data': {'message': 'Internal Server Error'}}), 500

    return app
