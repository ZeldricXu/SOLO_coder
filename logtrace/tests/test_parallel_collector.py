import os
import time
import pytest
from unittest.mock import MagicMock, patch
from datetime import datetime, timedelta

from logtrace.modules.collector import ParallelCollector, LogCollector
from logtrace.core.log_parser import LogParser
from logtrace.tests.data_generator import builder as data_builder


class TestParallelCollector:
    def test_parallel_collector_initialization(self):
        parser = LogParser()
        callback = MagicMock()

        collector = ParallelCollector(parser, callback, max_workers=3)

        assert collector.parser == parser
        assert collector.on_log_collected == callback
        assert collector.max_workers == 3
        assert collector.running == False

    def test_collect_nodes_parallel_empty_nodes(self):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback)

        results = collector.collect_nodes_parallel([])

        assert results == {}
        callback.assert_not_called()

    def test_collect_single_node(self, temp_dir):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback)

        log_lines = data_builder.build_sample_log_lines(count=5)
        log_path = data_builder.build_log_file(temp_dir, log_lines)
        node = data_builder.build_node_config(log_path=log_path)

        results = collector.collect_nodes_parallel([node])

        assert node.node_id in results
        assert results[node.node_id] == 5
        assert callback.call_count == 5

    def test_collect_multiple_nodes_parallel(self, temp_dir):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback, max_workers=3)

        nodes = []
        for i in range(3):
            log_lines = data_builder.build_sample_log_lines(count=2 + i)
            log_path = data_builder.build_log_file(temp_dir, f'node_{i}.log')
            with open(log_path, 'w', encoding='utf-8') as f:
                for line in log_lines:
                    f.write(line + '\n')
            node = data_builder.build_node_config(
                node_id=f'node_{i}',
                log_path=log_path
            )
            nodes.append(node)

        results = collector.collect_nodes_parallel(nodes)

        assert len(results) == 3
        for node in nodes:
            assert node.node_id in results
        total = sum(results.values())
        assert total == 9

    def test_collect_from_nonexistent_file(self):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback)

        node = data_builder.build_node_config(log_path='/nonexistent/path.log')

        results = collector.collect_nodes_parallel([node])

        assert results[node.node_id] == 0
        callback.assert_not_called()

    def test_node_position_tracking(self, temp_dir):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback)

        log_path = data_builder.build_log_file(
            temp_dir,
            data_builder.build_sample_log_lines(count=3)
        )
        node = data_builder.build_node_config(log_path=log_path)

        results1 = collector.collect_nodes_parallel([node])
        assert results1[node.node_id] == 3

        results2 = collector.collect_nodes_parallel([node])
        assert results2[node.node_id] == 0

    def test_file_rotation_resets_position(self, temp_dir):
        parser = LogParser()
        callback = MagicMock()
        collector = ParallelCollector(parser, callback)

        log_path = data_builder.build_log_file(
            temp_dir,
            data_builder.build_sample_log_lines(count=10)
        )
        node = data_builder.build_node_config(log_path=log_path)

        collector.collect_nodes_parallel([node])
        callback.reset_mock()

        with open(log_path, 'w', encoding='utf-8') as f:
            f.write("2026-05-10 10:00:00 INFO New log after rotation\n")

        results = collector.collect_nodes_parallel([node])

        assert results[node.node_id] == 1
        callback.assert_called_once()

    def test_collect_with_exception_handling(self, temp_dir):
        parser = LogParser()
        callback = MagicMock(side_effect=Exception("Callback error"))
        collector = ParallelCollector(parser, callback)

        log_path = data_builder.build_log_file(
            temp_dir,
            data_builder.build_sample_log_lines(count=2)
        )
        node = data_builder.build_node_config(log_path=log_path)

        try:
            results = collector.collect_nodes_parallel([node])
        except Exception:
            pass

        assert node.node_id in results


class TestLogCollectorParallelFeatures:
    def test_collector_with_parallel_collector(self, mock_config):
        callback = MagicMock()
        collector = LogCollector(mock_config, callback, max_workers=5)

        assert collector.max_workers == 5
        assert collector.parallel_collector is not None
        assert collector.parallel_collector.max_workers == 5

    def test_get_collection_stats(self, mock_config):
        callback = MagicMock()
        collector = LogCollector(mock_config, callback)

        stats = collector.get_collection_stats()

        assert 'total_nodes' in stats
        assert 'realtime_nodes' in stats
        assert 'scheduled_nodes' in stats
        assert 'max_workers' in stats
        assert 'running' in stats

    def test_collect_all_scheduled_nodes_empty(self, mock_config):
        mock_config.get_nodes.return_value = []
        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        results = collector.collect_all_scheduled_nodes()

        assert results == {}

    def test_collect_all_scheduled_nodes(self, mock_config, temp_dir):
        nodes = []
        for i in range(2):
            log_lines = data_builder.build_sample_log_lines(count=3)
            log_path = data_builder.build_log_file(temp_dir, f'sched_{i}.log')
            with open(log_path, 'w', encoding='utf-8') as f:
                for line in log_lines:
                    f.write(line + '\n')
            node_dict = {
                'node_id': f'sched_node_{i}',
                'node_name': f'节点{i}',
                'node_address': f'192.168.1.{100 + i}',
                'log_path': log_path,
                'collect_mode': 'scheduled',
                'collect_interval': 5,
                'enabled': True
            }
            nodes.append(node_dict)

        mock_config.get_nodes.return_value = nodes
        callback = MagicMock()
        collector = LogCollector(mock_config, callback)
        collector._load_nodes()

        results = collector.collect_all_scheduled_nodes()

        assert len(results) == 2
        total = sum(results.values())
        assert total == 6
