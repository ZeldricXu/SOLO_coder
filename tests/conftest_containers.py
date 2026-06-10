import os
import time
import pytest


@pytest.fixture(scope='session')
def postgresql_container():
    pytest.importorskip('testcontainers.postgresql')
    from testcontainers.postgres import PostgresContainer

    with PostgresContainer('postgres:15-alpine') as container:
        container.start()
        time.sleep(5)

        connection_url = (
            f"postgresql+psycopg2://{container.get_username()}:"
            f"{container.get_password()}@{container.get_container_host_ip()}:"
            f"{container.get_exposed_port(5432)}/{container.get_dbname()}"
        )

        yield {
            'container': container,
            'url': connection_url,
            'host': container.get_container_host_ip(),
            'port': container.get_exposed_port(5432),
            'username': container.get_username(),
            'password': container.get_password(),
            'database': container.get_dbname()
        }


@pytest.fixture(scope='session')
def redis_container():
    pytest.importorskip('testcontainers.redis')
    from testcontainers.redis import RedisContainer

    with RedisContainer('redis:7-alpine') as container:
        container.start()
        time.sleep(2)

        redis_url = (
            f"redis://{container.get_container_host_ip()}:"
            f"{container.get_exposed_port(6379)}/0"
        )

        yield {
            'container': container,
            'url': redis_url,
            'host': container.get_container_host_ip(),
            'port': container.get_exposed_port(6379)
        }


@pytest.fixture(scope='session')
def mysql_container():
    pytest.importorskip('testcontainers.mysql')
    from testcontainers.mysql import MySqlContainer

    with MySqlContainer('mysql:8.0') as container:
        container.start()
        time.sleep(10)

        connection_config = {
            'host': container.get_container_host_ip(),
            'port': int(container.get_exposed_port(3306)),
            'username': container.username,
            'password': container.password,
            'database': container.dbname
        }

        yield {
            'container': container,
            'connection_config': connection_config,
            'host': container.get_container_host_ip(),
            'port': int(container.get_exposed_port(3306)),
            'username': container.username,
            'password': container.password,
            'database': container.dbname
        }


@pytest.fixture(scope='session')
def wiremock_container():
    pytest.importorskip('testcontainers.wiremock')
    try:
        from testcontainers.wiremock import WireMockContainer
    except ImportError:
        from testcontainers.generic import DockerContainer

        class WireMockContainer(DockerContainer):
            def __init__(self, image='wiremock/wiremock:3.3.1'):
                super().__init__(image)
                self.with_exposed_ports(8080)
                self.with_command('--verbose')

            def get_base_url(self):
                return f"http://{self.get_container_host_ip()}:{self.get_exposed_port(8080)}"

    with WireMockContainer('wiremock/wiremock:3.3.1') as container:
        container.start()
        time.sleep(3)

        import requests
        base_url = container.get_base_url() if hasattr(container, 'get_base_url') else \
            f"http://{container.get_container_host_ip()}:{container.get_exposed_port(8080)}"

        try:
            requests.post(
                f"{base_url}/__admin/mappings",
                json={
                    "request": {"method": "GET", "url": "/api/data"},
                    "response": {
                        "status": 200,
                        "jsonBody": {
                            "code": 0,
                            "data": [
                                {"date": "2024-01-01", "value": 100, "count": 10},
                                {"date": "2024-01-02", "value": 200, "count": 20},
                                {"date": "2024-01-03", "value": 300, "count": 30}
                            ]
                        },
                        "headers": {"Content-Type": "application/json"}
                    }
                }
            )

            requests.post(
                f"{base_url}/__admin/mappings",
                json={
                    "request": {"method": "GET", "url": "/api/error"},
                    "response": {"status": 500, "body": "Internal Server Error"}
                }
            )

            requests.post(
                f"{base_url}/__admin/mappings",
                json={
                    "request": {"method": "POST", "url": "/api/query"},
                    "response": {
                        "status": 200,
                        "jsonBody": {
                            "status": "success",
                            "data": {
                                "result": [
                                    {"metric": {"__name__": "test_metric"}, "values": [[1704067200, "100"], [1704153600, "200"]]}
                                ]
                            }
                        },
                        "headers": {"Content-Type": "application/json"}
                    }
                }
            )
        except Exception:
            pass

        yield {
            'container': container,
            'base_url': base_url,
            'host': container.get_container_host_ip(),
            'port': container.get_exposed_port(8080) if hasattr(container, 'get_exposed_port') else 8080
        }


@pytest.fixture(scope='session')
def app_with_containers(postgresql_container, redis_container):
    from app import create_app

    os.environ['DATABASE_URL'] = postgresql_container['url']
    os.environ['REDIS_URL'] = redis_container['url']
    os.environ['CELERY_BROKER_URL'] = redis_container['url'].replace('/0', '/1')
    os.environ['CELERY_RESULT_BACKEND'] = redis_container['url'].replace('/0', '/2')
    os.environ['FLASK_ENV'] = 'testing'

    app = create_app('testing')
    app.config['TESTING'] = True
    app.config['WTF_CSRF_ENABLED'] = False
    app.config['SQLALCHEMY_DATABASE_URI'] = postgresql_container['url']
    app.config['REDIS_URL'] = redis_container['url']

    with app.app_context():
        from app import db
        from app.services.init_service import init_database
        init_database()

        yield app

        db.session.remove()
        db.drop_all()
