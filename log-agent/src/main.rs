use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;
use tracing_subscriber::{fmt, prelude::*, EnvFilter};

use log_agent::{config::AgentConfig, file_watcher::FileWatcher, sender::BatchSender, kubernetes::KubernetesWatcher};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "config.yaml")]
    config: PathBuf,

    #[arg(long, default_value = "dev")]
    env: String,

    #[arg(long, default_value = "config")]
    config_dir: PathBuf,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(EnvFilter::from_default_env())
        .init();

    let args = Args::parse();
    
    let config = if args.config.exists() {
        use serde::Deserialize;
        let content = std::fs::read_to_string(&args.config)?;
        if args.config.extension().and_then(|e| e.to_str()) == Some("toml") {
            common::config::AgentConfig::load(&args.config.to_string_lossy(), &args.env)?
        } else {
            let config: common::config::AgentConfig = serde_yaml::from_str(&content)?;
            config
        }
    } else {
        tracing::warn!("Config file not found, loading from config directory");
        let config_path = args.config_dir.join("default.toml");
        common::config::AgentConfig::load(&config_path.to_string_lossy(), &args.env)?
    };

    let hostname = config.get_hostname();
    tracing::info!("Starting log-agent on {}", hostname);

    let (tx, rx) = log_agent::sender::create_channel(config.channel_buffer_size);

    if config.is_kubernetes_enabled() {
        let k8s_config = config.kubernetes.as_ref().unwrap().clone();
        tracing::info!(
            "Kubernetes mode enabled, node={}, pod_log_dir={}",
            k8s_config.node_name,
            k8s_config.pod_log_dir
        );

        let mut k8s_watcher = KubernetesWatcher::new(
            k8s_config.node_name,
            std::path::PathBuf::from(&k8s_config.pod_log_dir),
            tx.clone(),
            hostname.clone(),
        );

        if k8s_config.api_enrichment {
            if let Err(e) = k8s_watcher.try_enrich_from_k8s_api().await {
                tracing::warn!("Failed to enrich from K8s API (non-fatal): {}", e);
            }
        }

        let k8s_handle = tokio::spawn(async move {
            if let Err(e) = k8s_watcher.run().await {
                tracing::error!("Kubernetes watcher error: {}", e);
            }
        });

        let mut sender = BatchSender::new(
            rx,
            config.downstream_url.clone(),
            config.batch_size,
            config.flush_interval_ms,
        );

        let sender_handle = tokio::spawn(async move {
            sender.run().await;
        });

        tokio::select! {
            _ = k8s_handle => {},
            _ = sender_handle => {},
        }
    } else {
        let mut watcher = FileWatcher::new(config.files.clone(), hostname.clone(), tx);
        let mut sender = BatchSender::new(
            rx,
            config.downstream_url.clone(),
            config.batch_size,
            config.flush_interval_ms,
        );

        let watcher_handle = tokio::spawn(async move {
            if let Err(e) = watcher.run().await {
                tracing::error!("File watcher error: {}", e);
            }
        });

        let sender_handle = tokio::spawn(async move {
            sender.run().await;
        });

        tokio::select! {
            _ = watcher_handle => {},
            _ = sender_handle => {},
        }
    }

    Ok(())
}
