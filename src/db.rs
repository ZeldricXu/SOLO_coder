use std::time::Duration;

use sqlx::{postgres::PgPoolOptions, Pool, Postgres};

use crate::config::Settings;

pub type DbPool = Pool<Postgres>;

pub async fn create_pool(settings: &Settings) -> Result<DbPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(settings.database.max_connections)
        .min_connections(settings.database.min_connections)
        .acquire_timeout(Duration::from_secs(settings.database.acquire_timeout_secs))
        .idle_timeout(Duration::from_secs(settings.database.idle_timeout_secs))
        .test_before_acquire(true)
        .connect(&settings.database.url)
        .await
}

pub async fn run_migrations(pool: &DbPool) -> Result<(), sqlx::Error> {
    sqlx::migrate!("./migrations")
        .run(pool)
        .await?;
    Ok(())
}
