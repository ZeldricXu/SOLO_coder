import math
import random
from typing import Dict, List, Optional, Tuple, Set

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QToolBar, QPushButton,
    QLabel, QLineEdit, QCheckBox, QStatusBar, QComboBox, QFrame
)
from PyQt6.QtCore import Qt, pyqtSignal, QPointF, QRectF, QTimer
from PyQt6.QtGui import QIcon, QAction

from app.database import Database
from app.graph.graph_view import GraphView
from app.graph.graph_node import GraphNode
from app.graph.graph_edge import GraphEdge


class ForceDirectedLayout:
    def __init__(
        self,
        nodes: Dict[int, GraphNode],
        edges: List[Tuple[int, int]],
        width: float = 800,
        height: float = 600,
    ):
        self.nodes = nodes
        self.edges = edges
        self.width = width
        self.height = height
        self.k = math.sqrt(width * height / max(1, len(nodes)))
        self.temperature = width / 10.0
        self.iterations = 0
        self.max_iterations = 300
        self.epsilon = 0.5

    def _repulsion(self, n1: GraphNode, n2: GraphNode) -> QPointF:
        dx = n1.pos().x() - n2.pos().x()
        dy = n1.pos().y() - n2.pos().y()
        dist = math.sqrt(dx * dx + dy * dy)
        if dist < 1e-6:
            dx = random.uniform(-1, 1)
            dy = random.uniform(-1, 1)
            dist = math.sqrt(dx * dx + dy * dy)
        force = (self.k * self.k) / dist
        return QPointF(dx / dist * force, dy / dist * force)

    def _attraction(self, n1: GraphNode, n2: GraphNode) -> QPointF:
        dx = n1.pos().x() - n2.pos().x()
        dy = n1.pos().y() - n2.pos().y()
        dist = math.sqrt(dx * dx + dy * dy)
        if dist < 1e-6:
            return QPointF(0, 0)
        force = (dist * dist) / self.k
        return QPointF(-dx / dist * force, -dy / dist * force)

    def step(self) -> bool:
        if self.iterations >= self.max_iterations:
            return False

        displacements: Dict[int, QPointF] = {nid: QPointF(0, 0) for nid in self.nodes}
        node_ids = list(self.nodes.keys())

        for i in range(len(node_ids)):
            for j in range(i + 1, len(node_ids)):
                n1 = self.nodes[node_ids[i]]
                n2 = self.nodes[node_ids[j]]
                disp = self._repulsion(n1, n2)
                displacements[node_ids[i]] += disp
                displacements[node_ids[j]] -= disp

        for src_id, tgt_id in self.edges:
            if src_id in self.nodes and tgt_id in self.nodes:
                disp = self._attraction(self.nodes[src_id], self.nodes[tgt_id])
                displacements[src_id] += disp
                displacements[tgt_id] -= disp

        max_disp = 0.0
        for nid in node_ids:
            node = self.nodes[nid]
            disp = displacements[nid]
            disp_mag = math.sqrt(disp.x() * disp.x() + disp.y() * disp.y())
            if disp_mag > self.epsilon:
                clamped = min(disp_mag, self.temperature)
                ratio = clamped / disp_mag
                new_pos = node.pos() + QPointF(disp.x() * ratio, disp.y() * ratio)
                half_w = self.width / 2
                half_h = self.height / 2
                new_pos.setX(max(-half_w, min(half_w, new_pos.x())))
                new_pos.setY(max(-half_h, min(half_h, new_pos.y())))
                node.setPos(new_pos)
                max_disp = max(max_disp, disp_mag)

        self.temperature *= 0.95
        self.iterations += 1

        if max_disp < self.epsilon or self.iterations >= self.max_iterations:
            return False
        return True


class GraphWidget(QWidget):
    noteDoubleClicked = pyqtSignal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.db: Optional[Database] = None
        self._nodes: Dict[int, GraphNode] = {}
        self._edges: List[GraphEdge] = []
        self._edge_pairs: Set[Tuple[int, int]] = set()
        self._isolated_note_ids: Set[int] = set()
        self._show_isolated = True
        self._layout_timer: Optional[QTimer] = None
        self._layout_engine: Optional[ForceDirectedLayout] = None
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        toolbar = QToolBar()
        toolbar.setMovable(False)

        self.zoom_out_btn = QPushButton("-")
        self.zoom_out_btn.setFixedWidth(32)
        self.zoom_out_btn.setToolTip("缩小")
        self.zoom_out_btn.clicked.connect(self._on_zoom_out)
        toolbar.addWidget(self.zoom_out_btn)

        self.zoom_reset_btn = QPushButton("100%")
        self.zoom_reset_btn.setFixedWidth(60)
        self.zoom_reset_btn.setToolTip("重置缩放")
        self.zoom_reset_btn.clicked.connect(self._on_zoom_reset)
        toolbar.addWidget(self.zoom_reset_btn)

        self.zoom_in_btn = QPushButton("+")
        self.zoom_in_btn.setFixedWidth(32)
        self.zoom_in_btn.setToolTip("放大")
        self.zoom_in_btn.clicked.connect(self._on_zoom_in)
        toolbar.addWidget(self.zoom_in_btn)

        separator = QFrame()
        separator.setFrameShape(QFrame.Shape.VLine)
        separator.setFrameShadow(QFrame.Shadow.Sunken)
        toolbar.addWidget(separator)

        self.auto_layout_btn = QPushButton("自动布局")
        self.auto_layout_btn.setToolTip("使用弹簧算法重新布局")
        self.auto_layout_btn.clicked.connect(self._on_auto_layout)
        toolbar.addWidget(self.auto_layout_btn)

        self.fit_view_btn = QPushButton("适应视图")
        self.fit_view_btn.setToolTip("缩放以显示全部节点")
        self.fit_view_btn.clicked.connect(self._on_fit_view)
        toolbar.addWidget(self.fit_view_btn)

        separator2 = QFrame()
        separator2.setFrameShape(QFrame.Shape.VLine)
        separator2.setFrameShadow(QFrame.Shadow.Sunken)
        toolbar.addWidget(separator2)

        self.filter_isolated_cb = QCheckBox("隐藏孤立节点")
        self.filter_isolated_cb.stateChanged.connect(self._on_filter_isolated)
        toolbar.addWidget(self.filter_isolated_cb)

        toolbar.addSpacing(16)

        toolbar.addWidget(QLabel("搜索:"))
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("输入笔记标题...")
        self.search_input.setFixedWidth(180)
        self.search_input.textChanged.connect(self._on_search_text)
        self.search_input.returnPressed.connect(self._on_search_go)
        toolbar.addWidget(self.search_input)

        self.search_combo = QComboBox()
        self.search_combo.setFixedWidth(180)
        self.search_combo.activated.connect(self._on_search_select)
        toolbar.addWidget(self.search_combo)

        self.locate_btn = QPushButton("定位")
        self.locate_btn.clicked.connect(self._on_locate)
        toolbar.addWidget(self.locate_btn)

        layout.addWidget(toolbar)

        self.view = GraphView()
        self.view.nodeDoubleClicked.connect(self._on_node_double_clicked)
        layout.addWidget(self.view, 1)

        self.status_bar = QStatusBar()
        self.node_count_label = QLabel("节点: 0")
        self.edge_count_label = QLabel("连线: 0")
        self.scale_label = QLabel("缩放: 100%")
        self.status_bar.addWidget(self.node_count_label)
        self.status_bar.addWidget(self.edge_count_label)
        self.status_bar.addPermanentWidget(self.scale_label)
        layout.addWidget(self.status_bar)

        self._update_scale_label()
        self.view.verticalScrollBar().valueChanged.connect(self._update_scale_label)
        self.view.horizontalScrollBar().valueChanged.connect(self._update_scale_label)

    def _update_scale_label(self):
        scale_pct = int(self.view.get_scale() * 100)
        self.scale_label.setText(f"缩放: {scale_pct}%")
        self.zoom_reset_btn.setText(f"{scale_pct}%")

    def _on_zoom_in(self):
        self.view.zoom_in()
        self._update_scale_label()

    def _on_zoom_out(self):
        self.view.zoom_out()
        self._update_scale_label()

    def _on_zoom_reset(self):
        self.view.reset_zoom()
        self._update_scale_label()

    def _on_fit_view(self):
        self.view.reset_view()
        self._update_scale_label()

    def _on_filter_isolated(self, state: int):
        self._show_isolated = state != Qt.CheckState.Checked.value
        for nid, node in self._nodes.items():
            node.setVisible(self._show_isolated or nid not in self._isolated_note_ids)
        for edge in self._edges:
            src_vis = edge.source.isVisible() if edge.source else False
            tgt_vis = edge.target.isVisible() if edge.target else False
            edge.setVisible(src_vis and tgt_vis)

    def _on_search_text(self, text: str):
        self.search_combo.clear()
        if not text or not text.strip():
            return
        query = text.strip().lower()
        for nid, node in self._nodes.items():
            if query in node.title.lower():
                display = node.title
                if len(display) > 30:
                    display = display[:30] + "..."
                self.search_combo.addItem(display, nid)

    def _on_search_go(self):
        if self.search_combo.count() > 0:
            self.search_combo.setCurrentIndex(0)
            self._on_search_select(0)

    def _on_search_select(self, index: int):
        if index < 0 or index >= self.search_combo.count():
            return
        note_id = self.search_combo.itemData(index)
        if note_id is not None and note_id in self._nodes:
            self._center_on_node(note_id)

    def _on_locate(self):
        self._on_search_go()

    def _center_on_node(self, note_id: int):
        node = self._nodes.get(note_id)
        if node is None:
            return
        self.view.resetTransform()
        self.view.centerOn(node)
        self.view.scale(1.5, 1.5)
        self._update_scale_label()
        for n in self._nodes.values():
            n.setSelected(False)
        node.setSelected(True)

    def _on_node_double_clicked(self, note_id: int):
        self.noteDoubleClicked.emit(note_id)

    def _on_auto_layout(self):
        if not self._nodes:
            return
        edge_pairs = [(e.source.note_id, e.target.note_id) for e in self._edges if e.source and e.target]
        self._layout_engine = ForceDirectedLayout(
            self._nodes, edge_pairs, width=1200, height=900
        )
        if self._layout_timer is not None:
            self._layout_timer.stop()
        self._layout_timer = QTimer(self)
        self._layout_timer.timeout.connect(self._layout_step)
        self._layout_timer.start(16)

    def _layout_step(self):
        if self._layout_engine is None:
            return
        if not self._layout_engine.step():
            self._layout_timer.stop()
            self._layout_timer = None
            self._layout_engine = None
            self._save_all_positions()
        for edge in self._edges:
            edge.update_position()

    def _on_node_position_changed(self, note_id: int, x: float, y: float):
        if self.db is not None:
            self.db.set_graph_layout(note_id, x, y)
        for edge in self._edges:
            if (edge.source and edge.source.note_id == note_id) or (
                edge.target and edge.target.note_id == note_id
            ):
                edge.update_position()

    def _save_all_positions(self):
        if self.db is None:
            return
        for nid, node in self._nodes.items():
            pos = node.pos()
            self.db.set_graph_layout(nid, pos.x(), pos.y())

    def _clear_graph(self):
        if self._layout_timer is not None:
            self._layout_timer.stop()
            self._layout_timer = None
        self._layout_engine = None
        for edge in self._edges:
            if edge.scene():
                edge.scene().removeItem(edge)
        self._edges.clear()
        self._edge_pairs.clear()
        for node in self._nodes.values():
            if node.scene():
                node.scene().removeItem(node)
        self._nodes.clear()
        self._isolated_note_ids.clear()

    def load_graph(self, db: Database):
        self.db = db
        self._clear_graph()

        notes = db.list_notes(limit=10000)
        references = db.get_all_references()
        saved_layouts = db.get_all_graph_layouts()

        isolated_ids = {n["id"] for n in db.get_isolated_notes()}
        self._isolated_note_ids = isolated_ids

        connected_note_ids: Set[int] = set()
        for src, tgt in references:
            connected_note_ids.add(src)
            connected_note_ids.add(tgt)

        citation_counts: Dict[int, int] = {}
        for src, tgt in references:
            citation_counts[tgt] = citation_counts.get(tgt, 0) + 1

        note_tags: Dict[int, List] = {}
        for note in notes:
            note_tags[note["id"]] = db.get_note_tags(note["id"])

        for note in notes:
            nid = note["id"]
            node = GraphNode(
                note_id=nid,
                title=note.get("title", "Untitled"),
                citation_count=citation_counts.get(nid, 0),
                tags=note_tags.get(nid, []),
                is_isolated=(nid in isolated_ids),
            )
            node.positionChanged.connect(self._on_node_position_changed)
            if nid in saved_layouts:
                x, y = saved_layouts[nid]
                node.setPos(QPointF(x, y))
            else:
                angle = random.uniform(0, 2 * math.pi)
                radius = random.uniform(50, 400)
                node.setPos(QPointF(math.cos(angle) * radius, math.sin(angle) * radius))
            self._nodes[nid] = node
            self.view.scene().addItem(node)

        bidirectional: Set[Tuple[int, int]] = set()
        forward_set: Set[Tuple[int, int]] = set()
        for src, tgt in references:
            forward_set.add((src, tgt))
            if (tgt, src) in forward_set:
                bidirectional.add((min(src, tgt), max(src, tgt)))

        drawn_edges: Set[Tuple[int, int]] = set()
        for src, tgt in references:
            if src not in self._nodes or tgt not in self._nodes:
                continue
            is_bidir = (min(src, tgt), max(src, tgt)) in bidirectional
            edge_key = (min(src, tgt), max(src, tgt))
            if is_bidir and edge_key in drawn_edges:
                continue
            if edge_key in drawn_edges:
                continue
            edge = GraphEdge(
                source_node=self._nodes[src],
                target_node=self._nodes[tgt],
                bidirectional=is_bidir,
            )
            self.view.scene().addItem(edge)
            self._edges.append(edge)
            self._edge_pairs.add((src, tgt))
            drawn_edges.add(edge_key)

        if not saved_layouts:
            self._on_auto_layout()

        rect = self.view.scene().itemsBoundingRect()
        margin = 100
        self.view.scene().setSceneRect(
            rect.x() - margin, rect.y() - margin,
            rect.width() + margin * 2, rect.height() + margin * 2,
        )
        self.view.reset_view()
        self._update_scale_label()
        self._update_status_counts()

    def _update_status_counts(self):
        visible_nodes = sum(1 for n in self._nodes.values() if n.isVisible())
        visible_edges = sum(1 for e in self._edges if e.isVisible())
        self.node_count_label.setText(f"节点: {visible_nodes}")
        self.edge_count_label.setText(f"连线: {visible_edges}")
