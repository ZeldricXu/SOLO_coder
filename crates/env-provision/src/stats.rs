use rusqlite::{Connection, params};
use uuid::Uuid;
use anyhow::Result;

use crate::models::{UsageStats, PreviewEnvironment};

pub struct UsageStatsCollector {
    conn: Connection,
}

impl UsageStatsCollector {
    pub fn new(conn: Connection) -> Self {
        Self { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS usage_stats (
                env_id TEXT PRIMARY KEY,
                total_hours REAL NOT NULL DEFAULT 0,
                cpu_seconds REAL NOT NULL DEFAULT 0,
                memory_gb_hours REAL NOT NULL DEFAULT 0,
                requests_count INTEGER NOT NULL DEFAULT 0
            )",
            [],
        )?;
        Ok(())
    }

    pub fn record_usage(&self, env_id: Uuid, duration_hours: f64) -> Result<()> {
        let env_id_str = env_id.to_string();
        self.conn.execute(
            "INSERT INTO usage_stats (env_id, total_hours) VALUES (?1, ?2)
             ON CONFLICT(env_id) DO UPDATE SET total_hours = total_hours + ?2",
            params![env_id_str, duration_hours],
        )?;
        Ok(())
    }

    pub fn get_env_stats(&self, env_id: Uuid) -> Result<Option<UsageStats>> {
        let mut stmt = self.conn.prepare("SELECT * FROM usage_stats WHERE env_id = ?1")?;
        let mut rows = stmt.query(params![env_id.to_string()])?;

        if let Some(row) = rows.next()? {
            let env_id_str: String = row.get(0)?;
            Ok(Some(UsageStats {
                env_id: Uuid::parse_str(&env_id_str)?,
                total_hours: row.get(1)?,
                cpu_seconds: row.get(2)?,
                memory_gb_hours: row.get(3)?,
                requests_count: row.get(4)?,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn get_team_stats(&self, team: &str, envs: &[PreviewEnvironment]) -> Result<UsageStats> {
        let team_envs: Vec<Uuid> = envs.iter()
            .filter(|env| env.owner_team == team)
            .map(|env| env.id)
            .collect();

        let mut total_hours = 0.0;
        let mut cpu_seconds = 0.0;
        let mut memory_gb_hours = 0.0;
        let mut requests_count = 0;

        for env_id in team_envs {
            if let Some(stats) = self.get_env_stats(env_id)? {
                total_hours += stats.total_hours;
                cpu_seconds += stats.cpu_seconds;
                memory_gb_hours += stats.memory_gb_hours;
                requests_count += stats.requests_count;
            }
        }

        Ok(UsageStats {
            env_id: Uuid::nil(),
            total_hours,
            cpu_seconds,
            memory_gb_hours,
            requests_count,
        })
    }
}
