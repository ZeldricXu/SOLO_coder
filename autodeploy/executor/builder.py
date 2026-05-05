import os
import subprocess
import time
from typing import Optional, List, Dict, Any
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class BuildArtifact:
    """
    构建产物
    """
    path: str
    file_name: str
    file_size: int
    is_directory: bool = False
    relative_path: Optional[str] = None


@dataclass
class BuildResult:
    """
    构建结果
    """
    success: bool
    command: str
    exit_code: int
    stdout: str
    stderr: str
    duration: float
    artifacts: List[BuildArtifact] = field(default_factory=list)
    output_dir: Optional[str] = None
    error_message: Optional[str] = None


class BuildExecutor:
    """
    构建执行器
    负责本地触发代码构建命令，收集构建产物文件
    """
    
    DEFAULT_TIMEOUT = 600
    
    def __init__(self, work_dir: Optional[str] = None):
        """
        初始化构建执行器
        
        Args:
            work_dir: 工作目录，默认为当前目录
        """
        self.work_dir = Path(work_dir) if work_dir else Path.cwd()
    
    def execute(self, command: str, timeout: Optional[int] = None,
                env: Optional[Dict[str, str]] = None) -> BuildResult:
        """
        执行构建命令
        
        Args:
            command: 构建命令
            timeout: 超时时间（秒）
            env: 环境变量
            
        Returns:
            构建结果
        """
        actual_timeout = timeout if timeout is not None else self.DEFAULT_TIMEOUT
        
        full_env = os.environ.copy()
        if env:
            full_env.update(env)
        
        start_time = time.time()
        
        try:
            process = subprocess.Popen(
                command,
                shell=True,
                cwd=str(self.work_dir),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=full_env,
                text=True
            )
            
            stdout, stderr = process.communicate(timeout=actual_timeout)
            exit_code = process.returncode
            
            duration = time.time() - start_time
            
            success = exit_code == 0
            
            return BuildResult(
                success=success,
                command=command,
                exit_code=exit_code,
                stdout=stdout,
                stderr=stderr,
                duration=duration
            )
            
        except subprocess.TimeoutExpired:
            duration = time.time() - start_time
            
            if 'process' in locals():
                process.kill()
                process.wait()
            
            return BuildResult(
                success=False,
                command=command,
                exit_code=-1,
                stdout="",
                stderr=f"构建超时: {actual_timeout}秒",
                duration=duration,
                error_message="Build timeout"
            )
        except Exception as e:
            duration = time.time() - start_time
            return BuildResult(
                success=False,
                command=command,
                exit_code=-1,
                stdout="",
                stderr=str(e),
                duration=duration,
                error_message=str(e)
            )
    
    def collect_artifacts(self, output_dir: str, 
                          include_patterns: Optional[List[str]] = None,
                          exclude_patterns: Optional[List[str]] = None) -> List[BuildArtifact]:
        """
        收集构建产物
        
        Args:
            output_dir: 构建输出目录
            include_patterns: 包含的文件模式（如 ["*.js", "*.css"]）
            exclude_patterns: 排除的文件模式
            
        Returns:
            构建产物列表
        """
        output_path = Path(output_dir)
        
        if not output_path.exists():
            return []
        
        if not output_path.is_absolute():
            output_path = self.work_dir / output_path
        
        artifacts = []
        
        if output_path.is_file():
            artifact = BuildArtifact(
                path=str(output_path),
                file_name=output_path.name,
                file_size=output_path.stat().st_size,
                is_directory=False,
                relative_path=output_path.name
            )
            artifacts.append(artifact)
            return artifacts
        
        for root, dirs, files in os.walk(output_path):
            for file_name in files:
                file_path = Path(root) / file_name
                
                if self._match_patterns(file_name, include_patterns) and \
                   not self._match_patterns(file_name, exclude_patterns):
                    
                    relative_path = str(file_path.relative_to(output_path))
                    
                    artifact = BuildArtifact(
                        path=str(file_path),
                        file_name=file_name,
                        file_size=file_path.stat().st_size,
                        is_directory=False,
                        relative_path=relative_path
                    )
                    artifacts.append(artifact)
            
            for dir_name in dirs:
                dir_path = Path(root) / dir_name
                relative_path = str(dir_path.relative_to(output_path))
                
                dir_artifact = BuildArtifact(
                    path=str(dir_path),
                    file_name=dir_name,
                    file_size=0,
                    is_directory=True,
                    relative_path=relative_path
                )
                artifacts.append(dir_artifact)
        
        return artifacts
    
    def build_and_collect(self, command: str, output_dir: str,
                          timeout: Optional[int] = None,
                          env: Optional[Dict[str, str]] = None,
                          include_patterns: Optional[List[str]] = None,
                          exclude_patterns: Optional[List[str]] = None) -> BuildResult:
        """
        执行构建并收集产物
        
        Args:
            command: 构建命令
            output_dir: 构建输出目录
            timeout: 超时时间
            env: 环境变量
            include_patterns: 包含的文件模式
            exclude_patterns: 排除的文件模式
            
        Returns:
            构建结果（包含收集的产物）
        """
        build_result = self.execute(command, timeout, env)
        
        if build_result.success:
            artifacts = self.collect_artifacts(
                output_dir,
                include_patterns,
                exclude_patterns
            )
            build_result.artifacts = artifacts
            build_result.output_dir = output_dir
        
        return build_result
    
    def _match_patterns(self, name: str, patterns: Optional[List[str]]) -> bool:
        """
        检查名称是否匹配任意模式
        
        Args:
            name: 文件/目录名称
            patterns: 模式列表
            
        Returns:
            True 表示匹配（或模式为None/空）
        """
        if patterns is None or len(patterns) == 0:
            return True
        
        import fnmatch
        
        for pattern in patterns:
            if fnmatch.fnmatch(name, pattern):
                return True
        
        return False
    
    def get_output_dir_size(self, output_dir: str) -> int:
        """
        获取输出目录的总大小
        
        Args:
            output_dir: 输出目录路径
            
        Returns:
            总字节数
        """
        output_path = Path(output_dir)
        
        if not output_path.exists():
            return 0
        
        if output_path.is_file():
            return output_path.stat().st_size
        
        total_size = 0
        
        for root, dirs, files in os.walk(output_path):
            for file_name in files:
                file_path = Path(root) / file_name
                total_size += file_path.stat().st_size
        
        return total_size
    
    def verify_build_output(self, output_dir: str, 
                           required_files: Optional[List[str]] = None) -> bool:
        """
        验证构建输出是否完整
        
        Args:
            output_dir: 输出目录
            required_files: 必需的文件列表
            
        Returns:
            True 表示验证通过
        """
        output_path = Path(output_dir)
        
        if not output_path.exists():
            return False
        
        if required_files is None or len(required_files) == 0:
            return output_path.exists()
        
        for required_file in required_files:
            file_path = output_path / required_file
            if not file_path.exists():
                return False
        
        return True
