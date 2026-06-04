use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{sqlite::SqlitePoolOptions, Pool, Sqlite, Row};
use std::path::Path;
use tracing::info;

use common::alert::{Alert, AlertSeverity, AlertStatus, DetectionMethod};
use common::metrics::{Label, Labels};

pub struct AlertStorage {
    pool: Pool<Sqlite>,
}

impl AlertStorage {
    pub async fn new<P: AsRef<Path>>(db_path: P) -> Result<Self> {
        let db_url = format!("sqlite:{}", db_path.as_ref().to_string_lossy());
        let pool = SqlitePoolOptions::new()
            .max_connections(10)
            .connect(&db_url)
            .await?;

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS alerts (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                severity TEXT NOT NULL,
                status TEXT NOT NULL,
                labels_json TEXT NOT NULL,
                annotations_json TEXT NOT NULL,
                starts_at TEXT NOT NULL,
                ends_at TEXT,
                detection_method TEXT NOT NULL,
                value REAL NOT NULL,
                threshold REAL,
                generator_url TEXT
            )
            "#,
        )
        .execute(&pool)
        .await?;

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS silences (
                id TEXT PRIMARY KEY,
                starts_at TEXT NOT NULL,
                ends_at TEXT NOT NULL,
                matchers_json TEXT NOT NULL,
                created_by TEXT NOT NULL,
                comment TEXT NOT NULL
            )
            "#,
        )
        .execute(&pool)
        .await?;

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS inhibit_rules (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                source_match_json TEXT NOT NULL,
                target_match_json TEXT NOT NULL,
                equal_json TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            "#,
        )
        .execute(&pool)
        .await?;

        info!("Alert storage initialized");
        Ok(Self { pool })
    }

    pub async fn save_alert(&self, alert: &Alert) -> Result<()> {
        let labels_json = serde_json::to_string(&alert.labels.to_btree())?;
        let annotations_json = serde_json::to_string(&alert.annotations)?;

        sqlx::query(
            r#"
            INSERT OR REPLACE INTO alerts
            (id, name, severity, status, labels_json, annotations_json,
             starts_at, ends_at, detection_method, value, threshold, generator_url)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(alert.id.to_string())
        .bind(&alert.name)
        .bind(alert.severity.as_str())
        .bind(format!("{:?}", alert.status))
        .bind(labels_json)
        .bind(annotations_json)
        .bind(alert.starts_at.to_rfc3339())
        .bind(alert.ends_at.map(|dt| dt.to_rfc3339()))
        .bind(format!("{:?}", alert.detection_method))
        .bind(alert.value)
        .bind(alert.threshold)
        .bind(alert.generator_url.as_ref())
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn get_alerts(&self, limit: i32) -> Result<Vec<Alert>> {
        let rows = sqlx::query(
            r#"SELECT * FROM alerts ORDER BY starts_at DESC LIMIT ?"#
        )
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        let mut alerts = Vec::new();
        for row in rows {
            let id: String = row.try_get("id")?;
            let name: String = row.try_get("name")?;
            let severity: String = row.try_get("severity")?;
            let status: String = row.try_get("status")?;
            let labels_json: String = row.try_get("labels_json")?;
            let annotations_json: String = row.try_get("annotations_json")?;
            let starts_at: String = row.try_get("starts_at")?;
            let ends_at: Option<String> = row.try_get("ends_at")?;
            let detection_method: String = row.try_get("detection_method")?;
            let value: f64 = row.try_get("value")?;
            let threshold: Option<f64> = row.try_get("threshold")?;
            let generator_url: Option<String> = row.try_get("generator_url")?;

            let labels: std::collections::BTreeMap<String, String> =
                serde_json::from_str(&labels_json)?;
            let labels_vec = labels
                .into_iter()
                .map(|(k, v)| Label { name: k, value: v })
                .collect();

            let annotations: std::collections::HashMap<String, String> =
                serde_json::from_str(&annotations_json)?;

            let alert_status = match status.as_str() {
                "Firing" => AlertStatus::Firing,
                "Resolved" => AlertStatus::Resolved,
                "Silenced" => AlertStatus::Silenced,
                "Inhibited" => AlertStatus::Inhibited,
                _ => AlertStatus::Firing,
            };

            let det_method = match detection_method.as_str() {
                "StaticThreshold" => DetectionMethod::StaticThreshold,
                "MovingAverage" => DetectionMethod::MovingAverage,
                "Dbscan" => DetectionMethod::Dbscan,
                "SeasonalComparison" => DetectionMethod::SeasonalComparison,
                "PatternChange" => DetectionMethod::PatternChange,
                "Correlation" => DetectionMethod::Correlation,
                _ => DetectionMethod::StaticThreshold,
            };

            let alert = Alert {
                id: uuid::Uuid::parse_str(&id)?,
                name,
                severity: AlertSeverity::from_str(&severity).unwrap_or(AlertSeverity::Warning),
                status: alert_status,
                labels: Labels::from_vec(labels_vec),
                annotations,
                starts_at: DateTime::parse_from_rfc3339(&starts_at)?.with_timezone(&Utc),
                ends_at: ends_at
                    .as_deref()
                    .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                detection_method: det_method,
                value,
                threshold,
                generator_url,
            };

            alerts.push(alert);
        }

        Ok(alerts)
    }

    pub async fn get_active_alerts(&self) -> Result<Vec<Alert>> {
        let rows = sqlx::query(
            r#"SELECT * FROM alerts WHERE status = 'Firing' ORDER BY starts_at DESC"#
        )
        .fetch_all(&self.pool)
        .await?;

        let mut alerts = Vec::new();
        for row in rows {
            let id: String = row.try_get("id")?;
            let name: String = row.try_get("name")?;
            let severity: String = row.try_get("severity")?;
            let labels_json: String = row.try_get("labels_json")?;
            let annotations_json: String = row.try_get("annotations_json")?;
            let starts_at: String = row.try_get("starts_at")?;
            let value: f64 = row.try_get("value")?;
            let threshold: Option<f64> = row.try_get("threshold")?;
            let generator_url: Option<String> = row.try_get("generator_url")?;

            let labels: std::collections::BTreeMap<String, String> =
                serde_json::from_str(&labels_json)?;
            let labels_vec = labels
                .into_iter()
                .map(|(k, v)| Label { name: k, value: v })
                .collect();

            let annotations: std::collections::HashMap<String, String> =
                serde_json::from_str(&annotations_json)?;

            let alert = Alert {
                id: uuid::Uuid::parse_str(&id)?,
                name,
                severity: AlertSeverity::from_str(&severity).unwrap_or(AlertSeverity::Warning),
                status: AlertStatus::Firing,
                labels: Labels::from_vec(labels_vec),
                annotations,
                starts_at: DateTime::parse_from_rfc3339(&starts_at)?.with_timezone(&Utc),
                ends_at: None,
                detection_method: DetectionMethod::StaticThreshold,
                value,
                threshold,
                generator_url,
            };

            alerts.push(alert);
        }

        Ok(alerts)
    }
}
