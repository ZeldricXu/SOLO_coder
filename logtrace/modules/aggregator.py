import threading
import queue
from typing import List, Callable
from collections import deque

from logtrace.core.models import LogRecord


class LogAggregator:
    def __init__(self, batch_size: int = 100, flush_interval: float = 1.0):
        self.batch_size = batch_size
        self.flush_interval = flush_interval
        self.log_queue = queue.Queue()
        self.batch_buffer: deque = deque()
        self.on_batch_ready: Callable[[List[LogRecord]], None] = None
        self.running = False
        self.flush_thread = None
        self._lock = threading.Lock()

    def start(self):
        self.running = True
        self.flush_thread = threading.Thread(target=self._flush_loop, daemon=True)
        self.flush_thread.start()

    def stop(self):
        self.running = False
        if self.flush_thread:
            self.flush_thread.join(timeout=2.0)
        self._flush()

    def aggregate(self, log: LogRecord):
        self.log_queue.put(log)

    def set_batch_handler(self, handler: Callable[[List[LogRecord]], None]):
        self.on_batch_ready = handler

    def _flush_loop(self):
        last_flush_time = threading.Event().wait
        import time
        while self.running:
            try:
                while not self.log_queue.empty():
                    try:
                        log = self.log_queue.get_nowait()
                        with self._lock:
                            self.batch_buffer.append(log)
                        if len(self.batch_buffer) >= self.batch_size:
                            self._flush()
                    except queue.Empty:
                        break
                self._flush()
                time.sleep(self.flush_interval)
            except Exception as e:
                print(f"Error in aggregator flush loop: {e}")

    def _flush(self):
        with self._lock:
            if not self.batch_buffer:
                return
            batch = list(self.batch_buffer)
            self.batch_buffer.clear()
        if self.on_batch_ready and batch:
            try:
                self.on_batch_ready(batch)
            except Exception as e:
                print(f"Error calling batch handler: {e}")
                for log in batch:
                    self.batch_buffer.appendleft(log)
