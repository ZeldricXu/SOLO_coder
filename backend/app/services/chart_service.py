from typing import Dict, List, Any, Optional
from app.models import CrossTableCell, FieldType

class ChartService:
    
    def create_frequency_chart_config(self, frequency_result: Dict[str, Any], chart_type: str = "bar") -> Dict[str, Any]:
        frequencies = frequency_result.get("frequencies", [])
        
        if not frequencies:
            return {}
        
        data = []
        for item in frequencies:
            data.append({
                "value": item["value"],
                "count": item["count"],
                "percentage": item["percentage"]
            })
        
        if chart_type == "pie":
            return {
                "type": "pie",
                "data": data,
                "angleField": "percentage",
                "colorField": "value",
                "title": f"{frequency_result.get('field_name', '')} 频数分布"
            }
        elif chart_type == "bar":
            return {
                "type": "interval",
                "data": data,
                "xField": "value",
                "yField": "percentage",
                "title": f"{frequency_result.get('field_name', '')} 频数分布",
                "style": {
                    "fill": "#1890ff"
                }
            }
        else:
            return {
                "type": "line",
                "data": data,
                "xField": "value",
                "yField": "percentage",
                "title": f"{frequency_result.get('field_name', '')} 频数分布"
            }
    
    def create_descriptive_chart_config(self, descriptive_stats: Dict[str, Any]) -> Dict[str, Any]:
        data = [
            {"metric": "均值", "value": descriptive_stats.get("mean", 0)},
            {"metric": "中位数", "value": descriptive_stats.get("median", 0)},
            {"metric": "标准差", "value": descriptive_stats.get("std", 0)},
            {"metric": "最小值", "value": descriptive_stats.get("min", 0)},
            {"metric": "最大值", "value": descriptive_stats.get("max", 0)},
            {"metric": "25分位", "value": descriptive_stats.get("q25", 0)},
            {"metric": "75分位", "value": descriptive_stats.get("q75", 0)}
        ]
        
        box_data = [{
            "field": descriptive_stats.get("field_name", ""),
            "min": descriptive_stats.get("min", 0),
            "q1": descriptive_stats.get("q25", 0),
            "median": descriptive_stats.get("median", 0),
            "q3": descriptive_stats.get("q75", 0),
            "max": descriptive_stats.get("max", 0)
        }]
        
        return {
            "type": "box",
            "data": box_data,
            "xField": "field",
            "box": ["min", "q1", "median", "q3", "max"],
            "title": f"{descriptive_stats.get('field_name', '')} 描述性统计",
            "summary": {
                "count": descriptive_stats.get("count", 0),
                "mean": descriptive_stats.get("mean", 0),
                "std": descriptive_stats.get("std", 0)
            }
        }
    
    def create_cross_chart_config(
        self, 
        cross_table: List[CrossTableCell], 
        row_type: FieldType, 
        col_type: FieldType,
        variables: List[str]
    ) -> Dict[str, Any]:
        if not cross_table:
            return {}
        
        first_cell = cross_table[0]
        has_mean = "mean" in first_cell.col_values
        
        if has_mean:
            return self._create_grouped_bar_chart(cross_table, variables)
        else:
            return self._create_stacked_bar_chart(cross_table, variables)
    
    def _create_grouped_bar_chart(
        self, 
        cross_table: List[CrossTableCell], 
        variables: List[str]
    ) -> Dict[str, Any]:
        data = []
        
        for cell in cross_table:
            row = cell.row
            col_values = cell.col_values
            
            mean = col_values.get("mean", 0)
            std = col_values.get("std", 0)
            count = col_values.get("count", 0)
            
            data.append({
                "category": row,
                "mean": mean,
                "std": std,
                "count": count
            })
        
        return {
            "type": "interval",
            "data": data,
            "xField": "category",
            "yField": "mean",
            "title": f"{variables[0]} 分组对比",
            "subtitle": "各组均值比较",
            "errorField": "std",
            "style": {
                "fill": "#52c41a"
            },
            "tooltip": {
                "fields": ["mean", "std", "count"]
            }
        }
    
    def _create_stacked_bar_chart(
        self, 
        cross_table: List[CrossTableCell], 
        variables: List[str]
    ) -> Dict[str, Any]:
        data = []
        
        col_names = set()
        for cell in cross_table:
            for key in cell.col_values:
                if not key.startswith("_"):
                    col_names.add(key)
        
        for cell in cross_table:
            row = cell.row
            for col_name in col_names:
                if col_name in cell.col_values:
                    val = cell.col_values[col_name]
                    if isinstance(val, dict):
                        count = val.get("count", 0)
                        percentage = val.get("row_percentage", 0)
                        data.append({
                            "row": row,
                            "column": col_name,
                            "count": count,
                            "percentage": percentage
                        })
        
        return {
            "type": "interval",
            "data": data,
            "xField": "row",
            "yField": "percentage",
            "seriesField": "column",
            "title": f"{variables[0]} vs {variables[1]} 交叉分析",
            "stack": True,
            "tooltip": {
                "fields": ["count", "percentage"]
            }
        }
    
    def create_summary_chart_config(self, statistics: List[Dict[str, Any]]) -> Dict[str, Any]:
        data = []
        
        for stat in statistics:
            if stat.get("type") == "frequency":
                field_name = stat.get("field_name", "")
                freq_data = stat.get("data", {})
                frequencies = freq_data.get("frequencies", [])
                
                for item in frequencies:
                    data.append({
                        "field": field_name,
                        "value": item["value"],
                        "percentage": item["percentage"]
                    })
        
        return {
            "type": "facets",
            "data": data,
            "facetField": "field",
            "childChart": {
                "type": "interval",
                "xField": "value",
                "yField": "percentage"
            },
            "title": "各变量频数分布概览"
        }
