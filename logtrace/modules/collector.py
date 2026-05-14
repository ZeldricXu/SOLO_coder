import threading
import time
import os
import concurrent.futures
from collections import deque
from pathlib import Path
from typing import Callable, List, Dict, Any, Optional
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler, FileModifiedEvent
import schedule

from logtrace.core.config import ConfigManager
from logtrace.core.models import NodeConfig, LogRecord
from logtrace.core.log_parser import LogParser


class LogFileHandler(FileSystemEventHandler):
    def __init__(self, node: NodeConfig, on_log_collected: Callable, parser: LogParser):
        self.node = node
        self.on_log_collected = on_log_collected
        self.parser = parser
        self.last_position = 0
        self.file_path = Path(node.log_path)
        if self.file_path.exists():
            self.last_position = self.file_path.stat().st_size

    def on_modified(self, event):
        if not isinstance(event, FileModifiedEvent):
            return
        if Path(event.src_path) != self.file_path:
            return
        self._read_new_lines()

    def _read_new_lines(self):
        try:
            if not self.file_path.exists():
                return
            current_size = self.file_path.stat().st_size
            if current_size == self.last_position:
                return
            if current_size < self.last_position:
                self.last_position = 0
            with open(self.file_path, 'r', encoding='utf-8', errors='ignore') as f:
                f.seek(self.last_position)
                for line in f:
                    line = line.strip()
                    if line:
                        self._process_line(line)
                self.last_position = f.tell()
        except Exception as e:
            print(f"Error reading log file {self.file_path}: {e}")

    def _process_line(self, raw_line: str):
        stripped_line = raw_line.strip()
        if not stripped_line:
            return
        try:
            log_level, log_content, timestamp, log_source = self.parser.parse(stripped_line)
            log_record = LogRecord.create(
                node_id=self.node.node_id,
                log_level=log_level,
                log_source=log_source,
                log_content=log_content,
                timestamp=timestamp
            )
            self.on_log_collected(log_record)
        except Exception as e:
            print(f"Error parsing log line: {e}")


class ParallelCollector:
    def __init__(
        self,
        parser: LogParser,
        on_log_collected: Callable,
        max_workers: int = 5
    ):
        self.parser = parser
        self.on_log_collected = on_log_collected
        self.max_workers = max_workers
        self._node_positions: Dict[str, int] = {}
        self._lock = threading.Lock()
        self.running = False

    def _get_node_position(self, node_id: str) -> int:
        with self._lock:
            return self._node_positions.get(node_id, 0)

    def _set_node_position(self, node_id: str, position: int):
        with self._lock:
            self._node_positions[node_id] = position

    def _collect_single_node(self, node: NodeConfig) -> int:
        file_path = Path(node.log_path)
        if not file_path.exists():
            return 0

        count = 0
        last_position = self._get_node_position(node.node_id)
        current_size = file_path.stat().st_size

        if current_size == last_position:
            return 0

        if current_size < last_position:
            last_position = 0

        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                f.seek(last_position)
                for line in f:
                    stripped_line = line.strip()
                    if stripped_line:
                        try:
                            log_level, log_content, timestamp, log_source = self.parser.parse(stripped_line)
                            log_record = LogRecord.create(
                                node_id=node.node_id,
                                log_level=log_level,
                                log_source=log_source,
                                log_content=log_content,
                                timestamp=timestamp
                            )
                            self.on_log_collected(log_record)
                            count += 1
                        except Exception as e:
                            print(f"Error parsing log line for node {node.node_id}: {e}")
                current_position = f.tell()
                self._set_node_position(node.node_id, current_position)
        except Exception as e:
            print(f"Error collecting from node {node.node_id}: {e}")

        return count

    def collect_nodes_parallel(self, nodes: List[NodeConfig]) -> Dict[str, int]:
        if not nodes:
            return {}

        results = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            future_to_node = {
                executor.submit(self._collect_single_node, node): node
                for node in nodes
            }
            for future in concurrent.futures.as_completed(future_to_node):
                node = future_to_node[future]
                try:
                    count = future.result()
                    results[node.node_id] = count
                except Exception as e:
                    print(f"Node {node.node_id} collection failed: {e}")
                    results[node.node_id] = 0

        return results


class LogCollector:
    def __init__(self, config: ConfigManager, on_log_collected: Callable, max_workers: int = 5):
        self.config = config
        self.on_log_collected = on_log_collected
        self.parser = LogParser()
        self.nodes: List[NodeConfig] = []
        self.realtime_handlers: dict = {}
        self.scheduled_collectors: list = []
        self.running = False
        self.scheduler_thread = None
        self.observer = Observer()
        self.max_workers = max_workers
        self.parallel_collector = ParallelCollector(
            parser=self.parser,
            on_log_collected=self.on_log_collected,
            max_workers=max_workers
        )
        self._scheduler_lock = threading.Lock()
        self._pending_collections: deque = deque()

    def start(self):
        self.running = True
        self._load_nodes()
        self._start_realtime_collectors()
        self._start_scheduled_collectors()

    def stop(self):
        self.running = False
        self.observer.stop()
        self.observer.join()

    def _load_nodes(self):
        for node_data in self.config.get_nodes():
            node = NodeConfig.from_dict(node_data)
            if node.enabled:
                self.nodes.append(node)

    def _start_realtime_collectors(self):
        for node in self.nodes:
            if node.collect_mode == 'realtime':
                handler = LogFileHandler(node, self.on_log_collected, self.parser)
                log_dir = Path(node.log_path).parent
                if log_dir.exists():
                    self.observer.schedule(handler, str(log_dir), recursive=False)
                    self.realtime_handlers[node.node_id] = handler
        if self.realtime_handlers:
            self.observer.start()

    def _start_scheduled_collectors(self):
        scheduled_nodes = [node for node in self.nodes if node.collect_mode == 'scheduled']
        if scheduled_nodes:
            for node in scheduled_nodes:
                self._register_scheduled_collector(node)
            self.scheduler_thread = threading.Thread(target=self._run_scheduler, daemon=True)
            self.scheduler_thread.start()

    def _register_scheduled_collector(self, node: NodeConfig):
        self.scheduled_collectors.append(node)
        interval = max(1, node.collect_interval)
        schedule.every(interval).seconds.do(self._queue_parallel_collection, [node])

    def _run_scheduler(self):
        while self.running:
            schedule.run_pending()
            self._process_pending_collections()
            time.sleep(0.5)

    def _queue_parallel_collection(self, nodes: List[NodeConfig]):
        with self._scheduler_lock:
            self._pending_collections.append(nodes)

    def _process_pending_collections(self):
        while self._pending_collections:
            with self._scheduler_lock:
                if not self._pending_collections:
                    break
                nodes = self._pending_collections.popleft()
            self.collect_all_scheduled_nodes()

    def collect_all_scheduled_nodes(self):
        scheduled_nodes = [node for node in self.nodes if node.collect_mode == 'scheduled']
        if not scheduled_nodes:
            return {}
        return self.parallel_collector.collect_nodes_parallel(scheduled_nodes)

    def _collect_from_node(self, node: NodeConfig):
        file_path = Path(node.log_path)
        if not file_path.exists():
            return
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        self._parse_and_collect(node, line)
        except Exception as e:
            print(f"Error collecting from node {node.node_id}: {e}")

    def _parse_and_collect(self, node: NodeConfig, raw_line: str):
        try:
            log_level, log_content, timestamp, log_source = self.parser.parse(raw_line)
            log_record = LogRecord.create(
                node_id=node.node_id,
                log_level=log_level,
                log_source=log_source,
                log_content=log_content,
                timestamp=timestamp
            )
            self.on_log_collected(log_record)
        except Exception as e:
            print(f"Error parsing log line for node {node.node_id}: {e}")

    def get_collection_stats(self) -> Dict[str, Any]:
        return {
            'total_nodes': len(self.nodes),
            'realtime_nodes': len(self.realtime_handlers),
            'scheduled_nodes': len(self.scheduled_collectors),
            'max_workers': self.max_workers,
            'running': self.running
        }
