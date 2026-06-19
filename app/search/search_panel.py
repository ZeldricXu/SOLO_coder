import re
import time
from datetime import datetime
from typing import Optional, List, Dict, Any

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLineEdit, QComboBox, QDateEdit,
    QListWidget, QListWidgetItem, QLabel, QPushButton, QToolButton,
    QMenu, QCheckBox, QWidgetAction, QFrame, QSizePolicy
)
from PyQt6.QtCore import Qt, pyqtSignal, QDate, QSize
from PyQt6.QtGui import QKeySequence, QShortcut, QIcon, QPainter, QColor, QPixmap, QFont

from app.database import Database
from app.search.search_engine import SearchEngine, preprocess_query


def highlight_keywords(text: str, query: str) -> str:
    if not text or not query or not query.strip():
        return text
    tokens = []
    segments = re.split(r'(\s+)', query)
    for seg in segments:
        if not seg.strip():
            continue
        if re.search(r'[\u4e00-\u9fff]', seg):
            import jieba
            words = jieba.lcut(seg)
            tokens.extend([w.strip() for w in words if w.strip()])
        else:
            tokens.append(seg.strip())
    tokens = list(dict.fromkeys(tokens))
    if not tokens:
        return text
    result = text
    for token in sorted(tokens, key=len, reverse=True):
        result = re.sub(
            r'(' + re.escape(token) + r')',
            r'<mark>\1</mark>',
            result,
            flags=re.IGNORECASE
        )
    return result


def _create_color_pixmap(color_hex: str, size: int = 12) -> QPixmap:
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setBrush(QColor(color_hex))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawEllipse(0, 0, size, size)
    painter.end()
    return pixmap


class TagMultiComboBox(QComboBox):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setEditable(False)
        self._selected_ids: List[int] = []
        self._tags: List[Dict] = []
        self.view().pressed.connect(self._on_item_pressed)
        self._update_text()

    def set_tags(self, tags: List[Dict]):
        self._tags = tags
        self.clear()
        for tag in tags:
            item = QListWidgetItem()
            item.setText(tag["name"])
            item.setData(Qt.ItemDataRole.UserRole, tag["id"])
            item.setIcon(QIcon(_create_color_pixmap(tag.get("color", "#4A90D9"))))
            item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(Qt.CheckState.Unchecked)
            self.addItem(item.text())
            self.setItemData(self.count() - 1, tag["id"], Qt.ItemDataRole.UserRole)
            self.setItemData(self.count() - 1, tag.get("color", "#4A90D9"), Qt.ItemDataRole.UserRole + 1)
            self.setItemIcon(self.count() - 1, QIcon(_create_color_pixmap(tag.get("color", "#4A90D9"))))

    def _on_item_pressed(self, index):
        tag_id = self.itemData(index.row(), Qt.ItemDataRole.UserRole)
        if tag_id in self._selected_ids:
            self._selected_ids.remove(tag_id)
            self.setItemData(index.row(), Qt.CheckState.Unchecked, Qt.ItemDataRole.CheckStateRole)
        else:
            self._selected_ids.append(tag_id)
            self.setItemData(index.row(), Qt.CheckState.Checked, Qt.ItemDataRole.CheckStateRole)
        self._update_text()

    def get_selected_tag_ids(self) -> List[int]:
        return list(self._selected_ids)

    def _update_text(self):
        if not self._selected_ids:
            self.setCurrentText("全部标签")
            return
        selected_names = []
        for tag in self._tags:
            if tag["id"] in self._selected_ids:
                selected_names.append(tag["name"])
        text = ", ".join(selected_names) if selected_names else "全部标签"
        if len(text) > 20:
            text = text[:17] + "..."
        self.setCurrentText(text)


class SearchPanel(QWidget):
    noteSelected = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self.db = db
        self.search_engine = SearchEngine(db)
        self._current_results: List[Dict] = []
        self._setup_ui()
        self._setup_shortcuts()
        self._load_filters()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(6)

        search_row = QHBoxLayout()
        search_row.setSpacing(4)

        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("搜索笔记... (Ctrl+F)")
        self.search_input.textChanged.connect(self._on_search_changed)
        self.search_input.returnPressed.connect(self._do_search)
        search_row.addWidget(self.search_input, 1)

        self.search_btn = QPushButton("搜索")
        self.search_btn.clicked.connect(self._do_search)
        search_row.addWidget(self.search_btn)

        layout.addLayout(search_row)

        filter_row = QHBoxLayout()
        filter_row.setSpacing(4)

        self.tag_combo = TagMultiComboBox()
        self.tag_combo.setMinimumWidth(140)
        filter_row.addWidget(self.tag_combo)

        self.folder_combo = QComboBox()
        self.folder_combo.setMinimumWidth(120)
        filter_row.addWidget(self.folder_combo)

        filter_row.addWidget(QLabel("从:"))
        self.date_from = QDateEdit()
        self.date_from.setCalendarPopup(True)
        self.date_from.setDisplayFormat("yyyy-MM-dd")
        self.date_from.setDate(QDate(2000, 1, 1))
        self.date_from.setSpecialValueText("不限")
        filter_row.addWidget(self.date_from)

        filter_row.addWidget(QLabel("到:"))
        self.date_to = QDateEdit()
        self.date_to.setCalendarPopup(True)
        self.date_to.setDisplayFormat("yyyy-MM-dd")
        self.date_to.setDate(QDate.currentDate())
        self.date_to.setSpecialValueText("不限")
        filter_row.addWidget(self.date_to)

        self.clear_btn = QToolButton()
        self.clear_btn.setText("清除")
        self.clear_btn.clicked.connect(self._clear_filters)
        filter_row.addWidget(self.clear_btn)

        layout.addLayout(filter_row)

        self.result_list = QListWidget()
        self.result_list.setSelectionMode(QListWidget.SelectionMode.SingleSelection)
        self.result_list.itemClicked.connect(self._on_result_clicked)
        self.result_list.itemDoubleClicked.connect(self._on_result_clicked)
        layout.addWidget(self.result_list, 1)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet("color: gray; font-size: 11px;")
        layout.addWidget(self.status_label)

    def _setup_shortcuts(self):
        shortcut = QShortcut(QKeySequence("Ctrl+F"), self)
        shortcut.activated.connect(self.focus_search)

    def _load_filters(self):
        folders = self.db.get_folder_tree()
        self.folder_combo.clear()
        self.folder_combo.addItem("全部目录", None)

        def _add_folders(folder_list, level=0):
            for f in folder_list:
                prefix = "  " * level
                self.folder_combo.addItem(f"{prefix}{f['name']}", f["id"])
                if f.get("children"):
                    _add_folders(f["children"], level + 1)

        _add_folders(folders)

        tags = self.db.list_tags()
        self.tag_combo.set_tags(tags)

    def focus_search(self):
        self.search_input.setFocus()
        self.search_input.selectAll()

    def _clear_filters(self):
        self.search_input.clear()
        self.tag_combo.set_tags(self.db.list_tags())
        self.folder_combo.setCurrentIndex(0)
        self.date_from.setDate(QDate(2000, 1, 1))
        self.date_to.setDate(QDate.currentDate())
        self.result_list.clear()
        self._current_results = []
        self.status_label.setText("")

    def _on_search_changed(self, text: str):
        if not text.strip():
            self.result_list.clear()
            self._current_results = []
            self.status_label.setText("")
            return
        self._do_search()

    def _do_search(self):
        query = self.search_input.text().strip()
        if not query:
            self.result_list.clear()
            self._current_results = []
            self.status_label.setText("")
            return

        tag_ids = self.tag_combo.get_selected_tag_ids() or None
        folder_id = self.folder_combo.currentData()
        date_from = None
        date_to = None

        if self.date_from.date() != QDate(2000, 1, 1):
            dt = datetime(self.date_from.date().year(), self.date_from.date().month(), self.date_from.date().day())
            date_from = int(dt.timestamp())

        if self.date_to.date() != QDate.currentDate():
            dt = datetime(self.date_to.date().year(), self.date_to.date().month(), self.date_to.date().day(), 23, 59, 59)
            date_to = int(dt.timestamp())

        results = self.search_engine.search(
            query=query,
            tag_ids=tag_ids,
            folder_id=folder_id,
            date_from=date_from,
            date_to=date_to
        )

        self._current_results = results
        self._populate_results(results, query)
        self.status_label.setText(f"找到 {len(results)} 条结果")

    def _populate_results(self, results: List[Dict], query: str):
        self.result_list.clear()
        for r in results:
            item_widget = self._create_result_item(r, query)
            list_item = QListWidgetItem()
            list_item.setData(Qt.ItemDataRole.UserRole, r["id"])
            list_item.setSizeHint(item_widget.sizeHint())
            self.result_list.addItem(list_item)
            self.result_list.setItemWidget(list_item, item_widget)

    def _create_result_item(self, note: Dict, query: str) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setContentsMargins(8, 6, 8, 6)
        layout.setSpacing(3)

        title_row = QHBoxLayout()
        title_row.setSpacing(6)

        title_label = QLabel()
        title_label.setTextFormat(Qt.TextFormat.RichText)
        title_text = highlight_keywords(note.get("title", "Untitled"), query)
        title_label.setText(f"<b>{title_text}</b>")
        title_font = title_label.font()
        title_font.setPointSize(title_font.pointSize() + 1)
        title_label.setFont(title_font)
        title_row.addWidget(title_label, 1)

        tags = self.db.get_note_tags(note["id"])
        for tag in tags[:5]:
            tag_label = QLabel()
            tag_label.setPixmap(_create_color_pixmap(tag.get("color", "#4A90D9"), 10))
            tag_label.setToolTip(tag["name"])
            title_row.addWidget(tag_label)

        if len(tags) > 5:
            more_label = QLabel(f"+{len(tags) - 5}")
            more_label.setStyleSheet("color: gray; font-size: 10px;")
            title_row.addWidget(more_label)

        layout.addLayout(title_row)

        content_text = note.get("content", "") or note.get("markdown_content", "")
        snippet = self.search_engine.highlight_text(content_text, query, max_length=180)
        snippet_label = QLabel()
        snippet_label.setTextFormat(Qt.TextFormat.RichText)
        snippet_label.setWordWrap(True)
        snippet_label.setStyleSheet("color: #555; font-size: 12px;")
        snippet_label.setText(snippet)
        layout.addWidget(snippet_label)

        meta_row = QHBoxLayout()
        meta_row.setSpacing(10)

        updated = datetime.fromtimestamp(note.get("updated_at", 0))
        time_str = updated.strftime("%Y-%m-%d %H:%M")
        time_label = QLabel(time_str)
        time_label.setStyleSheet("color: gray; font-size: 11px;")
        meta_row.addWidget(time_label)

        if note.get("score") is not None:
            score_label = QLabel(f"相关度: {note['score']:.2f}")
            score_label.setStyleSheet("color: gray; font-size: 11px;")
            meta_row.addWidget(score_label)

        meta_row.addStretch()
        layout.addLayout(meta_row)

        line = QFrame()
        line.setFrameShape(QFrame.Shape.HLine)
        line.setFrameShadow(QFrame.Shadow.Sunken)
        line.setStyleSheet("color: #e0e0e0;")
        layout.addWidget(line)

        widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        return widget

    def _on_result_clicked(self, item: QListWidgetItem):
        note_id = item.data(Qt.ItemDataRole.UserRole)
        if note_id is not None:
            self.noteSelected.emit(note_id)

    def reload_filters(self):
        self._load_filters()
