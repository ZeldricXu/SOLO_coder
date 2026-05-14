import os
import tempfile
import pytest
from unittest.mock import MagicMock, patch, call
from datetime import datetime
from pathlib import Path

from logtrace.core.models import NodeConfig, LogRecord
from logtrace.core.log_parser import LogParser
from logtrace.modules.collector import LogFileHandler, LogCollector


class TestLogFileHandler:
    def test_handler_initialization(self, test_node_config, log_parser):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        assert handler.node == test_node_config
        assert handler.on_log_collected == callback
        assert handler.parser == log_parser
        assert handler.last_position > 0

    def test_handler_initialization_nonexistent_file(self, log_parser, temp_dir):
        nonexistent_path = os.path.join(temp_dir, 'nonexistent.log')
        node = NodeConfig(
            node_id='test',
            node_name='test',
            node_address='127.0.0.1',
            log_path=nonexistent_path,
            collect_mode='realtime',
            collect_interval=10,
            enabled=True
        )
        callback = MagicMock()
        handler = LogFileHandler(node, callback, log_parser)

        assert handler.last_position == 0

    def test_process_line_creates_log_record(self, test_node_config, log_parser):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        test_line = "2026-05-04 16:00:00 INFO Test message"
        handler._process_line(test_line)

        callback.assert_called_once()
        log_record = callback.call_args[0][0]
        assert isinstance(log_record, LogRecord)
        assert log_record.node_id == test_node_config.node_id
        assert log_record.log_level == 'info'

    def test_process_line_parses_error_level(self, test_node_config, log_parser):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        test_line = "[ERROR] 2026-05-04 16:00:00 Database connection failed"
        handler._process_line(test_line)

        callback.assert_called_once()
        log_record = callback.call_args[0][0]
        assert log_record.log_level == 'error'

    def test_process_line_ignores_empty_line(self, test_node_config, log_parser):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        handler._process_line("   ")

        callback.assert_not_called()

    def test_read_new_lines_reads_new_content(self, test_node_config, log_parser, test_log_file):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        initial_position = handler.last_position

        with open(test_log_file, 'a', encoding='utf-8') as f:
            f.write("2026-05-04 16:00:03 INFO New log line\n")

        handler._read_new_lines()

        assert handler.last_position > initial_position
        callback.assert_called()

    def test_read_new_lines_handles_file_rotation(self, test_node_config, log_parser, test_log_file):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        with open(test_log_file, 'r', encoding='utf-8') as f:
            original_content = f.read()
        original_size = len(original_content)

        with open(test_log_file, 'w', encoding='utf-8') as f:
            f.write("Short log\n")

        handler.last_position = original_size

        handler._read_new_lines()

        assert handler.last_position < original_size

    def test_read_new_lines_no_change_does_nothing(self, test_node_config, log_parser, test_log_file):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)
        original_position = handler.last_position

        handler._read_new_lines()

        assert handler.last_position == original_position
        callback.assert_not_called()

    def test_on_modified_ignores_directory_events(self, test_node_config, log_parser):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        mock_event = MagicMock()
        mock_event.is_directory = True
        handler.on_modified(mock_event)

        callback.assert_not_called()

    def test_on_modified_ignores_other_files(self, test_node_config, log_parser, temp_dir):
        callback = MagicMock()
        handler = LogFileHandler(test_node_config, callback, log_parser)

        other_file = os.path.join(temp_dir, 'other.log')
        mock_event = MagicMock()
        mock_event.is_directory = False
        mock_event.src_path = other_file

        handler.on_modified(mock_event)

        callback.assert_not_called()


class TestLogCollector:
    def test_collector_initialization(self, mock_config):
        callback = MagicMock()
        collector = LogCollector(mock_config, callback)

        assert collector.config == mock_config
        assert collector.on_log_collected == callback
        assert isinstance(collector.parser, LogParser)
        assert collector.nodes == []
        assert collector.running == False

    def test_load_nodes_loads_enabled_nodes(self, mock_config):
        mock_config.get_nodes.return_value = [
            {
                'node_id': 'node_01',
                'node_name': 'Node 1',
                'node_address': '192.168.1.1',
                'log_path': '/var/log/node1.log',
                'collect_mode': 'realtime',
                'collect_interval': 10,
                'enabled': True
            },
            {
                'node_id': 'node_02',
                'node_name': 'Node 2',
                'node_address': '192.168.1.2',
                'log_path': '/var/log/node2.log',
                'collect_mode': 'scheduled',
                'collect_interval': 30,
                'enabled': False
            }
        ]

        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        assert len(collector.nodes) == 1
        assert collector.nodes[0].node_id == 'node_01'

    def test_collect_from_node_reads_file(self, mock_config, test_log_file, log_parser):
        mock_config.get_nodes.return_value = [{
            'node_id': 'test_node',
            'node_name': 'Test Node',
            'node_address': '127.0.0.1',
            'log_path': test_log_file,
            'collect_mode': 'scheduled',
            'collect_interval': 10,
            'enabled': True
        }]

        collected_logs = []
        def callback(log):
            collected_logs.append(log)

        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        node = collector.nodes[0]
        collector._collect_from_node(node)

        assert len(collected_logs) == 3

    def test_collect_from_node_nonexistent_file(self, mock_config, temp_dir):
        nonexistent_path = os.path.join(temp_dir, 'nonexistent.log')
        mock_config.get_nodes.return_value = [{
            'node_id': 'test_node',
            'node_name': 'Test Node',
            'node_address': '127.0.0.1',
            'log_path': nonexistent_path,
            'collect_mode': 'scheduled',
            'collect_interval': 10,
            'enabled': True
        }]

        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        node = collector.nodes[0]
        collector._collect_from_node(node)

        callback.assert_not_called()

    def test_parse_and_collect_creates_log_record(self, mock_config):
        mock_config.get_nodes.return_value = [{
            'node_id': 'test_node',
            'node_name': 'Test Node',
            'node_address': '127.0.0.1',
            'log_path': '/var/log/test.log',
            'collect_mode': 'scheduled',
            'collect_interval': 10,
            'enabled': True
        }]

        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        node = collector.nodes[0]
        collector._parse_and_collect(node, "2026-05-04 16:00:00 INFO Test message")

        callback.assert_called_once()
        log_record = callback.call_args[0][0]
        assert isinstance(log_record, LogRecord)
        assert log_record.node_id == 'test_node'
        assert log_record.log_level == 'info'

    def test_parse_and_collect_handles_parse_error(self, mock_config):
        mock_config.get_nodes.return_value = [{
            'node_id': 'test_node',
            'node_name': 'Test Node',
            'node_address': '127.0.0.1',
            'log_path': '/var/log/test.log',
            'collect_mode': 'scheduled',
            'collect_interval': 10,
            'enabled': True
        }]

        callback = MagicMock(side_effect=Exception("Parse error"))
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        node = collector.nodes[0]

        try:
            collector._parse_and_collect(node, "2026-05-04 16:00:00 INFO Test message")
        except Exception:
            pass

    def test_register_scheduled_collector(self, mock_config, test_log_file):
        mock_config.get_nodes.return_value = [{
            'node_id': 'test_node',
            'node_name': 'Test Node',
            'node_address': '127.0.0.1',
            'log_path': test_log_file,
            'collect_mode': 'scheduled',
            'collect_interval': 5,
            'enabled': True
        }]

        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        initial_collectors = len(collector.scheduled_collectors)
        collector._register_scheduled_collector(collector.nodes[0])

        assert len(collector.scheduled_collectors) == initial_collectors + 1

    def test_start_sets_running(self, mock_config):
        mock_config.get_nodes.return_value = []

        callback = MagicMock()
        collector = LogCollector(mock_config, callback)

        collector.start()

        assert collector.running == True

        collector.running = False
