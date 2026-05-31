pub mod domain;
pub mod infra;
pub mod modules;
pub mod service;
pub mod api;

use tokio;
use tracing_subscriber::{fmt, prelude::__tracing_subscriber_SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    tracing::info!("Zero Trust Network Access Controller starting...");

    let config = infra::config::AppConfig::load()?;
    let app_state = infra::app_state::AppState::new(config).await?;

    let router = api::routes::create_router(app_state.clone());

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await?;
    tracing::info!("Server listening on 0.0.0.0:8080");

    axum::serve(listener, router).await?;

    Ok(())
}
