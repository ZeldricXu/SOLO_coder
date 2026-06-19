import os
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict, Any

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QListView, QLabel, QPushButton,
    QDialog, QTableWidget, QTableWidgetItem, QHeaderView, QAbstractItemView,
    QMessageBox, QSplitter, QFrame, QFileIconProvider, QSizePolicy, QMenu,
    QToolButton, QAbstractScrollArea
)
from PyQt6.QtCore import Qt, pyqtSignal, QSize, QMimeData, QUrl, QTimer
from PyQt6.QtGui import (
    QStandardItemModel, QStandardItem, QIcon, QPixmap, QPainter, QColor,
    QBrush, QFont, QDrag, QAction
)

from app.database import Database
from app.config import Config
from app.attachments.attachment_manager import (
    add_attachment, delete_unused_attachments, get_attachment_stats,
    format_file_size, get_attachment_note_count, list_all_attachments
)
from app.attachments.image_utils import is_image_file


ICON_SIZE = 48
THUMBNAIL_SIZE = 48


def _get_file_icon(file_path: str, thumbnail_path: str = "") -> QIcon:
    if thumbnail_path and os.path.exists(thumbnail_path):
        pixmap = QPixmap(thumbnail_path)
        if not pixmap.isNull():
            scaled = pixmap.scaled(
                THUMBNAIL_SIZE, THUMBNAIL_SIZE,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation
            )
            return QIcon(scaled)
    provider = QFileIconProvider()
    icon = provider.icon(file_path)
    if icon.isNull():
        icon = QIcon.fromTheme("text-x-generic")
    return icon


def _create_default_pixmap(ext: str, size: int = ICON_SIZE) -> QPixmap:
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    colors = {
        ".pdf": QColor("#E53935"),
        ".doc": QColor("#1565C0"),
        ".docx": QColor("#1565C0"),
        ".xls": QColor("#2E7D32"),
        ".xlsx": QColor("#2E7D32"),
        ".ppt": QColor("#E65100"),
        ".pptx": QColor("#E65100"),
        ".txt": QColor("#616161"),
        ".zip": QColor("#FF8F00"),
        ".rar": QColor("#FF8F00"),
        ".7z": QColor("#FF8F00"),
    }
    color = colors.get(ext.lower(), QColor("#78909C"))

    painter.setBrush(QBrush(color))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawRoundedRect(4, 4, size - 8, size - 8, 8, 8)

    painter.setPen(QColor("#FFFFFF"))
    font = QFont()
    font.setBold(True)
    font.setPointSize(max(8, size // 5))
    painter.setFont(font)
    ext_text = ext.lstrip(".").upper()[:4]
    painter.drawText(pixmap.rect(), Qt.AlignmentFlag.AlignCenter, ext_text)
    painter.end()
    return pixmap


class AttachmentListWidget(QWidget):
    attachments_changed = pyqtSignal()

    def __init__(self, db: Database, config: Config, parent=None):
        super().__init__(parent)
        self._db = db
        self._config = config
        self._note_id: Optional[int] = None
        self._attachments: List[Dict[str, Any]] = []

        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(4, 4, 4, 4)
        layout.setSpacing(4)

        header = QHBoxLayout()
        title = QLabel("附件")
        font = title.font()
        font.setBold(True)
        font.setPointSize(12)
        title.setFont(font)
        header.addWidget(title)
        header.addStretch()

        add_btn = QToolButton()
        add_btn.setText("+ 添加")
        add_btn.setToolTip("添加附件")
        add_btn.clicked.connect(self._on_add_attachment)
        header.addWidget(add_btn)

        layout.addLayout(header)

        self._list_view = QListView()
        self._list_view.setViewMode(QListView.ViewMode.IconMode)
        self._list_view.setResizeMode(QListView.ResizeMode.Adjust)
        self._list_view.setMovement(QListView.Movement.Static)
        self._list_view.setIconSize(QSize(ICON_SIZE, ICON_SIZE))
        self._list_view.setSpacing(8)
        self._list_view.setUniformItemSizes(False)
        self._list_view.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self._list_view.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self._list_view.setDragEnabled(True)
        self._list_view.setDragDropMode(QAbstractItemView.DragDropMode.DragOnly)
        self._list_view.customContextMenuRequested.connect(self._on_context_menu)

        self._model = QStandardItemModel()
        self._list_view.setModel(self._model)

        layout.addWidget(self._list_view, stretch=1)

    def set_note_id(self, note_id: Optional[int]):
        self._note_id = note_id
        self.reload()

    def reload(self):
        self._model.clear()
        if self._note_id is None:
            self._attachments = []
            return

        self._attachments = self._db.get_note_attachments(self._note_id)

        for att in self._attachments:
            item = QStandardItem()
            file_path = att.get("file_path", "")
            thumbnail_path = att.get("thumbnail_path", "")
            ext = Path(file_path).suffix.lower()

            if thumbnail_path and os.path.exists(thumbnail_path):
                icon = QIcon(thumbnail_path)
            elif is_image_file(file_path) and os.path.exists(file_path):
                pixmap = QPixmap(file_path)
                if not pixmap.isNull():
                    scaled = pixmap.scaled(
                        ICON_SIZE, ICON_SIZE,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation
                    )
                    icon = QIcon(scaled)
                else:
                    icon = QIcon(_create_default_pixmap(ext))
            else:
                icon = QIcon(_create_default_pixmap(ext))

            item.setIcon(icon)

            file_name = att.get("file_name", "")
            file_size = att.get("file_size", 0)
            size_str = format_file_size(file_size)
            display_text = f"{file_name}\n{size_str}"
            item.setText(display_text)

            item.setData(att["id"], Qt.ItemDataRole.UserRole)
            item.setData(file_path, Qt.ItemDataRole.UserRole + 1)
            item.setData(att, Qt.ItemDataRole.UserRole + 2)

            item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
            item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsEditable)

            self._model.appendRow(item)

    def _on_add_attachment(self):
        from PyQt6.QtWidgets import QFileDialog
        if self._note_id is None:
            return

        files, _ = QFileDialog.getOpenFileNames(
            self, "选择附件", "", "所有文件 (*.*)"
        )
        if not files:
            return

        for f in files:
            try:
                add_attachment(self._db, self._config, f, self._note_id)
            except Exception as e:
                QMessageBox.warning(self, "警告", f"添加附件失败: {os.path.basename(f)}\n{str(e)}")

        self.reload()
        self.attachments_changed.emit()

    def _on_context_menu(self, pos):
        index = self._list_view.indexAt(pos)
        if not index.isValid():
            return

        item = self._model.itemFromIndex(index)
        att_id = item.data(Qt.ItemDataRole.UserRole)
        att_data = item.data(Qt.ItemDataRole.UserRole + 2)
        if not att_data:
            return

        menu = QMenu(self)

        open_action = QAction("打开文件", self)
        open_action.triggered.connect(lambda: self._open_file(att_data))
        menu.addAction(open_action)

        reveal_action = QAction("在文件夹中显示", self)
        reveal_action.triggered.connect(lambda: self._reveal_in_folder(att_data))
        menu.addAction(reveal_action)

        menu.addSeparator()

        copy_path_action = QAction("复制文件路径", self)
        copy_path_action.triggered.connect(lambda: self._copy_path(att_data))
        menu.addAction(copy_path_action)

        menu.addSeparator()

        remove_action = QAction("从笔记移除", self)
        remove_action.triggered.connect(lambda: self._remove_from_note(att_id))
        menu.addAction(remove_action)

        menu.exec(self._list_view.mapToGlobal(pos))

    def _open_file(self, att: Dict[str, Any]):
        file_path = att.get("file_path", "")
        if not file_path or not os.path.exists(file_path):
            QMessageBox.warning(self, "警告", "文件不存在")
            return
        import subprocess
        try:
            subprocess.Popen(["open", file_path])
        except Exception:
            QMessageBox.warning(self, "警告", "无法打开文件")

    def _reveal_in_folder(self, att: Dict[str, Any]):
        file_path = att.get("file_path", "")
        if not file_path or not os.path.exists(file_path):
            QMessageBox.warning(self, "警告", "文件不存在")
            return
        import subprocess
        try:
            subprocess.Popen(["open", "-R", file_path])
        except Exception:
            QMessageBox.warning(self, "警告", "无法打开文件夹")

    def _copy_path(self, att: Dict[str, Any]):
        from PyQt6.QtWidgets import QApplication
        file_path = att.get("file_path", "")
        if file_path:
            QApplication.clipboard().setText(file_path)

    def _remove_from_note(self, attachment_id: int):
        if self._note_id is None:
            return
        reply = QMessageBox.question(
            self, "确认", "确定要从当前笔记移除此附件吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return

        with self._db.transaction() as cur:
            cur.execute(
                "DELETE FROM note_attachments WHERE note_id = ? AND attachment_id = ?",
                (self._note_id, attachment_id)
            )
        self.reload()
        self.attachments_changed.emit()

    def startDrag(self, supportedActions):
        indexes = self._list_view.selectedIndexes()
        if not indexes:
            return

        item = self._model.itemFromIndex(indexes[0])
        file_path = item.data(Qt.ItemDataRole.UserRole + 1)
        if not file_path or not os.path.exists(file_path):
            return

        drag = QDrag(self)
        mime_data = QMimeData()
        mime_data.setUrls([QUrl.fromLocalFile(file_path)])
        drag.setMimeData(mime_data)

        pixmap = item.icon().pixmap(QSize(ICON_SIZE, ICON_SIZE))
        drag.setPixmap(pixmap)
        drag.exec(Qt.DropAction.CopyAction)


class AttachmentManagerDialog(QDialog):
    def __init__(self, db: Database, config: Config, parent=None):
        super().__init__(parent)
        self._db = db
        self._config = config
        self._attachments: List[Dict[str, Any]] = []

        self.setWindowTitle("附件管理")
        self.resize(800, 600)

        self._setup_ui()
        self._refresh_stats()
        self._refresh_table()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(10)

        stats_frame = QFrame()
        stats_frame.setFrameShape(QFrame.Shape.StyledPanel)
        stats_layout = QHBoxLayout(stats_frame)
        stats_layout.setContentsMargins(12, 8, 12, 8)
        stats_layout.setSpacing(20)

        self._total_count_label = QLabel()
        self._total_size_label = QLabel()
        self._unused_count_label = QLabel()
        self._unused_size_label = QLabel()

        for lbl in [self._total_count_label, self._total_size_label,
                    self._unused_count_label, self._unused_size_label]:
            lbl.setStyleSheet("font-size: 13px;")

        stats_layout.addWidget(self._total_count_label)
        stats_layout.addWidget(self._total_size_label)
        stats_layout.addWidget(self._unused_count_label)
        stats_layout.addWidget(self._unused_size_label)
        stats_layout.addStretch()

        self._cleanup_btn = QPushButton("清理未引用附件")
        self._cleanup_btn.clicked.connect(self._on_cleanup_unused)
        stats_layout.addWidget(self._cleanup_btn)

        layout.addWidget(stats_frame)

        self._table = QTableWidget()
        self._table.setColumnCount(5)
        self._table.setHorizontalHeaderLabels(["", "文件名", "大小", "引用笔记数", "添加时间"])
        self._table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self._table.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self._table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self._table.setAlternatingRowColors(True)
        self._table.setSortingEnabled(True)
        self._table.verticalHeader().setVisible(False)
        self._table.setSizeAdjustPolicy(QAbstractScrollArea.SizeAdjustPolicy.AdjustToContents)

        header = self._table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Fixed)
        self._table.setColumnWidth(0, 40)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)

        layout.addWidget(self._table, stretch=1)

        btn_layout = QHBoxLayout()
        btn_layout.addStretch()

        delete_btn = QPushButton("删除选中")
        delete_btn.clicked.connect(self._on_delete_selected)
        btn_layout.addWidget(delete_btn)

        close_btn = QPushButton("关闭")
        close_btn.clicked.connect(self.accept)
        btn_layout.addWidget(close_btn)

        layout.addLayout(btn_layout)

    def _refresh_stats(self):
        stats = get_attachment_stats(self._db)
        self._total_count_label.setText(f"总数: {stats['total_count']}")
        self._total_size_label.setText(f"总大小: {format_file_size(stats['total_size'])}")
        self._unused_count_label.setText(f"未引用: {stats['unused_count']}")
        self._unused_size_label.setText(f"未引用大小: {format_file_size(stats['unused_size'])}")

        if stats['unused_count'] > 0:
            self._unused_count_label.setStyleSheet("font-size: 13px; color: #E53935;")
            self._unused_size_label.setStyleSheet("font-size: 13px; color: #E53935;")
        else:
            self._unused_count_label.setStyleSheet("font-size: 13px; color: #2E7D32;")
            self._unused_size_label.setStyleSheet("font-size: 13px; color: #2E7D32;")

    def _refresh_table(self):
        self._attachments = list_all_attachments(self._db)
        self._table.setRowCount(len(self._attachments))

        for row, att in enumerate(self._attachments):
            file_path = att.get("file_path", "")
            thumbnail_path = att.get("thumbnail_path", "")
            ext = Path(file_path).suffix.lower()

            icon_item = QTableWidgetItem()
            icon = _get_file_icon(file_path, thumbnail_path)
            icon_item.setIcon(icon)
            icon_item.setData(Qt.ItemDataRole.UserRole, att)
            self._table.setItem(row, 0, icon_item)

            name_item = QTableWidgetItem(att.get("file_name", ""))
            name_item.setData(Qt.ItemDataRole.UserRole, att)
            self._table.setItem(row, 1, name_item)

            size_item = QTableWidgetItem(format_file_size(att.get("file_size", 0)))
            size_item.setTextAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            self._table.setItem(row, 2, size_item)

            note_count = get_attachment_note_count(self._db, att["id"])
            count_item = QTableWidgetItem(str(note_count))
            count_item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            if note_count == 0:
                count_item.setForeground(QBrush(QColor("#E53935")))
            self._table.setItem(row, 3, count_item)

            created_at = att.get("created_at", 0)
            time_str = datetime.fromtimestamp(created_at).strftime("%Y-%m-%d %H:%M")
            time_item = QTableWidgetItem(time_str)
            self._table.setItem(row, 4, time_item)

        self._table.resizeRowsToContents()

    def _on_cleanup_unused(self):
        unused = delete_unused_attachments(self._db, dry_run=True)
        if not unused:
            QMessageBox.information(self, "信息", "没有未引用的附件")
            return

        total_size = sum(a.get("file_size", 0) for a in unused)
        reply = QMessageBox.question(
            self, "确认清理",
            f"将删除 {len(unused)} 个未引用附件，释放 {format_file_size(total_size)} 空间。\n确定继续吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return

        freed = delete_unused_attachments(self._db, dry_run=False)
        QMessageBox.information(self, "完成", f"已清理，释放 {format_file_size(freed)} 空间")
        self._refresh_stats()
        self._refresh_table()

    def _on_delete_selected(self):
        selected_rows = set()
        for index in self._table.selectedIndexes():
            selected_rows.add(index.row())

        if not selected_rows:
            return

        to_delete = []
        for row in selected_rows:
            item = self._table.item(row, 0)
            if item:
                att = item.data(Qt.ItemDataRole.UserRole)
                if att:
                    to_delete.append(att)

        if not to_delete:
            return

        reply = QMessageBox.question(
            self, "确认删除",
            f"确定删除选中的 {len(to_delete)} 个附件吗？\n文件将被永久删除，此操作无法撤销。",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return

        total_freed = 0
        for att in to_delete:
            file_path = att.get("file_path", "")
            thumbnail_path = att.get("thumbnail_path", "")

            try:
                if file_path and os.path.exists(file_path):
                    total_freed += os.path.getsize(file_path)
                    os.remove(file_path)
            except Exception:
                pass

            try:
                if thumbnail_path and os.path.exists(thumbnail_path):
                    total_freed += os.path.getsize(thumbnail_path)
                    os.remove(thumbnail_path)
            except Exception:
                pass

            self._db.delete_attachment(att["id"])

        QMessageBox.information(self, "完成", f"已删除，释放 {format_file_size(total_freed)} 空间")
        self._refresh_stats()
        self._refresh_table()
