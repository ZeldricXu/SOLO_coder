from typing import List, Dict, Optional

from PyQt6.QtWidgets import QGraphicsObject, QGraphicsItem, QStyleOptionGraphicsItem, QWidget
from PyQt6.QtCore import Qt, QRectF, QPointF, pyqtSignal
from PyQt6.QtGui import QPainter, QPainterPath, QColor, QPen, QBrush, QFont, QFontMetrics


DEFAULT_COLORS = [
    "#4A90D9",
    "#E74C3C",
    "#2ECC71",
    "#F39C12",
    "#9B59B6",
    "#1ABC9C",
    "#E67E22",
    "#3498DB",
]

BASE_WIDTH = 120
BASE_HEIGHT = 60
MIN_SCALE = 1.0
MAX_SCALE = 5.0
ROUND_RADIUS = 10


class GraphNode(QGraphicsObject):
    positionChanged = pyqtSignal(int, float, float)

    def __init__(
        self,
        note_id: int,
        title: str,
        citation_count: int = 0,
        tags: Optional[List[Dict]] = None,
        is_isolated: bool = False,
        parent: Optional[QGraphicsItem] = None,
    ):
        super().__init__(parent)
        self.note_id = note_id
        self.title = title
        self.citation_count = citation_count
        self.tags = tags or []
        self.is_isolated = is_isolated
        self._selected = False
        self._setup_size()
        self._setup_color()
        self.setFlags(
            QGraphicsItem.GraphicsItemFlag.ItemIsSelectable
            | QGraphicsItem.GraphicsItemFlag.ItemIsMovable
            | QGraphicsItem.GraphicsItemFlag.ItemSendsGeometryChanges
        )
        self.setAcceptHoverEvents(True)
        self._move_dirty = False

    def _setup_size(self):
        scale = MIN_SCALE
        if self.citation_count > 0:
            scale = min(MAX_SCALE, MIN_SCALE + (self.citation_count - 1) * 0.5)
            scale = min(MAX_SCALE, max(MIN_SCALE, scale))
        self._width = BASE_WIDTH * scale
        self._height = BASE_HEIGHT * scale

    def _setup_color(self):
        if self.tags:
            color = self.tags[0].get("color", DEFAULT_COLORS[0])
        else:
            color = DEFAULT_COLORS[0]
        self._color = QColor(color)
        self._color.setAlpha(200)

    def get_display_title(self) -> str:
        if len(self.title) > 15:
            return self.title[:15] + "..."
        return self.title

    def boundingRect(self) -> QRectF:
        pen_width = 3.0 if self._selected else 2.0
        return QRectF(
            -self._width / 2 - pen_width,
            -self._height / 2 - pen_width,
            self._width + pen_width * 2,
            self._height + pen_width * 2,
        )

    def shape(self) -> QPainterPath:
        path = QPainterPath()
        path.addRoundedRect(
            -self._width / 2,
            -self._height / 2,
            self._width,
            self._height,
            ROUND_RADIUS,
            ROUND_RADIUS,
        )
        return path

    def paint(self, painter: QPainter, option: QStyleOptionGraphicsItem, widget: Optional[QWidget] = None):
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        if self._selected:
            pen = QPen(QColor("#FF6B35"))
            pen.setWidth(3)
        else:
            pen = QPen(QColor("#333333"))
            pen.setWidth(2)

        if self.is_isolated:
            pen.setStyle(Qt.PenStyle.DashLine)

        painter.setPen(pen)
        painter.setBrush(QBrush(self._color))

        rect = QRectF(
            -self._width / 2,
            -self._height / 2,
            self._width,
            self._height,
        )
        painter.drawRoundedRect(rect, ROUND_RADIUS, ROUND_RADIUS)

        if self.is_isolated:
            indicator_color = QColor("#F1C40F")
            indicator_color.setAlpha(220)
            painter.setBrush(QBrush(indicator_color))
            painter.setPen(Qt.PenStyle.NoPen)
            indicator_r = 8
            painter.drawEllipse(
                QPointF(self._width / 2 - 4, -self._height / 2 + 4),
                indicator_r,
                indicator_r,
            )

        text_color = QColor("#FFFFFF")
        painter.setPen(text_color)
        display_title = self.get_display_title()
        font = QFont()
        font.setBold(True)
        font_scale = min(1.0, self._width / BASE_WIDTH)
        font_size = max(8, int(11 * font_scale))
        font.setPointSize(font_size)
        painter.setFont(font)

        text_rect = QRectF(
            -self._width / 2 + 8,
            -self._height / 2 + 4,
            self._width - 16,
            self._height - 8,
        )
        painter.drawText(
            text_rect,
            Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap,
            display_title,
        )

    def itemChange(self, change: QGraphicsItem.GraphicsItemChange, value):
        if change == QGraphicsItem.GraphicsItemChange.ItemSelectedHasChanged:
            self._selected = self.isSelected()
            self.update()
        elif change == QGraphicsItem.GraphicsItemChange.ItemPositionChange and self.scene():
            self._move_dirty = True
        return super().itemChange(change, value)

    def mouseReleaseEvent(self, event):
        super().mouseReleaseEvent(event)
        if self._move_dirty:
            self._move_dirty = False
            pos = self.pos()
            self.positionChanged.emit(self.note_id, pos.x(), pos.y())

    def set_isolated(self, isolated: bool):
        if self.is_isolated != isolated:
            self.is_isolated = isolated
            self.update()
