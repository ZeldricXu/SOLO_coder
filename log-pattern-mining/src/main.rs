use clap::Parser;
use std::sync::Arc;
use tracing::info;

use log_pattern_mining::{routes::create_routes, service::PatternMiningService};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "0.0.0.0:8087")]
    listen_addr: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();

    info!("Starting Log Pattern Mining Service...");
    info!("Listen address: {}", args.listen_addr);

    let service = Arc::new(PatternMiningService::new());
    let routes = create_routes(service.clone());

    info!("Log Pattern Mining HTTP server starting on {}", args.listen_addr);
    warp::serve(routes).run(args.listen_addr.parse()?).await;

    Ok(())
}
