use chrono::{DateTime, Duration, Utc};
use std::collections::BTreeMap;

use common::metrics::{Labels, MetricPoint};

pub struct MetricCache {
    data: BTreeMap<String, BTreeMap<String, Vec<MetricPoint>>>,
    ttl: Duration,
}

impl MetricCache {
    pub fn new(ttl: Duration) -> Self {
        Self {
            data: BTreeMap::new(),
            ttl,
        }
    }

    pub fn insert(&mut self, metric_name: &str, labels: &Labels, point: MetricPoint) {
        let labels_key = self.labels_to_key(labels);
        let metric_entry = self.data.entry(metric_name.to_string()).or_insert_with(BTreeMap::new);
        let points = metric_entry.entry(labels_key).or_insert_with(Vec::new);
        points.push(point);

        let cutoff = Utc::now() - self.ttl;
        points.retain(|p| p.timestamp >= cutoff);
    }

    pub fn query(
        &self,
        metric_name: &str,
        labels: &Labels,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Vec<(Labels, Vec<MetricPoint>)> {
        let mut results = Vec::new();

        if let Some(metric_entry) = self.data.get(metric_name) {
            let labels_filter = self.labels_to_filter(labels);

            for (labels_key, points) in metric_entry {
                if self.match_labels(labels_key, &labels_filter) {
                    let filtered_points: Vec<MetricPoint> = points
                        .iter()
                        .filter(|p| p.timestamp >= start && p.timestamp <= end)
                        .cloned()
                        .collect();

                    if !filtered_points.is_empty() {
                        let parsed_labels = self.key_to_labels(labels_key);
                        results.push((parsed_labels, filtered_points));
                    }
                }
            }
        }

        results
    }

    fn labels_to_key(&self, labels: &Labels) -> String {
        let mut parts: Vec<String> = labels
            .0
            .iter()
            .map(|l| format!("{}={}", l.name, l.value))
            .collect();
        parts.sort();
        parts.join(",")
    }

    fn labels_to_filter(&self, labels: &Labels) -> BTreeMap<String, String> {
        labels
            .0
            .iter()
            .map(|l| (l.name.clone(), l.value.clone()))
            .collect()
    }

    fn match_labels(&self, key: &str, filter: &BTreeMap<String, String>) -> bool {
        if filter.is_empty() {
            return true;
        }

        for (k, v) in filter {
            let expected = format!("{}={}", k, v);
            if !key.contains(&expected) {
                return false;
            }
        }

        true
    }

    fn key_to_labels(&self, key: &str) -> Labels {
        let labels = key
            .split(',')
            .filter_map(|part| {
                let mut parts = part.splitn(2, '=');
                let name = parts.next()?.to_string();
                let value = parts.next()?.to_string();
                Some(common::metrics::Label { name, value })
            })
            .collect();

        Labels(labels)
    }

    pub fn cleanup_expired(&mut self) {
        let cutoff = Utc::now() - self.ttl;
        for metric_entry in self.data.values_mut() {
            for points in metric_entry.values_mut() {
                points.retain(|p| p.timestamp >= cutoff);
            }
        }
    }

    pub fn size(&self) -> usize {
        self.data.values().map(|m| m.values().map(|v| v.len()).sum::<usize>()).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::metrics::Label;

    #[test]
    fn test_cache_insert_query() {
        let mut cache = MetricCache::new(Duration::hours(1));

        let labels = Labels::from_vec(vec![Label {
            name: "service".to_string(),
            value: "test".to_string(),
        }]);

        let now = Utc::now();
        cache.insert("test_metric", &labels, MetricPoint::new(now, 42.0));

        let start = now - Duration::minutes(5);
        let end = now + Duration::minutes(5);
        let results = cache.query("test_metric", &Labels::new(), start, end);

        assert_eq!(results.len(), 1);
        assert_eq!(results[0].1.len(), 1);
        assert_eq!(results[0].1[0].value, 42.0);
    }
}
