use std::sync::Arc;
use std::time::Duration;

use clap::Parser;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use reqwest::Client;
use uuid::Uuid;

use common::config::AppConfig;
use common::models::{NodeRegistration, Heartbeat};
use cache_engine::CacheEngine;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long)]
    config: Option<String>,

    #[arg(long)]
    node_id: Option<String>,

    #[arg(long)]
    ip: Option<String>,

    #[arg(long)]
    datacenter: Option<String>,
}

struct EdgeNodeService {
    config: Arc<AppConfig>,
    node_id: Uuid,
    client: Client,
    cache_engine: CacheEngine,
}

impl EdgeNodeService {
    pub fn new(config: AppConfig, node_id: Uuid) -> Self {
        EdgeNodeService {
            config: Arc::new(config),
            node_id,
            client: Client::new(),
            cache_engine: CacheEngine::new(1024 * 1024 * 1024),
        }
    }

    pub async fn register(&self, ip: &str, datacenter: &str) -> anyhow::Result<()> {
        let registration = NodeRegistration {
            ip: ip.parse().unwrap_or(std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST)),
            region: "global".to_string(),
            datacenter: datacenter.to_string(),
            bandwidth_capacity: 10_000,
            storage_capacity: 1024 * 1024 * 1024 * 100,
            latitude: Some(0.0),
            longitude: Some(0.0),
            role: common::models::NodeRole::Edge,
            parent_node_id: None,
        };

        let center_addr = self.config.center.listen_addr.to_string();
        let response = self.client
            .post(format!("http://{}/api/v1/nodes", center_addr))
            .json(&registration)
            .send()
            .await?;

        if response.status().is_success() {
            tracing::info!("Node registered successfully: {}", self.node_id);
            Ok(())
        } else {
            Err(anyhow::anyhow!("Failed to register node: {}", response.status()))
        }
    }

    pub async fn start_heartbeat(&self) {
        let this = self.clone();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(10));

            loop {
                interval.tick().await;

                let heartbeat = Heartbeat {
                    node_id: this.node_id,
                    timestamp: chrono::Utc::now(),
                    load: 0.5,
                    memory_usage: 0.3,
                    bandwidth_usage: 0.2,
                    connection_count: 100,
                };

                if let Err(e) = this.send_heartbeat(&heartbeat).await {
                    tracing::error!("Failed to send heartbeat: {}", e);
                }
            }
        });
    }

    async fn send_heartbeat(&self, heartbeat: &Heartbeat) -> anyhow::Result<()> {
        let center_addr = self.config.center.listen_addr.to_string();
        let response = self.client
            .post(format!("http://{}/api/v1/nodes/{}/heartbeat", center_addr, self.node_id))
            .json(heartbeat)
            .send()
            .await?;

        if response.status().is_success() {
            tracing::debug!("Heartbeat sent successfully");
            Ok(())
        } else {
            Err(anyhow::anyhow!("Failed to send heartbeat: {}", response.status()))
        }
    }
}

impl Clone for EdgeNodeService {
    fn clone(&self) -> Self {
        EdgeNodeService {
            config: self.config.clone(),
            node_id: self.node_id,
            client: self.client.clone(),
            cache_engine: self.cache_engine.clone(),
        }
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "cdn_edge=info".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let args = Args::parse();
    let config = AppConfig::default();

    let node_id = args.node_id
        .map(|s| Uuid::parse_str(&s).unwrap_or_else(|_| Uuid::new_v4()))
        .unwrap_or_else(Uuid::new_v4);

    let ip = args.ip.unwrap_or_else(|| "127.0.0.1".to_string());
    let datacenter = args.datacenter.unwrap_or_else(|| "local".to_string());

    tracing::info!("Starting CDN Edge Node: {}", node_id);

    let service = EdgeNodeService::new(config, node_id);

    service.register(&ip, &datacenter).await?;
    service.start_heartbeat().await;

    tracing::info!("Edge node running. Press Ctrl+C to exit");

    tokio::signal::ctrl_c().await?;
    tracing::info!("Shutting down edge node");

    Ok(())
}
