use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use parking_lot::Mutex;

use dashmap::{DashMap, DashSet};
use metrics::counter;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio::sync::mpsc::error::TrySendError;
use tokio_tungstenite::tungstenite::protocol::Message;
use uuid::Uuid;

use collab_crdt::{Op, YataDocument};
use crate::presence::{PresenceUpdate, CursorPosition, SelectionRange};
use crate::config::AppConfig;

pub const WS_CHANNEL_CAPACITY: usize = 256;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum WsMessage {
    Hello {
        session_id: Uuid,
        client_id: u64,
        user_id: String,
        document_id: Uuid,
        resume_from: Option<u64>,
    },
    Welcome {
        session_id: Uuid,
        client_id: u64,
        server_time: i64,
        vector_clock: HashMap<u64, u32>,
        content: Option<String>,
        missing_ops: Vec<Op>,
    },
    Op {
        sequence: u64,
        op: Op,
    },
    Ack {
        sequence: u64,
        applied: bool,
    },
    Presence {
        user_id: String,
        update: PresenceUpdate,
    },
    Cursor {
        user_id: String,
        position: CursorPosition,
    },
    Selection {
        user_id: String,
        range: SelectionRange,
    },
    SnapshotRequest {
        from_version: Option<u64>,
    },
    SnapshotResponse {
        version: u64,
        ops: Vec<Op>,
        full_snapshot: bool,
    },
    Ping {
        timestamp: i64,
    },
    Pong {
        timestamp: i64,
        server_time: i64,
    },
    Error {
        code: String,
        message: String,
    },
    Goodbye {
        reason: String,
    },
    RoomJoined {
        document_id: Uuid,
        users: Vec<RoomUser>,
    },
    UserJoined {
        user: RoomUser,
    },
    UserLeft {
        user_id: String,
        reason: String,
    },
    BatchOps {
        sequence: u64,
        ops: Vec<Op>,
    },
    SyncRequest {
        vector_clock: HashMap<u64, u32>,
    },
    SyncResponse {
        server_version: u64,
        server_vector_clock: HashMap<u64, u32>,
        client_missing_ops: Vec<Op>,
        server_missing_clients: Vec<(u64, u32)>,
    },
    BatchSubmit {
        ops: Vec<Op>,
    },
    BatchAck {
        applied: u64,
        duplicates: u64,
        server_version: u64,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoomUser {
    pub user_id: String,
    pub client_id: u64,
    pub joined_at: i64,
    pub session_id: Uuid,
}

#[derive(Debug, Clone)]
pub struct ConnectionInfo {
    pub session_id: Uuid,
    pub client_id: u64,
    pub user_id: String,
    pub document_id: Uuid,
    pub sender: mpsc::Sender<WsMessage>,
    pub channel_capacity: usize,
    pub last_pong: Arc<Mutex<i64>>,
    pub last_seq: Arc<Mutex<u64>>,
    pub connected_at: chrono::DateTime<chrono::Utc>,
    pub disconnected_at: Arc<Mutex<Option<chrono::DateTime<chrono::Utc>>>>,
}

#[derive(Debug, Clone)]
pub struct ResumeSession {
    pub session_id: Uuid,
    pub client_id: u64,
    pub user_id: String,
    pub document_id: Uuid,
    pub pending_ops: Vec<(u64, Op)>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone)]
pub struct Room {
    pub document_id: Uuid,
    pub connections: DashSet<Uuid>,
    pub document: Arc<parking_lot::RwLock<YataDocument>>,
    pub ops_history: Arc<Mutex<Vec<(u64, Op)>>>,
    pub current_version: Arc<Mutex<u64>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub last_activity: Arc<Mutex<chrono::DateTime<chrono::Utc>>>,
    pub seen_ops: Arc<Mutex<HashSet<(u64, u32)>>>,
}

impl Room {
    fn new(document_id: Uuid, client_id: u64) -> Self {
        Self {
            document_id,
            connections: DashSet::new(),
            document: Arc::new(parking_lot::RwLock::new(YataDocument::new(document_id, client_id))),
            ops_history: Arc::new(Mutex::new(Vec::new())),
            current_version: Arc::new(Mutex::new(0)),
            created_at: chrono::Utc::now(),
            last_activity: Arc::new(Mutex::new(chrono::Utc::now())),
            seen_ops: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    pub fn add_op(&self, op: Op) -> u64 {
        let mut ver = self.current_version.lock();
        *ver += 1;
        let seq = *ver;
        let key = op.dedup_key();
        self.seen_ops.lock().insert(key);
        self.ops_history.lock().push((seq, op));
        *self.last_activity.lock() = chrono::Utc::now();
        seq
    }

    pub fn add_op_dedup(&self, op: Op) -> (u64, bool) {
        let key = op.dedup_key();
        let mut seen = self.seen_ops.lock();
        if seen.contains(&key) {
            (0, false)
        } else {
            seen.insert(key);
            drop(seen);
            let mut ver = self.current_version.lock();
            *ver += 1;
            let seq = *ver;
            self.ops_history.lock().push((seq, op));
            *self.last_activity.lock() = chrono::Utc::now();
            (seq, true)
        }
    }

    pub fn has_op(&self, client: u64, clock: u32) -> bool {
        self.seen_ops.lock().contains(&(client, clock))
    }

    pub fn get_ops_since(&self, from: u64) -> Vec<(u64, Op)> {
        let history = self.ops_history.lock();
        history.iter()
            .filter(|(s, _)| *s > from)
            .cloned()
            .collect()
    }

    pub fn get_ops_for_clients(&self, client_clocks: &HashMap<u64, u32>) -> Vec<Op> {
        let history = self.ops_history.lock();
        history.iter()
            .filter(|(_, op)| {
                let key = op.dedup_key();
                let last_seen = client_clocks.get(&key.0).copied().unwrap_or(0);
                key.1 > last_seen
            })
            .map(|(_, op)| op.clone())
            .collect()
    }

    pub fn missing_clients(&self, remote_clock: &HashMap<u64, u32>) -> Vec<(u64, u32)> {
        let doc = self.document.read();
        let server_clock = doc.vector_clock();
        let mut missing = Vec::new();
        for (client, clock) in server_clock {
            let remote = remote_clock.get(client).copied().unwrap_or(0);
            if *clock > remote {
                missing.push((*client, remote + 1));
            }
        }
        missing
    }
}

#[derive(Debug, Clone)]
pub struct ConnectionManager {
    pub rooms: Arc<DashMap<Uuid, Arc<Room>>>,
    connections: Arc<DashMap<Uuid, ConnectionInfo>>,
    resume_sessions: Arc<DashMap<Uuid, ResumeSession>>,
    pub subscribed_rooms: Arc<DashSet<Uuid>>,
    config: AppConfig,
}

impl ConnectionManager {
    pub fn new(config: AppConfig) -> Self {
        Self {
            rooms: Arc::new(DashMap::new()),
            connections: Arc::new(DashMap::new()),
            resume_sessions: Arc::new(DashMap::new()),
            subscribed_rooms: Arc::new(DashSet::new()),
            config,
        }
    }

    pub fn get_or_create_room(&self, document_id: Uuid, client_id: u64) -> Arc<Room> {
        self.rooms
            .entry(document_id)
            .or_insert_with(|| Arc::new(Room::new(document_id, client_id)))
            .clone()
    }

    pub fn get_room(&self, document_id: &Uuid) -> Option<Arc<Room>> {
        self.rooms.get(document_id).map(|r| r.clone())
    }

    pub fn remove_connection(&self, session_id: &Uuid) -> Option<ConnectionInfo> {
        if let Some(info) = self.connections.remove(session_id) {
            let info = info.1;

            if let Some(room) = self.rooms.get(&info.document_id) {
                room.connections.remove(session_id);
                self.broadcast_to_room(
                    &info.document_id,
                    WsMessage::UserLeft {
                        user_id: info.user_id.clone(),
                        reason: "disconnected".to_string(),
                    },
                    Some(session_id),
                );

                if room.connections.is_empty() {
                    let expires_at = chrono::Utc::now()
                        + chrono::Duration::seconds(self.config.websocket.session_resume_window_secs as i64);

                    let pending_ops = room.ops_history.lock().clone();
                    let resume = ResumeSession {
                        session_id: info.session_id,
                        client_id: info.client_id,
                        user_id: info.user_id.clone(),
                        document_id: info.document_id,
                        pending_ops,
                        created_at: chrono::Utc::now(),
                        expires_at,
                    };
                    self.resume_sessions.insert(*session_id, resume);
                }
            }
            Some(info)
        } else {
            None
        }
    }

    pub fn register_connection(
        &self,
        session_id: Uuid,
        client_id: u64,
        user_id: String,
        document_id: Uuid,
        sender: mpsc::Sender<WsMessage>,
    ) -> ConnectionInfo {
        let info = ConnectionInfo {
            session_id,
            client_id,
            user_id,
            document_id,
            sender,
            channel_capacity: WS_CHANNEL_CAPACITY,
            last_pong: Arc::new(Mutex::new(chrono::Utc::now().timestamp_millis())),
            last_seq: Arc::new(Mutex::new(0)),
            connected_at: chrono::Utc::now(),
            disconnected_at: Arc::new(Mutex::new(None)),
        };
        self.connections.insert(session_id, info.clone());
        info
    }

    pub fn broadcast_to_room(&self, document_id: &Uuid, message: WsMessage, exclude: Option<&Uuid>) {
        counter!("collab_broadcast_messages_total").increment(1);
        if let Some(room) = self.rooms.get(document_id) {
            for session_id in room.connections.iter() {
                if exclude.map(|e| e == session_id.key()).unwrap_or(false) {
                    continue;
                }
                if let Some(conn) = self.connections.get(session_id.key()) {
                    match conn.sender.try_send(message.clone()) {
                        Ok(_) => {}
                        Err(TrySendError::Full(_)) => {
                            counter!("collab_broadcast_dropped_total").increment(1);
                            counter!("collab_slow_consumers_kicked_total").increment(1);
                            tracing::warn!(
                                "Kicking slow consumer {} from room {} (channel full)",
                                session_id.key(),
                                document_id
                            );
                            drop(conn);
                            self.remove_connection(session_id.key());
                        }
                        Err(TrySendError::Closed(_)) => {
                            drop(conn);
                            self.remove_connection(session_id.key());
                        }
                    }
                }
            }
        }
    }

    pub fn send_to_session(&self, session_id: &Uuid, message: WsMessage) -> bool {
        if let Some(conn) = self.connections.get(session_id) {
            match conn.sender.try_send(message) {
                Ok(_) => true,
                Err(TrySendError::Full(_)) => {
                    tracing::warn!("send_to_session channel full for session {}", session_id);
                    false
                }
                Err(TrySendError::Closed(_)) => false,
            }
        } else {
            false
        }
    }

    pub fn room_users(&self, document_id: &Uuid) -> Vec<RoomUser> {
        if let Some(room) = self.rooms.get(document_id) {
            room.connections
                .iter()
                .filter_map(|sid| self.connections.get(sid.key()))
                .map(|c| RoomUser {
                    user_id: c.user_id.clone(),
                    client_id: c.client_id,
                    joined_at: c.connected_at.timestamp_millis(),
                    session_id: c.session_id,
                })
                .collect()
        } else {
            Vec::new()
        }
    }

    pub fn get_resume_session(&self, session_id: &Uuid) -> Option<ResumeSession> {
        self.resume_sessions.get(session_id).map(|r| r.clone())
    }

    pub fn remove_resume_session(&self, session_id: &Uuid) {
        self.resume_sessions.remove(session_id);
    }

    pub fn cleanup_expired_sessions(&self) {
        let now = chrono::Utc::now();
        self.resume_sessions.retain(|_, s| s.expires_at > now);
    }

    pub fn total_connections(&self) -> usize {
        self.connections.len()
    }

    pub fn total_rooms(&self) -> usize {
        self.rooms.len()
    }

    pub fn active_documents(&self) -> HashSet<Uuid> {
        self.rooms.iter().map(|r| *r.key()).collect()
    }

    pub fn get_connection(&self, session_id: &Uuid) -> Option<ConnectionInfo> {
        self.connections.get(session_id).map(|c| c.clone())
    }

    pub fn update_pong(&self, session_id: &Uuid) {
        if let Some(conn) = self.connections.get(session_id) {
            *conn.last_pong.lock() = chrono::Utc::now().timestamp_millis();
        }
    }

    pub fn check_stale_connections(&self) -> Vec<Uuid> {
        let now = chrono::Utc::now().timestamp_millis();
        let timeout = self.config.websocket.client_timeout_secs * 1000;
        let mut stale = Vec::new();

        for conn in self.connections.iter() {
            let last_pong = *conn.last_pong.lock();
            if now - last_pong > timeout as i64 {
                stale.push(*conn.key());
            }
        }

        stale
    }
}

impl WsMessage {
    pub fn to_ws(&self) -> Message {
        let json = serde_json::to_string(self).unwrap();
        Message::Text(json)
    }

    pub fn from_ws(msg: &Message) -> Result<Self, String> {
        match msg {
            Message::Text(text) => serde_json::from_str(text).map_err(|e| e.to_string()),
            Message::Binary(data) => serde_json::from_slice(data).map_err(|e| e.to_string()),
            _ => Err("Unsupported message type".into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc::error::TrySendError;

    #[test]
    fn test_bounded_channel_try_send_full() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let (tx, mut rx) = mpsc::channel::<i32>(2);

            assert!(tx.try_send(1).is_ok());
            assert!(tx.try_send(2).is_ok());

            let result = tx.try_send(3);
            assert!(result.is_err());
            match result.unwrap_err() {
                TrySendError::Full(3) => {}
                _ => panic!("Expected TrySendError::Full(3)"),
            }

            assert_eq!(rx.recv().await, Some(1));
            assert!(tx.try_send(3).is_ok());
        });
    }

    #[test]
    fn test_broadcast_slow_consumer_kicked() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let config = crate::config::AppConfig::default();
            let manager = ConnectionManager::new(config);
            let doc_id = Uuid::new_v4();
            let session_id = Uuid::new_v4();

            let (tx, _rx) = mpsc::channel::<WsMessage>(1);
            manager.register_connection(
                session_id,
                1,
                "user1".to_string(),
                doc_id,
                tx,
            );

            let room = manager.get_or_create_room(doc_id, 1);
            room.connections.insert(session_id);

            let msg = WsMessage::Ping { timestamp: 0 };
            manager.broadcast_to_room(&doc_id, msg.clone(), None);

            assert!(manager.get_connection(&session_id).is_some(),
                "First broadcast should succeed (channel has capacity 1)");

            manager.broadcast_to_room(&doc_id, msg, None);

            assert!(manager.get_connection(&session_id).is_none(),
                "Second broadcast should fill channel and kick slow consumer");
        });
    }
}
