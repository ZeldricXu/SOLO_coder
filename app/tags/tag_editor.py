from typing import Optional, List, Dict, Set
from PyQt6.QtWidgets import (
    QWidget, QHBoxLayout, QLineEdit, QPushButton, QLabel, QCompleter,
    QMenu, QAction, QInputDialog, QMessageBox, QFrame
)
from PyQt6.QtCore import Qt, pyqtSignal, QStringListModel, QEvent
from PyQt6.QtGui import QKeyEvent, QColor, QCursor, QFocusEvent
from app.database import Database


class TagChip(QFrame):
    remove_clicked = pyqtSignal(int)
    right_clicked = pyqtSignal(int)

    def __init__(self, tag_id: int, name: str, color: str, parent=None):
        super().__init__(parent)
        self._tag_id = tag_id
        self._name = name
        self._color = color
        self.setFrameShape(QFrame.Shape.StyledPanel)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(8, 2, 4, 2)
        layout.setSpacing(4)

        bg_color = self._lighten_color(color, 180)
        text_color = color
        border_color = color

        self.setStyleSheet(
            f"TagChip {{ background-color: {bg_color}; border: 1px solid {border_color}; "
            f"border-radius: 12px; }}"
        )

        name_label = QLabel(name)
        name_label.setStyleSheet(f"color: {text_color}; font-weight: bold;")
        layout.addWidget(name_label)

        remove_btn = QPushButton("×")
        remove_btn.setFixedSize(18, 18)
        remove_btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        remove_btn.setStyleSheet(
            f"QPushButton {{ background-color: transparent; color: {text_color}; "
            f"border: none; border-radius: 9px; font-size: 14px; font-weight: bold; }}"
            f"QPushButton:hover {{ background-color: {self._lighten_color(color, 150)}; }}"
        )
        remove_btn.clicked.connect(lambda: self.remove_clicked.emit(self._tag_id))
        layout.addWidget(remove_btn)

        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self._on_context_menu)

    def tag_id(self) -> int:
        return self._tag_id

    def tag_name(self) -> str:
        return self._name

    def _lighten_color(self, hex_color: str, amount: int) -> str:
        color = QColor(hex_color)
        r = min(255, color.red() + (255 - color.red()) * amount // 255)
        g = min(255, color.green() + (255 - color.green()) * amount // 255)
        b = min(255, color.blue() + (255 - color.blue()) * amount // 255)
        return QColor(r, g, b).name()

    def _on_context_menu(self, pos):
        from PyQt6.QtGui import QCursor

        menu = QMenu(self)

        rename_action = QAction("Rename Tag", self)
        rename_action.triggered.connect(lambda: self._request_rename())
        menu.addAction(rename_action)

        delete_action = QAction("Delete Tag", self)
        delete_action.triggered.connect(lambda: self._request_delete())
        menu.addAction(delete_action)

        menu.exec(self.mapToGlobal(pos))

    def _request_rename(self):
        self.right_clicked.emit(self._tag_id)

    def _request_delete(self):
        self.right_clicked.emit(self._tag_id)


class TagLineEdit(QLineEdit):
    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self.setPlaceholderText("Add tag...")
        self._completer = QCompleter()
        self._completer.setCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self._completer.setCompletionMode(QCompleter.CompletionMode.PopupCompletion)
        self.setCompleter(self._completer)
        self.refresh_completer()
        self.setFixedWidth(140)

    def refresh_completer(self):
        tags = self._db.list_tags()
        names = [t["name"] for t in tags]
        model = QStringListModel(names, self._completer)
        self._completer.setModel(model)

    def keyPressEvent(self, event: QKeyEvent):
        if event.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter) and event.modifiers() == Qt.KeyboardModifier.NoModifier:
            text = self.text().strip()
            if text:
                parent = self.parent()
                while parent and not hasattr(parent, "add_tag_by_name"):
                    parent = parent.parent()
                if parent and hasattr(parent, "add_tag_by_name"):
                    parent.add_tag_by_name(text)
                self.clear()
                return
        if event.key() == Qt.Key.Key_Backspace and not self.text():
            parent = self.parent()
            while parent and not hasattr(parent, "remove_last_tag"):
                parent = parent.parent()
            if parent and hasattr(parent, "remove_last_tag"):
                parent.remove_last_tag()
            return
        super().keyPressEvent(event)


class TagEditorWidget(QWidget):
    tag_added = pyqtSignal(int)
    tag_removed = pyqtSignal(int)
    tag_renamed = pyqtSignal(int, str)
    tag_deleted = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self._note_id: Optional[int] = None
        self._tag_chips: Dict[int, TagChip] = {}
        self._current_tag_ids: Set[int] = set()

        self._layout = QHBoxLayout(self)
        self._layout.setContentsMargins(4, 4, 4, 4)
        self._layout.setSpacing(6)
        self._layout.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)

        self._tag_input = TagLineEdit(db, self)
        self._tag_input.returnPressed.connect(self._on_return_pressed)

        self._layout.addStretch()
        self._layout.addWidget(self._tag_input)

    def set_note(self, note_id: Optional[int]):
        self._note_id = note_id
        self.reload()

    def note_id(self) -> Optional[int]:
        return self._note_id

    def reload(self):
        self._clear_chips()
        self._current_tag_ids.clear()
        if self._note_id:
            tags = self._db.get_note_tags(self._note_id)
            for tag in tags:
                self._add_chip(tag["id"], tag["name"], tag.get("color", "#4A90D9"))
                self._current_tag_ids.add(tag["id"])
        self._tag_input.refresh_completer()

    def current_tag_ids(self) -> Set[int]:
        return set(self._current_tag_ids)

    def _clear_chips(self):
        for chip in self._tag_chips.values():
            self._layout.removeWidget(chip)
            chip.deleteLater()
        self._tag_chips.clear()

    def _add_chip(self, tag_id: int, name: str, color: str):
        chip = TagChip(tag_id, name, color, self)
        chip.remove_clicked.connect(self._on_remove_tag)
        chip.right_clicked.connect(self._on_tag_right_clicked)
        insert_pos = self._layout.count() - 2
        self._layout.insertWidget(max(0, insert_pos), chip)
        self._tag_chips[tag_id] = chip

    def add_tag_by_name(self, name: str):
        name = name.strip()
        if not name:
            return
        tags = {t["name"]: t for t in self._db.list_tags()}
        if name in tags:
            tag = tags[name]
            if tag["id"] in self._current_tag_ids:
                return
            tag_id = tag["id"]
        else:
            tag_id = self._db.create_tag(name)
            self._tag_input.refresh_completer()

        if self._note_id:
            self._db.add_tag_to_note(self._note_id, tag_id)

        tag_info = None
        for t in self._db.list_tags():
            if t["id"] == tag_id:
                tag_info = t
                break
        if tag_info:
            self._add_chip(tag_id, tag_info["name"], tag_info.get("color", "#4A90D9"))
            self._current_tag_ids.add(tag_id)
            self.tag_added.emit(tag_id)

    def add_tag_by_id(self, tag_id: int):
        if tag_id in self._current_tag_ids:
            return
        tags = {t["id"]: t for t in self._db.list_tags()}
        tag = tags.get(tag_id)
        if not tag:
            return
        if self._note_id:
            self._db.add_tag_to_note(self._note_id, tag_id)
        self._add_chip(tag_id, tag["name"], tag.get("color", "#4A90D9"))
        self._current_tag_ids.add(tag_id)
        self.tag_added.emit(tag_id)

    def remove_last_tag(self):
        if self._current_tag_ids:
            tag_ids = list(self._current_tag_ids)
            last_id = tag_ids[-1]
            self._on_remove_tag(last_id)

    def _on_remove_tag(self, tag_id: int):
        if tag_id not in self._tag_chips:
            return
        if self._note_id:
            self._db.remove_tag_from_note(self._note_id, tag_id)
        chip = self._tag_chips.pop(tag_id)
        self._layout.removeWidget(chip)
        chip.deleteLater()
        self._current_tag_ids.discard(tag_id)
        self.tag_removed.emit(tag_id)

    def _on_return_pressed(self):
        text = self._tag_input.text().strip()
        if text:
            self.add_tag_by_name(text)
            self._tag_input.clear()

    def _on_tag_right_clicked(self, tag_id: int):
        from PyQt6.QtGui import QCursor

        tags = {t["id"]: t for t in self._db.list_tags()}
        tag = tags.get(tag_id)
        if not tag:
            return

        menu = QMenu(self)

        rename_action = QAction("Rename Tag", self)
        rename_action.triggered.connect(lambda: self._rename_tag(tag_id))
        menu.addAction(rename_action)

        delete_action = QAction("Delete Tag", self)
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
        self._current_tag_ids.discard(tag_id)
        self.tag_deleted.emit(tag_id)
        self.reload()

    def focus_input(self):
        self._tag_input.setFocus()
