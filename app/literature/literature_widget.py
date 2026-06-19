import os
import hashlib
import shutil
from pathlib import Path
from typing import List, Dict, Optional

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLineEdit, QToolBar, QPushButton,
    QTableView, QHeaderView, QMenu, QFileDialog, QMessageBox, QAbstractItemView,
    QApplication, QLabel, QFrame
)
from PyQt6.QtCore import (
    Qt, pyqtSignal, QAbstractTableModel, QModelIndex, QSortFilterProxyModel,
    QMimeData, QUrl
)
from PyQt6.QtGui import QAction, QKeySequence, QDesktopServices, QDrag

import fitz
import bibtexparser
import requests

from app.database import Database
from app.config import Config
from app.literature.pdf_extractor import extract_metadata, extract_first_page_image
from app.literature.crossref_client import fetch_by_doi
from app.literature.bibtex_manager import (
    literature_to_bibtex, parse_bibtex, export_all_bibtex, _generate_bibtex_key
)


COLUMNS = ["authors", "year", "journal", "title", "doi"]
COLUMN_HEADERS = {
    "authors": "作者",
    "year": "年份",
    "journal": "期刊",
    "title": "标题",
    "doi": "DOI",
}


class LiteratureTableModel(QAbstractTableModel):
    def __init__(self, data: Optional[List[Dict]] = None, parent=None):
        super().__init__(parent)
        self._data: List[Dict] = data or []

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return len(self._data)

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return len(COLUMNS)

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or role not in (Qt.ItemDataRole.DisplayRole, Qt.ItemDataRole.ToolTipRole, Qt.ItemDataRole.UserRole):
            return None
        row = index.row()
        col = index.column()
        if row < 0 or row >= len(self._data):
            return None
        item = self._data[row]
        field = COLUMNS[col]
        value = item.get(field)
        if role == Qt.ItemDataRole.UserRole:
            return item
        if role == Qt.ItemDataRole.ToolTipRole:
            if value:
                return str(value)
            return ""
        if value is None:
            return ""
        if field == "year":
            return str(value)
        display = str(value)
        if field == "authors" and len(display) > 60:
            display = display[:57] + "..."
        if field == "title" and len(display) > 100:
            display = display[:97] + "..."
        if field == "journal" and len(display) > 50:
            display = display[:47] + "..."
        return display

    def headerData(self, section: int, orientation: Qt.Orientation, role: int = Qt.ItemDataRole.DisplayRole):
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            return COLUMN_HEADERS.get(COLUMNS[section], COLUMNS[section])
        return None

    def set_data(self, data: List[Dict]):
        self.beginResetModel()
        self._data = list(data)
        self.endResetModel()

    def get_item(self, row: int) -> Optional[Dict]:
        if 0 <= row < len(self._data):
            return self._data[row]
        return None

    def remove_row(self, row: int):
        if 0 <= row < len(self._data):
            self.beginRemoveRows(QModelIndex(), row, row)
            del self._data[row]
            self.endRemoveRows()


class LiteratureSortFilterProxyModel(QSortFilterProxyModel):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFilterCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.setSortCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.setDynamicSortFilter(True)

    def filterAcceptsRow(self, source_row: int, source_parent: QModelIndex) -> bool:
        filter_text = self.filterRegularExpression().pattern()
        if not filter_text:
            return True
        model = self.sourceModel()
        if not isinstance(model, LiteratureTableModel):
            return True
        item = model.get_item(source_row)
        if not item:
            return False
        text = " ".join(str(item.get(f, "") or "") for f in COLUMNS).lower()
        return filter_text.lower() in text

    def lessThan(self, left: QModelIndex, right: QModelIndex) -> bool:
        model = self.sourceModel()
        if not isinstance(model, LiteratureTableModel):
            return super().lessThan(left, right)
        left_item = model.get_item(left.row())
        right_item = model.get_item(right.row())
        if not left_item or not right_item:
            return super().lessThan(left, right)
        field = COLUMNS[left.column()]
        lv = left_item.get(field)
        rv = right_item.get(field)
        if field == "year":
            lv = lv or 0
            rv = rv or 0
            try:
                return int(lv) < int(rv)
            except (ValueError, TypeError):
                pass
        lv = str(lv or "").lower()
        rv = str(rv or "").lower()
        return lv < rv


class LiteratureTableView(QTableView):
    literatureDoubleClicked = pyqtSignal(dict)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.setAlternatingRowColors(True)
        self.setSortingEnabled(True)
        self.setDragEnabled(True)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.verticalHeader().setVisible(False)
        self.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        self.horizontalHeader().setStretchLastSection(True)
        self.doubleClicked.connect(self._on_double_clicked)

    def _on_double_clicked(self, index: QModelIndex):
        proxy = self.model()
        if not isinstance(proxy, LiteratureSortFilterProxyModel):
            return
        source_index = proxy.mapToSource(index)
        model = proxy.sourceModel()
        if isinstance(model, LiteratureTableModel):
            item = model.get_item(source_index.row())
            if item:
                self.literatureDoubleClicked.emit(item)

    def startDrag(self, supportedActions):
        indexes = self.selectionModel().selectedRows()
        if not indexes:
            return
        index = indexes[0]
        proxy = self.model()
        if not isinstance(proxy, LiteratureSortFilterProxyModel):
            return
        source_index = proxy.mapToSource(index)
        model = proxy.sourceModel()
        if not isinstance(model, LiteratureTableModel):
            return
        item = model.get_item(source_index.row())
        if not item:
            return
        bibtex = literature_to_bibtex(item)
        mime_data = QMimeData()
        mime_data.setText(bibtex)
        drag = QDrag(self)
        drag.setMimeData(mime_data)
        drag.exec(Qt.DropAction.CopyAction)


class LiteratureWidget(QWidget):
    noteCreated = pyqtSignal(int)
    literatureUpdated = pyqtSignal()

    def __init__(self, db: Database, config: Config, parent=None):
        super().__init__(parent)
        self.db = db
        self.config = config
        self.config.ensure_directories()
        self._setup_ui()
        self.reload()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        top_bar = QHBoxLayout()
        top_bar.setContentsMargins(8, 8, 8, 4)
        top_bar.setSpacing(6)

        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("搜索文献（标题/作者/期刊/DOI）...")
        self.search_input.textChanged.connect(self._on_search_changed)
        top_bar.addWidget(self.search_input, 1)

        layout.addLayout(top_bar)

        toolbar = QToolBar()
        toolbar.setMovable(False)

        self.import_pdf_btn = QPushButton("导入PDF")
        self.import_pdf_btn.setToolTip("导入PDF文件（可多选）")
        self.import_pdf_btn.clicked.connect(self._on_import_pdf)
        toolbar.addWidget(self.import_pdf_btn)

        self.import_bib_btn = QPushButton("导入BibTeX")
        self.import_bib_btn.setToolTip("从.bib文件导入文献")
        self.import_bib_btn.clicked.connect(self._on_import_bibtex)
        toolbar.addWidget(self.import_bib_btn)

        self.export_bib_btn = QPushButton("导出BibTeX")
        self.export_bib_btn.setToolTip("导出全部文献为.bib文件")
        self.export_bib_btn.clicked.connect(self._on_export_bibtex)
        toolbar.addWidget(self.export_bib_btn)

        sep = QFrame()
        sep.setFrameShape(QFrame.Shape.VLine)
        sep.setFrameShadow(QFrame.Shadow.Sunken)
        toolbar.addWidget(sep)

        self.fetch_doi_btn = QPushButton("DOI补全")
        self.fetch_doi_btn.setToolTip("通过DOI从CrossRef补全元数据")
        self.fetch_doi_btn.clicked.connect(self._on_fetch_doi)
        toolbar.addWidget(self.fetch_doi_btn)

        self.delete_btn = QPushButton("删除")
        self.delete_btn.setToolTip("删除选中的文献")
        self.delete_btn.clicked.connect(self._on_delete)
        toolbar.addWidget(self.delete_btn)

        layout.addWidget(toolbar)

        self.table_model = LiteratureTableModel()
        self.proxy_model = LiteratureSortFilterProxyModel()
        self.proxy_model.setSourceModel(self.table_model)

        self.table_view = LiteratureTableView()
        self.table_view.setModel(self.proxy_model)
        self.table_view.setColumnWidth(0, 200)
        self.table_view.setColumnWidth(1, 60)
        self.table_view.setColumnWidth(2, 180)
        self.table_view.setColumnWidth(3, 300)
        self.table_view.setColumnWidth(4, 200)
        self.table_view.sortByColumn(1, Qt.SortOrder.DescendingOrder)
        self.table_view.customContextMenuRequested.connect(self._on_context_menu)
        self.table_view.literatureDoubleClicked.connect(self._on_literature_double_clicked)
        layout.addWidget(self.table_view, 1)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet("color: gray; font-size: 11px; padding: 4px 8px;")
        layout.addWidget(self.status_label)

    def reload(self):
        literature_list = self.db.list_literature()
        self.table_model.set_data(literature_list)
        self.status_label.setText(f"共 {len(literature_list)} 篇文献")

    def _on_search_changed(self, text: str):
        self.proxy_model.setFilterFixedString(text.strip())
        count = self.proxy_model.rowCount()
        self.status_label.setText(f"显示 {count} / {self.table_model.rowCount()} 篇文献")

    def _get_selected_literature(self) -> Optional[Dict]:
        proxy = self.table_view.model()
        if not isinstance(proxy, LiteratureSortFilterProxyModel):
            return None
        indexes = self.table_view.selectionModel().selectedRows()
        if not indexes:
            return None
        source_index = proxy.mapToSource(indexes[0])
        model = proxy.sourceModel()
        if isinstance(model, LiteratureTableModel):
            return model.get_item(source_index.row())
        return None

    def _md5_file(self, file_path: str) -> str:
        hash_md5 = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                hash_md5.update(chunk)
        return hash_md5.hexdigest()

    def _on_import_pdf(self):
        files, _ = QFileDialog.getOpenFileNames(
            self,
            "选择PDF文件",
            "",
            "PDF文件 (*.pdf);;所有文件 (*.*)"
        )
        if not files:
            return
        imported = 0
        for pdf_path in files:
            try:
                file_name = os.path.basename(pdf_path)
                file_size = os.path.getsize(pdf_path)
                md5 = self._md5_file(pdf_path)

                existing = self.db.get_attachment_by_md5(md5)
                if existing:
                    attachment_id = existing["id"]
                else:
                    dest_path = Path(self.config.attachments_dir) / f"{md5}{Path(pdf_path).suffix}"
                    shutil.copy2(pdf_path, str(dest_path))

                    thumb_name = f"{md5}.png"
                    thumb_path = Path(self.config.thumbnails_dir) / thumb_name
                    thumb_ok = extract_first_page_image(pdf_path, str(thumb_path))

                    attachment_id = self.db.create_attachment(
                        md5_hash=md5,
                        file_name=file_name,
                        file_path=str(dest_path),
                        file_size=file_size,
                        mime_type="application/pdf",
                        thumbnail_path=str(thumb_path) if thumb_ok else "",
                    )

                meta = extract_metadata(pdf_path)
                bibtex_key = _generate_bibtex_key(meta)

                self.db.create_literature(
                    title=meta.get("title"),
                    authors=meta.get("authors"),
                    abstract=meta.get("abstract"),
                    doi=meta.get("doi"),
                    attachment_id=attachment_id,
                    bibtex_key=bibtex_key,
                )
                imported += 1
            except Exception as e:
                QMessageBox.warning(self, "导入失败", f"导入 {os.path.basename(pdf_path)} 失败: {str(e)}")

        if imported > 0:
            self.reload()
            self.literatureUpdated.emit()
            QMessageBox.information(self, "导入完成", f"成功导入 {imported} 篇文献")

    def _on_import_bibtex(self):
        file_path, _ = QFileDialog.getOpenFileName(
            self,
            "选择BibTeX文件",
            "",
            "BibTeX文件 (*.bib);;所有文件 (*.*)"
        )
        if not file_path:
            return
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            QMessageBox.warning(self, "读取失败", f"无法读取文件: {str(e)}")
            return

        entries = parse_bibtex(content)
        if not entries:
            QMessageBox.warning(self, "导入失败", "未解析到有效的BibTeX条目")
            return

        imported = 0
        for entry in entries:
            try:
                if entry.get("doi"):
                    existing = self.db.find_literature_by_doi(entry["doi"])
                    if existing:
                        continue
                self.db.create_literature(
                    title=entry.get("title"),
                    authors=entry.get("authors"),
                    abstract=entry.get("abstract"),
                    journal=entry.get("journal"),
                    year=entry.get("year"),
                    volume=entry.get("volume"),
                    issue=entry.get("issue"),
                    pages=entry.get("pages"),
                    doi=entry.get("doi"),
                    bibtex_key=entry.get("bibtex_key") or _generate_bibtex_key(entry),
                )
                imported += 1
            except Exception:
                pass

        self.reload()
        self.literatureUpdated.emit()
        QMessageBox.information(self, "导入完成", f"成功导入 {imported} 篇文献（跳过 {len(entries) - imported} 篇重复）")

    def _on_export_bibtex(self):
        file_path, _ = QFileDialog.getSaveFileName(
            self,
            "导出BibTeX",
            "literature.bib",
            "BibTeX文件 (*.bib)"
        )
        if not file_path:
            return
        if export_all_bibtex(self.db, file_path):
            QMessageBox.information(self, "导出成功", f"已导出到: {file_path}")
        else:
            QMessageBox.warning(self, "导出失败", "导出BibTeX失败")

    def _on_fetch_doi(self):
        lit = self._get_selected_literature()
        if not lit:
            QMessageBox.information(self, "提示", "请先选择一篇文献")
            return
        if not lit.get("doi"):
            QMessageBox.information(self, "提示", "选中的文献没有DOI信息")
            return
        remote = fetch_by_doi(lit["doi"])
        if not remote:
            QMessageBox.warning(self, "失败", f"无法从CrossRef获取DOI {lit['doi']} 的信息")
            return
        update_fields = {}
        for field in ["title", "authors", "abstract", "journal", "year", "volume", "issue", "pages"]:
            if remote.get(field) and not lit.get(field):
                update_fields[field] = remote[field]
        if not update_fields:
            QMessageBox.information(self, "提示", "元数据已是完整的，无需补充")
            return
        if not lit.get("bibtex_key"):
            merged = {**lit, **update_fields}
            update_fields["bibtex_key"] = _generate_bibtex_key(merged)
        self.db.update_literature(lit["id"], **update_fields)
        self.reload()
        self.literatureUpdated.emit()
        QMessageBox.information(self, "完成", f"已补充 {len(update_fields)} 个字段")

    def _on_delete(self):
        lit = self._get_selected_literature()
        if not lit:
            QMessageBox.information(self, "提示", "请先选择一篇文献")
            return
        reply = QMessageBox.question(
            self,
            "确认删除",
            f"确定要删除文献《{lit.get('title', 'Untitled')}》吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if reply != QMessageBox.StandardButton.Yes:
            return
        self.db.delete_literature(lit["id"])
        self.reload()
        self.literatureUpdated.emit()

    def _on_context_menu(self, pos):
        lit = self._get_selected_literature()
        if not lit:
            return
        menu = QMenu(self)

        create_note_action = QAction("创建关联笔记", self)
        create_note_action.triggered.connect(lambda: self._on_create_note(lit))
        menu.addAction(create_note_action)

        copy_bibtex_action = QAction("复制引用(BibTeX)", self)
        copy_bibtex_action.setShortcut(QKeySequence("Ctrl+C"))
        copy_bibtex_action.triggered.connect(lambda: self._on_copy_bibtex(lit))
        menu.addAction(copy_bibtex_action)

        menu.addSeparator()

        open_pdf_action = QAction("打开PDF", self)
        open_pdf_action.setEnabled(bool(lit.get("attachment_id")))
        open_pdf_action.triggered.connect(lambda: self._on_open_pdf(lit))
        menu.addAction(open_pdf_action)

        open_doi_action = QAction("在浏览器打开DOI", self)
        open_doi_action.setEnabled(bool(lit.get("doi")))
        open_doi_action.triggered.connect(lambda: self._on_open_doi(lit))
        menu.addAction(open_doi_action)

        menu.exec(self.table_view.viewport().mapToGlobal(pos))

    def _on_create_note(self, lit: Dict):
        title = lit.get("title") or "关联文献笔记"
        content_parts = []
        if lit.get("authors"):
            content_parts.append(f"**作者**: {lit['authors']}")
        if lit.get("journal"):
            journal_info = lit["journal"]
            if lit.get("year"):
                journal_info += f", {lit['year']}"
            if lit.get("volume"):
                journal_info += f", {lit['volume']}"
            if lit.get("issue"):
                journal_info += f"({lit['issue']})"
            if lit.get("pages"):
                journal_info += f": {lit['pages']}"
            content_parts.append(f"**期刊**: {journal_info}")
        if lit.get("doi"):
            content_parts.append(f"**DOI**: {lit['doi']}")
        if lit.get("abstract"):
            content_parts.append(f"\n## 摘要\n\n{lit['abstract']}")

        note_id = self.db.create_note(
            title=title,
            content="\n\n".join(content_parts),
            markdown_content="\n\n".join(content_parts),
        )
        self.db.update_literature(lit["id"], note_id=note_id)
        self.reload()
        self.literatureUpdated.emit()
        self.noteCreated.emit(note_id)

    def _on_copy_bibtex(self, lit: Dict):
        bibtex = literature_to_bibtex(lit)
        QApplication.clipboard().setText(bibtex)

    def _on_open_pdf(self, lit: Dict):
        if not lit.get("attachment_id"):
            return
        attachment = self.db.get_attachment(lit["attachment_id"])
        if not attachment:
            return
        file_path = attachment.get("file_path")
        if file_path and os.path.exists(file_path):
            QDesktopServices.openUrl(QUrl.fromLocalFile(file_path))

    def _on_open_doi(self, lit: Dict):
        if not lit.get("doi"):
            return
        url = f"https://doi.org/{lit['doi']}"
        QDesktopServices.openUrl(QUrl(url))

    def _on_literature_double_clicked(self, lit: Dict):
        if lit.get("note_id"):
            self.noteCreated.emit(lit["note_id"])
        elif lit.get("attachment_id"):
            self._on_open_pdf(lit)
