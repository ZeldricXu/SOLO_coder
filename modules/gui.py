import os
import sys
import logging
from datetime import datetime
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QTextEdit, QLineEdit, QListWidget,
    QListWidgetItem, QSplitter, QMessageBox, QSystemTrayIcon, QMenu, QAction
)
from PyQt5.QtCore import (
    Qt, QTimer, pyqtSignal, QObject, QPoint, QSize
)
from PyQt5.QtGui import QFont, QColor, QPalette, QIcon, QCursor

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import WINDOW_WIDTH, WINDOW_HEIGHT, DEFAULT_WATCH_DIR

logger = logging.getLogger(__name__)


class TaskSignals(QObject):
    status_updated = pyqtSignal(str, str)
    summary_generated = pyqtSignal(str, str, str)
    error_occurred = pyqtSignal(str, str)


class FloatingWindow(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._dragging = False
        self._drag_position = None
        self._current_summary = None
        
        self._init_ui()
    
    def _init_ui(self):
        self.setWindowFlags(
            Qt.FramelessWindowHint |
            Qt.WindowStaysOnTopHint |
            Qt.Tool
        )
        self.setAttribute(Qt.WA_TranslucentBackground, False)
        self.setFixedSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        
        self.setStyleSheet("""
            QWidget {
                background-color: rgba(45, 45, 48, 230);
                border-radius: 10px;
                border: 1px solid rgba(100, 100, 100, 150);
            }
            QLabel {
                color: #FFFFFF;
                background-color: transparent;
                border: none;
            }
            QPushButton {
                background-color: rgba(80, 80, 85, 200);
                color: #FFFFFF;
                border: 1px solid rgba(120, 120, 125, 150);
                border-radius: 5px;
                padding: 5px 10px;
            }
            QPushButton:hover {
                background-color: rgba(100, 100, 105, 200);
            }
            QPushButton:pressed {
                background-color: rgba(60, 60, 65, 200);
            }
        """)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.setSpacing(8)
        
        title_label = QLabel("📄 DeskAI 摘要通知")
        title_label.setFont(QFont("Microsoft YaHei", 12, QFont.Bold))
        title_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_label)
        
        self.status_label = QLabel("等待新文件...")
        self.status_label.setFont(QFont("Microsoft YaHei", 10))
        self.status_label.setAlignment(Qt.AlignCenter)
        self.status_label.setWordWrap(True)
        layout.addWidget(self.status_label)
        
        self.summary_label = QLabel("")
        self.summary_label.setFont(QFont("Microsoft YaHei", 9))
        self.summary_label.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        self.summary_label.setWordWrap(True)
        self.summary_label.setStyleSheet("color: #E0E0E0; background-color: rgba(30, 30, 35, 150); padding: 8px; border-radius: 5px;")
        layout.addWidget(self.summary_label, 1)
        
        button_layout = QHBoxLayout()
        
        self.view_btn = QPushButton("查看详情")
        self.view_btn.clicked.connect(self._view_detail)
        self.view_btn.setEnabled(False)
        button_layout.addWidget(self.view_btn)
        
        self.hide_btn = QPushButton("隐藏")
        self.hide_btn.clicked.connect(self.hide)
        button_layout.addWidget(self.hide_btn)
        
        layout.addLayout(button_layout)
        
        screen = QApplication.primaryScreen().geometry()
        self.move(screen.width() - self.width() - 20, 20)
    
    def connect_signals(self, task_signals):
        task_signals.summary_generated.connect(self._on_summary_generated)
        task_signals.status_updated.connect(self._on_status_updated)
        task_signals.error_occurred.connect(self._on_error_occurred)
    
    def _on_summary_generated(self, file_path, summary, status):
        self._current_summary = {
            'file_path': file_path,
            'summary': summary,
            'status': status
        }
        
        file_name = os.path.basename(file_path)
        self.status_label.setText(f"✅ 已处理: {file_name}")
        
        if len(summary) > 100:
            display_summary = summary[:100] + "..."
        else:
            display_summary = summary
        
        self.summary_label.setText(display_summary)
        self.view_btn.setEnabled(True)
        self.show()
    
    def _on_status_updated(self, file_path, status):
        file_name = os.path.basename(file_path)
        if status == "PENDING":
            self.status_label.setText(f"⏳ 正在处理: {file_name}")
            self.summary_label.setText("")
            self.view_btn.setEnabled(False)
        elif status == "PROCESSING":
            self.status_label.setText(f"🔄 正在生成摘要: {file_name}")
    
    def _on_error_occurred(self, file_path, message):
        file_name = os.path.basename(file_path)
        self.status_label.setText(f"❌ 处理失败: {file_name}")
        self.summary_label.setText(message if len(message) < 50 else message[:50] + "...")
    
    def _view_detail(self):
        if self._current_summary:
            msg = QMessageBox(self)
            msg.setWindowTitle("摘要详情")
            msg.setIcon(QMessageBox.Information)
            
            file_name = os.path.basename(self._current_summary['file_path'])
            text = f"文件: {file_name}\n\n摘要:\n{self._current_summary['summary']}"
            msg.setText(text)
            
            msg.setStandardButtons(QMessageBox.Ok)
            msg.exec_()
    
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._dragging = True
            self._drag_position = event.globalPos() - self.frameGeometry().topLeft()
            event.accept()
    
    def mouseMoveEvent(self, event):
        if self._dragging and self._drag_position:
            self.move(event.globalPos() - self._drag_position)
            event.accept()
    
    def mouseReleaseEvent(self, event):
        self._dragging = False
        self._drag_position = None


class MainWindow(QMainWindow):
    def __init__(self, app, db_manager, parent=None):
        super().__init__(parent)
        self.app = app
        self.db_manager = db_manager
        self.floating_window = None
        
        self._init_ui()
        self._init_tray()
        self._start_refresh_timer()
    
    def _init_ui(self):
        self.setWindowTitle("DeskAI - 智能桌面办公辅助系统")
        self.setMinimumSize(800, 600)
        
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(15, 15, 15, 15)
        main_layout.setSpacing(10)
        
        header_label = QLabel("📊 DeskAI 控制面板")
        header_label.setFont(QFont("Microsoft YaHei", 18, QFont.Bold))
        main_layout.addWidget(header_label)
        
        status_layout = QHBoxLayout()
        
        self.watch_status_label = QLabel("监控状态: 未启动")
        self.watch_status_label.setStyleSheet("color: #FF6B6B; font-weight: bold;")
        status_layout.addWidget(self.watch_status_label)
        
        status_layout.addStretch()
        
        self.start_btn = QPushButton("▶ 启动监控")
        self.start_btn.clicked.connect(self._toggle_monitoring)
        self.start_btn.setMinimumWidth(100)
        status_layout.addWidget(self.start_btn)
        
        main_layout.addLayout(status_layout)
        
        splitter = QSplitter(Qt.Horizontal)
        
        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.setSpacing(5)
        
        list_header = QLabel("📁 文档列表")
        list_header.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        left_layout.addWidget(list_header)
        
        search_layout = QHBoxLayout()
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("搜索文档...")
        self.search_input.textChanged.connect(self._search_documents)
        search_layout.addWidget(self.search_input)
        
        self.refresh_btn = QPushButton("🔄")
        self.refresh_btn.setToolTip("刷新列表")
        self.refresh_btn.clicked.connect(self._refresh_document_list)
        self.refresh_btn.setMaximumWidth(40)
        search_layout.addWidget(self.refresh_btn)
        
        left_layout.addLayout(search_layout)
        
        self.document_list = QListWidget()
        self.document_list.itemClicked.connect(self._on_document_selected)
        self.document_list.setMinimumWidth(250)
        left_layout.addWidget(self.document_list)
        
        splitter.addWidget(left_widget)
        
        right_widget = QWidget()
        right_layout = QVBoxLayout(right_widget)
        right_layout.setContentsMargins(0, 0, 0, 0)
        right_layout.setSpacing(5)
        
        detail_header = QLabel("📝 文档详情")
        detail_header.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        right_layout.addWidget(detail_header)
        
        self.detail_text = QTextEdit()
        self.detail_text.setReadOnly(True)
        self.detail_text.setFont(QFont("Microsoft YaHei", 10))
        self.detail_text.setPlaceholderText("选择一个文档查看详情...")
        right_layout.addWidget(self.detail_text)
        
        splitter.addWidget(right_widget)
        
        splitter.setSizes([300, 500])
        
        main_layout.addWidget(splitter, 1)
        
        footer_label = QLabel("DeskAI v1.0 - 智能桌面办公辅助系统")
        footer_label.setStyleSheet("color: #888888; font-size: 11px;")
        footer_label.setAlignment(Qt.AlignCenter)
        main_layout.addWidget(footer_label)
        
        self._refresh_document_list()
    
    def _init_tray(self):
        self.tray_icon = QSystemTrayIcon(self)
        self.tray_icon.setIcon(self.style().standardIcon(self.style().SP_ComputerIcon))
        
        tray_menu = QMenu()
        
        show_action = QAction("显示主窗口", self)
        show_action.triggered.connect(self.show)
        tray_menu.addAction(show_action)
        
        show_floating_action = QAction("显示悬浮窗", self)
        show_floating_action.triggered.connect(self._show_floating_window)
        tray_menu.addAction(show_floating_action)
        
        tray_menu.addSeparator()
        
        quit_action = QAction("退出", self)
        quit_action.triggered.connect(self._quit_app)
        tray_menu.addAction(quit_action)
        
        self.tray_icon.setContextMenu(tray_menu)
        self.tray_icon.activated.connect(self._on_tray_activated)
        self.tray_icon.show()
    
    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.DoubleClick:
            self.show()
    
    def set_floating_window(self, floating_window):
        self.floating_window = floating_window
    
    def _toggle_monitoring(self):
        if hasattr(self, '_monitoring_started') and self._monitoring_started:
            self._monitoring_started = False
            self.start_btn.setText("▶ 启动监控")
            self.watch_status_label.setText("监控状态: 已停止")
            self.watch_status_label.setStyleSheet("color: #FF6B6B; font-weight: bold;")
        else:
            self._monitoring_started = True
            self.start_btn.setText("⏸ 暂停监控")
            self.watch_status_label.setText("监控状态: 运行中")
            self.watch_status_label.setStyleSheet("color: #4ECDC4; font-weight: bold;")
    
    def _show_floating_window(self):
        if self.floating_window:
            self.floating_window.show()
            self.floating_window.raise_()
            self.floating_window.activateWindow()
    
    def _refresh_document_list(self):
        self.document_list.clear()
        
        documents = self.db_manager.get_recent_documents(limit=50)
        
        for doc in documents:
            file_name = os.path.basename(doc['file_path'])
            status = doc['status']
            
            if status == "SUCCESS":
                status_icon = "✅"
            elif status == "PENDING":
                status_icon = "⏳"
            elif status == "PROCESSING":
                status_icon = "🔄"
            else:
                status_icon = "❌"
            
            item_text = f"{status_icon} {file_name}"
            item = QListWidgetItem(item_text)
            item.setData(Qt.UserRole, doc)
            
            if status == "SUCCESS":
                item.setForeground(QColor("#4ECDC4"))
            elif status == "FAILED":
                item.setForeground(QColor("#FF6B6B"))
            elif status == "PENDING" or status == "PROCESSING":
                item.setForeground(QColor("#FFE66D"))
            
            self.document_list.addItem(item)
    
    def _search_documents(self, keyword):
        if not keyword.strip():
            self._refresh_document_list()
            return
        
        self.document_list.clear()
        
        documents = self.db_manager.search_documents(keyword)
        
        for doc in documents:
            file_name = os.path.basename(doc['file_path'])
            status = doc['status']
            
            if status == "SUCCESS":
                status_icon = "✅"
            elif status == "PENDING":
                status_icon = "⏳"
            elif status == "PROCESSING":
                status_icon = "🔄"
            else:
                status_icon = "❌"
            
            item_text = f"{status_icon} {file_name}"
            item = QListWidgetItem(item_text)
            item.setData(Qt.UserRole, doc)
            self.document_list.addItem(item)
    
    def _on_document_selected(self, item):
        doc = item.data(Qt.UserRole)
        
        if not doc:
            return
        
        file_name = os.path.basename(doc['file_path'])
        status_text = {
            "SUCCESS": "✅ 处理成功",
            "PENDING": "⏳ 等待处理",
            "PROCESSING": "🔄 处理中",
            "FAILED": "❌ 处理失败"
        }.get(doc['status'], doc['status'])
        
        detail_text = f"""📁 文件信息
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📄 文件名: {file_name}
📍 路径: {doc['file_path']}
📂 类型: {doc['file_type']}
📊 状态: {status_text}
📅 创建时间: {doc['created_at']}
📅 更新时间: {doc['updated_at']}

📝 摘要内容
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{doc['summary_text'] if doc['summary_text'] else '(暂无摘要)'}

"""
        
        if doc.get('error_message'):
            detail_text += f"""⚠️ 错误信息
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{doc['error_message']}
"""
        
        self.detail_text.setText(detail_text)
    
    def _start_refresh_timer(self):
        self.refresh_timer = QTimer(self)
        self.refresh_timer.timeout.connect(self._refresh_document_list)
        self.refresh_timer.start(5000)
    
    def _quit_app(self):
        self.refresh_timer.stop()
        self.tray_icon.hide()
        self.app.quit()
    
    def closeEvent(self, event):
        event.ignore()
        self.hide()
        self.tray_icon.showMessage(
            "DeskAI",
            "应用已最小化到系统托盘",
            QSystemTrayIcon.Information,
            2000
        )
