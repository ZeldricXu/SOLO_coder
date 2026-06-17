use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{Executor, PgPool, Row, postgres::PgRow};
use thiserror::Error;
use uuid::Uuid;

use crate::crdt::Op;

#[derive(Error, Debug)]
pub enum StorageError {
    #[error("Database error: {0}")]
    Database(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("IO error: {0}")]
    Io(String),

    #[error("S3 error: {0}")]
    S3(String),

    #[error("Not found: {0}")]
    NotFound(String),
}

impl From<sqlx::Error> for StorageError {
    fn from(e: sqlx::Error) -> Self {
        StorageError::Database(e.to_string())
    }
}

impl From<std::io::Error> for StorageError {
    fn from(e: std::io::Error) -> Self {
        StorageError::Io(e.to_string())
    }
}

impl From<serde_json::Error> for StorageError {
    fn from(e: serde_json::Error) -> Self {
        StorageError::Serialization(e.to_string())
    }
}

impl From<bincode::Error> for StorageError {
    fn from(e: bincode::Error) -> Self {
        StorageError::Serialization(e.to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct DocumentMetadata {
    pub id: Uuid,
    pub title: String,
    pub owner_id: String,
    pub content_preview: Option<String>,
    pub current_version: i64,
    pub last_snapshot_version: Option<i64>,
    pub last_modified_at: DateTime<Utc>,
    pub last_modified_by: String,
    pub created_at: DateTime<Utc>,
    pub is_archived: bool,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperationLogEntry {
    pub id: Uuid,
    pub document_id: Uuid,
    pub client_id: i64,
    pub user_id: String,
    pub session_id: Option<Uuid>,
    pub op_type: String,
    pub yata_client: i64,
    pub yata_clock: i64,
    pub op_payload: serde_json::Value,
    pub op_binary: Option<Vec<u8>>,
    pub sequence: i64,
    pub timestamp: DateTime<Utc>,
    pub node_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryOplogParams {
    pub document_id: Uuid,
    pub from_time: Option<DateTime<Utc>>,
    pub to_time: Option<DateTime<Utc>>,
    pub user_id: Option<String>,
    pub sequence_from: Option<i64>,
    pub sequence_to: Option<i64>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotRecord {
    pub id: Uuid,
    pub document_id: Uuid,
    pub version: i64,
    pub ops_count: i64,
    pub size_bytes: i64,
    pub storage_backend: String,
    pub storage_path: String,
    pub checksum: String,
    pub created_at: DateTime<Utc>,
    pub created_by: String,
    pub compressed: bool,
    pub vector_clock: serde_json::Value,
}

impl<'r> sqlx::FromRow<'r, PgRow> for SnapshotRecord {
    fn from_row(row: &'r PgRow) -> Result<Self, sqlx::Error> {
        use sqlx::Row;
        Ok(Self {
            id: row.try_get("id")?,
            document_id: row.try_get("document_id")?,
            version: row.try_get("version")?,
            ops_count: row.try_get("ops_count")?,
            size_bytes: row.try_get("size_bytes")?,
            storage_backend: row.try_get("storage_backend")?,
            storage_path: row.try_get("storage_path")?,
            checksum: row.try_get("checksum")?,
            created_at: row.try_get("created_at")?,
            created_by: row.try_get("created_by")?,
            compressed: row.try_get("compressed")?,
            vector_clock: row.try_get("vector_clock")?,
        })
    }
}

pub struct OplogRepository {
    pool: PgPool,
}

impl OplogRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn init_schema(&self) -> Result<(), StorageError> {
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS documents (
                id UUID PRIMARY KEY,
                title VARCHAR(1024) NOT NULL DEFAULT 'Untitled',
                owner_id VARCHAR(255) NOT NULL,
                content_preview TEXT,
                current_version BIGINT NOT NULL DEFAULT 0,
                last_snapshot_version BIGINT,
                last_modified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                last_modified_by VARCHAR(255) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                is_archived BOOLEAN NOT NULL DEFAULT FALSE,
                tags TEXT[] NOT NULL DEFAULT '{}'
            );

            CREATE INDEX IF NOT EXISTS idx_documents_owner ON documents(owner_id);
            CREATE INDEX IF NOT EXISTS idx_documents_modified ON documents(last_modified_at DESC);

            CREATE TABLE IF NOT EXISTS operation_logs (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                client_id BIGINT NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                session_id UUID,
                op_type VARCHAR(32) NOT NULL,
                yata_client BIGINT NOT NULL,
                yata_clock BIGINT NOT NULL,
                op_payload JSONB NOT NULL,
                op_binary BYTEA,
                sequence BIGINT NOT NULL,
                timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                node_id VARCHAR(255)
            );

            CREATE INDEX IF NOT EXISTS idx_oplogs_doc_time ON operation_logs(document_id, timestamp DESC);
            CREATE INDEX IF NOT EXISTS idx_oplogs_doc_seq ON operation_logs(document_id, sequence);
            CREATE INDEX IF NOT EXISTS idx_oplogs_user ON operation_logs(user_id);
            CREATE INDEX IF NOT EXISTS idx_oplogs_yata ON operation_logs(document_id, yata_client, yata_clock);

            CREATE TABLE IF NOT EXISTS snapshots (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                version BIGINT NOT NULL,
                ops_count BIGINT NOT NULL,
                size_bytes BIGINT NOT NULL,
                storage_backend VARCHAR(32) NOT NULL,
                storage_path VARCHAR(2048) NOT NULL,
                checksum VARCHAR(128) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                created_by VARCHAR(255) NOT NULL,
                compressed BOOLEAN NOT NULL DEFAULT TRUE,
                vector_clock JSONB NOT NULL,
                UNIQUE(document_id, version)
            );

            CREATE INDEX IF NOT EXISTS idx_snapshots_doc ON snapshots(document_id, version DESC);

            CREATE TABLE IF NOT EXISTS document_permissions (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                user_id VARCHAR(255) NOT NULL,
                role VARCHAR(32) NOT NULL,
                granted_by VARCHAR(255),
                granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                expires_at TIMESTAMPTZ,
                UNIQUE(document_id, user_id)
            );

            CREATE INDEX IF NOT EXISTS idx_perms_user ON document_permissions(user_id);

            CREATE TABLE IF NOT EXISTS share_links (
                id UUID PRIMARY KEY,
                document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                created_by VARCHAR(255) NOT NULL,
                role VARCHAR(32) NOT NULL,
                token_hash VARCHAR(128) NOT NULL UNIQUE,
                expires_at TIMESTAMPTZ,
                max_uses INTEGER,
                use_count INTEGER NOT NULL DEFAULT 0,
                is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
                requires_email VARCHAR(255),
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            CREATE INDEX IF NOT EXISTS idx_shares_doc ON share_links(document_id);
            "#
        )
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn create_document(
        &self,
        id: Uuid,
        title: &str,
        owner_id: &str,
    ) -> Result<DocumentMetadata, StorageError> {
        let meta = sqlx::query_as::<_, DocumentMetadata>(
            r#"
            INSERT INTO documents (id, title, owner_id, last_modified_by)
            VALUES ($1, $2, $3, $3)
            ON CONFLICT (id) DO NOTHING
            RETURNING *
            "#
        )
        .bind(id)
        .bind(title)
        .bind(owner_id)
        .fetch_optional(&self.pool)
        .await?;

        if let Some(meta) = meta {
            Ok(meta)
        } else {
            self.get_document(id).await?
                .ok_or_else(|| StorageError::NotFound(format!("Document {}", id)))
        }
    }

    pub async fn get_document(&self, id: Uuid) -> Result<Option<DocumentMetadata>, StorageError> {
        let meta = sqlx::query_as::<_, DocumentMetadata>(
            "SELECT * FROM documents WHERE id = $1"
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(meta)
    }

    pub async fn update_document_version(
        &self,
        id: Uuid,
        new_version: i64,
        last_modified_by: &str,
        preview: Option<&str>,
    ) -> Result<(), StorageError> {
        sqlx::query(
            r#"
            UPDATE documents SET
                current_version = GREATEST(current_version, $2),
                last_modified_by = $3,
                last_modified_at = NOW(),
                content_preview = COALESCE($4, content_preview)
            WHERE id = $1
            "#
        )
        .bind(id)
        .bind(new_version)
        .bind(last_modified_by)
        .bind(preview)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn append_op(
        &self,
        op: &Op,
        sequence: u64,
        session_id: Option<Uuid>,
        user_id: &str,
    ) -> Result<(), StorageError> {
        let op_type = match &op.op_type {
            crate::crdt::OpType::Insert(_) => "insert",
            crate::crdt::OpType::Delete(_) => "delete",
        };

        let yata_id = op.yata_id();

        let op_payload = serde_json::to_value(&op.op_type)?;
        let op_binary = bincode::serialize(&op).ok();

        sqlx::query(
            r#"
            INSERT INTO operation_logs (
                document_id, client_id, user_id, session_id, op_type,
                yata_client, yata_clock, op_payload, op_binary, sequence
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            "#
        )
        .bind(op.document_id)
        .bind(op.client_id as i64)
        .bind(user_id)
        .bind(session_id)
        .bind(op_type)
        .bind(yata_id.client as i64)
        .bind(yata_id.clock as i64)
        .bind(op_payload)
        .bind(op_binary)
        .bind(sequence as i64)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn query_oplogs(
        &self,
        params: QueryOplogParams,
    ) -> Result<Vec<OperationLogEntry>, StorageError> {
        let mut sql = String::from("SELECT * FROM operation_logs WHERE document_id = $1");
        let mut bind_idx = 2i32;
        let mut conditions: Vec<String> = Vec::new();

        if params.from_time.is_some() {
            conditions.push(format!("timestamp >= ${}", bind_idx));
            bind_idx += 1;
        }
        if params.to_time.is_some() {
            conditions.push(format!("timestamp <= ${}", bind_idx));
            bind_idx += 1;
        }
        if params.user_id.is_some() {
            conditions.push(format!("user_id = ${}", bind_idx));
            bind_idx += 1;
        }
        if params.sequence_from.is_some() {
            conditions.push(format!("sequence >= ${}", bind_idx));
            bind_idx += 1;
        }
        if params.sequence_to.is_some() {
            conditions.push(format!("sequence <= ${}", bind_idx));
            bind_idx += 1;
        }

        if !conditions.is_empty() {
            sql.push_str(" AND ");
            sql.push_str(&conditions.join(" AND "));
        }

        sql.push_str(" ORDER BY sequence ASC");

        if let Some(limit) = params.limit {
            sql.push_str(&format!(" LIMIT ${}", bind_idx));
            bind_idx += 1;
        }
        if let Some(offset) = params.offset {
            sql.push_str(&format!(" OFFSET ${}", bind_idx));
        }

        let mut query = sqlx::query_as::<_, OperationLogEntry>(&sql).bind(params.document_id);
        if let Some(t) = params.from_time { query = query.bind(t); }
        if let Some(t) = params.to_time { query = query.bind(t); }
        if let Some(u) = &params.user_id { query = query.bind(u); }
        if let Some(s) = params.sequence_from { query = query.bind(s); }
        if let Some(s) = params.sequence_to { query = query.bind(s); }
        if let Some(l) = params.limit { query = query.bind(l); }
        if let Some(o) = params.offset { query = query.bind(o); }

        let results = query.fetch_all(&self.pool).await?;
        Ok(results)
    }

    pub async fn save_snapshot_record(&self, record: &SnapshotRecord) -> Result<(), StorageError> {
        sqlx::query(
            r#"
            INSERT INTO snapshots (
                id, document_id, version, ops_count, size_bytes,
                storage_backend, storage_path, checksum, created_by,
                compressed, vector_clock
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            ON CONFLICT (document_id, version) DO NOTHING
            "#
        )
        .bind(record.id)
        .bind(record.document_id)
        .bind(record.version)
        .bind(record.ops_count)
        .bind(record.size_bytes)
        .bind(&record.storage_backend)
        .bind(&record.storage_path)
        .bind(&record.checksum)
        .bind(&record.created_by)
        .bind(record.compressed)
        .bind(&record.vector_clock)
        .execute(&self.pool)
        .await?;

        sqlx::query(
            "UPDATE documents SET last_snapshot_version = $2 WHERE id = $1 AND (last_snapshot_version IS NULL OR last_snapshot_version < $2)"
        )
        .bind(record.document_id)
        .bind(record.version)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn get_latest_snapshot(
        &self,
        document_id: Uuid,
    ) -> Result<Option<SnapshotRecord>, StorageError> {
        let record = sqlx::query_as::<_, SnapshotRecord>(
            "SELECT * FROM snapshots WHERE document_id = $1 ORDER BY version DESC LIMIT 1"
        )
        .bind(document_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(record)
    }

    pub async fn get_snapshots(
        &self,
        document_id: Uuid,
        limit: i64,
    ) -> Result<Vec<SnapshotRecord>, StorageError> {
        let records = sqlx::query_as::<_, SnapshotRecord>(
            "SELECT * FROM snapshots WHERE document_id = $1 ORDER BY version DESC LIMIT $2"
        )
        .bind(document_id)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        Ok(records)
    }
}

impl sqlx::FromRow<'_, PgRow> for OperationLogEntry {
    fn from_row(row: &PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            id: row.try_get("id")?,
            document_id: row.try_get("document_id")?,
            client_id: row.try_get("client_id")?,
            user_id: row.try_get("user_id")?,
            session_id: row.try_get("session_id").ok(),
            op_type: row.try_get("op_type")?,
            yata_client: row.try_get("yata_client")?,
            yata_clock: row.try_get("yata_clock")?,
            op_payload: row.try_get("op_payload")?,
            op_binary: row.try_get("op_binary").ok(),
            sequence: row.try_get("sequence")?,
            timestamp: row.try_get("timestamp")?,
            node_id: row.try_get("node_id").ok(),
        })
    }
}
