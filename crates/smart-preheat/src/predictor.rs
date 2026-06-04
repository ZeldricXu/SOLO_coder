use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

use common::models::HeatRecord;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContentHeatSample {
    pub url: String,
    pub region: String,
    pub timestamp: DateTime<Utc>,
    pub request_count: u64,
}

#[derive(Debug, Clone)]
pub struct ExponentialSmoothing {
    alpha: f64,
    history: Arc<RwLock<HashMap<(String, String), Vec<f64>>>>,
}

impl ExponentialSmoothing {
    pub fn new(alpha: f64) -> Self {
        ExponentialSmoothing {
            alpha: alpha.clamp(0.0, 1.0),
            history: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn record_access(&self, url: &str, region: &str) {
        let key = (url.to_string(), region.to_string());
        let mut history = self.history.write().await;

        let values = history.entry(key).or_insert_with(Vec::new);
        let last = values.last().copied().unwrap_or(0.0);
        let smoothed = self.alpha * 1.0 + (1.0 - self.alpha) * last;
        values.push(smoothed);

        if values.len() > 1000 {
            let drain_count = values.len() - 500;
            values.drain(0..drain_count);
        }
    }

    pub async fn record_sample(&self, sample: &ContentHeatSample) {
        let key = (sample.url.clone(), sample.region.clone());
        let mut history = self.history.write().await;

        let values = history.entry(key).or_insert_with(Vec::new);
        let last = values.last().copied().unwrap_or(0.0);
        let smoothed = self.alpha * sample.request_count as f64 + (1.0 - self.alpha) * last;
        values.push(smoothed);

        if values.len() > 1000 {
            let drain_count = values.len() - 500;
            values.drain(0..drain_count);
        }
    }

    pub async fn predict(&self, url: &str, region: &str, hours_ahead: u64) -> f64 {
        let key = (url.to_string(), region.to_string());
        let history = self.history.read().await;

        match history.get(&key) {
            Some(values) if !values.is_empty() => {
                let last_smoothed = *values.last().unwrap();
                let trend = self.estimate_trend(values);
                (last_smoothed + trend * hours_ahead as f64).max(0.0)
            }
            _ => 0.0,
        }
    }

    pub async fn predict_all_hot_content(&self, threshold: f64) -> Vec<(String, String, f64)> {
        let history = self.history.read().await;
        let mut results = Vec::new();

        for ((url, region), values) in history.iter() {
            if values.is_empty() {
                continue;
            }
            let last_smoothed = *values.last().unwrap();
            if last_smoothed >= threshold {
                results.push((url.clone(), region.clone(), last_smoothed));
            }
        }

        results.sort_by(|a, b| b.2.partial_cmp(&a.2).unwrap_or(std::cmp::Ordering::Equal));
        results
    }

    fn estimate_trend(&self, values: &[f64]) -> f64 {
        if values.len() < 2 {
            return 0.0;
        }

        let n = values.len().min(10);
        let recent: Vec<f64> = values.iter().rev().take(n).copied().collect();

        if recent.len() < 2 {
            return 0.0;
        }

        let mut diffs = Vec::new();
        for i in 1..recent.len() {
            diffs.push(recent[i - 1] - recent[i]);
        }

        let sum: f64 = diffs.iter().sum();
        sum / diffs.len() as f64
    }

    pub async fn get_history_snapshot(&self) -> HashMap<(String, String), Vec<f64>> {
        let history = self.history.read().await;
        history.clone()
    }

    pub async fn to_heat_records(&self) -> Vec<HeatRecord> {
        let history = self.history.read().await;
        let mut records = Vec::new();

        for ((url, region), values) in history.iter() {
            if values.is_empty() {
                continue;
            }
            let access_count = *values.last().unwrap();
            records.push(HeatRecord {
                url: url.clone(),
                region: region.clone(),
                timestamp: Utc::now(),
                access_count: access_count as u64,
            });
        }

        records
    }
}

impl Default for ExponentialSmoothing {
    fn default() -> Self {
        ExponentialSmoothing::new(0.3)
    }
}
