import json
import os
import tempfile
import time
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from click.testing import CliRunner

from devkit.cli import cli


class TestApiTest:
    @patch("devkit.commands.api.HttpClient")
    def test_api_get_request(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.text = '{"id": 1, "name": "Alice"}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"id": 1, "name": "Alice"}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "GET", "https://api.example.com/users/1",
            "-H", "Authorization: Bearer token123"
        ])
        assert result.exit_code == 0
        assert "200" in result.output
        assert "Alice" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_api_post_request(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 201
        mock_response.reason = "Created"
        mock_response.text = '{"id": 1}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"id": 1}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.post.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "POST", "https://api.example.com/users",
            "-j", '{"name": "Alice"}'
        ])
        assert result.exit_code == 0
        assert "201" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_api_assert_status_pass(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.text = '{"status": "ok"}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"status": "ok"}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "GET", "https://api.example.com/health",
            "--assert-status", "200"
        ])
        assert result.exit_code == 0
        assert "✓ Status code = 200" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_api_assert_status_fail(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.reason = "Internal Error"
        mock_response.text = '{"error": "fail"}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"error": "fail"}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "GET", "https://api.example.com/health",
            "--assert-status", "200"
        ])
        assert result.exit_code == 1
        assert "✗ Status code = 200" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_api_assert_json(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.text = '{"data": {"user": {"name": "Alice", "age": 30}}}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"data": {"user": {"name": "Alice", "age": 30}}}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "GET", "https://api.example.com/users/1",
            "--assert-json", "data.user.name=Alice",
            "--assert-json", "data.user.age=30"
        ])
        assert result.exit_code == 0
        assert "✓ JSON data.user.name eq Alice" in result.output
        assert "✓ JSON data.user.age eq 30" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_api_save_var(self, mock_client_cls, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.text = '{"data": {"token": "abc123"}}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"data": {"token": "abc123"}}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.post.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "test", "POST", "https://api.example.com/login",
            "-j", '{"username": "test"}',
            "--save-var", "auth_token@data.token"
        ])
        assert result.exit_code == 0
        assert "Saved variable: auth_token = abc123" in result.output

    def test_api_invalid_json_body(self, runner):
        result = runner.invoke(cli, [
            "api", "test", "POST", "https://api.example.com/users",
            "-j", '{invalid json'
        ])
        assert result.exit_code == 0
        assert "Invalid JSON" in result.output


class TestApiVars:
    def test_vars_set_show_delete(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        
        result = runner.invoke(cli, ["api", "vars", "set", "token", "abc123"])
        assert result.exit_code == 0
        assert "Set token = abc123" in result.output

        result = runner.invoke(cli, ["api", "vars", "show", "token"])
        assert result.exit_code == 0
        assert "abc123" in result.output

        result = runner.invoke(cli, ["api", "vars", "list"])
        assert result.exit_code == 0
        assert "token" in result.output
        assert "abc123" in result.output

        result = runner.invoke(cli, ["api", "vars", "delete", "token"])
        assert result.exit_code == 0
        assert "Deleted token" in result.output

    def test_vars_clear(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        runner.invoke(cli, ["api", "vars", "set", "a", "1"])
        runner.invoke(cli, ["api", "vars", "set", "b", "2"])
        
        result = runner.invoke(cli, ["api", "vars", "clear"])
        assert result.exit_code == 0
        assert "All variables cleared" in result.output

        result = runner.invoke(cli, ["api", "vars", "list"])
        assert "No variables saved" in result.output

    def test_vars_show_nonexistent(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        result = runner.invoke(cli, ["api", "vars", "show", "nonexistent"])
        assert result.exit_code == 0
        assert "Variable not found" in result.output


class TestApiCollection:
    def test_collection_add_delete_list(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yml', delete=False) as f:
            f.write("""
method: GET
url: https://api.example.com/users
headers:
  - "Authorization: Bearer {{token}}"
""")
            req_file = f.name
        
        try:
            result = runner.invoke(cli, [
                "api", "collection", "add",
                "--project", "myapp",
                "--name", "get_users",
                "--file", req_file
            ])
            assert result.exit_code == 0
            assert "Added request: myapp.get_users" in result.output

            result = runner.invoke(cli, ["api", "collection", "list"])
            assert result.exit_code == 0
            assert "myapp" in result.output
            assert "get_users" in result.output

            result = runner.invoke(cli, ["api", "collection", "show", "--project", "myapp", "--name", "get_users"])
            assert result.exit_code == 0
            assert "GET" in result.output
            assert "api.example.com/users" in result.output

            result = runner.invoke(cli, [
                "api", "collection", "delete",
                "--project", "myapp",
                "--name", "get_users"
            ])
            assert result.exit_code == 0
            assert "Deleted request: myapp.get_users" in result.output
        finally:
            os.unlink(req_file)

    def test_collection_list_empty(self, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        result = runner.invoke(cli, ["api", "collection", "list"])
        assert "No collections configured" in result.output


class TestApiRun:
    @patch("devkit.commands.api.HttpClient")
    def test_run_request_with_variable(self, mock_client_cls, runner, tmp_path, monkeypatch):
        monkeypatch.setenv("HOME", str(tmp_path))
        from devkit.core import config as core_config
        
        core_config._config_instance = None
        runner.invoke(cli, ["api", "vars", "set", "auth_token", "secret123"])
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yml', delete=False) as f:
            f.write("""
method: GET
url: https://api.example.com/users/{{user_id}}
headers:
  - "Authorization: Bearer {{auth_token}}"
""")
            req_file = f.name
        
        try:
            core_config._config_instance = None
            runner.invoke(cli, [
                "api", "collection", "add",
                "--project", "myapp", "--name", "get_user",
                "--file", req_file
            ])

            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.reason = "OK"
            mock_response.text = '{"id": 1}'
            mock_response.content = mock_response.text.encode()
            mock_response.headers = {"Content-Type": "application/json"}
            mock_response.json.return_value = {"id": 1}
            mock_response.request = MagicMock()
            mock_response.request.headers = {}

            mock_instance = MagicMock()
            mock_instance.get.return_value = mock_response
            mock_client_cls.return_value = mock_instance

            core_config._config_instance = None
            result = runner.invoke(cli, [
                "api", "run", "get_user",
                "--project", "myapp",
                "--set-var", "user_id=123"
            ])
            assert result.exit_code == 0
            assert "200" in result.output
        finally:
            os.unlink(req_file)


class TestApiPerf:
    @patch("devkit.commands.api.HttpClient")
    def test_perf_test(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.text = '{"status": "ok"}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"status": "ok"}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "perf", "GET", "https://api.example.com/health",
            "-n", "10"
        ])
        assert result.exit_code == 0
        assert "Total requests:  10" in result.output
        assert "Successful:      10" in result.output
        assert "Min:" in result.output
        assert "Avg:" in result.output
        assert "Max:" in result.output
        assert "P50:" in result.output
        assert "P95:" in result.output
        assert "P99:" in result.output

    @patch("devkit.commands.api.HttpClient")
    def test_perf_test_with_errors(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.reason = "Error"
        mock_response.text = '{"error": "fail"}'
        mock_response.content = mock_response.text.encode()
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.json.return_value = {"error": "fail"}
        mock_response.request = MagicMock()
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, [
            "api", "perf", "GET", "https://api.example.com/error",
            "-n", "5"
        ])
        assert result.exit_code == 0
        assert "Status codes" in result.output
        assert "500" in result.output
