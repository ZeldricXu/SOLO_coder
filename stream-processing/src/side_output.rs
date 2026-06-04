use anyhow::Result;
use std::sync::Arc;
use tokio::sync::mpsc::{channel, Receiver, Sender};
use tokio::sync::RwLock;

use crate::pipeline::StreamEvent;

#[derive(Clone)]
pub struct SideOutput {
    name: String,
    sender: Sender<StreamEvent>,
    receiver: Arc<RwLock<Option<Receiver<StreamEvent>>>>,
}

impl SideOutput {
    pub fn new(name: String, buffer_size: usize) -> Self {
        let (sender, receiver) = channel(buffer_size);
        Self {
            name,
            sender,
            receiver: Arc::new(RwLock::new(Some(receiver))),
        }
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub async fn emit(&self, event: StreamEvent) -> Result<()> {
        let _ = self.sender.send(event).await;
        Ok(())
    }

    pub async fn take_receiver(&self) -> Option<Receiver<StreamEvent>> {
        let mut receiver_guard = self.receiver.write().await;
        receiver_guard.take()
    }

    pub fn sender(&self) -> Sender<StreamEvent> {
        self.sender.clone()
    }
}

pub struct AlertRouter {
    alert_sender: Sender<StreamEvent>,
}

impl AlertRouter {
    pub fn new(alert_sender: Sender<StreamEvent>) -> Self {
        Self { alert_sender }
    }

    pub async fn route(&self, event: StreamEvent) -> Result<()> {
        let _ = self.alert_sender.send(event).await;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogEvent, LogLevel};

    #[tokio::test]
    async fn test_side_output() {
        let output = SideOutput::new("test".to_string(), 100);
        let log_event = LogEvent::new(
            "host".to_string(),
            "service".to_string(),
            LogLevel::Info,
            "test".to_string(),
            "test.log".to_string(),
        );

        output.emit(StreamEvent::Log(log_event)).await.unwrap();

        let mut receiver = output.take_receiver().await.unwrap();
        let received = receiver.recv().await;
        assert!(received.is_some());
    }
}
