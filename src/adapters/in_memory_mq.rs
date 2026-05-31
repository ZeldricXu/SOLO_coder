use std::sync::Arc;
use async_trait::async_trait;
use tokio::sync::Mutex;
use std::collections::HashMap;
use serde_json::Value;
use uuid::Uuid;

use crate::common::error::AppResult;
use crate::ports::mod::MessageQueuePort;

struct QueueMessage {
    id: String,
    payload: Value,
    ack: bool,
}

pub struct InMemoryMessageQueue {
    queues: Arc<Mutex<HashMap<String, Vec<QueueMessage>>>>,
}

impl InMemoryMessageQueue {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            queues: Arc::new(Mutex::new(HashMap::new())),
        })
    }
}

#[async_trait]
impl MessageQueuePort for InMemoryMessageQueue {
    async fn send(&self, queue: &str, message: Value) -> AppResult<()> {
        let mut queues = self.queues.lock().await;
        let queue_entry = queues.entry(queue.to_string()).or_default();
        queue_entry.push(QueueMessage {
            id: Uuid::new_v4().to_string(),
            payload: message,
            ack: false,
        });
        Ok(())
    }

    async fn receive(&self, queue: &str) -> AppResult<Option<Value>> {
        let mut queues = self.queues.lock().await;
        let queue_entry = queues.entry(queue.to_string()).or_default();
        for msg in queue_entry.iter_mut() {
            if !msg.ack {
                msg.ack = true;
                return Ok(Some(msg.payload.clone()));
            }
        }
        Ok(None)
    }

    async fn acknowledge(&self, message_id: &str) -> AppResult<()> {
        let mut queues = self.queues.lock().await;
        for queue in queues.values_mut() {
            queue.retain(|msg| msg.id != message_id);
        }
        Ok(())
    }
}
