use std::sync::Arc;
use std::time::Duration;

use common::alert::Alert;
use moka::future::Cache;

pub struct AlertDeduplicator {
    cache: Cache<String, ()>,
    retention: Duration,
}

impl AlertDeduplicator {
    pub fn new() -> Self {
        Self::with_retention(Duration::from_secs(300))
    }

    pub fn with_retention(retention: Duration) -> Self {
        let cache = Cache::builder()
            .time_to_live(retention)
            .max_capacity(100_000)
            .build();

        Self { cache, retention }
    }

    pub async fn check_and_record(&self, alert: &Alert) -> bool {
        let fingerprint = self.fingerprint(alert);

        match self.cache.contains_key(&fingerprint) {
            true => true,
            false => {
                self.cache.insert(fingerprint, ()).await;
                metrics::counter!("alert_manager_dedup_cache_entries_total").increment(1);
                false
            }
        }
    }

    fn fingerprint(&self, alert: &Alert) -> String {
        let labels_str = alert
            .labels
            .0
            .iter()
            .map(|l| format!("{}={}", l.name, l.value))
            .collect::<Vec<_>>()
            .join(",");

        format!("{}:{}:{}", alert.name, alert.severity.as_str(), labels_str)
    }

    pub async fn cleanup(&self) {
        self.cache.invalidate_all();
        self.cache.run_pending_tasks().await;
    }

    pub fn entry_count(&self) -> u64 {
        self.cache.entry_count()
    }
}

impl Default for AlertDeduplicator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::alert::{AlertSeverity, DetectionMethod};
    use common::metrics::Labels;

    #[tokio::test]
    async fn test_deduplication() {
        let dedup = AlertDeduplicator::new();

        let alert = Alert::new(
            "test_alert".to_string(),
            AlertSeverity::Warning,
            Labels::new(),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        assert!(!dedup.check_and_record(&alert).await);
        assert!(dedup.check_and_record(&alert).await);
    }

    #[tokio::test]
    async fn test_dedup_different_alerts() {
        let dedup = AlertDeduplicator::new();

        let alert1 = Alert::new(
            "alert1".to_string(),
            AlertSeverity::Warning,
            Labels::new(),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        let alert2 = Alert::new(
            "alert2".to_string(),
            AlertSeverity::Critical,
            Labels::new(),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        assert!(!dedup.check_and_record(&alert1).await);
        assert!(!dedup.check_and_record(&alert2).await);
        assert!(dedup.check_and_record(&alert1).await);
        assert!(dedup.check_and_record(&alert2).await);
    }

    #[tokio::test]
    async fn test_dedup_with_labels() {
        let dedup = AlertDeduplicator::new();

        use common::metrics::Label;

        let alert1 = Alert::new(
            "alert".to_string(),
            AlertSeverity::Warning,
            Labels::from_vec(vec![Label {
                name: "host".to_string(),
                value: "host1".to_string(),
            }]),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        let alert2 = Alert::new(
            "alert".to_string(),
            AlertSeverity::Warning,
            Labels::from_vec(vec![Label {
                name: "host".to_string(),
                value: "host2".to_string(),
            }]),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        assert!(!dedup.check_and_record(&alert1).await);
        assert!(!dedup.check_and_record(&alert2).await);
        assert!(dedup.check_and_record(&alert1).await);
        assert!(dedup.check_and_record(&alert2).await);
    }

    #[tokio::test]
    async fn test_ttl_expiration() {
        let dedup = AlertDeduplicator::with_retention(Duration::from_millis(100));

        let alert = Alert::new(
            "test_alert".to_string(),
            AlertSeverity::Warning,
            Labels::new(),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        assert!(!dedup.check_and_record(&alert).await);
        assert!(dedup.check_and_record(&alert).await);

        tokio::time::sleep(Duration::from_millis(150)).await;
        dedup.cache.run_pending_tasks().await;

        assert!(!dedup.check_and_record(&alert).await);
    }

    #[tokio::test]
    async fn test_concurrent_access() {
        let dedup = Arc::new(AlertDeduplicator::new());
        let mut handles = Vec::new();

        for i in 0..100 {
            let dedup_clone = dedup.clone();
            let handle = tokio::spawn(async move {
                let alert = Alert::new(
                    format!("alert_{}", i % 10),
                    AlertSeverity::Warning,
                    Labels::new(),
                    DetectionMethod::StaticThreshold,
                    1.0,
                );
                dedup_clone.check_and_record(&alert).await
            });
            handles.push(handle);
        }

        for handle in handles {
            let _ = handle.await;
        }

        dedup.cache.run_pending_tasks().await;
        tokio::time::sleep(Duration::from_millis(100)).await;
        dedup.cache.run_pending_tasks().await;

        assert_eq!(dedup.entry_count(), 10);
    }
}
