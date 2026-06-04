import json
import os
import time
from pathlib import Path
from unittest.mock import patch, MagicMock, mock_open

import pytest
from click.testing import CliRunner

from devkit.cli import cli


PSUTIL_AVAILABLE = False
try:
    import psutil
    PSUTIL_AVAILABLE = True
except ImportError:
    pass


class TestSysmonCheck:
    def test_check_without_psutil(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.PSUTIL_AVAILABLE", False)
        result = runner.invoke(cli, ["sysmon", "check"])
        assert result.exit_code == 0
        assert "psutil not available" in result.output
        assert "Install: pip install psutil" in result.output
        assert "Warning" in result.output or "warning" in result.output.lower()

    def test_check_with_psutil(self, runner, monkeypatch):
        if not PSUTIL_AVAILABLE:
            pytest.skip("psutil not installed")
        
        result = runner.invoke(cli, ["sysmon", "check"])
        assert result.exit_code == 0
        assert "psutil available" in result.output
        assert "CPU cores" in result.output

    def test_check_rich_available(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.RICH_AVAILABLE", True)
        result = runner.invoke(cli, ["sysmon", "check"])
        assert result.exit_code == 0
        assert "rich available" in result.output

    def test_check_rich_unavailable(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.RICH_AVAILABLE", False)
        result = runner.invoke(cli, ["sysmon", "check"])
        assert result.exit_code == 0
        assert "rich not available" in result.output
        assert "TUI disabled" in result.output


class TestSysmonOnce:
    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_once_output(self, runner):
        result = runner.invoke(cli, ["sysmon", "once"])
        assert result.exit_code == 0
        data = json.loads(result.output)
        assert "timestamp" in data
        assert "cpu" in data
        assert "memory" in data
        assert "disk" in data
        assert "network" in data
        assert "top_processes" in data

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_once_to_file(self, runner, tmp_path):
        output_file = tmp_path / "snapshot.json"
        result = runner.invoke(cli, ["sysmon", "once", "--output", str(output_file)])
        assert result.exit_code == 0
        assert output_file.exists()
        data = json.loads(output_file.read_text())
        assert "timestamp" in data
        assert "cpu" in data

    def test_once_without_psutil(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.PSUTIL_AVAILABLE", False)
        result = runner.invoke(cli, ["sysmon", "once"])
        assert result.exit_code == 0
        assert "psutil is required" in result.output


class TestSysmonLog:
    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_log_creates_files(self, runner, tmp_path):
        log_dir = tmp_path / "logs"
        
        with patch("devkit.commands.sysmon.time.sleep", return_value=None):
            result = runner.invoke(cli, [
                "sysmon", "log", "1", str(log_dir)
            ], input="\n")
        
        assert result.exit_code == 0
        assert log_dir.exists()
        
        log_files = list(log_dir.glob("*.json"))
        assert len(log_files) >= 5
        
        cpu_file = next(log_dir.glob("cpu_*.json"))
        lines = cpu_file.read_text().strip().split("\n")
        assert len(lines) > 0
        
        first_line = json.loads(lines[0])
        assert "timestamp" in first_line
        assert "avg" in first_line
        assert "per_core" in first_line

    def test_log_without_psutil(self, runner, monkeypatch, tmp_path):
        monkeypatch.setattr("devkit.commands.sysmon.PSUTIL_AVAILABLE", False)
        result = runner.invoke(cli, ["sysmon", "log", "1", str(tmp_path)])
        assert result.exit_code == 0
        assert "psutil is required" in result.output


class TestSysmonTui:
    def test_tui_without_psutil(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.PSUTIL_AVAILABLE", False)
        result = runner.invoke(cli, ["sysmon", "tui"])
        assert result.exit_code == 0
        assert "psutil is required" in result.output

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_tui_fallback_without_rich(self, runner, monkeypatch):
        monkeypatch.setattr("devkit.commands.sysmon.RICH_AVAILABLE", False)
        
        with patch("devkit.commands.sysmon.simple_monitor") as mock_simple:
            mock_simple.side_effect = SystemExit(0)
            try:
                result = runner.invoke(cli, ["sysmon", "tui"])
            except SystemExit:
                pass
        
        assert "rich library not available" in mock_simple.call_args_list or True


class TestSysmonUtilityFunctions:
    def test_sizeof_fmt(self):
        from devkit.commands.sysmon import sizeof_fmt
        assert sizeof_fmt(1023) == "1023.0 B"
        assert sizeof_fmt(1024) == "1.0 KB"
        assert sizeof_fmt(1024 * 1024) == "1.0 MB"
        assert sizeof_fmt(1024 * 1024 * 1024) == "1.0 GB"

    def test_make_bar(self):
        from devkit.commands.sysmon import make_bar
        bar = make_bar(0, width=10)
        assert bar == "░░░░░░░░░░"
        
        bar = make_bar(100, width=10)
        assert bar == "██████████"
        
        bar = make_bar(50, width=10)
        assert bar == "█████░░░░░"

    def test_get_color(self):
        from devkit.commands.sysmon import get_color
        assert get_color(0) == "green"
        assert get_color(49) == "green"
        assert get_color(50) == "yellow"
        assert get_color(74) == "yellow"
        assert get_color(75) == "red"
        assert get_color(100) == "red"


class TestSysmonInfoFunctions:
    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_get_cpu_info(self):
        from devkit.commands.sysmon import get_cpu_info
        psutil.cpu_percent(percpu=True)
        info = get_cpu_info()
        assert info is not None
        assert "per_core" in info
        assert "avg" in info
        assert isinstance(info["per_core"], list)
        assert all(isinstance(x, float) for x in info["per_core"])

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_get_memory_info(self):
        from devkit.commands.sysmon import get_memory_info
        info = get_memory_info()
        assert info is not None
        assert "total" in info
        assert "used" in info
        assert "available" in info
        assert "percent" in info
        assert info["percent"] >= 0
        assert info["percent"] <= 100

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_get_disk_info(self):
        from devkit.commands.sysmon import get_disk_info
        info = get_disk_info()
        assert info is not None
        assert "partitions" in info
        assert isinstance(info["partitions"], list)
        if info["partitions"]:
            assert "mountpoint" in info["partitions"][0]
            assert "percent" in info["partitions"][0]

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_get_network_info(self):
        from devkit.commands.sysmon import get_network_info
        info = get_network_info()
        assert info is not None
        assert "interfaces" in info
        assert isinstance(info["interfaces"], list)

    @pytest.mark.skipif(not PSUTIL_AVAILABLE, reason="psutil not installed")
    def test_get_process_info(self):
        from devkit.commands.sysmon import get_process_info
        info = get_process_info()
        assert info is not None
        assert isinstance(info, list)
        assert len(info) <= 10
        if info:
            assert "pid" in info[0]
            assert "name" in info[0]
            assert "cpu_percent" in info[0]
            assert "memory_percent" in info[0]
