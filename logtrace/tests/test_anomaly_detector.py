import pytest
from unittest.mock import MagicMock, patch, call
from datetime import datetime

from logtrace.core.models import LogRecord, ExceptionRule
from logtrace.modules.anomaly_detector import AnomalyDetector


class TestAnomalyDetector:
    def test_detector_initialization(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        assert detector.config == mock_config_with_rules
        assert len(detector.rules) == 2
        assert detector.on_alert_triggered is None
        assert detector.exception_counters == {}

    def test_detector_initialization_no_rules(self, mock_config):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        assert len(detector.rules) == 0

    def test_add_rule(self, mock_config):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        rule = ExceptionRule(
            rule_id='test_rule',
            rule_name='Test Rule',
            pattern='test',
            log_level_filter=['error'],
            severity='high',
            alert_enabled=True,
            alert_threshold=5
        )

        detector.add_rule(rule)

        assert len(detector.rules) == 1
        assert detector.rules[0].rule_id == 'test_rule'

    def test_matches_rule_matching_level(self, mock_config, sample_exception_rule):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='error',
            log_source='database',
            log_content='Database connection failed'
        )

        assert detector._matches_rule(error_log, sample_exception_rule) == True

    def test_matches_rule_level_filter_rejects(self, mock_config, sample_exception_rule):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        info_log = LogRecord.create(
            node_id='node_01',
            log_level='info',
            log_source='application',
            log_content='Database connection failed info log'
        )

        assert detector._matches_rule(info_log, sample_exception_rule) == False

    def test_matches_rule_pattern_match(self, mock_config, sample_exception_rule):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='error',
            log_source='database',
            log_content='NullPointerException occurred'
        )

        assert detector._matches_rule(error_log, sample_exception_rule) == True

    def test_matches_rule_no_pattern_match(self, mock_config, sample_exception_rule):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='error',
            log_source='database',
            log_content='Normal operation completed successfully'
        )

        assert detector._matches_rule(error_log, sample_exception_rule) == False

    def test_matches_rule_case_insensitive(self, mock_config, sample_exception_rule):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='ERROR',
            log_source='database',
            log_content='DATABASE CONNECTION FAILED'
        )

        assert detector._matches_rule(error_log, sample_exception_rule) == True

    def test_matches_rule_invalid_regex(self, mock_config):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        invalid_rule = ExceptionRule(
            rule_id='invalid_rule',
            rule_name='Invalid Rule',
            pattern='[invalid',
            log_level_filter=['error'],
            severity='high',
            alert_enabled=True,
            alert_threshold=5
        )

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='error',
            log_source='database',
            log_content='Test error message'
        )

        assert detector._matches_rule(error_log, invalid_rule) == False

    def test_matches_rule_empty_level_filter(self, mock_config):
        mock_config.get_exception_rules.return_value = []
        detector = AnomalyDetector(mock_config)

        rule_no_filter = ExceptionRule(
            rule_id='no_filter_rule',
            rule_name='No Filter Rule',
            pattern='error',
            log_level_filter=[],
            severity='high',
            alert_enabled=True,
            alert_threshold=5
        )

        info_log = LogRecord.create(
            node_id='node_01',
            log_level='info',
            log_source='application',
            log_content='This is an error info message'
        )

        assert detector._matches_rule(info_log, rule_no_filter) == True

    def test_process_logs_marks_exception(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        error_log = LogRecord.create(
            node_id='node_01',
            log_level='error',
            log_source='database',
            log_content='Database connection failed'
        )

        processed = detector.process_logs([error_log])

        assert len(processed) == 1
        assert processed[0].is_exception == True
        assert processed[0].exception_type is not None
        assert processed[0].matched_rule_id is not None

    def test_process_logs_normal_log_not_marked(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        info_log = LogRecord.create(
            node_id='node_01',
            log_level='info',
            log_source='application',
            log_content='Application started successfully'
        )

        processed = detector.process_logs([info_log])

        assert len(processed) == 1
        assert processed[0].is_exception == False
        assert processed[0].exception_type is None
        assert processed[0].matched_rule_id is None

    def test_process_logs_batch(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        logs = [
            LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content='Database connection failed'
            ),
            LogRecord.create(
                node_id='node_01',
                log_level='info',
                log_source='application',
                log_content='Normal operation'
            ),
            LogRecord.create(
                node_id='node_01',
                log_level='warning',
                log_source='system',
                log_content='CPU warning: usage is high'
            )
        ]

        processed = detector.process_logs(logs)

        assert len(processed) == 3
        assert processed[0].is_exception == True
        assert processed[1].is_exception == False
        assert processed[2].is_exception == True

    def test_exception_counter_increments(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        for i in range(2):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            detector.process_logs([error_log])

        counters = detector.get_exception_counters()

        assert 'rule_error_pattern' in counters
        assert counters['rule_error_pattern']['node_01'] == 2

    def test_exception_counter_per_node(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        for node_id in ['node_01', 'node_02']:
            for i in range(2):
                error_log = LogRecord.create(
                    node_id=node_id,
                    log_level='error',
                    log_source='database',
                    log_content=f'Database error {i}'
                )
                detector.process_logs([error_log])

        counters = detector.get_exception_counters()

        assert 'rule_error_pattern' in counters
        assert counters['rule_error_pattern']['node_01'] == 2
        assert counters['rule_error_pattern']['node_02'] == 2

    def test_alert_triggered_at_threshold(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)
        alert_callback = MagicMock()
        detector.set_alert_callback(alert_callback)

        for i in range(3):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            detector.process_logs([error_log])

        alert_callback.assert_called_once()
        alert_data = alert_callback.call_args[0][0]
        assert alert_data['rule_id'] == 'rule_error_pattern'
        assert alert_data['node_id'] == 'node_01'
        assert alert_data['exception_count'] == 3

    def test_alert_disabled_not_triggered(self, mock_config):
        mock_config.get_exception_rules.return_value = [{
            'rule_id': 'disabled_alert_rule',
            'rule_name': 'Disabled Alert',
            'pattern': 'error',
            'log_level_filter': ['error'],
            'severity': 'high',
            'alert_enabled': False,
            'alert_threshold': 3
        }]

        detector = AnomalyDetector(mock_config)
        alert_callback = MagicMock()
        detector.set_alert_callback(alert_callback)

        for i in range(5):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            detector.process_logs([error_log])

        alert_callback.assert_not_called()

    def test_counter_reset_after_alert(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)
        alert_callback = MagicMock()
        detector.set_alert_callback(alert_callback)

        for i in range(3):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            detector.process_logs([error_log])

        counters = detector.get_exception_counters()

        assert counters['rule_error_pattern']['node_01'] == 0

    def test_reset_counters(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        for i in range(2):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            detector.process_logs([error_log])

        assert len(detector.get_exception_counters()) > 0

        detector.reset_counters()

        assert detector.get_exception_counters() == {}

    def test_set_alert_callback(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)
        callback = MagicMock()

        detector.set_alert_callback(callback)

        assert detector.on_alert_triggered == callback

    def test_alert_callback_exception_handled(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)
        callback = MagicMock(side_effect=Exception("Callback error"))
        detector.set_alert_callback(callback)

        for i in range(3):
            error_log = LogRecord.create(
                node_id='node_01',
                log_level='error',
                log_source='database',
                log_content=f'Database error {i}'
            )
            try:
                detector.process_logs([error_log])
            except Exception:
                pass

        callback.assert_called_once()

    def test_process_single_log_first_rule_matches(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        warning_log = LogRecord.create(
            node_id='node_01',
            log_level='warning',
            log_source='system',
            log_content='System timeout detected'
        )

        processed = detector._process_single_log(warning_log)

        assert processed.is_exception == True
        assert processed.matched_rule_id == 'rule_warning_pattern'

    def test_fatal_level_matches_error_filter(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules)

        fatal_log = LogRecord.create(
            node_id='node_01',
            log_level='fatal',
            log_source='system',
            log_content='System crash: exception in kernel'
        )

        processed = detector._process_single_log(fatal_log)

        assert processed.is_exception == True
