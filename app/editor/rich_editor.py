import re
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import (
    Qt,
    pyqtSignal,
    QEvent,
    QMimeData,
    QTimer,
    QRegularExpression,
)
from PyQt6.QtGui import (
    QKeyEvent,
    QMouseEvent,
    QTextCursor,
    QFont,
    QTextDocument,
    QTextCharFormat,
    QColor,
    QDesktopServices,
    QAction,
    QKeySequence,
)
from PyQt6.QtWidgets import QTextEdit, QApplication, QMenu

from .highlighter import MarkdownHighlighter
from .katex_renderer import KaTeXRenderer
from .image_handler import ImageHandler


class EditorMode:
    MARKDOWN = "markdown"
    WYSIWYG = "wysiwyg"


class RichTextEditor(QTextEdit):

    saveRequested = pyqtSignal(str, str)
    noteLinkClicked = pyqtSignal(int)
    cursorPositionChangedEx = pyqtSignal(int, int)
    editorModeChanged = pyqtSignal(str)

    def __init__(self, config=None, parent=None):
        super().__init__(parent)
        self.config = config
        self._mode = EditorMode.MARKDOWN
        self._markdown_content = ""
        self._wysiwyg_content = ""
        self._current_note_id: Optional[int] = None

        self._init_ui()
        self._init_handlers()
        self._init_highlighter()
        self._init_shortcuts()
        self._setup_signals()

    def _init_ui(self):
        font = QFont()
        if self.config:
            font.setFamily(self.config.font_family)
            font.setPointSize(self.config.font_size)
        else:
            font.setFamily("PingFang SC")
            font.setPointSize(14)
        self.setFont(font)
        self.setAcceptRichText(True)
        self.setAcceptDrops(True)
        self.setTabChangesFocus(False)
        self.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        self.setWordWrapMode(True)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)

    def _init_handlers(self):
        images_dir = self.config.images_dir if self.config else str(Path.home() / ".knowledge_vault" / "attachments" / "images")
        max_width = self.config.image_max_width if self.config else 2000
        self.image_handler = ImageHandler(images_dir, max_width)

        cache_dir = str(Path(images_dir).parent / "formulas")
        self.katex_renderer = KaTeXRenderer(cache_dir)

    def _init_highlighter(self):
        theme = self.config.theme if self.config else "light"
        self.highlighter = MarkdownHighlighter(self.document(), theme)

    def _init_shortcuts(self):
        save_action = QAction("Save", self)
        save_action.setShortcut(QKeySequence.StandardKey.Save)
        save_action.triggered.connect(self._emit_save_requested)
        self.addAction(save_action)

        bold_action = QAction("Bold", self)
        bold_action.setShortcut(QKeySequence("Ctrl+B"))
        bold_action.triggered.connect(lambda: self._apply_format_surround("**", "**"))
        self.addAction(bold_action)

        italic_action = QAction("Italic", self)
        italic_action.setShortcut(QKeySequence("Ctrl+I"))
        italic_action.triggered.connect(lambda: self._apply_format_surround("*", "*"))
        self.addAction(italic_action)

        code_action = QAction("Inline Code", self)
        code_action.setShortcut(QKeySequence("Ctrl+`"))
        code_action.triggered.connect(lambda: self._apply_format_surround("`", "`"))
        self.addAction(code_action)

        strike_action = QAction("Strikethrough", self)
        strike_action.setShortcut(QKeySequence("Ctrl+Shift+S"))
        strike_action.triggered.connect(lambda: self._apply_format_surround("~~", "~~"))
        self.addAction(strike_action)

        link_action = QAction("Insert Link", self)
        link_action.setShortcut(QKeySequence("Ctrl+K"))
        link_action.triggered.connect(self._insert_link)
        self.addAction(link_action)

        image_action = QAction("Insert Image", self)
        image_action.setShortcut(QKeySequence("Ctrl+Shift+I"))
        image_action.triggered.connect(self._insert_image_placeholder)
        self.addAction(image_action)

    def _setup_signals(self):
        self.cursorPositionChanged.connect(self._on_cursor_position_changed)
        self.textChanged.connect(self._on_text_changed)
        self.customContextMenuRequested.connect(self._show_context_menu)

    @property
    def mode(self) -> str:
        return self._mode

    @mode.setter
    def mode(self, value: str):
        if value == self._mode:
            return
        old_mode = self._mode
        self._mode = value
        self._switch_mode(old_mode, value)
        self.editorModeChanged.emit(value)

    def _switch_mode(self, old_mode: str, new_mode: str):
        if old_mode == EditorMode.MARKDOWN and new_mode == EditorMode.WYSIWYG:
            self._markdown_content = self.toPlainText()
            self._render_wysiwyg()
        elif old_mode == EditorMode.WYSIWYG and new_mode == EditorMode.MARKDOWN:
            self._wysiwyg_content = self.toHtml()
            self.setPlainText(self._markdown_content)
            self.highlighter.setDocument(self.document())
            self.highlighter.rehighlight()

    def _render_wysiwyg(self):
        import markdown
        html = markdown.markdown(
            self._markdown_content,
            extensions=["fenced_code", "tables", "nl2br"],
            output_format="html5",
        )
        self.setHtml(html)

    def _emit_save_requested(self):
        if self._mode == EditorMode.MARKDOWN:
            content_md = self.toPlainText()
            self._markdown_content = content_md
            content_html = self._markdown_to_html(content_md)
        else:
            content_html = self.toHtml()
            content_md = self._markdown_content
        self.saveRequested.emit(content_html, content_md)

    @staticmethod
    def _markdown_to_html(md_text: str) -> str:
        try:
            import markdown
            return markdown.markdown(
                md_text,
                extensions=["fenced_code", "tables", "nl2br"],
                output_format="html5",
            )
        except ImportError:
            from html import escape
            return "<pre>" + escape(md_text) + "</pre>"

    def _on_cursor_position_changed(self):
        cursor = self.textCursor()
        pos = cursor.position()
        block = cursor.block()
        line = block.blockNumber() + 1
        col = pos - block.position() + 1
        self.cursorPositionChangedEx.emit(line, col)

    def _on_text_changed(self):
        if self._mode == EditorMode.MARKDOWN:
            self._markdown_content = self.toPlainText()
        else:
            self._wysiwyg_content = self.toHtml()

    def _apply_format_surround(self, prefix: str, suffix: str):
        cursor = self.textCursor()
        if not cursor.hasSelection():
            word = self._select_word_at_cursor(cursor)
            if not word:
                cursor.insertText(prefix + suffix)
                cursor.movePosition(QTextCursor.MoveOperation.Left, QTextCursor.MoveMode.MoveAnchor, len(suffix))
                self.setTextCursor(cursor)
                return

        selected = cursor.selectedText()
        start = cursor.selectionStart()
        end = cursor.selectionEnd()

        doc = self.document()
        text = doc.toPlainText()

        prefix_len = len(prefix)
        suffix_len = len(suffix)
        before = text[max(0, start - prefix_len):start]
        after = text[end:end + suffix_len]

        if before == prefix and after == suffix:
            cursor.setPosition(start - prefix_len, QTextCursor.MoveMode.MoveAnchor)
            cursor.setPosition(end + suffix_len, QTextCursor.MoveMode.KeepAnchor)
            cursor.insertText(selected)
        elif before == prefix:
            cursor.setPosition(start - prefix_len, QTextCursor.MoveMode.MoveAnchor)
            cursor.setPosition(end, QTextCursor.MoveMode.KeepAnchor)
            cursor.insertText(prefix + selected)
        elif after == suffix:
            cursor.setPosition(start, QTextCursor.MoveMode.MoveAnchor)
            cursor.setPosition(end + suffix_len, QTextCursor.MoveMode.KeepAnchor)
            cursor.insertText(selected + suffix)
        else:
            cursor.insertText(prefix + selected + suffix)

    def _select_word_at_cursor(self, cursor: QTextCursor) -> str:
        cursor.select(QTextCursor.SelectionType.WordUnderCursor)
        return cursor.selectedText()

    def _insert_link(self):
        cursor = self.textCursor()
        selected = cursor.selectedText() or "链接文本"
        cursor.insertText(f"[{selected}](https://)")

        cursor = self.textCursor()
        pos = cursor.position()
        cursor.setPosition(pos - 1, QTextCursor.MoveMode.MoveAnchor)
        cursor.setPosition(pos - 8, QTextCursor.MoveMode.KeepAnchor)
        self.setTextCursor(cursor)

    def _insert_image_placeholder(self):
        cursor = self.textCursor()
        cursor.insertText("![图片描述](https://)")

    def keyPressEvent(self, event: QKeyEvent):
        key = event.key()
        modifiers = event.modifiers()

        if key == Qt.Key.Key_Return or key == Qt.Key.Key_Enter:
            if self._handle_enter_key(event):
                return

        if key == Qt.Key.Key_Tab:
            if self._handle_tab_key(event):
                return

        if key == Qt.Key.Key_Backspace:
            if self._handle_backspace_key(event):
                return

        if modifiers & Qt.KeyboardModifier.ControlModifier and key == Qt.Key.Key_S:
            self._emit_save_requested()
            event.accept()
            return

        super().keyPressEvent(event)
        self._handle_markdown_shortcuts(event)

    def _handle_markdown_shortcuts(self, event: QKeyEvent):
        cursor = self.textCursor()
        block_text = cursor.block().text()

        heading_match = re.match(r"^(#{1,6})\s$", block_text)
        if heading_match and event.key() == Qt.Key.Key_Space:
            level = len(heading_match.group(1))
            self._format_heading(level)
            return

        if block_text in ("-", "*", "+") and event.key() == Qt.Key.Key_Space:
            self._format_bullet_list()
            return

        num_match = re.match(r"^(\d+)\.\s$", block_text)
        if num_match and event.key() == Qt.Key.Key_Space:
            self._format_numbered_list(int(num_match.group(1)))
            return

        if block_text == ">" and event.key() == Qt.Key.Key_Space:
            self._format_quote()
            return

        if block_text in ("```", "~~~") and event.key() == Qt.Key.Key_Return:
            self._format_code_block()
            return

    def _format_heading(self, level: int):
        cursor = self.textCursor()
        cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
        selected = cursor.selectedText()
        heading_text = selected[level + 1:] if selected else ""
        cursor.insertText(f"{'#' * level} {heading_text}")

    def _format_bullet_list(self):
        cursor = self.textCursor()
        cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
        selected = cursor.selectedText()
        list_text = selected[2:] if len(selected) > 2 else ""
        cursor.insertText(f"- {list_text}")

    def _format_numbered_list(self, num: int):
        cursor = self.textCursor()
        cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
        selected = cursor.selectedText()
        list_match = re.match(r"^\d+\.\s(.*)$", selected)
        list_text = list_match.group(1) if list_match else ""
        cursor.insertText(f"{num}. {list_text}")

    def _format_quote(self):
        cursor = self.textCursor()
        cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
        selected = cursor.selectedText()
        quote_text = selected[2:] if len(selected) > 2 else ""
        cursor.insertText(f"> {quote_text}")

    def _format_code_block(self):
        cursor = self.textCursor()
        cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
        selected = cursor.selectedText()
        lang = selected[3:].strip() if len(selected) > 3 else ""
        cursor.insertText(f"```{lang}\n\n```")
        cursor.movePosition(QTextCursor.MoveOperation.Up, QTextCursor.MoveMode.MoveAnchor)
        self.setTextCursor(cursor)

    def _handle_enter_key(self, event: QKeyEvent) -> bool:
        cursor = self.textCursor()
        block_text = cursor.block().text().strip()

        if block_text in ("-", "*", "+", "- [ ]", "- [x]", "- [X]"):
            cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
            cursor.insertText("")
            return True

        num_match = re.match(r"^(\d+)\.\s*$", block_text)
        if num_match:
            cursor.select(QTextCursor.SelectionType.BlockUnderCursor)
            cursor.insertText("")
            return True

        if re.match(r"^[-*+]\s+", block_text):
            super().keyPressEvent(event)
            cursor = self.textCursor()
            cursor.insertText("- ")
            return True

        if re.match(r"^\d+\.\s+", block_text):
            super().keyPressEvent(event)
            cursor = self.textCursor()
            num_match = re.match(r"^(\d+)\.\s+", block_text)
            if num_match:
                next_num = int(num_match.group(1)) + 1
                cursor.insertText(f"{next_num}. ")
            return True

        if block_text.startswith("> "):
            super().keyPressEvent(event)
            cursor = self.textCursor()
            cursor.insertText("> ")
            return True

        return False

    def _handle_tab_key(self, event: QKeyEvent) -> bool:
        cursor = self.textCursor()
        if event.modifiers() & Qt.KeyboardModifier.ShiftModifier:
            block_text = cursor.block().text()
            if block_text.startswith("    ") or block_text.startswith("\t"):
                cursor.movePosition(QTextCursor.MoveOperation.StartOfBlock, QTextCursor.MoveMode.MoveAnchor)
                if block_text.startswith("    "):
                    for _ in range(4):
                        cursor.deleteChar()
                elif block_text.startswith("\t"):
                    cursor.deleteChar()
                return True
            return False

        cursor.insertText("    ")
        return True

    def _handle_backspace_key(self, event: QKeyEvent) -> bool:
        cursor = self.textCursor()
        if cursor.hasSelection():
            return False

        block_text = cursor.block().text()
        pos_in_block = cursor.position() - cursor.block().position()

        if pos_in_block <= 4 and block_text.startswith("    "):
            spaces_count = 0
            for c in block_text[:pos_in_block]:
                if c == " ":
                    spaces_count += 1
                else:
                    break

            if spaces_count == pos_in_block and spaces_count > 0:
                delete_count = min(spaces_count, 4)
                for _ in range(delete_count):
                    cursor.deletePreviousChar()
                return True

        return False

    def canInsertFromMimeData(self, source: QMimeData) -> bool:
        if source.hasImage():
            return True
        if source.hasUrls():
            for url in source.urls():
                if url.isLocalFile():
                    suffix = Path(url.toLocalFile()).suffix.lower()
                    if suffix in {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"}:
                        return True
        return super().canInsertFromMimeData(source)

    def insertFromMimeData(self, source: QMimeData):
        saved_path = self.image_handler.handle_mime_data(source)
        if saved_path:
            img_tag = self.image_handler.generate_html_tag(saved_path, "")
            cursor = self.textCursor()
            if self._mode == EditorMode.MARKDOWN:
                md_tag = self.image_handler.generate_markdown_tag(saved_path, "")
                cursor.insertText(md_tag)
            else:
                cursor.insertHtml(img_tag)
            return

        super().insertFromMimeData(source)

    def _show_context_menu(self, pos):
        menu = self.createStandardContextMenu()

        menu.addSeparator()

        insert_menu = menu.addMenu("插入")
        img_action = insert_menu.addAction("从剪贴板粘贴图片")
        img_action.triggered.connect(self._paste_image_from_clipboard)

        formula_menu = insert_menu.addMenu("数学公式")
        inline_formula = formula_menu.addAction("行内公式 $...$")
        inline_formula.triggered.connect(lambda: self._apply_format_surround("$", "$"))
        block_formula = formula_menu.addAction("块级公式 $$...$$")
        block_formula.triggered.connect(lambda: self._apply_format_surround("$$", "$$"))

        menu.exec(self.mapToGlobal(pos))

    def _paste_image_from_clipboard(self):
        clipboard = QApplication.clipboard()
        mime_data = clipboard.mimeData()
        saved_path = self.image_handler.handle_mime_data(mime_data)
        if saved_path:
            cursor = self.textCursor()
            if self._mode == EditorMode.MARKDOWN:
                md_tag = self.image_handler.generate_markdown_tag(saved_path, "")
                cursor.insertText(md_tag)
            else:
                img_tag = self.image_handler.generate_html_tag(saved_path, "")
                cursor.insertHtml(img_tag)

    def mousePressEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.LeftButton:
            anchor = self.anchorAt(event.position().toPoint())
            if anchor:
                self._process_link_click(anchor, event.modifiers())
                event.accept()
                return

        super().mousePressEvent(event)

    def _process_link_click(self, anchor: str, modifiers):
        note_match = re.match(r"@note/(\d+)", anchor)
        if note_match:
            note_id = int(note_match.group(1))
            self.noteLinkClicked.emit(note_id)
            return

        note_match2 = re.match(r"note://(\d+)", anchor)
        if note_match2:
            note_id = int(note_match2.group(1))
            self.noteLinkClicked.emit(note_id)
            return

        if modifiers & Qt.KeyboardModifier.ControlModifier:
            if anchor.startswith(("http://", "https://", "file://")):
                from PyQt6.QtCore import QUrl
                QDesktopServices.openUrl(QUrl(anchor))

    def set_note_content(self, content_md: str, content_html: Optional[str] = None,
                         note_id: Optional[int] = None):
        self._current_note_id = note_id
        self._markdown_content = content_md
        self._wysiwyg_content = content_html or ""

        if self._mode == EditorMode.MARKDOWN:
            self.setPlainText(content_md)
        else:
            if content_html:
                self.setHtml(content_html)
            else:
                self._markdown_content = content_md
                self._render_wysiwyg()

    def get_content_markdown(self) -> str:
        if self._mode == EditorMode.MARKDOWN:
            return self.toPlainText()
        return self._markdown_content

    def get_content_html(self) -> str:
        if self._mode == EditorMode.WYSIWYG:
            return self.toHtml()
        return self._markdown_to_html(self._markdown_content)

    def clear_content(self):
        self.clear()
        self._markdown_content = ""
        self._wysiwyg_content = ""
        self._current_note_id = None

    def refresh_highlighter(self, theme: str = "light"):
        self.highlighter.setDocument(None)
        self.highlighter = MarkdownHighlighter(self.document(), theme)
        self.highlighter.rehighlight()

    def find_internal_links(self) -> list[tuple[int, str]]:
        results = []
        text = self.toPlainText()

        for match in re.finditer(r"@note/(\d+)", text):
            try:
                note_id = int(match.group(1))
                results.append((note_id, match.group(0)))
            except ValueError:
                pass

        for match in re.finditer(r"\[([^\]]*)\]\(note://(\d+)\)", text):
            try:
                note_id = int(match.group(2))
                results.append((note_id, match.group(0)))
            except ValueError:
                pass

        return results

    def insert_note_link(self, note_id: int, note_title: str = ""):
        cursor = self.textCursor()
        display_text = note_title if note_title else f"@note/{note_id}"
        link_text = f"[{display_text}](note://{note_id})"
        cursor.insertText(link_text)
