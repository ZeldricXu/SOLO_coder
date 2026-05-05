from reportlab.lib import colors
from reportlab.lib.pagesizes import letter, A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch, cm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, 
    Image, PageBreak, KeepTogether
)
from reportlab.graphics.shapes import Drawing
from typing import Dict, Any, List, Optional, Union
from pathlib import Path
import io
import datetime
import numpy as np

from app.config.settings import settings
from app.modules.chart_renderer import ChartRenderer, ChartRendererError

class PDFComposerError(Exception):
    pass

class PDFComposer:
    
    PAGE_SIZE = A4
    
    def __init__(self, reports_dir: str = None):
        if reports_dir is None:
            reports_dir = settings.REPORTS_DIR
        
        self.reports_dir = Path(reports_dir)
        self.reports_dir.mkdir(parents=True, exist_ok=True)
        
        self.styles = getSampleStyleSheet()
        self._setup_custom_styles()
        self.chart_renderer = ChartRenderer()
    
    def _setup_custom_styles(self):
        self.styles.add(ParagraphStyle(
            name='CustomTitle',
            parent=self.styles['Title'],
            fontSize=24,
            spaceAfter=20,
            textColor=colors.HexColor('#2c3e50')
        ))
        
        self.styles.add(ParagraphStyle(
            name='CustomHeading',
            parent=self.styles['Heading2'],
            fontSize=14,
            spaceBefore=15,
            spaceAfter=10,
            textColor=colors.HexColor('#34495e')
        ))
        
        self.styles.add(ParagraphStyle(
            name='CustomBody',
            parent=self.styles['BodyText'],
            fontSize=10,
            leading=14,
            spaceAfter=6
        ))
        
        self.styles.add(ParagraphStyle(
            name='HighlightBox',
            parent=self.styles['BodyText'],
            fontSize=10,
            backColor=colors.HexColor('#f8f9fa'),
            borderPadding=(6, 6, 6, 6),
            borderRadius=4
        ))
        
        self.styles.add(ParagraphStyle(
            name='SmallLabel',
            parent=self.styles['BodyText'],
            fontSize=8,
            textColor=colors.HexColor('#7f8c8d')
        ))
    
    def create_document(
        self,
        output_path: Union[str, Path],
        page_size=PAGE_SIZE
    ) -> SimpleDocTemplate:
        return SimpleDocTemplate(
            str(output_path),
            pagesize=page_size,
            rightMargin=72,
            leftMargin=72,
            topMargin=72,
            bottomMargin=72
        )
    
    def compose_header(
        self,
        task_data: Dict[str, Any],
        include_timestamp: bool = True
    ) -> List[Any]:
        elements = []
        
        elements.append(Paragraph("LabCompute", self.styles['CustomTitle']))
        elements.append(Paragraph("Scientific Computation Report", self.styles['Heading2']))
        elements.append(Spacer(1, 12))
        
        task_type = task_data.get('task_type', 'Unknown')
        task_type_display = {
            'matrix_multiply': 'Matrix Multiplication',
            'matrix_inverse': 'Matrix Inversion',
            'matrix_eigenvalues': 'Eigenvalue Decomposition',
            'matrix_transpose': 'Matrix Transpose',
            'matrix_add': 'Matrix Addition',
            'ode_solve': 'Differential Equation Solving',
            'stats_descriptive': 'Descriptive Statistics',
            'stats_regression': 'Linear Regression',
            'stats_ttest': 'T-Test',
            'stats_correlation': 'Correlation Analysis',
            'stats_distribution': 'Probability Distribution'
        }.get(task_type, task_type)
        
        elements.append(Paragraph(f"<b>Analysis Type:</b> {task_type_display}", self.styles['CustomBody']))
        elements.append(Paragraph(f"<b>Task ID:</b> {task_data.get('task_id', 'N/A')}", self.styles['CustomBody']))
        
        if include_timestamp:
            elements.append(Paragraph(
                f"<b>Generated:</b> {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
                self.styles['CustomBody']
            ))
        
        return elements
    
    def compose_task_summary(
        self,
        task_data: Dict[str, Any],
        result_data: Optional[Dict[str, Any]] = None
    ) -> List[Any]:
        elements = []
        
        elements.append(Paragraph("Task Summary", self.styles['CustomHeading']))
        
        summary_data = [
            ['Parameter', 'Value'],
            ['Task Status', task_data.get('status', 'Unknown')],
            ['Progress', f"{task_data.get('progress', 0)}%"],
            ['Created At', task_data.get('created_at', 'N/A')],
        ]
        
        if result_data:
            exec_time = result_data.get('execution_time_seconds')
            if exec_time:
                summary_data.append(['Execution Time', f"{exec_time:.4f} seconds"])
            
            if 'operation' in result_data:
                summary_data.append(['Operation', result_data['operation']])
            
            if 'used_blocking' in result_data:
                summary_data.append(['Used Blocking', 'Yes' if result_data['used_blocking'] else 'No'])
            
            if 'used_memmap' in result_data:
                summary_data.append(['Used Memory Mapping', 'Yes' if result_data['used_memmap'] else 'No'])
            
            if 'is_adaptive' in result_data:
                summary_data.append(['Adaptive Step Size', 'Yes' if result_data['is_adaptive'] else 'No'])
            
            if 'min_step_used' in result_data and result_data['min_step_used'] is not None:
                summary_data.append(['Min Step Used', f"{result_data['min_step_used']:.2e}"])
            
            if 'max_step_used' in result_data and result_data['max_step_used'] is not None:
                summary_data.append(['Max Step Used', f"{result_data['max_step_used']:.2e}"])
            
            if 'actual_steps' in result_data:
                summary_data.append(['Actual Steps', str(result_data['actual_steps'])])
            
            if 'rejected_steps' in result_data:
                summary_data.append(['Rejected Steps', str(result_data['rejected_steps'])])
        
        table = Table(summary_data, colWidths=[2*inch, 3*inch])
        table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
            ('FONTSIZE', (0, 0), (-1, 0), 10),
            ('BOTTOMPADDING', (0, 0), (-1, 0), 8),
            ('BACKGROUND', (0, 1), (-1, -1), colors.HexColor('#f8f9fa')),
            ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ('FONTSIZE', (0, 1), (-1, -1), 9),
            ('TOPPADDING', (0, 1), (-1, -1), 6),
            ('BOTTOMPADDING', (0, 1), (-1, -1), 6),
        ]))
        
        elements.append(table)
        elements.append(Spacer(1, 12))
        
        return elements
    
    def compose_matrix_results(
        self,
        result_data: Dict[str, Any]
    ) -> List[Any]:
        elements = []
        
        operation = result_data.get('operation', 'Unknown')
        elements.append(Paragraph(f"Operation: {operation}", self.styles['CustomBody']))
        elements.append(Spacer(1, 6))
        
        if 'shape' in result_data:
            elements.append(Paragraph(f"Result Shape: {result_data['shape']}", self.styles['CustomBody']))
            elements.append(Spacer(1, 6))
        
        if 'determinant' in result_data:
            elements.append(Paragraph(f"Determinant: {result_data['determinant']:.6f}", self.styles['CustomBody']))
            elements.append(Spacer(1, 6))
        
        if 'eigenvalues_real' in result_data:
            elements.append(Paragraph("Eigenvalues:", self.styles['CustomHeading']))
            ev_data = [['Index', 'Real', 'Imaginary']]
            for i, (real, imag) in enumerate(zip(result_data['eigenvalues_real'], result_data['eigenvalues_imaginary'])):
                ev_data.append([str(i + 1), f"{real:.6f}", f"{imag:.6f}"])
            
            table = Table(ev_data, colWidths=[1*inch, 1.5*inch, 1.5*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
            elements.append(Spacer(1, 6))
            
            if result_data.get('is_real'):
                elements.append(Paragraph("Note: All eigenvalues are real.", self.styles['CustomBody']))
        
        return elements
    
    def compose_ode_results(
        self,
        result_data: Dict[str, Any]
    ) -> List[Any]:
        elements = []
        
        status = result_data.get('status', 'Unknown')
        status_display = {
            'stable': '✓ Stable',
            'diverged': '✗ Diverged',
            'timeout': '⏱ Timeout',
            'param_error': '✗ Parameter Error',
            'max_steps_exceeded': '⚠ Max Steps Exceeded'
        }.get(status, status)
        
        elements.append(Paragraph(f"Solution Status: {status_display}", self.styles['CustomBody']))
        elements.append(Spacer(1, 6))
        
        if 'error_message' in result_data and result_data['error_message']:
            elements.append(Paragraph(
                f"<font color='red'>Error: {result_data['error_message']}</font>",
                self.styles['CustomBody']
            ))
            elements.append(Spacer(1, 6))
        
        elements.append(Paragraph(f"Total Steps: {result_data.get('total_steps', 0)}", self.styles['CustomBody']))
        elements.append(Spacer(1, 6))
        elements.append(Paragraph(f"Method Used: {result_data.get('method', 'Unknown')}", self.styles['CustomBody']))
        elements.append(Spacer(1, 6))
        elements.append(Paragraph(f"Equation Type: {result_data.get('equation_type', 'Unknown')}", self.styles['CustomBody']))
        elements.append(Spacer(1, 6))
        
        if 'critical_time' in result_data and result_data['critical_time']:
            elements.append(Paragraph(
                f"Critical Time (divergence): {result_data['critical_time']:.4f}",
                self.styles['CustomBody']
            ))
            elements.append(Spacer(1, 6))
        
        trajectory = result_data.get('trajectory', [])
        if trajectory:
            elements.append(Paragraph("Trajectory Preview (First 10 points):", self.styles['CustomHeading']))
            
            preview_data = [['Time']]
            first_point = trajectory[0].get('y', [])
            for i in range(len(first_point)):
                preview_data[0].append(f"y[{i}]")
            
            for point in trajectory[:10]:
                row = [f"{point['t']:.4f}"]
                for y_val in point.get('y', []):
                    row.append(f"{y_val:.6f}")
                preview_data.append(row)
            
            if len(trajectory) > 10:
                preview_data.append(['...'] + ['...'] * len(first_point))
            
            table = Table(preview_data)
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
                ('FONTSIZE', (0, 0), (-1, -1), 8),
            ]))
            elements.append(table)
        
        return elements
    
    def compose_stats_results(
        self,
        task_type: str,
        result_data: Dict[str, Any]
    ) -> List[Any]:
        elements = []
        
        if task_type == 'stats_descriptive':
            elements.append(Paragraph("Descriptive Statistics:", self.styles['CustomHeading']))
            
            stats_data = [
                ['Metric', 'Value'],
                ['Count', str(result_data.get('count', 'N/A'))],
                ['Mean', f"{result_data.get('mean', 0):.6f}"],
                ['Median', f"{result_data.get('median', 0):.6f}"],
                ['Mode', str(result_data.get('mode', 'N/A'))],
                ['Std Deviation', f"{result_data.get('std_dev', 0):.6f}"],
                ['Variance', f"{result_data.get('variance', 0):.6f}"],
                ['Minimum', f"{result_data.get('min', 0):.6f}"],
                ['Maximum', f"{result_data.get('max', 0):.6f}"],
                ['Range', f"{result_data.get('range', 0):.6f}"],
                ['Q1 (25%)', f"{result_data.get('q1', 0):.6f}"],
                ['Q3 (75%)', f"{result_data.get('q3', 0):.6f}"],
                ['IQR', f"{result_data.get('iqr', 0):.6f}"],
                ['Skewness', f"{result_data.get('skewness', 0):.6f}"],
                ['Kurtosis', f"{result_data.get('kurtosis', 0):.6f}"],
            ]
            
            table = Table(stats_data, colWidths=[2*inch, 2*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
        
        elif task_type == 'stats_regression':
            elements.append(Paragraph("Linear Regression Results:", self.styles['CustomHeading']))
            
            elements.append(Paragraph(f"<b>Equation:</b> {result_data.get('equation', 'N/A')}", self.styles['CustomBody']))
            elements.append(Spacer(1, 6))
            
            reg_data = [
                ['Parameter', 'Value'],
                ['Slope', f"{result_data.get('slope', 0):.6f}"],
                ['Intercept', f"{result_data.get('intercept', 0):.6f}"],
                ['R (Correlation)', f"{result_data.get('r_value', 0):.6f}"],
                ['R² (Coefficient)', f"{result_data.get('r_squared', 0):.6f}"],
                ['P-Value', f"{result_data.get('p_value', 0):.6f}"],
                ['Std Error', f"{result_data.get('std_err', 0):.6f}"],
            ]
            
            table = Table(reg_data, colWidths=[2*inch, 2*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
        
        elif task_type == 'stats_ttest':
            elements.append(Paragraph("T-Test Results:", self.styles['CustomHeading']))
            
            ttest_data = [
                ['Parameter', 'Value'],
                ['Test Type', result_data.get('test_type', 'N/A')],
                ['T-Statistic', f"{result_data.get('t_statistic', 0):.6f}"],
                ['P-Value', f"{result_data.get('p_value', 0):.6f}"],
            ]
            
            if 'sample_mean' in result_data:
                ttest_data.append(['Sample Mean', f"{result_data.get('sample_mean', 0):.6f}"])
            if 'popmean' in result_data:
                ttest_data.append(['Population Mean', f"{result_data.get('popmean', 0):.6f}"])
            
            table = Table(ttest_data, colWidths=[2*inch, 2*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
            
            p_value = result_data.get('p_value', 1.0)
            if p_value < 0.05:
                elements.append(Paragraph(
                    f"<font color='green'><b>Conclusion:</b> Statistically significant (p < 0.05)</font>",
                    self.styles['CustomBody']
                ))
            else:
                elements.append(Paragraph(
                    f"<font color='orange'><b>Conclusion:</b> Not statistically significant (p >= 0.05)</font>",
                    self.styles['CustomBody']
                ))
        
        elif task_type == 'stats_correlation':
            elements.append(Paragraph("Correlation Analysis Results:", self.styles['CustomHeading']))
            
            corr_data = [
                ['Parameter', 'Value'],
                ['Method', result_data.get('method', 'N/A')],
                ['Correlation', f"{result_data.get('correlation', 0):.6f}"],
                ['Absolute Value', f"{result_data.get('absolute_value', 0):.6f}"],
                ['P-Value', f"{result_data.get('p_value', 0):.6f}"],
                ['Strength', result_data.get('strength', 'N/A')],
            ]
            
            table = Table(corr_data, colWidths=[2*inch, 2*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
        
        elif task_type == 'stats_distribution':
            elements.append(Paragraph("Probability Distribution Results:", self.styles['CustomHeading']))
            
            dist_data = [
                ['Parameter', 'Value'],
                ['Distribution', result_data.get('distribution', 'N/A')],
                ['Parameters', str(result_data.get('parameters', []))],
                ['KS Statistic', f"{result_data.get('ks_statistic', 0):.6f}"],
                ['KS P-Value', f"{result_data.get('ks_p_value', 0):.6f}"],
            ]
            
            table = Table(dist_data, colWidths=[2*inch, 2*inch])
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#34495e')),
                ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
                ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
                ('GRID', (0, 0), (-1, -1), 1, colors.HexColor('#dee2e6')),
            ]))
            elements.append(table)
        
        return elements
    
    def compose_chart(
        self,
        task_type: str,
        result_data: Dict[str, Any]
    ) -> List[Any]:
        elements = []
        
        try:
            chart = self.chart_renderer.render_chart_for_result(task_type, result_data)
            
            if chart:
                elements.append(PageBreak())
                
                chart_title = ""
                if task_type == 'ode_solve':
                    chart_title = "Solution Trajectory"
                elif task_type == 'stats_regression':
                    chart_title = "Regression Plot"
                elif task_type == 'stats_distribution':
                    chart_title = "Distribution Plot"
                
                if chart_title:
                    elements.append(Paragraph(chart_title, self.styles['CustomHeading']))
                
                elements.append(chart)
            
        except ChartRendererError as e:
            elements.append(Paragraph(
                f"Note: Chart generation skipped due to error: {str(e)}",
                self.styles['CustomBody']
            ))
        
        return elements
    
    def compose_footer(self) -> List[Any]:
        elements = []
        
        elements.append(Spacer(1, 20))
        elements.append(Paragraph("---", self.styles['CustomBody']))
        elements.append(Paragraph("LabCompute Platform - Scientific Computation Report", self.styles['CustomBody']))
        elements.append(Paragraph(
            f"Generated at: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            self.styles['CustomBody']
        ))
        
        return elements
    
    def compose_full_report(
        self,
        task_data: Dict[str, Any],
        result_data: Optional[Dict[str, Any]] = None,
        include_charts: bool = True
    ) -> List[Any]:
        elements = []
        
        elements.extend(self.compose_header(task_data))
        elements.append(Spacer(1, 12))
        elements.extend(self.compose_task_summary(task_data, result_data))
        
        if result_data:
            elements.append(PageBreak())
            elements.append(Paragraph("Computation Results", self.styles['CustomHeading']))
            
            task_type = task_data.get('task_type', '')
            
            if task_type.startswith('matrix_'):
                elements.extend(self.compose_matrix_results(result_data))
            elif task_type == 'ode_solve':
                elements.extend(self.compose_ode_results(result_data))
            elif task_type.startswith('stats_'):
                elements.extend(self.compose_stats_results(task_type, result_data))
            
            if include_charts:
                elements.extend(self.compose_chart(task_type, result_data))
        
        elements.extend(self.compose_footer())
        
        return elements
    
    def build_pdf(
        self,
        elements: List[Any],
        output_path: Union[str, Path]
    ) -> str:
        try:
            doc = self.create_document(output_path)
            doc.build(elements)
            return str(output_path)
        except Exception as e:
            raise PDFComposerError(f"Failed to build PDF: {str(e)}")
    
    def build_pdf_bytes(
        self,
        elements: List[Any]
    ) -> bytes:
        try:
            buffer = io.BytesIO()
            
            temp_path = self.reports_dir / f"temp_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S_%f')}.pdf"
            
            doc = SimpleDocTemplate(
                str(temp_path),
                pagesize=self.PAGE_SIZE,
                rightMargin=72,
                leftMargin=72,
                topMargin=72,
                bottomMargin=72
            )
            
            doc.build(elements)
            
            with open(temp_path, 'rb') as f:
                buffer.write(f.read())
            
            if temp_path.exists():
                temp_path.unlink()
            
            buffer.seek(0)
            return buffer.getvalue()
            
        except Exception as e:
            raise PDFComposerError(f"Failed to build PDF bytes: {str(e)}")
