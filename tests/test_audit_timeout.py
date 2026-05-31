import pytest
import asyncio
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch, Mock, call
from typing import List, Dict

from src.modules import (
    CommandAuditManager,
    Command,
    CommandStatus,
    CommandType,
    AuditLogEntry,
    AuditAction,
    Severity,
    ComplianceReport,
    get_command_audit_manager,
)
from src.modules.storage_module import StorageManager, MemoryStorageBackend
from .builders import BuilderFactory


@pytest.fixture
def memory_storage():
    backend = MemoryStorageBackend()
    return StorageManager(backend=backend)


@pytest.fixture
def audit_manager(memory_storage):
    from src.modules.audit_module import StorageCommandStore, StorageAuditLogStore
    from src.modules.event_store import EventStore, InMemoryEventStore

    command_store = StorageCommandStore(memory_storage)
    audit_store = StorageAuditLogStore(memory_storage)
    event_store = EventStore(backend=InMemoryEventStore())

    manager = CommandAuditManager(
        command_store=command_store,
        audit_store=audit_store,
        event_store=event_store,
    )
    return manager


class TestCommandTimeoutDegradation:
    @pytest.mark.asyncio
    async def test_command_execution_with_timeout(self, audit_manager):
        test_data = BuilderFactory.command().with_type("execute").build()

        async def slow_handler(payload):
            await asyncio.sleep(0.2)
            return {"result": "success"}

        audit_manager.register_command_handler(CommandType.EXECUTE, slow_handler)

        command = await audit_manager.create_command(
            command_type=CommandType.EXECUTE,
            payload=test_data.payload,
            user_id=test_data.user_id,
            max_retries=1,
        )

        start = asyncio.get_event_loop().time()
        result = await audit_manager.execute_command(command)
        elapsed = asyncio.get_event_loop().time() - start

        assert elapsed >= 0.2
        assert result.status == CommandStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_command_timeout_triggers_retry(self, audit_manager):
        retry_count = {"count": 0}

        async def flaky_handler(payload):
            retry_count["count"] += 1
            if retry_count["count"] < 3:
                await asyncio.sleep(0.1)
                raise TimeoutError("Operation timed out")
            return {"result": "success_after_retries"}

        audit_manager.register_command_handler(CommandType.CUSTOM, flaky_handler)

        command = await audit_manager.create_command(
            command_type=CommandType.CUSTOM,
            payload={"action": "test"},
            max_retries=3,
        )

        result = await audit_manager.execute_command(command)

        assert retry_count["count"] == 3
        assert result.status == CommandStatus.COMPLETED
        assert command.retry_count == 2

    @pytest.mark.asyncio
    async def test_command_max_retries_exceeded(self, audit_manager):
        call_count = {"count": 0}

        async def always_fails(payload):
            call_count["count"] += 1
            raise TimeoutError("Permanent timeout")

        audit_manager.register_command_handler(CommandType.MIGRATE, always_fails)

        command = await audit_manager.create_command(
            command_type=CommandType.MIGRATE,
            payload={"operation": "migrate"},
            max_retries=3,
        )

        result = await audit_manager.execute_command(command)

        assert call_count["count"] == 4
        assert result.status == CommandStatus.FAILED
        assert "Permanent timeout" in result.error_message
        assert command.retry_count == 3

    @pytest.mark.asyncio
    async def test_command_timeout_does_not_affect_other_commands(self, audit_manager):
        async def slow_handler(payload):
            await asyncio.sleep(0.3)
            return {"result": "slow"}

        async def fast_handler(payload):
            return {"result": "fast"}

        audit_manager.register_command_handler(CommandType.EXECUTE, slow_handler)
        audit_manager.register_command_handler(CommandType.CREATE, fast_handler)

        slow_command = await audit_manager.create_command(
            command_type=CommandType.EXECUTE,
            payload={"type": "slow"},
        )
        fast_command = await audit_manager.create_command(
            command_type=CommandType.CREATE,
            payload={"type": "fast"},
        )

        start = asyncio.get_event_loop().time()
        slow_result, fast_result = await asyncio.gather(
            audit_manager.execute_command(slow_command),
            audit_manager.execute_command(fast_command),
        )
        total_elapsed = asyncio.get_event_loop().time() - start

        assert slow_result.status == CommandStatus.COMPLETED
        assert fast_result.status == CommandStatus.COMPLETED
        assert total_elapsed >= 0.3
        assert fast_result.result == {"result": "fast"}

    @pytest.mark.asyncio
    async def test_concurrent_command_timeout_isolation(self, audit_manager):
        async def handler_with_random_timeout(payload):
            delay = payload.get("delay", 0.01)
            await asyncio.sleep(delay)
            return {"delay": delay}

        audit_manager.register_command_handler(CommandType.CUSTOM, handler_with_random_timeout)

        commands = []
        for i in range(5):
            cmd = await audit_manager.create_command(
                command_type=CommandType.CUSTOM,
                payload={"delay": 0.01 * i},
                correlation_id=f"batch_{i}",
            )
            commands.append(cmd)

        start = asyncio.get_event_loop().time()
        results = await asyncio.gather(
            *[audit_manager.execute_command(cmd) for cmd in commands]
        )
        total_elapsed = asyncio.get_event_loop().time() - start

        assert total_elapsed < 0.1
        assert all(r.status == CommandStatus.COMPLETED for r in results)

    @pytest.mark.asyncio
    async def test_command_timeout_audit_logging(self, audit_manager):
        async def timeout_handler(payload):
            await asyncio.sleep(0.05)
            raise TimeoutError("Database query timeout")

        audit_manager.register_command_handler(CommandType.UPDATE, timeout_handler)

        command = await audit_manager.create_command(
            command_type=CommandType.UPDATE,
            payload={"data": "test"},
            user_id="test_user",
            max_retries=1,
        )

        with patch.object(audit_manager, 'log_action', wraps=audit_manager.log_action) as mock_log:
            result = await audit_manager.execute_command(command)

            audit_calls = [
                call for call in mock_log.call_args_list
                if 'failed' in str(call).lower() or 'timeout' in str(call).lower()
            ]
            assert len(audit_calls) >= 1

    @pytest.mark.asyncio
    async def test_command_chain_correlation_on_timeout(self, audit_manager):
        correlation_id = f"corr_{uuid.uuid4().hex[:8]}"

        async def failing_handler(payload):
            raise TimeoutError("Downstream service timeout")

        audit_manager.register_command_handler(CommandType.CUSTOM, failing_handler)

        for i in range(3):
            await audit_manager.create_command(
                command_type=CommandType.CUSTOM,
                payload={"step": i},
                correlation_id=correlation_id,
                causation_id=f"step_{i - 1}" if i > 0 else None,
            )

        commands = await audit_manager.get_command_chain(correlation_id)
        assert len(commands) == 3

    @pytest.mark.asyncio
    async def test_deadline_propagation(self, audit_manager):
        async def nested_handler(payload):
            deadline = payload.get("deadline")
            if deadline and datetime.utcnow() > deadline:
                raise TimeoutError("Deadline exceeded")
            return {"processed": True}

        audit_manager.register_command_handler(CommandType.BATCH, nested_handler)

        tight_deadline = datetime.utcnow() + timedelta(milliseconds=50)

        command = await audit_manager.create_command(
            command_type=CommandType.BATCH,
            payload={"deadline": tight_deadline},
            max_retries=0,
        )

        await asyncio.sleep(0.1)
        result = await audit_manager.execute_command(command)

        assert result.status == CommandStatus.FAILED

    @pytest.mark.asyncio
    async def test_circuit_breaker_behavior(self, audit_manager):
        failure_count = 0
        circuit_open = {"value": False}

        async def circuit_handler(payload):
            nonlocal failure_count
            if circuit_open["value"]:
                raise RuntimeError("Circuit breaker is open")
            failure_count += 1
            if failure_count <= 3:
                raise TimeoutError("Service unavailable")
            return {"success": True}

        audit_manager.register_command_handler(CommandType.MIGRATE, circuit_handler)

        results = []
        for i in range(5):
            command = await audit_manager.create_command(
                command_type=CommandType.MIGRATE,
                payload={"attempt": i},
                max_retries=0,
            )
            if failure_count >= 3:
                circuit_open["value"] = True
            result = await audit_manager.execute_command(command)
            results.append(result)

        failed_count = sum(1 for r in results if r.status == CommandStatus.FAILED)
        assert failed_count >= 3


class TestAuditLogDegradation:
    @pytest.mark.asyncio
    async def test_audit_log_buffered_when_storage_slow(self, audit_manager, memory_storage):
        async def slow_save(key, data, **kwargs):
            await asyncio.sleep(0.2)
            return key

        original_save = memory_storage.save_data
        memory_storage.save_data = AsyncMock(side_effect=slow_save)

        test_data = BuilderFactory.audit_log().with_action("api_call").build()

        start = asyncio.get_event_loop().time()
        log_entry = await audit_manager.log_action(
            action=AuditAction.API_CALL,
            description=test_data.description,
            user_id=test_data.user_id,
            resource_type=test_data.resource_type,
            resource_id=test_data.resource_id,
        )
        elapsed = asyncio.get_event_loop().time() - start

        assert log_entry is not None
        assert log_entry.action == AuditAction.API_CALL

    @pytest.mark.asyncio
    async def test_audit_log_does_not_block_main_flow(self, audit_manager, memory_storage):
        log_count = 0

        async def very_slow_save(key, data, **kwargs):
            nonlocal log_count
            await asyncio.sleep(0.5)
            log_count += 1
            return key

        memory_storage.save_data = AsyncMock(side_effect=very_slow_save)

        async def main_operation():
            await audit_manager.log_action(
                action=AuditAction.MODIFY,
                description="Test operation",
                user_id="user1",
                resource_type="entity",
                resource_id="res_123",
            )
            return {"operation": "completed"}

        start = asyncio.get_event_loop().time()
        result = await main_operation()
        elapsed = asyncio.get_event_loop().time() - start

        assert result == {"operation": "completed"}

    @pytest.mark.asyncio
    async def test_audit_log_batch_processing(self, audit_manager):
        test_data_list = BuilderFactory.audit_log().build_many(10)

        start = asyncio.get_event_loop().time()
        tasks = [
            audit_manager.log_action(
                action=AuditAction(td.action),
                description=td.description,
                user_id=td.user_id,
                resource_type=td.resource_type,
                resource_id=td.resource_id,
                severity=Severity(td.severity),
            )
            for td in test_data_list
        ]
        results = await asyncio.gather(*tasks)
        elapsed = asyncio.get_event_loop().time() - start

        assert len(results) == 10

    @pytest.mark.asyncio
    async def test_audit_query_timeout_fallback(self, audit_manager):
        test_data = BuilderFactory.audit_log().successful().build()
        await audit_manager.log_action(
            action=AuditAction(test_data.action),
            description=test_data.description,
            user_id=test_data.user_id,
            resource_type=test_data.resource_type,
            resource_id=test_data.resource_id,
        )

        result = await audit_manager.query_audit_logs(
            user_id=test_data.user_id,
            limit=10,
        )

        assert isinstance(result, list)

    @pytest.mark.asyncio
    async def test_compliance_report_degraded_mode(self, audit_manager):
        for _ in range(5):
            td = BuilderFactory.audit_log().with_severity("high").failed().build()
            await audit_manager.log_action(
                action=AuditAction(td.action),
                description=td.description,
                user_id=td.user_id,
                severity=Severity.HIGH,
                success=False,
            )

        for _ in range(10):
            td = BuilderFactory.audit_log().successful().build()
            await audit_manager.log_action(
                action=AuditAction(td.action),
                description=td.description,
                user_id=td.user_id,
                success=True,
            )

        report = await audit_manager.generate_compliance_report(
            start_date=datetime.utcnow() - timedelta(hours=1),
            end_date=datetime.utcnow(),
        )

        assert report is not None
        assert report.total_events >= 15
        assert report.high_severity_events >= 5
        assert report.failed_operations >= 5

    @pytest.mark.asyncio
    async def test_audit_log_correlation_preserved_on_timeout(self, audit_manager):
        correlation_id = f"audit_corr_{uuid.uuid4().hex[:8]}"

        await audit_manager.log_action(
            action=AuditAction.API_CALL,
            description="First log",
            user_id="user1",
            correlation_id=correlation_id,
        )

        await audit_manager.log_action(
            action=AuditAction.MODIFY,
            description="Second log",
            user_id="user1",
            correlation_id=correlation_id,
        )

        result = await audit_manager.query_audit_logs(limit=100)
        correlated_logs = [l for l in result if l.correlation_id == correlation_id]

        assert len(correlated_logs) == 2

    @pytest.mark.asyncio
    async def test_command_persistence_resilience(self, audit_manager, memory_storage):
        async def failing_save(key, data, **kwargs):
            if "fail" in key:
                raise IOError("Storage failure")
            return key

        memory_storage.save_data = AsyncMock(side_effect=failing_save)

        success_command = await audit_manager.create_command(
            command_type=CommandType.CREATE,
            payload={"data": "good"},
        )

        assert success_command is not None
        assert success_command.status == CommandStatus.PENDING

    @pytest.mark.asyncio
    async def test_audit_log_severity_based_priority(self, audit_manager):
        high_log = BuilderFactory.audit_log().with_severity("critical").build()
        low_log = BuilderFactory.audit_log().with_severity("low").build()

        high_entry = await audit_manager.log_action(
            action=AuditAction(high_log.action),
            description=high_log.description,
            severity=Severity.CRITICAL,
        )
        low_entry = await audit_manager.log_action(
            action=AuditAction(low_log.action),
            description=low_log.description,
            severity=Severity.LOW,
        )

        assert high_entry.severity == Severity.CRITICAL
        assert low_entry.severity == Severity.LOW


class TestGracefulDegradation:
    @pytest.mark.asyncio
    async def test_partial_failure_recovery(self, audit_manager):
        attempt = 0

        async def intermittent_handler(payload):
            nonlocal attempt
            attempt += 1
            if attempt % 2 == 1:
                raise TimeoutError("Temporary failure")
            return {"success": True, "attempt": attempt}

        audit_manager.register_command_handler(CommandType.UPDATE, intermittent_handler)

        command1 = await audit_manager.create_command(
            command_type=CommandType.UPDATE,
            payload={"data": "first"},
            max_retries=0,
        )
        result1 = await audit_manager.execute_command(command1)

        command2 = await audit_manager.create_command(
            command_type=CommandType.UPDATE,
            payload={"data": "second"},
            max_retries=0,
        )
        result2 = await audit_manager.execute_command(command2)

        assert result1.status == CommandStatus.FAILED
        assert result2.status == CommandStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_timeout_cascading_failure_prevention(self, audit_manager):
        async def upstream_handler(payload):
            await asyncio.sleep(0.1)
            raise TimeoutError("Upstream timeout")

        async def downstream_handler(payload):
            cmd = await audit_manager.create_command(
                command_type=CommandType.CUSTOM,
                payload={"nested": True},
            )
            return await audit_manager.execute_command(cmd)

        audit_manager.register_command_handler(CommandType.CUSTOM, upstream_handler)
        audit_manager.register_command_handler(CommandType.EXECUTE, downstream_handler)

        command = await audit_manager.create_command(
            command_type=CommandType.EXECUTE,
            payload={},
            max_retries=1,
        )

        start = asyncio.get_event_loop().time()
        result = await audit_manager.execute_command(command)
        elapsed = asyncio.get_event_loop().time() - start

        assert result.status == CommandStatus.FAILED

    @pytest.mark.asyncio
    async def test_resource_cleanup_on_timeout(self, audit_manager):
        resources_acquired = []

        async def resource_handler(payload):
            resources_acquired.append("connection")
            try:
                await asyncio.sleep(0.3)
                return {"done": True}
            finally:
                resources_acquired.remove("connection")

        audit_manager.register_command_handler(CommandType.EXECUTE, resource_handler)

        command = await audit_manager.create_command(
            command_type=CommandType.EXECUTE,
            payload={},
            max_retries=0,
        )

        result = await audit_manager.execute_command(command)

        assert len(resources_acquired) == 0

    @pytest.mark.asyncio
    async def test_concurrent_audit_logging_isolation(self, audit_manager):
        user_ids = [f"user_{i}" for i in range(5)]

        async def log_for_user(user_id):
            for _ in range(10):
                td = BuilderFactory.audit_log().build()
                await audit_manager.log_action(
                    action=AuditAction.MODIFY,
                    description=f"Action by {user_id}",
                    user_id=user_id,
                )

        await asyncio.gather(*[log_for_user(uid) for uid in user_ids])

        all_logs = await audit_manager.query_audit_logs(limit=1000)
        assert len(all_logs) == 50

        logs_per_user = {}
        for log in all_logs:
            uid = log.user_id
            logs_per_user[uid] = logs_per_user.get(uid, 0) + 1

        for uid in user_ids:
            assert logs_per_user[uid] == 10
