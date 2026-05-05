from typing import Dict, List, Optional, Any, Tuple
from datetime import datetime
import threading
import time
import json
import copy
import statistics

from .models import (
    Deployment,
    generate_id,
    DeploymentHealthConfig
)
from .model_manager import model_manager
from .version_manager import version_manager
from .inference_service import inference_service
from ..storage import metadata_store, file_store


@dataclass
class LatencyCheckResult:
    passed: bool
    message: str
    measured_latency_ms: float
    expected_threshold_ms: float
    max_acceptable_ms: float
    latency_samples: List[float]
    avg_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float

    def to_dict(self) -> Dict:
        return {
            "passed": self.passed,
            "message": self.message,
            "measured_latency_ms": self.measured_latency_ms,
            "expected_threshold_ms": self.expected_threshold_ms,
            "max_acceptable_ms": self.max_acceptable_ms,
            "latency_samples": self.latency_samples,
            "avg_latency_ms": self.avg_latency_ms,
            "p95_latency_ms": self.p95_latency_ms,
            "p99_latency_ms": self.p99_latency_ms
        }


class HealthCheckResult:
    def __init__(
        self,
        healthy: bool,
        message: str = "",
        inference_result: Optional[Dict] = None,
        inference_time_ms: float = 0.0
    ):
        self.healthy = healthy
        self.message = message
        self.inference_result = inference_result
        self.inference_time_ms = inference_time_ms
        self.timestamp = time.time()
        self.latency_check_result: Optional[LatencyCheckResult] = None
        self.inference_attempts: int = 0
        self.successful_inferences: int = 0

    def to_dict(self) -> Dict:
        result = {
            "healthy": self.healthy,
            "message": self.message,
            "inference_result": self.inference_result,
            "inference_time_ms": self.inference_time_ms,
            "timestamp": self.timestamp,
            "inference_attempts": self.inference_attempts,
            "successful_inferences": self.successful_inferences
        }
        if self.latency_check_result:
            result["latency_check"] = self.latency_check_result.to_dict()
        return result


class RollbackInfo:
    def __init__(
        self,
        rollback_available: bool,
        target_version: Optional[str] = None,
        target_version_id: Optional[str] = None,
        message: str = ""
    ):
        self.rollback_available = rollback_available
        self.target_version = target_version
        self.target_version_id = target_version_id
        self.message = message
        self.timestamp = time.time()

    def to_dict(self) -> Dict:
        return {
            "rollback_available": self.rollback_available,
            "target_version": self.target_version,
            "target_version_id": self.target_version_id,
            "message": self.message,
            "timestamp": self.timestamp
        }


class DeploymentHealthChecker:
    def __init__(
        self,
        timeout_seconds: float = 30.0,
        retry_count: int = 3,
        latency_sample_count: int = 5
    ):
        self._timeout_seconds = timeout_seconds
        self._retry_count = retry_count
        self._latency_sample_count = latency_sample_count
        self._health_check_test_inputs = [
            [0.1, 0.2, 0.3, 0.4, 0.5],
            {"test": "health_check", "value": 1.0},
            "0.1,0.2,0.3,0.4,0.5,0.6"
        ]

    def _generate_test_input(self, model_type: str = "classification") -> Any:
        if model_type == "classification":
            return self._health_check_test_inputs[0]
        elif model_type == "detection":
            return self._health_check_test_inputs[1]
        else:
            return self._health_check_test_inputs[2]

    def _get_model_latency_thresholds(self, model_id: str) -> Tuple[float, float]:
        model = model_manager.get_model(model_id)
        if model:
            if hasattr(model, 'expected_latency_ms'):
                expected_ms = model.expected_latency_ms
                max_acceptable_ms = expected_ms * 4.0
                return expected_ms, max_acceptable_ms

        return 500.0, 2000.0

    def _calculate_percentiles(self, samples: List[float]) -> Tuple[float, float, float]:
        if not samples:
            return 0.0, 0.0, 0.0

        sorted_samples = sorted(samples)
        n = len(sorted_samples)

        p50_idx = int(n * 0.5)
        p95_idx = int(n * 0.95)
        p99_idx = int(n * 0.99)

        p50_idx = min(p50_idx, n - 1)
        p95_idx = min(p95_idx, n - 1)
        p99_idx = min(p99_idx, n - 1)

        return (
            sorted_samples[p50_idx],
            sorted_samples[p95_idx],
            sorted_samples[p99_idx]
        )

    def perform_latency_check(
        self,
        model_id: str,
        version: str,
        model_type: str = "classification",
        sample_count: Optional[int] = None,
        expected_threshold_ms: Optional[float] = None,
        max_acceptable_ms: Optional[float] = None
    ) -> LatencyCheckResult:
        if sample_count is None:
            sample_count = self._latency_sample_count

        if expected_threshold_ms is None or max_acceptable_ms is None:
            default_expected, default_max = self._get_model_latency_thresholds(model_id)
            if expected_threshold_ms is None:
                expected_threshold_ms = default_expected
            if max_acceptable_ms is None:
                max_acceptable_ms = default_max

        test_input = self._generate_test_input(model_type)
        latency_samples: List[float] = []

        for i in range(sample_count):
            try:
                start_time = time.time()
                result = inference_service.execute_inference(
                    model_id=model_id,
                    version=version,
                    input_data=test_input,
                    use_batching=False
                )
                end_time = time.time()

                if result.get("success"):
                    latency_ms = result.get("inference_time_ms", (end_time - start_time) * 1000)
                    latency_samples.append(latency_ms)
                else:
                    print(f"Latency check sample {i + 1} failed: {result.get('error')}")

            except Exception as e:
                print(f"Latency check sample {i + 1} error: {e}")

        if not latency_samples:
            return LatencyCheckResult(
                passed=False,
                message="All latency check samples failed to execute",
                measured_latency_ms=0.0,
                expected_threshold_ms=expected_threshold_ms,
                max_acceptable_ms=max_acceptable_ms,
                latency_samples=[],
                avg_latency_ms=0.0,
                p95_latency_ms=0.0,
                p99_latency_ms=0.0
            )

        avg_latency_ms = statistics.mean(latency_samples)
        p50_ms, p95_ms, p99_ms = self._calculate_percentiles(latency_samples)

        passed = True
        message = "Latency check passed"

        if p95_ms > max_acceptable_ms:
            passed = False
            message = f"Latency check failed: P95 latency ({p95_ms:.2f}ms) exceeds maximum acceptable ({max_acceptable_ms:.2f}ms)"
        elif p95_ms > expected_threshold_ms:
            passed = False
            message = f"Latency check failed: P95 latency ({p95_ms:.2f}ms) exceeds expected threshold ({expected_threshold_ms:.2f}ms)"
        elif avg_latency_ms > expected_threshold_ms:
            message = f"Latency check passed with warning: Average latency ({avg_latency_ms:.2f}ms) slightly above expected ({expected_threshold_ms:.2f}ms)"

        return LatencyCheckResult(
            passed=passed,
            message=message,
            measured_latency_ms=p95_ms,
            expected_threshold_ms=expected_threshold_ms,
            max_acceptable_ms=max_acceptable_ms,
            latency_samples=latency_samples,
            avg_latency_ms=avg_latency_ms,
            p95_latency_ms=p95_ms,
            p99_latency_ms=p99_ms
        )

    def perform_health_check(
        self,
        model_id: str,
        version: str,
        model_type: str = "classification",
        timeout_seconds: Optional[float] = None,
        health_config: Optional[DeploymentHealthConfig] = None
    ) -> HealthCheckResult:
        timeout = timeout_seconds if timeout_seconds else self._timeout_seconds
        test_input = self._generate_test_input(model_type)

        if health_config is None:
            health_config = DeploymentHealthConfig.get_default()

        start_time = time.time()
        last_error = ""
        result = HealthCheckResult(
            healthy=False,
            message="Health check not completed"
        )

        for attempt in range(self._retry_count):
            result.inference_attempts += 1

            try:
                elapsed = time.time() - start_time
                if elapsed >= timeout:
                    result.message = f"Health check timed out after {timeout} seconds"
                    return result

                inference_start = time.time()
                inference_result = inference_service.execute_inference(
                    model_id=model_id,
                    version=version,
                    input_data=test_input,
                    use_batching=False
                )
                inference_time_ms = (time.time() - inference_start) * 1000

                if inference_result.get("success"):
                    result.successful_inferences += 1
                    result.healthy = True
                    result.inference_result = inference_result.get("result")
                    result.inference_time_ms = inference_result.get("inference_time_ms", inference_time_ms)
                    result.message = f"Health check passed on attempt {attempt + 1}"

                    if health_config.enable_health_check:
                        latency_result = self.perform_latency_check(
                            model_id=model_id,
                            version=version,
                            model_type=model_type,
                            sample_count=3,
                            expected_threshold_ms=health_config.expected_latency_threshold_ms,
                            max_acceptable_ms=health_config.max_acceptable_latency_ms
                        )
                        result.latency_check_result = latency_result

                        if not latency_result.passed:
                            result.healthy = False
                            result.message = latency_result.message

                    return result
                else:
                    last_error = inference_result.get("error", "Unknown error")

            except Exception as e:
                last_error = str(e)

            remaining = timeout - (time.time() - start_time)
            if remaining > 0 and attempt < self._retry_count - 1:
                wait_time = min(1.0, remaining / 2)
                time.sleep(wait_time)

        result.message = f"Health check failed after {self._retry_count} attempts. Last error: {last_error}"
        return result


class DeploymentRollbackManager:
    def __init__(self):
        self._lock = threading.Lock()

    def get_previous_healthy_version(self, model_id: str, current_version: str) -> RollbackInfo:
        versions = version_manager.get_model_versions(model_id)

        if len(versions) <= 1:
            return RollbackInfo(
                rollback_available=False,
                message="No previous version available for rollback"
            )

        running_deployments = deployment_manager.get_running_deployments(model_id)

        for deployment in running_deployments:
            if deployment.version != current_version and deployment.deploy_status == "running":
                return RollbackInfo(
                    rollback_available=True,
                    target_version=deployment.version,
                    target_version_id=deployment.version_id,
                    message=f"Found healthy running deployment with version {deployment.version}"
                )

        sorted_versions = sorted(
            versions,
            key=lambda v: v.created_at,
            reverse=True
        )

        for version in sorted_versions:
            if version.version != current_version:
                return RollbackInfo(
                    rollback_available=True,
                    target_version=version.version,
                    target_version_id=version.version_id,
                    message=f"Found previous version {version.version} for rollback"
                )

        return RollbackInfo(
            rollback_available=False,
            message="No suitable previous version found for rollback"
        )

    def execute_rollback(
        self,
        model_id: str,
        failed_deploy_id: str,
        target_version: str
    ) -> Tuple[bool, str]:
        failed_deployment = deployment_manager.get_deployment(failed_deploy_id)
        if not failed_deployment:
            return False, f"Failed deployment {failed_deploy_id} not found"

        metadata_store.update(
            "deployments",
            failed_deploy_id,
            {
                "deploy_status": "failed",
                "rollback_triggered": True,
                "rollback_target_version": target_version,
                "rollback_time": datetime.utcnow().isoformat() + "Z"
            }
        )

        try:
            inference_service.unload_engine(model_id, failed_deployment.version)
        except Exception as e:
            print(f"Warning: Error unloading failed version engine: {e}")

        existing_deployments = deployment_manager.get_model_deployments(model_id)
        target_deployment = None

        for dep in existing_deployments:
            if dep.version == target_version:
                target_deployment = dep
                break

        if target_deployment:
            try:
                engine = inference_service.load_engine(model_id, target_version)
                if engine:
                    metadata_store.update(
                        "deployments",
                        target_deployment.deploy_id,
                        {
                            "deploy_status": "running",
                            "rollback_restored": True,
                            "restored_time": datetime.utcnow().isoformat() + "Z"
                        }
                    )
                    model_manager.update_status(model_id, "deployed")
                    return True, f"Rollback to version {target_version} successful"
            except Exception as e:
                return False, f"Error restoring target version: {e}"

        new_deployment = deployment_manager.create_deployment(
            model_id=model_id,
            version=target_version,
            replicas=failed_deployment.replicas
        )

        if new_deployment:
            return True, f"Rollback successful, new deployment {new_deployment.deploy_id} created"

        return False, f"Failed to create rollback deployment for version {target_version}"


class DeploymentManager:
    def __init__(self):
        self.collection = "deployments"
        self._lock = threading.Lock()
        self._base_port = 8000
        self._next_port = 8000

        self._health_checker = DeploymentHealthChecker(
            timeout_seconds=30.0,
            retry_count=3,
            latency_sample_count=5
        )
        self._rollback_manager = DeploymentRollbackManager()

        self._enable_health_check = True
        self._enable_auto_rollback = True
        self._enable_latency_check = True

    def _get_next_port(self) -> int:
        with self._lock:
            port = self._next_port
            self._next_port += 1
            return port

    def _generate_service_url(self, model_id: str, version: str, port: int) -> str:
        return f"http://localhost:{port}/api/v1/inference/{model_id}"

    def _get_health_config_for_deployment(
        self,
        model_id: str,
        custom_config: Optional[DeploymentHealthConfig] = None
    ) -> DeploymentHealthConfig:
        if custom_config:
            return custom_config

        model = model_manager.get_model(model_id)
        if model and hasattr(model, 'expected_latency_ms'):
            config = DeploymentHealthConfig()
            config.expected_latency_threshold_ms = model.expected_latency_ms
            config.max_acceptable_latency_ms = model.expected_latency_ms * 4.0
            return config

        return DeploymentHealthConfig.get_default()

    def create_deployment(
        self,
        model_id: str,
        version: str,
        replicas: int = 1,
        custom_port: Optional[int] = None,
        enable_health_check: Optional[bool] = None,
        enable_auto_rollback: Optional[bool] = None,
        enable_latency_check: Optional[bool] = None,
        health_config: Optional[DeploymentHealthConfig] = None
    ) -> Optional[Deployment]:
        do_health_check = enable_health_check if enable_health_check is not None else self._enable_health_check
        do_rollback = enable_auto_rollback if enable_auto_rollback is not None else self._enable_auto_rollback
        do_latency_check = enable_latency_check if enable_latency_check is not None else self._enable_latency_check

        effective_health_config = self._get_health_config_for_deployment(model_id, health_config)

        model = model_manager.get_model(model_id)
        if not model:
            print(f"Model {model_id} does not exist")
            return None

        version_info = version_manager.get_version_by_model_and_version(model_id, version)
        if not version_info:
            print(f"Version {version} not found for model {model_id}")
            return None

        model_file_path = file_store.get_model_file_path(
            model_id, version, version_info.model_file
        )
        if not model_file_path:
            print(f"Model file not found for version {version}")
            return None

        if not file_store.verify_file(model_id, version, version_info.model_file, version_info.checksum):
            print(f"Model file checksum verification failed")
            return None

        deploy_id = generate_id("deploy")
        port = custom_port if custom_port else self._get_next_port()
        service_url = self._generate_service_url(model_id, version, port)

        deployment = Deployment(
            deploy_id=deploy_id,
            model_id=model_id,
            version_id=version_info.version_id,
            version=version,
            service_url=service_url,
            deploy_status="pending",
            replicas=replicas,
            port=port
        )

        deployment_dict = deployment.to_dict()
        deployment_dict["health_check_enabled"] = do_health_check
        deployment_dict["latency_check_enabled"] = do_latency_check
        deployment_dict["auto_rollback_enabled"] = do_rollback
        deployment_dict["health_config"] = effective_health_config.to_dict()

        if metadata_store.save(self.collection, deploy_id, deployment_dict):
            return self._start_deployment_with_health_check(
                deploy_id,
                model_id,
                version,
                do_health_check,
                do_latency_check,
                do_rollback,
                effective_health_config
            )
        return None

    def _start_deployment_with_health_check(
        self,
        deploy_id: str,
        model_id: str,
        version: str,
        do_health_check: bool,
        do_latency_check: bool,
        do_rollback: bool,
        health_config: DeploymentHealthConfig
    ) -> Optional[Deployment]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return None

        metadata_store.update(
            self.collection,
            deploy_id,
            {"deploy_status": "starting"}
        )

        try:
            engine = inference_service.load_engine(model_id, version)

            if not engine:
                metadata_store.update(
                    self.collection,
                    deploy_id,
                    {"deploy_status": "failed", "failure_reason": "Engine load failed"}
                )
                return None

            metadata_store.update(
                self.collection,
                deploy_id,
                {"deploy_status": "health_checking"}
            )

            if do_health_check:
                model = model_manager.get_model(model_id)
                model_type = model.model_type if model else "classification"

                health_result = self._health_checker.perform_health_check(
                    model_id=model_id,
                    version=version,
                    model_type=model_type,
                    health_config=health_config
                )

                metadata_store.update(
                    self.collection,
                    deploy_id,
                    {
                        "health_check_result": health_result.to_dict(),
                        "health_check_time": datetime.utcnow().isoformat() + "Z"
                    }
                )

                if not health_result.healthy:
                    failure_reason = "Health check failed"
                    if health_result.latency_check_result and not health_result.latency_check_result.passed:
                        failure_reason = f"Latency check failed: {health_result.latency_check_result.message}"

                    metadata_store.update(
                        self.collection,
                        deploy_id,
                        {
                            "deploy_status": "failed",
                            "failure_reason": failure_reason,
                            "latency_check_passed": health_result.latency_check_result.passed if health_result.latency_check_result else None
                        }
                    )

                    inference_service.unload_engine(model_id, version)

                    if do_rollback:
                        rollback_info = self._rollback_manager.get_previous_healthy_version(model_id, version)
                        metadata_store.update(
                            self.collection,
                            deploy_id,
                            {"rollback_info": rollback_info.to_dict()}
                        )

                        if rollback_info.rollback_available and rollback_info.target_version:
                            success, message = self._rollback_manager.execute_rollback(
                                model_id,
                                deploy_id,
                                rollback_info.target_version
                            )
                            metadata_store.update(
                                self.collection,
                                deploy_id,
                                {
                                    "rollback_executed": success,
                                    "rollback_message": message
                                }
                            )

                    return None

            metadata_store.update(
                self.collection,
                deploy_id,
                {
                    "deploy_status": "running",
                    "latency_check_passed": True
                }
            )

            model_manager.update_status(model_id, "deployed")

            deployment.deploy_status = "running"
            return deployment

        except Exception as e:
            print(f"Error starting deployment: {e}")
            metadata_store.update(
                self.collection,
                deploy_id,
                {"deploy_status": "failed", "failure_reason": str(e)}
            )
            return None

    def _start_deployment(self, deploy_id: str) -> Optional[Deployment]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return None

        metadata_store.update(
            self.collection,
            deploy_id,
            {"deploy_status": "starting"}
        )

        try:
            engine = inference_service.load_engine(
                deployment.model_id,
                deployment.version
            )

            if not engine:
                metadata_store.update(
                    self.collection,
                    deploy_id,
                    {"deploy_status": "failed"}
                )
                return None

            metadata_store.update(
                self.collection,
                deploy_id,
                {"deploy_status": "running"}
            )

            model_manager.update_status(deployment.model_id, "deployed")

            deployment.deploy_status = "running"
            return deployment

        except Exception as e:
            print(f"Error starting deployment: {e}")
            metadata_store.update(
                self.collection,
                deploy_id,
                {"deploy_status": "failed"}
            )
            return None

    def get_deployment(self, deploy_id: str) -> Optional[Deployment]:
        data = metadata_store.load(self.collection, deploy_id)
        if data:
            return Deployment.from_dict(data)
        return None

    def get_deployment_details(self, deploy_id: str) -> Optional[Dict[str, Any]]:
        data = metadata_store.load(self.collection, deploy_id)
        return data

    def get_model_deployments(self, model_id: str) -> List[Deployment]:
        deployments_data = metadata_store.list_by_field(self.collection, "model_id", model_id)
        return [Deployment.from_dict(d) for d in deployments_data]

    def get_running_deployments(self, model_id: Optional[str] = None) -> List[Deployment]:
        if model_id:
            deployments = self.get_model_deployments(model_id)
        else:
            deployments = self.list_all_deployments()

        return [d for d in deployments if d.deploy_status == "running"]

    def perform_runtime_health_check(self, deploy_id: str) -> Optional[HealthCheckResult]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return None

        model = model_manager.get_model(deployment.model_id)
        model_type = model.model_type if model else "classification"

        details = self.get_deployment_details(deploy_id)
        health_config_data = details.get("health_config") if details else None
        health_config = None
        if health_config_data:
            health_config = DeploymentHealthConfig.from_dict(health_config_data)

        return self._health_checker.perform_health_check(
            model_id=deployment.model_id,
            version=deployment.version,
            model_type=model_type,
            health_config=health_config
        )

    def perform_runtime_latency_check(
        self,
        deploy_id: str,
        sample_count: int = 5
    ) -> Optional[LatencyCheckResult]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return None

        model = model_manager.get_model(deployment.model_id)
        model_type = model.model_type if model else "classification"

        return self._health_checker.perform_latency_check(
            model_id=deployment.model_id,
            version=deployment.version,
            model_type=model_type,
            sample_count=sample_count
        )

    def stop_deployment(self, deploy_id: str) -> bool:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return False

        try:
            inference_service.unload_engine(deployment.model_id, deployment.version)

            metadata_store.update(
                self.collection,
                deploy_id,
                {
                    "deploy_status": "stopped",
                    "stopped_time": datetime.utcnow().isoformat() + "Z"
                }
            )

            running_deployments = self.get_running_deployments(deployment.model_id)
            if not running_deployments:
                model_manager.update_status(deployment.model_id, "ready")

            return True
        except Exception as e:
            print(f"Error stopping deployment: {e}")
            return False

    def restart_deployment(self, deploy_id: str) -> Optional[Deployment]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return None

        details = self.get_deployment_details(deploy_id)
        do_health_check = details.get("health_check_enabled", self._enable_health_check) if details else self._enable_health_check
        do_rollback = details.get("auto_rollback_enabled", self._enable_auto_rollback) if details else self._enable_auto_rollback
        do_latency_check = details.get("latency_check_enabled", self._enable_latency_check) if details else self._enable_latency_check

        health_config_data = details.get("health_config") if details else None
        health_config = None
        if health_config_data:
            health_config = DeploymentHealthConfig.from_dict(health_config_data)

        self.stop_deployment(deploy_id)

        time.sleep(0.5)

        return self._start_deployment_with_health_check(
            deploy_id,
            deployment.model_id,
            deployment.version,
            do_health_check,
            do_latency_check,
            do_rollback,
            health_config if health_config else DeploymentHealthConfig.get_default()
        )

    def delete_deployment(self, deploy_id: str) -> bool:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return False

        if deployment.deploy_status == "running":
            self.stop_deployment(deploy_id)

        return metadata_store.delete(self.collection, deploy_id)

    def list_all_deployments(self) -> List[Deployment]:
        deployments_data = metadata_store.list_all(self.collection)
        return [Deployment.from_dict(d) for d in deployments_data]

    def update_deployment(self, deploy_id: str, updates: Dict) -> Optional[Deployment]:
        allowed_fields = ["replicas", "container_id"]
        filtered_updates = {k: v for k, v in updates.items() if k in allowed_fields}

        if not filtered_updates:
            return self.get_deployment(deploy_id)

        updated = metadata_store.update(self.collection, deploy_id, filtered_updates)
        if updated:
            return Deployment.from_dict(updated)
        return None

    def get_deployment_health(self, deploy_id: str) -> Dict[str, Any]:
        deployment = self.get_deployment(deploy_id)
        if not deployment:
            return {
                "health": "not_found",
                "message": "Deployment not found"
            }

        is_loaded = inference_service.is_model_loaded(
            deployment.model_id,
            deployment.version
        )

        runtime_health = None
        if deployment.deploy_status == "running":
            try:
                runtime_result = self.perform_runtime_health_check(deploy_id)
                if runtime_result:
                    runtime_health = runtime_result.to_dict()
            except Exception:
                pass

        details = self.get_deployment_details(deploy_id)

        if deployment.deploy_status == "running" and is_loaded:
            health_status = "healthy"
            if runtime_health and not runtime_health.get("healthy"):
                health_status = "degraded"

            return {
                "health": health_status,
                "status": deployment.deploy_status,
                "model_loaded": True,
                "service_url": deployment.service_url,
                "runtime_health_check": runtime_health,
                "health_check_enabled": details.get("health_check_enabled") if details else None,
                "latency_check_enabled": details.get("latency_check_enabled") if details else None,
                "auto_rollback_enabled": details.get("auto_rollback_enabled") if details else None,
                "latency_check_passed": details.get("latency_check_passed") if details else None
            }
        elif deployment.deploy_status == "running" and not is_loaded:
            return {
                "health": "unhealthy",
                "status": deployment.deploy_status,
                "model_loaded": False,
                "message": "Model not loaded despite running status",
                "runtime_health_check": runtime_health
            }
        else:
            return {
                "health": "inactive",
                "status": deployment.deploy_status,
                "model_loaded": is_loaded,
                "health_check_result": details.get("health_check_result") if details else None
            }

    def deploy_model(
        self,
        model_id: str,
        version: Optional[str] = None,
        replicas: int = 1,
        enable_health_check: bool = True,
        enable_latency_check: bool = True,
        enable_auto_rollback: bool = True
    ) -> Optional[Deployment]:
        if version is None:
            model = model_manager.get_model(model_id)
            if not model:
                return None
            if not model.current_version:
                print(f"No current version set for model {model_id}")
                return None
            version = model.current_version

        return self.create_deployment(
            model_id,
            version,
            replicas,
            enable_health_check=enable_health_check,
            enable_latency_check=enable_latency_check,
            enable_auto_rollback=enable_auto_rollback
        )

    def configure_health_check(
        self,
        enabled: bool,
        timeout_seconds: float = 30.0,
        retry_count: int = 3,
        latency_sample_count: int = 5
    ):
        self._enable_health_check = enabled
        self._health_checker = DeploymentHealthChecker(
            timeout_seconds=timeout_seconds,
            retry_count=retry_count,
            latency_sample_count=latency_sample_count
        )

    def configure_auto_rollback(self, enabled: bool):
        self._enable_auto_rollback = enabled

    def configure_latency_check(self, enabled: bool):
        self._enable_latency_check = enabled


deployment_manager = DeploymentManager()
