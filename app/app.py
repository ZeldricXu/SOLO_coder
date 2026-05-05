import logging
import os
from pathlib import Path
from logging.handlers import RotatingFileHandler

from flask import Flask
from flask_cors import CORS

from app import config


def setup_logging():
    log_dir = Path("logs")
    log_dir.mkdir(exist_ok=True)
    
    log_level = logging.DEBUG if config['server'].get('debug', False) else logging.INFO
    
    logging.basicConfig(
        level=log_level,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.StreamHandler(),
            RotatingFileHandler(
                log_dir / "app.log",
                maxBytes=10 * 1024 * 1024,
                backupCount=5,
                encoding='utf-8'
            )
        ]
    )
    
    logging.getLogger('werkzeug').setLevel(logging.WARNING)
    logging.getLogger('urllib3').setLevel(logging.WARNING)
    logging.getLogger('influxdb_client').setLevel(logging.WARNING)


def create_app(config_overrides=None):
    setup_logging()
    logger = logging.getLogger(__name__)
    logger.info("Starting MetricMonitor application...")
    
    app = Flask(__name__)
    
    app.config.update({
        'JSON_AS_ASCII': False,
        'JSON_SORT_KEYS': False
    })
    
    if config_overrides:
        app.config.update(config_overrides)
    
    CORS(app)
    
    from app.api.routes import api_bp
    app.register_blueprint(api_bp, url_prefix='/api/v1')
    
    @app.route('/')
    def index():
        return {
            "name": "MetricMonitor System",
            "version": "1.0.0",
            "description": "System metrics monitoring and alerting platform",
            "api_endpoint": "/api/v1",
            "health_check": "/api/v1/health"
        }
    
    @app.errorhandler(404)
    def not_found_error(error):
        return {
            "code": 404,
            "message": "Resource not found",
            "data": None
        }, 404
    
    @app.errorhandler(500)
    def internal_error(error):
        return {
            "code": 500,
            "message": "Internal server error",
            "data": None
        }, 500
    
    @app.errorhandler(Exception)
    def handle_exception(e):
        logger.exception("Unhandled exception")
        return {
            "code": 500,
            "message": str(e),
            "data": None
        }, 500
    
    logger.info("Application initialized successfully")
    return app
