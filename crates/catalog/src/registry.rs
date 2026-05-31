use anyhow::Result;
use rusqlite::Connection;
use uuid::Uuid;

use crate::models::{ServiceEntry, ServiceStatus};

pub struct CatalogRegistry {
    conn: Connection,
}

impl CatalogRegistry {
    pub fn new(conn: Connection) -> Self {
        Self { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS services (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                language TEXT NOT NULL,
                owner TEXT NOT NULL,
                team TEXT NOT NULL,
                repository_url TEXT NOT NULL,
                api_doc_url TEXT,
                status TEXT NOT NULL,
                version TEXT NOT NULL,
                tags TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );",
        )?;
        Ok(())
    }

    pub fn register(&self, entry: ServiceEntry) -> Result<()> {
        let tags_json = serde_json::to_string(&entry.tags)?;
        let status_str = match &entry.status {
            ServiceStatus::Active => "Active",
            ServiceStatus::Deprecated => "Deprecated",
            ServiceStatus::Development => "Development",
        };
        self.conn.execute(
            "INSERT INTO services (id, name, description, language, owner, team, repository_url, api_doc_url, status, version, tags, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            rusqlite::params![
                entry.id.to_string(),
                entry.name,
                entry.description,
                entry.language,
                entry.owner,
                entry.team,
                entry.repository_url,
                entry.api_doc_url,
                status_str,
                entry.version,
                tags_json,
                entry.created_at.to_rfc3339(),
                entry.updated_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn update(&self, id: Uuid, entry: ServiceEntry) -> Result<()> {
        let tags_json = serde_json::to_string(&entry.tags)?;
        let status_str = match &entry.status {
            ServiceStatus::Active => "Active",
            ServiceStatus::Deprecated => "Deprecated",
            ServiceStatus::Development => "Development",
        };
        let rows = self.conn.execute(
            "UPDATE services SET name=?2, description=?3, language=?4, owner=?5, team=?6, repository_url=?7, api_doc_url=?8, status=?9, version=?10, tags=?11, created_at=?12, updated_at=?13 WHERE id=?1",
            rusqlite::params![
                id.to_string(),
                entry.name,
                entry.description,
                entry.language,
                entry.owner,
                entry.team,
                entry.repository_url,
                entry.api_doc_url,
                status_str,
                entry.version,
                tags_json,
                entry.created_at.to_rfc3339(),
                entry.updated_at.to_rfc3339(),
            ],
        )?;
        if rows == 0 {
            anyhow::bail!("service not found: {}", id);
        }
        Ok(())
    }

    pub fn deregister(&self, id: Uuid) -> Result<()> {
        let rows = self.conn.execute(
            "DELETE FROM services WHERE id=?1",
            rusqlite::params![id.to_string()],
        )?;
        if rows == 0 {
            anyhow::bail!("service not found: {}", id);
        }
        Ok(())
    }

    pub fn get_by_id(&self, id: Uuid) -> Result<Option<ServiceEntry>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, language, owner, team, repository_url, api_doc_url, status, version, tags, created_at, updated_at FROM services WHERE id=?1",
        )?;
        let result = stmt.query_row(rusqlite::params![id.to_string()], |row| {
            Ok(row_to_service_entry(row))
        });
        match result {
            Ok(entry) => Ok(Some(entry?)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    pub fn list_all(&self) -> Result<Vec<ServiceEntry>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, description, language, owner, team, repository_url, api_doc_url, status, version, tags, created_at, updated_at FROM services",
        )?;
        let entries = stmt
            .query_map([], |row| Ok(row_to_service_entry(row)))?
            .map(|r| r?)
            .collect::<Result<Vec<ServiceEntry>, _>>()?;
        Ok(entries)
    }
}

fn row_to_service_entry(row: &rusqlite::Row) -> Result<ServiceEntry> {
    let id_str: String = row.get(0)?;
    let name: String = row.get(1)?;
    let description: String = row.get(2)?;
    let language: String = row.get(3)?;
    let owner: String = row.get(4)?;
    let team: String = row.get(5)?;
    let repository_url: String = row.get(6)?;
    let api_doc_url: Option<String> = row.get(7)?;
    let status_str: String = row.get(8)?;
    let version: String = row.get(9)?;
    let tags_json: String = row.get(10)?;
    let created_at_str: String = row.get(11)?;
    let updated_at_str: String = row.get(12)?;

    let id = Uuid::parse_str(&id_str)?;
    let status = match status_str.as_str() {
        "Active" => ServiceStatus::Active,
        "Deprecated" => ServiceStatus::Deprecated,
        "Development" => ServiceStatus::Development,
        _ => ServiceStatus::Development,
    };
    let tags: Vec<String> = serde_json::from_str(&tags_json)?;
    let created_at = chrono::DateTime::parse_from_rfc3339(&created_at_str)?.to_utc();
    let updated_at = chrono::DateTime::parse_from_rfc3339(&updated_at_str)?.to_utc();

    Ok(ServiceEntry {
        id,
        name,
        description,
        language,
        owner,
        team,
        repository_url,
        api_doc_url,
        status,
        version,
        tags,
        created_at,
        updated_at,
    })
}
