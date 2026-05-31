use std::sync::Arc;
use tokio::sync::mpsc;
use dashmap::DashMap;
use tracing::{info, debug, error};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::Utc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainEvent {
    pub event_id: String,
    pub event_type: String,
    pub entity_id: String,
    pub payload: serde_json::Value,
    pub timestamp: chrono::DateTime<Utc>,
    pub trace_id: String,
}

impl DomainEvent {
    pub fn new(event_type: impl Into<String>, entity_id: impl Into<String>, payload: serde_json::Value, trace_id: impl Into<String>) -> Self {
        Self {
            event_id: Uuid::new_v4().to_string(),
            event_type: event_type.into(),
            entity_id: entity_id.into(),
            payload,
            timestamp: Utc::now(),
            trace_id: trace_id.into(),
        }
    }
}

pub type EventHandler = Arc<dyn Fn(DomainEvent) -> mpsc::Receiver<()> + Send + Sync>;

pub struct EventBus {
    subscribers: DashMap<String, Vec<mpsc::UnboundedSender<DomainEvent>>>,
}

impl EventBus {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            subscribers: DashMap::new(),
        })
    }

    pub fn subscribe(&self, event_type: impl Into<String>, handler: mpsc::UnboundedSender<DomainEvent>) {
        let event_type = event_type.into();
        self.subscribers
            .entry(event_type.clone())
            .or_default()
            .push(handler);
        debug!(event_type = %event_type, "Subscribed to event");
    }

    pub async fn publish(&self, event: DomainEvent) {
        info!(
            event_id = %event.event_id,
            event_type = %event.event_type,
            entity_id = %event.entity_id,
            "Publishing domain event"
        );

        if let Some(subs) = self.subscribers.get(&event.event_type) {
            for sender in subs.value() {
                if let Err(e) = sender.send(event.clone()) {
                    error!(error = %e, event_type = %event.event_type, "Failed to send event to subscriber");
                }
            }
        }

        if let Some(global_subs) = self.subscribers.get("*") {
            for sender in global_subs.value() {
                if let Err(e) = sender.send(event.clone()) {
                    error!(error = %e, "Failed to send event to global subscriber");
                }
            }
        }
    }

    pub async fn publish_many(&self, events: Vec<DomainEvent>) {
        for event in events {
            self.publish(event).await;
        }
    }
}

#[async_trait::async_trait]
pub trait EventListener: Send + Sync {
    async fn on_event(&self, event: DomainEvent) -> crate::common::error::AppResult<()>;
}

pub struct EventListenerAdapter<L: EventListener> {
    listener: Arc<L>,
    receiver: mpsc::UnboundedReceiver<DomainEvent>,
}

impl<L: EventListener + 'static> EventListenerAdapter<L> {
    pub fn new(listener: Arc<L>) -> (Self, mpsc::UnboundedSender<DomainEvent>) {
        let (sender, receiver) = mpsc::unbounded_channel();
        (
            Self { listener, receiver },
            sender,
        )
    }

    pub async fn run(mut self) {
        while let Some(event) = self.receiver.recv().await {
            if let Err(e) = self.listener.on_event(event).await {
                error!(error = %e, "EventListener failed to handle event");
            }
        }
    }
}
