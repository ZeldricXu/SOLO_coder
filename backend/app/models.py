import json
import uuid
from datetime import datetime
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, asdict
from enum import Enum

class FieldType(str, Enum):
    SINGLE_CHOICE = "single_choice"
    MULTIPLE_CHOICE = "multiple_choice"
    NUMERIC = "numeric"
    TEXT = "text"
    DATE = "date"

@dataclass
class Field:
    field_id: str
    field_name: str
    field_type: FieldType
    options: Optional[List[str]] = None
    range: Optional[List[float]] = None
    
    def to_dict(self) -> Dict[str, Any]:
        result = {
            "field_id": self.field_id,
            "field_name": self.field_name,
            "field_type": self.field_type.value
        }
        if self.options:
            result["options"] = self.options
        if self.range:
            result["range"] = self.range
        return result

@dataclass
class SurveyData:
    survey_id: str
    survey_name: str
    total_responses: int
    fields: List[Field]
    imported_at: str
    file_path: str
    cleaning_config: Optional[Dict[str, Any]] = None
    cleaning_stats: Optional[Dict[str, Any]] = None
    cleaned_rows: Optional[int] = None
    
    def to_dict(self) -> Dict[str, Any]:
        result = {
            "survey_id": self.survey_id,
            "survey_name": self.survey_name,
            "total_responses": self.total_responses,
            "fields": [f.to_dict() for f in self.fields],
            "imported_at": self.imported_at,
            "file_path": self.file_path
        }
        if self.cleaning_config:
            result["cleaning_config"] = self.cleaning_config
        if self.cleaning_stats:
            result["cleaning_stats"] = self.cleaning_stats
        if self.cleaned_rows is not None:
            result["cleaned_rows"] = self.cleaned_rows
        return result

@dataclass
class CrossTableCell:
    row: str
    col_values: Dict[str, Any]

@dataclass
class SignificanceResult:
    test_type: str
    p_value: float
    significant: bool
    details: Optional[Dict[str, Any]] = None
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "test_type": self.test_type,
            "p_value": self.p_value,
            "significant": self.significant,
            "details": self.details or {}
        }

@dataclass
class CrossAnalysisResult:
    analysis_id: str
    survey_id: str
    variables: List[str]
    cross_table: List[CrossTableCell]
    significance: Optional[SignificanceResult]
    chart_config: Dict[str, Any]
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "analysis_id": self.analysis_id,
            "survey_id": self.survey_id,
            "variables": self.variables,
            "cross_table": [
                {"row": cell.row, "col_values": cell.col_values} 
                for cell in self.cross_table
            ],
            "significance": self.significance.to_dict() if self.significance else None,
            "chart_config": self.chart_config
        }

@dataclass
class FrequencyItem:
    value: Any
    count: int
    percentage: float

@dataclass
class FrequencyResult:
    field_id: str
    field_name: str
    frequencies: List[FrequencyItem]
    total_valid: int
    missing_count: int
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "field_id": self.field_id,
            "field_name": self.field_name,
            "frequencies": [
                {"value": f.value, "count": f.count, "percentage": f.percentage}
                for f in self.frequencies
            ],
            "total_valid": self.total_valid,
            "missing_count": self.missing_count
        }

@dataclass
class DescriptiveStats:
    field_id: str
    field_name: str
    count: int
    mean: float
    median: float
    std: float
    min: float
    max: float
    q25: float
    q75: float
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "field_id": self.field_id,
            "field_name": self.field_name,
            "count": self.count,
            "mean": self.mean,
            "median": self.median,
            "std": self.std,
            "min": self.min,
            "max": self.max,
            "q25": self.q25,
            "q75": self.q75
        }

@dataclass
class ReportSection:
    section_type: str
    title: str
    content: str
    chart_config: Optional[Dict[str, Any]] = None
    data: Optional[Dict[str, Any]] = None
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "section_type": self.section_type,
            "title": self.title,
            "content": self.content,
            "chart_config": self.chart_config,
            "data": self.data
        }

@dataclass
class Report:
    report_id: str
    survey_id: str
    title: str
    created_at: str
    sections: List[ReportSection]
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "report_id": self.report_id,
            "survey_id": self.survey_id,
            "title": self.title,
            "created_at": self.created_at,
            "sections": [s.to_dict() for s in self.sections]
        }

class SurveyStore:
    _instance = None
    _surveys: Dict[str, SurveyData] = {}
    _analysis_results: Dict[str, CrossAnalysisResult] = {}
    _frequency_results: Dict[str, FrequencyResult] = {}
    _descriptive_results: Dict[str, DescriptiveStats] = {}
    _reports: Dict[str, Report] = {}
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def save_survey(self, survey: SurveyData) -> None:
        self._surveys[survey.survey_id] = survey
    
    def get_survey(self, survey_id: str) -> Optional[SurveyData]:
        return self._surveys.get(survey_id)
    
    def save_cross_analysis(self, result: CrossAnalysisResult) -> None:
        self._analysis_results[result.analysis_id] = result
    
    def get_cross_analysis(self, analysis_id: str) -> Optional[CrossAnalysisResult]:
        return self._analysis_results.get(analysis_id)
    
    def save_frequency(self, result: FrequencyResult) -> None:
        key = f"{result.field_id}"
        self._frequency_results[key] = result
    
    def save_descriptive(self, result: DescriptiveStats) -> None:
        key = f"{result.field_id}"
        self._descriptive_results[key] = result
    
    def save_report(self, report: Report) -> None:
        self._reports[report.report_id] = report
    
    def get_report(self, report_id: str) -> Optional[Report]:
        return self._reports.get(report_id)
    
    def get_survey_analyses(self, survey_id: str) -> List[CrossAnalysisResult]:
        return [
            r for r in self._analysis_results.values()
            if r.survey_id == survey_id
        ]

survey_store = SurveyStore()

def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"

def get_current_timestamp() -> str:
    return datetime.utcnow().isoformat() + "Z"
