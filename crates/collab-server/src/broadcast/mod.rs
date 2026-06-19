use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use uuid::Uuid;

use collab_crdt::Op;
use crate::presence::PresenceUpdate;
use crate::ws::RoomUser;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BroadcastEvent {
    Op {
        sequence: u64,
        op: Op,
    },
    Presence {
        user_id: String,
        update: PresenceUpdate,
    },
    UserJoined {
        user: RoomUser,
    },
    UserLeft {
        user_id: String,
        reason: String,
    },
    Cursor {
        user_id: String,
        position: crate::presence::CursorPosition,
    },
    Selection {
        user_id: String,
        range: crate::presence::SelectionRange,
    },
}

#[derive(Debug, Clone)]
pub struct StreamPublisher {
    redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
    prefix: String,
}

impl StreamPublisher {
    pub fn new(redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>, prefix: String) -> Self {
        Self {
            redis_pool,
            prefix,
        }
    }

    pub fn stream_key(&self, document_id: Uuid) -> String {
        format!("{}stream:{}", self.prefix, document_id)
    }

    pub async fn publish(&self, document_id: Uuid, node_id: &str, event: &BroadcastEvent) -> Result<(), BroadcastError> {
        let payload = serde_json::to_vec(event)?;
        let key = self.stream_key(document_id);

        let mut conn = self.redis_pool.get().await
            .map_err(|e| BroadcastError::Redis(e.to_string()))?;

        let _: String = redis::cmd("XADD")
            .arg(&key)
            .arg("MAXLEN")
            .arg("~")
            .arg(10000usize)
            .arg("*")
            .arg("node_id")
            .arg(node_id)
            .arg("payload")
            .arg(&payload)
            .query_async(&mut *conn)
            .await
            .map_err(|e| BroadcastError::Redis(e.to_string()))?;

        Ok(())
    }
}

pub struct StreamConsumer {
    redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
    prefix: String,
    group_name: String,
    consumer_name: String,
    ws_manager: crate::ws::ConnectionManager,
    rx: mpsc::UnboundedReceiver<Uuid>,
}

impl StreamConsumer {
    pub fn new(
        redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
        prefix: String,
        node_id: String,
        ws_manager: crate::ws::ConnectionManager,
    ) -> (Self, mpsc::UnboundedSender<Uuid>) {
        let group_name = format!("{}group-nodes", prefix);
        let consumer_name = format!("consumer-{}", node_id);
        let (tx, rx) = mpsc::unbounded_channel();

        (
            Self {
                redis_pool,
                prefix,
                group_name,
                consumer_name,
                ws_manager,
                rx,
            },
            tx,
        )
    }

    pub async fn ensure_group(&self, document_id: Uuid) -> Result<(), BroadcastError> {
        let key = format!("{}stream:{}", self.prefix, document_id);
        let mut conn = self.redis_pool.get().await
            .map_err(|e| BroadcastError::Redis(e.to_string()))?;

        let result: Result<String, redis::RedisError> = redis::cmd("XGROUP")
            .arg("CREATE")
            .arg(&key)
            .arg(&self.group_name)
            .arg("0")
            .arg("MKSTREAM")
            .query_async(&mut *conn)
            .await;

        match result {
            Ok(_) => Ok(()),
            Err(e) => {
                if e.to_string().contains("BUSYGROUP") {
                    Ok(())
                } else {
                    Err(BroadcastError::Redis(e.to_string()))
                }
            }
        }
    }

    pub async fn consume_loop(mut self) {
        let mut active_streams: std::collections::HashSet<Uuid> = std::collections::HashSet::new();

        loop {
            while let Ok(doc_id) = self.rx.try_recv() {
                if active_streams.insert(doc_id) {
                    let _ = self.ensure_group(doc_id).await;
                }
            }

            if active_streams.is_empty() {
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }

            let keys: Vec<String> = active_streams
                .iter()
                .map(|id| format!("{}stream:{}", self.prefix, id))
                .collect();

            let result: Result<redis::streams::StreamReadReply, redis::RedisError> = async {
                let mut conn = self.redis_pool.get().await
                    .map_err(|e| redis::RedisError::from((redis::ErrorKind::IoError, "pool", e.to_string())))?;

                let mut cmd = redis::cmd("XREADGROUP");
                cmd.arg("GROUP")
                    .arg(&self.group_name)
                    .arg(&self.consumer_name)
                    .arg("COUNT")
                    .arg(50)
                    .arg("BLOCK")
                    .arg(100)
                    .arg("STREAMS");

                for k in &keys {
                    cmd.arg(k);
                }
                for _ in &keys {
                    cmd.arg(">");
                }

                cmd.query_async(&mut *conn).await
            }.await;

            match result {
                Ok(reply) => {
                    for stream in reply.keys {
                        let doc_id = extract_doc_id(&stream.key, &self.prefix);
                        if let Some(doc_uuid) = doc_id {
                            for message in stream.ids {
                                let msg_id = message.id.clone();
                                if let Err(e) = self.handle_message(doc_uuid, &message).await {
                                    tracing::warn!("Stream message handling error: {:?}", e);
                                }
                                let _ = self.ack_message(&stream.key, &msg_id).await;
                            }
                        }
                    }
                }
                Err(e) => {
                    let msg = e.to_string();
                    if !msg.contains("nil") && !msg.contains("Null") {
                        tracing::warn!("Stream consumer error: {:?}", e);
                        tokio::time::sleep(Duration::from_millis(500)).await;
                    }
                }
            }
        }
    }

    async fn ack_message(&self, key: &str, id: &str) -> Result<(), BroadcastError> {
        let mut conn = self.redis_pool.get().await
            .map_err(|e| BroadcastError::Redis(e.to_string()))?;

        let _: i64 = redis::cmd("XACK")
            .arg(key)
            .arg(&self.group_name)
            .arg(id)
            .query_async(&mut *conn)
            .await
            .map_err(|e| BroadcastError::Redis(e.to_string()))?;

        Ok(())
    }

    async fn handle_message(
        &self,
        document_id: Uuid,
        message: &redis::streams::StreamId,
    ) -> Result<(), BroadcastError> {
        let node_id: String = message
            .get("node_id")
            .unwrap_or_default();
        let payload: Vec<u8> = message
            .get("payload")
            .unwrap_or_default();

        if payload.is_empty() {
            return Ok(());
        }

        let event: BroadcastEvent = serde_json::from_slice(&payload)?;

        match &event {
            BroadcastEvent::Op { sequence, op } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::Op {
                        sequence: *sequence,
                        op: op.clone(),
                    },
                    None,
                );
            }
            BroadcastEvent::Presence { user_id, update } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::Presence {
                        user_id: user_id.clone(),
                        update: update.clone(),
                    },
                    None,
                );
            }
            BroadcastEvent::UserJoined { user } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::UserJoined { user: user.clone() },
                    None,
                );
            }
            BroadcastEvent::UserLeft { user_id, reason } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::UserLeft {
                        user_id: user_id.clone(),
                        reason: reason.clone(),
                    },
                    None,
                );
            }
            BroadcastEvent::Cursor { user_id, position } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::Cursor {
                        user_id: user_id.clone(),
                        position: position.clone(),
                    },
                    None,
                );
            }
            BroadcastEvent::Selection { user_id, range } => {
                self.ws_manager.broadcast_to_room(
                    &document_id,
                    crate::ws::WsMessage::Selection {
                        user_id: user_id.clone(),
                        range: range.clone(),
                    },
                    None,
                );
            }
        }

        let _ = node_id;
        Ok(())
    }
}

fn extract_doc_id(key: &str, prefix: &str) -> Option<Uuid> {
    let stream_prefix = format!("{}stream:", prefix);
    let suffix = key.strip_prefix(&stream_prefix)?;
    Uuid::parse_str(suffix).ok()
}

#[derive(Debug, thiserror::Error)]
pub enum BroadcastError {
    #[error("Redis error: {0}")]
    Redis(String),

    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),
}
