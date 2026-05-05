import os
import pandas as pd
import numpy as np
from typing import Dict, List, Any, Optional, Tuple
from werkzeug.utils import secure_filename
from dataclasses import dataclass

try:
    from app.services.data_cleaner import (
        DataCleaner, CleaningResult, NullTreatment, 
        OutlierMethod, TextNormalization, FieldCleaningConfig
    )
    HAS_DATA_CLEANER = True
except ImportError:
    HAS_DATA_CLEANER = False

from app.models import (
    SurveyData, Field, FieldType, 
    survey_store, generate_id, get_current_timestamp
)

class ImportService:
    def __init__(self, upload_folder: str):
        self.upload_folder = upload_folder
        os.makedirs(self.upload_folder, exist_ok=True)
    
    def save_file(self, file) -> str:
        filename = secure_filename(file.filename)
        if not filename:
            filename = f"upload_{generate_id('file')}"
        file_path = os.path.join(self.upload_folder, filename)
        file.save(file_path)
        return file_path
    
    def parse_file(self, file_path: str) -> pd.DataFrame:
        ext = os.path.splitext(file_path)[1].lower()
        if ext in ['.xlsx', '.xls']:
            return pd.read_excel(file_path, engine='openpyxl')
        elif ext == '.csv':
            return pd.read_csv(file_path, encoding='utf-8')
        else:
            raise ValueError(f"Unsupported file format: {ext}")
    
    def detect_field_type(self, series: pd.Series) -> FieldType:
        series_clean = series.dropna()
        
        if len(series_clean) == 0:
            return FieldType.TEXT
        
        try:
            pd.to_numeric(series_clean, errors='raise')
            return FieldType.NUMERIC
        except (ValueError, TypeError):
            pass
        
        try:
            pd.to_datetime(series_clean, errors='raise')
            return FieldType.DATE
        except (ValueError, TypeError):
            pass
        
        unique_ratio = len(series_clean.unique()) / len(series_clean)
        if unique_ratio < 0.1:
            return FieldType.SINGLE_CHOICE
        
        return FieldType.TEXT
    
    def infer_fields(self, df: pd.DataFrame) -> List[Field]:
        fields = []
        for col in df.columns:
            field_type = self.detect_field_type(df[col])
            
            options = None
            if field_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
                options = [str(x) for x in df[col].dropna().unique().tolist()]
            
            range_val = None
            if field_type == FieldType.NUMERIC:
                numeric_series = pd.to_numeric(df[col], errors='coerce')
                if not numeric_series.isna().all():
                    range_val = [float(numeric_series.min()), float(numeric_series.max())]
            
            field = Field(
                field_id=f"q_{col.lower().replace(' ', '_')}",
                field_name=col,
                field_type=field_type,
                options=options,
                range=range_val
            )
            fields.append(field)
        
        return fields
    
    def validate_data(self, df: pd.DataFrame, field_mappings: Optional[List[Dict]] = None) -> Tuple[int, int, List[Dict]]:
        errors = []
        valid_records = 0
        
        if field_mappings is None:
            for idx, row in df.iterrows():
                row_errors = []
                for col in df.columns:
                    val = row[col]
                    if pd.isna(val):
                        row_errors.append({
                            "column": col,
                            "message": "Missing value",
                            "row": idx + 2
                        })
                if not row_errors:
                    valid_records += 1
                else:
                    errors.extend(row_errors)
        else:
            mapping_dict = {m['field_id']: m['source_column'] for m in field_mappings}
            for idx, row in df.iterrows():
                row_errors = []
                for field_id, source_col in mapping_dict.items():
                    if source_col in df.columns:
                        val = row[source_col]
                        if pd.isna(val):
                            row_errors.append({
                                "field_id": field_id,
                                "column": source_col,
                                "message": "Missing value",
                                "row": idx + 2
                            })
                if not row_errors:
                    valid_records += 1
                else:
                    errors.extend(row_errors)
        
        invalid_records = len(df) - valid_records
        return valid_records, invalid_records, errors
    
    def create_survey(self, file_path: str, df: pd.DataFrame, survey_name: str, fields: List[Field]) -> Dict[str, Any]:
        valid_records, invalid_records, errors = self.validate_data(df)
        
        survey = SurveyData(
            survey_id=generate_id('survey'),
            survey_name=survey_name,
            total_responses=len(df),
            fields=fields,
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        
        survey_store.save_survey(survey)
        
        return {
            "survey_id": survey.survey_id,
            "survey_name": survey.survey_name,
            "total_records": survey.total_responses,
            "valid_records": valid_records,
            "invalid_records": invalid_records,
            "fields": [f.to_dict() for f in fields],
            "validation_errors": errors[:50]
        }
    
    def get_survey_info(self, survey_id: str) -> Optional[Dict[str, Any]]:
        survey = survey_store.get_survey(survey_id)
        if survey:
            return survey.to_dict()
        return None
    
    def get_survey_data_preview(self, survey_id: str, rows: int = 10) -> Optional[Dict[str, Any]]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        try:
            df = self.parse_file(survey.file_path)
            preview = df.head(rows).to_dict(orient='records')
            
            return {
                "survey_id": survey_id,
                "fields": [f.to_dict() for f in survey.fields],
                "preview_data": preview,
                "total_rows": len(df)
            }
        except Exception as e:
            return {
                "survey_id": survey_id,
                "error": str(e)
            }
    
    def apply_field_mapping(self, survey_id: str, field_mappings: List[Dict]) -> Dict[str, Any]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            raise ValueError(f"Survey not found: {survey_id}")
        
        try:
            df = self.parse_file(survey.file_path)
        except Exception as e:
            raise ValueError(f"Failed to parse file: {str(e)}")
        
        new_fields = []
        for mapping in field_mappings:
            field_id = mapping.get('field_id')
            field_name = mapping.get('field_name', field_id)
            field_type = FieldType(mapping.get('field_type', 'text'))
            source_column = mapping.get('source_column', field_id)
            
            if source_column not in df.columns:
                raise ValueError(f"Source column not found: {source_column}")
            
            options = mapping.get('options')
            range_val = mapping.get('range')
            
            if not options and field_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
                options = [str(x) for x in df[source_column].dropna().unique().tolist()]
            
            if not range_val and field_type == FieldType.NUMERIC:
                numeric_series = pd.to_numeric(df[source_column], errors='coerce')
                if not numeric_series.isna().all():
                    range_val = [float(numeric_series.min()), float(numeric_series.max())]
            
            field = Field(
                field_id=field_id,
                field_name=field_name,
                field_type=field_type,
                options=options,
                range=range_val
            )
            new_fields.append(field)
        
        survey.fields = new_fields
        survey_store.save_survey(survey)
        
        valid_records, invalid_records, errors = self.validate_data(df, field_mappings)
        
        return {
            "survey_id": survey_id,
            "fields": [f.to_dict() for f in new_fields],
            "valid_records": valid_records,
            "invalid_records": invalid_records,
            "validation_errors": errors[:50]
        }
    
    def get_default_cleaning_config(self, fields: List[Field]) -> Dict[str, Any]:
        """
        为字段生成默认的清洗配置
        
        Args:
            fields: 字段列表
            
        Returns:
            清洗配置字典
        """
        field_configs = []
        
        for field in fields:
            field_config = {
                "field_id": field.field_id,
                "field_name": field.field_name,
                "null_treatment": "keep",
                "outlier_method": "none",
                "text_normalization": []
            }
            
            if field.field_type == FieldType.NUMERIC:
                field_config["null_treatment"] = "keep"
                field_config["outlier_method"] = "none"
                field_config["numeric_decimals"] = None
            elif field.field_type == FieldType.DATE:
                field_config["date_format"] = "%Y-%m-%d"
            elif field.field_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
                field_config["text_normalization"] = ["trim"]
            
            field_configs.append(field_config)
        
        return {
            "field_configs": field_configs,
            "global_settings": {
                "drop_duplicates": False,
                "reset_index": True
            }
        }
    
    def apply_cleaning(
        self, 
        df: pd.DataFrame, 
        cleaning_config: Dict[str, Any],
        field_mappings: Optional[List[Dict]] = None
    ) -> Tuple[pd.DataFrame, Optional[Dict[str, Any]]]:
        """
        应用清洗配置到数据
        
        Args:
            df: 原始DataFrame
            cleaning_config: 清洗配置
            field_mappings: 字段映射列表
            
        Returns:
            (清洗后的DataFrame, 清洗结果统计)
        """
        if not HAS_DATA_CLEANER:
            return df, None
        
        try:
            cleaner = DataCleaner(cleaning_config)
            
            mappings_for_cleaning = []
            if field_mappings:
                for mapping in field_mappings:
                    mappings_for_cleaning.append({
                        "field_id": mapping.get("field_id"),
                        "source_column": mapping.get("source_column", mapping.get("field_id"))
                    })
            else:
                for col in df.columns:
                    mappings_for_cleaning.append({
                        "field_id": f"q_{col.lower().replace(' ', '_')}",
                        "source_column": col
                    })
            
            df_cleaned, cleaning_result = cleaner.clean(df, mappings_for_cleaning)
            
            return df_cleaned, cleaning_result.to_dict()
        
        except Exception as e:
            return df, {"error": str(e)}
    
    def preview_cleaning(
        self, 
        survey_id: str, 
        cleaning_config: Dict[str, Any]
    ) -> Optional[Dict[str, Any]]:
        """
        预览清洗效果
        
        Args:
            survey_id: 问卷ID
            cleaning_config: 清洗配置
            
        Returns:
            清洗预览结果
        """
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        try:
            df = self.parse_file(survey.file_path)
        except Exception as e:
            return {"error": str(e)}
        
        field_mappings = [
            {
                "field_id": f.field_id,
                "source_column": f.field_name,
                "field_name": f.field_name
            }
            for f in survey.fields
        ]
        
        df_cleaned, cleaning_stats = self.apply_cleaning(df, cleaning_config, field_mappings)
        
        preview_data = []
        if len(df) > 0:
            original_sample = df.head(10).to_dict(orient='records')
            cleaned_sample = df_cleaned.head(10).to_dict(orient='records')
            
            for i in range(min(10, len(original_sample))):
                preview_data.append({
                    "row": i + 1,
                    "original": original_sample[i],
                    "cleaned": cleaned_sample[i]
                })
        
        return {
            "survey_id": survey_id,
            "original_rows": len(df),
            "cleaned_rows": len(df_cleaned),
            "dropped_rows": len(df) - len(df_cleaned),
            "cleaning_stats": cleaning_stats,
            "preview_data": preview_data,
            "config_applied": cleaning_config
        }
    
    def apply_cleaning_to_survey(
        self,
        survey_id: str,
        cleaning_config: Dict[str, Any]
    ) -> Optional[Dict[str, Any]]:
        """
        应用清洗配置到问卷数据
        
        Args:
            survey_id: 问卷ID
            cleaning_config: 清洗配置
            
        Returns:
            应用结果
        """
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        try:
            df = self.parse_file(survey.file_path)
        except Exception as e:
            return {"error": str(e)}
        
        field_mappings = [
            {
                "field_id": f.field_id,
                "source_column": f.field_name,
                "field_name": f.field_name
            }
            for f in survey.fields
        ]
        
        df_cleaned, cleaning_stats = self.apply_cleaning(df, cleaning_config, field_mappings)
        
        if cleaning_stats:
            survey.cleaning_config = cleaning_config
            survey.cleaning_stats = cleaning_stats
            survey.cleaned_rows = len(df_cleaned)
            survey_store.save_survey(survey)
        
        return {
            "survey_id": survey_id,
            "original_rows": len(df),
            "cleaned_rows": len(df_cleaned),
            "dropped_rows": len(df) - len(df_cleaned),
            "cleaning_stats": cleaning_stats
        }
    
    def create_cleaning_config_from_rules(
        self,
        rules: List[Dict[str, Any]]
    ) -> Dict[str, Any]:
        """
        从规则列表创建清洗配置
        
        Args:
            rules: 规则列表，每个规则包含field_id和具体规则
            
        Returns:
            清洗配置字典
        """
        field_configs = []
        
        for rule in rules:
            field_config = {
                "field_id": rule.get("field_id"),
                "field_name": rule.get("field_name", rule.get("field_id"))
            }
            
            if "null_treatment" in rule:
                field_config["null_treatment"] = rule["null_treatment"]
            if "fill_value" in rule:
                field_config["fill_value"] = rule["fill_value"]
            if "outlier_method" in rule:
                field_config["outlier_method"] = rule["outlier_method"]
            if "outlier_threshold" in rule:
                field_config["outlier_threshold"] = rule["outlier_threshold"]
            if "outlier_action" in rule:
                field_config["outlier_action"] = rule["outlier_action"]
            if "text_normalization" in rule:
                field_config["text_normalization"] = rule["text_normalization"]
            if "date_format" in rule:
                field_config["date_format"] = rule["date_format"]
            if "numeric_decimals" in rule:
                field_config["numeric_decimals"] = rule["numeric_decimals"]
            if "custom_rules" in rule:
                field_config["custom_rules"] = rule["custom_rules"]
            
            field_configs.append(field_config)
        
        return {
            "field_configs": field_configs,
            "global_settings": {
                "drop_duplicates": False,
                "reset_index": True
            }
        }
