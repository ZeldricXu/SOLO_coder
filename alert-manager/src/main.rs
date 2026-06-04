use clap::Parser;
use std::sync::Arc;
use tracing::info;

use alert_manager::{manager::AlertManager, routes::create_routes, storage::AlertStorage, root_cause::RootCauseConfig};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "0.0.0.0:8086")]
    listen_addr: String,

    #[arg(short, long, default_value = "alerts.db")]
    db_path: String,

    #[arg(long)]
    openai_api_key: Option<String>,

    #[arg(long, default_value = "gpt-4o-mini")]
    openai_model: String,

    #[arg(long, default_value = "http://localhost:9200")]
    elasticsearch_url: String,

    #[arg(long, default_value = "5")]
    lookback_minutes: i64,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();

    info!("Starting Alert Manager...");
    info!("DB path: {}", args.db_path);
    info!("Listen address: {}", args.listen_addr);

    let storage = Arc::new(AlertStorage::new(&args.db_path).await?);

    let root_cause_config = if let Some(api_key) = args.openai_api_key {
        info!("LLM root cause analysis enabled with model {}", args.openai_model);
        RootCauseConfig {
            enabled: true,
            openai_api_key: api_key,
            openai_model: args.openai_model,
            elasticsearch_url: args.elasticsearch_url,
            lookback_minutes: args.lookback_minutes,
            max_log_lines: 100,
        }
    } else {
        info!("LLM root cause analysis disabled (no API key provided)");
        RootCauseConfig::default()
    };

    let alert_manager = Arc::new(
        AlertManager::new(storage.clone()).with_root_cause(root_cause_config),
    );

    let routes = create_routes(alert_manager.clone(), storage.clone());

    info!("Alert Manager HTTP server starting on {}", args.listen_addr);
    warp::serve(routes).run(args.listen_addr.parse::<std::net::SocketAddr>()?).await;

    Ok(())
}
