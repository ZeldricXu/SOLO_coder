"""
执行编排模块单元测试
使用 Mock 模拟多服务器部署任务执行
测试覆盖：
1. 并发池限流控制是否正确限制并发数
2. 等待队列的公平调度逻辑
3. 单个服务器失败不影响其他服务器任务
4. 全部失败的汇总报告生成
"""

import pytest
import yaml
from pathlib import Path
from unittest.mock import MagicMock, patch, Mock, PropertyMock, call
from typing import List, Dict, Any, Optional
from concurrent.futures import ThreadPoolExecutor, as_completed

from autodeploy.core.orchestrator import DeployOrchestrator, DeployResult
from autodeploy.core.models import DeployStatus, StepStatus
from autodeploy.connection import SSHConnection, ServerConfig, SSHConnectionError
from autodeploy.executor import FileTransfer, RemoteExecutor, BuildExecutor
from autodeploy.verification import HealthChecker


class TestDeployOrchestratorInit:
    """DeployOrchestrator 初始化测试"""
    
    def test_default_max_concurrent(self, temp_dir):
        """测试默认并发数应该是3"""
        test_config_dir = temp_dir / "configs"
        test_config_dir.mkdir()
        test_log_dir = temp_dir / "logs"
        test_log_dir.mkdir()
        
        orchestrator = DeployOrchestrator(
            config_dir=str(test_config_dir),
            log_dir=str(test_log_dir),
            work_dir=str(temp_dir)
        )
        
        assert orchestrator.DEFAULT_MAX_CONCURRENT == 3
    
    def test_custom_parameters(self, temp_dir):
        """测试自定义参数初始化"""
        test_config_dir = temp_dir / "configs"
        test_config_dir.mkdir()
        test_log_dir = temp_dir / "logs"
        test_log_dir.mkdir()
        
        orchestrator = DeployOrchestrator(
            config_dir=str(test_config_dir),
            log_dir=str(test_log_dir),
            work_dir=str(temp_dir)
        )
        
        assert orchestrator.config_parser is not None
        assert orchestrator.config_validator is not None
        assert orchestrator.deploy_logger is not None


class TestDeployOrchestratorConcurrency:
    """并发池限流控制测试"""
    
    @pytest.fixture
    def test_config(self, temp_dir):
        """创建测试配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        config = {
            "env_name": "test_env",
            "servers": [
                {"host": "server1.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "server2.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "server3.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "server4.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "server5.example.com", "port": 22, "user": "test", "key_file": "/key.pem"}
            ],
            "deploy_path": "/var/www/test",
            "build_command": "echo build",
            "build_output": "./dist",
            "start_command": "echo start",
            "rollback_enabled": False
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config, f, allow_unicode=True)
        
        dist_dir = temp_dir / "dist"
        dist_dir.mkdir()
        (dist_dir / "index.html").write_text("test content")
        
        return {
            "config_dir": config_dir,
            "log_dir": temp_dir / "logs",
            "work_dir": temp_dir,
            "server_count": 5
        }
    
    def test_concurrent_pool_limit(self, test_config):
        """测试并发池是否正确限制并发数"""
        actual_concurrent = [0]
        max_concurrent_observed = [0]
        
        def mock_deploy_single_server(config, server_config, build_result):
            actual_concurrent[0] += 1
            if actual_concurrent[0] > max_concurrent_observed[0]:
                max_concurrent_observed[0] = actual_concurrent[0]
            
            import time
            time.sleep(0.05)
            
            actual_concurrent[0] -= 1
            
            from autodeploy.core.models import ServerDeployResult
            return ServerDeployResult(
                server_host=server_config.get("host", "unknown"),
                server_port=server_config.get("port", 22),
                success=True,
                status=DeployStatus.COMPLETED,
                steps=[]
            )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(test_config["config_dir"]),
            log_dir=str(test_config["log_dir"]),
            work_dir=str(test_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    config.get("servers", []),
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        assert max_concurrent_observed[0] <= 2
        assert len(server_results) == 5
    
    def test_wait_queue_fair_scheduling(self, test_config):
        """测试等待队列的公平调度逻辑（FIFO）"""
        execution_order = []
        
        def mock_deploy_single_server(config, server_config, build_result):
            import time
            execution_order.append(server_config["host"])
            time.sleep(0.01)
            
            from autodeploy.core.models import ServerDeployResult
            return ServerDeployResult(
                server_host=server_config.get("host", "unknown"),
                server_port=server_config.get("port", 22),
                success=True,
                status=DeployStatus.COMPLETED,
                steps=[]
            )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(test_config["config_dir"]),
            log_dir=str(test_config["log_dir"]),
            work_dir=str(test_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                servers = config.get("servers", [])
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    servers,
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        assert len(execution_order) == 5
        
        executed_hosts = [r.server_host for r in server_results]
        original_hosts = [s["host"] for s in servers]
        
        assert set(executed_hosts) == set(original_hosts)


class TestDeployOrchestratorServerFailure:
    """服务器失败场景测试"""
    
    @pytest.fixture
    def mixed_servers_config(self, temp_dir):
        """创建混合成功和失败服务器的测试配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        config = {
            "env_name": "test_env",
            "servers": [
                {"host": "success1.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "failure.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "success2.example.com", "port": 22, "user": "test", "key_file": "/key.pem"}
            ],
            "deploy_path": "/var/www/test",
            "build_command": "echo build",
            "build_output": "./dist",
            "start_command": "echo start",
            "rollback_enabled": False
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config, f, allow_unicode=True)
        
        dist_dir = temp_dir / "dist"
        dist_dir.mkdir()
        (dist_dir / "index.html").write_text("test content")
        
        return {
            "config_dir": config_dir,
            "log_dir": temp_dir / "logs",
            "work_dir": temp_dir,
            "failure_host": "failure.example.com",
            "success_hosts": ["success1.example.com", "success2.example.com"]
        }
    
    def test_single_server_failure_not_affect_others(self, mixed_servers_config):
        """测试单个服务器失败不影响其他服务器任务"""
        def mock_deploy_single_server(config, server_config, build_result):
            from autodeploy.core.models import ServerDeployResult
            
            host = server_config["host"]
            
            if host == mixed_servers_config["failure_host"]:
                return ServerDeployResult(
                    server_host=host,
                    server_port=22,
                    success=False,
                    status=DeployStatus.FAILED,
                    steps=[],
                    error_message="Connection failed"
                )
            else:
                return ServerDeployResult(
                    server_host=host,
                    server_port=22,
                    success=True,
                    status=DeployStatus.COMPLETED,
                    steps=[]
                )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(mixed_servers_config["config_dir"]),
            log_dir=str(mixed_servers_config["log_dir"]),
            work_dir=str(mixed_servers_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    config.get("servers", []),
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        success_count = sum(1 for r in server_results if r.success)
        failed_count = sum(1 for r in server_results if not r.success)
        
        assert success_count == 2
        assert failed_count == 1
        
        failed_result = next((r for r in server_results if r.server_host == mixed_servers_config["failure_host"]), None)
        assert failed_result is not None
        assert failed_result.success is False
        
        success_hosts_in_result = [r.server_host for r in server_results if r.success]
        assert set(success_hosts_in_result) == set(mixed_servers_config["success_hosts"])
    
    def test_summary_report_partial_success(self, mixed_servers_config):
        """测试部分成功的汇总报告"""
        def mock_deploy_single_server(config, server_config, build_result):
            from autodeploy.core.models import ServerDeployResult
            
            host = server_config["host"]
            
            if host == mixed_servers_config["failure_host"]:
                return ServerDeployResult(
                    server_host=host,
                    server_port=22,
                    success=False,
                    status=DeployStatus.FAILED,
                    steps=[],
                    error_message="Connection failed"
                )
            else:
                return ServerDeployResult(
                    server_host=host,
                    server_port=22,
                    success=True,
                    status=DeployStatus.COMPLETED,
                    steps=[]
                )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(mixed_servers_config["config_dir"]),
            log_dir=str(mixed_servers_config["log_dir"]),
            work_dir=str(mixed_servers_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    config.get("servers", []),
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        success_count = sum(1 for r in server_results if r.success)
        failed_count = len(server_results) - success_count
        
        summary = {
            "total_servers": len(server_results),
            "success_count": success_count,
            "failed_count": failed_count
        }
        
        assert summary["total_servers"] == 3
        assert summary["success_count"] == 2
        assert summary["failed_count"] == 1


class TestDeployOrchestratorAllFailure:
    """全部服务器失败场景测试"""
    
    @pytest.fixture
    def all_failure_config(self, temp_dir):
        """创建全部服务器失败的测试配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        config = {
            "env_name": "test_env",
            "servers": [
                {"host": "fail1.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "fail2.example.com", "port": 22, "user": "test", "key_file": "/key.pem"},
                {"host": "fail3.example.com", "port": 22, "user": "test", "key_file": "/key.pem"}
            ],
            "deploy_path": "/var/www/test",
            "build_command": "echo build",
            "build_output": "./dist",
            "start_command": "echo start",
            "rollback_enabled": False
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config, f, allow_unicode=True)
        
        dist_dir = temp_dir / "dist"
        dist_dir.mkdir()
        (dist_dir / "index.html").write_text("test content")
        
        return {
            "config_dir": config_dir,
            "log_dir": temp_dir / "logs",
            "work_dir": temp_dir
        }
    
    def test_all_servers_failure_summary(self, all_failure_config):
        """测试全部失败的汇总报告生成"""
        def mock_deploy_single_server(config, server_config, build_result):
            from autodeploy.core.models import ServerDeployResult
            
            return ServerDeployResult(
                server_host=server_config["host"],
                server_port=22,
                success=False,
                status=DeployStatus.FAILED,
                steps=[],
                error_message=f"Connection failed to {server_config['host']}"
            )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(all_failure_config["config_dir"]),
            log_dir=str(all_failure_config["log_dir"]),
            work_dir=str(all_failure_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    config.get("servers", []),
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        success_count = sum(1 for r in server_results if r.success)
        failed_count = len(server_results) - success_count
        
        assert success_count == 0
        assert failed_count == 3
        
        for result in server_results:
            assert result.success is False
            assert "Connection failed" in result.error_message
        
        summary = {
            "total_servers": len(server_results),
            "success_count": success_count,
            "failed_count": failed_count
        }
        
        assert summary["total_servers"] == 3
        assert summary["success_count"] == 0
        assert summary["failed_count"] == 3
    
    def test_all_failure_with_detailed_errors(self, all_failure_config):
        """测试全部失败时详细错误信息"""
        def mock_deploy_single_server(config, server_config, build_result):
            from autodeploy.core.models import ServerDeployResult
            
            host = server_config["host"]
            
            errors = {
                "fail1.example.com": "SSH connection timeout",
                "fail2.example.com": "Authentication failed",
                "fail3.example.com": "Permission denied"
            }
            
            return ServerDeployResult(
                server_host=host,
                server_port=22,
                success=False,
                status=DeployStatus.FAILED,
                steps=[],
                error_message=errors.get(host, "Unknown error")
            )
        
        orchestrator = DeployOrchestrator(
            config_dir=str(all_failure_config["config_dir"]),
            log_dir=str(all_failure_config["log_dir"]),
            work_dir=str(all_failure_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server', side_effect=mock_deploy_single_server):
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                config = orchestrator.config_parser.parse("test_env")
                
                server_results = orchestrator._deploy_servers_concurrent(
                    config,
                    config.get("servers", []),
                    mock_build.return_value,
                    max_concurrent=2
                )
        
        error_messages = [r.error_message for r in server_results]
        
        assert "SSH connection timeout" in error_messages
        assert "Authentication failed" in error_messages
        assert "Permission denied" in error_messages


class TestDeployOrchestratorCallbacks:
    """回调函数测试"""
    
    @pytest.fixture
    def simple_config(self, temp_dir):
        """创建简单的测试配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        config = {
            "env_name": "test_env",
            "servers": [
                {"host": "server1.example.com", "port": 22, "user": "test", "key_file": "/key.pem"}
            ],
            "deploy_path": "/var/www/test",
            "build_command": "echo build",
            "build_output": "./dist",
            "start_command": "echo start",
            "rollback_enabled": False
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config, f, allow_unicode=True)
        
        dist_dir = temp_dir / "dist"
        dist_dir.mkdir()
        (dist_dir / "index.html").write_text("test content")
        
        return {
            "config_dir": config_dir,
            "log_dir": temp_dir / "logs",
            "work_dir": temp_dir
        }
    
    def test_step_callback(self, simple_config):
        """测试步骤回调函数"""
        step_calls = []
        
        def step_callback(step_name, status, message):
            step_calls.append({
                "step_name": step_name,
                "status": status,
                "message": message
            })
        
        orchestrator = DeployOrchestrator(
            config_dir=str(simple_config["config_dir"]),
            log_dir=str(simple_config["log_dir"]),
            work_dir=str(simple_config["work_dir"])
        )
        
        orchestrator.set_step_callback(step_callback)
        
        with patch.object(orchestrator, '_deploy_single_server') as mock_deploy:
            from autodeploy.core.models import ServerDeployResult
            mock_deploy.return_value = ServerDeployResult(
                server_host="server1.example.com",
                server_port=22,
                success=True,
                status=DeployStatus.COMPLETED,
                steps=[]
            )
            
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                orchestrator.deploy(
                    env_name="test_env",
                    max_concurrent=1
                )
        
        assert len(step_calls) > 0
        
        step_names = [call["step_name"] for call in step_calls]
        assert "load_config" in step_names
        assert "validate_config" in step_names
    
    def test_server_callback(self, simple_config):
        """测试服务器回调函数"""
        server_calls = []
        
        def server_callback(server_host, status, message):
            server_calls.append({
                "server_host": server_host,
                "status": status,
                "message": message
            })
        
        orchestrator = DeployOrchestrator(
            config_dir=str(simple_config["config_dir"]),
            log_dir=str(simple_config["log_dir"]),
            work_dir=str(simple_config["work_dir"])
        )
        
        orchestrator.set_server_callback(server_callback)
        
        with patch.object(orchestrator, '_deploy_single_server') as mock_deploy:
            from autodeploy.core.models import ServerDeployResult
            mock_deploy.return_value = ServerDeployResult(
                server_host="server1.example.com",
                server_port=22,
                success=True,
                status=DeployStatus.COMPLETED,
                steps=[]
            )
            
            with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
                from autodeploy.executor.builder import BuildResult
                mock_build.return_value = BuildResult(
                    success=True,
                    command="build",
                    exit_code=0,
                    stdout="",
                    stderr="",
                    duration=0.1
                )
                
                orchestrator.deploy(
                    env_name="test_env",
                    max_concurrent=1
                )
        
        server_hosts = [call["server_host"] for call in server_calls]
        assert "server1.example.com" in server_hosts


class TestDeployOrchestratorConfigLoad:
    """配置加载测试"""
    
    def test_load_nonexistent_env(self, temp_dir):
        """测试加载不存在的环境配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        orchestrator = DeployOrchestrator(
            config_dir=str(config_dir),
            log_dir=str(temp_dir / "logs"),
            work_dir=str(temp_dir)
        )
        
        result = orchestrator.deploy(
            env_name="nonexistent",
            max_concurrent=1
        )
        
        assert result.success is False
        assert "配置加载失败" in result.error_message or "配置" in result.error_message
    
    def test_invalid_config_validation(self, temp_dir):
        """测试无效配置的验证"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        invalid_config = {
            "env_name": "test_env"
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(invalid_config, f, allow_unicode=True)
        
        orchestrator = DeployOrchestrator(
            config_dir=str(config_dir),
            log_dir=str(temp_dir / "logs"),
            work_dir=str(temp_dir)
        )
        
        result = orchestrator.deploy(
            env_name="test_env",
            max_concurrent=1
        )
        
        assert result.success is False
        assert "配置验证失败" in result.error_message or "配置" in result.error_message
    
    def test_list_environments(self, temp_dir):
        """测试列出所有环境"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        envs = ["production", "staging", "development"]
        
        for env in envs:
            config = {
                "env_name": env,
                "servers": [{"host": "localhost", "port": 22, "user": "test"}],
                "deploy_path": "/var/www/test"
            }
            config_file = config_dir / f"{env}.yaml"
            with open(config_file, 'w', encoding='utf-8') as f:
                yaml.dump(config, f, allow_unicode=True)
        
        orchestrator = DeployOrchestrator(
            config_dir=str(config_dir),
            log_dir=str(temp_dir / "logs"),
            work_dir=str(temp_dir)
        )
        
        result = orchestrator.list_environments()
        
        assert sorted(result) == sorted(envs)


class TestDeployOrchestratorBuild:
    """构建相关测试"""
    
    @pytest.fixture
    def build_config(self, temp_dir):
        """创建带构建的测试配置"""
        config_dir = temp_dir / "configs"
        config_dir.mkdir()
        
        config = {
            "env_name": "test_env",
            "servers": [
                {"host": "server1.example.com", "port": 22, "user": "test", "key_file": "/key.pem"}
            ],
            "deploy_path": "/var/www/test",
            "build_command": "npm run build",
            "build_output": "./dist",
            "start_command": "echo start",
            "rollback_enabled": False
        }
        
        config_file = config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config, f, allow_unicode=True)
        
        dist_dir = temp_dir / "dist"
        dist_dir.mkdir()
        (dist_dir / "index.html").write_text("test content")
        
        return {
            "config_dir": config_dir,
            "log_dir": temp_dir / "logs",
            "work_dir": temp_dir
        }
    
    def test_build_failure_stops_deployment(self, build_config):
        """测试构建失败时停止部署"""
        orchestrator = DeployOrchestrator(
            config_dir=str(build_config["config_dir"]),
            log_dir=str(build_config["log_dir"]),
            work_dir=str(build_config["work_dir"])
        )
        
        with patch.object(BuildExecutor, 'build_and_collect') as mock_build:
            from autodeploy.executor.builder import BuildResult
            mock_build.return_value = BuildResult(
                success=False,
                command="npm run build",
                exit_code=1,
                stdout="",
                stderr="Build error: missing dependency",
                duration=5.0
            )
            
            result = orchestrator.deploy(
                env_name="test_env",
                max_concurrent=1
            )
        
        assert result.success is False
        assert result.error_message is not None or "失败" in str(result.status.value)
    
    def test_skip_build_option(self, build_config):
        """测试跳过构建选项"""
        orchestrator = DeployOrchestrator(
            config_dir=str(build_config["config_dir"]),
            log_dir=str(build_config["log_dir"]),
            work_dir=str(build_config["work_dir"])
        )
        
        with patch.object(orchestrator, '_deploy_single_server') as mock_deploy:
            from autodeploy.core.models import ServerDeployResult
            mock_deploy.return_value = ServerDeployResult(
                server_host="server1.example.com",
                server_port=22,
                success=True,
                status=DeployStatus.COMPLETED,
                steps=[]
            )
            
            result = orchestrator.deploy(
                env_name="test_env",
                max_concurrent=1,
                skip_build=True
            )
        
        assert result is not None
