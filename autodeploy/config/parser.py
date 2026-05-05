import os
import yaml
from typing import Dict, List, Any, Optional
from pathlib import Path


class ConfigParser:
    """
    配置解析器
    负责读取YAML配置文件，解析环境参数，支持环境变量替换
    """
    
    DEFAULT_CONFIG_DIR = "configs"
    
    def __init__(self, config_dir: Optional[str] = None):
        """
        初始化配置解析器
        
        Args:
            config_dir: 配置文件目录，默认为当前目录下的 configs
        """
        self.config_dir = Path(config_dir) if config_dir else Path(self.DEFAULT_CONFIG_DIR)
        self._env_vars = os.environ.copy()
    
    def parse(self, env_name: str) -> Dict[str, Any]:
        """
        解析指定环境的配置文件
        
        Args:
            env_name: 环境名称，如 production, staging, development
            
        Returns:
            解析后的配置字典
            
        Raises:
            FileNotFoundError: 配置文件不存在
            yaml.YAMLError: YAML格式错误
        """
        config_path = self._get_config_path(env_name)
        
        if not config_path.exists():
            raise FileNotFoundError(f"配置文件不存在: {config_path}")
        
        with open(config_path, 'r', encoding='utf-8') as f:
            config = yaml.safe_load(f)
        
        config = self._resolve_env_vars(config)
        
        if "env_name" not in config:
            config["env_name"] = env_name
        
        return config
    
    def list_environments(self) -> List[str]:
        """
        列出所有可用的环境配置
        
        Returns:
            环境名称列表
        """
        if not self.config_dir.exists():
            return []
        
        environments = []
        for file_path in self.config_dir.glob("*.yaml"):
            env_name = file_path.stem
            environments.append(env_name)
        
        return sorted(environments)
    
    def _get_config_path(self, env_name: str) -> Path:
        """
        获取配置文件路径
        
        Args:
            env_name: 环境名称
            
        Returns:
            配置文件的完整路径
        """
        if env_name.endswith(".yaml") or env_name.endswith(".yml"):
            return Path(env_name)
        else:
            return self.config_dir / f"{env_name}.yaml"
    
    def _resolve_env_vars(self, config: Any) -> Any:
        """
        递归解析配置中的环境变量引用
        支持格式: ${VAR_NAME} 或 $VAR_NAME
        
        Args:
            config: 配置值（可以是字典、列表、字符串等）
            
        Returns:
            解析后的配置值
        """
        if isinstance(config, dict):
            return {key: self._resolve_env_vars(value) for key, value in config.items()}
        elif isinstance(config, list):
            return [self._resolve_env_vars(item) for item in config]
        elif isinstance(config, str):
            return self._replace_env_vars_in_string(config)
        else:
            return config
    
    def _replace_env_vars_in_string(self, value: str) -> str:
        """
        替换字符串中的环境变量引用
        
        Args:
            value: 包含环境变量引用的字符串
            
        Returns:
            替换后的字符串
        """
        import re
        
        def replace_var(match):
            var_name = match.group(1) or match.group(2)
            default_value = match.group(3) if match.lastindex >= 3 else None
            
            env_value = self._env_vars.get(var_name)
            
            if env_value is not None:
                return env_value
            elif default_value is not None:
                return default_value
            else:
                raise ValueError(f"环境变量未设置: {var_name}")
        
        pattern = re.compile(r'\$\{(\w+)(?::-([^}]*))?\}|\$(\w+)')
        
        return pattern.sub(replace_var, value)
