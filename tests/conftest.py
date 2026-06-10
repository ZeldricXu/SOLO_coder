import os
import sys
import pytest
import asyncio
from unittest.mock import Mock, patch
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import create_app, db as _db
from app.models import User, Role, Team, TeamMember, Dashboard, Chart, DataSource


@pytest.fixture(scope='session')
def event_loop():
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope='session')
def app():
    os.environ['FLASK_ENV'] = 'testing'
    app = create_app('testing')
    app.config['TESTING'] = True
    app.config['WTF_CSRF_ENABLED'] = False
    app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///:memory:'

    with app.app_context():
        _db.create_all()
        _init_test_roles()
        _init_default_team()
        yield app
        _db.session.remove()
        _db.drop_all()


@pytest.fixture(scope='function')
def client(app):
    return app.test_client()


@pytest.fixture(scope='function')
def db(app):
    with app.app_context():
        _db.create_all()
        _init_test_roles()
        _init_default_team()
        yield _db
        _db.session.remove()
        _db.drop_all()


@pytest.fixture(scope='function')
def db_session(db, app):
    with app.app_context():
        yield db.session
        db.session.rollback()


def _init_test_roles():
    roles = [
        {'name': 'admin', 'description': '系统管理员'},
        {'name': 'editor', 'description': '编辑者'},
        {'name': 'viewer', 'description': '查看者'},
    ]
    for role_data in roles:
        if not Role.query.filter_by(name=role_data['name']).first():
            role = Role(**role_data)
            _db.session.add(role)
    _db.session.commit()


def _init_default_team():
    if not Team.query.filter_by(name='默认团队').first():
        team = Team(name='默认团队', description='系统默认团队', created_by=None)
        _db.session.add(team)
        _db.session.commit()


@pytest.fixture
def role_admin(db):
    with db.engine.connect() as conn:
        pass
    return Role.query.filter_by(name='admin').first()


@pytest.fixture
def role_editor(db):
    return Role.query.filter_by(name='editor').first()


@pytest.fixture
def role_viewer(db):
    return Role.query.filter_by(name='viewer').first()


@pytest.fixture
def default_team(db):
    return Team.query.filter_by(name='默认团队').first()


@pytest.fixture
def test_user(db_session, role_editor, default_team):
    from app.services.auth_service import register_user
    user = register_user('test@example.com', 'password123', 'Test User')
    user.role_id = role_editor.id
    db_session.commit()
    return user


@pytest.fixture
def test_user2(db_session, role_editor, default_team):
    from app.services.auth_service import register_user
    user = register_user('test2@example.com', 'password123', 'Test User 2')
    user.role_id = role_editor.id
    db_session.commit()
    return user


@pytest.fixture
def logged_in_client(client, test_user):
    with client.session_transaction() as session:
        session['user_id'] = test_user.id
        session['_user_id'] = str(test_user.id)
    return client


@pytest.fixture
def mock_redis():
    with patch('app.redis_client') as mock:
        mock.get.return_value = None
        mock.setex.return_value = True
        mock.ping.return_value = True
        mock.delete.return_value = True
        yield mock


@pytest.fixture
def sample_dashboard(db_session, test_user, default_team):
    from app.services.dashboard_service import create_dashboard
    layout_config = {
        'grid': {'cols': 12, 'rowHeight': 50},
        'widgets': []
    }
    return create_dashboard(
        user_id=test_user.id,
        name='测试看板',
        description='这是一个测试看板',
        team_id=default_team.id,
        layout_config=layout_config
    )


@pytest.fixture
def sample_datasource_mysql(db_session, test_user, default_team):
    from app.services.datasource_service import create_datasource
    return create_datasource(
        user_id=test_user.id,
        name='MySQL测试数据源',
        type='mysql',
        connection_config={
            'host': 'localhost',
            'port': 3306,
            'username': 'test',
            'password': 'test',
            'database': 'test_db'
        },
        team_id=default_team.id
    )


@pytest.fixture
def sample_datasource_clickhouse(db_session, test_user, default_team):
    from app.services.datasource_service import create_datasource
    return create_datasource(
        user_id=test_user.id,
        name='ClickHouse测试数据源',
        type='clickhouse',
        connection_config={
            'host': 'localhost',
            'port': 9000,
            'username': 'default',
            'password': '',
            'database': 'default'
        },
        team_id=default_team.id
    )


@pytest.fixture
def sample_datasource_prometheus(db_session, test_user, default_team):
    from app.services.datasource_service import create_datasource
    return create_datasource(
        user_id=test_user.id,
        name='Prometheus测试数据源',
        type='prometheus',
        connection_config={
            'base_url': 'http://localhost:9090',
            'username': '',
            'password': ''
        },
        team_id=default_team.id
    )


@pytest.fixture
def sample_datasource_http(db_session, test_user, default_team):
    from app.services.datasource_service import create_datasource
    return create_datasource(
        user_id=test_user.id,
        name='HTTP测试数据源',
        type='http',
        connection_config={
            'url': 'http://localhost:8080/api/data',
            'method': 'GET',
            'headers': {},
            'data_path': 'data',
            'category_key': 'date',
            'value_key': 'value'
        },
        team_id=default_team.id
    )


@pytest.fixture
def sample_chart(db_session, test_user, sample_dashboard, sample_datasource_mysql):
    from app.services.chart_service import create_chart
    return create_chart(
        user_id=test_user.id,
        dashboard_id=sample_dashboard.id,
        name='销售趋势图',
        chart_type='line',
        datasource_id=sample_datasource_mysql.id,
        query_template='SELECT date, amount FROM sales WHERE date >= {{start_date}}',
        query_params={'start_date': '2024-01-01'},
        position={'x': 0, 'y': 0, 'w': 6, 'h': 4}
    )


@pytest.fixture
def sample_layout_config():
    return {
        'grid': {'cols': 12, 'rowHeight': 50},
        'widgets': [
            {
                'id': 'widget-1',
                'chart_id': 1,
                'x': 0, 'y': 0, 'w': 6, 'h': 4,
                'minW': 3, 'minH': 2,
                'maxW': 12, 'maxH': 12
            },
            {
                'id': 'widget-2',
                'chart_id': 2,
                'x': 6, 'y': 0, 'w': 6, 'h': 4,
                'minW': 3, 'minH': 2
            }
        ],
        'version': '1.0',
        'created_at': datetime.utcnow().isoformat()
    }


@pytest.fixture
def sample_query_result():
    return {
        'success': True,
        'data': {
            'rows': [
                {'date': '2024-01-01', 'amount': 1000, 'orders': 50},
                {'date': '2024-01-02', 'amount': 1500, 'orders': 75},
                {'date': '2024-01-03', 'amount': 2000, 'orders': 100}
            ],
            'columns': ['date', 'amount', 'orders'],
            'categories': ['2024-01-01', '2024-01-02', '2024-01-03'],
            'values': [1000, 1500, 2000],
            'series': [
                {'name': 'amount', 'data': [1000, 1500, 2000]},
                {'name': 'orders', 'data': [50, 75, 100]}
            ],
            'row_count': 3
        },
        'execution_time': 12.5
    }


@pytest.fixture
def mock_mysql_connection():
    import sys
    mock_mysql = Mock()
    mock_conn = Mock()
    mock_cursor = Mock()
    mock_cursor.description = [('date',), ('amount',), ('orders',)]
    mock_cursor.fetchall.return_value = [
        {'date': '2024-01-01', 'amount': 1000, 'orders': 50},
        {'date': '2024-01-02', 'amount': 1500, 'orders': 75},
        {'date': '2024-01-03', 'amount': 2000, 'orders': 100}
    ]
    mock_conn.cursor.return_value = mock_cursor
    mock_mysql.connector.connect = Mock(return_value=mock_conn)
    sys.modules['mysql'] = mock_mysql
    sys.modules['mysql.connector'] = mock_mysql.connector
    mock_errors = Mock()
    mock_errors.Error = Exception
    mock_errors.InterfaceError = Exception
    sys.modules['mysql.connector.errors'] = mock_errors
    yield mock_mysql.connector.connect


@pytest.fixture
def mock_clickhouse_client():
    import sys
    mock_clickhouse = Mock()
    mock_client = Mock()
    mock_client.execute.return_value = (
        [('2024-01-01', 1000, 50), ('2024-01-02', 1500, 75), ('2024-01-03', 2000, 100)],
        [('date', 'String'), ('amount', 'Int64'), ('orders', 'Int64')]
    )
    mock_clickhouse.driver.Client = Mock(return_value=mock_client)
    sys.modules['clickhouse_driver'] = mock_clickhouse.driver
    sys.modules['clickhouse_driver.errors'] = Mock()
    yield mock_clickhouse.driver.Client


@pytest.fixture
def mock_requests():
    import sys
    mock_requests_lib = Mock()
    mock_response = Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        'status': 'success',
        'data': {
            'result': [{
                'metric': {'__name__': 'http_requests_total'},
                'values': [
                    [1704067200, '100'],
                    [1704153600, '150'],
                    [1704240000, '200']
                ]
            }]
        }
    }
    mock_response.text = 'OK'
    mock_requests_lib.request.return_value = mock_response
    mock_requests_lib.get.return_value = mock_response
    mock_requests_lib.post.return_value = mock_response
    mock_requests_lib.exceptions = Mock()
    mock_requests_lib.exceptions.Timeout = Exception
    mock_requests_lib.exceptions.RequestException = Exception
    sys.modules['requests'] = mock_requests_lib
    yield mock_requests_lib.request


@pytest.fixture
def sample_chart_types():
    return ['line', 'bar', 'pie', 'heatmap', 'funnel', 'scatter', 'gauge']


@pytest.fixture
def mock_redis(monkeypatch):
    import app
    mock_redis_client = Mock()
    mock_redis_client.get.return_value = None
    mock_redis_client.setex.return_value = True
    mock_redis_client.set.return_value = True
    mock_redis_client.delete.return_value = 1
    mock_redis_client.ping.return_value = True
    mock_redis_client.publish.return_value = 1
    mock_redis_client.keys.return_value = []
    mock_redis_client.info.return_value = {}
    mock_redis_client.flushdb.return_value = True

    original_redis = app.redis_client
    app.redis_client = mock_redis_client
    monkeypatch.setattr(app, 'redis_client', mock_redis_client)

    import app.services.datasource_service
    monkeypatch.setattr(app.services.datasource_service, 'redis_client', mock_redis_client)

    import app.api.sse
    monkeypatch.setattr(app.api.sse, 'redis_client', mock_redis_client)

    import app.utils.decorators
    monkeypatch.setattr(app.utils.decorators, 'redis_client', mock_redis_client)

    yield mock_redis_client

    app.redis_client = original_redis
