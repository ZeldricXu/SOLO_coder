import pytest
from datetime import datetime, timedelta
from src.modules import (
    InMemoryEventStore, EventStore, EventType, Event,
    Snapshot, Projection
)


class TestProjection(Projection[dict]):
    def apply(self, state: dict, event: Event) -> dict:
        if event.event_type == EventType.CREATED:
            return {**state, "created": True, **event.payload}
        elif event.event_type == EventType.UPDATED:
            return {**state, **event.payload}
        return state

    def initial_state(self) -> dict:
        return {"created": False}


@pytest.fixture
def event_store():
    backend = InMemoryEventStore()
    return EventStore(backend=backend)


@pytest.mark.asyncio
async def test_append_event(event_store):
    aggregate_id = "agg_123"
    event = await event_store.append(
        aggregate_id=aggregate_id,
        event_type=EventType.CREATED,
        payload={"name": "test"},
    )

    assert event.aggregate_id == aggregate_id
    assert event.version == 1


@pytest.mark.asyncio
async def test_get_events(event_store):
    aggregate_id = "agg_456"
    await event_store.append(aggregate_id, EventType.CREATED, {"v": 1})
    await event_store.append(aggregate_id, EventType.UPDATED, {"v": 2})
    await event_store.append(aggregate_id, EventType.UPDATED, {"v": 3})

    events = await event_store.get_events(aggregate_id)
    assert len(events) == 3
    assert events[0].version == 1
    assert events[-1].version == 3


@pytest.mark.asyncio
async def test_aggregate_state(event_store):
    aggregate_id = "agg_789"
    projection = TestProjection()

    await event_store.append(aggregate_id, EventType.CREATED, {"name": "test"})
    await event_store.append(aggregate_id, EventType.UPDATED, {"status": "active"})

    state = await event_store.aggregate_state(aggregate_id, projection)
    assert state["created"] is True
    assert state["name"] == "test"
    assert state["status"] == "active"


@pytest.mark.asyncio
async def test_time_travel(event_store):
    aggregate_id = "agg_time"
    projection = TestProjection()

    t1 = datetime.utcnow()
    await event_store.append(aggregate_id, EventType.CREATED, {"v": 1})

    t2 = datetime.utcnow() + timedelta(seconds=1)
    await event_store.append(aggregate_id, EventType.UPDATED, {"v": 2})

    state_after_t1 = await event_store.time_travel(aggregate_id, t1, projection)
    assert state_after_t1.get("v") == 1

    state_after_t2 = await event_store.time_travel(aggregate_id, t2, projection)
    assert state_after_t2.get("v") == 2


@pytest.mark.asyncio
async def test_concurrent_version_check(event_store):
    aggregate_id = "agg_concurrent"
    await event_store.append(aggregate_id, EventType.CREATED, {"v": 1})

    with pytest.raises(ValueError):
        await event_store.append(
            aggregate_id, EventType.UPDATED, {"v": 2},
            expected_version=5
        )
