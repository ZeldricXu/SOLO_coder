import os
import sys
import logging
import time
from datetime import datetime
from queue import Queue

from PyQt5.QtWidgets import QApplication
from PyQt5.QtCore import QThread, pyqtSignal, QObject, Qt

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from config import LOG_FILE, DEFAULT_WATCH_DIR
from modules import (
    DatabaseManager, 
    FileWatcher, 
    LLMService, 
    LLMServiceError,
    MainWindow, 
    FloatingWindow, 
    TaskSignals
)
from utils import TextExtractor


def setup_logging():
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.FileHandler(LOG_FILE, encoding='utf-8'),
            logging.StreamHandler()
        ]
    )


class TaskWorker(QObject):
    task_received = pyqtSignal(str, str)
    
    def __init__(self, db_manager, llm_service, task_signals):
        super().__init__()
        self.db_manager = db_manager
        self.llm_service = llm_service
        self.task_signals = task_signals
        self._task_queue = Queue()
        self._running = True
        self.logger = logging.getLogger(__name__)
    
    def add_task(self, file_path, file_type):
        self._task_queue.put((file_path, file_type))
        self.task_received.emit(file_path, file_type)
    
    def process_queue(self):
        while self._running:
            try:
                if self._task_queue.empty():
                    time.sleep(0.1)
                    continue
                
                file_path, file_type = self._task_queue.get(block=False)
                self._process_single_task(file_path, file_type)
                
            except Exception as e:
                self.logger.error(f"Error in process_queue: {str(e)}")
                time.sleep(0.1)
    
    def _process_single_task(self, file_path, file_type):
        self.logger.info(f"Processing file: {file_path}")
        
        self.task_signals.status_updated.emit(file_path, "PENDING")
        
        file_id = self.db_manager.create_document(file_path, file_type)
        
        if not file_id:
            self.logger.error(f"Failed to create document record for: {file_path}")
            return
        
        self.task_signals.status_updated.emit(file_path, "PROCESSING")
        
        try:
            content = TextExtractor.extract_text(file_path)
            
            if not content or not content.strip():
                error_msg = "Empty or unreadable content"
                self.logger.warning(f"{error_msg}: {file_path}")
                self.db_manager.update_document_status(
                    file_path, 
                    "FAILED", 
                    error_message=error_msg
                )
                self.task_signals.error_occurred.emit(file_path, error_msg)
                return
            
            self.logger.info(f"Extracted {len(content)} characters from file")
            
            summary = self.llm_service.generate_summary(content)
            
            self.logger.info(f"Generated summary: {summary[:100]}..." if len(summary) > 100 else f"Generated summary: {summary}")
            
            self.db_manager.update_document_status(
                file_path, 
                "SUCCESS", 
                summary_text=summary
            )
            
            self.task_signals.summary_generated.emit(file_path, summary, "SUCCESS")
            
            self.logger.info(f"Successfully processed file: {file_path}")
            
        except LLMServiceError as e:
            error_msg = f"LLM service error: {str(e)}"
            self.logger.error(f"{error_msg}: {file_path}")
            self.db_manager.update_document_status(
                file_path, 
                "FAILED", 
                error_message=error_msg
            )
            self.task_signals.error_occurred.emit(file_path, error_msg)
            
        except Exception as e:
            error_msg = f"Unexpected error: {str(e)}"
            self.logger.error(f"{error_msg}: {file_path}")
            self.db_manager.update_document_status(
                file_path, 
                "FAILED", 
                error_message=error_msg
            )
            self.task_signals.error_occurred.emit(file_path, error_msg)
    
    def stop(self):
        self._running = False


class TaskProcessorThread(QThread):
    def __init__(self, task_worker):
        super().__init__()
        self.task_worker = task_worker
    
    def run(self):
        self.task_worker.process_queue()


class DeskAIApp:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._init_components()
    
    def _init_components(self):
        self.logger.info("Initializing DeskAI components...")
        
        self.task_signals = TaskSignals()
        
        self.db_manager = DatabaseManager()
        self.logger.info("Database manager initialized")
        
        self.llm_service = LLMService()
        self.logger.info("LLM service initialized")
        
        self.task_worker = TaskWorker(
            self.db_manager,
            self.llm_service,
            self.task_signals
        )
        
        self.task_processor = TaskProcessorThread(self.task_worker)
        self.logger.info("Task processor initialized")
        
        self.file_watcher = FileWatcher(DEFAULT_WATCH_DIR)
        self.logger.info(f"File watcher initialized for: {DEFAULT_WATCH_DIR}")
        
        self.file_watcher.file_detected.connect(self._on_file_detected, Qt.QueuedConnection)
        self.logger.info("Signals connected: FileWatcher -> TaskWorker")
    
    def _on_file_detected(self, file_path, file_type):
        self.logger.info(f"File detected signal received: {file_path}")
        self.task_worker.add_task(file_path, file_type)
    
    def _start_background_services(self):
        self.logger.info("Starting background services...")
        
        self.task_processor.start()
        self.logger.info("Task processor thread started")
        
        self.file_watcher.start()
        self.logger.info("File watcher started")
    
    def _stop_background_services(self):
        self.logger.info("Stopping background services...")
        
        self.file_watcher.stop()
        self.logger.info("File watcher stopped")
        
        self.task_worker.stop()
        self.task_processor.wait(5000)
        self.logger.info("Task processor stopped")
        
        self.db_manager.close()
        self.logger.info("Database connection closed")
    
    def run(self):
        self.logger.info("Starting DeskAI application...")
        
        app = QApplication(sys.argv)
        app.setQuitOnLastWindowClosed(False)
        
        self.floating_window = FloatingWindow()
        self.floating_window.connect_signals(self.task_signals)
        self.floating_window.show()
        self.logger.info("Floating window created and shown")
        
        self.main_window = MainWindow(app, self.db_manager)
        self.main_window.set_floating_window(self.floating_window)
        self.main_window._monitoring_started = True
        self.main_window.start_btn.setText("⏸ 暂停监控")
        self.main_window.watch_status_label.setText("监控状态: 运行中")
        self.main_window.watch_status_label.setStyleSheet("color: #4ECDC4; font-weight: bold;")
        self.logger.info("Main window created")
        
        self._start_background_services()
        
        self.logger.info("DeskAI application running")
        
        exit_code = app.exec_()
        
        self._stop_background_services()
        
        sys.exit(exit_code)
    
    def cleanup(self):
        self._stop_background_services()
        self.logger.info("DeskAI application cleaned up")


def main():
    setup_logging()
    
    logger = logging.getLogger(__name__)
    logger.info("=" * 50)
    logger.info("DeskAI - 智能桌面办公辅助系统")
    logger.info(f"启动时间: {datetime.now().isoformat()}")
    logger.info("=" * 50)
    
    app = DeskAIApp()
    
    try:
        app.run()
    except KeyboardInterrupt:
        logger.info("Received keyboard interrupt")
    except Exception as e:
        logger.error(f"Unexpected error: {str(e)}", exc_info=True)
    finally:
        app.cleanup()
        logger.info("DeskAI application exited")


if __name__ == "__main__":
    main()
