from datetime import datetime
from dataclasses import dataclass, field
from typing import Dict, Optional, List, Any
import json
import uuid


@dataclass
class BatchingConfig:
    enable_batching: bool = True
    batch_timeout_ms: float = 100.0
    max_batch_size: int = 32
    max_queue_size: int = 10000

    def to_dict(self) -> Dict:
        return {
            "enable_batching": self.enable_batching,
            "batch_timeout_ms": self.batch_timeout_ms,
            "max_batch_size": self.max_batch_size,
            "max_queue_size": self.max_queue_size
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "BatchingConfig":
        return cls(
            enable_batching=data.get("enable_batching", True),
            batch_timeout_ms=data.get("batch_timeout_ms", 100.0),
            max_batch_size=data.get("max_batch_size", 32),
            max_queue_size=data.get("max_queue_size", 10000)
        )

    @classmethod
    def get_default_for_model_type(cls, model_type: str) -> "BatchingConfig":
        configs = {
            "classification": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=100.0,
                max_batch_size=32
            ),
            "detection": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=200.0,
                max_batch_size=16
            ),
            "segmentation": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=300.0,
                max_batch_size=8
            ),
            "text": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=50.0,
                max_batch_size=64
            ),
            "regression": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=50.0,
                max_batch_size=128
            ),
            "other": BatchingConfig(
                enable_batching=True,
                batch_timeout_ms=100.0,
                max_batch_size=32
            )
        }
        return configs.get(model_type, configs["other"])


@dataclass
class DeploymentHealthConfig:
    enable_health_check: bool = True
    health_check_timeout_ms: float = 30000.0
    health_check_retry_count: int = 3
    expected_latency_threshold_ms: float = 500.0
    max_acceptable_latency_ms: float = 2000.0
    enable_auto_rollback: bool = True

    def to_dict(self) -> Dict:
        return {
            "enable_health_check": self.enable_health_check,
            "health_check_timeout_ms": self.health_check_timeout_ms,
            "health_check_retry_count": self.health_check_retry_count,
            "expected_latency_threshold_ms": self.expected_latency_threshold_ms,
            "max_acceptable_latency_ms": self.max_acceptable_latency_ms,
            "enable_auto_rollback": self.enable_auto_rollback
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "DeploymentHealthConfig":
        return cls(
            enable_health_check=data.get("enable_health_check", True),
            health_check_timeout_ms=data.get("health_check_timeout_ms", 30000.0),
            health_check_retry_count=data.get("health_check_retry_count", 3),
            expected_latency_threshold_ms=data.get("expected_latency_threshold_ms", 500.0),
            max_acceptable_latency_ms=data.get("max_acceptable_latency_ms", 2000.0),
            enable_auto_rollback=data.get("enable_auto_rollback", True)
        )

    @classmethod
    def get_default(cls) -> "DeploymentHealthConfig":
        return cls()


@dataclass
class VersionDiffReport:
    model_id: str
    version1: str
    version2: str
    generated_at: datetime = field(default_factory=datetime.utcnow)

    file_changes: Dict[str, Any] = field(default_factory=dict)
    param_changes: Dict[str, Any] = field(default_factory=dict)
    performance_changes: Dict[str, Any] = field(default_factory=dict)
    training_param_changes: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "model_id": self.model_id,
            "version1": self.version1,
            "version2": self.version2,
            "generated_at": self.generated_at.isoformat() + "Z",
            "file_changes": self.file_changes,
            "param_changes": self.param_changes,
            "performance_changes": self.performance_changes,
            "training_param_changes": self.training_param_changes
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "VersionDiffReport":
        generated_at = datetime.fromisoformat(data["generated_at"].replace("Z", ""))
        return cls(
            model_id=data["model_id"],
            version1=data["version1"],
            version2=data["version2"],
            generated_at=generated_at,
            file_changes=data.get("file_changes", {}),
            param_changes=data.get("param_changes", {}),
            performance_changes=data.get("performance_changes", {}),
            training_param_changes=data.get("training_param_changes", {})
        )


@dataclass
class Model:
    model_id: str
    model_name: str
    model_type: str
    framework: str
    created_at: datetime = field(default_factory=datetime.utcnow)
    current_version: str = ""
    status: str = "draft"
    tags: List[str] = field(default_factory=list)
    description: str = ""

    batching_config: BatchingConfig = field(default_factory=BatchingConfig)
    expected_latency_ms: float = 100.0

    def to_dict(self) -> Dict:
        return {
            "model_id": self.model_id,
            "model_name": self.model_name,
            "model_type": self.model_type,
            "framework": self.framework,
            "created_at": self.created_at.isoformat() + "Z",
            "current_version": self.current_version,
            "status": self.status,
            "tags": self.tags,
            "description": self.description,
            "batching_config": self.batching_config.to_dict(),
            "expected_latency_ms": self.expected_latency_ms
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "Model":
        created_at = datetime.fromisoformat(data["created_at"].replace("Z", ""))
        batching_config_data = data.get("batching_config", {})
        if isinstance(batching_config_data, dict):
            batching_config = BatchingConfig.from_dict(batching_config_data)
        else:
            batching_config = BatchingConfig()

        return cls(
            model_id=data["model_id"],
            model_name=data["model_name"],
            model_type=data["model_type"],
            framework=data["framework"],
            created_at=created_at,
            current_version=data.get("current_version", ""),
            status=data.get("status", "draft"),
            tags=data.get("tags", []),
            description=data.get("description", ""),
            batching_config=batching_config,
            expected_latency_ms=data.get("expected_latency_ms", 100.0)
        )


@dataclass
class ModelVersion:
    version_id: str
    model_id: str
    version: str
    model_file: str
    model_size: int
    training_params: Dict = field(default_factory=dict)
    accuracy: Optional[float] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    checksum: str = ""
    notes: str = ""

    additional_files: List[Dict] = field(default_factory=list)
    model_metrics: Dict = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "version_id": self.version_id,
            "model_id": self.model_id,
            "version": self.version,
            "model_file": self.model_file,
            "model_size": self.model_size,
            "training_params": self.training_params,
            "accuracy": self.accuracy,
            "created_at": self.created_at.isoformat() + "Z",
            "checksum": self.checksum,
            "notes": self.notes,
            "additional_files": self.additional_files,
            "model_metrics": self.model_metrics
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "ModelVersion":
        created_at = datetime.fromisoformat(data["created_at"].replace("Z", ""))
        return cls(
            version_id=data["version_id"],
            model_id=data["model_id"],
            version=data["version"],
            model_file=data["model_file"],
            model_size=data["model_size"],
            training_params=data.get("training_params", {}),
            accuracy=data.get("accuracy"),
            created_at=created_at,
            checksum=data.get("checksum", ""),
            notes=data.get("notes", ""),
            additional_files=data.get("additional_files", []),
            model_metrics=data.get("model_metrics", {})
        )


@dataclass
class Deployment:
    deploy_id: str
    model_id: str
    version_id: str
    version: str
    service_url: str
    deploy_status: str = "pending"
    deploy_time: datetime = field(default_factory=datetime.utcnow)
    replicas: int = 1
    port: int = 0
    container_id: str = ""

    health_config: DeploymentHealthConfig = field(default_factory=DeploymentHealthConfig.get_default)
    health_check_result: Optional[Dict] = None
    latency_check_passed: Optional[bool] = None

    def to_dict(self) -> Dict:
        result = {
            "deploy_id": self.deploy_id,
            "model_id": self.model_id,
            "version_id": self.version_id,
            "version": self.version,
            "service_url": self.service_url,
            "deploy_status": self.deploy_status,
            "deploy_time": self.deploy_time.isoformat() + "Z",
            "replicas": self.replicas,
            "port": self.port,
            "container_id": self.container_id,
            "health_config": self.health_config.to_dict(),
            "health_check_result": self.health_check_result,
            "latency_check_passed": self.latency_check_passed
        }
        return result

    @classmethod
    def from_dict(cls, data: Dict) -> "Deployment":
        deploy_time = datetime.fromisoformat(data["deploy_time"].replace("Z", ""))
        health_config_data = data.get("health_config", {})
        if isinstance(health_config_data, dict):
            health_config = DeploymentHealthConfig.from_dict(health_config_data)
        else:
            health_config = DeploymentHealthConfig.get_default()

        return cls(
            deploy_id=data["deploy_id"],
            model_id=data["model_id"],
            version_id=data["version_id"],
            version=data["version"],
            service_url=data["service_url"],
            deploy_status=data.get("deploy_status", "pending"),
            deploy_time=deploy_time,
            replicas=data.get("replicas", 1),
            port=data.get("port", 0),
            container_id=data.get("container_id", ""),
            health_config=health_config,
            health_check_result=data.get("health_check_result"),
            latency_check_passed=data.get("latency_check_passed")
        )


@dataclass
class InferenceRequest:
    request_id: str
    model_id: str
    input_data: str
    result: Optional[Dict] = None
    inference_time: float = 0.0
    request_time: datetime = field(default_factory=datetime.utcnow)
    status: str = "pending"
    error_message: str = ""

    def to_dict(self) -> Dict:
        return {
            "request_id": self.request_id,
            "model_id": self.model_id,
            "input_data": self.input_data,
            "result": self.result,
            "inference_time": self.inference_time,
            "request_time": self.request_time.isoformat() + "Z",
            "status": self.status,
            "error_message": self.error_message
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "InferenceRequest":
        request_time = datetime.fromisoformat(data["request_time"].replace("Z", ""))
        return cls(
            request_id=data["request_id"],
            model_id=data["model_id"],
            input_data=data["input_data"],
            result=data.get("result"),
            inference_time=data.get("inference_time", 0.0),
            request_time=request_time,
            status=data.get("status", "pending"),
            error_message=data.get("error_message", "")
        )


@dataclass
class PerformanceStats:
    stat_id: str
    model_id: str
    stat_date: str
    request_count: int = 0
    avg_latency: float = 0.0
    max_latency: float = 0.0
    min_latency: float = 0.0
    throughput: float = 0.0
    error_count: int = 0
    total_latency: float = 0.0

    p50_latency: float = 0.0
    p95_latency: float = 0.0
    p99_latency: float = 0.0
    latency_percentiles: List[float] = field(default_factory=list)

    def to_dict(self) -> Dict:
        return {
            "stat_id": self.stat_id,
            "model_id": self.model_id,
            "stat_date": self.stat_date,
            "request_count": self.request_count,
            "avg_latency": self.avg_latency,
            "max_latency": self.max_latency,
            "min_latency": self.min_latency,
            "throughput": self.throughput,
            "error_count": self.error_count,
            "p50_latency": self.p50_latency,
            "p95_latency": self.p95_latency,
            "p99_latency": self.p99_latency
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "PerformanceStats":
        return cls(
            stat_id=data["stat_id"],
            model_id=data["model_id"],
            stat_date=data["stat_date"],
            request_count=data.get("request_count", 0),
            avg_latency=data.get("avg_latency", 0.0),
            max_latency=data.get("max_latency", 0.0),
            min_latency=data.get("min_latency", 0.0),
            throughput=data.get("throughput", 0.0),
            error_count=data.get("error_count", 0),
            total_latency=data.get("total_latency", 0.0),
            p50_latency=data.get("p50_latency", 0.0),
            p95_latency=data.get("p95_latency", 0.0),
            p99_latency=data.get("p99_latency", 0.0)
        )

    def add_request(self, latency: float, success: bool = True):
        self.request_count += 1
        self.total_latency += latency
        if success:
            if self.min_latency == 0 or latency < self.min_latency:
                self.min_latency = latency
            if latency > self.max_latency:
                self.max_latency = latency
            self.avg_latency = self.total_latency / self.request_count
            self.latency_percentiles.append(latency)
        else:
            self.error_count += 1

    def calculate_percentiles(self):
        if not self.latency_percentiles:
            sorted_latencies = sorted(self.latency_percentiles)
            n = len(sorted_latencies)
            self.p50_latency = sorted_latencies[int(n * 0.5)] if n > 0 else 0.0
            self.p95_latency = sorted_latencies[int(n * 0.95)] if n > 0 else 0.0
            self.p99_latency = sorted_latencies[int(n * 0.99)] if n > 0 else 0.0


@dataclass
class TrainingRecord:
    training_id: str
    model_id: str
    version_id: str
    training_params: Dict = field(default_factory=dict)
    training_metrics: Dict = field(default_factory=dict)
    training_time: float = 0.0
    started_at: datetime = field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    status: str = "running"
    dataset_info: Dict = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "training_id": self.training_id,
            "model_id": self.model_id,
            "version_id": self.version_id,
            "training_params": self.training_params,
            "training_metrics": self.training_metrics,
            "training_time": self.training_time,
            "started_at": self.started_at.isoformat() + "Z",
            "completed_at": self.completed_at.isoformat() + "Z" if self.completed_at else None,
            "status": self.status,
            "dataset_info": self.dataset_info
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "TrainingRecord":
        started_at = datetime.fromisoformat(data["started_at"].replace("Z", ""))
        completed_at = None
        if data.get("completed_at"):
            completed_at = datetime.fromisoformat(data["completed_at"].replace("Z", ""))
        return cls(
            training_id=data["training_id"],
            model_id=data["model_id"],
            version_id=data["version_id"],
            training_params=data.get("training_params", {}),
            training_metrics=data.get("training_metrics", {}),
            training_time=data.get("training_time", 0.0),
            started_at=started_at,
            completed_at=completed_at,
            status=data.get("status", "running"),
            dataset_info=data.get("dataset_info", {})
        )


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"
