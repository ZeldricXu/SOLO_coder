import os
from pathlib import Path
from typing import Optional, List

from PyQt6.QtCore import Qt, QSize, pyqtSignal
from PyQt6.QtGui import QDragEnterEvent, QDropEvent, QFont
from PyQt6.QtWidgets import (
    QDialog,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QComboBox,
    QPushButton,
    QFileDialog,
    QLineEdit,
    QCheckBox,
    QGroupBox,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QProgressDialog,
    QSpinBox,
    QFrame,
)

from app.database import Database
from app.config import Config


EXPORT_TYPES = [
    ("markdown", "单篇 Markdown (.md)", ".md"),
    ("html", "单篇 HTML (.html)", ".html"),
    ("pdf", "单篇 PDF (.pdf)", ".pdf"),
    ("opml", "整库 OPML 大纲 (.opml)", ".opml"),
    ("backup", "整库 ZIP 备份 (.zip)", ".zip"),
]


IMPORT_EXT_FILTERS = {
    ".md": "Markdown",
    ".markdown": "Markdown",
    ".html": "HTML",
    ".htm": "HTML",
    ".opml": "OPML",
    ".xml": "OPML",
    ".zip": "ZIP 备份",
}


class ExportDialog(QDialog):

    exported = pyqtSignal(str)

    def __init__(
        self,
        db: Database,
        config: Config,
        parent: Optional[QWidget] = None,
        default_note_id: Optional[int] = None,
    ):
        super().__init__(parent)
        self.db = db
        self.config = config
        self.default_note_id = default_note_id
        self._build_ui()

    def _build_ui(self):
        self.setWindowTitle("导出")
        self.setMinimumSize(520, 420)

        layout = QVBoxLayout(self)

        type_box = QGroupBox("导出类型")
        type_layout = QVBoxLayout(type_box)

        self.type_combo = QComboBox()
        for key, label, _ in EXPORT_TYPES:
            self.type_combo.addItem(label, key)
        self.type_combo.currentIndexChanged.connect(self._on_type_changed)
        type_layout.addWidget(self.type_combo)

        layout.addWidget(type_box)

        options_box = QGroupBox("选项")
        options_layout = QVBoxLayout(options_box)

        note_row = QHBoxLayout()
        note_row.addWidget(QLabel("笔记:"))
        self.note_combo = QComboBox()
        self._populate_notes()
        note_row.addWidget(self.note_combo, 1)
        options_layout.addLayout(note_row)

        self.standalone_check = QCheckBox("生成自包含 HTML (内联 CSS + 图片 base64)")
        self.standalone_check.setChecked(True)
        options_layout.addWidget(self.standalone_check)

        self.include_attachments_check = QCheckBox("包含附件")
        self.include_attachments_check.setChecked(True)
        options_layout.addWidget(self.include_attachments_check)

        layout.addWidget(options_box)

        path_box = QGroupBox("目标路径")
        path_layout = QVBoxLayout(path_box)

        path_row = QHBoxLayout()
        self.path_edit = QLineEdit()
        self.path_edit.setPlaceholderText("选择导出文件或目录...")
        path_row.addWidget(self.path_edit, 1)
        browse_btn = QPushButton("浏览...")
        browse_btn.clicked.connect(self._browse_path)
        path_row.addWidget(browse_btn)
        path_layout.addLayout(path_row)

        layout.addWidget(path_box)

        layout.addStretch(1)

        sep = QFrame()
        sep.setFrameShape(QFrame.Shape.HLine)
        sep.setFrameShadow(QFrame.Shadow.Sunken)
        layout.addWidget(sep)

        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        cancel_btn = QPushButton("取消")
        cancel_btn.clicked.connect(self.reject)
        btn_row.addWidget(cancel_btn)
        export_btn = QPushButton("导出")
        export_btn.setDefault(True)
        export_btn.clicked.connect(self._do_export)
        btn_row.addWidget(export_btn)
        layout.addLayout(btn_row)

        self._on_type_changed()

    def _populate_notes(self):
        notes = self.db.list_recent_notes(limit=200)
        self.note_combo.clear()
        for n in notes:
            self.note_combo.addItem(f"{n['title']} (#{n['id']})", n["id"])
        if self.default_note_id is not None:
            for i in range(self.note_combo.count()):
                if self.note_combo.itemData(i) == self.default_note_id:
                    self.note_combo.setCurrentIndex(i)
                    break

    def _current_type(self) -> str:
        return self.type_combo.currentData()

    def _current_note_id(self) -> Optional[int]:
        return self.note_combo.currentData()

    def _on_type_changed(self):
        t = self._current_type()
        is_single = t in ("markdown", "html", "pdf")
        self.note_combo.setEnabled(is_single)
        self.standalone_check.setVisible(t == "html")
        self.include_attachments_check.setVisible(t == "backup")

    def _default_ext(self) -> str:
        t = self._current_type()
        for key, _, ext in EXPORT_TYPES:
            if key == t:
                return ext
        return ""

    def _browse_path(self):
        t = self._current_type()
        ext = self._default_ext()
        config_exports = self.config.exports_dir

        if t == "markdown":
            path, _ = QFileDialog.getSaveFileName(
                self, "选择导出目录", config_exports,
                f"Markdown 文件 (*{ext})",
            )
        elif t in ("html", "pdf", "opml", "backup"):
            type_name_map = {
                "html": "HTML 文件",
                "pdf": "PDF 文件",
                "opml": "OPML 文件",
                "backup": "ZIP 备份",
            }
            path, _ = QFileDialog.getSaveFileName(
                self, f"选择导出{type_name_map[t]}路径",
                f"{config_exports}/export{ext}",
                f"{type_name_map[t]} (*{ext})",
            )
        else:
            path, _ = QFileDialog.getSaveFileName(self, "选择导出路径", config_exports)

        if path:
            self.path_edit.setText(path)

    def _do_export(self):
        from app.io.markdown_io import export_note_to_markdown
        from app.io.html_io import export_note_to_html
        from app.io.pdf_io import export_note_to_pdf
        from app.io.opml_io import export_opml
        from app.io.backup_manager import export_backup

        t = self._current_type()
        out_path = self.path_edit.text().strip()

        if not out_path:
            QMessageBox.warning(self, "提示", "请选择目标路径")
            return

        note_id = self._current_note_id()
        if t in ("markdown", "html", "pdf") and not note_id:
            QMessageBox.warning(self, "提示", "请选择要导出的笔记")
            return

        progress = QProgressDialog("正在导出...", None, 0, 0, self)
        progress.setWindowModality(Qt.WindowModality.WindowModal)
        progress.show()

        try:
            ok = False
            if t == "markdown":
                out_dir = str(Path(out_path).parent) if not Path(out_path).is_dir() else out_path
                result = export_note_to_markdown(self.db, note_id, out_dir)
                ok = bool(result)
                out_path = result
            elif t == "html":
                standalone = self.standalone_check.isChecked()
                ok = export_note_to_html(self.db, note_id, out_path, standalone=standalone)
            elif t == "pdf":
                ok = export_note_to_pdf(self.db, note_id, out_path)
            elif t == "opml":
                ok = export_opml(self.db, out_path)
            elif t == "backup":
                ok = export_backup(self.db, self.config, out_path)

            progress.close()

            if ok:
                QMessageBox.information(self, "成功", f"导出成功:\n{out_path}")
                self.exported.emit(out_path)
                self.accept()
            else:
                QMessageBox.critical(self, "失败", "导出失败，请检查权限或路径。")

        except Exception as e:
            progress.close()
            QMessageBox.critical(self, "错误", f"导出出错: {e}")


class _DropArea(QFrame):

    filesDropped = pyqtSignal(list)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAcceptDrops(True)
        self.setFrameShape(QFrame.Shape.StyledPanel)
        self.setMinimumHeight(90)
        self.setStyleSheet(
            "QFrame { border: 2px dashed #aaa; border-radius: 8px; background: #fafafa; }"
        )
        lay = QVBoxLayout(self)
        lay.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl = QLabel("将文件拖放到此处，或点击下方按钮选择")
        lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl.setStyleSheet("color: #666;")
        lay.addWidget(lbl)
        hint = QLabel("支持 .md .markdown .html .htm .opml .xml .zip")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hint.setStyleSheet("color: #999; font-size: 11px;")
        lay.addWidget(hint)

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event: QDropEvent):
        files = []
        for url in event.mimeData().urls():
            p = url.toLocalFile()
            if p:
                files.append(p)
        if files:
            self.filesDropped.emit(files)
            event.acceptProposedAction()


class ImportDialog(QDialog):

    imported = pyqtSignal(list)

    def __init__(
        self,
        db: Database,
        config: Config,
        parent: Optional[QWidget] = None,
        default_folder_id: Optional[int] = None,
    ):
        super().__init__(parent)
        self.db = db
        self.config = config
        self.default_folder_id = default_folder_id
        self._pending_files: List[str] = []
        self._preview = {"notes": 0, "tags": 0, "folders": 0}
        self._build_ui()

    def _build_ui(self):
        self.setWindowTitle("导入")
        self.setMinimumSize(560, 520)

        layout = QVBoxLayout(self)

        drop = _DropArea(self)
        drop.filesDropped.connect(self._add_files)
        layout.addWidget(drop)

        choose_btn = QPushButton("选择文件...")
        choose_btn.clicked.connect(self._choose_files)
        layout.addWidget(choose_btn)

        files_box = QGroupBox("待导入文件")
        files_layout = QVBoxLayout(files_box)
        self.files_list = QListWidget()
        self.files_list.setSelectionMode(QListWidget.SelectionMode.ExtendedSelection)
        files_layout.addWidget(self.files_list)

        rm_row = QHBoxLayout()
        rm_row.addStretch(1)
        rm_btn = QPushButton("移除选中")
        rm_btn.clicked.connect(self._remove_selected)
        rm_row.addWidget(rm_btn)
        clear_btn = QPushButton("清空")
        clear_btn.clicked.connect(lambda: (self.files_list.clear(), self._pending_files.clear(), self._refresh_preview()))
        rm_row.addWidget(clear_btn)
        files_layout.addLayout(rm_row)

        layout.addWidget(files_box, 1)

        opts_box = QGroupBox("导入选项")
        opts_layout = QVBoxLayout(opts_box)

        folder_row = QHBoxLayout()
        folder_row.addWidget(QLabel("导入到目录:"))
        self.folder_combo = QComboBox()
        self._populate_folders()
        folder_row.addWidget(self.folder_combo, 1)
        opts_layout.addLayout(folder_row)

        self.restore_attachments_check = QCheckBox("恢复附件 (ZIP 备份)")
        self.restore_attachments_check.setChecked(True)
        opts_layout.addWidget(self.restore_attachments_check)

        layout.addWidget(opts_box)

        preview_box = QGroupBox("导入预览")
        preview_layout = QHBoxLayout(preview_box)
        self.preview_notes = QLabel("笔记: 0")
        self.preview_tags = QLabel("标签: 0")
        self.preview_folders = QLabel("目录: 0")
        for w in (self.preview_notes, self.preview_tags, self.preview_folders):
            w.setStyleSheet("font-weight: bold; color: #1967d2; padding: 4px 8px;")
            preview_layout.addWidget(w)
        preview_layout.addStretch(1)
        layout.addWidget(preview_box)

        sep = QFrame()
        sep.setFrameShape(QFrame.Shape.HLine)
        sep.setFrameShadow(QFrame.Shadow.Sunken)
        layout.addWidget(sep)

        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        cancel_btn = QPushButton("取消")
        cancel_btn.clicked.connect(self.reject)
        btn_row.addWidget(cancel_btn)
        import_btn = QPushButton("导入")
        import_btn.setDefault(True)
        import_btn.clicked.connect(self._do_import)
        btn_row.addWidget(import_btn)
        layout.addLayout(btn_row)

    def _populate_folders(self):
        self.folder_combo.clear()
        self.folder_combo.addItem("(根目录)", None)

        def _add(parent_id, depth=0):
            for f in self.db.list_folders(parent_id):
                prefix = "  " * depth
                self.folder_combo.addItem(f"{prefix}📁 {f['name']}", f["id"])
                _add(f["id"], depth + 1)

        _add(None)

        if self.default_folder_id is not None:
            for i in range(self.folder_combo.count()):
                if self.folder_combo.itemData(i) == self.default_folder_id:
                    self.folder_combo.setCurrentIndex(i)
                    break

    def _folder_id(self) -> Optional[int]:
        return self.folder_combo.currentData()

    def _add_files(self, paths: List[str]):
        for p in paths:
            ext = Path(p).suffix.lower()
            if ext in IMPORT_EXT_FILTERS and p not in self._pending_files:
                self._pending_files.append(p)
                item = QListWidgetItem(f"[{IMPORT_EXT_FILTERS.get(ext, '文件')}] {p}")
                self.files_list.addItem(item)
        self._refresh_preview()

    def _choose_files(self):
        filters = (
            "支持的文件 (*.md *.markdown *.html *.htm *.opml *.xml *.zip);;"
            "所有文件 (*.*)"
        )
        paths, _ = QFileDialog.getOpenFileNames(self, "选择要导入的文件", "", filters)
        if paths:
            self._add_files(paths)

    def _remove_selected(self):
        rows = sorted([self.files_list.row(i) for i in self.files_list.selectedItems()], reverse=True)
        for r in rows:
            self.files_list.takeItem(r)
            if r < len(self._pending_files):
                del self._pending_files[r]
        self._refresh_preview()

    def _refresh_preview(self):
        notes = 0
        tags = 0
        folders = 0
        import yaml
        import re
        from lxml import etree

        for p in self._pending_files:
            ext = Path(p).suffix.lower()
            try:
                if ext in (".md", ".markdown"):
                    notes += 1
                    try:
                        raw = Path(p).read_text(encoding="utf-8", errors="ignore")
                        if raw.startswith("---"):
                            parts = raw.split("---", 2)
                            if len(parts) >= 3:
                                fm = yaml.safe_load(parts[1]) or {}
                                tgs = fm.get("tags", []) or []
                                if isinstance(tgs, str):
                                    tgs = [t.strip() for t in tgs.split(",") if t.strip()]
                                tags += len(tgs)
                    except Exception:
                        pass
                elif ext in (".html", ".htm"):
                    notes += 1
                elif ext in (".opml", ".xml"):
                    try:
                        tree = etree.parse(p)
                        outlines = tree.findall(".//outline")
                        for o in outlines:
                            otype = o.get("type", "").lower()
                            if otype == "folder" or (len(list(o)) > 0 and not o.get("noteId")):
                                folders += 1
                            else:
                                notes += 1
                            tgs = o.get("tags", "")
                            if tgs:
                                tags += len([t for t in tgs.split(",") if t.strip()])
                    except Exception:
                        notes += 1
                elif ext == ".zip":
                    import zipfile
                    try:
                        with zipfile.ZipFile(p) as zf:
                            names = zf.namelist()
                            if "backup_manifest.json" in names:
                                notes += 1
                    except Exception:
                        pass
            except Exception:
                pass

        self._preview = {"notes": notes, "tags": tags, "folders": folders}
        self.preview_notes.setText(f"笔记: {notes}")
        self.preview_tags.setText(f"标签: {tags}")
        self.preview_folders.setText(f"目录: {folders}")

    def _do_import(self):
        from app.io.markdown_io import import_markdown_to_note
        from app.io.html_io import import_html_to_note
        from app.io.opml_io import import_opml
        from app.io.backup_manager import import_backup

        if not self._pending_files:
            QMessageBox.warning(self, "提示", "请先选择要导入的文件")
            return

        folder_id = self._folder_id()
        created_ids: List[int] = []

        progress = QProgressDialog("正在导入...", None, 0, len(self._pending_files), self)
        progress.setWindowModality(Qt.WindowModality.WindowModal)
        progress.show()

        try:
            for idx, path in enumerate(self._pending_files):
                progress.setLabelText(f"导入 ({idx + 1}/{len(self._pending_files)}): {Path(path).name}")
                progress.setValue(idx)
                ext = Path(path).suffix.lower()

                try:
                    if ext in (".md", ".markdown"):
                        nid = import_markdown_to_note(self.db, self.config, path, folder_id)
                        created_ids.append(nid)
                    elif ext in (".html", ".htm"):
                        nid = import_html_to_note(self.db, self.config, path, folder_id)
                        created_ids.append(nid)
                    elif ext in (".opml", ".xml"):
                        ids = import_opml(self.db, self.config, path, folder_id)
                        created_ids.extend(ids)
                    elif ext == ".zip":
                        restore_att = self.restore_attachments_check.isChecked()
                        ok = import_backup(self.db, self.config, path, restore_attachments=restore_att)
                        if ok:
                            QMessageBox.information(
                                self, "备份恢复",
                                "备份已恢复，建议重启应用以确保数据库重新加载。",
                            )
                            self.accept()
                            return
                        else:
                            raise RuntimeError("备份恢复失败")
                except Exception as e:
                    QMessageBox.warning(self, "警告", f"导入 {Path(path).name} 失败: {e}")

            progress.close()
            self.imported.emit(created_ids)
            QMessageBox.information(self, "完成", f"导入完成，共创建 {len(created_ids)} 条笔记。")
            self.accept()

        except Exception as e:
            progress.close()
            QMessageBox.critical(self, "错误", f"导入出错: {e}")
