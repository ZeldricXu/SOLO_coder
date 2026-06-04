use std::env;
use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};
use sqlx::postgres::PgPoolOptions;
use sqlx::FromRow;
use sqlx::PgPool;

#[derive(FromRow)]
struct MigrationRow {
    version: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = env::args().collect();
    let command = args.get(1).map(|s| s.as_str()).unwrap_or("up");

    let database_url =
        env::var("DATABASE_URL").context("DATABASE_URL environment variable not set")?;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await
        .context("Failed to connect to database")?;

    sqlx::query("SELECT pg_advisory_lock(12345)")
        .execute(&pool)
        .await
        .context("Failed to acquire advisory lock")?;

    let result = match command {
        "up" => run_up(&pool).await,
        "down" => run_down(&pool).await,
        _ => Err(anyhow::anyhow!(
            "Unknown command: {}. Use 'up' or 'down'",
            command
        )),
    };

    sqlx::query("SELECT pg_advisory_unlock(12345)")
        .execute(&pool)
        .await
        .context("Failed to release advisory lock")?;

    result
}

async fn ensure_migrations_table(pool: &PgPool) -> Result<()> {
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS _migrations (version VARCHAR(255) PRIMARY KEY, applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW())",
    )
    .execute(pool)
    .await
    .context("Failed to create _migrations table")?;
    Ok(())
}

async fn run_up(pool: &PgPool) -> Result<()> {
    ensure_migrations_table(pool).await?;

    let applied: Vec<MigrationRow> = sqlx::query_as("SELECT version FROM _migrations")
        .fetch_all(pool)
        .await
        .context("Failed to query applied migrations")?;

    let applied_versions: Vec<String> = applied.into_iter().map(|r| r.version).collect();

    let migrations_dir = find_migrations_dir()?;
    let mut entries: Vec<_> = fs::read_dir(&migrations_dir)
        .context("Failed to read migrations directory")?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().map(|ext| ext == "sql").unwrap_or(false))
        .collect();
    entries.sort_by_key(|e| e.file_name());

    for entry in entries {
        let file_name = entry.file_name().to_string_lossy().to_string();
        if applied_versions.contains(&file_name) {
            continue;
        }

        let sql = fs::read_to_string(entry.path())
            .with_context(|| format!("Failed to read migration file: {}", file_name))?;

        let mut tx = pool
            .begin()
            .await
            .with_context(|| format!("Failed to begin transaction for: {}", file_name))?;

        sqlx::raw_sql(&sql)
            .execute(&mut *tx)
            .await
            .with_context(|| format!("Failed to execute migration: {}", file_name))?;

        sqlx::query("INSERT INTO _migrations (version) VALUES ($1)")
            .bind(&file_name)
            .execute(&mut *tx)
            .await
            .with_context(|| format!("Failed to record migration: {}", file_name))?;

        tx.commit()
            .await
            .with_context(|| format!("Failed to commit migration: {}", file_name))?;

        println!("Applied: {}", file_name);
    }

    Ok(())
}

async fn run_down(pool: &PgPool) -> Result<()> {
    ensure_migrations_table(pool).await?;

    let latest: Option<MigrationRow> = sqlx::query_as(
        "SELECT version FROM _migrations ORDER BY applied_at DESC LIMIT 1",
    )
    .fetch_optional(pool)
    .await
    .context("Failed to query latest migration")?;

    match latest {
        Some(record) => {
            sqlx::query("DELETE FROM _migrations WHERE version = $1")
                .bind(&record.version)
                .execute(pool)
                .await
                .with_context(|| format!("Failed to rollback migration: {}", record.version))?;
            println!("Rolled back: {}", record.version);
        }
        None => {
            println!("No migrations to roll back");
        }
    }

    Ok(())
}

fn find_migrations_dir() -> Result<PathBuf> {
    let candidates = ["./migrations", "../migrations"];
    for dir in candidates {
        let path = PathBuf::from(dir);
        if path.is_dir() {
            return Ok(path);
        }
    }
    Err(anyhow::anyhow!("migrations directory not found"))
}
