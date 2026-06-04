use crate::{config::AppConfig, error::AppResult};
use sqlx::{postgres::PgPoolOptions, PgPool};

pub async fn create_pg_pool(config: &AppConfig) -> AppResult<PgPool> {
    let pool = PgPoolOptions::new()
        .max_connections(config.database.max_connections)
        .connect(&config.database.url)
        .await?;

    Ok(pool)
}

pub async fn run_migrations(pool: &PgPool) {
    if let Err(e) = sqlx::migrate!("../../migrations").run(pool).await {
        tracing::warn!("Migration warning: {}", e);
    }
}

pub async fn create_redis_client(config: &AppConfig) -> AppResult<redis::Client> {
    let client = redis::Client::open(config.redis.url.clone())?;
    Ok(client)
}

pub async fn create_redis_connection(config: &AppConfig) -> AppResult<redis::aio::MultiplexedConnection> {
    let client = redis::Client::open(config.redis.url.clone())?;
    let conn = client.get_multiplexed_tokio_connection().await?;
    Ok(conn)
}

pub async fn create_mongodb_client(config: &AppConfig) -> AppResult<mongodb::Client> {
    let client = mongodb::Client::with_uri_str(&config.mongodb.uri).await?;
    Ok(client)
}

pub trait DatabasePool {
    fn pool(&self) -> &PgPool;
}
