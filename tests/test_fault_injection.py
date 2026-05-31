import pytest
import asyncio
from src.modules import (
    FaultInjectionManager, FaultType, InjectionScope,
    FaultStatus, FaultCondition, RollbackStrategy
)


@pytest.fixture
def fault_manager():
    fm = FaultInjectionManager()
    fm._enabled = True
    return fm


def test_create_fault(fault_manager):
    fault = fault_manager.create_fault(
        fault_type=FaultType.LATENCY,
        scope=InjectionScope.FUNCTION,
        target="test_function",
        parameters={"delay_ms": 100},
        description="Test latency fault",
    )

    assert fault.fault_type == FaultType.LATENCY
    assert fault.scope == InjectionScope.FUNCTION
    assert fault.target == "test_function"
    assert fault.status == FaultStatus.INACTIVE


def test_activate_deactivate_fault(fault_manager):
    fault = fault_manager.create_fault(
        fault_type=FaultType.ERROR,
        scope=InjectionScope.GLOBAL,
        target="*",
    )

    activated = fault_manager.activate_fault(fault.fault_id)
    assert activated is not None
    assert activated.status == FaultStatus.ACTIVE

    deactivated = fault_manager.deactivate_fault(fault.fault_id)
    assert deactivated is not None
    assert deactivated.status == FaultStatus.INACTIVE


def test_list_faults(fault_manager):
    fault_manager.create_fault(FaultType.LATENCY, InjectionScope.FUNCTION, "func1")
    fault_manager.create_fault(FaultType.ERROR, InjectionScope.MODULE, "module1")

    all_faults = fault_manager.list_faults()
    assert len(all_faults) == 2

    latency_faults = fault_manager.list_faults(fault_type=FaultType.LATENCY)
    assert len(latency_faults) == 1


@pytest.mark.asyncio
async def test_latency_injection(fault_manager):
    fault = fault_manager.create_fault(
        fault_type=FaultType.LATENCY,
        scope=InjectionScope.FUNCTION,
        target="test_func",
        parameters={"delay_ms": 10},
    )
    fault_manager.activate_fault(fault.fault_id)

    start = asyncio.get_event_loop().time()
    await fault_manager.check_and_inject(InjectionScope.FUNCTION, "test_func")
    elapsed = asyncio.get_event_loop().time() - start

    assert elapsed >= 0.01


def test_fault_condition():
    condition = FaultCondition(
        min_calls=2,
        max_calls=5,
        probability=1.0,
    )

    assert not condition.should_trigger(1)
    assert condition.should_trigger(2)
    assert condition.should_trigger(3)
    assert not condition.should_trigger(6)


def test_get_stats(fault_manager):
    fault_manager.create_fault(FaultType.LATENCY, InjectionScope.FUNCTION, "f1")
    fault_manager.create_fault(FaultType.ERROR, InjectionScope.GLOBAL, "*")

    stats = fault_manager.get_stats()
    assert stats["total_faults"] == 2
