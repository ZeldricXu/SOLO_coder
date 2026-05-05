import os
from flask import Flask, jsonify, send_from_directory
from flask_cors import CORS

from app.config import (
    FLASK_DEBUG,
    SECRET_KEY,
    UPLOAD_FOLDER,
    MAX_CONTENT_LENGTH,
    STATIC_IMAGE_DIR,
    ensure_directories,
)
from app.api.routes import api_bp


def create_app() -> Flask:
    ensure_directories()

    app = Flask(
        __name__,
        static_folder="app/static",
        static_url_path="/static",
    )

    app.config["SECRET_KEY"] = SECRET_KEY
    app.config["UPLOAD_FOLDER"] = UPLOAD_FOLDER
    app.config["MAX_CONTENT_LENGTH"] = MAX_CONTENT_LENGTH
    app.config["DEBUG"] = FLASK_DEBUG

    CORS(app, resources={r"/api/*": {"origins": "*"}})

    app.register_blueprint(api_bp, url_prefix="/api/v1")

    @app.route("/")
    def index():
        return jsonify({
            "name": "SignalProcess Platform",
            "version": "1.0.0",
            "description": "Signal Data Processing and Filtering Analysis Platform",
            "endpoints": {
                "signal": {
                    "import": "POST /api/v1/signal/import",
                    "list": "GET /api/v1/signal/list",
                    "get": "GET /api/v1/signal/<signal_id>",
                    "delete": "DELETE /api/v1/signal/<signal_id>",
                },
                "process": {
                    "filter": "POST /api/v1/process/filter",
                    "spectrum": "POST /api/v1/process/spectrum",
                    "features": "POST /api/v1/process/features",
                },
                "visualize": {
                    "waveform": "POST /api/v1/visualize/waveform",
                    "spectrum": "POST /api/v1/visualize/spectrum",
                    "combined": "POST /api/v1/visualize/combined",
                },
                "results": {
                    "list": "GET /api/v1/results",
                    "get": "GET /api/v1/results/<result_id>",
                    "delete": "DELETE /api/v1/results/<result_id>",
                },
                "statistics": "GET /api/v1/statistics",
                "health": "GET /api/v1/health",
            }
        })

    @app.route("/static/images/<filename>")
    def serve_image(filename: str):
        return send_from_directory(STATIC_IMAGE_DIR, filename)

    @app.errorhandler(404)
    def not_found(error):
        return jsonify({"code": 404, "message": "Not found"}), 404

    @app.errorhandler(500)
    def internal_error(error):
        return jsonify({"code": 500, "message": "Internal server error"}), 500

    return app


if __name__ == "__main__":
    app = create_app()
    app.run(host="0.0.0.0", port=5000, debug=True)
