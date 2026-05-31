use serde::{Deserialize, Serialize};
use std::sync::Arc;
use dashmap::DashMap;
use uuid::Uuid;
use tokio::sync::mpsc;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum EventType {
    SbomUploaded,
    VulnerabilityDetected,
    CriticalVulnerabilityFound,
    FixRecommendationGenerated,
    AnalysisCompleted,
    CveDatabaseUpdated,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainEvent {
    pub event_id: Uuid,
    pub event_type: EventType,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub source: String,
    pub payload: serde_json::Value,
    pub correlation_id: Option<String>,
}

impl DomainEvent {
    pub fn new(event_type: EventType, source: &str, payload: serde_json::Value) -> Self {
        Self {
            event_id: Uuid::new_v4(),
            event_type,
            timestamp: chrono::Utc::now(),
            source: source.to_string(),
            payload,
            correlation_id: None,
        }
    }

    pub fn with_correlation_id(mut self, correlation_id: String) -> Self {
        self.correlation_id = Some(correlation_id);
        self
    }
}

pub type EventHandlerFn = Arc<dyn Fn(DomainEvent) -> mpsc::Receiver<()> + Send + Sync>;
pub type SyncEventHandlerFn = Arc<dyn Fn(&DomainEvent) + Send + Sync>;

pub struct EventBus {
    subscribers: DashMap<EventType, Vec<SyncEventHandlerFn>>,
    all_subscribers: Vec<SyncEventHandlerFn>,
    async_senders: Vec<mpsc::UnboundedSender<DomainEvent>>,
}

impl EventBus {
    pub fn new() -> Self {
        Self {
            subscribers: DashMap::new(),
            all_subscribers: Vec::new(),
            async_senders: Vec::new(),
        }
    }

    pub fn publish(&self, event: DomainEvent) {
        if let Some(handlers) = self.subscribers.get(&event.event_type) {
            for handler in handlers.value() {
                handler(&event);
            }
        }

        for handler in &self.all_subscribers {
            handler(&event);
        }

        for sender in &self.async_senders {
            let _ = sender.send(event.clone());
        }
    }

    pub fn subscribe<F>(&self, event_type: EventType, handler: F)
    where
        F: Fn(&DomainEvent) + Send + Sync + 'static,
    {
        self.subscribers
            .entry(event_type)
            .or_insert_with(Vec::new)
            .push(Arc::new(handler));
    }

    pub fn subscribe_all<F>(&mut self, handler: F)
    where
        F: Fn(&DomainEvent) + Send + Sync + 'static,
    {
        self.all_subscribers.push(Arc::new(handler));
    }

    pub fn subscribe_async(&mut self) -> mpsc::UnboundedReceiver<DomainEvent> {
        let (tx, rx) = mpsc::unbounded_channel();
        self.async_senders.push(tx);
        rx
    }

    pub fn subscriber_count(&self, event_type: &EventType) -> usize {
        self.subscribers.get(event_type).map(|v| v.len()).unwrap_or(0)
    }

    pub fn total_subscriber_count(&self) -> usize {
        let mut count = self.all_subscribers.len() + self.async_senders.len();
        for entry in self.subscribers.iter() {
            count += entry.value().len();
        }
        count
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SbomUploadedPayload {
    pub sbom_id: Uuid,
    pub sbom_name: String,
    pub package_count: usize,
    pub uploaded_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VulnerabilityDetectedPayload {
    pub sbom_id: Uuid,
    pub cve_id: String,
    pub package_name: String,
    pub package_version: String,
    pub severity: crate::models::Severity,
    pub cvss_score: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CriticalVulnerabilityPayload {
    pub sbom_id: Uuid,
    pub cve_id: String,
    pub package_name: String,
    pub cvss_score: f64,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FixRecommendationPayload {
    pub sbom_id: Uuid,
    pub package_name: String,
    pub current_version: String,
    pub recommended_version: String,
    pub confidence: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisCompletedPayload {
    pub analysis_id: Uuid,
    pub sbom_name: String,
    pub total_packages: usize,
    pub vulnerable_packages: usize,
    pub risk_score: f64,
    pub duration_ms: u64,
}

pub struct EventPublisher {
    bus: Arc<EventBus>,
    source: String,
}

impl EventPublisher {
    pub fn new(bus: Arc<EventBus>, source: &str) -> Self {
        Self {
            bus,
            source: source.to_string(),
        }
    }

    pub fn publish_sbom_uploaded(&self, payload: SbomUploadedPayload) {
        let event = DomainEvent::new(
            EventType::SbomUploaded,
            &self.source,
            serde_json::to_value(payload).unwrap_or_default(),
        );
        self.bus.publish(event);
    }

    pub fn publish_vulnerability_detected(&self, payload: VulnerabilityDetectedPayload) {
        let event = DomainEvent::new(
            EventType::VulnerabilityDetected,
            &self.source,
            serde_json::to_value(payload).unwrap_or_default(),
        );
        self.bus.publish(event);
    }

    pub fn publish_critical_vulnerability(&self, payload: CriticalVulnerabilityPayload) {
        let event = DomainEvent::new(
            EventType::CriticalVulnerabilityFound,
            &self.source,
            serde_json::to_value(payload).unwrap_or_default(),
        );
        self.bus.publish(event);
    }

    pub fn publish_fix_recommendation(&self, payload: FixRecommendationPayload) {
        let event = DomainEvent::new(
            EventType::FixRecommendationGenerated,
            &self.source,
            serde_json::to_value(payload).unwrap_or_default(),
        );
        self.bus.publish(event);
    }

    pub fn publish_analysis_completed(&self, payload: AnalysisCompletedPayload) {
        let event = DomainEvent::new(
            EventType::AnalysisCompleted,
            &self.source,
            serde_json::to_value(payload).unwrap_or_default(),
        );
        self.bus.publish(event);
    }

    pub fn bus(&self) -> Arc<EventBus> {
        self.bus.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn test_event_publish_subscribe() {
        let bus = Arc::new(EventBus::new());
        let publisher = EventPublisher::new(bus.clone(), "test");

        let counter = Arc::new(AtomicUsize::new(0));
        let counter_clone = counter.clone();

        bus.subscribe(EventType::SbomUploaded, move |_event| {
            counter_clone.fetch_add(1, Ordering::SeqCst);
        });

        publisher.publish_sbom_uploaded(SbomUploadedPayload {
            sbom_id: Uuid::new_v4(),
            sbom_name: "test.sbom".to_string(),
            package_count: 10,
            uploaded_by: "test_user".to_string(),
        });

        assert_eq!(counter.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn test_async_subscription() {
        let mut bus = EventBus::new();
        let mut rx = bus.subscribe_async();

        let publisher = EventPublisher::new(Arc::new(bus), "test");

        publisher.publish_analysis_completed(AnalysisCompletedPayload {
            analysis_id: Uuid::new_v4(),
            sbom_name: "test".to_string(),
            total_packages: 100,
            vulnerable_packages: 5,
            risk_score: 7.5,
            duration_ms: 150,
        });

        let received = tokio::time::timeout(
            tokio::time::Duration::from_secs(1),
            rx.recv()
        ).await;

        assert!(received.is_ok());
        assert!(received.unwrap().is_some());
    }
}
