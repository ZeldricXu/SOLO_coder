import math
from typing import Optional

from PyQt6.QtWidgets import QGraphicsLineItem, QGraphicsItem, QStyleOptionGraphicsItem, QWidget
from PyQt6.QtCore import Qt, QRectF, QLineF, QPointF
from PyQt6.QtGui import QPainter, QPainterPath, QColor, QPen, QBrush, QPolygonF


EDGE_COLOR_FORWARD = QColor("#4A90D9")
EDGE_COLOR_BACKWARD = QColor("#E74C3C")
ARROW_SIZE = 10.0


class GraphEdge(QGraphicsLineItem):
    def __init__(
        self,
        source_node,
        target_node,
        bidirectional: bool = False,
        parent: Optional[QGraphicsItem] = None,
    ):
        super().__init__(parent)
        self.source = source_node
        self.target = target_node
        self.bidirectional = bidirectional
        self.setZValue(-1)
        self.setAcceptedMouseButtons(Qt.MouseButton.NoButton)
        self.setFlag(QGraphicsItem.GraphicsItemFlag.ItemIsSelectable, False)
        self._update_position()

    def _edge_color(self) -> QColor:
        if self.bidirectional:
            return QColor("#9B59B6")
        return EDGE_COLOR_FORWARD

    def _update_position(self):
        if self.source is None or self.target is None:
            return
        src_pos = self.source.pos()
        tgt_pos = self.target.pos()
        self.setLine(QLineF(src_pos, tgt_pos))

    def _control_points(self, line: QLineF):
        dx = line.dx()
        dy = line.dy()
        dist = math.sqrt(dx * dx + dy * dy)
        if dist < 1e-6:
            return line.p1(), line.p2()

        perp_x = -dy / dist * dist * 0.2
        perp_y = dx / dist * dist * 0.2

        mid_x = (line.x1() + line.x2()) / 2
        mid_y = (line.y1() + line.y2()) / 2
        ctrl = QPointF(mid_x + perp_x, mid_y + perp_y)
        return ctrl, ctrl

    def _bezier_path(self) -> QPainterPath:
        line = self.line()
        path = QPainterPath(line.p1())
        ctrl1, ctrl2 = self._control_points(line)
        path.cubicTo(ctrl1, ctrl2, line.p2())
        return path

    def _arrow_points(self, end_point: QPointF, direction_angle: float) -> QPolygonF:
        arrow_p1 = QPointF(
            end_point.x() - ARROW_SIZE * math.cos(direction_angle - math.pi / 6),
            end_point.y() - ARROW_SIZE * math.sin(direction_angle - math.pi / 6),
        )
        arrow_p2 = QPointF(
            end_point.x() - ARROW_SIZE * math.cos(direction_angle + math.pi / 6),
            end_point.y() - ARROW_SIZE * math.sin(direction_angle + math.pi / 6),
        )
        polygon = QPolygonF()
        polygon.append(end_point)
        polygon.append(arrow_p1)
        polygon.append(arrow_p2)
        return polygon

    def _tangent_angle_at_end(self) -> float:
        line = self.line()
        dx = line.dx()
        dy = line.dy()
        return math.atan2(dy, dx)

    def _clip_to_node_border(self, node, target_point: QPointF) -> QPointF:
        node_pos = node.pos()
        half_w = node._width / 2
        half_h = node._height / 2
        dx = target_point.x() - node_pos.x()
        dy = target_point.y() - node_pos.y()

        if abs(dx) < 1e-6 and abs(dy) < 1e-6:
            return node_pos

        scale_x = half_w / abs(dx) if abs(dx) > 1e-6 else float("inf")
        scale_y = half_h / abs(dy) if abs(dy) > 1e-6 else float("inf")
        scale = min(scale_x, scale_y)
        return QPointF(node_pos.x() + dx * scale, node_pos.y() + dy * scale)

    def boundingRect(self) -> QRectF:
        extra = ARROW_SIZE + 4.0
        line = self.line()
        rect = QRectF(
            min(line.x1(), line.x2()) - extra,
            min(line.y1(), line.y2()) - extra,
            abs(line.dx()) + extra * 2,
            abs(line.dy()) + extra * 2,
        )
        return rect

    def shape(self) -> QPainterPath:
        return self._bezier_path()

    def paint(self, painter: QPainter, option: QStyleOptionGraphicsItem, widget: Optional[QWidget] = None):
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        if self.source is None or self.target is None:
            return

        self._update_position()
        line = self.line()

        start = self._clip_to_node_border(self.source, line.p2())
        end = self._clip_to_node_border(self.target, line.p1())

        line = QLineF(start, end)

        ctrl1, ctrl2 = self._control_points(line)
        path = QPainterPath(start)
        path.cubicTo(ctrl1, ctrl2, end)

        color = self._edge_color()
        pen = QPen(color)
        pen.setWidth(2)
        pen.setCosmetic(True)
        painter.setPen(pen)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawPath(path)

        angle = self._tangent_angle_at_end()
        arrow = self._arrow_points(end, angle)
        painter.setBrush(QBrush(color))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawPolygon(arrow)

        if self.bidirectional:
            reverse_angle = angle + math.pi
            arrow_rev = self._arrow_points(start, reverse_angle)
            painter.drawPolygon(arrow_rev)

    def update_position(self):
        self._update_position()
        self.update()
