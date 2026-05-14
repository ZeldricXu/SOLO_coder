import pytest
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

from logtrace.core.models import LogRecord, ExceptionRule, ExceptionContext
from logtrace.modules.anomaly_detector import AnomalyDetector, LogBuffer
from logtrace.tests.data_generator import builder as data_builder


class TestLogBuffer:
    def test_buffer_initialization(self):
        buffer = LogBuffer(max_age_seconds=30, max_size=1000)

        assert buffer.max_age_seconds == 30
        assert buffer.max_size == 1000

    def test_add_log_to_buffer(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        log = data_builder.build_log_record(log_level='info')
        buffer.add_log(log)

        logs_in_range = buffer.get_logs_in_time_range(
            log.node_id,
            log.timestamp - timedelta(seconds=1),
            log.timestamp + timedelta(seconds=1)
        )

        assert len(logs_in_range) == 1
        assert logs_in_range[0].log_id == log.log_id

    def test_add_multiple_logs(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        logs = data_builder.build_info_logs(count=5)
        for log in logs:
            buffer.add_log(log)

        reference_time = datetime.utcnow()
        logs_in_range = buffer.get_logs_in_time_range(
            'test_node_01',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )

        assert len(logs_in_range) == 5

    def test_buffer_max_size_limit(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=3)

        logs = data_builder.build_info_logs(count=5)
        for log in logs:
            buffer.add_log(log)

        reference_time = datetime.utcnow()
        logs_in_range = buffer.get_logs_in_time_range(
            'test_node_01',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )

        assert len(logs_in_range) == 3

    def test_get_logs_before(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        now = datetime.utcnow()
        logs = []
        for i in range(5):
            log = data_builder.build_log_record(
                log_level='info',
                log_content=f'Log {i}',
                timestamp=now - timedelta(seconds=i)
            )
            logs.append(log)
            buffer.add_log(log)

        logs_before = buffer.get_logs_before(
            'test_node_01',
            now,
            window_seconds=3
        )

        assert len(logs_before) == 4

    def test_get_logs_after(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        now = datetime.utcnow()
        logs = []
        for i in range(5):
            log = data_builder.build_log_record(
                log_level='info',
                log_content=f'Log {i}',
                timestamp=now + timedelta(seconds=i)
            )
            logs.append(log)
            buffer.add_log(log)

        logs_after = buffer.get_logs_after(
            'test_node_01',
            now,
            window_seconds=3
        )

        assert len(logs_after) == 4

    def test_multiple_nodes_in_buffer(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        for node_id in ['node_01', 'node_02']:
            logs = data_builder.build_info_logs(count=3, node_id=node_id)
            for log in logs:
                buffer.add_log(log)

        reference_time = datetime.utcnow()

        node1_logs = buffer.get_logs_in_time_range(
            'node_01',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )
        node2_logs = buffer.get_logs_in_time_range(
            'node_02',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )

        assert len(node1_logs) == 3
        assert len(node2_logs) == 3

    def test_get_logs_for_nonexistent_node(self):
        buffer = LogBuffer(max_age_seconds=60, max_size=100)

        reference_time = datetime.utcnow()
        logs = buffer.get_logs_in_time_range(
            'nonexistent_node',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )

        assert len(logs) == 0


class TestExceptionContextFeature:
    def test_detector_with_context_enabled(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        assert detector.enable_context == True
        assert detector.log_buffer is not None
        assert detector.exception_contexts == {}

    def test_detector_with_context_disabled(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=False)

        assert detector.enable_context == False

    def test_process_logs_populates_buffer(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        logs = data_builder.build_info_logs(count=3)
        detector.process_logs(logs)

        reference_time = datetime.utcnow()
        logs_in_buffer = detector.log_buffer.get_logs_in_time_range(
            'test_node_01',
            reference_time - timedelta(seconds=60),
            reference_time + timedelta(seconds=60)
        )

        assert len(logs_in_buffer) == 3

    def test_exception_log_has_context_id(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=3)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )

        processed = detector.process_logs([error_log])

        assert len(processed) == 1
        assert processed[0].is_exception == True
        assert processed[0].context_id is not None

    def test_context_stored_in_detector(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=5)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        detector.process_logs([error_log])

        assert detector.get_context_count() == 1

    def test_get_context_by_id(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=5)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        processed = detector.process_logs([error_log])

        context_id = processed[0].context_id
        context = detector.get_context(context_id)

        assert context is not None
        assert context.exception_log_id == error_log.log_id

    def test_get_all_contexts(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        for i in range(3):
            info_logs = data_builder.build_info_logs(count=2)
            detector.process_logs(info_logs)

            error_log = data_builder.build_log_record(
                log_level='error',
                log_content=f'Error {i}',
                is_exception=False
            )
            detector.process_logs([error_log])

        contexts = detector.get_all_contexts()

        assert len(contexts) == 3

    def test_clear_contexts(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=2)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        detector.process_logs([error_log])

        assert detector.get_context_count() == 1

        detector.clear_contexts()

        assert detector.get_context_count() == 0

    def test_context_callback(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)
        context_callback = MagicMock()
        detector.set_context_callback(context_callback)

        info_logs = data_builder.build_info_logs(count=3)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        detector.process_logs([error_log])

        context_callback.assert_called_once()
        context = context_callback.call_args[0][0]
        assert isinstance(context, ExceptionContext)

    def test_get_detector_stats(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=3)
        detector.process_logs(info_logs)

        stats = detector.get_detector_stats()

        assert 'total_rules' in stats
        assert 'exception_counter_count' in stats
        assert 'context_count' in stats
        assert 'context_enabled' in stats
        assert stats['context_enabled'] == True

    def test_context_includes_before_logs(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        before_logs = data_builder.build_info_logs(count=5)
        detector.process_logs(before_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        processed = detector.process_logs([error_log])

        context_id = processed[0].context_id
        context = detector.get_context(context_id)

        assert len(context.context_logs_before) == 5

    def test_alert_includes_context_id(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)
        alert_callback = MagicMock()
        detector.set_alert_callback(alert_callback)

        for i in range(3):
            info_logs = data_builder.build_info_logs(count=1)
            detector.process_logs(info_logs)

            error_log = data_builder.build_log_record(
                log_level='error',
                log_content=f'Database error {i}',
                is_exception=False
            )
            detector.process_logs([error_log])

        alert_callback.assert_called_once()
        alert_data = alert_callback.call_args[0][0]
        assert 'context_id' in alert_data
        assert alert_data['context_id'] is not None

    def test_exception_context_to_dict(self, mock_config_with_rules):
        detector = AnomalyDetector(mock_config_with_rules, enable_context=True)

        info_logs = data_builder.build_info_logs(count=3)
        detector.process_logs(info_logs)

        error_log = data_builder.build_log_record(
            log_level='error',
            log_content='Database connection failed'
        )
        detector.process_logs([error_log])

        contexts = detector.get_all_contexts()
        context_dict = contexts[0].to_dict()

        assert 'context_id' in context_dict
        assert 'exception_log_id' in context_dict
        assert 'node_id' in context_dict
        assert 'context_logs_before' in context_dict
        assert 'context_logs_after' in context_dict
