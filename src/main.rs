use apishield::config::AppConfig;
use apishield::api::{create_router, AppState};
use clap::Parser;
use std::net::SocketAddr;
use tokio::net::TcpListener;
use tracing_subscriber::{fmt, EnvFilter};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Cli {
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    #[arg(short, long, default_value_t = 8080)]
    port: u16,

    #[arg(long)]
    start_async_masking: bool,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(EnvFilter::from_default_env())
        .init();

    let cli = Cli::parse();
    let config = AppConfig::default();
    let state = AppState::new(config);
    let router = create_router(state.clone());

    if cli.start_async_masking {
        state.start_async_masking().await;
        tracing::info!("Async masking engine started");
    }

    let addr: SocketAddr = format!("{}:{}", cli.host, cli.port)
        .parse()
        .expect("Invalid address");

    tracing::info!("Starting APIShield server on {}", addr);

    let listener = TcpListener::bind(&addr)
        .await
        .expect("Failed to bind to address");

    axum::serve(listener, router)
        .await
        .expect("Server error");
}
