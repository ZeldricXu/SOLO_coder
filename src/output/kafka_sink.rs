use crate::{AlertEvent, WindowStats};
use crate::config::{ConfigHandle, KafkaSinkConfig, MinIOSinkConfig};
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use rdkafka::config::ClientConfig;
use rdkafka::message::{Header, OwnedHeaders};
use rdkafka::producer::{FutureProducer, FutureRecord};
use rdkafka::util::Timeout;
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration as StdDuration;
use tracing::{debug, error, info, warn};

pub struct KafkaSink {
    config: ConfigHandle,
    inner: Arc<RwLock<KafkaSinkInner>>,
}

struct KafkaSinkInner {
    producer: Option<FutureProducer>,
    anomaly_topic: String,
    stats_topic: String,
    anomaly_queue: VecDeque<AlertEvent>,
    stats_queue: VecDeque<WindowStats>,
}

impl KafkaSink {
    pub fn new(config: ConfigHandle) -> Self {
        Self {
            config,
            inner: Arc::new(RwLock::new(KafkaSinkInner {
                producer: None,
                anomaly_topic: String::new(),
                stats_topic: String::new(),
                anomaly_queue: VecDeque::new(),
                stats_queue: VecDeque::new(),
            })),
        }
    }

    pub async fn init_from_config(&self) {
        let cfg = self.config.read().await;
        if let Some(kafka_cfg) = &cfg.sink.kafka {
            self.init(kafka_cfg.clone());
        }
    }

    pub fn init(&self, cfg: KafkaSinkConfig) {
        let mut inner = self.inner.write();
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &cfg.brokers)
            .set("message.timeout.ms", "30000")
            .set("queue.buffering.max.ms", cfg.producer_linger_ms.to_string())
            .set(
                "batch.num.messages",
                (cfg.batch_size.max(1) / 100).max(1).to_string(),
            )
            .set(
                "compression.codec",
                if cfg.compression.is_empty() {
                    "none"
                } else {
                    cfg.compression.as_str()
                },
            )
            .set("request.required.acks", "1")
            .create()
            .expect("Failed to create Kafka producer");
        inner.producer = Some(producer);
        inner.anomaly_topic = cfg.anomaly_topic.clone();
        inner.stats_topic = cfg.stats_topic.clone();
        info!("Kafka producer initialized (brokers={})", cfg.brokers);
    }

    pub fn enqueue_anomaly(&self, event: AlertEvent) {
        let mut inner = self.inner.write();
        inner.anomaly_queue.push_back(event);
    }

    pub fn enqueue_stats(&self, stats: WindowStats) {
        let mut inner = self.inner.write();
        inner.stats_queue.push_back(stats);
    }

    pub async fn flush(&self) {
        let anomalies: Vec<AlertEvent>;
        let stats: Vec<WindowStats>;
        let producer_opt: Option<FutureProducer>;
        let (anom_topic, stats_topic) = {
            let mut inner = self.inner.write();
            anomalies = inner.anomaly_queue.drain(..).collect();
            stats = inner.stats_queue.drain(..).collect();
            producer_opt = inner.producer.clone();
            (inner.anomaly_topic.clone(), inner.stats_topic.clone())
        };

        let producer = match producer_opt {
            Some(p) => p,
            None => return,
        };

        for ev in anomalies {
            let payload = match serde_json::to_vec(&ev) {
                Ok(v) => v,
                Err(e) => {
                    warn!("Failed to serialize alert event: {}", e);
                    continue;
                }
            };
            let topic = anom_topic.clone();
            let key = format!("{}:{}", ev.service, ev.rule_id);
            let producer_c = producer.clone();
            tokio::spawn(async move {
                let rec = FutureRecord::to(&topic)
                    .payload(&payload)
                    .key(&key)
                    .headers(
                        OwnedHeaders::new()
                            .insert(Header { key: "severity", value: Some(ev.severity.to_string().as_bytes()) })
                            .insert(Header { key: "service", value: Some(ev.service.as_bytes()) }),
                    );
                match producer_c.send(rec, Timeout::After(StdDuration::from_secs(5))).await {
                    Ok(delivery) => {
                        debug!(
                            "Anomaly sent to kafka topic {}: partition={:?} offset={:?}",
                            topic, delivery.0, delivery.1
                        );
                    }
                    Err((e, _)) => {
                        warn!("Failed to send anomaly to kafka: {}", e);
                    }
                }
            });
        }

        for st in stats {
            let payload = match serde_json::to_vec(&st) {
                Ok(v) => v,
                Err(e) => {
                    warn!("Failed to serialize window stats: {}", e);
                    continue;
                }
            };
            let topic = stats_topic.clone();
            let key = format!("{}:{}", st.key.service, st.key.level);
            let producer_c = producer.clone();
            tokio::spawn(async move {
                let rec = FutureRecord::to(&topic)
                    .payload(&payload)
                    .key(&key);
                match producer_c.send(rec, Timeout::After(StdDuration::from_secs(5))).await {
                    Ok(_) => {}
                    Err((e, _)) => {
                        warn!("Failed to send stats to kafka: {}", e);
                    }
                }
            });
        }
    }
}
