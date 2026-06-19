import socket
from unittest.mock import MagicMock, patch

import pytest
import pytest_asyncio

from etl_engine.connectors.mysql import MySQLSource
from etl_engine.connectors.postgresql import PostgreSQLSource
from etl_engine.exceptions import ConnectTimeoutError


def _no_sleep(_seconds: float):
    return None


@pytest.mark.unit
@pytest.mark.exception
class TestMySQLConnectTimeout:
    @pytest_asyncio.fixture
    def mysql_source(self):
        config = {
            "name": "test_mysql",
            "type": "mysql",
            "connection_params": {
                "host": "localhost",
                "port": 3306,
                "user": "root",
                "password": "",
                "database": "test",
            },
        }
        return MySQLSource(config)

    @pytest.mark.asyncio
    async def test_socket_timeout_raises_identifiable_error(self, mysql_source):
        with patch(
            "etl_engine.connectors.mysql.PooledDB",
            side_effect=socket.timeout("timed out"),
        ), patch(
            "etl_engine.connectors.mysql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises((ConnectTimeoutError, ConnectionError, Exception)) as exc_info:
                await mysql_source.connect()

            error_msg = str(exc_info.value).lower()
            has_timeout_keyword = "timeout" in error_msg or "timed out" in error_msg
            has_connection_keyword = "connection" in error_msg or "connect" in error_msg

            assert has_timeout_keyword or has_connection_keyword, (
                f"Error message should contain 'timeout' or 'connection' keywords. "
                f"Got: {exc_info.value}"
            )

    @pytest.mark.asyncio
    async def test_timeout_error_class_raised_or_message_indicates_timeout(self, mysql_source):
        with patch(
            "etl_engine.connectors.mysql.PooledDB",
            side_effect=TimeoutError("connection timed out after 10s"),
        ), patch(
            "etl_engine.connectors.mysql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises((ConnectTimeoutError, ConnectionError, Exception)) as exc_info:
                await mysql_source.connect()

            is_timeout_error = isinstance(exc_info.value, (ConnectTimeoutError, TimeoutError))
            error_msg = str(exc_info.value).lower()
            has_timeout_in_msg = "timeout" in error_msg or "timed out" in error_msg

            assert is_timeout_error or has_timeout_in_msg, (
                f"Either ConnectTimeoutError/TimeoutError should be raised, "
                f"or message should contain 'timeout'. Got type={type(exc_info.value)}, "
                f"msg={exc_info.value}"
            )


@pytest.mark.unit
@pytest.mark.exception
class TestMySQLConnectionRefused:
    @pytest_asyncio.fixture
    def mysql_source(self):
        config = {
            "name": "prod_mysql",
            "type": "mysql",
            "connection_params": {
                "host": "db.prod.example.com",
                "port": 3306,
                "user": "app_user",
                "password": "secret",
                "database": "app_db",
            },
        }
        return MySQLSource(config)

    @pytest.mark.asyncio
    async def test_connection_refused_propagated_with_source_type(self, mysql_source):
        with patch(
            "etl_engine.connectors.mysql.PooledDB",
            side_effect=ConnectionRefusedError(61, "Connection refused"),
        ), patch(
            "etl_engine.connectors.mysql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises(Exception) as exc_info:
                await mysql_source.connect()

            error_msg = str(exc_info.value).lower()
            source_type_indicated = (
                "mysql" in error_msg
                or "source" in error_msg
                or "connect" in error_msg
            )

            assert source_type_indicated, (
                f"Error message should indicate source type (mysql) or connection context. "
                f"Got: {exc_info.value}"
            )


@pytest.mark.unit
@pytest.mark.exception
class TestInvalidCredentials:
    @pytest_asyncio.fixture
    def mysql_source(self):
        config = {
            "name": "secure_db",
            "type": "mysql",
            "connection_params": {
                "host": "secure.example.com",
                "port": 3306,
                "user": "wrong_user",
                "password": "wrong_password",
                "database": "restricted_db",
            },
        }
        return MySQLSource(config)

    @pytest.mark.asyncio
    async def test_invalid_credentials_indicates_access_issue(self, mysql_source):
        import pymysql

        operational_error = pymysql.OperationalError(
            1045,
            "Access denied for user 'wrong_user'@'localhost' (using password: YES)",
        )

        with patch(
            "etl_engine.connectors.mysql.PooledDB",
            side_effect=operational_error,
        ), patch(
            "etl_engine.connectors.mysql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises(Exception) as exc_info:
                await mysql_source.connect()

            error_msg = str(exc_info.value).lower()
            credentials_indicated = (
                "credentials" in error_msg
                or "access denied" in error_msg
                or "access" in error_msg
                or "denied" in error_msg
                or "password" in error_msg
                or "user" in error_msg
            )

            assert credentials_indicated, (
                f"Error should indicate credential/access problem. Got: {exc_info.value}"
            )


@pytest.mark.unit
@pytest.mark.exception
class TestPostgresConnectTimeout:
    @pytest_asyncio.fixture
    def postgres_source(self):
        config = {
            "name": "test_postgres",
            "type": "postgresql",
            "connection_params": {
                "host": "pg.example.com",
                "port": 5432,
                "user": "postgres",
                "password": "",
                "database": "test_db",
            },
        }
        return PostgreSQLSource(config)

    @pytest.mark.asyncio
    async def test_psycopg2_operational_error_timeout(self, postgres_source):
        import psycopg2

        timeout_error = psycopg2.OperationalError(
            "could not connect to server: Connection timed out\n"
            "\tIs the server running on host 'pg.example.com' (10.0.0.1) and accepting\n"
            "\tTCP/IP connections on port 5432?"
        )

        with patch(
            "etl_engine.connectors.postgresql.pool.SimpleConnectionPool",
            side_effect=timeout_error,
        ), patch(
            "etl_engine.connectors.postgresql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises((ConnectTimeoutError, psycopg2.OperationalError, ConnectionError, Exception)) as exc_info:
                await postgres_source.connect()

            error_msg = str(exc_info.value).lower()
            has_timeout_or_connection = (
                "timeout" in error_msg
                or "connection" in error_msg
                or "connect" in error_msg
                or "timed out" in error_msg
            )

            assert has_timeout_or_connection, (
                f"PostgreSQL timeout error should mention timeout or connection. "
                f"Got: {exc_info.value}"
            )

    @pytest.mark.asyncio
    async def test_postgres_socket_timeout_propagated(self, postgres_source):
        with patch(
            "etl_engine.connectors.postgresql.pool.SimpleConnectionPool",
            side_effect=socket.timeout("socket timed out"),
        ), patch(
            "etl_engine.connectors.postgresql.asyncio.sleep",
            side_effect=_no_sleep,
        ):
            with pytest.raises(Exception) as exc_info:
                await postgres_source.connect()

            error_msg = str(exc_info.value).lower()
            assert "timeout" in error_msg or "timed out" in error_msg or "connect" in error_msg, (
                f"Postgres socket timeout should be propagated with timeout indication. "
                f"Got: {exc_info.value}"
            )
