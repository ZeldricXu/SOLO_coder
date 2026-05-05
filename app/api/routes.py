import os
import uuid
import tempfile
from datetime import datetime
from typing import Optional, Dict, Any

from flask import Blueprint, request, jsonify, send_from_directory, current_app
from werkzeug.utils import secure_filename

from app.core.data_import import DataImporter, SignalData
from app.core.filtering import FilterProcessor, FilterConfig, OrderAdvisor, OrderRecommendation
from app.core.spectrum import SpectrumAnalyzer, NormalizationMode
from app.core.features import FeatureExtractor
from app.core.visualization import Visualizer
from app.core.workflow import WorkflowManager, ProcessPipeline, ProcessResult
from app.core.signal_parser import SignalParser, SignalParserRegistry
from app.config import ALLOWED_EXTENSIONS, STATIC_IMAGE_DIR

api_bp = Blueprint("api", __name__)

workflow_manager = WorkflowManager()
process_pipeline = ProcessPipeline(workflow_manager)


def allowed_file(filename: str) -> bool:
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


def make_response(code: int, data: Any = None, message: str = "", warnings: Optional[List[str]] = None) -> Dict[str, Any]:
    response = {"code": code}
    if data is not None:
        response["data"] = data
    if message:
        response["message"] = message
    if warnings:
        response["warnings"] = warnings
    return response


@api_bp.route("/signal/import", methods=["POST"])
def import_signal():
    if "file" not in request.files:
        return jsonify(make_response(400, message="No file uploaded")), 400

    file = request.files["file"]
    if file.filename == "":
        return jsonify(make_response(400, message="No file selected")), 400

    if not allowed_file(file.filename):
        return jsonify(make_response(400, message="Invalid file type")), 400

    try:
        sample_rate = request.form.get("sample_rate", type=float)
        column_index = request.form.get("column_index", 0, type=int)
        file_format = request.form.get("format", "csv", type=str)
        has_header = request.form.get("has_header", "true", type=str).lower() == "true"
        skip_rows = request.form.get("skip_rows", 0, type=int)
        
        dtype = request.form.get("dtype", "float64", type=str)
        byte_order = request.form.get("byte_order", "<", type=str)
        skip_bytes = request.form.get("skip_bytes", 0, type=int)
        delimiter = request.form.get("delimiter", None, type=str)

        filename = secure_filename(file.filename)
        upload_dir = current_app.config.get("UPLOAD_FOLDER", tempfile.gettempdir())
        os.makedirs(upload_dir, exist_ok=True)
        temp_path = os.path.join(upload_dir, f"{uuid.uuid4().hex}_{filename}")
        file.save(temp_path)

        try:
            importer = DataImporter()
            
            if file_format.lower() == "csv":
                signal = importer.import_file(
                    temp_path,
                    file_format="csv",
                    sample_rate=sample_rate,
                    column_index=column_index,
                    has_header=has_header,
                    skip_rows=skip_rows,
                    delimiter=delimiter,
                )
            elif file_format.lower() == "binary":
                if sample_rate is None:
                    return jsonify(make_response(400, message="Sample rate required for binary files")), 400
                
                signal = importer.import_file(
                    temp_path,
                    file_format="binary",
                    sample_rate=sample_rate,
                    dtype=dtype,
                    byte_order=byte_order,
                    skip_bytes=skip_bytes,
                )
            else:
                os.remove(temp_path)
                return jsonify(make_response(400, message=f"Unsupported format: {file_format}")), 400

        finally:
            if os.path.exists(temp_path):
                os.remove(temp_path)

        result = {
            "signal_id": signal.signal_id,
            "name": signal.name,
            "sample_rate": signal.sample_rate,
            "duration": signal.duration,
            "data_points_count": len(signal.data_points),
            "format": signal.format,
            "imported_at": signal.imported_at,
            "parse_metadata": signal.parse_metadata,
        }

        return jsonify(make_response(200, data=result))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/signal/list", methods=["GET"])
def list_signals():
    try:
        signals = DataImporter.list_signals()
        return jsonify(make_response(200, data={"signals": signals}))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/signal/<signal_id>", methods=["GET"])
def get_signal(signal_id: str):
    try:
        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        include_data = request.args.get("include_data", "false", type=str).lower() == "true"
        
        result = signal.to_dict()
        if include_data:
            result["data_points"] = signal.data_points.tolist()

        return jsonify(make_response(200, data=result))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/signal/<signal_id>", methods=["DELETE"])
def delete_signal(signal_id: str):
    try:
        deleted = DataImporter.delete_signal(signal_id)
        if deleted:
            return jsonify(make_response(200, message="Signal deleted"))
        else:
            return jsonify(make_response(404, message="Signal not found")), 404
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/filter/recommend-order", methods=["POST"])
def recommend_filter_order():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            signal_data_length = data.get("data_length", 1000, type=int)
            sample_rate = data.get("sample_rate", 1000, type=float)
        else:
            signal = SignalData.load_from_file(signal_id)
            if signal is None:
                return jsonify(make_response(404, message="Signal not found")), 404
            signal_data_length = len(signal.data_points)
            sample_rate = signal.sample_rate

        filter_type = data.get("filter_type", "lowpass")
        cutoff_freq = data.get("cutoff_freq", sample_rate * 0.1, type=float)
        high_cutoff_freq = data.get("high_cutoff_freq", None, type=float)

        recommendation = OrderAdvisor.recommend_order(
            data_length=signal_data_length,
            sample_rate=sample_rate,
            filter_type=filter_type,
            cutoff_freq=cutoff_freq,
            high_cutoff_freq=high_cutoff_freq,
        )

        result = {
            "recommended_order": recommendation.recommended_order,
            "reason": recommendation.reason,
            "performance_impact": recommendation.performance_impact,
            "alternatives": recommendation.alternatives,
            "data_length": signal_data_length,
            "sample_rate": sample_rate,
            "max_safe_order": OrderAdvisor.MAX_SAFE_ORDER,
            "max_recommended_order": OrderAdvisor.MAX_RECOMMENDED_ORDER,
            "absolute_max_order": OrderAdvisor.ABSOLUTE_MAX_ORDER,
        }

        return jsonify(make_response(200, data=result))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/process/filter", methods=["POST"])
def process_filter():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        filter_type = data.get("filter_type")
        if not filter_type:
            return jsonify(make_response(400, message="filter_type is required")), 400

        cutoff_freq = data.get("cutoff_freq", type=float)
        high_cutoff_freq = data.get("high_cutoff_freq", type=float)
        order = data.get("order", None, type=int)
        method = data.get("method", "butterworth", type=str)
        use_auto_order = data.get("use_auto_order", True, type=bool)
        force_order = data.get("force_order", False, type=bool)

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if order is None or use_auto_order:
            recommendation = OrderAdvisor.recommend_order(
                data_length=len(signal.data_points),
                sample_rate=signal.sample_rate,
                filter_type=filter_type,
                cutoff_freq=cutoff_freq,
                high_cutoff_freq=high_cutoff_freq,
            )
            order = recommendation.recommended_order

        filter_config = {
            "filter_type": filter_type,
            "cutoff_freq": cutoff_freq,
            "high_cutoff_freq": high_cutoff_freq,
            "order": order,
            "method": method,
        }

        try:
            config = FilterConfig.from_dict(filter_config)
        except ValueError as e:
            return jsonify(make_response(400, message=str(e))), 400

        validation = FilterProcessor.validate_and_advise(
            config=config,
            data_length=len(signal.data_points),
            sample_rate=signal.sample_rate,
        )

        if not validation.valid:
            if force_order:
                pass
            else:
                error_data = {
                    "message": validation.message,
                    "recommended_order": None,
                    "alternatives": None,
                }
                if validation.order_recommendation:
                    error_data["recommended_order"] = validation.order_recommendation.recommended_order
                    error_data["alternatives"] = validation.order_recommendation.alternatives
                return jsonify(make_response(400, data=error_data, message=validation.message)), 400

        try:
            result = process_pipeline.run_full_pipeline(
                signal_id=signal_id,
                original_data=signal.data_points,
                sample_rate=signal.sample_rate,
                filter_config=filter_config,
            )
        except Exception as e:
            return jsonify(make_response(500, message=f"Processing error: {str(e)}")), 500

        response_data = {
            "result_id": result.result_id,
            "signal_id": result.signal_id,
            "filter_config": result.filter_config,
            "features": result.features,
            "processed_at": result.processed_at,
            "order_used": order,
        }

        if validation.order_recommendation:
            response_data["order_recommendation"] = {
                "recommended_order": validation.order_recommendation.recommended_order,
                "reason": validation.order_recommendation.reason,
                "performance_impact": validation.order_recommendation.performance_impact,
            }

        return jsonify(make_response(
            200,
            data=response_data,
            warnings=validation.warnings if validation.warnings else None,
        ))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/spectrum/normalization-modes", methods=["GET"])
def get_normalization_modes():
    try:
        modes = SpectrumAnalyzer.list_normalization_modes()
        return jsonify(make_response(200, data={"normalization_modes": modes}))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/process/spectrum", methods=["POST"])
def process_spectrum():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        use_filtered = data.get("use_filtered", False, type=bool)
        include_phase = data.get("include_phase", False, type=bool)
        normalization = data.get("normalization", "standard", type=str)

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if use_filtered:
            last_filtered = workflow_manager.list_results(signal_id=signal_id, limit=1)
            if not last_filtered:
                return jsonify(make_response(404, message="No filtered results found")), 404
            
            result_id = last_filtered[0]["result_id"]
            result = workflow_manager.get_result(result_id, load_data=True)
            if result is None or result.filtered_data is None:
                return jsonify(make_response(404, message="Filtered data not found")), 404
            
            data_array = result.filtered_data
        else:
            data_array = signal.data_points

        spectrum_result = SpectrumAnalyzer.compute_fft(
            data_array,
            signal.sample_rate,
            include_phase=include_phase,
            normalization=normalization,
        )

        peaks = SpectrumAnalyzer.find_peaks(spectrum_result)

        response_data = {
            "signal_id": signal_id,
            "sample_rate": signal.sample_rate,
            "spectrum": spectrum_result.to_dict(),
            "peaks": peaks,
            "peak_frequency": spectrum_result.get_peak_frequency()[0],
            "total_power": spectrum_result.get_total_power(),
            "normalization_used": normalization,
        }

        return jsonify(make_response(200, data=response_data))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/process/features", methods=["POST"])
def extract_features():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        use_filtered = data.get("use_filtered", False, type=bool)

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if use_filtered:
            last_filtered = workflow_manager.list_results(signal_id=signal_id, limit=1)
            if not last_filtered:
                return jsonify(make_response(404, message="No filtered results found")), 404
            
            result_id = last_filtered[0]["result_id"]
            result = workflow_manager.get_result(result_id, load_data=True)
            if result is None or result.filtered_data is None:
                return jsonify(make_response(404, message="Filtered data not found")), 404
            
            data_array = result.filtered_data
        else:
            data_array = signal.data_points

        features = FeatureExtractor.extract_all_features(
            data_array,
            signal.sample_rate,
        )

        response_data = {
            "signal_id": signal_id,
            "features": features.to_dict(),
        }

        return jsonify(make_response(200, data=response_data))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/parser/formats", methods=["GET"])
def get_parser_formats():
    try:
        formats = SignalParser.get_supported_formats()
        valid_dtypes = SignalParser.get_valid_dtypes()
        
        result = {
            "supported_formats": formats,
            "valid_dtypes": list(valid_dtypes.keys()),
        }
        return jsonify(make_response(200, data=result))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/visualize/waveform", methods=["POST"])
def visualize_waveform():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        use_filtered = data.get("use_filtered", False, type=bool)
        use_plotly = data.get("use_plotly", False, type=bool)
        title = data.get("title", "Signal Waveform")

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if use_filtered:
            last_filtered = workflow_manager.list_results(signal_id=signal_id, limit=1)
            if not last_filtered:
                return jsonify(make_response(404, message="No filtered results found")), 404
            
            result_id = last_filtered[0]["result_id"]
            result = workflow_manager.get_result(result_id, load_data=True)
            if result is None or result.filtered_data is None:
                return jsonify(make_response(404, message="Filtered data not found")), 404
            
            data_array = result.filtered_data
        else:
            data_array = signal.data_points

        if use_plotly:
            plot_data = Visualizer.plot_waveform_plotly(
                data_array,
                signal.sample_rate,
                title=title,
            )
            return jsonify(make_response(200, data={"plot_type": "plotly", "plot": plot_data}))
        else:
            filename = Visualizer.plot_waveform_matplotlib(
                data_array,
                signal.sample_rate,
                title=title,
            )
            return jsonify(make_response(200, data={"plot_type": "matplotlib", "image_url": f"/static/images/{filename}"}))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/visualize/spectrum", methods=["POST"])
def visualize_spectrum():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        use_filtered = data.get("use_filtered", False, type=bool)
        use_plotly = data.get("use_plotly", False, type=bool)
        title = data.get("title", "Frequency Spectrum")
        normalization = data.get("normalization", "standard", type=str)

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if use_filtered:
            last_filtered = workflow_manager.list_results(signal_id=signal_id, limit=1)
            if not last_filtered:
                return jsonify(make_response(404, message="No filtered results found")), 404
            
            result_id = last_filtered[0]["result_id"]
            result = workflow_manager.get_result(result_id, load_data=True)
            if result is None or result.filtered_data is None:
                return jsonify(make_response(404, message="Filtered data not found")), 404
            
            data_array = result.filtered_data
        else:
            data_array = signal.data_points

        spectrum_result = SpectrumAnalyzer.compute_fft(
            data_array,
            signal.sample_rate,
            normalization=normalization,
        )

        if use_plotly:
            plot_data = Visualizer.plot_spectrum_plotly(
                spectrum_result.frequencies,
                spectrum_result.amplitudes,
                title=title,
            )
            response_data = {
                "plot_type": "plotly",
                "plot": plot_data,
                "normalization_used": normalization,
            }
            return jsonify(make_response(200, data=response_data))
        else:
            filename = Visualizer.plot_spectrum_matplotlib(
                spectrum_result.frequencies,
                spectrum_result.amplitudes,
                title=title,
            )
            response_data = {
                "plot_type": "matplotlib",
                "image_url": f"/static/images/{filename}",
                "normalization_used": normalization,
            }
            return jsonify(make_response(200, data=response_data))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/visualize/combined", methods=["POST"])
def visualize_combined():
    try:
        data = request.get_json()
        if not data:
            return jsonify(make_response(400, message="Invalid JSON")), 400

        signal_id = data.get("signal_id")
        if not signal_id:
            return jsonify(make_response(400, message="signal_id is required")), 400

        use_filtered = data.get("use_filtered", False, type=bool)
        use_plotly = data.get("use_plotly", False, type=bool)
        title = data.get("title", "Signal Analysis")
        normalization = data.get("normalization", "standard", type=str)

        signal = SignalData.load_from_file(signal_id)
        if signal is None:
            return jsonify(make_response(404, message="Signal not found")), 404

        if use_filtered:
            last_filtered = workflow_manager.list_results(signal_id=signal_id, limit=1)
            if not last_filtered:
                return jsonify(make_response(404, message="No filtered results found")), 404
            
            result_id = last_filtered[0]["result_id"]
            result = workflow_manager.get_result(result_id, load_data=True)
            if result is None or result.filtered_data is None:
                return jsonify(make_response(404, message="Filtered data not found")), 404
            
            data_array = result.filtered_data
        else:
            data_array = signal.data_points

        spectrum_result = SpectrumAnalyzer.compute_fft(
            data_array,
            signal.sample_rate,
            normalization=normalization,
        )

        if use_plotly:
            plot_data = Visualizer.plot_combined_plotly(
                data_array,
                spectrum_result.frequencies,
                spectrum_result.amplitudes,
                signal.sample_rate,
                title=title,
            )
            response_data = {
                "plot_type": "plotly",
                "plot": plot_data,
                "normalization_used": normalization,
            }
            return jsonify(make_response(200, data=response_data))
        else:
            filename = Visualizer.plot_combined_matplotlib(
                data_array,
                spectrum_result.frequencies,
                spectrum_result.amplitudes,
                signal.sample_rate,
                title=title,
            )
            response_data = {
                "plot_type": "matplotlib",
                "image_url": f"/static/images/{filename}",
                "normalization_used": normalization,
            }
            return jsonify(make_response(200, data=response_data))

    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/results", methods=["GET"])
def list_results():
    try:
        signal_id = request.args.get("signal_id")
        limit = request.args.get("limit", 50, type=int)
        offset = request.args.get("offset", 0, type=int)

        results = workflow_manager.list_results(
            signal_id=signal_id,
            limit=limit,
            offset=offset,
        )

        return jsonify(make_response(200, data={"results": results, "count": len(results)}))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/results/<result_id>", methods=["GET"])
def get_result(result_id: str):
    try:
        load_data = request.args.get("load_data", "false", type=str).lower() == "true"
        result = workflow_manager.get_result(result_id, load_data=load_data)
        
        if result is None:
            return jsonify(make_response(404, message="Result not found")), 404

        response_data = result.to_dict()
        return jsonify(make_response(200, data=response_data))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/results/<result_id>", methods=["DELETE"])
def delete_result(result_id: str):
    try:
        deleted = workflow_manager.delete_result(result_id)
        if deleted:
            return jsonify(make_response(200, message="Result deleted"))
        else:
            return jsonify(make_response(404, message="Result not found")), 404
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/statistics", methods=["GET"])
def get_statistics():
    try:
        stats = workflow_manager.get_statistics()
        return jsonify(make_response(200, data=stats))
    except Exception as e:
        return jsonify(make_response(500, message=str(e))), 500


@api_bp.route("/health", methods=["GET"])
def health_check():
    return jsonify(make_response(200, data={"status": "ok", "timestamp": datetime.now().isoformat()}))
