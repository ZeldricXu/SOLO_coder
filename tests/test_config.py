"""
配置解析模块单元测试
测试覆盖：
1. YAML配置解析的正常输出
2. 缺失必填字段时的错误抛出
3. 参数类型不匹配时的校验提示
4. 嵌套配置结构的正确解析
5. 环境变量替换功能
"""

import pytest
import yaml
import os
from pathlib import Path
from unittest.mock import patch

from autodeploy.config.parser import ConfigParser
from autodeploy.config.validator import ConfigValidator, ValidationError


class TestConfigParser:
    """ConfigParser 单元测试"""
    
    def test_parse_valid_config(self, test_config_dir, sample_valid_config):
        """测试正常配置解析"""
        config_file = test_config_dir / "test_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(sample_valid_config, f, allow_unicode=True)
        
        parser = ConfigParser(config_dir=str(test_config_dir))
        config = parser.parse("test_env")
        
        assert config["env_name"] == "test_env"
        assert len(config["servers"]) == 1
        assert config["servers"][0]["host"] == "192.168.1.100"
        assert config["deploy_path"] == "/var/www/test"
        assert config["build_command"] == "npm run build"
        assert config["health_check"]["type"] == "http"
    
    def test_parse_nested_config(self, test_config_dir):
        """测试嵌套配置结构的正确解析"""
        nested_config = {
            "env_name": "nested_env",
            "servers": [
                {
                    "host": "server1.example.com",
                    "port": 22,
                    "user": "admin",
                    "key_file": "/keys/key.pem",
                    "advanced": {
                        "connect_timeout": 30,
                        "keepalive": True,
                        "compression": "zlib"
                    }
                }
            ],
            "deploy_path": "/opt/app",
            "health_check": {
                "type": "http",
                "url": "http://localhost/api/health",
                "timeout": 10,
                "expected_status_codes": [200, 204],
                "headers": {
                    "X-API-Key": "test-key",
                    "Authorization": "Bearer token"
                }
            }
        }
        
        config_file = test_config_dir / "nested_env.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(nested_config, f, allow_unicode=True)
        
        parser = ConfigParser(config_dir=str(test_config_dir))
        config = parser.parse("nested_env")
        
        assert config["servers"][0]["advanced"]["connect_timeout"] == 30
        assert config["servers"][0]["advanced"]["keepalive"] is True
        assert config["health_check"]["expected_status_codes"] == [200, 204]
        assert config["health_check"]["headers"]["X-API-Key"] == "test-key"
    
    def test_parse_config_file_not_found(self, test_config_dir):
        """测试配置文件不存在时抛出异常"""
        parser = ConfigParser(config_dir=str(test_config_dir))
        
        with pytest.raises(FileNotFoundError) as exc_info:
            parser.parse("nonexistent_env")
        
        assert "nonexistent_env" in str(exc_info.value)
    
    def test_parse_with_env_variable_substitution(self, test_config_dir):
        """测试环境变量替换功能"""
        config_with_env = {
            "env_name": "test_env",
            "servers": [
                {
                    "host": "${TEST_HOST}",
                    "port": 22,
                    "user": "${TEST_USER:-default_user}",
                    "key_file": "/home/${TEST_USER}/.ssh/key.pem"
                }
            ],
            "deploy_path": "/var/www/${APP_NAME:-app}"
        }
        
        config_file = test_config_dir / "env_test.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config_with_env, f, allow_unicode=True)
        
        env_vars = {
            "TEST_HOST": "10.0.0.1",
            "TEST_USER": "testuser"
        }
        
        parser = ConfigParser(config_dir=str(test_config_dir))
        parser._env_vars = env_vars
        
        config = parser.parse("env_test")
        
        assert config["servers"][0]["host"] == "10.0.0.1"
        assert config["servers"][0]["user"] == "testuser"
        assert config["servers"][0]["key_file"] == "/home/testuser/.ssh/key.pem"
        assert config["deploy_path"] == "/var/www/app"
    
    def test_parse_with_env_variable_default_value(self, test_config_dir):
        """测试环境变量默认值功能"""
        config_with_default = {
            "env_name": "test_env",
            "servers": [
                {
                    "host": "${UNDEFINED_VAR:-default.host.com}",
                    "port": 22,
                    "user": "deploy"
                }
            ],
            "deploy_path": "/var/www/app"
        }
        
        config_file = test_config_dir / "default_test.yaml"
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(config_with_default, f, allow_unicode=True)
        
        parser = ConfigParser(config_dir=str(test_config_dir))
        parser._env_vars = {}
        
        config = parser.parse("default_test")
        
        assert config["servers"][0]["host"] == "default.host.com"
    
    def test_list_environments(self, test_config_dir):
        """测试列出所有可用环境"""
        environments = ["production", "staging", "development"]
        
        for env in environments:
            config_file = test_config_dir / f"{env}.yaml"
            config_content = {
                "env_name": env,
                "servers": [{"host": "localhost", "port": 22, "user": "test"}],
                "deploy_path": "/var/www/test"
            }
            with open(config_file, 'w', encoding='utf-8') as f:
                yaml.dump(config_content, f, allow_unicode=True)
        
        parser = ConfigParser(config_dir=str(test_config_dir))
        result = parser.list_environments()
        
        assert sorted(result) == sorted(environments)


class TestConfigValidator:
    """ConfigValidator 单元测试"""
    
    def test_validate_valid_config(self, sample_valid_config):
        """测试有效配置的验证"""
        validator = ConfigValidator()
        errors = validator.validate(sample_valid_config)
        
        assert len(errors) == 0
        assert validator.is_valid(sample_valid_config) is True
    
    def test_validate_missing_required_fields(self):
        """测试缺失必填字段时的错误"""
        validator = ConfigValidator()
        
        config_without_servers = {
            "env_name": "test",
            "deploy_path": "/var/www/test"
        }
        errors = validator.validate(config_without_servers)
        assert len(errors) == 1
        assert errors[0].field == "servers"
        assert "缺少" in errors[0].message
        
        config_without_deploy_path = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}]
        }
        errors = validator.validate(config_without_deploy_path)
        assert len(errors) == 1
        assert errors[0].field == "deploy_path"
    
    def test_validate_server_config_invalid_port(self):
        """测试服务器端口号类型不匹配"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [
                {
                    "host": "localhost",
                    "port": "not_a_number",
                    "user": "test"
                }
            ],
            "deploy_path": "/var/www/test"
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        port_error = next((e for e in errors if "port" in e.field), None)
        assert port_error is not None
        assert "整数" in port_error.message or "类型" in port_error.message
    
    def test_validate_server_config_port_out_of_range(self):
        """测试服务器端口号超出范围"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [
                {
                    "host": "localhost",
                    "port": 99999,
                    "user": "test"
                }
            ],
            "deploy_path": "/var/www/test"
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        port_error = next((e for e in errors if "port" in e.field), None)
        assert port_error is not None
        assert "1-65535" in port_error.message
    
    def test_validate_health_check_http_type(self):
        """测试HTTP类型健康检查配置"""
        validator = ConfigValidator()
        
        valid_http_config = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}],
            "deploy_path": "/var/www/test",
            "health_check": {
                "type": "http",
                "url": "http://localhost/health"
            }
        }
        
        errors = validator.validate(valid_http_config)
        assert len(errors) == 0
    
    def test_validate_health_check_process_type(self):
        """测试进程类型健康检查配置"""
        validator = ConfigValidator()
        
        valid_process_config = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}],
            "deploy_path": "/var/www/test",
            "health_check": {
                "type": "process",
                "process_name": "node"
            }
        }
        
        errors = validator.validate(valid_process_config)
        assert len(errors) == 0
    
    def test_validate_health_check_invalid_type(self):
        """测试无效的健康检查类型"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}],
            "deploy_path": "/var/www/test",
            "health_check": {
                "type": "invalid_type",
                "url": "http://localhost/health"
            }
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        health_error = next((e for e in errors if "health" in e.field), None)
        assert health_error is not None
        assert "不支持的健康检查类型" in health_error.message
    
    def test_validate_health_check_missing_url_for_http(self):
        """测试HTTP健康检查缺少URL"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}],
            "deploy_path": "/var/www/test",
            "health_check": {
                "type": "http"
            }
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        health_error = next((e for e in errors if "url" in e.field), None)
        assert health_error is not None or any("缺少" in e.message for e in errors)
    
    def test_validate_servers_empty_list(self):
        """测试服务器列表为空"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [],
            "deploy_path": "/var/www/test"
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        server_error = next((e for e in errors if e.field == "servers"), None)
        assert server_error is not None
        assert "不能为空" in server_error.message
    
    def test_validate_servers_not_a_list(self):
        """测试服务器配置不是列表类型"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": {"host": "localhost", "port": 22, "user": "test"},
            "deploy_path": "/var/www/test"
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        server_error = next((e for e in errors if e.field == "servers"), None)
        assert server_error is not None
        assert "列表类型" in server_error.message
    
    def test_validate_positive_number(self):
        """测试正数验证（超时时间等）"""
        validator = ConfigValidator()
        
        config_with_negative_timeout = {
            "env_name": "test",
            "servers": [{"host": "localhost", "port": 22, "user": "test"}],
            "deploy_path": "/var/www/test",
            "health_check": {
                "type": "http",
                "url": "http://localhost/health",
                "timeout": -10
            }
        }
        
        errors = validator.validate(config_with_negative_timeout)
        
        assert len(errors) > 0
        timeout_error = next((e for e in errors if "timeout" in e.field), None)
        assert timeout_error is not None or any("大于0" in e.message for e in errors)
    
    def test_validate_multiple_errors(self):
        """测试多个错误的组合验证"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": "test",
            "servers": [
                {
                    "host": "localhost",
                    "port": 99999,
                    "user": "test"
                }
            ],
            "health_check": {
                "type": "invalid_type"
            }
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) >= 3
        
        error_fields = [e.field for e in errors]
        assert "deploy_path" in error_fields
        assert any("port" in field for field in error_fields)
        assert any("health" in field for field in error_fields)
    
    def test_validate_none_values(self):
        """测试None值的验证"""
        validator = ConfigValidator()
        
        invalid_config = {
            "env_name": None,
            "servers": None,
            "deploy_path": None
        }
        
        errors = validator.validate(invalid_config)
        
        assert len(errors) > 0
        assert any("不能为空" in e.message for e in errors)
