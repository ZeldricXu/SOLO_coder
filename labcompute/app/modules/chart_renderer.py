from reportlab.lib import colors
from reportlab.lib.units import inch, cm
from reportlab.graphics.shapes import Drawing, Line, Rect, Circle, Path, Polygon
from reportlab.graphics.charts.lineplots import LinePlot
from reportlab.graphics.charts.barcharts import VerticalBarChart
from reportlab.graphics.charts.piecharts import Pie
from typing import Dict, Any, List, Optional, Tuple
import numpy as np

class ChartRendererError(Exception):
    pass

class ChartType(str):
    LINE = "line"
    SCATTER = "scatter"
    BAR = "bar"
    PIE = "pie"
    HISTOGRAM = "histogram"

class ChartTheme:
    
    PRIMARY_BLUE = colors.HexColor('#3498db')
    SECONDARY_RED = colors.HexColor('#e74c3c')
    SUCCESS_GREEN = colors.HexColor('#2ecc71')
    WARNING_ORANGE = colors.HexColor('#f39c12')
    INFO_CYAN = colors.HexColor('#1abc9c')
    DARK_GRAY = colors.HexColor('#34495e')
    LIGHT_GRAY = colors.HexColor('#95a5a6')
    BACKGROUND = colors.HexColor('#f8f9fa')
    
    COLOR_PALETTE = [
        PRIMARY_BLUE,
        SECONDARY_RED,
        SUCCESS_GREEN,
        WARNING_ORANGE,
        INFO_CYAN,
        colors.HexColor('#9b59b6'),
        colors.HexColor('#1abc9c'),
        colors.HexColor('#e67e22'),
    ]

class ChartRenderer:
    
    DEFAULT_WIDTH = 450
    DEFAULT_HEIGHT = 250
    
    def __init__(self):
        self.theme = ChartTheme()
    
    def create_line_chart(
        self,
        x_values: List[float],
        y_values_list: List[List[float]],
        labels: Optional[List[str]] = None,
        x_label: str = "X",
        y_label: str = "Y",
        title: Optional[str] = None,
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            drawing = Drawing(width, height)
            
            if not x_values or not y_values_list:
                return self._create_empty_drawing(width, height, "No data")
            
            line_plot = LinePlot()
            
            all_data = []
            for y_vals in y_values_list:
                if len(y_vals) != len(x_values):
                    min_len = min(len(x_values), len(y_vals))
                    data_series = list(zip(x_values[:min_len], y_vals[:min_len]))
                else:
                    data_series = list(zip(x_values, y_vals))
                all_data.append(data_series)
            
            line_plot.data = all_data
            line_plot.x = 50
            line_plot.y = 50
            line_plot.height = height - 70
            line_plot.width = width - 70
            
            for i, line in enumerate(line_plot.lines):
                color_idx = i % len(self.theme.COLOR_PALETTE)
                line.strokeColor = self.theme.COLOR_PALETTE[color_idx]
                line.strokeWidth = 2
            
            line_plot.xLabel = x_label
            line_plot.yLabel = y_label
            
            drawing.add(line_plot)
            
            if labels and len(labels) > 1:
                legend_y = height - 30
                for i, label in enumerate(labels[:6]):
                    color_idx = i % len(self.theme.COLOR_PALETTE)
                    legend_x = 50 + i * 80
                    
                    circle = Circle(legend_x, legend_y, 5, fillColor=self.theme.COLOR_PALETTE[color_idx])
                    drawing.add(circle)
                    
                    from reportlab.graphics.charts.textlabels import Label
                    text_label = Label()
                    text_label.setOrigin(legend_x + 12, legend_y)
                    text_label.setText(label[:10])
                    text_label.fontSize = 8
                    text_label.fillColor = self.theme.DARK_GRAY
                    drawing.add(text_label)
            
            return drawing
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create line chart: {str(e)}")
    
    def create_scatter_plot(
        self,
        x_values: List[float],
        y_values: List[float],
        predicted_values: Optional[List[float]] = None,
        x_label: str = "X",
        y_label: str = "Y",
        title: Optional[str] = None,
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            drawing = Drawing(width, height)
            
            if not x_values or not y_values:
                return self._create_empty_drawing(width, height, "No data")
            
            chart_x = 50
            chart_y = 50
            chart_width = width - 70
            chart_height = height - 70
            
            min_x, max_x = min(x_values), max(x_values)
            min_y, max_y = min(y_values), max(y_values)
            
            if predicted_values:
                min_y = min(min_y, min(predicted_values))
                max_y = max(max_y, max(predicted_values))
            
            padding_x = (max_x - min_x) * 0.1 if max_x != min_x else 1
            padding_y = (max_y - min_y) * 0.1 if max_y != min_y else 1
            
            def to_pixel_x(val):
                return chart_x + ((val - (min_x - padding_x)) / ((max_x + padding_x) - (min_x - padding_x))) * chart_width
            
            def to_pixel_y(val):
                return chart_y + ((val - (min_y - padding_y)) / ((max_y + padding_y) - (min_y - padding_y))) * chart_height
            
            for x, y in zip(x_values, y_values):
                px = to_pixel_x(x)
                py = to_pixel_y(y)
                circle = Circle(px, py, 3, fillColor=self.theme.PRIMARY_BLUE, strokeColor=self.theme.PRIMARY_BLUE)
                drawing.add(circle)
            
            if predicted_values:
                sorted_indices = sorted(range(len(x_values)), key=lambda k: x_values[k])
                sorted_x = [x_values[i] for i in sorted_indices]
                sorted_pred = [predicted_values[i] for i in sorted_indices]
                
                path = Path(strokeColor=self.theme.SECONDARY_RED, strokeWidth=2)
                
                for i, (x, y) in enumerate(zip(sorted_x, sorted_pred)):
                    px = to_pixel_x(x)
                    py = to_pixel_y(y)
                    
                    if i == 0:
                        path.moveTo(px, py)
                    else:
                        path.lineTo(px, py)
                
                drawing.add(path)
            
            border = Rect(chart_x, chart_y, chart_width, chart_height, fillColor=None, strokeColor=self.theme.LIGHT_GRAY)
            drawing.add(border)
            
            return drawing
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create scatter plot: {str(e)}")
    
    def create_bar_chart(
        self,
        categories: List[str],
        values_list: List[List[float]],
        labels: Optional[List[str]] = None,
        title: Optional[str] = None,
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            drawing = Drawing(width, height)
            
            if not categories or not values_list:
                return self._create_empty_drawing(width, height, "No data")
            
            bar_chart = VerticalBarChart()
            
            bar_chart.x = 50
            bar_chart.y = 50
            bar_chart.height = height - 70
            bar_chart.width = width - 70
            
            bar_chart.data = values_list
            bar_chart.categoryAxis.categoryNames = categories
            
            for i, bar in enumerate(bar_chart.bars):
                color_idx = i % len(self.theme.COLOR_PALETTE)
                bar.fillColor = self.theme.COLOR_PALETTE[color_idx]
            
            bar_chart.valueAxis.labels.fontSize = 8
            bar_chart.categoryAxis.labels.fontSize = 8
            
            drawing.add(bar_chart)
            
            return drawing
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create bar chart: {str(e)}")
    
    def create_histogram(
        self,
        data: List[float],
        bins: int = 10,
        x_label: str = "Value",
        y_label: str = "Frequency",
        title: Optional[str] = None,
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            drawing = Drawing(width, height)
            
            if not data:
                return self._create_empty_drawing(width, height, "No data")
            
            counts, bin_edges = np.histogram(data, bins=bins)
            
            categories = []
            for i in range(len(counts)):
                categories.append(f"{bin_edges[i]:.2f}-{bin_edges[i+1]:.2f}")
            
            return self.create_bar_chart(
                categories=categories,
                values_list=[counts.tolist()],
                title=title,
                width=width,
                height=height
            )
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create histogram: {str(e)}")
    
    def create_ode_trajectory_chart(
        self,
        trajectory: List[Dict[str, Any]],
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            if not trajectory or len(trajectory) < 2:
                return self._create_empty_drawing(width, height, "No trajectory data")
            
            x_values = [p['t'] for p in trajectory]
            y_values_list = []
            
            first_point = trajectory[0].get('y', [])
            if isinstance(first_point, list):
                for i in range(len(first_point)):
                    y_values_list.append([p['y'][i] for p in trajectory])
            else:
                y_values_list.append([p['y'] for p in trajectory])
            
            labels = [f"y[{i}]" for i in range(len(y_values_list))] if len(y_values_list) > 1 else None
            
            return self.create_line_chart(
                x_values=x_values,
                y_values_list=y_values_list,
                labels=labels,
                x_label="Time (t)",
                y_label="Value (y)",
                width=width,
                height=height
            )
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create ODE trajectory chart: {str(e)}")
    
    def create_regression_chart(
        self,
        x_values: List[float],
        y_values: List[float],
        predicted_values: List[float],
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            return self.create_scatter_plot(
                x_values=x_values,
                y_values=y_values,
                predicted_values=predicted_values,
                x_label="X",
                y_label="Y",
                width=width,
                height=height
            )
        except Exception as e:
            raise ChartRendererError(f"Failed to create regression chart: {str(e)}")
    
    def create_distribution_chart(
        self,
        data: List[float],
        pdf_x: Optional[List[float]] = None,
        pdf_y: Optional[List[float]] = None,
        width: int = DEFAULT_WIDTH,
        height: int = DEFAULT_HEIGHT
    ) -> Drawing:
        try:
            drawing = Drawing(width, height)
            
            if not data:
                return self._create_empty_drawing(width, height, "No data")
            
            chart_x = 50
            chart_y = 50
            chart_width = width - 70
            chart_height = height - 70
            
            counts, bin_edges = np.histogram(data, bins=15)
            
            bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2
            
            max_count = max(counts)
            min_val, max_val = min(data), max(data)
            
            def to_pixel_x(val):
                return chart_x + ((val - min_val) / (max_val - min_val)) * chart_width
            
            def to_pixel_y(val):
                return chart_y + (val / max_count) * chart_height
            
            bar_width = (max_val - min_val) / len(counts) * 0.8
            
            for i, (count, center) in enumerate(zip(counts, bin_centers)):
                if count == 0:
                    continue
                
                px = to_pixel_x(center - bar_width/2)
                py = to_pixel_y(0)
                w = (bar_width / (max_val - min_val)) * chart_width
                h = to_pixel_y(count) - py
                
                rect = Rect(px, py, w, h, fillColor=self.theme.PRIMARY_BLUE, strokeColor=self.theme.PRIMARY_BLUE)
                drawing.add(rect)
            
            if pdf_x and pdf_y and len(pdf_x) > 1:
                max_pdf = max(pdf_y)
                scale_factor = max_count / max_pdf if max_pdf > 0 else 1
                
                scaled_pdf = [y * scale_factor for y in pdf_y]
                
                path = Path(strokeColor=self.theme.SECONDARY_RED, strokeWidth=2)
                
                for i, (x, y) in enumerate(zip(pdf_x, scaled_pdf)):
                    px = to_pixel_x(x)
                    py = to_pixel_y(y)
                    
                    if i == 0:
                        path.moveTo(px, py)
                    else:
                        path.lineTo(px, py)
                
                drawing.add(path)
            
            border = Rect(chart_x, chart_y, chart_width, chart_height, fillColor=None, strokeColor=self.theme.LIGHT_GRAY)
            drawing.add(border)
            
            return drawing
            
        except Exception as e:
            raise ChartRendererError(f"Failed to create distribution chart: {str(e)}")
    
    def _create_empty_drawing(self, width: int, height: int, message: str) -> Drawing:
        drawing = Drawing(width, height)
        
        rect = Rect(0, 0, width, height, fillColor=self.theme.BACKGROUND, strokeColor=self.theme.LIGHT_GRAY)
        drawing.add(rect)
        
        from reportlab.graphics.charts.textlabels import Label
        label = Label()
        label.setOrigin(width/2, height/2)
        label.setText(message)
        label.fontSize = 12
        label.fillColor = self.theme.LIGHT_GRAY
        label.textAnchor = 'middle'
        drawing.add(label)
        
        return drawing
    
    def render_chart_for_result(
        self,
        task_type: str,
        result_data: Dict[str, Any]
    ) -> Optional[Drawing]:
        try:
            if task_type == 'ode_solve':
                trajectory = result_data.get('trajectory', [])
                if trajectory and len(trajectory) > 1:
                    return self.create_ode_trajectory_chart(trajectory)
            
            elif task_type == 'stats_regression':
                x_values = result_data.get('x_values', [])
                y_values = result_data.get('y_values', [])
                predicted = result_data.get('predicted_values', [])
                if x_values and y_values:
                    return self.create_regression_chart(x_values, y_values, predicted)
            
            elif task_type == 'stats_descriptive':
                pass
            
            elif task_type == 'stats_distribution':
                data_hist = result_data.get('data_histogram', {})
                pdf_x = result_data.get('pdf_x', [])
                pdf_y = result_data.get('pdf_y', [])
                
                if data_hist and 'counts' in data_hist:
                    counts = data_hist.get('counts', [])
                    if counts:
                        pass
            
            return None
            
        except Exception as e:
            raise ChartRendererError(f"Failed to render chart for result: {str(e)}")
