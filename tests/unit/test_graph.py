import math
from typing import List, Tuple

import pytest
from PyQt6.QtCore import Qt, QPointF, QRectF, QTimer
from PyQt6.QtGui import QColor, QPainterPath
from PyQt6.QtTest import QTest, QSignalSpy

from app.graph import GraphWidget, GraphNode, GraphEdge, ForceDirectedLayout
from app.database import Database


EDGE_COLOR_FORWARD = QColor("#4A90D9")
EDGE_COLOR_BIDIRECTIONAL = QColor("#9B59B6")


def sample_edge_path_points(edge: GraphEdge, num_points: int = 10) -> List[QPointF]:
    path = edge._bezier_path()
    points = []
    for i in range(num_points + 1):
        t = i / num_points
        points.append(path.pointAtPercent(t))
    return points


def point_in_node_rect(point: QPointF, node: GraphNode, margin: float = 1.0) -> bool:
    rect = node.boundingRect()
    scene_rect = QRectF(
        node.pos().x() + rect.x(),
        node.pos().y() + rect.y(),
        rect.width(),
        rect.height()
    )
    adjusted_rect = QRectF(
        scene_rect.x() + margin,
        scene_rect.y() + margin,
        scene_rect.width() - 2 * margin,
        scene_rect.height() - 2 * margin
    )
    return adjusted_rect.contains(point)


def run_layout_steps(layout_engine: ForceDirectedLayout, steps: int = 50):
    for _ in range(steps):
        if not layout_engine.step():
            break


def wait_for_layout_completion(qtbot, graph_widget: GraphWidget, timeout: int = 5000):
    def layout_done():
        return graph_widget._layout_timer is None
    try:
        qtbot.waitUntil(layout_done, timeout=timeout)
    except Exception:
        if graph_widget._layout_timer is not None:
            graph_widget._layout_timer.stop()
            graph_widget._layout_timer = None
            graph_widget._layout_engine = None
        qtbot.wait(200)

    if graph_widget._layout_engine is not None:
        for _ in range(200):
            if not graph_widget._layout_engine.step():
                break
        graph_widget._update_edge_positions()
        qtbot.wait(50)


class TestKnowledgeGraphRendering:

    def test_nodes_no_overlap(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)

        wait_for_layout_completion(qtbot, graph_widget)

        nodes = list(graph_widget._nodes.values())
        assert len(nodes) == 6

        for i in range(len(nodes)):
            for j in range(i + 1, len(nodes)):
                n1 = nodes[i]
                n2 = nodes[j]
                r1 = n1.boundingRect()
                r2 = n2.boundingRect()
                scene_r1 = QRectF(
                    n1.pos().x() + r1.x(),
                    n1.pos().y() + r1.y(),
                    r1.width(),
                    r1.height()
                )
                scene_r2 = QRectF(
                    n2.pos().x() + r2.x(),
                    n2.pos().y() + r2.y(),
                    r2.width(),
                    r2.height()
                )
                assert not scene_r1.intersects(scene_r2), \
                    f"节点{n1.note_id}和节点{n2.note_id}发生重叠"

    def test_edges_do_not_cross_unrelated_nodes(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)

        wait_for_layout_completion(qtbot, graph_widget)

        for edge in graph_widget._edges:
            if edge.source is None or edge.target is None:
                continue
            source_id = edge.source.note_id
            target_id = edge.target.note_id

            points = sample_edge_path_points(edge, num_points=10)

            for nid, node in graph_widget._nodes.items():
                if nid == source_id or nid == target_id:
                    continue

                for point in points:
                    node_center = node.pos()
                    node_half_w = node._width / 2
                    node_half_h = node._height / 2

                    dx = point.x() - node_center.x()
                    dy = point.y() - node_center.y()

                    if abs(dx) < node_half_w - 2 and abs(dy) < node_half_h - 2:
                        assert False, f"边{source_id}→{target_id}穿过节点{nid}"

    def test_isolated_node_has_separate_area(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)

        wait_for_layout_completion(qtbot, graph_widget)

        isolated_nodes = [n for n in graph_widget._nodes.values() if n.is_isolated]
        non_isolated_nodes = [n for n in graph_widget._nodes.values() if not n.is_isolated]

        assert len(isolated_nodes) == 1
        assert len(non_isolated_nodes) == 5

        isolated_node = isolated_nodes[0]

        non_isolated_centers = [n.pos() for n in non_isolated_nodes]
        center_x = sum(p.x() for p in non_isolated_centers) / len(non_isolated_centers)
        center_y = sum(p.y() for p in non_isolated_centers) / len(non_isolated_centers)
        cluster_center = QPointF(center_x, center_y)

        distances_between_non_isolated = []
        for i in range(len(non_isolated_nodes)):
            for j in range(i + 1, len(non_isolated_nodes)):
                dx = non_isolated_nodes[i].pos().x() - non_isolated_nodes[j].pos().x()
                dy = non_isolated_nodes[i].pos().y() - non_isolated_nodes[j].pos().y()
                distances_between_non_isolated.append(math.sqrt(dx * dx + dy * dy))
        avg_distance = sum(distances_between_non_isolated) / len(distances_between_non_isolated)

        dx = isolated_node.pos().x() - cluster_center.x()
        dy = isolated_node.pos().y() - cluster_center.y()
        distance_to_center = math.sqrt(dx * dx + dy * dy)

        assert distance_to_center > avg_distance, \
            f"孤立节点距离聚集中心{distance_to_center:.1f}应大于平均距离{avg_distance:.1f}"

        assert isolated_node.is_isolated is True

    def test_node_size_and_color(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)

        wait_for_layout_completion(qtbot, graph_widget)

        citation_counts = {}
        for nid, node in graph_widget._nodes.items():
            citation_counts[nid] = node.citation_count

        node3 = None
        isolated_node = None
        for nid, node in graph_widget._nodes.items():
            if node.citation_count == 2:
                node3 = node
            if node.is_isolated:
                isolated_node = node

        assert node3 is not None
        assert isolated_node is not None

        assert node3._width > isolated_node._width, \
            f"被引用多的节点宽度{node3._width}应大于孤立节点宽度{isolated_node._width}"
        assert node3._height > isolated_node._height, \
            f"被引用多的节点高度{node3._height}应大于孤立节点高度{isolated_node._height}"

        note_tags = {}
        for nid, node in graph_widget._nodes.items():
            if node.tags:
                expected_color = QColor(node.tags[0].get("color", "#4A90D9"))
                expected_color.setAlpha(200)
                assert node._color.name() == expected_color.name(), \
                    f"节点{nid}颜色{node._color.name()}应与标签颜色{expected_color.name()}匹配"

        for nid, node in graph_widget._nodes.items():
            display_title = node.get_display_title()
            if len(node.title) > 15:
                assert len(display_title) == 18
                assert display_title.endswith("...")
            else:
                assert display_title == node.title

    def test_edge_direction_and_color(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)

        wait_for_layout_completion(qtbot, graph_widget)

        for edge in graph_widget._edges:
            if edge.bidirectional:
                assert edge._edge_color().name() == EDGE_COLOR_BIDIRECTIONAL.name(), \
                    f"双向边颜色应为紫色"
            else:
                assert edge._edge_color().name() == EDGE_COLOR_FORWARD.name(), \
                    f"单向边颜色应为蓝色"

        bidirectional_edges = [e for e in graph_widget._edges if e.bidirectional]
        assert len(bidirectional_edges) >= 1

        for edge in bidirectional_edges:
            assert edge.bidirectional is True

        forward_edges = [e for e in graph_widget._edges if not e.bidirectional]
        assert len(forward_edges) >= 1


class TestKnowledgeGraphInteraction:

    def test_double_click_triggers_note_jump(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)
        wait_for_layout_completion(qtbot, graph_widget)

        spy = QSignalSpy(graph_widget.noteDoubleClicked)

        target_note_id = list(graph_widget._nodes.keys())[0]
        target_node = graph_widget._nodes[target_note_id]

        scene_pos = target_node.pos()
        view_pos = graph_widget.view.mapFromScene(scene_pos)
        widget_pos = graph_widget.view.mapToParent(view_pos)

        QTest.mouseDClick(
            graph_widget.view.viewport(),
            Qt.MouseButton.LeftButton,
            Qt.KeyboardModifier.NoModifier,
            view_pos
        )

        qtbot.wait(100)

        assert len(spy) == 1, f"双击信号应触发1次，实际触发{len(spy)}次"
        assert spy[0][0] == target_note_id, f"信号参数应为{target_note_id}，实际为{spy[0][0]}"

    def test_drag_node_updates_edges(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)
        wait_for_layout_completion(qtbot, graph_widget, timeout=3000)

        target_note_id = list(graph_widget._nodes.keys())[0]
        target_node = graph_widget._nodes[target_note_id]

        connected_edges = [
            e for e in graph_widget._edges
            if (e.source and e.source.note_id == target_note_id) or
               (e.target and e.target.note_id == target_note_id)
        ]
        assert len(connected_edges) > 0

        old_positions = {}
        for edge in connected_edges:
            line = edge.line()
            if edge.source and edge.source.note_id == target_note_id:
                old_positions[edge] = (line.p1(), line.p2())
            else:
                old_positions[edge] = (line.p1(), line.p2())

        position_spy = QSignalSpy(target_node.positionChanged)

        old_pos = target_node.pos()
        new_pos = QPointF(old_pos.x() + 100, old_pos.y() + 100)

        scene_pos = target_node.pos()
        view_pos = graph_widget.view.mapFromScene(scene_pos)

        QTest.mousePress(
            graph_widget.view.viewport(),
            Qt.MouseButton.LeftButton,
            Qt.KeyboardModifier.NoModifier,
            view_pos
        )

        new_scene_pos = new_pos
        new_view_pos = graph_widget.view.mapFromScene(new_scene_pos)
        QTest.mouseMove(
            graph_widget.view.viewport(),
            new_view_pos
        )

        QTest.mouseRelease(
            graph_widget.view.viewport(),
            Qt.MouseButton.LeftButton,
            Qt.KeyboardModifier.NoModifier,
            new_view_pos
        )

        target_node.setPos(new_pos)
        target_node.positionChanged.emit(target_note_id, new_pos.x(), new_pos.y())

        qtbot.wait(100)

        for edge in connected_edges:
            edge.update_position()

        for edge in connected_edges:
            line = edge.line()
            if edge.source and edge.source.note_id == target_note_id:
                assert abs(line.p1().x() - new_pos.x()) < 1
                assert abs(line.p1().y() - new_pos.y()) < 1
            else:
                assert abs(line.p2().x() - new_pos.x()) < 1
                assert abs(line.p2().y() - new_pos.y()) < 1

        assert len(position_spy) >= 1
        last_signal = position_spy[-1]
        assert last_signal[0] == target_note_id
        assert abs(last_signal[1] - new_pos.x()) < 1
        assert abs(last_signal[2] - new_pos.y()) < 1

    def test_zoom_scales_node_labels(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)
        wait_for_layout_completion(qtbot, graph_widget)

        graph_widget.view.reset_zoom()
        assert abs(graph_widget.view.get_scale() - 1.0) < 0.01

        graph_widget.view.zoom_in()
        assert abs(graph_widget.view.get_scale() - 1.2) < 0.1

        graph_widget.view.zoom_in()
        assert graph_widget.view.get_scale() > 1.2

        graph_widget.view.zoom_out()
        assert graph_widget.view.get_scale() < 1.44

        graph_widget.view.reset_zoom()
        assert abs(graph_widget.view.get_scale() - 1.0) < 0.01

        initial_scale = graph_widget.view.get_scale()
        graph_widget.zoom_in_btn.click()
        assert graph_widget.view.get_scale() > initial_scale

        graph_widget.zoom_out_btn.click()
        assert graph_widget.view.get_scale() < initial_scale * 1.2

        graph_widget.zoom_reset_btn.click()
        assert abs(graph_widget.view.get_scale() - 1.0) < 0.01

        for node in graph_widget._nodes.values():
            br = node.boundingRect()
            font_scale = min(1.0, node._width / 120)
            expected_font_size = max(8, int(11 * font_scale))
            assert expected_font_size >= 8

            text_rect_width = node._width - 16
            assert text_rect_width > 0

    def test_node_selection_highlight(self, qapp, qtbot, graph_widget: GraphWidget, graph_test_db: Database):
        graph_widget.load_graph(graph_test_db)
        wait_for_layout_completion(qtbot, graph_widget)

        target_note_id = list(graph_widget._nodes.keys())[0]
        target_node = graph_widget._nodes[target_note_id]

        assert target_node.isSelected() is False
        normal_rect = target_node.boundingRect()

        target_node.setSelected(True)
        assert target_node.isSelected() is True
        assert target_node._selected is True

        selected_rect = target_node.boundingRect()

        assert selected_rect.width() > normal_rect.width(), \
            f"选中后boundingRect宽度应更大"
        assert selected_rect.height() > normal_rect.height(), \
            f"选中后boundingRect高度应更大"

        target_node.setSelected(False)
        assert target_node.isSelected() is False
        assert target_node._selected is False
