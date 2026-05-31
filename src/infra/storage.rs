use sqlx::{postgres::PgPoolOptions, PgPool};

use crate::infra::config::DatabaseConfig;
use crate::infra::error::{AppError, AppResult};

#[derive(Clone)]
pub struct Database {
    pub pool: PgPool,
}

impl Database {
    pub async fn new(config: &DatabaseConfig) -> AppResult<Self> {
        let pool = PgPoolOptions::new()
            .max_connections(config.max_connections)
            .min_connections(config.min_connections)
            .acquire_timeout(std::time::Duration::from_secs(config.acquire_timeout_seconds))
            .idle_timeout(std::time::Duration::from_secs(config.idle_timeout_seconds))
            .connect(&config.url)
            .await
            .map_err(|e| AppError::DatabaseError(format!("Failed to connect to database: {}", e)))?;

        Ok(Self { pool })
    }

    pub async fn run_migrations(&self) -> AppResult<()> {
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS entities (
                id TEXT PRIMARY KEY,
                entity_type TEXT NOT NULL,
                status TEXT NOT NULL,
                attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            CREATE TABLE IF NOT EXISTS configs (
                config_id TEXT PRIMARY KEY,
                namespace TEXT NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
                enabled BOOLEAN NOT NULL DEFAULT true,
                applied_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            CREATE TABLE IF NOT EXISTS run_instances (
                run_id TEXT PRIMARY KEY,
                entity_id TEXT NOT NULL,
                phase TEXT NOT NULL,
                progress DOUBLE PRECISION NOT NULL DEFAULT 0,
                started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                completed_at TIMESTAMPTZ,
                error_detail TEXT,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            );

            CREATE TABLE IF NOT EXISTS snapshots (
                snapshot_id TEXT PRIMARY KEY,
                timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                metrics JSONB NOT NULL,
                dimensions JSONB NOT NULL DEFAULT '{}'::jsonb
            );

            CREATE TABLE IF NOT EXISTS audit_logs (
                log_id TEXT PRIMARY KEY,
                timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                resource_id TEXT,
                details JSONB,
                previous_hash TEXT,
                current_hash TEXT NOT NULL,
                block_height BIGINT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs(timestamp);
            CREATE INDEX IF NOT EXISTS idx_audit_logs_block_height ON audit_logs(block_height);
            "#,
        )
        .execute(&self.pool)
        .await
        .map_err(|e| AppError::DatabaseError(format!("Migration failed: {}", e)))?;

        Ok(())
    }
}
