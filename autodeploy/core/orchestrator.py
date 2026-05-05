import time
from typing import Optional, Dict, Any, List, Callable
from dataclasses import asdict
from pathlib import Path
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

from ..config import ConfigParser, ConfigValidator, ValidationError
from ..connection import SSHConnection, SSHConnectionError, ServerConfig
from ..executor import FileTransfer, TransferResult, RemoteExecutor, BuildExecutor, BuildResult
from ..verification import HealthChecker, HealthCheckResult
from ..logging import DeployLogger, DeployRecord
from .models import (
    DeployStatus, StepStatus, DeployStep,
    StepExecutionResult, ServerDeployResult, DeployResult
)


class DeployOrchestrator:
    """
    部署编排器
    负责按照配置定义的步骤顺序编排执行流程，管理步骤依赖关系，支持并发控制
    """
    
    DEFAULT_MAX_CONCURRENT = 3
    DEFAULT_STEP_TIMEOUT = 300
    
    def __init__(self, config_dir: Optional[str] = None, 
                 log_dir: Optional[str] = None,
                 work_dir: Optional[str] = None):
        """
        初始化部署编排器
        
        Args:
            config_dir: 配置文件目录
            log_dir: 日志目录
            work_dir: 工作目录
        """
        self.config_parser = ConfigParser(config_dir)
        self.config_validator = ConfigValidator()
        self.deploy_logger = DeployLogger(log_dir)
        self.work_dir = work_dir
        self._build_executor: Optional[BuildExecutor] = None
        self._on_step_callback: Optional[Callable] = None
        self._on_server_callback: Optional[Callable] = None
    
    def set_step_callback(self, callback: Callable):
        """
        设置步骤执行回调函数
        
        Args:
            callback: 回调函数，参数为 (step_name, status, message)
        """
        self._on_step_callback = callback
    
    def set_server_callback(self, callback: Callable):
        """
        设置服务器部署回调函数
        
        Args:
            callback: 回调函数，参数为 (server_host, status, message)
        """
        self._on_server_callback = callback
    
    def _notify_step(self, step_name: str, status: StepStatus, message: str):
        """
        通知步骤状态
        
        Args:
            step_name: 步骤名称
            status: 步骤状态
            message: 消息
        """
        if self._on_step_callback:
            self._on_step_callback(step_name, status, message)
    
    def _notify_server(self, server_host: str, status: DeployStatus, message: str):
        """
        通知服务器状态
        
        Args:
            server_host: 服务器主机
            status: 状态
            message: 消息
        """
        if self._on_server_callback:
            self._on_server_callback(server_host, status, message)
    
    def deploy(self, env_name: str, 
               build_override: Optional[str] = None,
               max_concurrent: Optional[int] = None,
               skip_build: bool = False) -> DeployResult:
        """
        执行部署
        
        Args:
            env_name: 环境名称
            build_override: 构建命令覆盖
            max_concurrent: 最大并发服务器数
            skip_build: 是否跳过构建步骤
            
        Returns:
            部署结果
        """
        actual_max_concurrent = max_concurrent if max_concurrent is not None else self.DEFAULT_MAX_CONCURRENT
        
        self._notify_step("load_config", StepStatus.IN_PROGRESS, f"正在加载环境配置: {env_name}")
        
        try:
            config = self.config_parser.parse(env_name)
        except Exception as e:
            self._notify_step("load_config", StepStatus.FAILED, f"配置加载失败: {str(e)}")
            return self._create_failed_result(
                env_name,
                f"配置加载失败: {str(e)}"
            )
        
        self._notify_step("validate_config", StepStatus.IN_PROGRESS, "正在验证配置")
        
        validation_errors = self.config_validator.validate(config)
        if validation_errors:
            error_messages = "; ".join([f"{e.field}: {e.message}" for e in validation_errors])
            self._notify_step("validate_config", StepStatus.FAILED, f"配置验证失败: {error_messages}")
            return self._create_failed_result(
                env_name,
                f"配置验证失败: {error_messages}"
            )
        
        self._notify_step("validate_config", StepStatus.SUCCESS, "配置验证通过")
        
        servers_config = config.get("servers", [])
        if not servers_config:
            self._notify_step("deploy", StepStatus.FAILED, "没有配置目标服务器")
            return self._create_failed_result(
                env_name,
                "没有配置目标服务器"
            )
        
        deploy_record = self.deploy_logger.start_deploy(
            env_name=env_name,
            servers=servers_config
        )
        
        self.deploy_logger.start_step("load_config")
        self.deploy_logger.end_step("load_config", "success", "配置加载成功")
        
        self.deploy_logger.start_step("validate_config")
        self.deploy_logger.end_step("validate_config", "success", "配置验证通过")
        
        build_result_data: Optional[Dict[str, Any]] = None
        
        if not skip_build:
            build_command = build_override if build_override else config.get("build_command")
            
            if build_command:
                self._notify_step("build", StepStatus.IN_PROGRESS, f"正在执行构建: {build_command}")
                self.deploy_logger.start_step("build")
                
                build_result = self._execute_build(config, build_command)
                build_result_data = asdict(build_result) if build_result else None
                
                if build_result and build_result.success:
                    self._notify_step("build", StepStatus.SUCCESS, f"构建成功，产物数: {len(build_result.artifacts)}")
                    self.deploy_logger.end_step(
                        "build", "success", 
                        f"构建成功，耗时 {build_result.duration:.1f}秒"
                    )
                else:
                    error_msg = build_result.stderr if build_result else "构建失败"
                    self._notify_step("build", StepStatus.FAILED, f"构建失败: {error_msg}")
                    self.deploy_logger.end_step("build", "failed", error=error_msg)
                    
                    self.deploy_logger.end_deploy(
                        status="failed",
                        error_message=error_msg,
                        rollback_available=False
                    )
                    
                    return self._create_failed_result(
                        env_name,
                        error_msg,
                        deploy_record.deploy_id
                    )
            else:
                self._notify_step("build", StepStatus.SKIPPED, "没有配置构建命令，跳过构建")
                self.deploy_logger.start_step("build")
                self.deploy_logger.end_step("build", "skipped", "没有配置构建命令")
        else:
            self._notify_step("build", StepStatus.SKIPPED, "跳过构建步骤")
            self.deploy_logger.start_step("build")
            self.deploy_logger.end_step("build", "skipped", "用户指定跳过构建")
        
        self.deploy_logger.start_step("deploy_servers")
        
        server_results: List[ServerDeployResult] = []
        
        if actual_max_concurrent > 1 and len(servers_config) > 1:
            server_results = self._deploy_servers_concurrent(
                config,
                servers_config,
                build_result,
                actual_max_concurrent
            )
        else:
            for server_config in servers_config:
                server_result = self._deploy_single_server(
                    config,
                    server_config,
                    build_result
                )
                server_results.append(server_result)
        
        success_count = sum(1 for r in server_results if r.success)
        failed_count = len(server_results) - success_count
        
        if failed_count == 0:
            overall_status = DeployStatus.COMPLETED
            overall_success = True
            self.deploy_logger.end_step(
                "deploy_servers", "success",
                f"所有服务器部署成功: {success_count}/{len(server_results)}"
            )
        elif success_count > 0:
            overall_status = DeployStatus.PARTIAL_SUCCESS
            overall_success = False
            self.deploy_logger.end_step(
                "deploy_servers", "failed",
                f"部分服务器部署失败: 成功{success_count}, 失败{failed_count}"
            )
        else:
            overall_status = DeployStatus.FAILED
            overall_success = False
            self.deploy_logger.end_step(
                "deploy_servers", "failed",
                f"所有服务器部署失败: {failed_count}/{len(server_results)}"
            )
        
        rollback_available = config.get("rollback_enabled", False)
        
        completed_record = self.deploy_logger.end_deploy(
            status=overall_status.value,
            error_message=None if overall_success else f"{failed_count}台服务器部署失败",
            rollback_available=rollback_available
        )
        
        deploy_result = DeployResult(
            deploy_id=completed_record.deploy_id,
            env_name=env_name,
            status=overall_status,
            success=overall_success,
            trigger_time=completed_record.trigger_time,
            end_time=datetime.utcnow().isoformat() + "Z",
            total_duration=sum(
                (r.steps[-1].duration if r.steps else 0) 
                for r in server_results
            ) if server_results else 0,
            server_results=server_results,
            build_result=build_result_data,
            rollback_available=rollback_available,
            summary={
                "total_servers": len(server_results),
                "success_count": success_count,
                "failed_count": failed_count
            }
        )
        
        return deploy_result
    
    def _execute_build(self, config: Dict[str, Any], 
                       build_command: str) -> Optional[BuildResult]:
        """
        执行构建
        
        Args:
            config: 配置字典
            build_command: 构建命令
            
        Returns:
            构建结果
        """
        if self._build_executor is None:
            self._build_executor = BuildExecutor(self.work_dir)
        
        build_output = config.get("build_output", "./dist")
        build_timeout = config.get("build_timeout")
        
        result = self._build_executor.build_and_collect(
            command=build_command,
            output_dir=build_output,
            timeout=build_timeout
        )
        
        return result
    
    def _deploy_single_server(self, config: Dict[str, Any],
                              server_config: Dict[str, Any],
                              build_result: Optional[BuildResult]) -> ServerDeployResult:
        """
        部署单台服务器
        
        Args:
            config: 整体配置
            server_config: 服务器配置
            build_result: 构建结果
            
        Returns:
            服务器部署结果
        """
        server_host = server_config.get("host", "unknown")
        server_port = server_config.get("port", 22)
        
        self._notify_server(server_host, DeployStatus.IN_PROGRESS, "开始部署")
        
        step_results: List[StepExecutionResult] = []
        
        try:
            server_cfg = ServerConfig(
                host=server_host,
                port=server_port,
                user=server_config.get("user", "root"),
                key_file=server_config.get("key_file"),
                password=server_config.get("password")
            )
            
            with SSHConnection(server_cfg) as ssh_conn:
                file_transfer = FileTransfer(ssh_conn)
                remote_executor = RemoteExecutor(ssh_conn)
                health_checker = HealthChecker(ssh_conn)
                
                deploy_path = config.get("deploy_path")
                build_output = config.get("build_output")
                config_files = config.get("config_files", [])
                
                stop_command = config.get("stop_command")
                start_command = config.get("start_command")
                health_check_config = config.get("health_check")
                rollback_enabled = config.get("rollback_enabled", False)
                
                step_results.append(self._create_step_result(
                    "connect", StepStatus.SUCCESS, 0,
                    "SSH连接成功"
                ))
                
                if build_result and build_result.success and build_output:
                    self._notify_server(server_host, DeployStatus.IN_PROGRESS, "传输构建产物")
                    self.deploy_logger.start_step(f"transfer_{server_host}")
                    
                    transfer_start = time.time()
                    batch_result = file_transfer.transfer_directory(
                        source_dir=build_output,
                        target_dir=deploy_path,
                        backup=rollback_enabled,
                        verify=True,
                        recursive=True
                    )
                    transfer_duration = time.time() - transfer_start
                    
                    if batch_result.failed_count == 0:
                        step_results.append(self._create_step_result(
                            "transfer_artifacts", StepStatus.SUCCESS, transfer_duration,
                            f"传输成功: {batch_result.success_count}个文件"
                        ))
                        self.deploy_logger.end_step(
                            f"transfer_{server_host}", "success",
                            f"传输成功: {batch_result.success_count}个文件"
                        )
                    else:
                        step_results.append(self._create_step_result(
                            "transfer_artifacts", StepStatus.FAILED, transfer_duration,
                            f"传输失败: {batch_result.failed_count}个文件失败",
                            error=f"失败文件: {batch_result.failed_files}"
                        ))
                        self.deploy_logger.end_step(
                            f"transfer_{server_host}", "failed",
                            error=f"传输失败: {batch_result.failed_count}个文件"
                        )
                        
                        if rollback_enabled:
                            file_transfer.restore_all_backups()
                        
                        return self._create_server_result(
                            server_host, server_port, False,
                            DeployStatus.FAILED, step_results,
                            f"构建产物传输失败: {batch_result.failed_count}个文件"
                        )
                
                if config_files:
                    self._notify_server(server_host, DeployStatus.IN_PROGRESS, "传输配置文件")
                    
                    for config_file in config_files:
                        source = config_file.get("source")
                        target = config_file.get("target")
                        
                        if source and target:
                            transfer_result = file_transfer.transfer_file(
                                source_path=source,
                                target_path=target,
                                backup=rollback_enabled,
                                verify=True
                            )
                            
                            if transfer_result.success:
                                step_results.append(self._create_step_result(
                                    f"transfer_config_{Path(source).name}", StepStatus.SUCCESS, 0,
                                    f"配置文件传输成功: {source}"
                                ))
                            else:
                                step_results.append(self._create_step_result(
                                    f"transfer_config_{Path(source).name}", StepStatus.FAILED, 0,
                                    f"配置文件传输失败: {source}",
                                    error=transfer_result.error_message
                                ))
                
                if stop_command:
                    self._notify_server(server_host, DeployStatus.IN_PROGRESS, "停止服务")
                    
                    stop_start = time.time()
                    stop_result = remote_executor.stop_service(stop_command)
                    stop_duration = time.time() - stop_start
                    
                    if stop_result.success:
                        step_results.append(self._create_step_result(
                            "stop_service", StepStatus.SUCCESS, stop_duration,
                            "服务停止成功",
                            output={"stdout": stop_result.stdout, "stderr": stop_result.stderr}
                        ))
                    else:
                        step_results.append(self._create_step_result(
                            "stop_service", StepStatus.FAILED, stop_duration,
                            "服务停止失败",
                            error=stop_result.stderr or stop_result.error_message
                        ))
                
                if start_command:
                    self._notify_server(server_host, DeployStatus.IN_PROGRESS, "启动服务")
                    
                    start_delay = config.get("start_delay", 5)
                    start_start = time.time()
                    start_result = remote_executor.start_service(
                        start_command,
                        wait_delay=start_delay
                    )
                    start_duration = time.time() - start_start
                    
                    if start_result.success:
                        step_results.append(self._create_step_result(
                            "start_service", StepStatus.SUCCESS, start_duration,
                            "服务启动成功",
                            output={"stdout": start_result.stdout, "stderr": start_result.stderr}
                        ))
                    else:
                        step_results.append(self._create_step_result(
                            "start_service", StepStatus.FAILED, start_duration,
                            "服务启动失败",
                            error=start_result.stderr or start_result.error_message
                        ))
                        
                        if rollback_enabled:
                            self._notify_server(server_host, DeployStatus.IN_PROGRESS, "执行回滚")
                            rollback_success = file_transfer.restore_all_backups()
                            
                            step_results.append(self._create_step_result(
                                "rollback", StepStatus.SUCCESS if all(rollback_success.values()) else StepStatus.FAILED,
                                0,
                                f"回滚执行: {'成功' if all(rollback_success.values()) else '部分失败'}"
                            ))
                            
                            return self._create_server_result(
                                server_host, server_port, False,
                                DeployStatus.ROLLED_BACK, step_results,
                                "服务启动失败，已执行回滚",
                                rollback_performed=True,
                                rollback_success=all(rollback_success.values())
                            )
                        
                        return self._create_server_result(
                            server_host, server_port, False,
                            DeployStatus.FAILED, step_results,
                            f"服务启动失败: {start_result.stderr or start_result.error_message}"
                        )
                
                if health_check_config:
                    self._notify_server(server_host, DeployStatus.IN_PROGRESS, "执行健康检查")
                    
                    health_start = time.time()
                    health_result = health_checker.check_from_config(health_check_config)
                    health_duration = time.time() - health_start
                    
                    if health_result.success:
                        step_results.append(self._create_step_result(
                            "health_check", StepStatus.SUCCESS, health_duration,
                            f"健康检查通过: {health_result.message}",
                            output={
                                "response_time": health_result.response_time,
                                "status_code": health_result.status_code
                            }
                        ))
                    else:
                        step_results.append(self._create_step_result(
                            "health_check", StepStatus.FAILED, health_duration,
                            f"健康检查失败: {health_result.message}",
                            error=health_result.error
                        ))
                        
                        if rollback_enabled:
                            self._notify_server(server_host, DeployStatus.IN_PROGRESS, "执行回滚")
                            rollback_success = file_transfer.restore_all_backups()
                            
                            step_results.append(self._create_step_result(
                                "rollback", StepStatus.SUCCESS if all(rollback_success.values()) else StepStatus.FAILED,
                                0,
                                f"回滚执行: {'成功' if all(rollback_success.values()) else '部分失败'}"
                            ))
                            
                            return self._create_server_result(
                                server_host, server_port, False,
                                DeployStatus.ROLLED_BACK, step_results,
                                "健康检查失败，已执行回滚",
                                rollback_performed=True,
                                rollback_success=all(rollback_success.values())
                            )
                        
                        return self._create_server_result(
                            server_host, server_port, False,
                            DeployStatus.FAILED, step_results,
                            f"健康检查失败: {health_result.message}"
                        )
                
                self._notify_server(server_host, DeployStatus.COMPLETED, "部署完成")
                
                return self._create_server_result(
                    server_host, server_port, True,
                    DeployStatus.COMPLETED, step_results,
                    "部署成功"
                )
                
        except SSHConnectionError as e:
            step_results.append(self._create_step_result(
                "connect", StepStatus.FAILED, 0,
                "SSH连接失败",
                error=str(e)
            ))
            self._notify_server(server_host, DeployStatus.FAILED, f"SSH连接失败: {str(e)}")
            
            return self._create_server_result(
                server_host, server_port, False,
                DeployStatus.FAILED, step_results,
                f"SSH连接失败: {str(e)}"
            )
        except Exception as e:
            step_results.append(self._create_step_result(
                "deploy", StepStatus.FAILED, 0,
                "部署过程发生错误",
                error=str(e)
            ))
            self._notify_server(server_host, DeployStatus.FAILED, f"部署错误: {str(e)}")
            
            return self._create_server_result(
                server_host, server_port, False,
                DeployStatus.FAILED, step_results,
                f"部署错误: {str(e)}"
            )
    
    def _deploy_servers_concurrent(self, config: Dict[str, Any],
                                   servers_config: List[Dict[str, Any]],
                                   build_result: Optional[BuildResult],
                                   max_concurrent: int) -> List[ServerDeployResult]:
        """
        并发部署多台服务器
        
        Args:
            config: 整体配置
            servers_config: 服务器配置列表
            build_result: 构建结果
            max_concurrent: 最大并发数
            
        Returns:
            服务器部署结果列表
        """
        results: List[ServerDeployResult] = []
        
        with ThreadPoolExecutor(max_workers=max_concurrent) as executor:
            future_to_server = {
                executor.submit(
                    self._deploy_single_server,
                    config,
                    server_config,
                    build_result
                ): server_config
                for server_config in servers_config
            }
            
            for future in as_completed(future_to_server):
                try:
                    result = future.result()
                    results.append(result)
                except Exception as e:
                    server_config = future_to_server[future]
                    server_host = server_config.get("host", "unknown")
                    server_port = server_config.get("port", 22)
                    
                    error_result = self._create_server_result(
                        server_host, server_port, False,
                        DeployStatus.FAILED, [],
                        f"并发部署异常: {str(e)}"
                    )
                    results.append(error_result)
        
        return results
    
    def _create_step_result(self, step_name: str, status: StepStatus,
                            duration: float, message: str,
                            error: Optional[str] = None,
                            output: Optional[Dict[str, Any]] = None) -> StepExecutionResult:
        """
        创建步骤执行结果
        
        Args:
            step_name: 步骤名称
            status: 步骤状态
            duration: 持续时间（秒）
            message: 消息
            error: 错误信息
            output: 输出数据
            
        Returns:
            步骤执行结果
        """
        now = datetime.utcnow().isoformat() + "Z"
        
        return StepExecutionResult(
            step_name=step_name,
            status=status,
            duration=duration,
            start_time=now,
            end_time=now,
            message=message,
            error=error,
            output=output
        )
    
    def _create_server_result(self, host: str, port: int, success: bool,
                              status: DeployStatus, steps: List[StepExecutionResult],
                              error_message: Optional[str] = None,
                              rollback_performed: bool = False,
                              rollback_success: Optional[bool] = None) -> ServerDeployResult:
        """
        创建服务器部署结果
        
        Args:
            host: 服务器主机
            port: 服务器端口
            success: 是否成功
            status: 部署状态
            steps: 步骤结果列表
            error_message: 错误消息
            rollback_performed: 是否执行了回滚
            rollback_success: 回滚是否成功
            
        Returns:
            服务器部署结果
        """
        return ServerDeployResult(
            server_host=host,
            server_port=port,
            success=success,
            status=status,
            steps=steps,
            error_message=error_message,
            rollback_performed=rollback_performed,
            rollback_success=rollback_success
        )
    
    def _create_failed_result(self, env_name: str, error_message: str,
                              deploy_id: Optional[str] = None) -> DeployResult:
        """
        创建失败的部署结果
        
        Args:
            env_name: 环境名称
            error_message: 错误消息
            deploy_id: 部署ID（可选）
            
        Returns:
            部署结果
        """
        now = datetime.utcnow().isoformat() + "Z"
        
        return DeployResult(
            deploy_id=deploy_id or "unknown",
            env_name=env_name,
            status=DeployStatus.FAILED,
            success=False,
            trigger_time=now,
            end_time=now,
            error_message=error_message,
            summary={"error": error_message}
        )
    
    def list_environments(self) -> List[str]:
        """
        列出所有可用环境
        
        Returns:
            环境名称列表
        """
        return self.config_parser.list_environments()
    
    def get_deploy_history(self, env_name: Optional[str] = None,
                          limit: int = 20) -> List[DeployRecord]:
        """
        获取部署历史
        
        Args:
            env_name: 环境名称筛选
            limit: 返回数量限制
            
        Returns:
            部署记录列表
        """
        return self.deploy_logger.list_deploy_history(env_name, limit)
    
    def get_deploy_record(self, deploy_id: str) -> Optional[DeployRecord]:
        """
        获取指定的部署记录
        
        Args:
            deploy_id: 部署ID
            
        Returns:
            部署记录，不存在则返回None
        """
        return self.deploy_logger.get_deploy_record(deploy_id)
