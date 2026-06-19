import re
import math
from collections import Counter, defaultdict
from typing import Optional, List, Dict, Tuple, Set
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QLabel, QPushButton,
    QDialog, QLineEdit, QColorDialog, QMessageBox, QGridLayout, QFrame,
    QSizePolicy, QInputDialog
)
from PyQt6.QtCore import Qt, pyqtSignal, QSize
from PyQt6.QtGui import QColor, QFont, QCursor
from app.database import Database

try:
    import jieba
    HAS_JIEBA = True
except ImportError:
    HAS_JIEBA = False


class NewTagDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("New Tag")
        self.setModal(True)
        self._color = "#4A90D9"

        layout = QVBoxLayout(self)

        name_layout = QHBoxLayout()
        name_layout.addWidget(QLabel("Name:"))
        self._name_edit = QLineEdit()
        self._name_edit.setPlaceholderText("Enter tag name")
        name_layout.addWidget(self._name_edit)
        layout.addLayout(name_layout)

        color_layout = QHBoxLayout()
        color_layout.addWidget(QLabel("Color:"))
        self._color_btn = QPushButton()
        self._color_btn.setFixedSize(60, 30)
        self._update_color_button()
        self._color_btn.clicked.connect(self._choose_color)
        color_layout.addWidget(self._color_btn)
        color_layout.addStretch()
        layout.addLayout(color_layout)

        btn_layout = QHBoxLayout()
        btn_layout.addStretch()
        ok_btn = QPushButton("OK")
        ok_btn.clicked.connect(self._on_ok)
        cancel_btn = QPushButton("Cancel")
        cancel_btn.clicked.connect(self.reject)
        btn_layout.addWidget(ok_btn)
        btn_layout.addWidget(cancel_btn)
        layout.addLayout(btn_layout)

        self._name_edit.setFocus()

    def _update_color_button(self):
        self._color_btn.setStyleSheet(
            f"background-color: {self._color}; border: 1px solid #ccc; border-radius: 4px;"
        )

    def _choose_color(self):
        color = QColorDialog.getColor(QColor(self._color), self, "Choose Color")
        if color.isValid():
            self._color = color.name()
            self._update_color_button()

    def _on_ok(self):
        name = self._name_edit.text().strip()
        if not name:
            QMessageBox.warning(self, "Warning", "Tag name cannot be empty")
            return
        self.accept()

    def get_result(self) -> Tuple[str, str]:
        return self._name_edit.text().strip(), self._color


class TagLabel(QLabel):
    clicked = pyqtSignal(int)
    right_clicked = pyqtSignal(int)

    def __init__(self, tag_id: int, name: str, color: str, font_size: int, parent=None):
        super().__init__(name, parent)
        self._tag_id = tag_id
        self._selected = False
        self._color = color
        self._base_color = color

        self.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setContentsMargins(8, 4, 8, 4)
        self.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)

        font = self.font()
        font.setPointSize(font_size)
        font.setBold(True)
        self.setFont(font)

        self._update_style()

    def set_selected(self, selected: bool):
        self._selected = selected
        self._update_style()

    def is_selected(self) -> bool:
        return self._selected

    def tag_id(self) -> int:
        return self._tag_id

    def _update_style(self):
        if self._selected:
            border_color = "#222"
            bg_color = self._lighten_color(self._base_color, 120)
        else:
            border_color = self._base_color
            bg_color = self._lighten_color(self._base_color, 180)
        text_color = self._base_color
        self.setStyleSheet(
            f"QLabel {{ background-color: {bg_color}; color: {text_color}; "
            f"border: 2px solid {border_color}; border-radius: 12px; padding: 2px 8px; }}"
        )

    def _lighten_color(self, hex_color: str, amount: int) -> str:
        color = QColor(hex_color)
        r = min(255, color.red() + (255 - color.red()) * amount // 255)
        g = min(255, color.green() + (255 - color.green()) * amount // 255)
        b = min(255, color.blue() + (255 - color.blue()) * amount // 255)
        return QColor(r, g, b).name()

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.set_selected(not self._selected)
            self.clicked.emit(self._tag_id)
        elif event.button() == Qt.MouseButton.RightButton:
            self.right_clicked.emit(self._tag_id)
        super().mousePressEvent(event)


class TFIDFRecommender:
    def __init__(self, db: Database):
        self._db = db
        self._doc_count = 0
        self._idf: Dict[str, float] = {}
        self._tag_vectors: Dict[int, Dict[str, float]] = {}
        self._stop_words: Set[str] = {
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought",
            "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "through", "during", "before", "after", "above",
            "below", "between", "out", "off", "over", "under", "again",
            "further", "then", "once", "here", "there", "when", "where", "why",
            "how", "all", "each", "every", "both", "few", "more", "most",
            "other", "some", "such", "no", "nor", "not", "only", "own", "same",
            "so", "than", "too", "very", "just", "and", "but", "if", "or",
            "because", "until", "while", "about", "against", "this", "that",
            "these", "those", "i", "me", "my", "myself", "we", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "yourselves",
            "he", "him", "his", "himself", "she", "her", "hers", "herself",
            "it", "its", "itself", "they", "them", "their", "theirs",
            "themselves", "what", "which", "who", "whom", "am", "up", "down",
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都",
            "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你",
            "会", "着", "没有", "看", "好", "自己", "这", "那", "他", "她",
            "它", "们", "这个", "那个", "什么", "怎么", "为什么", "如何",
            "可以", "可能", "应该", "需要", "因为", "所以", "但是", "而且",
            "或者", "如果", "虽然", "然而", "已经", "正在", "将", "被",
            "把", "让", "从", "向", "对", "与", "及", "等", "等等", "之",
            "而", "其", "于", "以", "及", "个", "之", "还", "又", "只",
            "能", "如", "通过", "进行", "使用", "基于", "主要", "其中"
        }

    def _tokenize(self, text: str) -> List[str]:
        tokens: List[str] = []
        if HAS_JIEBA:
            chinese_tokens = jieba.cut(text)
            tokens.extend([t.strip().lower() for t in chinese_tokens if t.strip()])
        english_tokens = re.findall(r'[a-zA-Z][a-zA-Z0-9_\-]{2,}', text.lower())
        tokens.extend(english_tokens)
        return [t for t in tokens if t and t not in self._stop_words and len(t) > 1]

    def rebuild_index(self):
        all_notes = self._db.list_notes(limit=10000)
        self._doc_count = len(all_notes)

        doc_freq: Counter = Counter()
        note_tokens: Dict[int, List[str]] = {}

        for note in all_notes:
            text = (note.get("title", "") or "") + " " + (note.get("content", "") or "")
            tokens = self._tokenize(text)
            note_tokens[note["id"]] = tokens
            unique_tokens = set(tokens)
            for token in unique_tokens:
                doc_freq[token] += 1

        self._idf = {}
        for token, df in doc_freq.items():
            self._idf[token] = math.log((self._doc_count + 1) / (df + 1)) + 1

        tags = self._db.list_tags()
        self._tag_vectors = {}

        for tag in tags:
            tag_notes = self._db.list_notes(tag_id=tag["id"], limit=10000)
            if not tag_notes:
                continue
            tag_tokens: List[str] = []
            for note in tag_notes:
                tag_tokens.extend(note_tokens.get(note["id"], []))
            if not tag_tokens:
                continue
            tf = Counter(tag_tokens)
            vector: Dict[str, float] = {}
            for token, count in tf.items():
                if token in self._idf:
                    vector[token] = (count / len(tag_tokens)) * self._idf[token]
            norm = math.sqrt(sum(v * v for v in vector.values()))
            if norm > 0:
                for token in vector:
                    vector[token] /= norm
            self._tag_vectors[tag["id"]] = vector

    def recommend_tags(self, note_content: str, note_title: str = "", top_k: int = 5) -> List[Tuple[int, float]]:
        if self._doc_count == 0:
            self.rebuild_index()
        if not self._tag_vectors:
            return []

        text = (note_title or "") + " " + (note_content or "")
        tokens = self._tokenize(text)
        if not tokens:
            return []

        tf = Counter(tokens)
        query_vector: Dict[str, float] = {}
        for token, count in tf.items():
            if token in self._idf:
                query_vector[token] = (count / len(tokens)) * self._idf[token]

        query_norm = math.sqrt(sum(v * v for v in query_vector.values()))
        if query_norm == 0:
            return []
        for token in query_vector:
            query_vector[token] /= query_norm

        similarities: List[Tuple[int, float]] = []
        for tag_id, tag_vector in self._tag_vectors.items():
            sim = sum(query_vector.get(t, 0) * tag_vector.get(t, 0) for t in query_vector)
            if sim > 0:
                similarities.append((tag_id, sim))

        similarities.sort(key=lambda x: x[1], reverse=True)
        return similarities[:top_k]


class TagCloudWidget(QWidget):
    tag_selected = pyqtSignal(int, bool)
    tag_renamed = pyqtSignal(int, str)
    tag_deleted = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self._tag_labels: Dict[int, TagLabel] = {}
        self._selected_tags: Set[int] = set()

        self._layout = QVBoxLayout(self)
        self._layout.setContentsMargins(0, 0, 0, 0)

        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setFrameShape(QFrame.Shape.NoFrame)

        self._container = QWidget()
        self._grid_layout = QGridLayout(self._container)
        self._grid_layout.setContentsMargins(8, 8, 8, 8)
        self._grid_layout.setSpacing(6)
        self._grid_layout.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)

        self._scroll.setWidget(self._container)
        self._layout.addWidget(self._scroll)

    def reload(self):
        self._clear()
        tags = self._db.list_tags()
        if not tags:
            return

        max_count = max(t.get("note_count", 0) for t in tags) or 1
        min_font = 10
        max_font = 24

        row = 0
        col = 0
        max_cols = 4

        for tag in tags:
            count = tag.get("note_count", 0)
            if max_count > 1:
                font_size = min_font + int((max_font - min_font) * (count / (max_count - 1)))
            else:
                font_size = (min_font + max_font) // 2
            font_size = max(min_font, min(max_font, font_size))

            label = TagLabel(tag["id"], tag["name"], tag.get("color", "#4A90D9"), font_size)
            if tag["id"] in self._selected_tags:
                label.set_selected(True)
            label.clicked.connect(self._on_tag_clicked)
            label.right_clicked.connect(self._on_tag_right_clicked)

            self._grid_layout.addWidget(label, row, col)
            self._tag_labels[tag["id"]] = label

            col += 1
            if col >= max_cols:
                col = 0
                row += 1

        self._grid_layout.setRowStretch(row + 1, 1)

    def _clear(self):
        while self._grid_layout.count():
            item = self._grid_layout.takeAt(0)
            widget = item.widget()
            if widget:
                widget.deleteLater()
        self._tag_labels.clear()

    def _on_tag_clicked(self, tag_id: int):
        label = self._tag_labels.get(tag_id)
        if not label:
            return
        selected = label.is_selected()
        if selected:
            self._selected_tags.add(tag_id)
        else:
            self._selected_tags.discard(tag_id)
        self.tag_selected.emit(tag_id, selected)

    def _on_tag_right_clicked(self, tag_id: int):
        from PyQt6.QtWidgets import QMenu, QAction
        from PyQt6.QtGui import QCursor

        menu = QMenu(self)

        rename_action = QAction("Rename", self)
        rename_action.triggered.connect(lambda: self._rename_tag(tag_id))
        menu.addAction(rename_action)

        delete_action = QAction("Delete", self)
        delete_action.triggered.connect(lambda: self._delete_tag(tag_id))
        menu.addAction(delete_action)

        menu.exec(QCursor.pos())

    def _rename_tag(self, tag_id: int):
        tags = {t["id"]: t for t in self._db.list_tags()}
        tag = tags.get(tag_id)
        if not tag:
            return
        name, ok = QInputDialog.getText(self, "Rename Tag", "New name:", text=tag["name"])
        if not ok or not name.strip():
            return
        name = name.strip()
        self._db.update_tag(tag_id, name=name)
        self.tag_renamed.emit(tag_id, name)
        self.reload()

    def _delete_tag(self, tag_id: int):
        tags = {t["id"]: t for t in self._db.list_tags()}
        tag = tags.get(tag_id)
        if not tag:
            return
        reply = QMessageBox.question(
            self, "Delete Tag",
            f"Delete tag '{tag['name']}'? This will remove it from all notes.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return
        self._db.delete_tag(tag_id)
        self._selected_tags.discard(tag_id)
        self.tag_deleted.emit(tag_id)
        self.reload()

    def selected_tag_ids(self) -> Set[int]:
        return set(self._selected_tags)

    def clear_selection(self):
        self._selected_tags.clear()
        for label in self._tag_labels.values():
            label.set_selected(False)


class TagPanelWidget(QWidget):
    tag_selected = pyqtSignal(int, bool)
    tag_created = pyqtSignal(int)
    tag_renamed = pyqtSignal(int, str)
    tag_deleted = pyqtSignal(int)
    tags_recommended = pyqtSignal(list)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self._recommender = TFIDFRecommender(db)

        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(4, 4, 4, 4)
        main_layout.setSpacing(6)

        header_layout = QHBoxLayout()
        title_label = QLabel("Tags")
        font = title_label.font()
        font.setBold(True)
        font.setPointSize(12)
        title_label.setFont(font)
        header_layout.addWidget(title_label)
        header_layout.addStretch()

        new_btn = QPushButton("+ New")
        new_btn.setFixedHeight(28)
        new_btn.clicked.connect(self._on_new_tag)
        header_layout.addWidget(new_btn)

        refresh_btn = QPushButton("⟳")
        refresh_btn.setFixedSize(28, 28)
        refresh_btn.setToolTip("Refresh recommendations")
        refresh_btn.clicked.connect(self._on_refresh_recommendations)
        header_layout.addWidget(refresh_btn)

        main_layout.addLayout(header_layout)

        rec_layout = QVBoxLayout()
        rec_title = QLabel("Recommended")
        rec_font = rec_title.font()
        rec_font.setBold(True)
        rec_title.setFont(rec_font)
        rec_layout.addWidget(rec_title)

        self._rec_container = QWidget()
        self._rec_layout = QHBoxLayout(self._rec_container)
        self._rec_layout.setContentsMargins(0, 0, 0, 0)
        self._rec_layout.setSpacing(4)
        self._rec_layout.setAlignment(Qt.AlignmentFlag.AlignLeft)
        rec_layout.addWidget(self._rec_container)

        main_layout.addLayout(rec_layout)

        self._tag_cloud = TagCloudWidget(db, self)
        self._tag_cloud.tag_selected.connect(self.tag_selected)
        self._tag_cloud.tag_renamed.connect(self.tag_renamed)
        self._tag_cloud.tag_deleted.connect(self.tag_deleted)
        main_layout.addWidget(self._tag_cloud, stretch=1)

    def reload(self):
        self._tag_cloud.reload()

    def tag_cloud(self) -> TagCloudWidget:
        return self._tag_cloud

    def recommender(self) -> TFIDFRecommender:
        return self._recommender

    def _on_new_tag(self):
        dialog = NewTagDialog(self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            name, color = dialog.get_result()
            existing = {t["name"]: t for t in self._db.list_tags()}
            if name in existing:
                QMessageBox.information(self, "Info", f"Tag '{name}' already exists")
                return
            tag_id = self._db.create_tag(name, color)
            self.tag_created.emit(tag_id)
            self.reload()
            self._recommender.rebuild_index()

    def _on_refresh_recommendations(self):
        self._recommender.rebuild_index()
        self.reload()

    def update_recommendations(self, note_title: str, note_content: str, exclude_tag_ids: Optional[Set[int]] = None):
        while self._rec_layout.count():
            item = self._rec_layout.takeAt(0)
            widget = item.widget()
            if widget:
                widget.deleteLater()

        if not note_title and not note_content:
            return

        recommendations = self._recommender.recommend_tags(note_content, note_title, top_k=5)
        if exclude_tag_ids:
            recommendations = [(tid, score) for tid, score in recommendations if tid not in exclude_tag_ids]

        tags = {t["id"]: t for t in self._db.list_tags()}

        if not recommendations:
            no_rec = QLabel("No recommendations")
            no_rec.setStyleSheet("color: #888;")
            self._rec_layout.addWidget(no_rec)
            self.tags_recommended.emit([])
            return

        rec_ids = []
        for tag_id, score in recommendations:
            tag = tags.get(tag_id)
            if not tag:
                continue
            rec_ids.append(tag_id)
            btn = QPushButton(f"{tag['name']} ({score:.2f})")
            btn.setStyleSheet(
                f"QPushButton {{ background-color: {self._lighten(tag['color'], 180)}; "
                f"color: {tag['color']}; border: 1px solid {tag['color']}; "
                f"border-radius: 10px; padding: 2px 10px; }}"
                f"QPushButton:hover {{ background-color: {self._lighten(tag['color'], 150)}; }}"
            )
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            btn.clicked.connect(lambda checked, tid=tag_id: self._on_recommendation_clicked(tid))
            self._rec_layout.addWidget(btn)

        self._rec_layout.addStretch()
        self.tags_recommended.emit(rec_ids)

    def _on_recommendation_clicked(self, tag_id: int):
        label = self._tag_cloud._tag_labels.get(tag_id)
        if label and not label.is_selected():
            label.set_selected(True)
            self._tag_cloud._selected_tags.add(tag_id)
            self.tag_selected.emit(tag_id, True)

    def _lighten(self, hex_color: str, amount: int) -> str:
        color = QColor(hex_color)
        r = min(255, color.red() + (255 - color.red()) * amount // 255)
        g = min(255, color.green() + (255 - color.green()) * amount // 255)
        b = min(255, color.blue() + (255 - color.blue()) * amount // 255)
        return QColor(r, g, b).name()
