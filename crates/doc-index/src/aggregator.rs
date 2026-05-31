use anyhow::Result;
use rusqlite::{Connection, params};
use uuid::Uuid;
use chrono::{Utc, DateTime};
use crate::models::{AggregationJob, IndexStatus, DocumentSource};

pub struct SourceAggregator {
    conn: Connection,
}

impl SourceAggregator {
    pub fn new(conn: Connection) -> Self {
        let aggregator = SourceAggregator { conn };
        aggregator.init_schema().unwrap();
        aggregator
    }

    pub fn add_source(&self, job: AggregationJob) -> Result<()> {
        let source_str = match job.source {
            DocumentSource::Confluence => "confluence",
            DocumentSource::Notion => "notion",
            DocumentSource::GitLabWiki => "gitlabwiki",
            DocumentSource::GitHubWiki => "githubwiki",
            DocumentSource::Markdown => "markdown",
        };
        let status_str = match job.status {
            IndexStatus::Pending => "pending",
            IndexStatus::Indexed => "indexed",
            IndexStatus::Failed => "failed",
        };
        self.conn.execute(
            "INSERT INTO aggregation_jobs (id, source, config_url, last_sync, status)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                job.id.to_string(),
                source_str,
                job.config_url,
                job.last_sync.to_rfc3339(),
                status_str,
            ],
        )?;
        Ok(())
    }

    pub fn list_sources(&self) -> Result<Vec<AggregationJob>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, source, config_url, last_sync, status FROM aggregation_jobs"
        )?;
        let job_iter = stmt.query_map([], |row| {
            let id_str: String = row.get(0)?;
            let source_str: String = row.get(1)?;
            let config_url: String = row.get(2)?;
            let last_sync_str: String = row.get(3)?;
            let status_str: String = row.get(4)?;
            let id = Uuid::parse_str(&id_str).unwrap_or(Uuid::nil());
            let source = match source_str.as_str() {
                "confluence" => DocumentSource::Confluence,
                "notion" => DocumentSource::Notion,
                "gitlabwiki" => DocumentSource::GitLabWiki,
                "githubwiki" => DocumentSource::GitHubWiki,
                _ => DocumentSource::Markdown,
            };
            let last_sync = DateTime::parse_from_rfc3339(&last_sync_str)
                .map(|dt| dt.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());
            let status = match status_str.as_str() {
                "pending" => IndexStatus::Pending,
                "indexed" => IndexStatus::Indexed,
                _ => IndexStatus::Failed,
            };
            Ok(AggregationJob {
                id,
                source,
                config_url,
                last_sync,
                status,
            })
        })?;
        let mut jobs = Vec::new();
        for job in job_iter {
            jobs.push(job?);
        }
        Ok(jobs)
    }

    pub fn update_sync_status(&self, id: Uuid, status: IndexStatus) -> Result<()> {
        let status_str = match status {
            IndexStatus::Pending => "pending",
            IndexStatus::Indexed => "indexed",
            IndexStatus::Failed => "failed",
        };
        self.conn.execute(
            "UPDATE aggregation_jobs SET status = ?1, last_sync = ?2 WHERE id = ?3",
            params![
                status_str,
                Utc::now().to_rfc3339(),
                id.to_string(),
            ],
        )?;
        Ok(())
    }

    fn init_schema(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS aggregation_jobs (
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                config_url TEXT NOT NULL,
                last_sync TEXT NOT NULL,
                status TEXT NOT NULL
            )",
            [],
        )?;
        Ok(())
    }
}
