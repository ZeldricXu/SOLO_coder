from typing import Optional

from PyQt6.QtWidgets import QGraphicsView, QGraphicsScene
from PyQt6.QtCore import Qt, pyqtSignal, QPointF, QRectF
from PyQt6.QtGui import QPainter, QMouseEvent, QWheelEvent, QPen, QColor


class GraphView(QGraphicsView):
    nodeDoubleClicked = pyqtSignal(int)

    MIN_SCALE = 0.2
    MAX_SCALE = 5.0
    GRID_SIZE = 20

    def __init__(self, scene: Optional[QGraphicsScene] = None, parent=None):
        if scene is None:
            scene = QGraphicsScene()
        super().__init__(scene, parent)
        self._scale_factor = 1.0
        self._pan_start: Optional[QPointF] = None
        self._setup_ui()

    def _setup_ui(self):
        self.setRenderHints(
            QPainter.RenderHint.Antialiasing
            | QPainter.RenderHint.SmoothPixmapTransform
            | QPainter.RenderHint.TextAntialiasing
        )
        self.setTransformationAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setResizeAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setDragMode(QGraphicsView.DragMode.NoDrag)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setBackgroundBrush(QColor("#F5F5F5"))
        self.setMouseTracking(True)

    def get_scale(self) -> float:
        return self._scale_factor

    def drawBackground(self, painter: QPainter, rect: QRectF):
        super().drawBackground(painter, rect)
        grid_pen = QPen(QColor("#E0E0E0"))
        grid_pen.setWidth(1)
        painter.setPen(grid_pen)

        left = int(rect.left()) - (int(rect.left()) % self.GRID_SIZE)
        top = int(rect.top()) - (int(rect.top()) % self.GRID_SIZE)

        lines = []
        x = left
        while x < rect.right():
            painter.drawLine(x, int(rect.top()), x, int(rect.bottom()))
            x += self.GRID_SIZE
        y = top
        while y < rect.bottom():
            painter.drawLine(int(rect.left()), y, int(rect.right()), y)
            y += self.GRID_SIZE

    def wheelEvent(self, event: QWheelEvent):
        if event.angleDelta().y() == 0:
            return
        if event.angleDelta().y() > 0:
            new_scale = self._scale_factor * 1.15
        else:
            new_scale = self._scale_factor * 0.87
        new_scale = max(self.MIN_SCALE, min(self.MAX_SCALE, new_scale))
        factor = new_scale / self._scale_factor
        self.scale(factor, factor)
        self._scale_factor = new_scale

    def mousePressEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.MiddleButton or (
            event.button() == Qt.MouseButton.LeftButton
            and event.modifiers() & Qt.KeyboardModifier.ControlModifier
        ):
            self._pan_start = event.position()
            self.setCursor(Qt.CursorShape.ClosedHandCursor)
            event.accept()
            return
        if event.button() == Qt.MouseButton.LeftButton:
            item = self.itemAt(event.position().toPoint())
            if item is None:
                self._pan_start = event.position()
                self.setCursor(Qt.CursorShape.ClosedHandCursor)
                event.accept()
                return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event: QMouseEvent):
        if self._pan_start is not None:
            delta = event.position() - self._pan_start
            self._pan_start = event.position()
            self.horizontalScrollBar().setValue(
                int(self.horizontalScrollBar().value() - delta.x())
            )
            self.verticalScrollBar().setValue(
                int(self.verticalScrollBar().value() - delta.y())
            )
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event: QMouseEvent):
        if self._pan_start is not None:
            self._pan_start = None
            self.setCursor(Qt.CursorShape.ArrowCursor)
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.LeftButton:
            item = self.itemAt(event.position().toPoint())
            if item is not None:
                node = item
                while node is not None and not hasattr(node, "note_id"):
                    node = node.parentItem()
                if node is not None and hasattr(node, "note_id"):
                    self.nodeDoubleClicked.emit(node.note_id)
                    event.accept()
                    return
        super().mouseDoubleClickEvent(event)

    def reset_view(self):
        self.resetTransform()
        self._scale_factor = 1.0
        self.fitInView(self.sceneRect(), Qt.AspectRatioMode.KeepAspectRatio)

    def zoom_in(self):
        new_scale = min(self.MAX_SCALE, self._scale_factor * 1.2)
        factor = new_scale / self._scale_factor
        self.scale(factor, factor)
        self._scale_factor = new_scale

    def zoom_out(self):
        new_scale = max(self.MIN_SCALE, self._scale_factor * 0.8)
        factor = new_scale / self._scale_factor
        self.scale(factor, factor)
        self._scale_factor = new_scale

    def reset_zoom(self):
        self.resetTransform()
        self._scale_factor = 1.0
