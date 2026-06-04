import json
import os
import tempfile
import sqlite3
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from click.testing import CliRunner

from devkit.cli import cli


class TestDbQuery:
    def test_sqlite_query_table_format(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, email TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice', 'alice@test.com')")
        conn.execute("INSERT INTO users VALUES (2, 'Bob', 'bob@test.com')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "SELECT * FROM users ORDER BY id",
            "--type", "sqlite", "--path", str(db_file), "--format", "table"
        ])
        assert result.exit_code == 0
        assert "Alice" in result.output
        assert "Bob" in result.output
        assert "alice@test.com" in result.output
        assert "2 rows returned" in result.output

    def test_sqlite_query_json_format(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "SELECT * FROM users",
            "--type", "sqlite", "--path", str(db_file), "--format", "json"
        ])
        assert result.exit_code == 0
        stripped = result.output.strip()
        data = json.loads(stripped)
        assert len(data) == 1
        assert data[0]["name"] == "Alice"

    def test_sqlite_query_csv_format(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "SELECT * FROM users",
            "--type", "sqlite", "--path", str(db_file), "--format", "csv"
        ])
        assert result.exit_code == 0
        lines = result.output.strip().split("\n")
        assert lines[0] == "id,name"
        assert "1,Alice" in lines

    def test_sqlite_query_vertical_format(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "SELECT * FROM users",
            "--type", "sqlite", "--path", str(db_file), "--format", "vertical"
        ])
        assert result.exit_code == 0
        assert "1. row" in result.output
        assert "id" in result.output
        assert "1" in result.output
        assert "name" in result.output
        assert "Alice" in result.output

    def test_sqlite_insert_query(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "INSERT INTO users VALUES (1, 'Alice')",
            "--type", "sqlite", "--path", str(db_file)
        ])
        assert result.exit_code == 0

        conn = sqlite3.connect(db_file)
        row = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        assert row[1] == "Alice"
        conn.close()

    def test_sqlite_in_memory(self, runner):
        result = runner.invoke(cli, [
            "db", "query", "SELECT 1 as id, 'hello' as msg",
            "--type", "sqlite", "--path", ":memory:"
        ])
        assert result.exit_code == 0
        assert "hello" in result.output

    def test_sqlite_output_to_file(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        output_file = tmp_path / "output.json"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "query", "SELECT * FROM users",
            "--type", "sqlite", "--path", str(db_file),
            "--format", "json", "--output", str(output_file)
        ])
        assert result.exit_code == 0
        assert output_file.exists()
        with open(output_file) as f:
            data = json.load(f)
            assert data[0]["name"] == "Alice"

    def test_sqlite_invalid_sql(self, runner):
        result = runner.invoke(cli, [
            "db", "query", "INVALID SQL",
            "--type", "sqlite", "--path", ":memory:"
        ])
        assert result.exit_code == 0
        assert "Error:" in result.output

    def test_sqlite_stdin_input(self, runner):
        result = runner.invoke(cli, [
            "db", "query", "--type", "sqlite", "--path", ":memory:", "--format", "json"
        ], input="SELECT 1 as id")
        assert result.exit_code == 0
        stripped = result.output.strip()
        data = json.loads(stripped)
        assert data[0]["id"] == 1

    def test_parse_conn_string_simple(self):
        from devkit.commands.db import parse_conn_string
        conn = parse_conn_string("localhost:3306:testdb")
        assert conn["host"] == "localhost"
        assert conn["port"] == 3306
        assert conn["dbname"] == "testdb"

    def test_parse_conn_string_with_user(self):
        from devkit.commands.db import parse_conn_string
        conn = parse_conn_string("user:pass@localhost:3306/testdb")
        assert conn["user"] == "user"
        assert conn["password"] == "pass"
        assert conn["host"] == "localhost"
        assert conn["port"] == 3306
        assert conn["dbname"] == "testdb"

    def test_parse_conn_string_with_scheme(self):
        from devkit.commands.db import parse_conn_string
        conn = parse_conn_string("mysql://user:pass@localhost:3306/testdb")
        assert conn["type"] == "mysql"
        assert conn["user"] == "user"
        assert conn["dbname"] == "testdb"


class TestDbSchema:
    def test_sqlite_schema_export(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        conn.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY, title TEXT, user_id INTEGER)")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "schema", "--type", "sqlite", "--path", str(db_file)
        ])
        assert result.exit_code == 0
        assert "CREATE TABLE users" in result.output
        assert "CREATE TABLE posts" in result.output

    def test_sqlite_schema_specific_table(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY, title TEXT)")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "schema", "--type", "sqlite", "--path", str(db_file), "--table", "users"
        ])
        assert result.exit_code == 0
        assert "CREATE TABLE users" in result.output
        assert "CREATE TABLE posts" not in result.output


class TestDbExport:
    def test_sqlite_export_csv(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        output_file = tmp_path / "export.csv"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.execute("INSERT INTO users VALUES (2, 'Bob')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "export", "--table", "users",
            "--type", "sqlite", "--path", str(db_file),
            "--format", "csv", "--output", str(output_file)
        ])
        assert result.exit_code == 0
        assert output_file.exists()
        lines = output_file.read_text().strip().split("\n")
        assert lines[0] == "id,name"
        assert "1,Alice" in lines
        assert "2,Bob" in lines

    def test_sqlite_export_json(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        output_file = tmp_path / "export.json"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice')")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "export", "--table", "users",
            "--type", "sqlite", "--path", str(db_file),
            "--format", "json", "--output", str(output_file)
        ])
        assert result.exit_code == 0
        data = json.loads(output_file.read_text())
        assert len(data) == 1
        assert data[0]["name"] == "Alice"

    def test_sqlite_export_sql(self, runner, tmp_path):
        db_file = tmp_path / "test.db"
        output_file = tmp_path / "export.sql"
        conn = sqlite3.connect(db_file)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
        conn.execute("INSERT INTO users VALUES (1, 'Alice', 30)")
        conn.execute("INSERT INTO users VALUES (2, 'Bob', NULL)")
        conn.commit()
        conn.close()

        result = runner.invoke(cli, [
            "db", "export", "--table", "users",
            "--type", "sqlite", "--path", str(db_file),
            "--format", "sql", "--output", str(output_file)
        ])
        assert result.exit_code == 0
        content = output_file.read_text()
        assert "INSERT INTO users" in content
        assert "'Alice'" in content
        assert "30" in content
        assert "NULL" in content


class TestDbConnectionManagement:
    def test_add_list_connection(self, runner, tmp_path, monkeypatch):
        cfg_path = tmp_path / "config.yml"
        monkeypatch.setenv("HOME", str(tmp_path))
        
        result = runner.invoke(cli, [
            "db", "add-connection", "testdb", "mysql://user:pass@localhost:3306/testdb"
        ])
        assert result.exit_code == 0
        assert "Added connection: testdb" in result.output

        result = runner.invoke(cli, ["db", "list-connections"])
        assert result.exit_code == 0
        assert "testdb" in result.output
        assert "mysql" in result.output

    def test_list_no_connections(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        from devkit.core import config as core_config
        core_config._config_instance = None
        result = runner.invoke(cli, ["db", "list-connections"])
        assert result.exit_code == 0
        assert "No connections configured" in result.output

    def test_named_connection_not_found(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        result = runner.invoke(cli, [
            "db", "query", "SELECT 1", "--connect", "nonexistent"
        ])
        assert result.exit_code == 0
        assert "not found" in result.output
