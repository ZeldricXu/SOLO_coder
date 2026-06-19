from typing import Optional, List, Dict, Any
from PyQt6.QtWidgets import (
    QTreeView, QMenu, QInputDialog, QMessageBox, QAbstractItemView,
    QWidget, QVBoxLayout
)
from PyQt6.QtCore import (
    Qt, QAbstractItemModel, QModelIndex, QMimeData, QByteArray,
    pyqtSignal, QVariant
)
from PyQt6.QtGui import QAction, QDrag
from app.database import Database


class FolderNode:
    def __init__(self, data: Dict[str, Any], parent: Optional["FolderNode"] = None):
        self._data = data
        self._parent = parent
        self._children: List["FolderNode"] = []
        for child_data in data.get("children", []):
            self._children.append(FolderNode(child_data, self))

    def id(self) -> int:
        return self._data.get("id", 0)

    def data(self, column: int) -> Any:
        if column == 0:
            return self._data.get("name", "")
        return None

    def set_data(self, column: int, value: Any) -> bool:
        if column == 0:
            self._data["name"] = value
            return True
        return False

    def parent(self) -> Optional["FolderNode"]:
        return self._parent

    def child(self, row: int) -> Optional["FolderNode"]:
        if 0 <= row < len(self._children):
            return self._children[row]
        return None

    def child_count(self) -> int:
        return len(self._children)

    def row(self) -> int:
        if self._parent:
            return self._parent._children.index(self)
        return 0

    def column_count(self) -> int:
        return 1

    def add_child(self, child: "FolderNode"):
        child._parent = self
        self._children.append(child)

    def insert_child(self, row: int, child: "FolderNode"):
        child._parent = self
        self._children.insert(row, child)

    def remove_child(self, row: int) -> bool:
        if 0 <= row < len(self._children):
            removed = self._children.pop(row)
            removed._parent = None
            return True
        return False

    def to_dict(self) -> Dict[str, Any]:
        result = dict(self._data)
        result["children"] = [c.to_dict() for c in self._children]
        return result


class FolderTreeModel(QAbstractItemModel):
    folder_renamed = pyqtSignal(int, str)
    folder_created = pyqtSignal(int, int)
    folder_deleted = pyqtSignal(int)
    folders_reordered = pyqtSignal()

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self._root_nodes: List[FolderNode] = []
        self.reload()

    def reload(self):
        self.beginResetModel()
        tree = self._db.get_folder_tree()
        self._root_nodes = [FolderNode(item) for item in tree]
        self.endResetModel()

    def _get_node(self, index: QModelIndex) -> Optional[FolderNode]:
        if not index.isValid():
            return None
        return index.internalPointer()

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        if not parent.isValid():
            return len(self._root_nodes)
        node = self._get_node(parent)
        return node.child_count() if node else 0

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 1

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole) -> Any:
        if not index.isValid():
            return QVariant()
        node = self._get_node(index)
        if not node:
            return QVariant()
        if role in (Qt.ItemDataRole.DisplayRole, Qt.ItemDataRole.EditRole):
            return QVariant(node.data(index.column()))
        if role == Qt.ItemDataRole.UserRole:
            return QVariant(node.id())
        return QVariant()

    def setData(self, index: QModelIndex, value: Any, role: int = Qt.ItemDataRole.EditRole) -> bool:
        if not index.isValid() or role != Qt.ItemDataRole.EditRole:
            return False
        node = self._get_node(index)
        if not node:
            return False
        old_name = node.data(0)
        new_name = str(value).strip()
        if not new_name or new_name == old_name:
            return False
        if node.set_data(index.column(), new_name):
            self._db.update_folder(node.id(), name=new_name)
            self.dataChanged.emit(index, index, [Qt.ItemDataRole.DisplayRole, Qt.ItemDataRole.EditRole])
            self.folder_renamed.emit(node.id(), new_name)
            return True
        return False

    def flags(self, index: QModelIndex) -> Qt.ItemFlag:
        if not index.isValid():
            return Qt.ItemFlag.ItemIsDropEnabled | Qt.ItemFlag.ItemIsEnabled
        return (
            Qt.ItemFlag.ItemIsEnabled
            | Qt.ItemFlag.ItemIsSelectable
            | Qt.ItemFlag.ItemIsEditable
            | Qt.ItemFlag.ItemIsDragEnabled
            | Qt.ItemFlag.ItemIsDropEnabled
        )

    def headerData(self, section: int, orientation: Qt.Orientation, role: int = Qt.ItemDataRole.DisplayRole) -> Any:
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            if section == 0:
                return QVariant("Folders")
        return QVariant()

    def index(self, row: int, column: int, parent: QModelIndex = QModelIndex()) -> QModelIndex:
        if not self.hasIndex(row, column, parent):
            return QModelIndex()
        if not parent.isValid():
            if 0 <= row < len(self._root_nodes):
                return self.createIndex(row, column, self._root_nodes[row])
        else:
            parent_node = self._get_node(parent)
            if parent_node:
                child = parent_node.child(row)
                if child:
                    return self.createIndex(row, column, child)
        return QModelIndex()

    def parent(self, index: QModelIndex) -> QModelIndex:
        if not index.isValid():
            return QModelIndex()
        node = self._get_node(index)
        if not node:
            return QModelIndex()
        parent_node = node.parent()
        if not parent_node:
            return QModelIndex()
        return self.createIndex(parent_node.row(), 0, parent_node)

    def supportedDropActions(self) -> Qt.DropAction:
        return Qt.DropAction.MoveAction

    def supportedDragActions(self) -> Qt.DropAction:
        return Qt.DropAction.MoveAction

    def mimeTypes(self) -> List[str]:
        return ["application/x-folder-id"]

    def mimeData(self, indexes: List[QModelIndex]) -> QMimeData:
        mime_data = QMimeData()
        if indexes:
            index = indexes[0]
            node = self._get_node(index)
            if node:
                mime_data.setData("application/x-folder-id", QByteArray(str(node.id()).encode()))
        return mime_data

    def canDropMimeData(self, data: QMimeData, action: Qt.DropAction,
                        row: int, column: int, parent: QModelIndex) -> bool:
        if not data.hasFormat("application/x-folder-id"):
            return False
        dragged_id = int(bytes(data.data("application/x-folder-id")).decode())
        parent_node = self._get_node(parent) if parent.isValid() else None
        if parent_node and parent_node.id() == dragged_id:
            return False

        def _is_descendant(node: Optional[FolderNode], target_id: int) -> bool:
            while node:
                if node.id() == target_id:
                    return True
                node = node.parent()
            return False

        if parent_node and _is_descendant(parent_node, dragged_id):
            return False
        return True

    def dropMimeData(self, data: QMimeData, action: Qt.DropAction,
                     row: int, column: int, parent: QModelIndex) -> bool:
        if action != Qt.DropAction.MoveAction:
            return False
        if not data.hasFormat("application/x-folder-id"):
            return False
        dragged_id = int(bytes(data.data("application/x-folder-id")).decode())

        dragged_node = None
        dragged_parent = None

        def _find_node(nodes: List[FolderNode], target_id: int) -> tuple:
            for idx, n in enumerate(nodes):
                if n.id() == target_id:
                    return n, nodes, idx
                found = _find_node(n._children, target_id)
                if found[0]:
                    return found
            return None, None, -1

        dragged_node, dragged_siblings, dragged_row = _find_node(self._root_nodes, dragged_id)
        if not dragged_node:
            return False

        parent_node = self._get_node(parent) if parent.isValid() else None
        new_parent_id = parent_node.id() if parent_node else None

        if parent_node is None:
            target_children = self._root_nodes
        else:
            target_children = parent_node._children

        self.beginResetModel()
        dragged_siblings.pop(dragged_row)
        if row < 0 or row > len(target_children):
            row = len(target_children)
        dragged_node._parent = parent_node
        target_children.insert(row, dragged_node)
        self.endResetModel()

        def _collect_ids(nodes: List[FolderNode]) -> List[int]:
            return [n.id() for n in nodes]

        if parent_node is None:
            reordered_ids = _collect_ids(self._root_nodes)
            self._db.reorder_folders(reordered_ids, None)
        else:
            reordered_ids = _collect_ids(parent_node._children)
            self._db.reorder_folders(reordered_ids, new_parent_id)

        for child in (dragged_node._children if dragged_node else []):
            self._update_folder_parent_recursive(child, dragged_id)

        self.folders_reordered.emit()
        return True

    def _update_folder_parent_recursive(self, node: FolderNode, parent_id: int):
        self._db.update_folder(node.id(), parent_id=parent_id)
        for child in node._children:
            self._update_folder_parent_recursive(child, node.id())

    def create_child_folder(self, parent_index: QModelIndex) -> Optional[QModelIndex]:
        name, ok = QInputDialog.getText(None, "New Folder", "Folder name:")
        if not ok or not name.strip():
            return None
        name = name.strip()
        parent_node = self._get_node(parent_index) if parent_index.isValid() else None
        parent_id = parent_node.id() if parent_node else None
        new_id = self._db.create_folder(name, parent_id)

        if parent_node:
            row = parent_node.child_count()
            self.beginInsertRows(parent_index, row, row)
            new_folder = self._db.get_folder(new_id)
            if new_folder:
                new_folder["children"] = []
                parent_node.add_child(FolderNode(new_folder, parent_node))
            self.endInsertRows()
        else:
            row = len(self._root_nodes)
            self.beginInsertRows(QModelIndex(), row, row)
            new_folder = self._db.get_folder(new_id)
            if new_folder:
                new_folder["children"] = []
                self._root_nodes.append(FolderNode(new_folder))
            self.endInsertRows()

        self.folder_created.emit(new_id, parent_id if parent_id else 0)
        return self.index(row, 0, parent_index)

    def delete_folder(self, index: QModelIndex) -> bool:
        if not index.isValid():
            return False
        node = self._get_node(index)
        if not node:
            return False
        reply = QMessageBox.question(
            None, "Delete Folder",
            f"Delete folder '{node.data(0)}' and all its contents?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return False

        parent_index = self.parent(index)
        row = node.row()
        folder_id = node.id()

        if parent_index.isValid():
            parent_node = self._get_node(parent_index)
            self.beginRemoveRows(parent_index, row, row)
            parent_node.remove_child(row)
            self.endRemoveRows()
        else:
            self.beginRemoveRows(QModelIndex(), row, row)
            self._root_nodes.pop(row)
            self.endRemoveRows()

        self._db.delete_folder(folder_id)
        self.folder_deleted.emit(folder_id)
        return True


class FolderTreeView(QTreeView):
    folder_selected = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        self._db = db
        self._model = FolderTreeModel(db, self)
        self.setModel(self._model)
        self.setHeaderHidden(False)
        self.setDragEnabled(True)
        self.setAcceptDrops(True)
        self.setDropIndicatorShown(True)
        self.setDragDropMode(QAbstractItemView.DragDropMode.InternalMove)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self._on_context_menu)
        self.doubleClicked.connect(self._on_double_clicked)
        self.clicked.connect(self._on_clicked)
        self.setEditTriggers(QAbstractItemView.EditTrigger.EditKeyPressed)

    def model(self) -> FolderTreeModel:
        return self._model

    def reload(self):
        self._model.reload()
        self.expandAll()

    def _on_clicked(self, index: QModelIndex):
        if index.isValid():
            folder_id = self._model.data(index, Qt.ItemDataRole.UserRole)
            if folder_id:
                self.folder_selected.emit(int(folder_id))

    def _on_double_clicked(self, index: QModelIndex):
        if self.isExpanded(index):
            self.collapse(index)
        else:
            self.expand(index)

    def _on_context_menu(self, pos):
        index = self.indexAt(pos)
        menu = QMenu(self)

        new_folder_action = QAction("New Subfolder", self)
        new_folder_action.triggered.connect(lambda: self._create_folder(index))
        menu.addAction(new_folder_action)

        if index.isValid():
            rename_action = QAction("Rename", self)
            rename_action.triggered.connect(lambda: self.edit(index))
            menu.addAction(rename_action)

            delete_action = QAction("Delete", self)
            delete_action.triggered.connect(lambda: self._model.delete_folder(index))
            menu.addAction(delete_action)

        menu.exec(self.viewport().mapToGlobal(pos))

    def _create_folder(self, parent_index: QModelIndex):
        new_index = self._model.create_child_folder(parent_index)
        if new_index and new_index.isValid():
            self.expand(parent_index)
            self.setCurrentIndex(new_index)
            self.edit(new_index)


class FolderTreeWidget(QWidget):
    folder_selected = pyqtSignal(int)
    folder_renamed = pyqtSignal(int, str)
    folder_created = pyqtSignal(int, int)
    folder_deleted = pyqtSignal(int)

    def __init__(self, db: Database, parent=None):
        super().__init__(parent)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self._tree_view = FolderTreeView(db, self)
        layout.addWidget(self._tree_view)

        self._tree_view.folder_selected.connect(self.folder_selected)
        self._tree_view.model().folder_renamed.connect(self.folder_renamed)
        self._tree_view.model().folder_created.connect(self.folder_created)
        self._tree_view.model().folder_deleted.connect(self.folder_deleted)

    def tree_view(self) -> FolderTreeView:
        return self._tree_view

    def reload(self):
        self._tree_view.reload()
