use std::sync::Arc;
use async_trait::async_trait;
use tokio::sync::mpsc;

use crate::common::error::AppResult;
use crate::common::event::{EventBus, DomainEvent};
use crate::ports::mod::EventPublisherPort;

pub struct InMemoryEventPublisher {
    bus: Arc<EventBus>,
}

impl InMemoryEventPublisher {
    pub fn new(bus: Arc<EventBus>) -> Arc<Self> {
        Arc::new(Self { bus })
    }
}

#[async_trait]
impl EventPublisherPort for InMemoryEventPublisher {
    async fn publish(&self, event: DomainEvent) -> AppResult<()> {
        self.bus.publish(event).await;
        Ok(())
    }

    async fn publish_many(&self, events: Vec<DomainEvent>) -> AppResult<()> {
        self.bus.publish_many(events).await;
        Ok(())
    }
}

pub struct EventSubscriber {
    pub event_type: String,
    pub sender: mpsc::UnboundedSender<DomainEvent>,
}

impl EventSubscriber {
    pub fn new(event_type: impl Into<String>) -> (Self, mpsc::UnboundedReceiver<DomainEvent>) {
        let (sender, receiver) = mpsc::unbounded_channel();
        (
            Self {
                event_type: event_type.into(),
                sender,
            },
            receiver,
        )
    }

    pub fn register(self, bus: &EventBus) {
        bus.subscribe(self.event_type, self.sender);
    }
}
