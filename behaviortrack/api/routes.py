import logging
from typing import Any, Dict

from flask import Flask, jsonify, request, send_file
from flask_cors import CORS

from ..config import settings
from ..modules import (
    BehaviorCollector,
    EventAnalyzer,
    TrajectoryAnalyzer,
    UserProfiler,
    StatisticsModule,
    QueryModule,
    ExportModule,
    VisualizationModule
)


logger = logging.getLogger(__name__)


def create_app() -> Flask:
    app = Flask(__name__)
    app.config["JSON_AS_ASCII"] = False
    CORS(app)
    
    collector = BehaviorCollector()
    event_analyzer = EventAnalyzer()
    trajectory_analyzer = TrajectoryAnalyzer()
    profiler = UserProfiler()
    stats_module = StatisticsModule()
    query_module = QueryModule()
    export_module = ExportModule()
    visualization_module = VisualizationModule()
    
    @app.route("/api/v1/health", methods=["GET"])
    def health_check() -> Any:
        return jsonify({
            "code": 200,
            "status": "healthy",
            "service": settings.APP_NAME,
            "version": settings.APP_VERSION
        })
    
    @app.route("/api/v1/behavior/report", methods=["POST"])
    def report_behavior() -> Any:
        try:
            data = request.get_json()
            if not data:
                return jsonify({
                    "code": 400,
                    "error": "No JSON data provided"
                }), 400
            
            result = collector.collect(data)
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": {
                        "event_id": result.get("event_id")
                    }
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error", "Failed to collect behavior"),
                    "details": result.get("details")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in report_behavior: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/report/batch", methods=["POST"])
    def report_behavior_batch() -> Any:
        try:
            data = request.get_json()
            if not data or not isinstance(data, list):
                return jsonify({
                    "code": 400,
                    "error": "Expected a list of events"
                }), 400
            
            result = collector.collect_batch(data)
            
            if result.get("success") or result.get("event_ids"):
                return jsonify({
                    "code": 200,
                    "data": {
                        "event_ids": result.get("event_ids"),
                        "count": len(result.get("event_ids", [])),
                        "errors": result.get("errors", [])
                    }
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error", "Failed to collect behaviors"),
                    "errors": result.get("errors", [])
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in report_behavior_batch: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/stats", methods=["GET"])
    def get_behavior_stats() -> Any:
        try:
            event_type = request.args.get("event_type")
            start_date = request.args.get("start_date")
            end_date = request.args.get("end_date")
            stats_type = request.args.get("type", "overview")
            
            if stats_type == "overview":
                result = stats_module.get_overview_stats()
            elif stats_type == "daily":
                result = stats_module.get_daily_stats(start_date, end_date)
            elif stats_type == "active_users":
                period = request.args.get("period", "daily")
                result = stats_module.get_active_users_stats(period, start_date, end_date)
            elif stats_type == "retention":
                cohort_date = request.args.get("cohort_date")
                days = int(request.args.get("days", "7"))
                if not cohort_date:
                    return jsonify({
                        "code": 400,
                        "error": "cohort_date is required for retention stats"
                    }), 400
                result = stats_module.get_retention_stats(cohort_date, days)
            elif stats_type == "event_distribution":
                limit = int(request.args.get("limit", "20"))
                result = stats_module.get_event_distribution(limit)
            elif stats_type == "hourly":
                result = stats_module.get_hourly_distribution()
            else:
                result = event_analyzer.get_event_stats_by_type(
                    event_type,
                    start_date,
                    end_date
                ) if event_type else stats_module.get_overview_stats()
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error", "Failed to get stats")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in get_behavior_stats: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/profile", methods=["GET"])
    def get_user_profile() -> Any:
        try:
            user_id = request.args.get("user_id")
            if not user_id:
                return jsonify({
                    "code": 400,
                    "error": "user_id is required"
                }), 400
            
            result = profiler.get_profile(user_id)
            
            if result.get("success"):
                profile = result.get("profile", {})
                return jsonify({
                    "code": 200,
                    "data": {
                        "profile": {
                            "user_id": profile.get("user_id"),
                            "basic_attributes": profile.get("basic_attributes", {}),
                            "behavior_attributes": profile.get("behavior_attributes", {}),
                            "tags": profile.get("profile_tags", []),
                            "updated_at": profile.get("updated_at")
                        }
                    }
                })
            else:
                return jsonify({
                    "code": 404,
                    "error": result.get("error", "Profile not found")
                }), 404
                
        except Exception as e:
            logger.exception(f"Error in get_user_profile: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/profile/build", methods=["POST"])
    def build_user_profile() -> Any:
        try:
            data = request.get_json()
            if not data or "user_id" not in data:
                return jsonify({
                    "code": 400,
                    "error": "user_id is required"
                }), 400
            
            result = profiler.build_profile(data["user_id"])
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result.get("profile")
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error", "Failed to build profile")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in build_user_profile: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/profile/tags", methods=["GET"])
    def get_profile_tags() -> Any:
        try:
            result = profiler.get_tag_distribution()
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in get_profile_tags: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/events", methods=["GET"])
    def query_events() -> Any:
        try:
            user_id = request.args.get("user_id")
            event_type = request.args.get("event_type")
            event_name = request.args.get("event_name")
            session_id = request.args.get("session_id")
            start_date = request.args.get("start_date")
            end_date = request.args.get("end_date")
            limit = int(request.args.get("limit", "100"))
            offset = int(request.args.get("offset", "0"))
            
            result = query_module.query_events(
                user_id=user_id,
                event_type=event_type,
                event_name=event_name,
                session_id=session_id,
                start_date=start_date,
                end_date=end_date,
                limit=limit,
                offset=offset
            )
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in query_events: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/events/<event_id>", methods=["GET"])
    def get_event(event_id: str) -> Any:
        try:
            result = query_module.get_event_by_id(event_id)
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result.get("event")
                })
            else:
                return jsonify({
                    "code": 404,
                    "error": "Event not found"
                }), 404
                
        except Exception as e:
            logger.exception(f"Error in get_event: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/events/types", methods=["GET"])
    def get_event_types() -> Any:
        try:
            result = query_module.get_event_types()
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in get_event_types: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/trajectory", methods=["GET"])
    def get_trajectory() -> Any:
        try:
            user_id = request.args.get("user_id")
            session_id = request.args.get("session_id")
            
            if not user_id:
                return jsonify({
                    "code": 400,
                    "error": "user_id is required"
                }), 400
            
            result = trajectory_analyzer.get_user_trajectory(user_id, session_id)
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 404,
                    "error": result.get("error")
                }), 404
                
        except Exception as e:
            logger.exception(f"Error in get_trajectory: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/analysis/event", methods=["POST"])
    def analyze_events() -> Any:
        try:
            data = request.get_json() or {}
            event_type = data.get("event_type")
            start_date = data.get("start_date")
            end_date = data.get("end_date")
            
            result = event_analyzer.analyze_events(event_type, start_date, end_date)
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in analyze_events: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/analysis/funnel", methods=["POST"])
    def analyze_funnel() -> Any:
        try:
            data = request.get_json()
            if not data or "events" not in data:
                return jsonify({
                    "code": 400,
                    "error": "events array is required"
                }), 400
            
            events = data.get("events", [])
            start_date = data.get("start_date")
            end_date = data.get("end_date")
            
            result = trajectory_analyzer.analyze_funnel(events, start_date, end_date)
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in analyze_funnel: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/export", methods=["POST"])
    def export_data() -> Any:
        try:
            data = request.get_json() or {}
            export_type = data.get("type", "events")
            file_format = data.get("format", "json")
            
            if export_type == "events":
                result = export_module.export_events(
                    file_format=file_format,
                    user_id=data.get("user_id"),
                    event_type=data.get("event_type"),
                    start_date=data.get("start_date"),
                    end_date=data.get("end_date"),
                    limit=int(data.get("limit", "10000"))
                )
            elif export_type == "profile":
                user_id = data.get("user_id")
                if not user_id:
                    return jsonify({
                        "code": 400,
                        "error": "user_id is required for profile export"
                    }), 400
                result = export_module.export_user_profile(user_id, file_format)
            elif export_type == "stats":
                stats_type = data.get("stats_type", "daily")
                result = export_module.export_statistics(
                    stats_type=stats_type,
                    file_format=file_format,
                    start_date=data.get("start_date"),
                    end_date=data.get("end_date")
                )
            else:
                return jsonify({
                    "code": 400,
                    "error": f"Unsupported export type: {export_type}"
                }), 400
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in export_data: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/export/files", methods=["GET"])
    def list_export_files() -> Any:
        try:
            result = export_module.list_export_files()
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in list_export_files: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/export/download/<filename>", methods=["GET"])
    def download_export_file(filename: str) -> Any:
        try:
            file_path = export_module.get_export_file(filename)
            
            if file_path:
                return send_file(
                    str(file_path),
                    as_attachment=True,
                    download_name=filename
                )
            else:
                return jsonify({
                    "code": 404,
                    "error": "File not found"
                }), 404
                
        except Exception as e:
            logger.exception(f"Error in download_export_file: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/visualization", methods=["POST"])
    def generate_visualization() -> Any:
        try:
            data = request.get_json() or {}
            chart_type = data.get("type", "event_trend")
            
            if chart_type == "event_trend":
                result = visualization_module.generate_event_trend_chart(
                    start_date=data.get("start_date"),
                    end_date=data.get("end_date")
                )
            elif chart_type == "event_distribution":
                limit = int(data.get("limit", "10"))
                result = visualization_module.generate_event_distribution_pie(limit=limit)
            elif chart_type == "hourly_activity":
                result = visualization_module.generate_hourly_activity_chart()
            elif chart_type == "active_users":
                period = data.get("period", "daily")
                result = visualization_module.generate_active_users_chart(
                    period=period,
                    start_date=data.get("start_date"),
                    end_date=data.get("end_date")
                )
            elif chart_type == "tag_distribution":
                result = visualization_module.generate_tag_distribution_chart()
            else:
                return jsonify({
                    "code": 400,
                    "error": f"Unsupported chart type: {chart_type}"
                }), 400
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in generate_visualization: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/visualization/files", methods=["GET"])
    def list_visualizations() -> Any:
        try:
            result = visualization_module.list_visualizations()
            
            if result.get("success"):
                return jsonify({
                    "code": 200,
                    "data": result
                })
            else:
                return jsonify({
                    "code": 400,
                    "error": result.get("error")
                }), 400
                
        except Exception as e:
            logger.exception(f"Error in list_visualizations: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.route("/api/v1/behavior/visualization/<filename>", methods=["GET"])
    def get_visualization(filename: str) -> Any:
        try:
            file_path = visualization_module.get_visualization(filename)
            
            if file_path:
                return send_file(str(file_path), mimetype="image/png")
            else:
                return jsonify({
                    "code": 404,
                    "error": "File not found"
                }), 404
                
        except Exception as e:
            logger.exception(f"Error in get_visualization: {str(e)}")
            return jsonify({
                "code": 500,
                "error": str(e)
            }), 500
    
    @app.errorhandler(404)
    def not_found(error: Exception) -> Any:
        return jsonify({
            "code": 404,
            "error": "Not found"
        }), 404
    
    @app.errorhandler(500)
    def internal_error(error: Exception) -> Any:
        logger.exception(f"Internal server error: {str(error)}")
        return jsonify({
            "code": 500,
            "error": "Internal server error"
        }), 500
    
    return app
