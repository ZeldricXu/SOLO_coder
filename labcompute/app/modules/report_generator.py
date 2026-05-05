from typing import Dict, Any, List, Optional
from pathlib import Path
import datetime

from app.config.settings import settings
from app.modules.pdf_composer import PDFComposer, PDFComposerError
from app.modules.chart_renderer import ChartRenderer, ChartRendererError

class ReportGeneratorError(Exception):
    pass

class ReportGenerator:
    
    def __init__(self, reports_dir: str = None):
        if reports_dir is None:
            reports_dir = settings.REPORTS_DIR
        
        self.reports_dir = Path(reports_dir)
        self.pdf_composer = PDFComposer(reports_dir)
        self.chart_renderer = ChartRenderer()
    
    def generate_report(
        self,
        task_data: Dict[str, Any],
        result_data: Dict[str, Any] = None,
        output_filename: str = None,
        include_charts: bool = True
    ) -> str:
        try:
            if output_filename is None:
                timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                task_id = task_data.get('task_id', 'unknown')
                output_filename = f"report_{task_id}_{timestamp}.pdf"
            
            output_path = self.reports_dir / output_filename
            
            elements = self.pdf_composer.compose_full_report(
                task_data=task_data,
                result_data=result_data,
                include_charts=include_charts
            )
            
            self.pdf_composer.build_pdf(elements, output_path)
            
            return str(output_path)
            
        except PDFComposerError as e:
            raise ReportGeneratorError(f"PDF composition error: {str(e)}")
        except Exception as e:
            raise ReportGeneratorError(f"Failed to generate report: {str(e)}")
    
    def generate_report_bytes(
        self,
        task_data: Dict[str, Any],
        result_data: Dict[str, Any] = None,
        include_charts: bool = True
    ) -> bytes:
        try:
            elements = self.pdf_composer.compose_full_report(
                task_data=task_data,
                result_data=result_data,
                include_charts=include_charts
            )
            
            return self.pdf_composer.build_pdf_bytes(elements)
            
        except PDFComposerError as e:
            raise ReportGeneratorError(f"PDF composition error: {str(e)}")
        except Exception as e:
            raise ReportGeneratorError(f"Failed to generate report bytes: {str(e)}")
    
    def get_chart_renderer(self) -> ChartRenderer:
        return self.chart_renderer
    
    def get_pdf_composer(self) -> PDFComposer:
        return self.pdf_composer
