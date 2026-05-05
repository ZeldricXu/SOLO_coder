import os
from flask import Blueprint, request, jsonify, send_file, current_app
from werkzeug.utils import secure_filename
from typing import Dict, List, Any

from app.services.import_service import ImportService
from app.services.statistics_service import StatisticsService
from app.services.cross_analysis_service import CrossAnalysisService
from app.services.chart_service import ChartService
from app.services.report_service import ReportService
from app.services.export_service import ExportService
from app.models import survey_store

survey_bp = Blueprint('survey', __name__)
analysis_bp = Blueprint('analysis', __name__)
report_bp = Blueprint('report', __name__)

def get_import_service() -> ImportService:
    return ImportService(current_app.config['UPLOAD_FOLDER'])

def get_statistics_service() -> StatisticsService:
    return StatisticsService(get_import_service())

def get_chart_service() -> ChartService:
    return ChartService()

def get_cross_analysis_service() -> CrossAnalysisService:
    return CrossAnalysisService(get_import_service(), get_chart_service())

def get_report_service() -> ReportService:
    return ReportService(
        get_import_service(),
        get_statistics_service(),
        get_cross_analysis_service(),
        get_chart_service()
    )

def get_export_service() -> ExportService:
    return ExportService(current_app.config['EXPORT_FOLDER'])

ALLOWED_EXTENSIONS = {'xlsx', 'xls', 'csv'}

def allowed_file(filename: str) -> bool:
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@survey_bp.route('/survey/import', methods=['POST'])
def import_survey():
    if 'file' not in request.files:
        return jsonify({
            "code": 400,
            "message": "No file part"
        }), 400
    
    file = request.files['file']
    if file.filename == '':
        return jsonify({
            "code": 400,
            "message": "No selected file"
        }), 400
    
    if not allowed_file(file.filename):
        return jsonify({
            "code": 400,
            "message": "File type not allowed. Only .xlsx, .xls, .csv are supported."
        }), 400
    
    try:
        import_service = get_import_service()
        file_path = import_service.save_file(file)
        
        df = import_service.parse_file(file_path)
        fields = import_service.infer_fields(df)
        
        survey_name = request.form.get('survey_name', secure_filename(file.filename))
        
        result = import_service.create_survey(file_path, df, survey_name, fields)
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>', methods=['GET'])
def get_survey(survey_id: str):
    import_service = get_import_service()
    survey = import_service.get_survey_info(survey_id)
    
    if not survey:
        return jsonify({
            "code": 404,
            "message": "Survey not found"
        }), 404
    
    return jsonify({
        "code": 200,
        "data": survey
    })

@survey_bp.route('/survey/<survey_id>/preview', methods=['GET'])
def get_survey_preview(survey_id: str):
    rows = request.args.get('rows', 10, type=int)
    
    import_service = get_import_service()
    preview = import_service.get_survey_data_preview(survey_id, rows)
    
    if not preview:
        return jsonify({
            "code": 404,
            "message": "Survey not found"
        }), 404
    
    return jsonify({
        "code": 200,
        "data": preview
    })

@survey_bp.route('/survey/<survey_id>/fields', methods=['PUT'])
def update_field_mapping(survey_id: str):
    data = request.get_json()
    field_mappings = data.get('field_mappings', [])
    
    if not field_mappings:
        return jsonify({
            "code": 400,
            "message": "field_mappings is required"
        }), 400
    
    try:
        import_service = get_import_service()
        result = import_service.apply_field_mapping(survey_id, field_mappings)
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except ValueError as e:
        return jsonify({
            "code": 400,
            "message": str(e)
        }), 400
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/statistics/<survey_id>', methods=['GET'])
def get_statistics(survey_id: str):
    try:
        stats_service = get_statistics_service()
        result = stats_service.get_survey_statistics(survey_id)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/frequency/<survey_id>/<field_id>', methods=['GET'])
def get_frequency(survey_id: str, field_id: str):
    try:
        stats_service = get_statistics_service()
        result = stats_service.calculate_frequency(survey_id, field_id)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Survey or field not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result.to_dict()
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/descriptive/<survey_id>/<field_id>', methods=['GET'])
def get_descriptive(survey_id: str, field_id: str):
    try:
        stats_service = get_statistics_service()
        result = stats_service.calculate_descriptive_stats(survey_id, field_id)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Survey, field not found or field is not numeric"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result.to_dict()
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/cross', methods=['POST'])
def perform_cross_analysis():
    data = request.get_json()
    survey_id = data.get('survey_id')
    variables = data.get('variables', [])
    analysis_type = data.get('analysis_type', 'comparison')
    
    if not survey_id or len(variables) < 2:
        return jsonify({
            "code": 400,
            "message": "survey_id and at least 2 variables are required"
        }), 400
    
    try:
        cross_service = get_cross_analysis_service()
        result = cross_service.perform_cross_analysis(survey_id, variables, analysis_type)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Survey not found or insufficient valid data"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result.to_dict()
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/cross/<analysis_id>', methods=['GET'])
def get_cross_analysis(analysis_id: str):
    try:
        cross_service = get_cross_analysis_service()
        result = cross_service.get_analysis_result(analysis_id)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Analysis not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@analysis_bp.route('/analysis/cross/survey/<survey_id>', methods=['GET'])
def get_survey_cross_analyses(survey_id: str):
    try:
        cross_service = get_cross_analysis_service()
        results = cross_service.get_survey_analyses(survey_id)
        
        return jsonify({
            "code": 200,
            "data": results
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@report_bp.route('/report/generate', methods=['POST'])
def generate_report():
    data = request.get_json()
    survey_id = data.get('survey_id')
    title = data.get('title')
    
    if not survey_id:
        return jsonify({
            "code": 400,
            "message": "survey_id is required"
        }), 400
    
    try:
        report_service = get_report_service()
        report = report_service.generate_report(survey_id, title)
        
        if not report:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": report.to_dict()
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@report_bp.route('/report/<report_id>', methods=['GET'])
def get_report(report_id: str):
    try:
        report_service = get_report_service()
        report = report_service.get_report(report_id)
        
        if not report:
            return jsonify({
                "code": 404,
                "message": "Report not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": report
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@report_bp.route('/report/<report_id>/preview', methods=['GET'])
def get_report_preview(report_id: str):
    try:
        report_service = get_report_service()
        preview = report_service.get_report_preview(report_id)
        
        if not preview:
            return jsonify({
                "code": 404,
                "message": "Report not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": preview
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@report_bp.route('/report/<report_id>/export/<format>', methods=['GET'])
def export_report(report_id: str, format: str):
    if format not in ['word', 'pdf']:
        return jsonify({
            "code": 400,
            "message": "Format must be 'word' or 'pdf'"
        }), 400
    
    try:
        export_service = get_export_service()
        report = survey_store.get_report(report_id)
        
        if not report:
            return jsonify({
                "code": 404,
                "message": "Report not found"
            }), 404
        
        if format == 'pdf':
            file_path = export_service.export_to_pdf(report_id)
            mimetype = 'application/pdf'
            download_name = f"{report.title}.pdf"
        else:
            file_path = export_service.export_to_word(report_id)
            mimetype = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
            download_name = f"{report.title}.docx"
        
        if not file_path or not os.path.exists(file_path):
            return jsonify({
                "code": 500,
                "message": "Export failed"
            }), 500
        
        return send_file(
            file_path,
            mimetype=mimetype,
            as_attachment=True,
            download_name=download_name
        )
        
    except ImportError as e:
        return jsonify({
            "code": 500,
            "message": f"Missing dependency: {str(e)}"
        }), 500
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@report_bp.route('/report/<report_id>/export/package', methods=['GET'])
def export_report_package(report_id: str):
    """
    导出完整报告包，包含Word文档和配套的Excel图表文件（ZIP格式）
    """
    try:
        export_service = get_export_service()
        report = survey_store.get_report(report_id)
        
        if not report:
            return jsonify({
                "code": 404,
                "message": "Report not found"
            }), 404
        
        file_path = export_service.create_export_package(report_id, "docx")
        
        if not file_path or not os.path.exists(file_path):
            return jsonify({
                "code": 500,
                "message": "Export failed"
            }), 500
        
        if file_path.endswith('.zip'):
            mimetype = 'application/zip'
            download_name = f"{report.title}_完整导出包.zip"
        else:
            mimetype = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
            download_name = f"{report.title}.docx"
        
        return send_file(
            file_path,
            mimetype=mimetype,
            as_attachment=True,
            download_name=download_name
        )
        
    except ImportError as e:
        return jsonify({
            "code": 500,
            "message": f"Missing dependency: {str(e)}"
        }), 500
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>/cleaning/config/default', methods=['GET'])
def get_default_cleaning_config(survey_id: str):
    """
    获取问卷的默认清洗配置
    """
    try:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        import_service = get_import_service()
        default_config = import_service.get_default_cleaning_config(survey.fields)
        
        return jsonify({
            "code": 200,
            "data": default_config
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>/cleaning/presets', methods=['GET'])
def get_cleaning_presets(survey_id: str):
    """
    获取所有预设的清洗配置
    """
    try:
        from app.config import (
            DEFAULT_CLEANING_CONFIG,
            BASIC_CLEANING_CONFIG,
            STRICT_CLEANING_CONFIG,
            NUMERIC_CLEANING_CONFIG,
            TEXT_CLEANING_CONFIG,
            CUSTOMER_SURVEY_CONFIG,
            MARKET_RESEARCH_CONFIG
        )
        
        presets = {
            "default": DEFAULT_CLEANING_CONFIG,
            "basic": BASIC_CLEANING_CONFIG,
            "strict": STRICT_CLEANING_CONFIG,
            "numeric": NUMERIC_CLEANING_CONFIG,
            "text": TEXT_CLEANING_CONFIG,
            "customer_survey": CUSTOMER_SURVEY_CONFIG,
            "market_research": MARKET_RESEARCH_CONFIG
        }
        
        return jsonify({
            "code": 200,
            "data": presets
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>/cleaning/preview', methods=['POST'])
def preview_cleaning(survey_id: str):
    """
    预览清洗效果
    """
    try:
        data = request.get_json()
        cleaning_config = data.get('cleaning_config')
        
        if not cleaning_config:
            return jsonify({
                "code": 400,
                "message": "cleaning_config is required"
            }), 400
        
        import_service = get_import_service()
        preview = import_service.preview_cleaning(survey_id, cleaning_config)
        
        if not preview:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": preview
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>/cleaning/apply', methods=['POST'])
def apply_cleaning(survey_id: str):
    """
    应用清洗配置到问卷数据
    """
    try:
        data = request.get_json()
        cleaning_config = data.get('cleaning_config')
        
        if not cleaning_config:
            return jsonify({
                "code": 400,
                "message": "cleaning_config is required"
            }), 400
        
        import_service = get_import_service()
        result = import_service.apply_cleaning_to_survey(survey_id, cleaning_config)
        
        if not result:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500

@survey_bp.route('/survey/<survey_id>/cleaning', methods=['GET'])
def get_survey_cleaning_info(survey_id: str):
    """
    获取问卷的清洗配置和统计信息
    """
    try:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return jsonify({
                "code": 404,
                "message": "Survey not found"
            }), 404
        
        result = {
            "survey_id": survey.survey_id,
            "original_rows": survey.total_responses
        }
        
        if survey.cleaning_config:
            result["cleaning_config"] = survey.cleaning_config
        if survey.cleaning_stats:
            result["cleaning_stats"] = survey.cleaning_stats
        if survey.cleaned_rows is not None:
            result["cleaned_rows"] = survey.cleaned_rows
            result["dropped_rows"] = survey.total_responses - survey.cleaned_rows
        
        return jsonify({
            "code": 200,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "code": 500,
            "message": str(e)
        }), 500
