from datetime import datetime
from typing import Optional

from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QSplitter,
    QTabWidget, QLabel, QLineEdit, QToolBar, QStatusBar,
    QMessageBox, QFileDialog, QDialog, QTreeWidget, QTreeWidgetItem,
    QMenu, QDockWidget
)
from PyQt6.QtCore import Qt, QTimer, QSize
from PyQt6.QtGui import QAction, QKeySequence, QIcon, QFont

from app.config import Config
from app.database import Database

from app.editor import RichTextEditor, EditorMode, ImageHandler, KaTeXRenderer
from app.graph import GraphWidget
from app.search.search_panel import SearchPanel
from app.tags.folder_tree import FolderTreeWidget
from app.tags.tag_panel import TagPanelWidget
from app.tags.tag_editor import TagEditorWidget
from app.ui.note_list import NoteListWidget
from app.literature.literature_widget import LiteratureWidget
from app.attachments.attachment_widget import AttachmentListWidget, AttachmentManagerDialog
from app.io.export_widget import ExportDialog, ImportDialog


class MainWindow(QMainWindow):
    def __init__(self, config: Config, db: Database):
        super().__init__()
        self.config = config
        self.db = db
        self.current_note_id: Optional[int] = None
        self._dirty = False
        self._auto_save_timer = QTimer(self)
        self._auto_save_timer.setInterval(config.auto_save_interval_ms)
        self._auto_save_timer.timeout.connect(self.auto_save)
        self._init_ui()
        self._init_docks()
        self._init_menu()
        self._connect_signals()
        self._auto_save_timer.start()
        self.setWindowTitle("KnowledgeVault")
        self.resize(1400, 900)
        self._apply_font()

    def _apply_font(self):
        font = QFont(self.config.font_family, self.config.font_size)
        self.setFont(font)

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        self.splitter = QSplitter(Qt.Orientation.Horizontal)
        main_layout.addWidget(self.splitter)

        self.note_list = NoteListWidget(self.db)
        self.note_list.setMinimumWidth(280)
        self.splitter.addWidget(self.note_list)

        right_panel = QWidget()
        right_layout = QVBoxLayout(right_panel)
        right_layout.setContentsMargins(0, 0, 0, 0)
        right_layout.setSpacing(0)

        self.title_input = QLineEdit()
        self.title_input.setPlaceholderText("笔记标题")
        self.title_input.setStyleSheet("""
            QLineEdit {
                font-size: 20px;
                font-weight: 600;
                padding: 12px 16px;
                border: none;
                border-bottom: 1px solid #e0e0e0;
                background: white;
            }
        """)
        right_layout.addWidget(self.title_input)

        self.tag_editor = TagEditorWidget(self.db)
        right_layout.addWidget(self.tag_editor)

        self.editor = RichTextEditor(self.config)
        right_layout.addWidget(self.editor, 1)

        self.attachment_list = AttachmentListWidget(self.db, self.config)
        self.attachment_list.setMaximumHeight(140)
        right_layout.addWidget(self.attachment_list)

        self.editor_toolbar = QToolBar("编辑器工具栏")
        self.editor_toolbar.setIconSize(QSize(18, 18))
        self.editor_toolbar.setMovable(False)
        self._build_editor_toolbar()
        right_layout.addWidget(self.editor_toolbar)

        self.status_info = QLabel("就绪")
        self.status_info.setStyleSheet("color: #607d8b; font-size: 11px; padding: 4px 12px;")
        right_layout.addWidget(self.status_info)

        right_panel.setMinimumWidth(500)
        self.splitter.addWidget(right_panel)

        self.splitter.setStretchFactor(0, 0)
        self.splitter.setStretchFactor(1, 1)
        self.splitter.setSizes([320, 1080])

        self.statusBar = QStatusBar()
        self.setStatusBar(self.statusBar)
        self.statusBar.showMessage("KnowledgeVault 已就绪")

    def _build_editor_toolbar(self):
        mode_action = QAction("📝 Markdown / WYSIWYG", self)
        mode_action.triggered.connect(self._toggle_editor_mode)
        self.editor_toolbar.addAction(mode_action)

        self.editor_toolbar.addSeparator()

        bold_action = QAction("粗体", self)
        bold_action.setShortcut(QKeySequence("Ctrl+B"))
        bold_action.triggered.connect(lambda: self.editor.insert_markdown_surround("**"))
        self.editor_toolbar.addAction(bold_action)

        italic_action = QAction("斜体", self)
        italic_action.setShortcut(QKeySequence("Ctrl+I"))
        italic_action.triggered.connect(lambda: self.editor.insert_markdown_surround("*"))
        self.editor_toolbar.addAction(italic_action)

        code_action = QAction("行内代码", self)
        code_action.setShortcut(QKeySequence("Ctrl+`"))
        code_action.triggered.connect(lambda: self.editor.insert_markdown_surround("`"))
        self.editor_toolbar.addAction(code_action)

        link_action = QAction("链接", self)
        link_action.setShortcut(QKeySequence("Ctrl+K"))
        link_action.triggered.connect(self.editor.insert_markdown_link)
        self.editor_toolbar.addAction(link_action)

        img_action = QAction("图片", self)
        img_action.setShortcut(QKeySequence("Ctrl+Shift+I"))
        img_action.triggered.connect(self._insert_image_file)
        self.editor_toolbar.addAction(img_action)

        formula_action = QAction("数学公式", self)
        formula_action.triggered.connect(self._insert_formula)
        self.editor_toolbar.addAction(formula_action)

        self.editor_toolbar.addSeparator()

        save_action = QAction("💾 保存", self)
        save_action.setShortcut(QKeySequence("Ctrl+S"))
        save_action.triggered.connect(self.save_current_note)
        self.editor_toolbar.addAction(save_action)

    def _init_docks(self):
        self.folder_dock = QDockWidget("目录", self)
        self.folder_dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        self.folder_tree = FolderTreeWidget(self.db)
        self.folder_dock.setWidget(self.folder_tree)
        self.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, self.folder_dock)

        self.tag_dock = QDockWidget("标签", self)
        self.tag_dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        self.tag_panel = TagPanelWidget(self.db)
        self.tag_dock.setWidget(self.tag_panel)
        self.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, self.tag_dock)

        self.search_dock = QDockWidget("搜索", self)
        self.search_dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        self.search_panel = SearchPanel(self.db)
        self.search_dock.setWidget(self.search_panel)
        self.addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, self.search_dock)

        self.graph_dock = QDockWidget("知识图谱", self)
        self.graph_dock.setAllowedAreas(Qt.DockWidgetArea.AllDockWidgetAreas)
        self.graph_widget = GraphWidget()
        self.graph_widget.load_graph(self.db)
        self.graph_dock.setWidget(self.graph_widget)
        self.addDockWidget(Qt.DockWidgetArea.BottomDockWidgetArea, self.graph_dock)

        self.literature_dock = QDockWidget("文献管理", self)
        self.literature_dock.setAllowedAreas(Qt.DockWidgetArea.AllDockWidgetAreas)
        self.literature_widget = LiteratureWidget(self.db, self.config)
        self.literature_dock.setWidget(self.literature_widget)
        self.addDockWidget(Qt.DockWidgetArea.BottomDockWidgetArea, self.literature_dock)

        self.tabifyDockWidget(self.graph_dock, self.literature_dock)
        self.graph_dock.raise_()

    def _init_menu(self):
        menubar = self.menuBar()

        file_menu = menubar.addMenu("文件")

        new_note_action = QAction("新建笔记", self)
        new_note_action.setShortcut(QKeySequence("Ctrl+N"))
        new_note_action.triggered.connect(self.note_list._on_new_note)
        file_menu.addAction(new_note_action)

        file_menu.addSeparator()

        import_action = QAction("导入...", self)
        import_action.triggered.connect(self._show_import_dialog)
        file_menu.addAction(import_action)

        export_action = QAction("导出...", self)
        export_action.triggered.connect(self._show_export_dialog)
        file_menu.addAction(export_action)

        file_menu.addSeparator()

        backup_action = QAction("整库备份(ZIP)...", self)
        backup_action.triggered.connect(self._backup_database)
        file_menu.addAction(backup_action)

        file_menu.addSeparator()

        quit_action = QAction("退出", self)
        quit_action.setShortcut(QKeySequence("Ctrl+Q"))
        quit_action.triggered.connect(self.close)
        file_menu.addAction(quit_action)

        edit_menu = menubar.addMenu("编辑")

        save_action = QAction("保存笔记", self)
        save_action.setShortcut(QKeySequence("Ctrl+S"))
        save_action.triggered.connect(self.save_current_note)
        edit_menu.addAction(save_action)

        search_focus_action = QAction("聚焦搜索", self)
        search_focus_action.setShortcut(QKeySequence("Ctrl+F"))
        search_focus_action.triggered.connect(lambda: self.search_panel.focus_search())
        edit_menu.addAction(search_focus_action)

        tool_menu = menubar.addMenu("工具")

        attach_mgr_action = QAction("附件管理器", self)
        attach_mgr_action.triggered.connect(self._open_attachment_manager)
        tool_menu.addAction(attach_mgr_action)

        refresh_graph_action = QAction("刷新知识图谱", self)
        refresh_graph_action.triggered.connect(lambda: self.graph_widget.load_graph(self.db))
        tool_menu.addAction(refresh_graph_action)

        view_menu = menubar.addMenu("视图")
        view_menu.addAction(self.folder_dock.toggleViewAction())
        view_menu.addAction(self.tag_dock.toggleViewAction())
        view_menu.addAction(self.search_dock.toggleViewAction())
        view_menu.addAction(self.graph_dock.toggleViewAction())
        view_menu.addAction(self.literature_dock.toggleViewAction())

    def _connect_signals(self):
        self.note_list.noteSelected.connect(self.load_note)
        self.note_list.noteCreated.connect(self.load_note)
        self.note_list.noteDeleted.connect(self._on_note_deleted)

        self.folder_tree.folder_selected.connect(self._on_folder_selected)
        self.folder_tree.folder_created.connect(lambda *_: self._refresh_all())
        self.folder_tree.folder_deleted.connect(lambda *_: self._refresh_all())
        self.folder_tree.folder_renamed.connect(lambda *_: self._refresh_all())

        self.tag_panel.tag_selected.connect(self._on_tag_selected)
        self.tag_panel.tag_created.connect(lambda *_: self._refresh_all())
        self.tag_panel.tag_deleted.connect(lambda *_: self._refresh_all())
        self.tag_panel.tag_renamed.connect(lambda *_: self._refresh_all())
        self.tag_panel.tags_recommended.connect(self._on_tags_recommended)

        self.tag_editor.tag_added.connect(lambda *_: self._refresh_all())
        self.tag_editor.tag_removed.connect(lambda *_: self._refresh_all())

        self.editor.saveRequested.connect(lambda *_: self.save_current_note())
        self.editor.noteLinkClicked.connect(self.load_note)
        self.editor.cursorPositionChangedEx.connect(self._on_cursor_moved)
        self.editor.editorModeChanged.connect(lambda m: self.status_info.setText(f"模式: {m}"))
        self.editor.textChanged.connect(self._on_text_changed)

        self.title_input.textEdited.connect(self._on_title_changed)

        self.search_panel.noteSelected.connect(self.load_note)

        self.graph_widget.noteDoubleClicked.connect(self.load_note)

    def _toggle_editor_mode(self):
        if self.editor.editor_mode == EditorMode.MARKDOWN:
            self.editor.set_editor_mode(EditorMode.WYSIWYG)
        else:
            self.editor.set_editor_mode(EditorMode.MARKDOWN)

    def _insert_image_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "选择图片", "",
            "图片文件 (*.png *.jpg *.jpeg *.gif *.bmp *.webp)"
        )
        if path:
            handler = ImageHandler(self.config, self.db)
            attachment = handler.handle_file(path, self.current_note_id)
            if attachment and self.current_note_id:
                self.attachment_list.load_attachments(self.current_note_id)
                self.editor.insert_image_tag(attachment["file_path"])

    def _insert_formula(self):
        from PyQt6.QtWidgets import QInputDialog
        latex, ok = QInputDialog.getMultiLineText(
            self, "插入数学公式", "输入 LaTeX 公式 (不含 $):", ""
        )
        if ok and latex.strip():
            renderer = KaTeXRenderer(self.config)
            renderer.insert_formula_into_cursor(
                self.editor.textCursor(), latex, inline=True
            )

    def load_note(self, note_id: int):
        if self._dirty and self.current_note_id:
            self.save_current_note()

        note = self.db.get_note(note_id)
        if not note:
            return
        self.current_note_id = note_id
        self.title_input.blockSignals(True)
        self.title_input.setText(note["title"] or "")
        self.title_input.blockSignals(False)
        self.editor.set_content(note.get("markdown_content", "") or note.get("content", ""))
        self.tag_editor.set_note(note_id)
        self.attachment_list.load_attachments(note_id)
        self._dirty = False
        self.config.last_opened_note = note_id
        self.config.save()
        self._update_recommendations()
        self.statusBar.showMessage(f"已加载笔记: {note['title']}", 3000)

    def save_current_note(self):
        if not self.current_note_id:
            return
        title = self.title_input.text().strip() or "Untitled"
        md_content = self.editor.get_content_markdown()
        html_content = self.editor.get_content_html()
        self.db.update_note(
            self.current_note_id,
            title=title,
            content=html_content,
            markdown_content=md_content,
        )
        self._parse_and_update_references(md_content)
        self._dirty = False
        self.statusBar.showMessage(
            f"已保存 - {datetime.now().strftime('%H:%M:%S')}", 2000
        )
        self.note_list.reload()
        self.tag_panel.refresh_recommendations(md_content, self.current_note_id)

    def auto_save(self):
        if self._dirty and self.current_note_id:
            self.save_current_note()

    def _parse_and_update_references(self, md_content: str):
        if not self.current_note_id:
            return
        import re
        pattern = re.compile(r"\[nid:(\d+)\]|note://(\d+)")
        found_ids = set()
        for m in pattern.finditer(md_content):
            nid = int(m.group(1) or m.group(2))
            if nid and nid != self.current_note_id:
                found_ids.add(nid)
        existing = {r[1] for r in self.db.get_all_references() if r[0] == self.current_note_id}
        for nid in found_ids - existing:
            self.db.add_reference(self.current_note_id, nid)
        for nid in existing - found_ids:
            self.db.remove_reference(self.current_note_id, nid)

    def _on_text_changed(self):
        self._dirty = True

    def _on_title_changed(self, _text):
        self._dirty = True

    def _on_cursor_moved(self, line: int, col: int):
        self.status_info.setText(f"行 {line}, 列 {col}")

    def _on_folder_selected(self, folder_id: int):
        self.note_list.set_folder_filter(folder_id)

    def _on_tag_selected(self, tag_id: int, selected: bool):
        if selected:
            if tag_id not in self.note_list.current_tag_ids:
                self.note_list.current_tag_ids.append(tag_id)
        else:
            if tag_id in self.note_list.current_tag_ids:
                self.note_list.current_tag_ids.remove(tag_id)
        self.note_list.reload()

    def _on_tags_recommended(self, tag_ids: list):
        pass

    def _update_recommendations(self):
        if self.current_note_id:
            note = self.db.get_note(self.current_note_id)
            if note:
                self.tag_panel.refresh_recommendations(
                    note.get("markdown_content", ""), self.current_note_id
                )

    def _on_note_deleted(self, note_id: int):
        if self.current_note_id == note_id:
            self.current_note_id = None
            self.title_input.clear()
            self.editor.set_content("")
            self.tag_editor.set_note(None)
            self.attachment_list.load_attachments(None)
            self._dirty = False
        self.graph_widget.load_graph(self.db)
        self.search_panel.reload_filters()

    def _refresh_all(self):
        self.note_list.reload()
        self.tag_panel.load_tags()
        self.search_panel.reload_filters()
        self.graph_widget.load_graph(self.db)

    def _show_import_dialog(self):
        dlg = ImportDialog(self.db, self.config, self)
        if dlg.exec() == QDialog.DialogCode.Accepted:
            self._refresh_all()

    def _show_export_dialog(self):
        dlg = ExportDialog(self.db, self.config, self, self.current_note_id)
        dlg.exec()

    def _backup_database(self):
        from app.io.backup_manager import export_backup
        path, _ = QFileDialog.getSaveFileName(
            self, "备份数据库", f"kv_backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}.zip",
            "ZIP 备份 (*.zip)"
        )
        if path:
            try:
                export_backup(self.db, self.config, path)
                QMessageBox.information(self, "备份成功", f"数据库已备份到:\n{path}")
            except Exception as e:
                QMessageBox.critical(self, "备份失败", str(e))

    def _open_attachment_manager(self):
        dlg = AttachmentManagerDialog(self.db, self.config, self)
        dlg.exec()

    def closeEvent(self, event):
        if self._dirty and self.current_note_id:
            reply = QMessageBox.question(
                self, "未保存的更改",
                "有笔记尚未保存，是否保存后退出？",
                QMessageBox.StandardButton.Save |
                QMessageBox.StandardButton.Discard |
                QMessageBox.StandardButton.Cancel,
                QMessageBox.StandardButton.Save
            )
            if reply == QMessageBox.StandardButton.Save:
                self.save_current_note()
            elif reply == QMessageBox.StandardButton.Cancel:
                event.ignore()
                return
        self.auto_save()
        self._auto_save_timer.stop()
        self.config.save()
        self.db.close()
        super().closeEvent(event)
