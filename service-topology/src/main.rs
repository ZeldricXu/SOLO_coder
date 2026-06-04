use clap::Parser;
use std::sync::Arc;
use tracing::info;

use service_topology::{routes::create_routes, service::TopologyService};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "0.0.0.0:8088")]
    listen_addr: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();

    info!("Starting Service Topology Discovery Service...");
    info!("Listen address: {}", args.listen_addr);

    let service = Arc::new(TopologyService::new());
    let routes = create_routes(service.clone());

    info!("Service Topology HTTP server starting on {}", args.listen_addr);
    warp::serve(routes).run(args.listen_addr.parse()?).await;

    Ok(())
}
