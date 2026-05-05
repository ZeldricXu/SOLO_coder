from typing import Dict, List, Any, Optional
from dataclasses import dataclass


@dataclass
class ValidationError:
    """
    配置校验错误
    """
    field: str
    message: str


class ConfigValidator:
    """
    配置校验器
    负责验证部署配置的完整性和正确性
    """
    
    REQUIRED_FIELDS = [
        "env_name",
        "servers",
        "deploy_path"
    ]
    
    SERVER_REQUIRED_FIELDS = [
        "host",
        "port",
        "user"
    ]
    
    HEALTH_CHECK_HTTP_FIELDS = [
        "url"
    ]
    
    HEALTH_CHECK_PROCESS_FIELDS = [
        "process_name"
    ]
    
    def validate(self, config: Dict[str, Any]) -> List[ValidationError]:
        """
        验证配置的完整性和正确性
        
        Args:
            config: 配置字典
            
        Returns:
            校验错误列表，如果为空则表示校验通过
        """
        errors = []
        
        errors.extend(self._validate_required_fields(config))
        
        if "servers" in config:
            errors.extend(self._validate_servers(config["servers"]))
        
        if "health_check" in config:
            errors.extend(self._validate_health_check(config["health_check"]))
        
        if "port" in config:
            errors.extend(self._validate_port(config["port"], "port"))
        
        if "timeout" in config:
            errors.extend(self._validate_positive_number(config["timeout"], "timeout"))
        
        return errors
    
    def _validate_required_fields(self, config: Dict[str, Any]) -> List[ValidationError]:
        """
        验证必填字段
        
        Args:
            config: 配置字典
            
        Returns:
            校验错误列表
        """
        errors = []
        
        for field in self.REQUIRED_FIELDS:
            if field not in config:
                errors.append(ValidationError(
                    field=field,
                    message=f"缺少必填字段: {field}"
                ))
            elif config[field] is None:
                errors.append(ValidationError(
                    field=field,
                    message=f"字段不能为空: {field}"
                ))
        
        return errors
    
    def _validate_servers(self, servers: Any) -> List[ValidationError]:
        """
        验证服务器配置
        
        Args:
            servers: 服务器配置列表
            
        Returns:
            校验错误列表
        """
        errors = []
        
        if not isinstance(servers, list):
            errors.append(ValidationError(
                field="servers",
                message="servers 必须是列表类型"
            ))
            return errors
        
        if len(servers) == 0:
            errors.append(ValidationError(
                field="servers",
                message="服务器列表不能为空"
            ))
            return errors
        
        for index, server in enumerate(servers):
            if not isinstance(server, dict):
                errors.append(ValidationError(
                    field=f"servers[{index}]",
                    message=f"服务器配置[{index}]必须是字典类型"
                ))
                continue
            
            for field in self.SERVER_REQUIRED_FIELDS:
                if field not in server:
                    errors.append(ValidationError(
                        field=f"servers[{index}].{field}",
                        message=f"服务器[{index}]缺少必填字段: {field}"
                    ))
                elif server[field] is None:
                    errors.append(ValidationError(
                        field=f"servers[{index}].{field}",
                        message=f"服务器[{index}]字段不能为空: {field}"
                    ))
            
            if "port" in server:
                errors.extend(self._validate_port(server["port"], f"servers[{index}].port"))
            
            if "key_file" in server and server["key_file"] is not None:
                if not isinstance(server["key_file"], str):
                    errors.append(ValidationError(
                        field=f"servers[{index}].key_file",
                        message=f"服务器[{index}]key_file必须是字符串类型"
                    ))
        
        return errors
    
    def _validate_health_check(self, health_check: Any) -> List[ValidationError]:
        """
        验证健康检查配置
        
        Args:
            health_check: 健康检查配置
            
        Returns:
            校验错误列表
        """
        errors = []
        
        if not isinstance(health_check, dict):
            errors.append(ValidationError(
                field="health_check",
                message="health_check 必须是字典类型"
            ))
            return errors
        
        if "type" not in health_check:
            errors.append(ValidationError(
                field="health_check.type",
                message="健康检查缺少类型字段"
            ))
            return errors
        
        check_type = health_check["type"]
        
        if check_type == "http":
            for field in self.HEALTH_CHECK_HTTP_FIELDS:
                if field not in health_check:
                    errors.append(ValidationError(
                        field=f"health_check.{field}",
                        message=f"HTTP健康检查缺少必填字段: {field}"
                    ))
        
        elif check_type == "process":
            for field in self.HEALTH_CHECK_PROCESS_FIELDS:
                if field not in health_check:
                    errors.append(ValidationError(
                        field=f"health_check.{field}",
                        message=f"进程健康检查缺少必填字段: {field}"
                    ))
        
        else:
            errors.append(ValidationError(
                field="health_check.type",
                message=f"不支持的健康检查类型: {check_type}，支持类型: http, process"
            ))
        
        if "timeout" in health_check:
            errors.extend(self._validate_positive_number(
                health_check["timeout"],
                "health_check.timeout"
            ))
        
        return errors
    
    def _validate_port(self, port: Any, field_name: str) -> List[ValidationError]:
        """
        验证端口号
        
        Args:
            port: 端口号值
            field_name: 字段名称，用于错误消息
            
        Returns:
            校验错误列表
        """
        errors = []
        
        if not isinstance(port, int):
            errors.append(ValidationError(
                field=field_name,
                message=f"{field_name} 必须是整数类型"
            ))
            return errors
        
        if port < 1 or port > 65535:
            errors.append(ValidationError(
                field=field_name,
                message=f"{field_name} 必须在 1-65535 范围内"
            ))
        
        return errors
    
    def _validate_positive_number(self, value: Any, field_name: str) -> List[ValidationError]:
        """
        验证正数值
        
        Args:
            value: 数值
            field_name: 字段名称
            
        Returns:
            校验错误列表
        """
        errors = []
        
        if not isinstance(value, (int, float)):
            errors.append(ValidationError(
                field=field_name,
                message=f"{field_name} 必须是数字类型"
            ))
            return errors
        
        if value <= 0:
            errors.append(ValidationError(
                field=field_name,
                message=f"{field_name} 必须大于0"
            ))
        
        return errors
    
    def is_valid(self, config: Dict[str, Any]) -> bool:
        """
        检查配置是否有效
        
        Args:
            config: 配置字典
            
        Returns:
            True 表示有效，False 表示无效
        """
        errors = self.validate(config)
        return len(errors) == 0
