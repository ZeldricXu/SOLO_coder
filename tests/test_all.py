import pytest
import asyncio
from datetime import datetime
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from app import (
    settings,
    BaseEntity,
    ConfigEntity,
    RunInstance,
    Snapshot,
    data_access_module,
    storage_module,
    classification_module,
    core_processor,
    dp_module,
    config_module,
    audit_module,
    notification_module,
    mpc_module,
    ResourceRequest,
    BatchOperation,
    DataCategory,
    SensitivityLevel,
    NotificationChannel,
    MPCProtocol,
    NoiseMechanism
)


@pytest.fixture
async def setup_test_env():
    settings.ensure_dirs()
    yield


class TestCoreModels:
    def test_base_entity_creation(self):
        entity = BaseEntity(type="workflow", attributes={"key": "value"})
        assert entity.id.startswith("ent_")
        assert entity.type == "workflow"
        assert entity.attributes["key"] == "value"
        assert entity.created_at is not None

    def test_config_entity_creation(self):
        config = ConfigEntity(namespace="test", parameters={"timeout": 30})
        assert config.config_id.startswith("cfg_")
        assert config.namespace == "test"
        assert config.version == 1

    def test_run_instance_creation(self):
        run = RunInstance(entity_id="ent_001")
        assert run.run_id.startswith("run_")
        assert run.entity_id == "ent_001"
        assert run.progress == 0.0

    def test_snapshot_creation(self):
        snapshot = Snapshot(
            metrics={"throughput": 1500, "latency_p99": 250},
            dimensions={"host": "node-1"}
        )
        assert snapshot.snapshot_id.startswith("snap_")
        assert snapshot.metrics["throughput"] == 1500


class TestDataAccessModule:
    def test_create_resource(self):
        resource = data_access_module.create_resource(
            "workflow",
            {"config": {"timeout": 30}}
        )
        assert resource.id is not None
        assert resource.type == "workflow"

    def test_get_resource(self):
        resource = data_access_module.create_resource("test", {"a": 1})
        retrieved = data_access_module.get_resource(resource.id)
        assert retrieved is not None
        assert retrieved.id == resource.id

    def test_list_resources(self):
        initial = len(data_access_module.list_resources("unique_test_type"))
        data_access_module.create_resource("unique_test_type", {})
        assert len(data_access_module.list_resources("unique_test_type")) == initial + 1

    def test_schema_version_control(self):
        schema = data_access_module.schema_controller
        v1 = schema.create_version({"tables": {"users": {"columns": ["id", "name"]}}}, "Initial schema")
        assert v1.version == 1

        current = schema.get_current_version()
        assert current.version >= 1

        versions = schema.list_versions()
        assert len(versions) >= 1

    def test_create_migration_task(self):
        task = data_access_module.migration_service.create_migration_task(
            "source_db", "target_db", "users", total_records=100
        )
        assert task.task_id.startswith("mig_")
        assert task.table_name == "users"


class TestStorageModule:
    def test_create_backup_record(self):
        test_dir = Path("./data/test_backup_source")
        test_dir.mkdir(parents=True, exist_ok=True)
        test_file = test_dir / "test.txt"
        test_file.write_text("test content")

        try:
            record = storage_module.backup_manager.create_backup(str(test_dir), "test_backup")
            assert record.backup_id.startswith("bak_")
            assert record.name == "test_backup"
        finally:
            if test_file.exists():
                test_file.unlink()
            if test_dir.exists():
                test_dir.rmdir()

    def test_list_backups(self):
        backups = storage_module.backup_manager.list_backups()
        assert isinstance(backups, list)

    def test_create_recovery_task(self):
        backups = storage_module.backup_manager.list_backups()
        if backups:
            task = storage_module.recovery_manager.create_recovery_task(
                backups[0].backup_id, "./data/test_restore"
            )
            assert task.recovery_id.startswith("rec_")


class TestClassificationModule:
    def test_scan_email(self):
        result = classification_module.scanner.scan_value(
            "email", "test@example.com"
        )
        assert result.category in [DataCategory.PII, DataCategory.GENERAL]
        assert result.confidence >= 0

    def test_scan_phone(self):
        result = classification_module.scanner.scan_value(
            "phone", "13800138000"
        )
        assert result.category in [DataCategory.PII, DataCategory.GENERAL]

    def test_classify_record(self):
        record = {
            "email": "user@example.com",
            "phone": "13800138000",
            "name": "张三",
            "order_id": "ORD_001"
        }
        results = classification_module.classify_record(record)
        assert len(results) == 4

    def test_get_data_summary(self):
        dataset = [
            {"email": "a@b.com", "phone": "13800138000", "amount": 100},
            {"email": "c@d.com", "phone": "13900139000", "amount": 200}
        ]
        summary = classification_module.get_data_summary(dataset)
        assert "total_fields" in summary
        assert "high_risk_fields" in summary

    def test_policy_evaluation(self):
        result = classification_module.scanner.scan_value("email", "test@example.com")
        evaluation = classification_module.policy_engine.evaluate_policy(result)
        assert "risk_level" in evaluation
        assert "required_actions" in evaluation


class TestCoreProcessor:
    def test_create_resource(self):
        request = ResourceRequest(type="workflow", config={"timeout": 30})
        response = core_processor.create_resource(request)
        assert response.code == 201
        assert response.data.id.startswith("rsc_")

    def test_get_resource_status_not_found(self):
        response = core_processor.get_resource_status("nonexistent")
        assert response.code == 404

    async def test_batch_operations(self):
        operations = [BatchOperation(action="start", id="test_1")]
        response = await core_processor.execute_batch(operations)
        assert response.code == 200
        assert len(response.data.results) == 1

    async def test_execute_handler_success(self):
        request = {
            "traceId": "test_trace",
            "params": {"key": "value"},
            "namespace": "default",
            "payload": {"data": "test"},
            "op_type": "default"
        }
        response = await core_processor.execute_handler(request)
        assert response.code == 200


class TestDifferentialPrivacyModule:
    def test_create_budget(self):
        budget = dp_module.budget_manager.create_budget(None, 5.0, 1e-5)
        assert budget.budget_id is not None
        assert budget.epsilon == 5.0

    def test_noise_injection_laplace(self):
        original = 100.0
        noisy = dp_module.noise_injector.laplace_mechanism(original, 1.0, 1.0)
        assert noisy != original

    def test_private_count(self):
        budget = dp_module.budget_manager.create_budget("test_count_budget", 10.0)
        data = [1, 2, 3, 4, 5]
        result = dp_module.private_count(data, budget.budget_id, 1.0)
        assert result is not None
        assert 3 <= result <= 7

    def test_private_sum(self):
        budget = dp_module.budget_manager.create_budget("test_sum_budget", 10.0)
        data = [10.0, 20.0, 30.0]
        result = dp_module.private_sum(data, budget.budget_id, 0, 100, 1.0)
        assert result is not None

    def test_budget_consumption(self):
        budget = dp_module.budget_manager.create_budget("consume_test", 5.0)
        initial = budget.remaining_epsilon
        dp_module.private_count([1, 2, 3], budget.budget_id, 1.0)
        assert budget.remaining_epsilon < initial

    def test_privacy_report(self):
        report = dp_module.get_privacy_report()
        assert "global_status" in report
        assert "total_queries" in report


class TestConfigManagementModule:
    def test_get_config(self):
        config = config_module.get("default")
        assert isinstance(config, dict)

    def test_set_config(self):
        config = config_module.set("test_namespace", {"new_key": "new_value"})
        assert config.version >= 1

    def test_list_versions(self):
        versions = config_module.version_manager.list_versions("default")
        assert len(versions) >= 1

    def test_create_and_apply(self):
        config = config_module.create_and_apply(
            "new_test_ns", {"param1": "value1", "param2": 123}
        )
        assert config.status == "active"

    async def test_rollback(self):
        config_module.set("rollback_test", {"v1": True})
        config_module.set("rollback_test", {"v1": True, "v2": True})

        current = config_module.version_manager.get_active_version("rollback_test")
        if current and current > 1:
            rolled_back = config_module.rollback_manager.rollback_to_version(
                "rollback_test", current - 1
            )
            assert rolled_back is not None

    def test_can_rollback(self):
        can_rollback = config_module.rollback_manager.can_rollback("default")
        assert isinstance(can_rollback, bool)


class TestAuditModule:
    def test_log_creation(self):
        entry = audit_module.log(
            "test_user",
            "test_action",
            "test_resource",
            "res_001",
            {"detail": "test"}
        )
        assert entry.log_id.startswith("audit_")
        assert entry.actor == "test_user"

    def test_query_logs(self):
        audit_module.log("query_test", "query_action", "query_resource")
        logs = audit_module.query_logs(action="query_action", limit=10)
        assert len(logs) >= 1

    def test_chain_integrity(self):
        audit_module.log("integrity_test", "check", "resource")
        result = audit_module.verify_integrity()
        assert "valid" in result

    def test_chain_info(self):
        info = audit_module.get_chain_info()
        assert "length" in info
        assert "last_hash" in info

    def test_get_log_proof(self):
        entry = audit_module.log("proof_test", "proof", "resource")
        proof = audit_module.get_log_proof(entry.log_id)
        assert proof is not None
        assert proof["log_id"] == entry.log_id


class TestNotificationModule:
    def test_list_templates(self):
        templates = notification_module.renderer.list_templates()
        assert len(templates) >= 5

    async def test_send_notification(self):
        results = await notification_module.notify_with_custom_content(
            NotificationChannel.IN_APP,
            ["user_001"],
            "Test Subject",
            "Test Body"
        )
        assert len(results) == 1

    def test_template_rendering(self):
        rendered = notification_module.renderer.render(
            "task_complete",
            {
                "task_name": "Test Task",
                "task_id": "task_001",
                "completion_time": "2026-05-13",
                "result": "Success"
            }
        )
        assert "Test Task" in rendered["subject"]

    def test_notification_stats(self):
        stats = notification_module.notifier.get_statistics()
        assert "total" in stats
        assert "by_channel" in stats


class TestMPCModule:
    async def test_secure_sum(self):
        result = await mpc_module.run_secure_sum(
            {
                "p1": 10,
                "p2": 20,
                "p3": 30
            },
            MPCProtocol.SECRET_SHARING
        )
        assert result["status"] == "completed"
        assert result["result"] == 60

    async def test_secure_average(self):
        result = await mpc_module.run_secure_average(
            {
                "p1": 10,
                "p2": 20,
                "p3": 30
            },
            MPCProtocol.HOMOMORPHIC
        )
        assert result["status"] == "completed"
        assert result["result"] == 20.0

    def test_create_session(self):
        session = mpc_module.coordinator.create_session(
            MPCProtocol.SECRET_SHARING,
            "sum",
            ["p1", "p2", "p3"]
        )
        assert session.session_id.startswith("mpc_")
        assert len(session.participants) == 3

    async def test_submit_input(self):
        session = mpc_module.coordinator.create_session(
            MPCProtocol.SECRET_SHARING,
            "sum",
            ["p1", "p2"]
        )
        success = await mpc_module.coordinator.submit_input(session.session_id, "p1", 100)
        assert success is True

    def test_secret_sharing(self):
        secret = 42
        shares = mpc_module.engine.secret_share(secret, 3, 2)
        assert len(shares) == 3

        recon = mpc_module.engine.secret_reconstruct([(1, shares[0]), (2, shares[1])], 2)
        assert recon == secret

    def test_commitment(self):
        value = "test_value"
        commitment, nonce = mpc_module.engine.compute_commitment(value)
        assert mpc_module.engine.verify_commitment(value, commitment, nonce) is True

    def test_get_session_status(self):
        session = mpc_module.coordinator.create_session(
            MPCProtocol.SECRET_SHARING,
            "sum",
            ["p1", "p2"]
        )
        status = mpc_module.coordinator.get_session_status(session.session_id)
        assert status is not None
        assert status["session_id"] == session.session_id


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
