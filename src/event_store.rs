use crate::types::{AppError, Event, Snapshot};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{de::DeserializeOwned, Serialize};
use sqlx::{PgPool, Row};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use uuid::Uuid;

pub struct EventStore {
    db_pool: PgPool,
    projections: Arc<DashMap<String, ProjectionState>>,
    subscribers: Arc<DashMap<String, Vec<tokio::sync::mpsc::Sender<Event>>>>,
    snapshot_interval: u64,
}

struct ProjectionState {
    name: String,
    version: u64,
    state: serde_json::Value,
    last_updated: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ProjectionInfo {
    pub name: String,
    pub version: u64,
    pub last_updated: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
pub struct TimeTravelQueryResult<T> {
    pub state: T,
    pub version: u64,
    pub timestamp: DateTime<Utc>,
    pub events_replayed: u64,
}

impl EventStore {
    pub fn new(db_pool: PgPool) -> Self {
        Self {
            db_pool,
            projections: Arc::new(DashMap::new()),
            subscribers: Arc::new(DashMap::new()),
            snapshot_interval: 100,
        }
    }

    pub async fn init_database(&self) -> Result<(), AppError> {
        sqlx::query!(
            r#"
            CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY,
                event_type TEXT NOT NULL,
                aggregate_id TEXT NOT NULL,
                version BIGINT NOT NULL,
                payload JSONB NOT NULL,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE (aggregate_id, version)
            );
            CREATE INDEX IF NOT EXISTS idx_events_aggregate ON events(aggregate_id, version);
            CREATE INDEX IF NOT EXISTS idx_events_type ON events(event_type);
            CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);
            "#
        )
        .execute(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建事件表失败: {}", e)))?;

        sqlx::query!(
            r#"
            CREATE TABLE IF NOT EXISTS snapshots (
                snapshot_id TEXT PRIMARY KEY,
                aggregate_id TEXT NOT NULL,
                version BIGINT NOT NULL,
                state JSONB NOT NULL,
                timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE (aggregate_id, version)
            );
            CREATE INDEX IF NOT EXISTS idx_snapshots_aggregate ON snapshots(aggregate_id, version);
            "#
        )
        .execute(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建快照表失败: {}", e)))?;

        sqlx::query!(
            r#"
            CREATE TABLE IF NOT EXISTS projections (
                name TEXT PRIMARY KEY,
                version BIGINT NOT NULL DEFAULT 0,
                state JSONB NOT NULL DEFAULT '{}'::jsonb,
                last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            "#
        )
        .execute(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建投影表失败: {}", e)))?;

        Ok(())
    }

    pub async fn append_event(&self, event: &Event) -> Result<Event, AppError> {
        let metadata_json = serde_json::to_value(&event.metadata)
            .map_err(|e| AppError::InternalError(format!("序列化元数据失败: {}", e)))?;

        let result = sqlx::query!(
            r#"
            INSERT INTO events (event_id, event_type, aggregate_id, version, payload, metadata, timestamp)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING event_id, event_type, aggregate_id, version, payload, metadata, timestamp
            "#,
            event.event_id,
            event.event_type,
            event.aggregate_id,
            event.version as i64,
            &event.payload,
            metadata_json,
            event.timestamp,
        )
        .fetch_one(&self.db_pool)
        .await
        .map_err(|e| {
            if e.to_string().contains("duplicate key") {
                AppError::Conflict(format!("事件版本冲突: aggregate_id={}, version={}", 
                    event.aggregate_id, event.version))
            } else {
                AppError::InternalError(format!("存储事件失败: {}", e))
            }
        })?;

        let stored_event = Event {
            event_id: result.event_id,
            event_type: result.event_type,
            aggregate_id: result.aggregate_id,
            version: result.version as u64,
            payload: result.payload,
            metadata: serde_json::from_value(result.metadata).unwrap_or_default(),
            timestamp: result.timestamp,
        };

        if stored_event.version % self.snapshot_interval == 0 {
            let state = self.load_aggregate_state(&stored_event.aggregate_id, Some(stored_event.version)).await?;
            self.create_snapshot(&stored_event.aggregate_id, stored_event.version, &state).await?;
        }

        self.notify_subscribers(&stored_event).await;

        Ok(stored_event)
    }

    pub async fn get_events(
        &self,
        aggregate_id: &str,
        from_version: Option<u64>,
        to_version: Option<u64>,
        limit: Option<i64>,
    ) -> Result<Vec<Event>, AppError> {
        let from = from_version.unwrap_or(0) as i64;
        let to = to_version.unwrap_or(u64::MAX) as i64;
        let lim = limit.unwrap_or(1000);

        let rows = sqlx::query!(
            r#"
            SELECT event_id, event_type, aggregate_id, version, payload, metadata, timestamp
            FROM events 
            WHERE aggregate_id = $1 AND version >= $2 AND version <= $3
            ORDER BY version ASC
            LIMIT $4
            "#,
            aggregate_id,
            from,
            to,
            lim,
        )
        .fetch_all(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("查询事件失败: {}", e)))?;

        let events = rows
            .into_iter()
            .map(|row| Event {
                event_id: row.event_id,
                event_type: row.event_type,
                aggregate_id: row.aggregate_id,
                version: row.version as u64,
                payload: row.payload,
                metadata: serde_json::from_value(row.metadata).unwrap_or_default(),
                timestamp: row.timestamp,
            })
            .collect();

        Ok(events)
    }

    pub async fn create_snapshot(
        &self,
        aggregate_id: &str,
        version: u64,
        state: &serde_json::Value,
    ) -> Result<Snapshot, AppError> {
        let snapshot_id = format!("snap_{}", Uuid::new_v4().to_string().replace("-", ""));

        let result = sqlx::query!(
            r#"
            INSERT INTO snapshots (snapshot_id, aggregate_id, version, state, timestamp)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (aggregate_id, version) DO NOTHING
            RETURNING snapshot_id, aggregate_id, version, state, timestamp
            "#,
            snapshot_id,
            aggregate_id,
            version as i64,
            state,
            Utc::now(),
        )
        .fetch_optional(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建快照失败: {}", e)))?;

        match result {
            Some(row) => Ok(Snapshot {
                snapshot_id: row.snapshot_id,
                aggregate_id: row.aggregate_id,
                version: row.version as u64,
                state: row.state,
                timestamp: row.timestamp,
            }),
            None => {
                let existing = sqlx::query!(
                    r#"
                    SELECT snapshot_id, aggregate_id, version, state, timestamp
                    FROM snapshots WHERE aggregate_id = $1 AND version = $2
                    "#,
                    aggregate_id,
                    version as i64,
                )
                .fetch_one(&self.db_pool)
                .await
                .map_err(|e| AppError::InternalError(format!("查询快照失败: {}", e)))?;

                Ok(Snapshot {
                    snapshot_id: existing.snapshot_id,
                    aggregate_id: existing.aggregate_id,
                    version: existing.version as u64,
                    state: existing.state,
                    timestamp: existing.timestamp,
                })
            }
        }
    }

    pub async fn get_latest_snapshot(
        &self,
        aggregate_id: &str,
    ) -> Result<Option<Snapshot>, AppError> {
        let result = sqlx::query!(
            r#"
            SELECT snapshot_id, aggregate_id, version, state, timestamp
            FROM snapshots 
            WHERE aggregate_id = $1
            ORDER BY version DESC
            LIMIT 1
            "#,
            aggregate_id,
        )
        .fetch_optional(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("查询快照失败: {}", e)))?;

        Ok(result.map(|row| Snapshot {
            snapshot_id: row.snapshot_id,
            aggregate_id: row.aggregate_id,
            version: row.version as u64,
            state: row.state,
            timestamp: row.timestamp,
        }))
    }

    pub async fn load_aggregate_state(
        &self,
        aggregate_id: &str,
        to_version: Option<u64>,
    ) -> Result<serde_json::Value, AppError> {
        let snapshot = self.get_latest_snapshot(aggregate_id).await?;
        let (mut state, start_version) = match snapshot {
            Some(snap) => (snap.state, snap.version + 1),
            None => (serde_json::json!({}), 1),
        };

        let end_version = to_version.unwrap_or(u64::MAX);
        if start_version <= end_version {
            let events = self.get_events(aggregate_id, Some(start_version), to_version, None).await?;
            for event in events {
                state = apply_event(state, &event);
            }
        }

        Ok(state)
    }

    pub async fn time_travel_query<T: DeserializeOwned>(
        &self,
        aggregate_id: &str,
        target_time: DateTime<Utc>,
    ) -> Result<TimeTravelQueryResult<T>, AppError> {
        let rows = sqlx::query!(
            r#"
            SELECT snapshot_id, aggregate_id, version, state, timestamp
            FROM snapshots 
            WHERE aggregate_id = $1 AND timestamp <= $2
            ORDER BY version DESC
            LIMIT 1
            "#,
            aggregate_id,
            target_time,
        )
        .fetch_optional(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("时间旅行查询快照失败: {}", e)))?;

        let (mut state, start_version, mut last_timestamp) = match rows {
            Some(row) => (row.state, row.version as u64 + 1, row.timestamp),
            None => (serde_json::json!({}), 1, Utc::now()),
        };

        let event_rows = sqlx::query!(
            r#"
            SELECT event_id, event_type, aggregate_id, version, payload, metadata, timestamp
            FROM events 
            WHERE aggregate_id = $1 AND timestamp <= $2 AND version >= $3
            ORDER BY version ASC
            "#,
            aggregate_id,
            target_time,
            start_version as i64,
        )
        .fetch_all(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("时间旅行查询事件失败: {}", e)))?;

        let mut events_replayed = 0;
        let mut final_version = start_version.saturating_sub(1);

        for row in event_rows {
            let event = Event {
                event_id: row.event_id,
                event_type: row.event_type,
                aggregate_id: row.aggregate_id,
                version: row.version as u64,
                payload: row.payload,
                metadata: serde_json::from_value(row.metadata).unwrap_or_default(),
                timestamp: row.timestamp,
            };
            state = apply_event(state, &event);
            final_version = event.version;
            last_timestamp = event.timestamp;
            events_replayed += 1;
        }

        let typed_state: T = serde_json::from_value(state)
            .map_err(|e| AppError::InternalError(format!("反序列化状态失败: {}", e)))?;

        Ok(TimeTravelQueryResult {
            state: typed_state,
            version: final_version,
            timestamp: last_timestamp,
            events_replayed,
        })
    }

    pub async fn rebuild_projection<F>(
        &self,
        projection_name: &str,
        mut apply: F,
    ) -> Result<(), AppError>
    where
        F: FnMut(serde_json::Value, &Event) -> serde_json::Value,
    {
        tracing::info!("开始重建投影: {}", projection_name);

        let saved_state = sqlx::query!(
            r#"SELECT name, version, state, last_updated FROM projections WHERE name = $1"#,
            projection_name,
        )
        .fetch_optional(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("查询投影状态失败: {}", e)))?;

        let (mut state, mut start_version) = match saved_state {
            Some(row) => (row.state, row.version as u64 + 1),
            None => (serde_json::json!({}), 1),
        };

        let mut last_processed_version = start_version.saturating_sub(1);
        let batch_size = 1000;
        let mut total_processed = 0;

        loop {
            let events = sqlx::query!(
                r#"
                SELECT event_id, event_type, aggregate_id, version, payload, metadata, timestamp
                FROM events 
                WHERE version >= $1
                ORDER BY version ASC
                LIMIT $2
                "#,
                start_version as i64,
                batch_size,
            )
            .fetch_all(&self.db_pool)
            .await
            .map_err(|e| AppError::InternalError(format!("批量查询事件失败: {}", e)))?;

            if events.is_empty() {
                break;
            }

            for row in events {
                let event = Event {
                    event_id: row.event_id,
                    event_type: row.event_type,
                    aggregate_id: row.aggregate_id,
                    version: row.version as u64,
                    payload: row.payload,
                    metadata: serde_json::from_value(row.metadata).unwrap_or_default(),
                    timestamp: row.timestamp,
                };
                state = apply(state, &event);
                last_processed_version = event.version;
                total_processed += 1;
            }

            start_version = last_processed_version + 1;

            sqlx::query!(
                r#"
                INSERT INTO projections (name, version, state, last_updated)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (name) DO UPDATE SET
                    version = EXCLUDED.version,
                    state = EXCLUDED.state,
                    last_updated = EXCLUDED.last_updated
                "#,
                projection_name,
                last_processed_version as i64,
                &state,
                Utc::now(),
            )
            .execute(&self.db_pool)
            .await
            .map_err(|e| AppError::InternalError(format!("保存投影状态失败: {}", e)))?;
        }

        self.projections.insert(
            projection_name.to_string(),
            ProjectionState {
                name: projection_name.to_string(),
                version: last_processed_version,
                state: state.clone(),
                last_updated: Utc::now(),
            },
        );

        tracing::info!(
            "投影重建完成: {}, 处理事件数: {}, 最终版本: {}",
            projection_name,
            total_processed,
            last_processed_version
        );

        Ok(())
    }

    pub fn get_projection_state<T: DeserializeOwned>(
        &self,
        projection_name: &str,
    ) -> Result<Option<T>, AppError> {
        self.projections
            .get(projection_name)
            .map(|state| {
                serde_json::from_value(state.state.clone())
                    .map_err(|e| AppError::InternalError(format!("反序列化投影状态失败: {}", e)))
            })
            .transpose()
    }

    pub fn list_projections(&self) -> Vec<ProjectionInfo> {
        self.projections
            .iter()
            .map(|entry| ProjectionInfo {
                name: entry.name.clone(),
                version: entry.version,
                last_updated: entry.last_updated,
            })
            .collect()
    }

    pub async fn subscribe(
        &self,
        event_type: &str,
        sender: tokio::sync::mpsc::Sender<Event>,
    ) {
        self.subscribers
            .entry(event_type.to_string())
            .or_default()
            .push(sender);
    }

    async fn notify_subscribers(&self, event: &Event) {
        if let Some(senders) = self.subscribers.get(&event.event_type) {
            for sender in senders.iter() {
                let _ = sender.send(event.clone()).await;
            }
        }
        
        if let Some(senders) = self.subscribers.get("*") {
            for sender in senders.iter() {
                let _ = sender.send(event.clone()).await;
            }
        }
    }

    pub async fn get_aggregate_version(&self, aggregate_id: &str) -> Result<u64, AppError> {
        let result = sqlx::query!(
            r#"SELECT COALESCE(MAX(version), 0) as max_version FROM events WHERE aggregate_id = $1"#,
            aggregate_id,
        )
        .fetch_one(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("查询聚合版本失败: {}", e)))?;

        Ok(result.max_version.unwrap_or(0) as u64)
    }

    pub async fn create_event(
        &self,
        aggregate_id: &str,
        event_type: &str,
        payload: serde_json::Value,
        metadata: Option<HashMap<String, String>>,
    ) -> Result<Event, AppError> {
        let next_version = self.get_aggregate_version(aggregate_id).await? + 1;
        
        let event = Event {
            event_id: format!("evt_{}", Uuid::new_v4().to_string().replace("-", "")),
            event_type: event_type.to_string(),
            aggregate_id: aggregate_id.to_string(),
            version: next_version,
            payload,
            metadata: metadata.unwrap_or_default(),
            timestamp: Utc::now(),
        };

        self.append_event(&event).await
    }
}

fn apply_event(mut state: serde_json::Value, event: &Event) -> serde_json::Value {
    if let Some(obj) = state.as_object_mut() {
        let events = obj
            .entry("events")
            .or_insert_with(|| serde_json::Value::Array(Vec::new()))
            .as_array_mut()
            .unwrap();
        events.push(serde_json::json!({
            "type": event.event_type,
            "version": event.version,
            "timestamp": event.timestamp.to_rfc3339(),
        }));

        obj.insert(
            "last_event_type".to_string(),
            serde_json::Value::String(event.event_type.clone()),
        );
        obj.insert(
            "last_event_version".to_string(),
            serde_json::Value::Number(serde_json::Number::from(event.version)),
        );
        obj.insert(
            "last_updated".to_string(),
            serde_json::Value::String(event.timestamp.to_rfc3339()),
        );
    }
    state
}
