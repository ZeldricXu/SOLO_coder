import os
import redis as redis_lib
from flask import Flask, render_template
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from flask_login import LoginManager, login_required
from flask_wtf.csrf import CSRFProtect
from flask_cors import CORS
from celery import Celery
from config import config

db = SQLAlchemy()
migrate = Migrate()
login_manager = LoginManager()
csrf = CSRFProtect()
cors = CORS()
redis_client = None
celery = None


def create_app(config_name='default'):
    global redis_client, celery

    app = Flask(__name__,
                template_folder=os.path.join(os.path.dirname(__file__), 'templates'),
                static_folder=os.path.join(os.path.dirname(__file__), 'static'))
    app.config.from_object(config[config_name])

    for folder in [app.config['UPLOAD_FOLDER'], app.config['EXPORT_FOLDER'], app.config['SNAPSHOT_FOLDER']]:
        os.makedirs(folder, exist_ok=True)

    db.init_app(app)
    migrate.init_app(app, db)
    login_manager.init_app(app)
    login_manager.login_view = 'auth.login'
    login_manager.login_message_category = 'warning'
    csrf.init_app(app)
    cors.init_app(app, resources={r"/api/*": {"origins": "*"}})

    redis_client = redis_lib.Redis.from_url(app.config['REDIS_URL'], decode_responses=True)

    celery = Celery(
        app.import_name,
        broker=app.config['CELERY_BROKER_URL'],
        backend=app.config['CELERY_RESULT_BACKEND']
    )
    celery.conf.update(app.config)

    class ContextTask(celery.Task):
        def __call__(self, *args, **kwargs):
            with app.app_context():
                return self.run(*args, **kwargs)

    celery.Task = ContextTask

    from app.models import User

    @login_manager.user_loader
    def load_user(user_id):
        return User.query.get(int(user_id))

    from app.api.auth import auth_bp
    from app.api.dashboard import dashboard_bp
    from app.api.datasource import datasource_bp
    from app.api.chart import chart_bp
    from app.api.template import template_bp
    from app.api.sse import sse_bp
    from app.api.share import share_bp
    from app.api.report import report_bp

    app.register_blueprint(auth_bp, url_prefix='/auth')
    app.register_blueprint(dashboard_bp, url_prefix='/api/dashboards')
    app.register_blueprint(datasource_bp, url_prefix='/api/datasources')
    app.register_blueprint(chart_bp, url_prefix='/api/charts')
    app.register_blueprint(template_bp, url_prefix='/api/templates')
    app.register_blueprint(sse_bp, url_prefix='/sse')
    app.register_blueprint(share_bp, url_prefix='/share')
    app.register_blueprint(report_bp, url_prefix='/api/reports')

    @app.route('/')
    def index():
        from flask_login import current_user
        if current_user.is_authenticated:
            from flask import redirect, url_for
            return redirect(url_for('dashboard_list'))
        return render_template('index.html')

    @app.route('/health')
    def health_check():
        from flask import jsonify
        status = {'status': 'healthy', 'services': {}}
        try:
            db.session.execute('SELECT 1')
            status['services']['database'] = 'healthy'
        except Exception as e:
            status['services']['database'] = f'unhealthy: {str(e)}'
            status['status'] = 'unhealthy'
        try:
            if redis_client:
                redis_client.ping()
                status['services']['redis'] = 'healthy'
            else:
                status['services']['redis'] = 'not configured'
        except Exception as e:
            status['services']['redis'] = f'unhealthy: {str(e)}'
            status['status'] = 'unhealthy'
        return jsonify(status), 200 if status['status'] == 'healthy' else 503

    @app.route('/dashboards')
    def dashboard_list():
        return render_template('dashboards/list.html')

    @app.route('/dashboards/<int:dashboard_id>')
    def view_dashboard(dashboard_id):
        from flask_login import current_user, login_required
        from app.models import Dashboard, Chart
        from app.services.dashboard_service import get_dashboard_charts
        from app.services.auth_service import can_edit_dashboard
        dashboard = Dashboard.query.get_or_404(dashboard_id)
        charts = get_dashboard_charts(dashboard_id)
        can_edit = can_edit_dashboard(current_user, dashboard)
        return render_template('dashboards/view.html', 
                             dashboard=dashboard, 
                             charts=charts,
                             can_edit=can_edit)

    @app.route('/dashboards/<int:dashboard_id>/edit')
    @login_required
    def edit_dashboard(dashboard_id):
        from flask_login import current_user
        from app.models import Dashboard, Chart, DataSource
        from app.services.dashboard_service import get_dashboard_charts
        from app.services.auth_service import can_edit_dashboard
        dashboard = Dashboard.query.get_or_404(dashboard_id)
        if not can_edit_dashboard(current_user, dashboard):
            from flask import abort
            abort(403)
        charts = get_dashboard_charts(dashboard_id)
        datasources = DataSource.query.filter_by(user_id=current_user.id).all()
        return render_template('dashboards/edit.html', 
                             dashboard=dashboard, 
                             charts=charts,
                             datasources=datasources)

    @app.route('/templates')
    def template_list():
        from app.models import Template
        categories = ['销售', '用户', '运营', '客服', '财务']
        return render_template('templates/list.html', categories=categories)

    @app.route('/datasources')
    def datasource_list():
        return render_template('datasources/list.html')

    @app.errorhandler(404)
    def not_found(error):
        return render_template('errors/404.html'), 404

    @app.errorhandler(403)
    def forbidden(error):
        return render_template('errors/403.html'), 403

    @app.errorhandler(500)
    def internal_error(error):
        db.session.rollback()
        return render_template('errors/500.html'), 500

    from app.utils.filters import register_filters
    register_filters(app)

    return app
