use anyhow::Result;
use rusqlite::Connection;

use crate::models::{Language, QualityGate, RuleDefinition};

pub struct RuleManager {
    conn: Connection,
}

impl RuleManager {
    pub fn new(conn: Connection) -> Self {
        Self { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS rules (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                language TEXT NOT NULL,
                severity TEXT NOT NULL,
                pattern TEXT NOT NULL,
                description TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS gates (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                rules TEXT NOT NULL,
                max_critical INTEGER NOT NULL,
                max_high INTEGER NOT NULL,
                max_medium INTEGER NOT NULL,
                coverage_min REAL NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            );",
        )?;
        Ok(())
    }

    pub fn add_rule(&self, rule: RuleDefinition) -> Result<()> {
        self.conn.execute(
            "INSERT INTO rules (id, name, language, severity, pattern, description, enabled, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            rusqlite::params![
                rule.id.to_string(),
                rule.name,
                serde_json::to_string(&rule.language)?,
                serde_json::to_string(&rule.severity)?,
                rule.pattern,
                rule.description,
                rule.enabled as i32,
                rule.created_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn update_rule(&self, id: uuid::Uuid, rule: RuleDefinition) -> Result<()> {
        self.conn.execute(
            "UPDATE rules SET name=?2, language=?3, severity=?4, pattern=?5, description=?6, enabled=?7, created_at=?8 WHERE id=?1",
            rusqlite::params![
                id.to_string(),
                rule.name,
                serde_json::to_string(&rule.language)?,
                serde_json::to_string(&rule.severity)?,
                rule.pattern,
                rule.description,
                rule.enabled as i32,
                rule.created_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn delete_rule(&self, id: uuid::Uuid) -> Result<()> {
        self.conn.execute("DELETE FROM rules WHERE id=?1", [id.to_string()])?;
        Ok(())
    }

    pub fn get_rule(&self, id: uuid::Uuid) -> Result<Option<RuleDefinition>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, language, severity, pattern, description, enabled, created_at FROM rules WHERE id=?1",
        )?;
        let rule = stmt.query_row([id.to_string()], |row| {
            Ok(row_to_rule(row))
        }).ok();
        match rule {
            Some(r) => Ok(Some(r?)),
            None => Ok(None),
        }
    }

    pub fn list_rules(&self, language: Option<Language>) -> Result<Vec<RuleDefinition>> {
        let mut rules = Vec::new();
        match language {
            Some(lang) => {
                let lang_str = serde_json::to_string(&lang)?;
                let mut stmt = self.conn.prepare(
                    "SELECT id, name, language, severity, pattern, description, enabled, created_at FROM rules WHERE language=?1",
                )?;
                let rows = stmt.query_map([lang_str], |row| Ok(row_to_rule(row)))?;
                for row in rows {
                    rules.push(row??);
                }
            }
            None => {
                let mut stmt = self.conn.prepare(
                    "SELECT id, name, language, severity, pattern, description, enabled, created_at FROM rules",
                )?;
                let rows = stmt.query_map([], |row| Ok(row_to_rule(row)))?;
                for row in rows {
                    rules.push(row??);
                }
            }
        }
        Ok(rules)
    }

    pub fn create_gate(&self, gate: QualityGate) -> Result<()> {
        self.conn.execute(
            "INSERT INTO gates (id, name, description, rules, max_critical, max_high, max_medium, coverage_min, enabled) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            rusqlite::params![
                gate.id.to_string(),
                gate.name,
                gate.description,
                serde_json::to_string(&gate.rules)?,
                gate.thresholds.max_critical,
                gate.thresholds.max_high,
                gate.thresholds.max_medium,
                gate.thresholds.coverage_min,
                gate.enabled as i32,
            ],
        )?;
        Ok(())
    }

    pub fn update_gate(&self, id: uuid::Uuid, gate: QualityGate) -> Result<()> {
        self.conn.execute(
            "UPDATE gates SET name=?2, description=?3, rules=?4, max_critical=?5, max_high=?6, max_medium=?7, coverage_min=?8, enabled=?9 WHERE id=?1",
            rusqlite::params![
                id.to_string(),
                gate.name,
                gate.description,
                serde_json::to_string(&gate.rules)?,
                gate.thresholds.max_critical,
                gate.thresholds.max_high,
                gate.thresholds.max_medium,
                gate.thresholds.coverage_min,
                gate.enabled as i32,
            ],
        )?;
        Ok(())
    }

    pub fn get_gate(&self, id: uuid::Uuid) -> Result<Option<QualityGate>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, rules, max_critical, max_high, max_medium, coverage_min, enabled FROM gates WHERE id=?1",
        )?;
        let gate = stmt.query_row([id.to_string()], |row| {
            Ok(row_to_gate(row))
        }).ok();
        match gate {
            Some(g) => Ok(Some(g?)),
            None => Ok(None),
        }
    }

    pub fn list_gates(&self) -> Result<Vec<QualityGate>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, rules, max_critical, max_high, max_medium, coverage_min, enabled FROM gates",
        )?;
        let gates = stmt.query_map([], |row| Ok(row_to_gate(row)))?;
        let mut result = Vec::new();
        for gate in gates {
            result.push(gate??);
        }
        Ok(result)
    }
}

fn row_to_rule(row: &rusqlite::Row) -> Result<RuleDefinition> {
    let id_str: String = row.get(0)?;
    let name: String = row.get(1)?;
    let lang_str: String = row.get(2)?;
    let sev_str: String = row.get(3)?;
    let pattern: String = row.get(4)?;
    let description: String = row.get(5)?;
    let enabled: i32 = row.get(6)?;
    let created_str: String = row.get(7)?;

    Ok(RuleDefinition {
        id: id_str.parse()?,
        name,
        language: serde_json::from_str(&lang_str)?,
        severity: serde_json::from_str(&sev_str)?,
        pattern,
        description,
        enabled: enabled != 0,
        created_at: created_str.parse()?,
    })
}

fn row_to_gate(row: &rusqlite::Row) -> Result<QualityGate> {
    let id_str: String = row.get(0)?;
    let name: String = row.get(1)?;
    let description: String = row.get(2)?;
    let rules_str: String = row.get(3)?;
    let max_critical: u32 = row.get(4)?;
    let max_high: u32 = row.get(5)?;
    let max_medium: u32 = row.get(6)?;
    let coverage_min: f64 = row.get(7)?;
    let enabled: i32 = row.get(8)?;

    Ok(QualityGate {
        id: id_str.parse()?,
        name,
        description,
        rules: serde_json::from_str(&rules_str)?,
        thresholds: crate::models::GateThresholds {
            max_critical,
            max_high,
            max_medium,
            coverage_min,
        },
        enabled: enabled != 0,
    })
}
