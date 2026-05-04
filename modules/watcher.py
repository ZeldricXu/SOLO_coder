import os
import threading
import time
import logging
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler, FileCreatedEvent, FileModifiedEvent
from PyQt5.QtCore import QObject, pyqtSignal, QThread

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import SUPPORTED_EXTENSIONS, DEFAULT_WATCH_DIR

logger = logging.getLogger(__name__)


class FileChangeHandler(FileSystemEventHandler):
    def __init__(self, file_watcher, supported_extensions=None):
        super().__init__()
        self.file_watcher = file_watcher
        self.supported_extensions = supported_extensions or SUPPORTED_EXTENSIONS
        self._cooldown_period = 2.0
        self._file_timestamps = {}
        self._lock = threading.Lock()
    
    def _is_supported_file(self, file_path):
        if not os.path.isfile(file_path):
            return False
        
        ext = os.path.splitext(file_path)[1].lower()
        return ext in self.supported_extensions
    
    def _should_process(self, file_path):
        with self._lock:
            current_time = time.time()
            
            if file_path in self._file_timestamps:
                last_processed = self._file_timestamps[file_path]
                if current_time - last_processed < self._cooldown_period:
                    return False
            
            self._file_timestamps[file_path] = current_time
            return True
    
    def _try_emit_signal(self, file_path):
        if not self._is_supported_file(file_path):
            return
        
        if not self._should_process(file_path):
            logger.debug(f"Skipping file due to cooldown: {file_path}")
            return
        
        max_wait = 10
        wait_interval = 0.5
        waited = 0
        
        while waited < max_wait:
            try:
                with open(file_path, 'rb'):
                    break
            except PermissionError:
                logger.debug(f"File is still being written: {file_path}, waiting...")
                time.sleep(wait_interval)
                waited += wait_interval
            except Exception as e:
                logger.error(f"Error checking file availability: {str(e)}")
                return
        
        if waited >= max_wait:
            logger.warning(f"File still unavailable after {max_wait}s: {file_path}")
            return
        
        file_type = os.path.splitext(file_path)[1].lower().lstrip('.')
        
        self.file_watcher.file_detected.emit(file_path, file_type)
        logger.info(f"File detected and signal emitted: {file_path}")
    
    def on_created(self, event):
        if not isinstance(event, FileCreatedEvent):
            return
        
        if event.is_directory:
            return
        
        logger.debug(f"File created: {event.src_path}")
        self._try_emit_signal(event.src_path)
    
    def on_modified(self, event):
        if not isinstance(event, FileModifiedEvent):
            return
        
        if event.is_directory:
            return
        
        logger.debug(f"File modified: {event.src_path}")
        self._try_emit_signal(event.src_path)


class FileWatcher(QObject):
    file_detected = pyqtSignal(str, str)
    
    def __init__(self, watch_dir=None, supported_extensions=None, parent=None):
        super().__init__(parent)
        self.watch_dir = watch_dir or DEFAULT_WATCH_DIR
        self.supported_extensions = supported_extensions or SUPPORTED_EXTENSIONS
        self.observer = None
        self._running = False
        self._lock = threading.Lock()
    
    def start(self):
        with self._lock:
            if self._running:
                logger.warning("FileWatcher is already running")
                return
            
            if not os.path.exists(self.watch_dir):
                os.makedirs(self.watch_dir, exist_ok=True)
                logger.info(f"Created watch directory: {self.watch_dir}")
            
            event_handler = FileChangeHandler(
                self, 
                self.supported_extensions
            )
            
            self.observer = Observer()
            self.observer.schedule(
                event_handler, 
                self.watch_dir, 
                recursive=False
            )
            
            self.observer.start()
            self._running = True
            logger.info(f"FileWatcher started, watching: {self.watch_dir}")
    
    def stop(self):
        with self._lock:
            if not self._running:
                logger.warning("FileWatcher is not running")
                return
            
            if self.observer:
                self.observer.stop()
                self.observer.join()
            
            self._running = False
            logger.info("FileWatcher stopped")
    
    def is_running(self):
        with self._lock:
            return self._running
