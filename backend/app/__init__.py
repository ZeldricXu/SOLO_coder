from flask import Flask
from flask_cors import CORS
import os

def create_app():
    app = Flask(__name__)
    CORS(app)
    
    app.config['UPLOAD_FOLDER'] = os.path.join(os.path.dirname(__file__), '..', 'uploads')
    app.config['EXPORT_FOLDER'] = os.path.join(os.path.dirname(__file__), '..', 'exports')
    app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024
    app.config['SECRET_KEY'] = 'survey-analytics-secret-key-2024'
    
    os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)
    os.makedirs(app.config['EXPORT_FOLDER'], exist_ok=True)
    
    from app.routes import survey_bp, analysis_bp, report_bp
    app.register_blueprint(survey_bp, url_prefix='/api/v1')
    app.register_blueprint(analysis_bp, url_prefix='/api/v1')
    app.register_blueprint(report_bp, url_prefix='/api/v1')
    
    return app
