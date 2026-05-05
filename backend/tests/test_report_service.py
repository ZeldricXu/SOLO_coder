"""
报告组装模块集成测试
测试报告结构完整性、分析结果嵌入正确性、图表配置格式正确性、导出文件格式合规性
"""
import os
import sys
import tempfile
import shutil
import pandas as pd
import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.report_service import ReportService
from app.services.export_service import ExportService
from app.services.import_service import ImportService
from app.services.chart_service import ChartService
from app.models import (
    SurveyData, Field, FieldType, Report, ReportSection,
    FrequencyResult, FrequencyItem, DescriptiveStats,
    CrossAnalysisResult, CrossTableCell, SignificanceResult,
    survey_store, get_current_timestamp
)
from tests.conftest import create_test_excel_file, assert_almost_equal


class TestReportService:
    """报告生成服务测试"""
    
    def test_report_section_generation(self, test_upload_dir):
        """
        测试报告章节生成
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        frequency_data = {
            "frequencies": [
                {"value": "男", "count": 50, "percentage": 50.0},
                {"value": "女", "count": 50, "percentage": 50.0}
            ]
        }
        
        content = report_service._generate_frequency_section_content(frequency_data, "性别")
        
        assert "性别" in content
        assert "50" in content
        assert "50.0%" in content
    
    def test_descriptive_section_content(self, test_upload_dir):
        """
        测试描述性统计章节内容生成
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        descriptive_data = {
            "count": 100,
            "mean": 32.5,
            "median": 31.0,
            "std": 8.5,
            "min": 18,
            "max": 65,
            "q25": 26.0,
            "q75": 38.0
        }
        
        content = report_service._generate_descriptive_section_content(descriptive_data, "年龄")
        
        assert "年龄" in content
        assert "32.5" in content
        assert "31.0" in content
        assert "8.5" in content
        assert "18" in content
        assert "65" in content
    
    def test_cross_section_content(self, test_upload_dir):
        """
        测试交叉分析章节内容生成
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        cross_table = [
            CrossTableCell(
                row="男",
                col_values={
                    "高": {"count": 30, "percentage": 30.0},
                    "中": {"count": 15, "percentage": 15.0},
                    "低": {"count": 5, "percentage": 5.0},
                    "_total": {"count": 50, "percentage": 50.0}
                }
            ),
            CrossTableCell(
                row="女",
                col_values={
                    "高": {"count": 15, "percentage": 15.0},
                    "中": {"count": 30, "percentage": 30.0},
                    "低": {"count": 5, "percentage": 5.0},
                    "_total": {"count": 50, "percentage": 50.0}
                }
            )
        ]
        
        significance = SignificanceResult(
            test_type="chi_square",
            p_value=0.015,
            significant=True,
            details={"chi2_statistic": 8.5}
        )
        
        content = report_service._generate_cross_section_content(
            cross_table, significance, ["性别", "收入水平"]
        )
        
        assert "性别" in content
        assert "收入水平" in content
        assert "男" in content
        assert "女" in content
        assert "30" in content
        assert "15" in content
        assert "0.015" in content


class TestReportGeneration:
    """完整报告生成测试"""
    
    def test_generate_full_report(self, test_upload_dir):
        """
        测试生成完整报告
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男', '女', '男', '女', '男', '女'],
            'age': [25, 32, 28, 45, 35, 22, 40, 33, 29, 38],
            'satisfaction': [8, 7, 9, 6, 8, 9, 7, 8, 6, 7]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_report_gen.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_report_survey_001',
            survey_name='报告生成测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_age',
                    field_name='age',
                    field_type=FieldType.NUMERIC
                ),
                Field(
                    field_id='q_satisfaction',
                    field_name='satisfaction',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        report = report_service.generate_report(
            'test_report_survey_001',
            title='测试报告',
            frequency_fields=['q_gender'],
            descriptive_fields=['q_age', 'q_satisfaction']
        )
        
        assert report is not None
        assert report.survey_id == 'test_report_survey_001'
        assert report.title == '测试报告'
        assert len(report.sections) >= 3
        
        section_types = [s.section_type for s in report.sections]
        assert 'summary' in section_types
        assert 'frequency' in section_types
        assert 'descriptive' in section_types
    
    def test_report_structure(self, test_upload_dir):
        """
        测试报告结构完整性
        """
        df = pd.DataFrame({
            'gender': ['男', '女'] * 5,
            'age': [25, 30, 35, 40, 45, 22, 28, 33, 38, 42]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_report_struct.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_struct_survey_001',
            survey_name='报告结构测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                ),
                Field(
                    field_id='q_age',
                    field_name='age',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        report = report_service.generate_report(
            'test_struct_survey_001',
            title='结构测试报告',
            frequency_fields=['q_gender'],
            descriptive_fields=['q_age']
        )
        
        assert report is not None
        
        summary_section = next((s for s in report.sections if s.section_type == 'summary'), None)
        assert summary_section is not None
        assert '报告概述' in summary_section.title
        
        frequency_section = next((s for s in report.sections if s.section_type == 'frequency'), None)
        assert frequency_section is not None
        assert '频数分析' in frequency_section.title
        
        descriptive_section = next((s for s in report.sections if s.section_type == 'descriptive'), None)
        assert descriptive_section is not None
        assert '描述性统计' in descriptive_section.title


class TestExportService:
    """导出服务测试"""
    
    def test_export_service_initialization(self, test_export_dir):
        """
        测试导出服务初始化
        """
        export_service = ExportService(test_export_dir)
        
        assert export_service.export_folder == test_export_dir
        assert os.path.exists(test_export_dir)
    
    def test_get_export_file_path(self, test_export_dir):
        """
        测试导出文件路径生成
        """
        export_service = ExportService(test_export_dir)
        
        word_path = export_service.get_export_file_path('test_report_001', 'docx')
        pdf_path = export_service.get_export_file_path('test_report_001', 'pdf')
        
        assert word_path.endswith('.docx')
        assert pdf_path.endswith('.pdf')
        assert 'test_report_001' in word_path
        assert 'test_report_001' in pdf_path


class TestReportDataValidation:
    """报告数据验证测试"""
    
    def test_frequency_section_data_structure(self, test_upload_dir):
        """
        测试频数分析章节数据结构
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男', '女', '男', '女', '男', '女']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_freq_data.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_freq_survey_001',
            survey_name='频数测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        report = report_service.generate_report(
            'test_freq_survey_001',
            title='频数测试报告',
            frequency_fields=['q_gender']
        )
        
        assert report is not None
        
        freq_section = next((s for s in report.sections if s.section_type == 'frequency'), None)
        assert freq_section is not None
        
        assert 'statistics' in freq_section.data
        stats = freq_section.data['statistics']
        assert len(stats) > 0
        
        for stat in stats:
            assert 'field_name' in stat
            assert 'data' in stat
            assert 'frequencies' in stat['data']
            
            frequencies = stat['data']['frequencies']
            for freq in frequencies:
                assert 'value' in freq
                assert 'count' in freq
                assert 'percentage' in freq
    
    def test_descriptive_section_data_structure(self, test_upload_dir):
        """
        测试描述性统计章节数据结构
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        df = pd.DataFrame({
            'age': [25, 32, 28, 45, 35, 22, 40, 33, 29, 38]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_desc_data.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_survey_001',
            survey_name='描述性测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_age',
                    field_name='age',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        report = report_service.generate_report(
            'test_desc_survey_001',
            title='描述性测试报告',
            descriptive_fields=['q_age']
        )
        
        assert report is not None
        
        desc_section = next((s for s in report.sections if s.section_type == 'descriptive'), None)
        assert desc_section is not None
        
        assert 'statistics' in desc_section.data
        stats = desc_section.data['statistics']
        assert len(stats) > 0
        
        for stat in stats:
            assert 'field_name' in stat
            assert 'data' in stat
            
            data = stat['data']
            required_fields = ['count', 'mean', 'median', 'std', 'min', 'max', 'q25', 'q75']
            for field in required_fields:
                assert field in data


class TestReportStorage:
    """报告存储测试"""
    
    def test_report_save_and_retrieve(self, test_upload_dir):
        """
        测试报告保存和获取
        """
        report = Report(
            report_id='test_store_001',
            survey_id='test_survey_001',
            title='存储测试报告',
            created_at=get_current_timestamp(),
            sections=[
                ReportSection(
                    section_type='summary',
                    title='报告概述',
                    content='测试内容',
                    data={'total_responses': 100}
                )
            ]
        )
        
        survey_store.save_report(report)
        
        retrieved = survey_store.get_report('test_store_001')
        
        assert retrieved is not None
        assert retrieved.report_id == 'test_store_001'
        assert retrieved.survey_id == 'test_survey_001'
        assert retrieved.title == '存储测试报告'
        assert len(retrieved.sections) == 1
    
    def test_get_nonexistent_report(self):
        """
        测试获取不存在的报告
        """
        retrieved = survey_store.get_report('nonexistent_report')
        assert retrieved is None


class TestReportEdgeCases:
    """报告边界场景测试"""
    
    def test_empty_data_report(self, test_upload_dir):
        """
        测试空数据的报告生成
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        df = pd.DataFrame({
            'gender': [],
            'age': []
        })
        
        file_path = os.path.join(test_upload_dir, 'test_empty_data.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_empty_survey_001',
            survey_name='空数据测试',
            total_responses=0,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        report = report_service.generate_report(
            'test_empty_survey_001',
            title='空数据报告',
            frequency_fields=['q_gender']
        )
        
        assert report is not None
        
        summary_section = next((s for s in report.sections if s.section_type == 'summary'), None)
        assert summary_section is not None
    
    def test_report_with_no_specified_fields(self, test_upload_dir):
        """
        测试没有指定分析字段的报告生成
        """
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        report_service = ReportService(import_service, chart_service)
        
        df = pd.DataFrame({
            'gender': ['男', '女'] * 5,
            'age': [25, 30, 35, 40, 45, 22, 28, 33, 38, 42]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_no_fields.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_no_fields_survey_001',
            survey_name='无字段测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                ),
                Field(
                    field_id='q_age',
                    field_name='age',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at=get_current_timestamp(),
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        report = report_service.generate_report(
            'test_no_fields_survey_001',
            title='无字段报告'
        )
        
        assert report is not None
        assert len(report.sections) >= 1
        
        summary_section = next((s for s in report.sections if s.section_type == 'summary'), None)
        assert summary_section is not None
