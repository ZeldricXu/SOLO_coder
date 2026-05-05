from flask import Flask, jsonify
from flask_cors import CORS
import logging
import sys

from config import get_config
from modelserve.api import api_bp


def create_app(config_class=None) -> Flask:
    app = Flask(__name__)

    if config_class is None:
        config_class = get_config()

    app.config.from_object(config_class)

    CORS(app, resources={r"/api/*": {"origins": "*"}})

    setup_logging(app)

    app.register_blueprint(api_bp, url_prefix='/api/v1')

    @app.route('/')
    def index():
        return jsonify({
            "name": "ModelServe",
            "version": "1.0.0",
            "description": "Machine Learning Model Serving Platform",
            "api_endpoints": {
                "health": "/api/v1/health",
                "models": "/api/v1/models",
                "versions": "/api/v1/models/versions",
                "deploy": "/api/v1/models/deploy",
                "inference": "/api/v1/models/inference",
                "stats": "/api/v1/models/stats",
                "trainings": "/api/v1/trainings"
            }
        })

    @app.route('/health')
    def health():
        return jsonify({"status": "healthy", "service": "ModelServe"})

    @app.errorhandler(404)
    def not_found(error):
        return jsonify({
            "code": 404,
            "message": "Resource not found",
            "error": str(error)
        }), 404

    @app.errorhandler(500)
    def internal_error(error):
        return jsonify({
            "code": 500,
            "message": "Internal server error",
            "error": str(error)
        }), 500

    @app.errorhandler(400)
    def bad_request(error):
        return jsonify({
            "code": 400,
            "message": "Bad request",
            "error": str(error)
        }), 400

    return app


def setup_logging(app: Flask):
    log_level = app.config.get('LOG_LEVEL', 'INFO')
    log_format = app.config.get('LOG_FORMAT', '%(asctime)s - %(name)s - %(levelname)s - %(message)s')

    logging.basicConfig(
        level=getattr(logging, log_level),
        format=log_format,
        handlers=[
            logging.StreamHandler(sys.stdout)
        ]
    )

    logger = logging.getLogger('modelserve')
    logger.setLevel(getattr(logging, log_level))

    app.logger.info(f"ModelServe logging initialized with level: {log_level}")


def run_server():
    config = get_config()
    app = create_app(config)

    host = config.FLASK_HOST
    port = config.FLASK_PORT
    debug = config.FLASK_DEBUG

    app.logger.info(f"Starting ModelServe server on {host}:{port}")
    app.logger.info(f"Debug mode: {debug}")

    app.run(
        host=host,
        port=port,
        debug=debug,
        threaded=True
    )


if __name__ == '__main__':
    run_server()
