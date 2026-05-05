import logging
from datetime import datetime, timedelta
from typing import Optional, List

from flask import Blueprint, request, jsonify

from app.models.alert import AlertRule, AlertEvent
from app.services.query import MetricQueryService
from app.services.alert_rules import AlertRuleManager
from app.services.alert_engine import AlertEngine
from app.services.alert_history import AlertHistoryManager
from app.services.storage import InfluxDBStorage
from app.services.collector import MetricCollector
from app.services.notifier import NotificationService
from app import config

logger = logging.getLogger(__name__)

api_bp = Blueprint('api', __name__)

_storage: Optional[InfluxDBStorage] = None
_query_service: Optional[MetricQueryService] = None
_rule_manager: Optional[AlertRuleManager] = None
_history_manager: Optional[AlertHistoryManager] = None
_alert_engine: Optional[AlertEngine] = None
_collector: Optional[MetricCollector] = None
_notification_service: Optional[NotificationService] = None


def _get_storage() -> InfluxDBStorage:
    global _storage
    if _storage is None:
        _storage = InfluxDBStorage(config['influxdb'])
    return _storage


def _get_query_service() -> MetricQueryService:
    global _query_service
    if _query_service is None:
        _query_service = MetricQueryService(_get_storage())
    return _query_service


def _get_rule_manager() -> AlertRuleManager:
    global _rule_manager
    if _rule_manager is None:
        _rule_manager = AlertRuleManager(initial_rules=config.get('alert_rules', []))
    return _rule_manager


def _get_history_manager() -> AlertHistoryManager:
    global _history_manager
    if _history_manager is None:
        from pathlib import Path
        _history_manager = AlertHistoryManager(
            storage_file=Path("data/alert_history.json")
        )
    return _history_manager


def _get_alert_engine() -> AlertEngine:
    global _alert_engine
    if _alert_engine is None:
        _alert_engine = AlertEngine(
            rule_manager=_get_rule_manager(),
            history_manager=_get_history_manager(),
            notification_service=_get_notification_service()
        )
    return _alert_engine


def _get_collector() -> MetricCollector:
    global _collector
    if _collector is None:
        _collector = MetricCollector(config['collector'])
    return _collector


def _get_notification_service() -> NotificationService:
    global _notification_service
    if _notification_service is None:
        _notification_service = NotificationService(config.get('notification', {}))
    return _notification_service


def _parse_datetime(dt_str: Optional[str], default: Optional[datetime] = None) -> Optional[datetime]:
    if not dt_str:
        return default
    try:
        from dateutil.parser import parse
        return parse(dt_str)
    except Exception:
        return default


def _success_response(data=None, message: str = "Success"):
    return jsonify({
        "code": 200,
        "message": message,
        "data": data
    })


def _error_response(message: str, code: int = 400):
    return jsonify({
        "code": code,
        "message": message,
        "data": None
    }), code


@api_bp.route('/health', methods=['GET'])
def health_check():
    return _success_response({
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat()
    })


@api_bp.route('/metrics/query', methods=['GET'])
def query_metrics():
    server_id = request.args.get('server_id')
    metric_type = request.args.get('metric_type')
    time_range_minutes = request.args.get('time_range_minutes', type=int)
    start_time_str = request.args.get('start_time')
    end_time_str = request.args.get('end_time')
    limit = request.args.get('limit', 1000, type=int)
    
    if not server_id or not metric_type:
        return _error_response("server_id and metric_type are required")
    
    start_time = _parse_datetime(start_time_str)
    end_time = _parse_datetime(end_time_str)
    
    query_service = _get_query_service()
    
    if time_range_minutes is not None:
        metrics = query_service.query_metrics(
            server_id=server_id,
            metric_type=metric_type,
            time_range_minutes=time_range_minutes,
            limit=limit
        )
    else:
        metrics = query_service.query_metrics(
            server_id=server_id,
            metric_type=metric_type,
            start_time=start_time,
            end_time=end_time,
            limit=limit
        )
    
    return _success_response({
        "metrics": [m.to_dict() for m in metrics],
        "count": len(metrics)
    })


@api_bp.route('/metrics/aggregated', methods=['GET'])
def query_aggregated_metrics():
    server_id = request.args.get('server_id')
    metric_type = request.args.get('metric_type')
    time_range_minutes = request.args.get('time_range_minutes', 60, type=int)
    interval_seconds = request.args.get('interval_seconds', 60, type=int)
    aggregator = request.args.get('aggregator', 'mean')
    
    if not server_id or not metric_type:
        return _error_response("server_id and metric_type are required")
    
    end_time = datetime.utcnow()
    start_time = end_time - timedelta(minutes=time_range_minutes)
    
    query_service = _get_query_service()
    
    results = query_service.query_aggregated(
        server_id=server_id,
        metric_type=metric_type,
        start_time=start_time,
        end_time=end_time,
        interval_seconds=interval_seconds,
        aggregator=aggregator
    )
    
    return _success_response({
        "data_points": results,
        "count": len(results),
        "aggregator": aggregator
    })


@api_bp.route('/metrics/latest', methods=['GET'])
def get_latest_metrics():
    server_id = request.args.get('server_id')
    lookback_minutes = request.args.get('lookback_minutes', 5, type=int)
    
    query_service = _get_query_service()
    
    if server_id:
        metrics = query_service.query_latest_metrics(
            server_id=server_id,
            lookback_minutes=lookback_minutes
        )
        result = {}
        for mt, m in metrics.items():
            result[mt] = m.to_dict() if m else None
        return _success_response(result)
    else:
        overview = query_service.query_servers_overview(lookback_minutes=lookback_minutes)
        return _success_response({
            "servers": overview,
            "count": len(overview)
        })


@api_bp.route('/metrics/servers', methods=['GET'])
def list_servers():
    query_service = _get_query_service()
    servers = query_service.list_servers()
    return _success_response({
        "servers": servers,
        "count": len(servers)
    })


@api_bp.route('/metrics/types', methods=['GET'])
def list_metric_types():
    server_id = request.args.get('server_id')
    query_service = _get_query_service()
    metric_types = query_service.list_metric_types(server_id)
    return _success_response({
        "metric_types": metric_types,
        "count": len(metric_types)
    })


@api_bp.route('/metrics/collect', methods=['POST'])
def collect_metrics_now():
    collector = _get_collector()
    storage = _get_storage()
    alert_engine = _get_alert_engine()
    
    metrics = collector.collect_all()
    if not metrics:
        return _success_response({
            "collected": 0,
            "written": 0,
            "alerts_triggered": 0
        }, "No metrics collected")
    
    written_count = storage.write_metrics_batch(metrics)
    
    alert_events = alert_engine.process_metrics_batch(metrics)
    
    return _success_response({
        "collected": len(metrics),
        "written": written_count,
        "alerts_triggered": len(alert_events),
        "metric_types": list(set([m.metric_type for m in metrics]))
    })


@api_bp.route('/alerts/rules', methods=['GET'])
def list_alert_rules():
    metric_type = request.args.get('metric_type')
    enabled_only = request.args.get('enabled_only', 'false').lower() == 'true'
    server_id = request.args.get('server_id')
    
    rule_manager = _get_rule_manager()
    rules = rule_manager.list_rules(
        metric_type=metric_type,
        enabled_only=enabled_only,
        server_id=server_id
    )
    
    return _success_response({
        "rules": [r.to_dict() for r in rules],
        "count": len(rules)
    })


@api_bp.route('/alerts/rules/<rule_id>', methods=['GET'])
def get_alert_rule(rule_id):
    rule_manager = _get_rule_manager()
    rule = rule_manager.get_rule(rule_id)
    
    if not rule:
        return _error_response(f"Rule not found: {rule_id}", 404)
    
    return _success_response(rule.to_dict())


@api_bp.route('/alerts/rules', methods=['POST'])
def create_alert_rule():
    try:
        data = request.get_json()
        if not data:
            return _error_response("No JSON data provided")
        
        required_fields = ['metric_type', 'threshold', 'operator', 'duration', 'severity', 'notify_channels']
        for field in required_fields:
            if field not in data:
                return _error_response(f"Missing required field: {field}")
        
        rule_manager = _get_rule_manager()
        
        rule = rule_manager.create_rule(
            metric_type=data['metric_type'],
            threshold=float(data['threshold']),
            operator=data['operator'],
            duration=int(data['duration']),
            severity=data['severity'],
            notify_channels=data['notify_channels'],
            silence_period=data.get('silence_period', 300),
            enabled=data.get('enabled', True),
            description=data.get('description', ''),
            server_filter=data.get('server_filter'),
            rule_id=data.get('rule_id')
        )
        
        return _success_response(rule.to_dict(), "Rule created successfully")
    except ValueError as e:
        return _error_response(str(e))
    except Exception as e:
        logger.error(f"Failed to create rule: {e}")
        return _error_response(f"Internal error: {str(e)}", 500)


@api_bp.route('/alerts/rules/<rule_id>', methods=['PUT'])
def update_alert_rule(rule_id):
    try:
        data = request.get_json()
        if not data:
            return _error_response("No JSON data provided")
        
        rule_manager = _get_rule_manager()
        
        allowed_updates = [
            'metric_type', 'threshold', 'operator', 'duration', 'severity',
            'notify_channels', 'silence_period', 'enabled', 'description', 'server_filter'
        ]
        
        updates = {k: v for k, v in data.items() if k in allowed_updates}
        
        rule = rule_manager.update_rule(rule_id, **updates)
        
        return _success_response(rule.to_dict(), "Rule updated successfully")
    except ValueError as e:
        return _error_response(str(e), 404)
    except Exception as e:
        logger.error(f"Failed to update rule: {e}")
        return _error_response(f"Internal error: {str(e)}", 500)


@api_bp.route('/alerts/rules/<rule_id>', methods=['DELETE'])
def delete_alert_rule(rule_id):
    rule_manager = _get_rule_manager()
    
    if rule_manager.delete_rule(rule_id):
        return _success_response(None, "Rule deleted successfully")
    else:
        return _error_response(f"Rule not found: {rule_id}", 404)


@api_bp.route('/alerts/rules/<rule_id>/enable', methods=['POST'])
def enable_alert_rule(rule_id):
    rule_manager = _get_rule_manager()
    
    if rule_manager.enable_rule(rule_id):
        return _success_response(None, "Rule enabled")
    else:
        return _error_response(f"Rule not found: {rule_id}", 404)


@api_bp.route('/alerts/rules/<rule_id>/disable', methods=['POST'])
def disable_alert_rule(rule_id):
    rule_manager = _get_rule_manager()
    
    if rule_manager.disable_rule(rule_id):
        return _success_response(None, "Rule disabled")
    else:
        return _error_response(f"Rule not found: {rule_id}", 404)


@api_bp.route('/alerts/history', methods=['GET'])
def query_alert_history():
    server_id = request.args.get('server_id')
    rule_id = request.args.get('rule_id')
    metric_type = request.args.get('metric_type')
    status = request.args.get('status')
    severity = request.args.get('severity')
    start_time_str = request.args.get('start_time')
    end_time_str = request.args.get('end_time')
    limit = request.args.get('limit', 100, type=int)
    offset = request.args.get('offset', 0, type=int)
    
    start_time = _parse_datetime(start_time_str)
    end_time = _parse_datetime(end_time_str)
    
    history_manager = _get_history_manager()
    
    alerts = history_manager.query_alerts(
        server_id=server_id,
        rule_id=rule_id,
        metric_type=metric_type,
        status=status,
        severity=severity,
        start_time=start_time,
        end_time=end_time,
        limit=limit,
        offset=offset
    )
    
    return _success_response({
        "alerts": [a.to_dict() for a in alerts],
        "count": len(alerts),
        "limit": limit,
        "offset": offset
    })


@api_bp.route('/alerts/history/<alert_id>', methods=['GET'])
def get_alert_history_item(alert_id):
    history_manager = _get_history_manager()
    alert = history_manager.get_alert(alert_id)
    
    if not alert:
        return _error_response(f"Alert not found: {alert_id}", 404)
    
    return _success_response(alert.to_dict())


@api_bp.route('/alerts/history/<alert_id>/acknowledge', methods=['POST'])
def acknowledge_alert(alert_id):
    history_manager = _get_history_manager()
    alert = history_manager.acknowledge_alert(alert_id)
    
    if not alert:
        return _error_response(f"Alert not found: {alert_id}", 404)
    
    return _success_response(alert.to_dict(), "Alert acknowledged")


@api_bp.route('/alerts/active', methods=['GET'])
def get_active_alerts():
    server_id = request.args.get('server_id')
    rule_id = request.args.get('rule_id')
    
    alert_engine = _get_alert_engine()
    alerts = alert_engine.get_active_alerts(server_id=server_id, rule_id=rule_id)
    
    return _success_response({
        "alerts": [a.to_dict() for a in alerts],
        "count": len(alerts)
    })


@api_bp.route('/alerts/statistics', methods=['GET'])
def get_alert_statistics():
    start_time_str = request.args.get('start_time')
    end_time_str = request.args.get('end_time')
    
    start_time = _parse_datetime(start_time_str, datetime.utcnow() - timedelta(days=30))
    end_time = _parse_datetime(end_time_str, datetime.utcnow())
    
    history_manager = _get_history_manager()
    stats = history_manager.get_statistics(start_time=start_time, end_time=end_time)
    
    return _success_response(stats)


@api_bp.route('/notifications/test', methods=['POST'])
def test_notification():
    channel = request.args.get('channel', 'email')
    
    notification_service = _get_notification_service()
    
    success = notification_service.test_channel(channel)
    
    if success:
        return _success_response({
            "channel": channel,
            "sent": success
        }, "Test notification sent successfully")
    else:
        return _error_response(f"Failed to send test notification via {channel}")


@api_bp.route('/system/info', methods=['GET'])
def get_system_info():
    collector = _get_collector()
    info = collector.get_system_info()
    
    return _success_response(info)


@api_bp.route('/system/config', methods=['GET'])
def get_config_info():
    return _success_response({
        "server": {
            "host": config['server']['host'],
            "port": config['server']['port'],
            "debug": config['server']['debug']
        },
        "collector": {
            "interval_seconds": config['collector']['interval_seconds'],
            "server_id": config['collector']['server_id'],
            "enabled_metrics": config['collector']['enabled_metrics'],
            "collect_mode": config['collector'].get('collect_mode', 'local'),
            "remote_servers_count": len(config['collector'].get('remote_servers', []))
        },
        "alert": {
            "silence_default_seconds": config['alert']['silence_default_seconds'],
            "silence_by_severity": config['alert'].get('silence_by_severity', {})
        },
        "redis": {
            "host": config.get('redis', {}).get('host', 'localhost'),
            "port": config.get('redis', {}).get('port', 6379),
            "notification_queue_key": config.get('redis', {}).get('notification_queue_key')
        },
        "ssh_pool": config.get('ssh_pool', {}),
        "notification": {
            "async_enabled": config.get('notification', {}).get('async_enabled', True),
            "worker_count": config.get('notification', {}).get('worker_count', 2),
            "use_redis_persistence": config.get('notification', {}).get('use_redis_persistence', True)
        }
    })


@api_bp.route('/alerts/silences', methods=['GET'])
def list_silences():
    rule_id = request.args.get('rule_id')
    server_id = request.args.get('server_id')
    
    alert_engine = _get_alert_engine()
    silences = alert_engine.list_active_silences(rule_id=rule_id, server_id=server_id)
    
    def silence_to_dict(s):
        return {
            "silence_id": s.silence_id,
            "silence_type": s.silence_type.value if hasattr(s.silence_type, 'value') else s.silence_type,
            "rule_id": s.rule_id,
            "server_id": s.server_id,
            "start_time": s.start_time.isoformat() if s.start_time else None,
            "end_time": s.end_time.isoformat() if s.end_time else None,
            "reason": s.reason,
            "created_by": s.created_by,
            "is_active": s.is_active,
            "is_expired": s.is_expired()
        }
    
    return _success_response({
        "silences": [silence_to_dict(s) for s in silences],
        "count": len(silences)
    })


@api_bp.route('/alerts/silences/<silence_id>', methods=['GET'])
def get_silence(silence_id):
    alert_engine = _get_alert_engine()
    silence = alert_engine.silence_manager.get_silence(silence_id)
    
    if not silence:
        return _error_response(f"Silence not found: {silence_id}", 404)
    
    return _success_response({
        "silence_id": silence.silence_id,
        "silence_type": silence.silence_type.value if hasattr(silence.silence_type, 'value') else silence.silence_type,
        "rule_id": silence.rule_id,
        "server_id": silence.server_id,
        "start_time": silence.start_time.isoformat() if silence.start_time else None,
        "end_time": silence.end_time.isoformat() if silence.end_time else None,
        "reason": silence.reason,
        "created_by": silence.created_by,
        "is_active": silence.is_active,
        "is_expired": silence.is_expired()
    })


@api_bp.route('/alerts/silences', methods=['POST'])
def create_silence():
    try:
        data = request.get_json()
        if not data:
            return _error_response("No JSON data provided")
        
        silence_type = data.get('silence_type', 'rule_server')
        duration_seconds = data.get('duration_seconds', 300)
        reason = data.get('reason', '')
        created_by = data.get('created_by', 'api')
        
        from app.services.alert_engine import SilenceType
        
        try:
            st = SilenceType(silence_type)
        except ValueError:
            return _error_response(f"Invalid silence_type: {silence_type}. Valid types: rule_server, rule, server, global")
        
        alert_engine = _get_alert_engine()
        
        if st == SilenceType.RULE_SERVER:
            if not data.get('rule_id') or not data.get('server_id'):
                return _error_response("rule_id and server_id are required for silence_type 'rule_server'")
            
            silence = alert_engine.silence_rule_server(
                rule_id=data['rule_id'],
                server_id=data['server_id'],
                duration_seconds=duration_seconds,
                reason=reason,
                created_by=created_by
            )
        elif st == SilenceType.RULE:
            if not data.get('rule_id'):
                return _error_response("rule_id is required for silence_type 'rule'")
            
            silence = alert_engine.silence_rule(
                rule_id=data['rule_id'],
                duration_seconds=duration_seconds,
                reason=reason,
                created_by=created_by
            )
        elif st == SilenceType.SERVER:
            if not data.get('server_id'):
                return _error_response("server_id is required for silence_type 'server'")
            
            silence = alert_engine.silence_server(
                server_id=data['server_id'],
                duration_seconds=duration_seconds,
                reason=reason,
                created_by=created_by
            )
        elif st == SilenceType.GLOBAL:
            silence = alert_engine.silence_global(
                duration_seconds=duration_seconds,
                reason=reason,
                created_by=created_by
            )
        else:
            return _error_response(f"Unsupported silence_type: {silence_type}")
        
        return _success_response({
            "silence_id": silence.silence_id,
            "silence_type": silence.silence_type.value if hasattr(silence.silence_type, 'value') else silence.silence_type
        }, "Silence created successfully")
        
    except Exception as e:
        logger.error(f"Failed to create silence: {e}")
        return _error_response(f"Internal error: {str(e)}", 500)


@api_bp.route('/alerts/silences/<silence_id>', methods=['DELETE'])
def cancel_silence(silence_id):
    alert_engine = _get_alert_engine()
    
    if alert_engine.cancel_silence(silence_id):
        return _success_response(None, "Silence cancelled successfully")
    else:
        return _error_response(f"Silence not found: {silence_id}", 404)


@api_bp.route('/notifications/queue/status', methods=['GET'])
def get_notification_queue_status():
    alert_engine = _get_alert_engine()
    status = alert_engine.get_notification_queue_status()
    
    return _success_response(status)


@api_bp.route('/notifications/queue/retry', methods=['POST'])
def retry_failed_notifications():
    alert_engine = _get_alert_engine()
    retried_count = alert_engine.retry_failed_notifications()
    
    return _success_response({
        "retried_count": retried_count
    }, f"Retried {retried_count} failed notifications")


@api_bp.route('/notifications/failed', methods=['GET'])
def get_failed_notifications():
    alert_engine = _get_alert_engine()
    failed_tasks = alert_engine.notification_queue.get_failed_tasks()
    
    def task_to_dict(t):
        return {
            "task_id": t.task_id,
            "alert_id": t.alert_id,
            "is_resolved": t.is_resolved,
            "channels": t.channels,
            "created_at": t.created_at.isoformat() if t.created_at else None,
            "retry_count": t.retry_count,
            "max_retries": t.max_retries
        }
    
    return _success_response({
        "failed_notifications": [task_to_dict(t) for t in failed_tasks],
        "count": len(failed_tasks)
    })


@api_bp.route('/notifications/failed/clear', methods=['POST'])
def clear_failed_notifications():
    alert_engine = _get_alert_engine()
    cleared_count = alert_engine.notification_queue.clear_failed_tasks()
    
    return _success_response({
        "cleared_count": cleared_count
    }, f"Cleared {cleared_count} failed notifications")


_ssh_pool: Optional['SSHConnectionPool'] = None


def _get_ssh_pool():
    global _ssh_pool
    if _ssh_pool is None:
        from app.services.ssh_pool import SSHConnectionPool
        
        ssh_config = config.get('ssh_pool', {})
        _ssh_pool = SSHConnectionPool(
            max_connections_per_server=ssh_config.get('max_connections_per_server', 3),
            max_total_connections=ssh_config.get('max_total_connections', 10),
            idle_timeout_seconds=ssh_config.get('idle_timeout_seconds', 300),
            cleanup_interval_seconds=ssh_config.get('cleanup_interval_seconds', 60),
            health_check_interval_seconds=ssh_config.get('health_check_interval_seconds', 30),
            health_check_command=ssh_config.get('health_check_command', 'echo 1'),
            connection_retry_count=ssh_config.get('connection_retry_count', 3),
            connection_retry_delay_seconds=ssh_config.get('connection_retry_delay_seconds', 2),
            max_health_check_failures=ssh_config.get('max_health_check_failures', 3),
            max_connection_age_seconds=ssh_config.get('max_connection_age_seconds', 3600)
        )
        
        collector = _get_collector()
        collector.set_ssh_pool(_ssh_pool)
    
    return _ssh_pool


@api_bp.route('/ssh/pool/status', methods=['GET'])
def get_ssh_pool_status():
    ssh_pool = _get_ssh_pool()
    status = ssh_pool.get_pool_status()
    
    return _success_response(status)


@api_bp.route('/ssh/servers', methods=['GET'])
def list_ssh_servers():
    collector = _get_collector()
    server_ids = collector.get_remote_servers()
    
    servers = []
    for sid in server_ids:
        if sid in collector._remote_servers:
            config_obj = collector._remote_servers[sid]
            servers.append({
                "server_id": config_obj.server_id,
                "host": config_obj.host,
                "port": config_obj.port,
                "username": config_obj.username
            })
    
    return _success_response({
        "servers": servers,
        "count": len(servers)
    })


@api_bp.route('/ssh/servers', methods=['POST'])
def add_ssh_server():
    try:
        data = request.get_json()
        if not data:
            return _error_response("No JSON data provided")
        
        required_fields = ['server_id', 'host', 'username']
        for field in required_fields:
            if field not in data:
                return _error_response(f"Missing required field: {field}")
        
        from app.services.ssh_pool import SSHConnectionConfig
        
        ssh_config = SSHConnectionConfig(
            server_id=data['server_id'],
            host=data['host'],
            port=data.get('port', 22),
            username=data['username'],
            password=data.get('password'),
            private_key_path=data.get('private_key_path'),
            private_key_passphrase=data.get('private_key_passphrase'),
            timeout=data.get('timeout', 10)
        )
        
        collector = _get_collector()
        collector.add_remote_server(ssh_config)
        
        return _success_response({
            "server_id": data['server_id'],
            "host": data['host']
        }, "SSH server added successfully")
        
    except Exception as e:
        logger.error(f"Failed to add SSH server: {e}")
        return _error_response(f"Internal error: {str(e)}", 500)


@api_bp.route('/ssh/servers/<server_id>', methods=['DELETE'])
def remove_ssh_server(server_id):
    collector = _get_collector()
    
    if collector.remove_remote_server(server_id):
        return _success_response(None, "SSH server removed successfully")
    else:
        return _error_response(f"SSH server not found: {server_id}", 404)


@api_bp.route('/ssh/servers/<server_id>/test', methods=['POST'])
def test_ssh_connection(server_id):
    ssh_pool = _get_ssh_pool()
    collector = _get_collector()
    
    if server_id not in collector._remote_servers:
        return _error_response(f"SSH server not configured: {server_id}", 404)
    
    success, message = ssh_pool.test_connection(server_id)
    
    if success:
        return _success_response({
            "server_id": server_id,
            "connected": True
        }, "SSH connection test successful")
    else:
        return _error_response(f"Test failed: {message}", 500)


@api_bp.route('/ssh/servers/<server_id>/reconnect', methods=['POST'])
def reconnect_ssh_server(server_id):
    ssh_pool = _get_ssh_pool()
    collector = _get_collector()
    
    if server_id not in collector._remote_servers:
        return _error_response(f"SSH server not configured: {server_id}", 404)
    
    success, message = ssh_pool.force_reconnect(server_id)
    
    if success:
        return _success_response({
            "server_id": server_id,
            "reconnected": True,
            "message": message
        }, message)
    else:
        return _error_response(f"Reconnect failed: {message}", 500)


@api_bp.route('/ssh/pool/reset', methods=['POST'])
def reset_ssh_pool():
    global _ssh_pool
    
    if _ssh_pool:
        _ssh_pool.close_all_connections()
        _ssh_pool = None
    
    return _success_response(None, "SSH connection pool reset successfully")
