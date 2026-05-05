import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import create_app

app = create_app()

if __name__ == '__main__':
    print("=" * 50)
    print("SurveyAnalytics - 问卷数据分析与报告生成平台")
    print("=" * 50)
    print(f"服务器启动: http://localhost:5000")
    print(f"API 前缀: /api/v1")
    print("=" * 50)
    print("可用接口:")
    print("  POST /api/v1/survey/import - 导入问卷数据")
    print("  GET  /api/v1/survey/<survey_id> - 获取问卷信息")
    print("  GET  /api/v1/survey/<survey_id>/preview - 预览数据")
    print("  GET  /api/v1/analysis/statistics/<survey_id> - 获取统计结果")
    print("  POST /api/v1/analysis/cross - 执行交叉分析")
    print("  POST /api/v1/report/generate - 生成报告")
    print("  GET  /api/v1/report/<report_id>/export/pdf - 导出PDF")
    print("  GET  /api/v1/report/<report_id>/export/word - 导出Word")
    print("=" * 50)
    
    app.run(debug=True, host='0.0.0.0', port=5000)
