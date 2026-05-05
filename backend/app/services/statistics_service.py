import pandas as pd
import numpy as np
from typing import Dict, List, Any, Optional

from app.models import (
    SurveyData, Field, FieldType, 
    FrequencyResult, FrequencyItem, DescriptiveStats,
    survey_store
)
from app.services.import_service import ImportService

class StatisticsService:
    def __init__(self, import_service: ImportService):
        self.import_service = import_service
    
    def calculate_frequency(self, survey_id: str, field_id: str) -> Optional[FrequencyResult]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        field = None
        for f in survey.fields:
            if f.field_id == field_id:
                field = f
                break
        
        if not field:
            return None
        
        try:
            df = self.import_service.parse_file(survey.file_path)
        except Exception as e:
            return None
        
        col_index = -1
        for i, f in enumerate(survey.fields):
            if f.field_id == field_id:
                col_index = i
                break
        
        if col_index >= len(df.columns):
            col_index = -1
        
        if col_index == -1:
            col_name = field.field_name
        else:
            col_name = df.columns[col_index]
        
        if col_name not in df.columns:
            alt_col_name = None
            for col in df.columns:
                if col == field.field_name or col.lower().replace(' ', '_') == field_id.replace('q_', ''):
                    alt_col_name = col
                    break
            if alt_col_name:
                col_name = alt_col_name
            else:
                col_name = df.columns[min(col_index if col_index >= 0 else 0, len(df.columns) - 1)]
        
        series = df[col_name]
        
        missing_count = series.isna().sum()
        valid_series = series.dropna()
        total_valid = len(valid_series)
        
        if total_valid == 0:
            return FrequencyResult(
                field_id=field_id,
                field_name=field.field_name,
                frequencies=[],
                total_valid=0,
                missing_count=missing_count
            )
        
        value_counts = valid_series.value_counts()
        
        frequencies = []
        for value, count in value_counts.items():
            percentage = (count / total_valid) * 100
            frequencies.append(FrequencyItem(
                value=str(value),
                count=int(count),
                percentage=round(percentage, 2)
            ))
        
        result = FrequencyResult(
            field_id=field_id,
            field_name=field.field_name,
            frequencies=frequencies,
            total_valid=total_valid,
            missing_count=int(missing_count)
        )
        
        survey_store.save_frequency(result)
        
        return result
    
    def calculate_descriptive_stats(self, survey_id: str, field_id: str) -> Optional[DescriptiveStats]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        field = None
        for f in survey.fields:
            if f.field_id == field_id:
                field = f
                break
        
        if not field or field.field_type != FieldType.NUMERIC:
            return None
        
        try:
            df = self.import_service.parse_file(survey.file_path)
        except Exception as e:
            return None
        
        col_index = -1
        for i, f in enumerate(survey.fields):
            if f.field_id == field_id:
                col_index = i
                break
        
        if col_index >= len(df.columns):
            col_index = -1
        
        if col_index == -1:
            col_name = field.field_name
        else:
            col_name = df.columns[col_index]
        
        if col_name not in df.columns:
            alt_col_name = None
            for col in df.columns:
                if col == field.field_name or col.lower().replace(' ', '_') == field_id.replace('q_', ''):
                    alt_col_name = col
                    break
            if alt_col_name:
                col_name = alt_col_name
            else:
                col_name = df.columns[min(col_index if col_index >= 0 else 0, len(df.columns) - 1)]
        
        series = pd.to_numeric(df[col_name], errors='coerce')
        valid_series = series.dropna()
        
        if len(valid_series) == 0:
            return None
        
        stats = DescriptiveStats(
            field_id=field_id,
            field_name=field.field_name,
            count=int(len(valid_series)),
            mean=round(float(valid_series.mean()), 4),
            median=round(float(valid_series.median()), 4),
            std=round(float(valid_series.std()), 4),
            min=round(float(valid_series.min()), 4),
            max=round(float(valid_series.max()), 4),
            q25=round(float(valid_series.quantile(0.25)), 4),
            q75=round(float(valid_series.quantile(0.75)), 4)
        )
        
        survey_store.save_descriptive(stats)
        
        return stats
    
    def get_survey_statistics(self, survey_id: str) -> Optional[Dict[str, Any]]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        results = {
            "survey_id": survey_id,
            "survey_name": survey.survey_name,
            "total_responses": survey.total_responses,
            "statistics": []
        }
        
        for field in survey.fields:
            if field.field_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
                freq = self.calculate_frequency(survey_id, field.field_id)
                if freq:
                    results["statistics"].append({
                        "field_id": field.field_id,
                        "field_name": field.field_name,
                        "field_type": field.field_type.value,
                        "type": "frequency",
                        "data": freq.to_dict()
                    })
            elif field.field_type == FieldType.NUMERIC:
                stats = self.calculate_descriptive_stats(survey_id, field.field_id)
                if stats:
                    results["statistics"].append({
                        "field_id": field.field_id,
                        "field_name": field.field_name,
                        "field_type": field.field_type.value,
                        "type": "descriptive",
                        "data": stats.to_dict()
                    })
        
        return results
