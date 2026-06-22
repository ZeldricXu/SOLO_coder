from dataclasses import dataclass, field
from typing import List, Optional, Union, Dict, Any
from enum import Enum


class NodeType(Enum):
    DOCUMENT = "document"
    PARAGRAPH = "paragraph"
    HEADING = "heading"
    CODE_BLOCK = "code_block"
    IMAGE = "image"
    FORMULA = "formula"
    TABLE = "table"
    TABLE_ROW = "table_row"
    TABLE_CELL = "table_cell"
    LIST = "list"
    LIST_ITEM = "list_item"
    QUOTE = "quote"
    TEXT = "text"
    LINK = "link"
    INLINE_CODE = "inline_code"


@dataclass
class DocumentNode:
    node_type: NodeType
    children: List["DocumentNode"] = field(default_factory=list)
    attributes: Dict[str, Any] = field(default_factory=dict)
    text_content: str = ""
    parent: Optional["DocumentNode"] = None

    def add_child(self, child: "DocumentNode") -> "DocumentNode":
        child.parent = self
        self.children.append(child)
        return child

    def insert_child(self, index: int, child: "DocumentNode") -> "DocumentNode":
        child.parent = self
        self.children.insert(index, child)
        return child

    def remove_child(self, child: "DocumentNode") -> bool:
        if child in self.children:
            self.children.remove(child)
            child.parent = None
            return True
        return False

    def remove_child_at(self, index: int) -> Optional["DocumentNode"]:
        if 0 <= index < len(self.children):
            child = self.children.pop(index)
            child.parent = None
            return child
        return None

    def find_nodes_by_type(self, node_type: NodeType) -> List["DocumentNode"]:
        result = []
        if self.node_type == node_type:
            result.append(self)
        for child in self.children:
            result.extend(child.find_nodes_by_type(node_type))
        return result

    def find_all_text(self) -> str:
        parts = [self.text_content]
        for child in self.children:
            parts.append(child.find_all_text())
        return "".join(parts)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "node_type": self.node_type.value,
            "attributes": dict(self.attributes),
            "text_content": self.text_content,
            "children": [c.to_dict() for c in self.children],
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DocumentNode":
        node = cls(
            node_type=NodeType(data["node_type"]),
            attributes=dict(data.get("attributes", {})),
            text_content=data.get("text_content", ""),
        )
        for child_data in data.get("children", []):
            child = cls.from_dict(child_data)
            node.add_child(child)
        return node

    def __len__(self) -> int:
        return len(self.children)

    def __iter__(self):
        return iter(self.children)

    def __getitem__(self, index: int) -> "DocumentNode":
        return self.children[index]


class DocumentModel:
    def __init__(self):
        self.root = DocumentNode(NodeType.DOCUMENT)
        self._undo_stack: List[List[dict]] = []
        self._redo_stack: List[List[dict]] = []
        self._history_limit = 100
        self._transaction: Optional[List[dict]] = None

    @property
    def can_undo(self) -> bool:
        return len(self._undo_stack) > 0

    @property
    def can_redo(self) -> bool:
        return len(self._redo_stack) > 0

    def _save_state(self) -> List[dict]:
        return [self.root.to_dict()]

    def _restore_state(self, state: List[dict]):
        self.root = DocumentNode.from_dict(state[0])

    def begin_transaction(self):
        if self._transaction is None:
            self._transaction = self._save_state()

    def end_transaction(self):
        if self._transaction is not None:
            transaction = self._transaction
            self._transaction = None
            self._undo_stack.append(transaction)
            if len(self._undo_stack) > self._history_limit:
                self._undo_stack.pop(0)
            self._redo_stack.clear()

    def _push_undo(self, operations: Optional[List[dict]] = None):
        if self._transaction is not None:
            return
        state = self._save_state()
        self._undo_stack.append(state)
        if len(self._undo_stack) > self._history_limit:
            self._undo_stack.pop(0)
        self._redo_stack.clear()

    def undo(self) -> bool:
        if not self._undo_stack:
            return False
        current_state = self._save_state()
        state = self._undo_stack.pop()
        self._redo_stack.append(current_state)
        self._restore_state(state)
        return True

    def redo(self) -> bool:
        if not self._redo_stack:
            return False
        current_state = self._save_state()
        state = self._redo_stack.pop()
        self._undo_stack.append(current_state)
        self._restore_state(state)
        return True

    def append_node(self, parent: DocumentNode, node: DocumentNode) -> DocumentNode:
        self._push_undo()
        return parent.add_child(node)

    def insert_node(self, parent: DocumentNode, index: int, node: DocumentNode) -> DocumentNode:
        self._push_undo()
        return parent.insert_child(index, node)

    def remove_node(self, node: DocumentNode) -> bool:
        if node.parent is None:
            return False
        self._push_undo()
        return node.parent.remove_child(node)

    def update_node_attributes(self, node: DocumentNode, **kwargs) -> DocumentNode:
        self._push_undo()
        node.attributes.update(kwargs)
        return node

    def update_node_text(self, node: DocumentNode, text: str) -> DocumentNode:
        self._push_undo()
        node.text_content = text
        return node

    def append_paragraph(self, text: str = "") -> DocumentNode:
        node = DocumentNode(NodeType.PARAGRAPH)
        if text:
            node.add_child(DocumentNode(NodeType.TEXT, text_content=text))
        self._push_undo()
        return self.root.add_child(node)

    def append_heading(self, level: int, text: str = "") -> DocumentNode:
        node = DocumentNode(NodeType.HEADING, attributes={"level": level})
        if text:
            node.add_child(DocumentNode(NodeType.TEXT, text_content=text))
        self._push_undo()
        return self.root.add_child(node)

    def append_code_block(self, language: str, code: str = "") -> DocumentNode:
        node = DocumentNode(
            NodeType.CODE_BLOCK,
            attributes={"language": language},
            text_content=code,
        )
        self._push_undo()
        return self.root.add_child(node)

    def append_image(self, path: str, alt: str = "", title: str = "") -> DocumentNode:
        node = DocumentNode(
            NodeType.IMAGE,
            attributes={"path": path, "alt": alt, "title": title},
        )
        self._push_undo()
        return self.root.add_child(node)

    def append_formula(self, latex: str, inline: bool = False) -> DocumentNode:
        node = DocumentNode(
            NodeType.FORMULA,
            attributes={"latex": latex, "inline": inline},
        )
        self._push_undo()
        return self.root.add_child(node)

    def append_table(self, rows: List[List[str]], headers: Optional[List[str]] = None) -> DocumentNode:
        table = DocumentNode(NodeType.TABLE, attributes={
            "row_count": len(rows),
            "col_count": len(headers) if headers else (len(rows[0]) if rows else 0),
        })
        if headers:
            header_row = DocumentNode(NodeType.TABLE_ROW, attributes={"is_header": True})
            for h in headers:
                cell = DocumentNode(NodeType.TABLE_CELL)
                cell.add_child(DocumentNode(NodeType.TEXT, text_content=h))
                header_row.add_child(cell)
            table.add_child(header_row)
        for row_data in rows:
            row = DocumentNode(NodeType.TABLE_ROW)
            for cell_data in row_data:
                cell = DocumentNode(NodeType.TABLE_CELL)
                cell.add_child(DocumentNode(NodeType.TEXT, text_content=cell_data))
                row.add_child(cell)
            table.add_child(row)
        self._push_undo()
        return self.root.add_child(table)

    def append_list(self, items: List[str], ordered: bool = False) -> DocumentNode:
        list_node = DocumentNode(NodeType.LIST, attributes={"ordered": ordered})
        for idx, item in enumerate(items):
            li = DocumentNode(NodeType.LIST_ITEM, attributes={"index": idx + 1 if ordered else 0})
            li.add_child(DocumentNode(NodeType.TEXT, text_content=item))
            list_node.add_child(li)
        self._push_undo()
        return self.root.add_child(list_node)

    def append_quote(self, text: str) -> DocumentNode:
        node = DocumentNode(NodeType.QUOTE)
        node.add_child(DocumentNode(NodeType.TEXT, text_content=text))
        self._push_undo()
        return self.root.add_child(node)

    def clear(self):
        self._push_undo()
        self.root = DocumentNode(NodeType.DOCUMENT)

    def get_node_by_path(self, path: List[int]) -> Optional[DocumentNode]:
        node = self.root
        for idx in path:
            if idx < 0 or idx >= len(node.children):
                return None
            node = node.children[idx]
        return node

    def get_all_nodes_of_type(self, node_type: NodeType) -> List[DocumentNode]:
        return self.root.find_nodes_by_type(node_type)
