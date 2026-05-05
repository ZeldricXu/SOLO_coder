from typing import Dict, List, Any, Optional
from datetime import datetime

from app.models import (
    SurveyData, Report, ReportSection, 
    survey_store, generate_id, get_current_timestamp
)
from app.services.import_service import ImportService
from app.services.statistics_service import StatisticsService
from app.services.cross_analysis_service import CrossAnalysisService
from app.services.chart_service import ChartService

class ReportService:
    def __init__(
        self, 
        import_service: ImportService,
        statistics_service: StatisticsService,
        cross_analysis_service: CrossAnalysisService,
        chart_service: ChartService
    ):
        self.import_service = import_service
        self.statistics_service = statistics_service
        self.cross_analysis_service = cross_analysis_service
        self.chart_service = chart_service
    
    def generate_report(self, survey_id: str, title: Optional[str] = None) -> Optional[Report]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        if not title:
            title = f"{survey.survey_name} 分析报告"
        
        sections: List[ReportSection] = []
        
        intro_content = f"""
本次调查共收集有效样本 {survey.total_responses} 份。
数据导入时间：{survey.imported_at}
问卷包含 {len(survey.fields)} 个问题。
        """
        
        sections.append(ReportSection(
            section_type="introduction",
            title="一、调查概述",
            content=intro_content.strip()
        ))
        
        statistics = self.statistics_service.get_survey_statistics(survey_id)
        
        if statistics and "statistics" in statistics:
            freq_sections = []
            desc_sections = []
            
            for stat in statistics["statistics"]:
                if stat.get("type") == "frequency":
                    freq_sections.append(stat)
                elif stat.get("type") == "descriptive":
                    desc_sections.append(stat)
            
            if freq_sections:
                freq_content = self._generate_frequency_section_content(freq_sections)
                
                freq_section = ReportSection(
                    section_type="frequency",
                    title="二、频数分析结果",
                    content=freq_content,
                    data={"statistics": freq_sections}
                )
                sections.append(freq_section)
            
            if desc_sections:
                desc_content = self._generate_descriptive_section_content(desc_sections)
                
                desc_section = ReportSection(
                    section_type="descriptive",
                    title="三、描述性统计结果",
                    content=desc_content,
                    data={"statistics": desc_sections}
                )
                sections.append(desc_section)
        
        cross_analyses = survey_store.get_survey_analyses(survey_id)
        
        if cross_analyses:
            cross_content = self._generate_cross_section_content(cross_analyses)
            
            cross_section = ReportSection(
                section_type="cross_analysis",
                title="四、交叉分析结果",
                content=cross_content,
                data={"analyses": [a.to_dict() for a in cross_analyses]}
            )
            sections.append(cross_section)
        
        conclusion_content = self._generate_conclusion_content(statistics, cross_analyses)
        
        sections.append(ReportSection(
            section_type="conclusion",
            title="五、分析结论",
            content=conclusion_content
        ))
        
        report = Report(
            report_id=generate_id('report'),
            survey_id=survey_id,
            title=title,
            created_at=get_current_timestamp(),
            sections=sections
        )
        
        survey_store.save_report(report)
        
        return report
    
    def _generate_frequency_section_content(self, freq_sections: List[Dict]) -> str:
        content_parts = []
        
        for stat in freq_sections:
            field_name = stat.get("field_name", "")
            data = stat.get("data", {})
            frequencies = data.get("frequencies", [])
            total_valid = data.get("total_valid", 0)
            missing_count = data.get("missing_count", 0)
            
            content_parts.append(f"\n【{field_name}】")
            content_parts.append(f"有效样本数：{total_valid}，缺失值：{missing_count}")
            
            top_items = frequencies[:5]
            for item in top_items:
                content_parts.append(f"  - {item['value']}: {item['count']}人 ({item['percentage']}%)")
            
            if len(frequencies) > 5:
                content_parts.append(f"  ... 还有 {len(frequencies) - 5} 个选项")
        
        return "\n".join(content_parts).strip()
    
    def _generate_descriptive_section_content(self, desc_sections: List[Dict]) -> str:
        content_parts = []
        
        for stat in desc_sections:
            field_name = stat.get("field_name", "")
            data = stat.get("data", {})
            
            content_parts.append(f"\n【{field_name}】")
            content_parts.append(f"样本数：{data.get('count', 0)}")
            content_parts.append(f"均值：{data.get('mean', 0):.4f}")
            content_parts.append(f"中位数：{data.get('median', 0):.4f}")
            content_parts.append(f"标准差：{data.get('std', 0):.4f}")
            content_parts.append(f"范围：{data.get('min', 0):.4f} - {data.get('max', 0):.4f}")
        
        return "\n".join(content_parts).strip()
    
    def _generate_cross_section_content(self, cross_analyses: List) -> str:
        content_parts = []
        
        for i, analysis in enumerate(cross_analyses):
            variables = analysis.variables
            significance = analysis.significance
            
            content_parts.append(f"\n【分析 {i+1}: {', '.join(variables)}】")
            
            if significance:
                sig_text = "显著" if significance.significant else "不显著"
                content_parts.append(f"检验方法：{significance.test_type}")
                content_parts.append(f"P值：{significance.p_value:.6f}")
                content_parts.append(f"显著性：{sig_text} (p<0.05)")
            
            cross_table = analysis.cross_table
            if cross_table:
                content_parts.append("\n交叉表摘要：")
                for row in cross_table[:5]:
                    row_str = f"  {row.row}: "
                    if "mean" in row.col_values:
                        row_str += f"均值={row.col_values['mean']:.4f}, 样本数={row.col_values['count']}"
                    else:
                        total = row.col_values.get('_total', {}).get('count', 0)
                        row_str += f"样本数={total}"
                    content_parts.append(row_str)
                
                if len(cross_table) > 5:
                    content_parts.append(f"  ... 还有 {len(cross_table) - 5} 行")
        
        return "\n".join(content_parts).strip()
    
    def _generate_conclusion_content(self, statistics: Optional[Dict], cross_analyses: List) -> str:
        content_parts = []
        
        content_parts.append("基于以上分析，得出以下主要结论：\n")
        
        if statistics and "statistics" in statistics:
            freq_stats = [s for s in statistics["statistics"] if s.get("type") == "frequency"]
            desc_stats = [s for s in statistics["statistics"] if s.get("type") == "descriptive"]
            
            for stat in freq_stats[:3]:
                field_name = stat.get("field_name", "")
                data = stat.get("data", {})
                frequencies = data.get("frequencies", [])
                
                if frequencies:
                    top = frequencies[0]
                    content_parts.append(f"1. {field_name}方面，'{top['value']}'占比最高，为{top['percentage']}%。")
            
            for stat in desc_stats[:2]:
                field_name = stat.get("field_name", "")
                data = stat.get("data", {})
                mean = data.get("mean", 0)
                std = data.get("std", 0)
                
                content_parts.append(f"2. {field_name}的平均水平为{mean:.2f}，标准差为{std:.2f}。")
        
        if cross_analyses:
            significant_analyses = [a for a in cross_analyses if a.significance and a.significance.significant]
            
            if significant_analyses:
                for analysis in significant_analyses[:2]:
                    vars_str = "与".join(analysis.variables)
                    content_parts.append(f"3. {vars_str}之间存在显著关联（{analysis.significance.test_type}检验，p={analysis.significance.p_value:.4f}）。")
            else:
                content_parts.append("3. 交叉分析未发现变量间存在统计学显著关联。")
        
        return "\n".join(content_parts).strip()
    
    def get_report(self, report_id: str) -> Optional[Dict[str, Any]]:
        report = survey_store.get_report(report_id)
        if report:
            return report.to_dict()
        return None
    
    def get_report_preview(self, report_id: str) -> Optional[Dict[str, Any]]:
        report = survey_store.get_report(report_id)
        if not report:
            return None
        
        preview_data = {
            "report_id": report.report_id,
            "title": report.title,
            "created_at": report.created_at,
            "survey_id": report.survey_id,
            "sections": [],
            "toc": []
        }
        
        for i, section in enumerate(report.sections):
            preview_data["toc"].append({
                "section_number": i + 1,
                "title": section.title,
                "type": section.section_type
            })
            
            preview_data["sections"].append({
                "section_type": section.section_type,
                "title": section.title,
                "content_preview": section.content[:200] + "..." if len(section.content) > 200 else section.content,
                "has_chart": section.chart_config is not None,
                "has_data": section.data is not None
            })
        
        return preview_data
