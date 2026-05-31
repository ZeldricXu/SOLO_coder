use crate::models::StreamSQLError;
use crate::timeseries_compression::data::{MultiResolutionSeries, TimeSeriesBatch, TimeSeriesPoint, TimeResolution, TimeSeriesStatsCalculator};
use crate::timeseries_compression::encoder::{CompressedData, CompressionConfig, CompressionAlgorithm, TimeSeriesEncoder};
use crate::timeseries_compression::decoder::TimeSeriesDecoder;
use crate::timeseries_compression::sampler::{TimeSeriesSampler, DownsampleConfig, DownsampleMethod};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoreConfig {
    pub base_path: String,
    pub compression: CompressionConfig,
    pub resolutions: Vec<TimeResolution>,
    pub downsample_method: DownsampleMethod,
    pub max_series: usize,
    pub flush_interval_ms: u64,
}

impl Default for StoreConfig {
    fn default() -> Self {
        Self {
            base_path: "./data/timeseries".to_string(),
            compression: CompressionConfig::default(),
            resolutions: vec![
                TimeResolution::Second,
                TimeResolution::Minute,
                TimeResolution::Hour,
                TimeResolution::Day,
            ],
            downsample_method: DownsampleMethod::Avg,
            max_series: 10000,
            flush_interval_ms: 5000,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeriesInfo {
    pub series_id: String,
    pub metric: String,
    pub tags: HashMap<String, String>,
    pub total_points: usize,
    pub start_time: i64,
    pub end_time: i64,
    pub resolutions: Vec<TimeResolution>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub last_updated: chrono::DateTime<chrono::Utc>,
}

pub struct TimeSeriesStore {
    config: StoreConfig,
    encoder: TimeSeriesEncoder,
    decoder: TimeSeriesDecoder,
    series: Arc<RwLock<HashMap<String, MultiResolutionSeries>>>,
    compressed: Arc<RwLock<HashMap<String, CompressedData>>>,
    series_info: Arc<RwLock<HashMap<String, SeriesInfo>>>,
}

impl TimeSeriesStore {
    pub fn new(config: StoreConfig) -> Self {
        let encoder = TimeSeriesEncoder::new(config.compression.clone());
        let decoder = TimeSeriesDecoder::new(config.compression.clone());

        Self {
            config,
            encoder,
            decoder,
            series: Arc::new(RwLock::new(HashMap::new())),
            compressed: Arc::new(RwLock::new(HashMap::new())),
            series_info: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn insert_point(
        &self,
        series_id: impl Into<String>,
        metric: impl Into<String>,
        point: TimeSeriesPoint,
    ) -> Result<(), StreamSQLError> {
        let series_id = series_id.into();
        let metric = metric.into();

        let mut series_map = self.series.write().await;

        if !series_map.contains_key(&series_id) {
            let new_series = MultiResolutionSeries::new(&series_id, &metric);
            series_map.insert(series_id.clone(), new_series);

            let mut info_map = self.series_info.write().await;
            info_map.insert(
                series_id.clone(),
                SeriesInfo {
                    series_id: series_id.clone(),
                    metric: metric.clone(),
                    tags: point.tags.clone(),
                    total_points: 0,
                    start_time: point.timestamp,
                    end_time: point.timestamp,
                    resolutions: vec![TimeResolution::Raw],
                    created_at: chrono::Utc::now(),
                    last_updated: chrono::Utc::now(),
                },
            );
        }

        let series = series_map.get_mut(&series_id).unwrap();

        let raw_batch = series
            .get_resolution(TimeResolution::Raw)
            .cloned()
            .unwrap_or_else(|| {
                let mut batch = TimeSeriesBatch::new(&series_id, &metric);
                batch.resolution = TimeResolution::Raw;
                batch
            });

        let mut updated_batch = raw_batch;
        updated_batch.add_point(point.clone());
        series.add_batch(updated_batch);

        let mut info_map = self.series_info.write().await;
        if let Some(info) = info_map.get_mut(&series_id) {
            info.total_points += 1;
            info.start_time = info.start_time.min(point.timestamp);
            info.end_time = info.end_time.max(point.timestamp);
            info.last_updated = chrono::Utc::now();
        }

        Ok(())
    }

    pub async fn insert_batch(
        &self,
        series_id: impl Into<String>,
        metric: impl Into<String>,
        points: Vec<TimeSeriesPoint>,
    ) -> Result<(), StreamSQLError> {
        let series_id = series_id.into();
        let metric = metric.into();

        let mut series_map = self.series.write().await;

        if !series_map.contains_key(&series_id) {
            let new_series = MultiResolutionSeries::new(&series_id, &metric);
            series_map.insert(series_id.clone(), new_series);

            let start_time = points.iter().map(|p| p.timestamp).min().unwrap_or(0);
            let end_time = points.iter().map(|p| p.timestamp).max().unwrap_or(0);

            let mut info_map = self.series_info.write().await;
            info_map.insert(
                series_id.clone(),
                SeriesInfo {
                    series_id: series_id.clone(),
                    metric: metric.clone(),
                    tags: HashMap::new(),
                    total_points: points.len(),
                    start_time,
                    end_time,
                    resolutions: vec![TimeResolution::Raw],
                    created_at: chrono::Utc::now(),
                    last_updated: chrono::Utc::now(),
                },
            );
        }

        let series = series_map.get_mut(&series_id).unwrap();

        let raw_batch = series
            .get_resolution(TimeResolution::Raw)
            .cloned()
            .unwrap_or_else(|| {
                let mut batch = TimeSeriesBatch::new(&series_id, &metric);
                batch.resolution = TimeResolution::Raw;
                batch
            });

        let mut updated_batch = raw_batch;
        let start_time = points.iter().map(|p| p.timestamp).min().unwrap_or(0);
        let end_time = points.iter().map(|p| p.timestamp).max().unwrap_or(0);

        for point in points {
            updated_batch.add_point(point);
        }
        series.add_batch(updated_batch);

        let mut info_map = self.series_info.write().await;
        if let Some(info) = info_map.get_mut(&series_id) {
            info.total_points += updated_batch.len();
            info.start_time = info.start_time.min(start_time);
            info.end_time = info.end_time.max(end_time);
            info.last_updated = chrono::Utc::now();
        }

        Ok(())
    }

    pub async fn query(
        &self,
        series_id: &str,
        start_time: i64,
        end_time: i64,
        resolution: TimeResolution,
    ) -> Result<Option<TimeSeriesBatch>, StreamSQLError> {
        let series_map = self.series.read().await;

        let series = match series_map.get(series_id) {
            Some(s) => s,
            None => return Ok(None),
        };

        Ok(TimeSeriesSampler::query_by_resolution(
            series,
            start_time,
            end_time,
            resolution,
        ))
    }

    pub async fn downsample_series(&self, series_id: &str) -> Result<(), StreamSQLError> {
        let mut series_map = self.series.write().await;

        let series = match series_map.get_mut(series_id) {
            Some(s) => s,
            None => {
                return Err(StreamSQLError::Compression(format!(
                    "Series not found: {}",
                    series_id
                )))
            }
        };

        let raw_batch = match series.get_resolution(TimeResolution::Raw).cloned() {
            Some(b) => b,
            None => return Ok(()),
        };

        for &resolution in &self.config.resolutions {
            if resolution <= TimeResolution::Raw {
                continue;
            }

            let config = DownsampleConfig {
                target_resolution: resolution,
                method: self.config.downsample_method,
                include_timestamp: true,
            };

            let downsampled = TimeSeriesSampler::downsample(&raw_batch, &config);
            series.add_batch(downsampled);
        }

        let mut info_map = self.series_info.write().await;
        if let Some(info) = info_map.get_mut(series_id) {
            info.resolutions = series.available_resolutions();
        }

        Ok(())
    }

    pub async fn compress_series(&self, series_id: &str) -> Result<CompressedData, StreamSQLError> {
        let series_map = self.series.read().await;

        let series = series_map
            .get(series_id)
            .ok_or_else(|| {
                StreamSQLError::Compression(format!("Series not found: {}", series_id))
            })?;

        let raw_batch = series
            .get_resolution(TimeResolution::Raw)
            .ok_or_else(|| {
                StreamSQLError::Compression(format!("Raw data not found for: {}", series_id))
            })?;

        let compressed = self.encoder.encode(raw_batch)?;

        let mut compressed_map = self.compressed.write().await;
        compressed_map.insert(series_id.to_string(), compressed.clone());

        Ok(compressed)
    }

    pub async fn decompress_series(&self, series_id: &str) -> Result<TimeSeriesBatch, StreamSQLError> {
        let compressed_map = self.compressed.read().await;

        let compressed = compressed_map
            .get(series_id)
            .ok_or_else(|| {
                StreamSQLError::Compression(format!("Compressed data not found: {}", series_id))
            })?;

        self.decoder.decode(compressed)
    }

    pub async fn get_series_info(&self, series_id: &str) -> Option<SeriesInfo> {
        let info_map = self.series_info.read().await;
        info_map.get(series_id).cloned()
    }

    pub async fn list_series(&self) -> Vec<SeriesInfo> {
        let info_map = self.series_info.read().await;
        info_map.values().cloned().collect()
    }

    pub async fn delete_series(&self, series_id: &str) -> Result<(), StreamSQLError> {
        let mut series_map = self.series.write().await;
        let mut compressed_map = self.compressed.write().await;
        let mut info_map = self.series_info.write().await;

        series_map.remove(series_id);
        compressed_map.remove(series_id);
        info_map.remove(series_id);

        Ok(())
    }

    pub async fn get_stats(&self, series_id: &str) -> Option<crate::timeseries_compression::data::TimeSeriesStats> {
        let series_map = self.series.read().await;

        series_map
            .get(series_id)
            .and_then(|s| s.get_resolution(TimeResolution::Raw))
            .map(TimeSeriesStatsCalculator::calculate)
    }

    pub async fn flush(&self) -> Result<(), StreamSQLError> {
        let series_ids: Vec<String> = {
            let series_map = self.series.read().await;
            series_map.keys().cloned().collect()
        };

        for series_id in series_ids {
            let _ = self.downsample_series(&series_id).await;
            let _ = self.compress_series(&series_id).await;
        }

        Ok(())
    }

    pub fn config(&self) -> &StoreConfig {
        &self.config
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn create_test_point(ts: i64, value: f64) -> TimeSeriesPoint {
        TimeSeriesPoint {
            timestamp: ts,
            value,
            tags: HashMap::new(),
        }
    }

    #[tokio::test]
    async fn test_store_creation() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let series = store.list_series().await;
        assert!(series.is_empty());
    }

    #[tokio::test]
    async fn test_insert_point() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        store
            .insert_point("s1", "cpu_usage", create_test_point(1000, 50.0))
            .await
            .unwrap();

        let info = store.get_series_info("s1").await;
        assert!(info.is_some());
        let info = info.unwrap();
        assert_eq!(info.total_points, 1);
    }

    #[tokio::test]
    async fn test_insert_batch() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let points: Vec<TimeSeriesPoint> = (0..10)
            .map(|i| create_test_point(i * 1000, i as f64))
            .collect();

        store.insert_batch("s1", "cpu_usage", points).await.unwrap();

        let info = store.get_series_info("s1").await.unwrap();
        assert_eq!(info.total_points, 10);
    }

    #[tokio::test]
    async fn test_query() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let points: Vec<TimeSeriesPoint> = (0..60)
            .map(|i| create_test_point(i * 1000, i as f64))
            .collect();

        store.insert_batch("s1", "cpu_usage", points).await.unwrap();

        let result = store
            .query("s1", 0, 30000, TimeResolution::Raw)
            .await
            .unwrap();

        assert!(result.is_some());
        let result = result.unwrap();
        assert!(result.len() >= 30);
    }

    #[tokio::test]
    async fn test_downsample() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let points: Vec<TimeSeriesPoint> = (0..120)
            .map(|i| create_test_point(i * 1000, i as f64))
            .collect();

        store.insert_batch("s1", "cpu_usage", points).await.unwrap();
        store.downsample_series("s1").await.unwrap();

        let info = store.get_series_info("s1").await.unwrap();
        assert!(info.resolutions.len() > 1);
    }

    #[tokio::test]
    async fn test_compress_decompress() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let points: Vec<TimeSeriesPoint> = (0..50)
            .map(|i| create_test_point(i * 1000, i as f64))
            .collect();

        store.insert_batch("s1", "cpu_usage", points).await.unwrap();

        let compressed = store.compress_series("s1").await.unwrap();
        assert!(compressed.compressed_size_bytes > 0);
        assert!(compressed.compression_ratio >= 1.0);

        let decompressed = store.decompress_series("s1").await.unwrap();
        assert_eq!(decompressed.len(), 50);
    }

    #[tokio::test]
    async fn test_delete_series() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        store
            .insert_point("s1", "cpu_usage", create_test_point(1000, 50.0))
            .await
            .unwrap();

        assert!(store.get_series_info("s1").await.is_some());

        store.delete_series("s1").await.unwrap();
        assert!(store.get_series_info("s1").await.is_none());
    }

    #[tokio::test]
    async fn test_flush() {
        let config = StoreConfig::default();
        let store = TimeSeriesStore::new(config);

        let points: Vec<TimeSeriesPoint> = (0..100)
            .map(|i| create_test_point(i * 1000, i as f64))
            .collect();

        store.insert_batch("s1", "cpu_usage", points).await.unwrap();
        store.flush().await.unwrap();

        let compressed_map = store.compressed.read().await;
        assert!(compressed_map.contains_key("s1"));
    }
}
