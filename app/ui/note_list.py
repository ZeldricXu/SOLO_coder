from datetime import datetime
from typing import Optional, List

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QListWidget, QListWidgetItem,
    QLabel, QLineEdit, QPushButton, QMenu, QFrame, QToolBar
)
from PyQt6.QtCore import Qt, pyqtSignal, QSize
from PyQt6.QtGui import QIcon, QColor, QPainter, QPixmap, QAction

from app.database import Database


def create_color_pixmap(color_hex: str, size: int = 12) -> QPixmap:
    pm = QPixmap(size, size)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setBrush(QColor(color_hex))
    p.setPen(Qt.PenStyle.NoPen)
    p.drawEllipse(0, 0, size, size)
    p.end()
    return pm


class NoteListItemWidget(QFrame):
    def __init__(self, note: dict, tags: List[dict], parent=None):
        super().__init__(parent)
        self.note_id = note["id"]
        self.setFrameShape(QFrame.Shape.StyledPanel)
        self.setStyleSheet("""
            NoteListItemWidget {
                background: white;
                border: 1px solid #e0e0e0;
                border-radius: 6px;
                padding: 8px;
            }
            NoteListItemWidget:hover {
                background: #f5f7fa;
                border-color: #b0bec5;
            }
        """)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setSpacing(4)

        top_layout = QHBoxLayout()
        title_label = QLabel(note["title"] or "Untitled")
        title_label.setStyleSheet("font-weight: 600; font-size: 14px; color: #263238;")
        title_label.setWordWrap(True)
        top_layout.addWidget(title_label, 1)

        if note.get("is_pinned"):
            pin_label = QLabel("📌")
            top_layout.addWidget(pin_label)
        top_layout.setSpacing(4)
        layout.addLayout(top_layout)

        if tags:
            tag_layout = QHBoxLayout()
            tag_layout.setSpacing(4)
            for t in tags[:5]:
                tag_lbl = QLabel()
                tag_lbl.setPixmap(create_color_pixmap(t["color"], 10))
                tag_lbl.setToolTip(t["name"])
                tag_layout.addWidget(tag_lbl)
            if len(tags) > 5:
                more_lbl = QLabel(f"+{len(tags) - 5}")
                more_lbl.setStyleSheet("color: #78909c; font-size: 11px;")
                tag_layout.addWidget(more_lbl)
            tag_layout.addStretch(1)
            layout.addLayout(tag_layout)

        updated = datetime.fromtimestamp(note["updated_at"])
        date_str = updated.strftime("%Y-%m-%d %H:%M")

        preview = (note.get("content") or "")[:120]
        preview = preview.replace("\n", " ").strip()
        if len(preview) >= 120:
            preview += "..."

        bottom_layout = QHBoxLayout()
        if preview:
            prev_label = QLabel(preview)
            prev_label.setStyleSheet("color: #607d8b; font-size: 12px;")
            prev_label.setWordWrap(True)
            bottom_layout.addWidget(prev_label, 1)
        else:
            bottom_layout.addStretch(1)
        date_label = QLabel(date_str)
        date_label.setStyleSheet("color: #90a4ae; font-size: 11px;")
        bottom_layout.addWidget(date_label)
        layout.addLayout(bottom_layout)

        self.setMinimumHeight(60)


class NoteListWidget(QWidget):
    noteSelected = pyqtSignal(int)
    noteCreated = pyqtSignal(int)
    noteDeleted = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self.db = db
        self.current_folder_id: Optional[int] = None
        self.current_tag_ids: List[int] = []
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(4)

        toolbar = QToolBar()
        toolbar.setIconSize(QSize(18, 18))

        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("过滤笔记标题...")
        self.search_input.setClearButtonEnabled(True)
        self.search_input.textChanged.connect(self._on_filter_text)
        toolbar.addWidget(self.search_input)

        toolbar.addSeparator()

        new_action = QAction("✚ 新建笔记", self)
        new_action.triggered.connect(self._on_new_note)
        toolbar.addAction(new_action)

        layout.addWidget(toolbar)

        self.list_widget = QListWidget()
        self.list_widget.setSpacing(4)
        self.list_widget.itemClicked.connect(self._on_item_clicked)
        self.list_widget.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.list_widget.customContextMenuRequested.connect(self._on_context_menu)
        self.list_widget.setStyleSheet("""
            QListWidget {
                background: #fafafa;
                border: none;
                padding: 6px;
            }
            QListWidget::item {
                padding: 0;
                margin-bottom: 2px;
            }
            QListWidget::item:selected {
                background: transparent;
            }
        """)
        layout.addWidget(self.list_widget, 1)

        self.count_label = QLabel("0 篇笔记")
        self.count_label.setStyleSheet("color: #90a4ae; font-size: 11px; padding: 4px 10px;")
        layout.addWidget(self.count_label)

    def set_folder_filter(self, folder_id: Optional[int]):
        self.current_folder_id = folder_id
        self.current_tag_ids = []
        self.reload()

    def set_tag_filter(self, tag_ids: List[int]):
        self.current_tag_ids = tag_ids
        self.reload()

    def reload(self):
        self.list_widget.clear()
        notes = self.db.list_notes(folder_id=self.current_folder_id)
        if self.current_tag_ids:
            note_sets = []
            for tid in self.current_tag_ids:
                nlist = self.db.list_notes(tag_id=tid)
                note_sets.append({n["id"]: n for n in nlist})
            if note_sets:
                common = set(note_sets[0].keys())
                for s in note_sets[1:]:
                    common &= set(s.keys())
                notes = [note_sets[0][nid] for nid in common]
                notes.sort(key=lambda n: (-n.get("is_pinned", 0), -n["updated_at"]))
            else:
                notes = []

        filter_text = self.search_input.text().strip().lower()
        if filter_text:
            notes = [n for n in notes if filter_text in (n["title"] or "").lower()]

        for note in notes:
            tags = self.db.get_note_tags(note["id"])
            item_widget = NoteListItemWidget(note, tags)
            list_item = QListWidgetItem(self.list_widget)
            list_item.setData(Qt.ItemDataRole.UserRole, note["id"])
            list_item.setSizeHint(item_widget.sizeHint())
            self.list_widget.addItem(list_item)
            self.list_widget.setItemWidget(list_item, item_widget)

        self.count_label.setText(f"{len(notes)} 篇笔记")

    def _on_filter_text(self, _text):
        self.reload()

    def _on_item_clicked(self, item: QListWidgetItem):
        note_id = item.data(Qt.ItemDataRole.UserRole)
        if note_id:
            self.noteSelected.emit(int(note_id))

    def _on_new_note(self):
        note_id = self.db.create_note(folder_id=self.current_folder_id)
        self.noteCreated.emit(note_id)
        self.reload()
        self._select_note(note_id)

    def _select_note(self, note_id: int):
        for i in range(self.list_widget.count()):
            item = self.list_widget.item(i)
            if item.data(Qt.ItemDataRole.UserRole) == note_id:
                self.list_widget.setCurrentRow(i)
                self.noteSelected.emit(note_id)
                break

    def _on_context_menu(self, pos):
        item = self.list_widget.itemAt(pos)
        if not item:
            menu = QMenu(self)
            new_action = menu.addAction("新建笔记")
            new_action.triggered.connect(self._on_new_note)
            menu.exec(self.list_widget.mapToGlobal(pos))
            return

        note_id = item.data(Qt.ItemDataRole.UserRole)
        menu = QMenu(self)

        pin_action = menu.addAction("切换置顶")
        pin_action.triggered.connect(lambda: self._toggle_pin(note_id))

        delete_action = menu.addAction("删除笔记")
        delete_action.triggered.connect(lambda: self._delete_note(note_id))

        menu.exec(self.list_widget.mapToGlobal(pos))

    def _toggle_pin(self, note_id: int):
        note = self.db.get_note(note_id)
        if note:
            self.db.update_note(note_id, is_pinned=0 if note.get("is_pinned") else 1)
            self.reload()

    def _delete_note(self, note_id: int):
        from PyQt6.QtWidgets import QMessageBox
        reply = QMessageBox.question(
            self, "确认删除", "确定要删除这篇笔记吗？此操作不可恢复。",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No
        )
        if reply == QMessageBox.StandardButton.Yes:
            self.db.delete_note(note_id)
            self.noteDeleted.emit(note_id)
            self.reload()
