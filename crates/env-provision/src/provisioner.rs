use rusqlite::{Connection, params};
use uuid::Uuid;
use chrono::{DateTime, Utc, Duration};
use anyhow::Result;
use serde_json;
use std::collections::HashMap;

use crate::models::{PreviewEnvironment, ProvisionRequest, EnvStatus};

pub struct EnvironmentProvisioner {
    conn: Connection,
}

impl EnvironmentProvisioner {
    pub fn new(conn: Connection) -> Self {
        Self { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS environments (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                env_type TEXT NOT NULL,
                branch_name TEXT NOT NULL,
                git_url TEXT NOT NULL,
                status TEXT NOT NULL,
                creator TEXT NOT NULL,
                owner_team TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                last_heartbeat TEXT,
                resources TEXT NOT NULL,
                endpoints TEXT NOT NULL
            )",
            [],
        )?;
        Ok(())
    }

    pub fn provision(&self, request: ProvisionRequest) -> Result<PreviewEnvironment> {
        let id = Uuid::new_v4();
        let now = Utc::now();
        let expires_at = now + Duration::hours(request.ttl_hours as i64);
        let env = PreviewEnvironment {
            id,
            name: request.name,
            env_type: request.env_type,
            branch_name: request.branch_name,
            git_url: request.git_url,
            status: EnvStatus::Pending,
            creator: request.creator,
            owner_team: request.owner_team,
            created_at: now,
            expires_at,
            last_heartbeat: None,
            resources: request.resource_config,
            endpoints: HashMap::new(),
        };

        self.conn.execute(
            "INSERT INTO environments (
                id, name, env_type, branch_name, git_url, status, creator, owner_team, 
                created_at, expires_at, last_heartbeat, resources, endpoints
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            params![
                id.to_string(),
                env.name,
                serde_json::to_string(&env.env_type)?,
                env.branch_name,
                env.git_url,
                serde_json::to_string(&env.status)?,
                env.creator,
                env.owner_team,
                env.created_at.to_rfc3339(),
                env.expires_at.to_rfc3339(),
                env.last_heartbeat.map(|dt| dt.to_rfc3339()),
                serde_json::to_string(&env.resources)?,
                serde_json::to_string(&env.endpoints)?,
            ],
        )?;

        Ok(env)
    }

    pub fn terminate(&self, env_id: Uuid) -> Result<()> {
        self.conn.execute(
            "UPDATE environments SET status = ?1 WHERE id = ?2",
            params![serde_json::to_string(&EnvStatus::Terminated)?, env_id.to_string()],
        )?;
        Ok(())
    }

    pub fn stop(&self, env_id: Uuid) -> Result<()> {
        self.conn.execute(
            "UPDATE environments SET status = ?1 WHERE id = ?2",
            params![serde_json::to_string(&EnvStatus::Stopped)?, env_id.to_string()],
        )?;
        Ok(())
    }

    pub fn start(&self, env_id: Uuid) -> Result<()> {
        self.conn.execute(
            "UPDATE environments SET status = ?1 WHERE id = ?2",
            params![serde_json::to_string(&EnvStatus::Running)?, env_id.to_string()],
        )?;
        Ok(())
    }

    pub fn get_environment(&self, env_id: Uuid) -> Result<Option<PreviewEnvironment>> {
        let mut stmt = self.conn.prepare("SELECT * FROM environments WHERE id = ?1")?;
        let mut rows = stmt.query(params![env_id.to_string()])?;

        if let Some(row) = rows.next()? {
            Ok(Some(self.row_to_env(row)?))
        } else {
            Ok(None)
        }
    }

    pub fn list_environments(&self, team: Option<&str>) -> Result<Vec<PreviewEnvironment>> {
        let mut result = Vec::new();
        if let Some(team) = team {
            let mut stmt = self.conn.prepare("SELECT * FROM environments WHERE owner_team = ?1")?;
            let mut rows = stmt.query(params![team])?;
            while let Some(row) = rows.next()? {
                result.push(self.row_to_env(row)?);
            }
        } else {
            let mut stmt = self.conn.prepare("SELECT * FROM environments")?;
            let mut rows = stmt.query([])?;
            while let Some(row) = rows.next()? {
                result.push(self.row_to_env(row)?);
            }
        }

        Ok(result)
    }

    pub fn heartbeat(&self, env_id: Uuid) -> Result<()> {
        let now = Utc::now();
        self.conn.execute(
            "UPDATE environments SET last_heartbeat = ?1 WHERE id = ?2",
            params![now.to_rfc3339(), env_id.to_string()],
        )?;
        Ok(())
    }

    fn row_to_env(&self, row: &rusqlite::Row) -> Result<PreviewEnvironment> {
        let id_str: String = row.get(0)?;
        let env_type_str: String = row.get(2)?;
        let status_str: String = row.get(5)?;
        let resources_str: String = row.get(11)?;
        let endpoints_str: String = row.get(12)?;
        let created_at_str: String = row.get(8)?;
        let expires_at_str: String = row.get(9)?;
        let last_heartbeat_str: Option<String> = row.get(10)?;

        Ok(PreviewEnvironment {
            id: Uuid::parse_str(&id_str)?,
            name: row.get(1)?,
            env_type: serde_json::from_str(&env_type_str)?,
            branch_name: row.get(3)?,
            git_url: row.get(4)?,
            status: serde_json::from_str(&status_str)?,
            creator: row.get(6)?,
            owner_team: row.get(7)?,
            created_at: DateTime::parse_from_rfc3339(&created_at_str)?.with_timezone(&Utc),
            expires_at: DateTime::parse_from_rfc3339(&expires_at_str)?.with_timezone(&Utc),
            last_heartbeat: last_heartbeat_str
                .map(|s| -> Result<_> {
                    Ok(DateTime::parse_from_rfc3339(&s)?.with_timezone(&Utc))
                })
                .transpose()?,
            resources: serde_json::from_str(&resources_str)?,
            endpoints: serde_json::from_str(&endpoints_str)?,
        })
    }
}
