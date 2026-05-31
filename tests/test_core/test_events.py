import pytest
from streamsql.core.events import EventType, Event, EventBus


def test_event_type_enum():
    assert hasattr(EventType, "TASK_STARTED")
    assert hasattr(EventType, "TASK_COMPLETED")
    assert hasattr(EventType, "TASK_FAILED")


def test_event_creation():
    event = Event(
        event_type=EventType.TASK_STARTED,
        payload={"table": "users", "columns": 4},
        source="test",
    )
    assert event.event_type == EventType.TASK_STARTED
    assert event.payload["table"] == "users"
    assert event.source == "test"
    assert event.event_id is not None


def test_event_to_dict():
    event = Event(
        event_type=EventType.TASK_COMPLETED,
        payload={"test": "data"},
        source="test",
    )
    event_dict = event.to_dict()
    assert event_dict["event_type"] == "task.completed"
    assert event_dict["payload"]["test"] == "data"


def test_event_bus_subscribe():
    bus = EventBus()
    events_received = []

    def handler(event):
        events_received.append(event)

    bus.subscribe(EventType.TASK_COMPLETED, handler)
    event = Event(EventType.TASK_COMPLETED, {"test": "data"}, "test")
    bus.emit(event)
    assert len(events_received) == 1
    assert events_received[0].payload["test"] == "data"


def test_event_bus_unsubscribe():
    bus = EventBus()
    events_received = []

    def handler(event):
        events_received.append(event)

    bus.subscribe(EventType.TASK_COMPLETED, handler)
    bus.unsubscribe(EventType.TASK_COMPLETED, handler)
    event = Event(EventType.TASK_COMPLETED, {"test": "data"}, "test")
    bus.emit(event)
    assert len(events_received) == 0


def test_event_bus_multiple_subscribers():
    bus = EventBus()
    events1 = []
    events2 = []

    def handler1(event):
        events1.append(event)

    def handler2(event):
        events2.append(event)

    bus.subscribe(EventType.TASK_COMPLETED, handler1)
    bus.subscribe(EventType.TASK_COMPLETED, handler2)
    event = Event(EventType.TASK_COMPLETED, {"test": "data"}, "test")
    bus.emit(event)
    assert len(events1) == 1
    assert len(events2) == 1


def test_event_bus_different_event_types():
    bus = EventBus()
    start_events = []
    complete_events = []

    def start_handler(event):
        start_events.append(event)

    def complete_handler(event):
        complete_events.append(event)

    bus.subscribe(EventType.TASK_STARTED, start_handler)
    bus.subscribe(EventType.TASK_COMPLETED, complete_handler)

    bus.emit(Event(EventType.TASK_STARTED, {}, "test"))
    bus.emit(Event(EventType.TASK_COMPLETED, {}, "test"))

    assert len(start_events) == 1
    assert len(complete_events) == 1


def test_event_bus_clear():
    bus = EventBus()
    events = []

    def handler(event):
        events.append(event)

    bus.subscribe(EventType.TASK_COMPLETED, handler)
    bus.clear()
    bus.emit(Event(EventType.TASK_COMPLETED, {}, "test"))
    assert len(events) == 0
