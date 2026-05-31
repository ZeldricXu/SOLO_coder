import pytest
import asyncio
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch, Mock
from typing import List, Dict

from src.modules import (
    FaultInjectionManager,
    FaultType,
    InjectionScope,
    FaultStatus,
    FaultCondition,
    RollbackStrategy,
    LatencyInjector,
    ErrorInjector,
    get_fault_injection_manager,
)
from .builders import BuilderFactory


@pytest.fixture
def fault_manager(monkeypatch) -> FaultInjectionManager:
    from src.modules import EventStore, InMemoryEventStore
    event_store = EventStore(backend=InMemoryEventStore())
    event_store.append = AsyncMock()
    
    monkeypatch.setattr(asyncio, 'create_task', lambda coro: None)
    
    manager = FaultInjectionManager(event_store=event_store)
    manager._enabled = True
    manager._faults.clear()
    manager._active_faults.clear()
    return manager


class TestFaultIsolation:
    def test_singleton_isolation(self):
        fm1 = get_fault_injection_manager()
        fm2 = get_fault_injection_manager()
        assert fm1 is fm2

    def test_fault_registration_isolation(self, fault_manager):
        test_data = BuilderFactory.fault().with_scope("function").with_target("test_func").build()

        fault = fault_manager.create_fault(
            fault_type=FaultType(test_data.fault_type),
            scope=InjectionScope(test_data.scope),
            target=test_data.target,
            parameters=test_data.parameters,
            description=test_data.description,
        )

        assert fault.fault_id in fault_manager._faults
        assert len(fault_manager._faults) == 1

    def test_fault_activation_isolation(self, fault_manager):
        test_data_list = BuilderFactory.fault().build_many(5)

        for i, td in enumerate(test_data_list):
            fault_manager.create_fault(
                fault_type=FaultType(td.fault_type),
                scope=InjectionScope(td.scope),
                target=f"target_{i}",
                parameters=td.parameters,
            )

        fault_ids = list(fault_manager._faults.keys())
        fault_manager.activate_fault(fault_ids[0])
        fault_manager.activate_fault(fault_ids[1])

        active_count = sum(
            1 for f in fault_manager._faults.values()
            if f.status == FaultStatus.ACTIVE
        )
        assert active_count == 2
        assert fault_manager._faults[fault_ids[2]].status == FaultStatus.INACTIVE

    def test_scope_isolation_global_vs_function(self, fault_manager):
        global_fault_data = BuilderFactory.fault().with_scope("global").with_target("*").active().build()
        function_fault_data = BuilderFactory.fault().with_scope("function").with_target("specific_func").inactive().build()

        global_fault = fault_manager.create_fault(
            fault_type=FaultType(global_fault_data.fault_type),
            scope=InjectionScope.GLOBAL,
            target="*",
            parameters={"delay_ms": 10},
        )
        function_fault = fault_manager.create_fault(
            fault_type=FaultType(function_fault_data.fault_type),
            scope=InjectionScope.FUNCTION,
            target="specific_func",
            parameters={"error_code": 500},
        )

        fault_manager.activate_fault(global_fault.fault_id)
        fault_manager.activate_fault(function_fault.fault_id)

        matching_global = fault_manager._get_matching_faults(InjectionScope.FUNCTION, "other_func")
        matching_specific = fault_manager._get_matching_faults(InjectionScope.FUNCTION, "specific_func")

        assert len(matching_global) == 1
        assert len(matching_specific) == 2

    def test_entity_scope_isolation(self, fault_manager):
        entity_ids = [f"entity_{i}" for i in range(3)]

        for entity_id in entity_ids:
            fault_data = BuilderFactory.fault() \
                .with_fault_type("latency") \
                .with_scope("entity") \
                .with_target(entity_id) \
                .with_parameters({"delay_ms": 50}) \
                .build()

            fault = fault_manager.create_fault(
                fault_type=FaultType.LATENCY,
                scope=InjectionScope.ENTITY,
                target=entity_id,
                parameters=fault_data.parameters,
            )
            fault_manager.activate_fault(fault.fault_id)

        matches_0 = fault_manager._get_matching_faults(InjectionScope.ENTITY, entity_ids[0])
        matches_1 = fault_manager._get_matching_faults(InjectionScope.ENTITY, entity_ids[1])
        matches_2 = fault_manager._get_matching_faults(InjectionScope.ENTITY, entity_ids[2])
        no_matches = fault_manager._get_matching_faults(InjectionScope.ENTITY, "unknown_entity")

        assert len(matches_0) == 1
        assert len(matches_1) == 1
        assert len(matches_2) == 1
        assert len(no_matches) == 0

    def test_user_scope_isolation(self, fault_manager):
        users = [f"user_{i}" for i in range(5)]

        for user in users[:2]:
            fault_data = BuilderFactory.fault() \
                .with_scope("user") \
                .with_target(user) \
                .build()
            fault = fault_manager.create_fault(
                fault_type=FaultType.ERROR,
                scope=InjectionScope.USER,
                target=user,
                parameters={"error_code": 403},
            )
            fault_manager.activate_fault(fault.fault_id)

        for user in users:
            matches = fault_manager._get_matching_faults(InjectionScope.USER, user)
            if user in users[:2]:
                assert len(matches) == 1
            else:
                assert len(matches) == 0

    @pytest.mark.asyncio
    async def test_concurrent_fault_injection_isolation(self, fault_manager):
        targets = [f"target_{i}" for i in range(10)]
        created_faults = []

        for target in targets:
            fault = fault_manager.create_fault(
                fault_type=FaultType.LATENCY,
                scope=InjectionScope.FUNCTION,
                target=target,
                parameters={"delay_ms": 5},
            )
            fault_manager.activate_fault(fault.fault_id)
            created_faults.append(fault)

        async def inject_for_target(target):
            return await fault_manager.check_and_inject(InjectionScope.FUNCTION, target)

        tasks = [inject_for_target(target) for target in targets]
        results = await asyncio.gather(*tasks)

        assert len(results) == 10
        for result in results:
            assert isinstance(result, list)

    @pytest.mark.asyncio
    async def test_fault_injection_does_not_affect_other_targets(self, fault_manager):
        protected_target = "protected_function"
        injected_target = "injected_function"

        fault = fault_manager.create_fault(
            fault_type=FaultType.LATENCY,
            scope=InjectionScope.FUNCTION,
            target=injected_target,
            parameters={"delay_ms": 50},
        )
        fault_manager.activate_fault(fault.fault_id)

        start = asyncio.get_event_loop().time()
        protected_result = await fault_manager.check_and_inject(
            InjectionScope.FUNCTION, protected_target
        )
        protected_time = asyncio.get_event_loop().time() - start

        start = asyncio.get_event_loop().time()
        injected_result = await fault_manager.check_and_inject(
            InjectionScope.FUNCTION, injected_target
        )
        injected_time = asyncio.get_event_loop().time() - start

        assert protected_time < 0.01
        assert injected_time >= 0.05
        assert len(protected_result) == 0
        assert len(injected_result) >= 1

    def test_fault_disable_global(self, fault_manager):
        fault_data = BuilderFactory.fault().active().build()
        fault = fault_manager.create_fault(
            fault_type=FaultType.LATENCY,
            scope=InjectionScope.GLOBAL,
            target="*",
            parameters={"delay_ms": 100},
        )
        fault_manager.activate_fault(fault.fault_id)

        matches_before = fault_manager._get_matching_faults(InjectionScope.FUNCTION, "any_func")
        assert len(matches_before) == 1

        fault_manager._enabled = False
        matches_after = fault_manager._get_matching_faults(InjectionScope.FUNCTION, "any_func")
        assert len(matches_after) == 0

    def test_fault_rollback_isolation(self, fault_manager):
        faults_data = BuilderFactory.fault().build_many(5)
        fault_ids = []

        for fd in faults_data:
            fault = fault_manager.create_fault(
                fault_type=FaultType(fd.fault_type),
                scope=InjectionScope(fd.scope),
                target=fd.target,
                parameters=fd.parameters,
            )
            fault_manager.activate_fault(fault.fault_id)
            fault_ids.append(fault.fault_id)

        assert fault_manager.get_active_fault_count() == 5

        result = asyncio.run(fault_manager.rollback_fault(fault_ids[0]))
        assert result is True
        assert fault_manager.get_active_fault_count() == 4

    def test_deactivate_all_faults(self, fault_manager):
        for fd in BuilderFactory.fault().build_many(10):
            fault = fault_manager.create_fault(
                fault_type=FaultType(fd.fault_type),
                scope=InjectionScope(fd.scope),
                target=fd.target,
                parameters=fd.parameters,
            )
            fault_manager.activate_fault(fault.fault_id)

        assert fault_manager.get_active_fault_count() == 10

        for fault_id in list(fault_manager._faults.keys()):
            fault_manager.deactivate_fault(fault_id)

        assert fault_manager.get_active_fault_count() == 0

    @pytest.mark.asyncio
    async def test_concurrent_activation_deactivation(self, fault_manager):
        fd = BuilderFactory.fault().build()
        fault = fault_manager.create_fault(
            fault_type=FaultType.LATENCY,
            scope=InjectionScope.FUNCTION,
            target="test",
            parameters={"delay_ms": 10},
        )

        async def toggle_activation():
            for _ in range(10):
                fault_manager.activate_fault(fault.fault_id)
                await asyncio.sleep(0.001)
                fault_manager.deactivate_fault(fault.fault_id)
                await asyncio.sleep(0.001)

        await asyncio.gather(*[toggle_activation() for _ in range(5)])

        assert fault.fault_id in fault_manager._faults


class TestFaultConditionIsolation:
    def test_min_calls_condition_isolation(self):
        condition = FaultCondition(min_calls=3)

        assert not condition.should_trigger(1)
        assert not condition.should_trigger(2)
        assert condition.should_trigger(3)
        assert condition.should_trigger(10)

    def test_max_calls_condition_isolation(self):
        condition = FaultCondition(min_calls=0, max_calls=5)

        assert condition.should_trigger(1)
        assert condition.should_trigger(5)
        assert not condition.should_trigger(6)
        assert not condition.should_trigger(10)

    def test_time_window_condition_isolation(self):
        now = datetime.utcnow()
        condition = FaultCondition(
            start_time=now + timedelta(seconds=1),
            end_time=now + timedelta(seconds=5),
        )

        assert not condition.should_trigger(1)

    def test_probability_condition_isolation(self):
        condition = FaultCondition(probability=0.0)
        for _ in range(100):
            assert not condition.should_trigger(1)

        condition = FaultCondition(probability=1.0)
        for _ in range(100):
            assert condition.should_trigger(1)

    @pytest.mark.asyncio
    async def test_call_count_isolation_per_fault(self, fault_manager):
        targets = ["func_a", "func_b", "func_c"]
        for target in targets:
            fault = fault_manager.create_fault(
                fault_type=FaultType.LATENCY,
                scope=InjectionScope.FUNCTION,
                target=target,
                parameters={"delay_ms": 1},
            )
            fault_manager.activate_fault(fault.fault_id)

        for i in range(5):
            await fault_manager.check_and_inject(InjectionScope.FUNCTION, targets[0])

        for i in range(3):
            await fault_manager.check_and_inject(InjectionScope.FUNCTION, targets[1])

        call_counts = [f.call_count for f in fault_manager._faults.values()]
        assert call_counts[0] == 5
        assert call_counts[1] == 3
        assert call_counts[2] == 0


class TestInjectorIsolation:
    @pytest.mark.asyncio
    async def test_latency_injector_isolation(self):
        injector = LatencyInjector()
        fault = MagicMock()
        fault.parameters = {"delay_ms": 50, "jitter_ms": 0}

        start = asyncio.get_event_loop().time()
        await injector.inject(fault)
        elapsed = asyncio.get_event_loop().time() - start

        assert elapsed >= 0.05

    @pytest.mark.asyncio
    async def test_error_injector_isolation(self):
        injector = ErrorInjector()
        fault = MagicMock()
        fault.parameters = {"error_code": 500, "error_message": "Test error"}

        with pytest.raises(RuntimeError) as exc_info:
            await injector.inject(fault)

        assert "500" in str(exc_info.value)
        assert "Test error" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_injector_independence(self):
        injector1 = LatencyInjector()
        injector2 = ErrorInjector()

        fault1 = MagicMock()
        fault1.parameters = {"delay_ms": 10}

        fault2 = MagicMock()
        fault2.parameters = {"error_code": 500, "error_message": "Error"}

        start = asyncio.get_event_loop().time()
        await injector1.inject(fault1)
        elapsed1 = asyncio.get_event_loop().time() - start

        assert elapsed1 >= 0.01

        with pytest.raises(RuntimeError):
            await injector2.inject(fault2)

    @pytest.mark.asyncio
    async def test_rollback_isolation(self, fault_manager):
        from src.modules.fault_injection import LatencyInjector

        injector = LatencyInjector()
        fault = MagicMock()
        fault.parameters = {"delay_ms": 10}

        await injector.inject(fault)
        await injector.rollback(fault)

        assert True


class TestFaultStatsIsolation:
    def test_stats_correctness(self, fault_manager):
        for fd in BuilderFactory.fault().with_fault_type("latency").build_many(3):
            fault_manager.create_fault(
                fault_type=FaultType.LATENCY,
                scope=InjectionScope(fd.scope),
                target=fd.target,
                parameters=fd.parameters,
            )

        for fd in BuilderFactory.fault().with_fault_type("error").build_many(2):
            fault_manager.create_fault(
                fault_type=FaultType.ERROR,
                scope=InjectionScope(fd.scope),
                target=fd.target,
                parameters=fd.parameters,
            )

        stats = fault_manager.get_fault_stats()
        assert stats["total_faults"] == 5
        assert stats["by_type"]["latency"] == 3
        assert stats["by_type"]["error"] == 2

    def test_stats_update_on_activation(self, fault_manager):
        fd = BuilderFactory.fault().build()
        fault = fault_manager.create_fault(
            fault_type=FaultType.LATENCY,
            scope=InjectionScope.FUNCTION,
            target="test",
            parameters=fd.parameters,
        )

        stats_before = fault_manager.get_fault_stats()
        assert stats_before["active_faults"] == 0

        fault_manager.activate_fault(fault.fault_id)

        stats_after = fault_manager.get_fault_stats()
        assert stats_after["active_faults"] == 1

    def test_scope_distribution_stats(self, fault_manager):
        scopes = [InjectionScope.GLOBAL, InjectionScope.MODULE, InjectionScope.FUNCTION]

        for scope in scopes:
            for i in range(2):
                fault_manager.create_fault(
                    fault_type=FaultType.LATENCY,
                    scope=scope,
                    target=f"target_{i}",
                    parameters={},
                )

        stats = fault_manager.get_fault_stats()
        assert stats["by_scope"]["global"] == 2
        assert stats["by_scope"]["module"] == 2
        assert stats["by_scope"]["function"] == 2
