use rusqlite::{Connection, params};
use uuid::Uuid;
use chrono::Utc;
use anyhow::Result;
use crate::models::{FeatureToggle, ToggleStatus, RolloutStrategy, UserAttribute};

pub struct ToggleManager {
    conn: Connection,
}

impl ToggleManager {
    pub fn new(conn: Connection) -> Self {
        ToggleManager { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS feature_toggles (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                status TEXT NOT NULL,
                strategy TEXT NOT NULL,
                rollout_percentage INTEGER NOT NULL,
                whitelist_users TEXT NOT NULL,
                whitelist_teams TEXT NOT NULL,
                attributes TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )",
            [],
        )?;
        Ok(())
    }

    pub fn create_toggle(&self, toggle: &FeatureToggle) -> Result<()> {
        let status = match toggle.status {
            ToggleStatus::On => "On",
            ToggleStatus::Off => "Off",
        };
        let strategy = match toggle.strategy {
            RolloutStrategy::Percentage => "Percentage",
            RolloutStrategy::UserList => "UserList",
            RolloutStrategy::TeamList => "TeamList",
            RolloutStrategy::AttributeMatch => "AttributeMatch",
            RolloutStrategy::Gradual => "Gradual",
        };
        let whitelist_users = serde_json::to_string(&toggle.whitelist_users)?;
        let whitelist_teams = serde_json::to_string(&toggle.whitelist_teams)?;
        let attributes = serde_json::to_string(&toggle.attributes)?;

        self.conn.execute(
            "INSERT INTO feature_toggles (
                id, name, description, status, strategy, rollout_percentage,
                whitelist_users, whitelist_teams, attributes, created_at, updated_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                toggle.id.to_string(),
                toggle.name,
                toggle.description,
                status,
                strategy,
                toggle.rollout_percentage,
                whitelist_users,
                whitelist_teams,
                attributes,
                toggle.created_at.to_rfc3339(),
                toggle.updated_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn update_toggle(&self, id: Uuid, toggle: &FeatureToggle) -> Result<()> {
        let status = match toggle.status {
            ToggleStatus::On => "On",
            ToggleStatus::Off => "Off",
        };
        let strategy = match toggle.strategy {
            RolloutStrategy::Percentage => "Percentage",
            RolloutStrategy::UserList => "UserList",
            RolloutStrategy::TeamList => "TeamList",
            RolloutStrategy::AttributeMatch => "AttributeMatch",
            RolloutStrategy::Gradual => "Gradual",
        };
        let whitelist_users = serde_json::to_string(&toggle.whitelist_users)?;
        let whitelist_teams = serde_json::to_string(&toggle.whitelist_teams)?;
        let attributes = serde_json::to_string(&toggle.attributes)?;
        let updated_at = Utc::now();

        self.conn.execute(
            "UPDATE feature_toggles SET
                name = ?1, description = ?2, status = ?3, strategy = ?4,
                rollout_percentage = ?5, whitelist_users = ?6, whitelist_teams = ?7,
                attributes = ?8, updated_at = ?9
            WHERE id = ?10",
            params![
                toggle.name,
                toggle.description,
                status,
                strategy,
                toggle.rollout_percentage,
                whitelist_users,
                whitelist_teams,
                attributes,
                updated_at.to_rfc3339(),
                id.to_string(),
            ],
        )?;
        Ok(())
    }

    pub fn delete_toggle(&self, id: Uuid) -> Result<()> {
        self.conn.execute(
            "DELETE FROM feature_toggles WHERE id = ?1",
            params![id.to_string()],
        )?;
        Ok(())
    }

    pub fn get_toggle(&self, id: Uuid) -> Result<Option<FeatureToggle>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, status, strategy, rollout_percentage,
                    whitelist_users, whitelist_teams, attributes, created_at, updated_at
             FROM feature_toggles WHERE id = ?1"
        )?;

        let mut rows = stmt.query(params![id.to_string()])?;

        if let Some(row) = rows.next()? {
            let status_str: String = row.get(3)?;
            let strategy_str: String = row.get(4)?;
            let whitelist_users_str: String = row.get(6)?;
            let whitelist_teams_str: String = row.get(7)?;
            let attributes_str: String = row.get(8)?;
            let created_at_str: String = row.get(9)?;
            let updated_at_str: String = row.get(10)?;

            let status = match status_str.as_str() {
                "On" => ToggleStatus::On,
                _ => ToggleStatus::Off,
            };
            let strategy = match strategy_str.as_str() {
                "Percentage" => RolloutStrategy::Percentage,
                "UserList" => RolloutStrategy::UserList,
                "TeamList" => RolloutStrategy::TeamList,
                "AttributeMatch" => RolloutStrategy::AttributeMatch,
                _ => RolloutStrategy::Gradual,
            };
            let whitelist_users: Vec<String> = serde_json::from_str(&whitelist_users_str)?;
            let whitelist_teams: Vec<String> = serde_json::from_str(&whitelist_teams_str)?;
            let attributes: Vec<UserAttribute> = serde_json::from_str(&attributes_str)?;
            let created_at = chrono::DateTime::parse_from_rfc3339(&created_at_str)?.with_timezone(&Utc);
            let updated_at = chrono::DateTime::parse_from_rfc3339(&updated_at_str)?.with_timezone(&Utc);

            Ok(Some(FeatureToggle {
                id: Uuid::parse_str(&row.get::<_, String>(0)?)?,
                name: row.get(1)?,
                description: row.get(2)?,
                status,
                strategy,
                rollout_percentage: row.get(5)?,
                whitelist_users,
                whitelist_teams,
                attributes,
                created_at,
                updated_at,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn list_toggles(&self) -> Result<Vec<FeatureToggle>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, status, strategy, rollout_percentage,
                    whitelist_users, whitelist_teams, attributes, created_at, updated_at
             FROM feature_toggles"
        )?;

        let rows = stmt.query_map([], |row| {
            let status_str: String = row.get(3)?;
            let strategy_str: String = row.get(4)?;
            let whitelist_users_str: String = row.get(6)?;
            let whitelist_teams_str: String = row.get(7)?;
            let attributes_str: String = row.get(8)?;
            let created_at_str: String = row.get(9)?;
            let updated_at_str: String = row.get(10)?;

            let status = match status_str.as_str() {
                "On" => ToggleStatus::On,
                _ => ToggleStatus::Off,
            };
            let strategy = match strategy_str.as_str() {
                "Percentage" => RolloutStrategy::Percentage,
                "UserList" => RolloutStrategy::UserList,
                "TeamList" => RolloutStrategy::TeamList,
                "AttributeMatch" => RolloutStrategy::AttributeMatch,
                _ => RolloutStrategy::Gradual,
            };
            let whitelist_users: Vec<String> = serde_json::from_str(&whitelist_users_str).unwrap_or_default();
            let whitelist_teams: Vec<String> = serde_json::from_str(&whitelist_teams_str).unwrap_or_default();
            let attributes: Vec<UserAttribute> = serde_json::from_str(&attributes_str).unwrap_or_default();
            let created_at = chrono::DateTime::parse_from_rfc3339(&created_at_str)
                .map(|dt| dt.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());
            let updated_at = chrono::DateTime::parse_from_rfc3339(&updated_at_str)
                .map(|dt| dt.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());

            Ok(FeatureToggle {
                id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap_or_else(|_| Uuid::nil()),
                name: row.get(1)?,
                description: row.get(2)?,
                status,
                strategy,
                rollout_percentage: row.get(5)?,
                whitelist_users,
                whitelist_teams,
                attributes,
                created_at,
                updated_at,
            })
        })?;

        let mut toggles = Vec::new();
        for toggle in rows {
            toggles.push(toggle?);
        }
        Ok(toggles)
    }
}
