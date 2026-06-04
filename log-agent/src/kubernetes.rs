use anyhow::{Context, Result};
use chrono::Utc;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tokio::sync::mpsc::Sender;
use tracing::{debug, error, info, warn};

use common::log::{LogTailConfig, LogEvent};

use crate::parser::LogParser;

#[derive(Debug, Clone)]
pub struct PodMetadata {
    pub namespace: String,
    pub pod_name: String,
    pub uid: String,
    pub containers: Vec<ContainerInfo>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct ContainerInfo {
    pub name: String,
    pub container_id: String,
    pub log_path: PathBuf,
}

pub struct KubernetesWatcher {
    node_name: String,
    pod_log_dir: PathBuf,
    event_sender: Sender<LogEvent>,
    hostname: String,
    pods: HashMap<String, PodMetadata>,
    parser: LogParser,
}

impl KubernetesWatcher {
    pub fn new(
        node_name: String,
        pod_log_dir: PathBuf,
        event_sender: Sender<LogEvent>,
        hostname: String,
    ) -> Self {
        Self {
            node_name,
            pod_log_dir,
            event_sender,
            hostname,
            pods: HashMap::new(),
            parser: LogParser::new(),
        }
    }

    pub async fn run(&mut self) -> Result<()> {
        info!(
            "Starting Kubernetes watcher for node {}, pod log dir: {:?}",
            self.node_name, self.pod_log_dir
        );

        self.discover_existing_pods().await?;

        loop {
            if let Err(e) = self.watch_pod_directories().await {
                error!("Error watching pod directories: {}", e);
            }
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    }

    async fn discover_existing_pods(&mut self) -> Result<()> {
        if !self.pod_log_dir.exists() {
            warn!("Pod log directory {:?} does not exist", self.pod_log_dir);
            return Ok(());
        }

        let mut entries = tokio::fs::read_dir(&self.pod_log_dir).await?;
        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            if path.is_dir() {
                self.discover_pod_from_path(&path).await?;
            }
        }

        info!("Discovered {} existing pods", self.pods.len());
        Ok(())
    }

    async fn discover_pod_from_path(&mut self, pod_path: &Path) -> Result<()> {
        let dir_name = pod_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("");

        let parts: Vec<&str> = dir_name.splitn(3, '_').collect();
        if parts.len() < 3 {
            debug!("Skipping non-pod directory: {}", dir_name);
            return Ok(());
        }

        let namespace = parts[0];
        let pod_name = parts[1];
        let uid = parts[2];

        let mut containers = Vec::new();

        let mut entries = tokio::fs::read_dir(pod_path).await?;
        while let Some(entry) = entries.next_entry().await? {
            let container_path = entry.path();
            if container_path.is_dir() {
                let container_name = container_path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("unknown")
                    .to_string();

                let log_file = container_path.join("0.log");
                if log_file.exists() {
                    containers.push(ContainerInfo {
                        name: container_name,
                        container_id: String::new(),
                        log_path: log_file,
                    });
                }
            }
        }

        if !containers.is_empty() {
            let pod_meta = PodMetadata {
                namespace: namespace.to_string(),
                pod_name: pod_name.to_string(),
                uid: uid.to_string(),
                containers,
                labels: HashMap::new(),
            };

            debug!(
                "Discovered pod: {}/{} with {} containers",
                namespace,
                pod_name,
                pod_meta.containers.len()
            );

            self.pods.insert(dir_name.to_string(), pod_meta);
        }

        Ok(())
    }

    pub async fn try_enrich_from_k8s_api(&mut self) -> Result<()> {
        info!("Attempting to connect to Kubernetes API for Pod metadata enrichment");

        let client = self.create_k8s_client().await?;

        let pods: kube::Api<k8s_openapi::api::core::v1::Pod> =
            kube::Api::all(client.clone());

        let lp = kube::api::ListParams::default()
            .fields(&format!("spec.nodeName={}", self.node_name));

        let pod_list = pods.list(&lp).await?;
        let items = pod_list.items;

        info!("Found {} pods on this node via K8s API", items.len());

        for pod in items {
            let namespace = pod.metadata.namespace.clone().unwrap_or_default();
            let pod_name = pod.metadata.name.clone().unwrap_or_default();
            let uid = pod.metadata.uid.clone().unwrap_or_default();
            let labels = pod.metadata.labels.clone().unwrap_or_default();

            let dir_key = format!("{}_{}_{}", namespace, pod_name, uid);

            if let Some(pod_meta) = self.pods.get_mut(&dir_key) {
                pod_meta.labels = labels.into_iter().collect();
                debug!("Enriched pod {}/{} with {} labels", namespace, pod_name, pod_meta.labels.len());
            }
        }

        Ok(())
    }

    async fn create_k8s_client(&self) -> Result<kube::Client> {
        let config = kube::Config::infer().await.context(
            "Failed to infer Kubernetes config. Ensure the agent is running in a cluster or KUBECONFIG is set",
        )?;
        let client = kube::Client::try_from(config)?;
        Ok(client)
    }

    async fn watch_pod_directories(&mut self) -> Result<()> {
        if !self.pod_log_dir.exists() {
            return Ok(());
        }

        let mut current_pods = HashMap::new();
        let mut entries = tokio::fs::read_dir(&self.pod_log_dir).await?;

        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            if path.is_dir() {
                let dir_name = path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("")
                    .to_string();

                if !self.pods.contains_key(&dir_name) {
                    self.discover_pod_from_path(&path).await?;
                    info!("New pod discovered: {}", dir_name);
                }
                current_pods.insert(dir_name, true);
            }
        }

        let removed: Vec<String> = self
            .pods
            .keys()
            .filter(|k| !current_pods.contains_key(k.as_str()))
            .cloned()
            .collect();

        for key in removed {
            info!("Pod removed: {}", key);
            self.pods.remove(&key);
        }

        for (_, pod_meta) in &self.pods {
            for container in &pod_meta.containers {
                if container.log_path.exists() {
                    self.tail_container_log(pod_meta, container).await?;
                }
            }
        }

        Ok(())
    }

    async fn tail_container_log(
        &self,
        pod_meta: &PodMetadata,
        container: &ContainerInfo,
    ) -> Result<()> {
        let content = tokio::fs::read_to_string(&container.log_path).await;
        match content {
            Ok(content) => {
                for line in content.lines().rev().take(10) {
                    if let Some(mut event) = self.parser.parse(line, &container.name) {
                        event.hostname = self.hostname.clone();
                        event.source_file = container.log_path.to_string_lossy().to_string();
                        event.timestamp = Utc::now();

                        event.add_field("k8s_namespace", serde_json::Value::String(pod_meta.namespace.clone()));
                        event.add_field("k8s_pod_name", serde_json::Value::String(pod_meta.pod_name.clone()));
                        event.add_field("k8s_container_name", serde_json::Value::String(container.name.clone()));
                        event.add_field("k8s_pod_uid", serde_json::Value::String(pod_meta.uid.clone()));

                        for (key, value) in &pod_meta.labels {
                            event.add_field(
                                format!("k8s_label_{}", key),
                                serde_json::Value::String(value.clone()),
                            );
                        }

                        if self.event_sender.send(event).await.is_err() {
                            warn!("Event channel closed");
                            return Ok(());
                        }
                    }
                }
            }
            Err(e) => {
                debug!("Cannot read log file {:?}: {}", container.log_path, e);
            }
        }

        Ok(())
    }

    pub fn get_file_tail_configs(&self) -> Vec<LogTailConfig> {
        let mut configs = Vec::new();

        for (_, pod_meta) in &self.pods {
            for container in &pod_meta.containers {
                let service_name = format!(
                    "{}-{}-{}",
                    pod_meta.namespace, pod_meta.pod_name, container.name
                );

                configs.push(LogTailConfig {
                    path: container.log_path.to_string_lossy().to_string(),
                    path_pattern: container.log_path.to_string_lossy().to_string(),
                    service_name,
                    multiline: false,
                    multiline_pattern: None,
                    encoding: "utf-8".to_string(),
                    start_from_beginning: false,
                    tags: Vec::new(),
                });
            }
        }

        configs
    }
}
