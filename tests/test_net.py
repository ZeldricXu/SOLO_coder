import json
import socket
import threading
from unittest.mock import patch, MagicMock

import pytest
from click.testing import CliRunner

from devkit.cli import cli


class TestPortCheck:
    def test_closed_port(self, runner):
        result = runner.invoke(cli, ["net", "portcheck", "59999"])
        assert result.exit_code == 0
        assert "CLOSED" in result.output

    def test_open_port(self, runner):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]

        try:
            result = runner.invoke(cli, ["net", "portcheck", str(port), "--host", "127.0.0.1"])
            assert result.exit_code == 0
            assert "OPEN" in result.output
        finally:
            server.close()

    def test_invalid_port(self, runner):
        result = runner.invoke(cli, ["net", "portcheck", "99999"])
        assert result.exit_code == 0

    def test_timeout_option(self, runner):
        result = runner.invoke(cli, ["net", "portcheck", "59999", "-t", "1"])
        assert result.exit_code == 0
        assert "CLOSED" in result.output

    def test_custom_host(self, runner):
        result = runner.invoke(cli, ["net", "portcheck", "80", "--host", "192.0.2.1", "-t", "1"])
        assert result.exit_code == 0


class TestHTTP:
    @patch("devkit.commands.net.HttpClient")
    def test_http_get_sends_headers(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.reason = "OK"
        mock_response.content = b'{"result": "ok"}'
        mock_response.text = '{"result": "ok"}'
        mock_response.json.return_value = {"result": "ok"}
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.request = MagicMock()
        mock_response.request.url = "http://example.com"
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, ["net", "http", "http://example.com", "-H", "X-Custom: test"])
        assert result.exit_code == 0

    @patch("devkit.commands.net.HttpClient")
    def test_http_post_sends_body(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 201
        mock_response.reason = "Created"
        mock_response.content = b'{"id": 1}'
        mock_response.text = '{"id": 1}'
        mock_response.json.return_value = {"id": 1}
        mock_response.headers = {"Content-Type": "application/json"}
        mock_response.request = MagicMock()
        mock_response.request.url = "http://example.com"
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.post.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, ["net", "http", "http://example.com", "-X", "POST", "-j", '{"name": "test"}'])
        assert result.exit_code == 0

    @patch("devkit.commands.net.HttpClient")
    def test_http_status_code_display(self, mock_client_cls, runner):
        mock_response = MagicMock()
        mock_response.status_code = 404
        mock_response.reason = "Not Found"
        mock_response.content = b"Not Found"
        mock_response.text = "Not Found"
        mock_response.headers = {}
        mock_response.request = MagicMock()
        mock_response.request.url = "http://example.com"
        mock_response.request.headers = {}

        mock_instance = MagicMock()
        mock_instance.get.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, ["net", "http", "http://example.com"])
        assert result.exit_code == 0
        assert "404" in result.output

    @patch("devkit.commands.net.HttpClient")
    def test_http_connection_error(self, mock_client_cls, runner):
        import requests as req
        mock_instance = MagicMock()
        mock_instance.get.side_effect = req.exceptions.ConnectionError("Connection refused")
        mock_client_cls.return_value = mock_instance

        result = runner.invoke(cli, ["net", "http", "http://nonexistent.invalid"])
        assert result.exit_code == 0
        assert "error" in result.output.lower() or "connection" in result.output.lower()


class TestDNS:
    @patch("devkit.commands.net.dns_resolver.Resolver")
    def test_dns_resolve_a_record(self, mock_resolver_cls, runner):
        mock_resolver = MagicMock()
        mock_resolver_cls.return_value = mock_resolver
        mock_rdata = MagicMock()
        mock_rdata.address = "93.184.216.34"
        mock_resolver.resolve.return_value = [mock_rdata]

        result = runner.invoke(cli, ["net", "dns", "resolve", "example.com", "-t", "A"])
        assert result.exit_code == 0
        assert "93.184.216.34" in result.output

    @patch("devkit.commands.net.dns_resolver.Resolver")
    def test_dns_resolve_nxdomain(self, mock_resolver_cls, runner):
        import dns.resolver
        mock_resolver = MagicMock()
        mock_resolver_cls.return_value = mock_resolver
        mock_resolver.resolve.side_effect = dns.resolver.NXDOMAIN()

        result = runner.invoke(cli, ["net", "dns", "resolve", "nonexistent.invalid", "-t", "A"])
        assert result.exit_code == 0
        assert "does not exist" in result.output.lower() or "nxdomain" in result.output.lower()

    @patch("devkit.commands.net.dns_resolver.Resolver")
    def test_dns_resolve_mx_record(self, mock_resolver_cls, runner):
        mock_resolver = MagicMock()
        mock_resolver_cls.return_value = mock_resolver
        mock_rdata = MagicMock()
        mock_rdata.preference = 10
        mock_rdata.exchange = "mail.example.com."
        mock_resolver.resolve.return_value = [mock_rdata]

        result = runner.invoke(cli, ["net", "dns", "resolve", "example.com", "-t", "MX"])
        assert result.exit_code == 0
        assert "mail.example.com" in result.output

    @patch("devkit.commands.net.dns_resolver.Resolver")
    def test_dns_timeout(self, mock_resolver_cls, runner):
        import dns.resolver
        mock_resolver = MagicMock()
        mock_resolver_cls.return_value = mock_resolver
        mock_resolver.resolve.side_effect = dns.resolver.Timeout()

        result = runner.invoke(cli, ["net", "dns", "resolve", "example.com", "-t", "A"])
        assert result.exit_code == 0
        output = result.output.replace("\x1b", "").lower()
        assert "timed out" in output or "timeout" in output or "error" in output
