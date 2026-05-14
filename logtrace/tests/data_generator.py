import os
import tempfile
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional

from logtrace.core.models import (
    LogRecord,
    NodeConfig,
    ExceptionRule,
    AlertRecord,
    LogStats,
    ExceptionContext
)


class TestDataBuilder:
    DEFAULT_NODE_ID = 'test_node_01'
    DEFAULT_NODE_NAME = '测试节点'
    DEFAULT_NODE_ADDRESS = '192.168.1.100'
    DEFAULT_LOG_PATH = '/var/log/app.log'

    LOG_LEVELS = ['debug', 'info', 'warning', 'error', 'fatal']
    LOG_SOURCES = ['application', 'database', 'system', 'network', 'security']

    INFO_MESSAGES = [
        'Application started successfully',
        'User login successful',
        'Request processed in 50ms',
        'Configuration loaded from /etc/app/config.yaml',
        'Health check passed',
        'Cache hit ratio: 95%',
        'Connection pool size: 10/50',
        'Task completed successfully'
    ]

    WARNING_MESSAGES = [
        'CPU usage exceeds 80%',
        'Memory usage exceeds 70%',
        'Low disk space warning',
        'Connection pool nearly exhausted',
        'Slow query detected',
        'Rate limit approaching',
        'Cache miss ratio increasing'
    ]

    ERROR_MESSAGES = [
        'Database connection failed: timeout after 30s',
        'NullPointerException occurred in UserService',
        'Failed to load configuration file',
        'Query execution failed: syntax error',
        'Authentication failed for user admin',
        'IOException: file not found',
        'SQLException: connection refused'
    ]

    FATAL_MESSAGES = [
        'System crash: out of memory',
        'Critical failure: database corrupted',
        'Kernel panic detected',
        'Disk failure: I/O error'
    ]

    DEBUG_MESSAGES = [
        'Entering function processRequest',
        'Variable value: 42',
        'Cache key: user_123',
        'Query: SELECT * FROM users WHERE id = ?',
        'Request params: page=1, limit=20'
    ]

    @classmethod
    def build_node_config(
        cls,
        node_id: str = DEFAULT_NODE_ID,
        node_name: str = DEFAULT_NODE_NAME,
        node_address: str = DEFAULT_NODE_ADDRESS,
        log_path: str = DEFAULT_LOG_PATH,
        collect_mode: str = 'realtime',
        collect_interval: int = 10,
        enabled: bool = True
    ) -> NodeConfig:
        return NodeConfig(
            node_id=node_id,
            node_name=node_name,
            node_address=node_address,
            log_path=log_path,
            collect_mode=collect_mode,
            collect_interval=collect_interval,
            enabled=enabled
        )

    @classmethod
    def build_multiple_nodes(cls, count: int = 3, base_id: str = 'node_') -> List[NodeConfig]:
        nodes = []
        for i in range(count):
            node_id = f'{base_id}{i + 1:02d}'
            nodes.append(cls.build_node_config(
                node_id=node_id,
                node_name=f'节点{i + 1}',
                node_address=f'192.168.1.{100 + i}',
                log_path=f'/var/log/app_{i + 1}.log'
            ))
        return nodes

    @classmethod
    def build_log_record(
        cls,
        node_id: str = DEFAULT_NODE_ID,
        log_level: str = 'info',
        log_source: str = 'application',
        log_content: Optional[str] = None,
        timestamp: Optional[datetime] = None,
        is_exception: bool = False,
        exception_type: Optional[str] = None,
        matched_rule_id: Optional[str] = None
    ) -> LogRecord:
        if log_content is None:
            if log_level == 'info':
                log_content = cls.INFO_MESSAGES[0]
            elif log_level == 'warning':
                log_content = cls.WARNING_MESSAGES[0]
            elif log_level == 'error':
                log_content = cls.ERROR_MESSAGES[0]
            elif log_level == 'fatal':
                log_content = cls.FATAL_MESSAGES[0]
            else:
                log_content = cls.DEBUG_MESSAGES[0]

        log = LogRecord.create(
            node_id=node_id,
            log_level=log_level,
            log_source=log_source,
            log_content=log_content,
            timestamp=timestamp or datetime.utcnow()
        )

        if is_exception:
            log.is_exception = True
            log.exception_type = exception_type or 'Unknown Exception'
            log.matched_rule_id = matched_rule_id

        return log

    @classmethod
    def build_info_logs(
        cls,
        count: int = 10,
        node_id: str = DEFAULT_NODE_ID
    ) -> List[LogRecord]:
        logs = []
        for i in range(count):
            logs.append(cls.build_log_record(
                node_id=node_id,
                log_level='info',
                log_content=cls.INFO_MESSAGES[i % len(cls.INFO_MESSAGES)]
            ))
        return logs

    @classmethod
    def build_warning_logs(
        cls,
        count: int = 5,
        node_id: str = DEFAULT_NODE_ID
    ) -> List[LogRecord]:
        logs = []
        for i in range(count):
            logs.append(cls.build_log_record(
                node_id=node_id,
                log_level='warning',
                log_content=cls.WARNING_MESSAGES[i % len(cls.WARNING_MESSAGES)]
            ))
        return logs

    @classmethod
    def build_error_logs(
        cls,
        count: int = 5,
        node_id: str = DEFAULT_NODE_ID,
        is_exception: bool = True
    ) -> List[LogRecord]:
        logs = []
        for i in range(count):
            logs.append(cls.build_log_record(
                node_id=node_id,
                log_level='error',
                log_content=cls.ERROR_MESSAGES[i % len(cls.ERROR_MESSAGES)],
                is_exception=is_exception,
                exception_type='错误日志识别',
                matched_rule_id='rule_error_pattern'
            ))
        return logs

    @classmethod
    def build_fatal_logs(
        cls,
        count: int = 2,
        node_id: str = DEFAULT_NODE_ID
    ) -> List[LogRecord]:
        logs = []
        for i in range(count):
            logs.append(cls.build_log_record(
                node_id=node_id,
                log_level='fatal',
                log_content=cls.FATAL_MESSAGES[i % len(cls.FATAL_MESSAGES)],
                is_exception=True,
                exception_type='致命错误',
                matched_rule_id='rule_fatal_pattern'
            ))
        return logs

    @classmethod
    def build_debug_logs(
        cls,
        count: int = 10,
        node_id: str = DEFAULT_NODE_ID
    ) -> List[LogRecord]:
        logs = []
        for i in range(count):
            logs.append(cls.build_log_record(
                node_id=node_id,
                log_level='debug',
                log_content=cls.DEBUG_MESSAGES[i % len(cls.DEBUG_MESSAGES)]
            ))
        return logs

    @classmethod
    def build_mixed_logs(
        cls,
        node_id: str = DEFAULT_NODE_ID,
        info_count: int = 5,
        warning_count: int = 2,
        error_count: int = 2,
        debug_count: int = 3
    ) -> List[LogRecord]:
        logs = []
        logs.extend(cls.build_info_logs(info_count, node_id))
        logs.extend(cls.build_warning_logs(warning_count, node_id))
        logs.extend(cls.build_error_logs(error_count, node_id))
        logs.extend(cls.build_debug_logs(debug_count, node_id))
        return logs

    @classmethod
    def build_exception_rule(
        cls,
        rule_id: str = 'rule_error_pattern',
        rule_name: str = '错误日志识别',
        pattern: str = 'error|exception|failed',
        log_level_filter: List[str] = None,
        severity: str = 'high',
        alert_enabled: bool = True,
        alert_threshold: int = 3,
        context_before_seconds: int = 5,
        context_after_seconds: int = 5
    ) -> ExceptionRule:
        return ExceptionRule(
            rule_id=rule_id,
            rule_name=rule_name,
            pattern=pattern,
            log_level_filter=log_level_filter or ['error', 'fatal'],
            severity=severity,
            alert_enabled=alert_enabled,
            alert_threshold=alert_threshold,
            context_before_seconds=context_before_seconds,
            context_after_seconds=context_after_seconds
        )

    @classmethod
    def build_default_rules(cls) -> List[ExceptionRule]:
        return [
            cls.build_exception_rule(
                rule_id='rule_error_pattern',
                rule_name='错误日志识别',
                pattern='error|exception|failed',
                log_level_filter=['error', 'fatal'],
                severity='high',
                alert_threshold=3
            ),
            cls.build_exception_rule(
                rule_id='rule_warning_pattern',
                rule_name='警告日志识别',
                pattern='warning|warn|timeout',
                log_level_filter=['warning'],
                severity='medium',
                alert_threshold=10
            )
        ]

    @classmethod
    def build_log_file(cls, temp_dir: str, lines_or_filename, filename: str = None) -> str:
        if isinstance(lines_or_filename, str) and filename is None:
            log_path = os.path.join(temp_dir, lines_or_filename)
            with open(log_path, 'w', encoding='utf-8') as f:
                pass
            return log_path
        elif isinstance(lines_or_filename, list) and filename is not None:
            log_path = os.path.join(temp_dir, filename)
            with open(log_path, 'w', encoding='utf-8') as f:
                for line in lines_or_filename:
                    f.write(line + '\n')
            return log_path
        elif isinstance(lines_or_filename, list):
            log_path = os.path.join(temp_dir, 'test.log')
            with open(log_path, 'w', encoding='utf-8') as f:
                for line in lines_or_filename:
                    f.write(line + '\n')
            return log_path
        else:
            raise ValueError("Invalid arguments")

    @classmethod
    def build_sample_log_lines(cls, count: int = 10) -> List[str]:
        now = datetime.utcnow()
        lines = []
        for i in range(count):
            timestamp = (now + timedelta(seconds=i)).strftime('%Y-%m-%d %H:%M:%S')
            if i % 5 == 0:
                level = 'ERROR'
                content = cls.ERROR_MESSAGES[i % len(cls.ERROR_MESSAGES)]
            elif i % 3 == 0:
                level = 'WARNING'
                content = cls.WARNING_MESSAGES[i % len(cls.WARNING_MESSAGES)]
            else:
                level = 'INFO'
                content = cls.INFO_MESSAGES[i % len(cls.INFO_MESSAGES)]
            lines.append(f"{timestamp} {level} {content}")
        return lines

    @classmethod
    def build_log_stats(
        cls,
        node_id: str = DEFAULT_NODE_ID,
        stat_date: Optional[str] = None,
        total_logs: int = 1000,
        error_count: int = 50,
        warning_count: int = 100,
        info_count: int = 850
    ) -> LogStats:
        stats = LogStats.create(
            node_id=node_id,
            stat_date=stat_date or datetime.utcnow().strftime('%Y-%m-%d')
        )
        stats.total_logs = total_logs
        stats.error_count = error_count
        stats.warning_count = warning_count
        stats.info_count = info_count
        return stats

    @classmethod
    def build_alert_record(
        cls,
        rule_id: str = 'rule_error_pattern',
        node_id: str = DEFAULT_NODE_ID,
        exception_count: int = 5,
        notify_channels: List[str] = None,
        context_id: Optional[str] = None
    ) -> AlertRecord:
        return AlertRecord.create(
            rule_id=rule_id,
            node_id=node_id,
            exception_count=exception_count,
            notify_channels=notify_channels or ['console'],
            context_id=context_id
        )

    @classmethod
    def build_exception_context(
        cls,
        exception_log: LogRecord,
        rule: ExceptionRule,
        before_logs: List[LogRecord] = None,
        after_logs: List[LogRecord] = None
    ) -> ExceptionContext:
        context = ExceptionContext.create(
            exception_log_id=exception_log.log_id,
            node_id=exception_log.node_id,
            rule_id=rule.rule_id,
            rule_name=rule.rule_name,
            exception_time=exception_log.timestamp,
            before_window_seconds=rule.context_before_seconds,
            after_window_seconds=rule.context_after_seconds
        )

        if before_logs:
            for log in before_logs:
                context.add_context_before(log.to_dict())

        if after_logs:
            for log in after_logs:
                context.add_context_after(log.to_dict())

        return context

    @classmethod
    def build_mock_es_search_response(
        cls,
        logs: List[LogRecord],
        total: Optional[int] = None
    ) -> Dict[str, Any]:
        return {
            'hits': {
                'total': {'value': total or len(logs)},
                'hits': [{'_source': log.to_dict()} for log in logs]
            }
        }

    @classmethod
    def build_mock_es_count_response(cls, count: int) -> Dict[str, Any]:
        return {'count': count}

    @classmethod
    def build_config_dict_for_nodes(cls, nodes: List[NodeConfig]) -> List[Dict[str, Any]]:
        return [
            {
                'node_id': node.node_id,
                'node_name': node.node_name,
                'node_address': node.node_address,
                'log_path': node.log_path,
                'collect_mode': node.collect_mode,
                'collect_interval': node.collect_interval,
                'enabled': node.enabled
            }
            for node in nodes
        ]

    @classmethod
    def build_config_dict_for_rules(cls, rules: List[ExceptionRule]) -> List[Dict[str, Any]]:
        return [
            {
                'rule_id': rule.rule_id,
                'rule_name': rule.rule_name,
                'pattern': rule.pattern,
                'log_level_filter': rule.log_level_filter,
                'severity': rule.severity,
                'alert_enabled': rule.alert_enabled,
                'alert_threshold': rule.alert_threshold,
                'context_before_seconds': rule.context_before_seconds,
                'context_after_seconds': rule.context_after_seconds
            }
            for rule in rules
        ]


builder = TestDataBuilder()
