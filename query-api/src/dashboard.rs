use anyhow::Result;
use serde::{Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, Pool, Sqlite, Row};
use std::path::Path;
use tracing::info;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ChartType {
    Line,
    Bar,
    Table,
    Stat,
}

impl ChartType {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "line" => Some(ChartType::Line),
            "bar" => Some(ChartType::Bar),
            "table" => Some(ChartType::Table),
            "stat" | "value" | "card" => Some(ChartType::Stat),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            ChartType::Line => "line",
            ChartType::Bar => "bar",
            ChartType::Table => "table",
            ChartType::Stat => "stat",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum RefreshInterval {
    Sec10,
    Sec30,
    Min1,
    Min5,
}

impl RefreshInterval {
    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "10s" => Some(RefreshInterval::Sec10),
            "30s" => Some(RefreshInterval::Sec30),
            "1m" => Some(RefreshInterval::Min1),
            "5m" => Some(RefreshInterval::Min5),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            RefreshInterval::Sec10 => "10s",
            RefreshInterval::Sec30 => "30s",
            RefreshInterval::Min1 => "1m",
            RefreshInterval::Min5 => "5m",
        }
    }

    pub fn to_seconds(&self) -> u64 {
        match self {
            RefreshInterval::Sec10 => 10,
            RefreshInterval::Sec30 => 30,
            RefreshInterval::Min1 => 60,
            RefreshInterval::Min5 => 300,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DashboardPanel {
    pub id: String,
    pub title: String,
    pub query: String,
    pub chart_type: ChartType,
    pub grid_x: u32,
    pub grid_y: u32,
    pub grid_w: u32,
    pub grid_h: u32,
    pub refresh_interval: RefreshInterval,
    pub color: Option<String>,
    pub unit: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Dashboard {
    pub id: String,
    pub name: String,
    pub description: String,
    pub panels: Vec<DashboardPanel>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Deserialize)]
pub struct CreateDashboardRequest {
    pub name: String,
    pub description: Option<String>,
    pub panels: Vec<CreatePanelRequest>,
}

#[derive(Debug, Deserialize)]
pub struct CreatePanelRequest {
    pub title: String,
    pub query: String,
    pub chart_type: String,
    pub grid_x: u32,
    pub grid_y: u32,
    pub grid_w: u32,
    pub grid_h: u32,
    pub refresh_interval: Option<String>,
    pub color: Option<String>,
    pub unit: Option<String>,
}

pub struct DashboardStorage {
    pool: Pool<Sqlite>,
}

impl DashboardStorage {
    pub async fn new<P: AsRef<Path>>(db_path: P) -> Result<Self> {
        let db_url = format!("sqlite:{}", db_path.as_ref().to_string_lossy());
        let pool = SqlitePoolOptions::new()
            .max_connections(5)
            .connect(&db_url)
            .await?;

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS dashboards (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                panels_json TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            "#,
        )
        .execute(&pool)
        .await?;

        info!("Dashboard storage initialized");
        Ok(Self { pool })
    }

    pub async fn create_dashboard(&self, req: CreateDashboardRequest) -> Result<Dashboard> {
        let id = uuid::Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();
        let description = req.description.unwrap_or_default();

        let panels: Vec<DashboardPanel> = req
            .panels
            .into_iter()
            .enumerate()
            .map(|(i, p)| DashboardPanel {
                id: format!("{}-{}", id, i),
                title: p.title,
                query: p.query,
                chart_type: ChartType::from_str(&p.chart_type).unwrap_or(ChartType::Line),
                grid_x: p.grid_x,
                grid_y: p.grid_y,
                grid_w: p.grid_w,
                grid_h: p.grid_h,
                refresh_interval: p
                    .refresh_interval
                    .as_deref()
                    .and_then(RefreshInterval::from_str)
                    .unwrap_or(RefreshInterval::Min1),
                color: p.color,
                unit: p.unit,
            })
            .collect();

        let panels_json = serde_json::to_string(&panels)?;

        sqlx::query(
            r#"
            INSERT INTO dashboards (id, name, description, panels_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(&id)
        .bind(&req.name)
        .bind(&description)
        .bind(&panels_json)
        .bind(&now)
        .bind(&now)
        .execute(&self.pool)
        .await?;

        Ok(Dashboard {
            id,
            name: req.name,
            description,
            panels,
            created_at: now.clone(),
            updated_at: now,
        })
    }

    pub async fn get_dashboard(&self, id: &str) -> Result<Option<Dashboard>> {
        let row = sqlx::query(
            r#"SELECT id, name, description, panels_json, created_at, updated_at FROM dashboards WHERE id = ?"#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            Some(row) => {
                let id: String = row.try_get("id")?;
                let name: String = row.try_get("name")?;
                let description: String = row.try_get("description")?;
                let panels_json: String = row.try_get("panels_json")?;
                let created_at: String = row.try_get("created_at")?;
                let updated_at: String = row.try_get("updated_at")?;

                let panels: Vec<DashboardPanel> =
                    serde_json::from_str(&panels_json).unwrap_or_default();
                Ok(Some(Dashboard {
                    id,
                    name,
                    description,
                    panels,
                    created_at,
                    updated_at,
                }))
            }
            None => Ok(None),
        }
    }

    pub async fn list_dashboards(&self) -> Result<Vec<Dashboard>> {
        let rows = sqlx::query(
            r#"SELECT id, name, description, panels_json, created_at, updated_at FROM dashboards ORDER BY updated_at DESC"#,
        )
        .fetch_all(&self.pool)
        .await?;

        let mut dashboards = Vec::new();
        for row in rows {
            let id: String = row.try_get("id")?;
            let name: String = row.try_get("name")?;
            let description: String = row.try_get("description")?;
            let panels_json: String = row.try_get("panels_json")?;
            let created_at: String = row.try_get("created_at")?;
            let updated_at: String = row.try_get("updated_at")?;

            let panels: Vec<DashboardPanel> =
                serde_json::from_str(&panels_json).unwrap_or_default();
            dashboards.push(Dashboard {
                id,
                name,
                description,
                panels,
                created_at,
                updated_at,
            });
        }

        Ok(dashboards)
    }

    pub async fn delete_dashboard(&self, id: &str) -> Result<bool> {
        let result = sqlx::query("DELETE FROM dashboards WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    pub async fn update_dashboard(
        &self,
        id: &str,
        req: CreateDashboardRequest,
    ) -> Result<Option<Dashboard>> {
        let now = chrono::Utc::now().to_rfc3339();
        let description = req.description.unwrap_or_default();

        let panels: Vec<DashboardPanel> = req
            .panels
            .into_iter()
            .enumerate()
            .map(|(i, p)| DashboardPanel {
                id: format!("{}-{}", id, i),
                title: p.title,
                query: p.query,
                chart_type: ChartType::from_str(&p.chart_type).unwrap_or(ChartType::Line),
                grid_x: p.grid_x,
                grid_y: p.grid_y,
                grid_w: p.grid_w,
                grid_h: p.grid_h,
                refresh_interval: p
                    .refresh_interval
                    .as_deref()
                    .and_then(RefreshInterval::from_str)
                    .unwrap_or(RefreshInterval::Min1),
                color: p.color,
                unit: p.unit,
            })
            .collect();

        let panels_json = serde_json::to_string(&panels)?;

        let result = sqlx::query(
            r#"
            UPDATE dashboards SET name = ?, description = ?, panels_json = ?, updated_at = ?
            WHERE id = ?
            "#,
        )
        .bind(&req.name)
        .bind(&description)
        .bind(&panels_json)
        .bind(&now)
        .bind(id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            Ok(None)
        } else {
            self.get_dashboard(id).await
        }
    }
}
