import pytest
from edge_platform.common.event_bus import EventBus, Event


def test_event_bus_publish_subscribe():
    bus = EventBus()
    received_events = []

    def handler(event):
        received_events.append(event)

    bus.subscribe("test.event", handler)

    event = Event(event_type="test.event", source="test", payload={"data": "value"})
    bus.publish(event)

    assert len(received_events) == 1
    assert received_events[0].event_type == "test.event"
    assert received_events[0].payload["data"] == "value"


def test_event_bus_multiple_subscribers():
    bus = EventBus()
    count1 = [0]
    count2 = [0]

    def handler1(event):
        count1[0] += 1

    def handler2(event):
        count2[0] += 1

    bus.subscribe("test.event", handler1)
    bus.subscribe("test.event", handler2)

    event = Event(event_type="test.event", source="test")
    bus.publish(event)

    assert count1[0] == 1
    assert count2[0] == 1


def test_event_bus_event_history():
    bus = EventBus()

    for i in range(5):
        event = Event(event_type=f"event_{i}", source="test")
        bus.publish(event)

    history = bus.get_event_history()
    assert len(history) == 5


def test_event_bus_filter_history():
    bus = EventBus()

    bus.publish(Event(event_type="type_a", source="test"))
    bus.publish(Event(event_type="type_b", source="test"))
    bus.publish(Event(event_type="type_a", source="test"))

    history_a = bus.get_event_history("type_a")
    assert len(history_a) == 2
