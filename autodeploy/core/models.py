from enum import Enum
from typing import Optional, Dict, Any, List
from dataclasses import dataclass, field
from datetime import datetime


class StepStatus(Enum):
    """
    步骤执行状态
    """
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    SUCCESS = "success"
    FAILED = "failed"
    SKIPPED = "skipped"
    ROLLED_BACK = "rolled_back"


class DeployStatus(Enum):
    """
    部署整体状态
    """
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    PARTIAL_SUCCESS = "partial_success"
    FAILED = "failed"
    ROLLED_BACK = "rolled_back"


@dataclass
class DeployStep:
    """
    部署步骤定义
    """
    name: str
    description: Optional[str] = None
    dependencies: List[str] = field(default_factory=list)
    timeout: Optional[int] = None
    retries: int = 0
    can_rollback: bool = True
    enabled: bool = True
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class StepExecutionResult:
    """
    步骤执行结果
    """
    step_name: str
    status: StepStatus
    duration: float
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    message: Optional[str] = None
    error: Optional[str] = None
    output: Optional[Dict[str, Any]] = None


@dataclass
class ServerDeployResult:
    """
    单台服务器部署结果
    """
    server_host: str
    server_port: int
    success: bool
    status: DeployStatus
    steps: List[StepExecutionResult] = field(default_factory=list)
    error_message: Optional[str] = None
    rollback_performed: bool = False
    rollback_success: Optional[bool] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class DeployResult:
    """
    整体部署结果
    """
    deploy_id: str
    env_name: str
    status: DeployStatus
    success: bool
    trigger_time: str
    end_time: Optional[str] = None
    total_duration: Optional[float] = None
    server_results: List[ServerDeployResult] = field(default_factory=list)
    build_result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    rollback_available: bool = False
    rollback_performed: bool = False
    summary: Dict[str, Any] = field(default_factory=dict)
