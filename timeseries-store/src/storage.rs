use anyhow::{Context, Result};
use arrow::array::{Float64Array, StringArray, TimestampNanosecondArray};
use arrow::datatypes::{DataType, Field, Schema, SchemaRef};
use arrow::record_batch::RecordBatch;
use chrono::{DateTime, Duration, Utc};
use parquet::arrow::ArrowWriter;
use parquet::basic::{Compression, Encoding};
use parquet::file::properties::WriterProperties;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::task;
use tracing::{debug, error, info, warn};

use common::metrics::{Label, Labels, TimeSeries};

use crate::cache::MetricCache;
use crate::compaction::CompactionManager;
use crate::corruption_scanner::CorruptionScanner;
use crate::partition::{get_partition_key, PartitionManager};

pub struct TimeSeriesStore {
    base_path: PathBuf,
    schema: SchemaRef,
    cache: Arc<RwLock<MetricCache>>,
    partition_manager: PartitionManager,
    write_buffer: Arc<RwLock<BTreeMap<String, Vec<BufferedPoint>>>>,
    buffer_flush_interval_secs: u64,
    max_buffer_size: usize,
    compaction_manager: Option<Arc<CompactionManager>>,
    corruption_scanner: Option<Arc<CorruptionScanner>>,
}

#[derive(Debug, Clone)]
struct BufferedPoint {
    timestamp: i64,
    value: f64,
    labels: BTreeMap<String, String>,
}

impl TimeSeriesStore {
    pub fn new(base_path: PathBuf) -> Self {
        let schema = Arc::new(Schema::new(vec![
            Field::new("timestamp", DataType::Timestamp(arrow::datatypes::TimeUnit::Nanosecond, None), false),
            Field::new("value", DataType::Float64, false),
            Field::new("metric_name", DataType::Utf8, false),
            Field::new("labels_json", DataType::Utf8, false),
        ]));

        Self {
            base_path: base_path.clone(),
            schema,
            cache: Arc::new(RwLock::new(MetricCache::new(Duration::hours(1)))),
            partition_manager: PartitionManager::new(base_path.clone()),
            write_buffer: Arc::new(RwLock::new(BTreeMap::new())),
            buffer_flush_interval_secs: 60,
            max_buffer_size: 10000,
            compaction_manager: None,
            corruption_scanner: None,
        }
    }

    pub fn with_compaction(mut self) -> Self {
        self.compaction_manager = Some(Arc::new(CompactionManager::new(self.base_path.clone())));
        self
    }

    pub fn with_corruption_scan(mut self) -> Self {
        self.corruption_scanner = Some(Arc::new(CorruptionScanner::new(self.base_path.clone())));
        self
    }

    pub fn compaction_manager(&self) -> Option<Arc<CompactionManager>> {
        self.compaction_manager.clone()
    }

    pub fn corruption_scanner(&self) -> Option<Arc<CorruptionScanner>> {
        self.corruption_scanner.clone()
    }

    pub async fn insert(&self, series: &TimeSeries) -> Result<()> {
        let mut buffer = self.write_buffer.write().await;

        for point in &series.points {
            let partition_key = get_partition_key(&point.timestamp);
            let labels_btree = series.labels.to_btree();

            let buffered = BufferedPoint {
                timestamp: point.timestamp.timestamp_nanos_opt().unwrap_or(0),
                value: point.value,
                labels: labels_btree,
            };

            buffer
                .entry(partition_key.clone())
                .or_insert_with(Vec::new)
                .push(buffered);

            self.cache.write().await
                .insert(&series.metric_name, &series.labels, point.clone());
        }

        if buffer.len() >= self.max_buffer_size {
            drop(buffer);
            self.flush_buffer().await?;
        }

        Ok(())
    }

    pub async fn flush_buffer(&self) -> Result<()> {
        let mut buffer = self.write_buffer.write().await;
        let to_flush: BTreeMap<String, Vec<BufferedPoint>> = std::mem::take(&mut *buffer);
        drop(buffer);

        for (partition_key, points) in to_flush {
            if points.is_empty() {
                continue;
            }

            self.write_partition(&partition_key, &points).await?;
        }

        Ok(())
    }

    async fn write_partition(&self, partition_key: &str, points: &[BufferedPoint]) -> Result<()> {
        let partition_path = self.partition_manager.ensure_partition(partition_key)?;
        let file_stem = format!("metrics_{}", Utc::now().format("%Y%m%d_%H%M%S_%f"));
        let file_path = partition_path.join(format!("{}.parquet", file_stem));
        let temp_file_path = partition_path.join(format!("{}.tmp", file_stem));

        debug!("Writing {} points to {}", points.len(), file_path.display());

        let schema = self.schema.clone();
        let points_clone = points.to_vec();
        let temp_path_clone = temp_file_path.clone();
        let final_path_clone = file_path.clone();

        task::spawn_blocking(move || -> Result<()> {
            let timestamps: Vec<i64> = points_clone.iter().map(|p| p.timestamp).collect();
            let values: Vec<f64> = points_clone.iter().map(|p| p.value).collect();
            let metric_names: Vec<&str> = points_clone.iter().map(|_| "metric").collect();
            let labels_json: Vec<String> = points_clone
                .iter()
                .map(|p| serde_json::to_string(&p.labels).unwrap_or_default())
                .collect();

            let batch = RecordBatch::try_new(
                schema.clone(),
                vec![
                    Arc::new(TimestampNanosecondArray::from(timestamps)),
                    Arc::new(Float64Array::from(values)),
                    Arc::new(StringArray::from(metric_names)),
                    Arc::new(StringArray::from(labels_json)),
                ],
            )?;

            let props = WriterProperties::builder()
                .set_compression(Compression::SNAPPY)
                .set_encoding(Encoding::PLAIN)
                .build();

            let file = std::fs::File::create(&temp_path_clone)
                .with_context(|| format!("Failed to create temp file: {}", temp_path_clone.display()))?;

            let mut writer = ArrowWriter::try_new(file, schema.clone(), Some(props))?;
            writer.write(&batch)?;
            writer.close()?;

            std::fs::rename(&temp_path_clone, &final_path_clone)
                .with_context(|| format!(
                    "Failed to rename temp file {} to {}",
                    temp_path_clone.display(),
                    final_path_clone.display()
                ))?;

            Ok(())
        }).await
            .with_context(|| "Parquet write task panicked")??;

        info!(
            "Flushed {} points to partition {}",
            points.len(),
            partition_key
        );

        Ok(())
    }

    pub async fn query(
        &self,
        metric_name: &str,
        labels: &Labels,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<TimeSeries>> {
        let cache_results = self.cache.read().await.query(metric_name, labels, start, end);

        let mut results = Vec::new();
        for (labels, points) in cache_results {
            let mut ts = TimeSeries::new(metric_name.to_string(), labels);
            for point in points {
                ts.points.push(point);
            }
            results.push(ts);
        }

        let cold_results = self.query_cold(metric_name, labels, start, end).await?;
        results.extend(cold_results);

        Ok(results)
    }

    async fn query_cold(
        &self,
        metric_name: &str,
        labels: &Labels,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<TimeSeries>> {
        let partitions = self.partition_manager.list_partitions_in_range(start, end)?;
        let mut all_series: BTreeMap<String, TimeSeries> = BTreeMap::new();

        for partition in partitions {
            let files = self.partition_manager.list_files_in_partition(&partition)?;
            for file in files {
                if let Ok(batches) = self.read_parquet_file(&file).await {
                    for batch in batches {
                        self.process_record_batch(
                            &batch,
                            metric_name,
                            labels,
                            start,
                            end,
                            &mut all_series,
                        )?;
                    }
                }
            }
        }

        Ok(all_series.into_values().collect())
    }

    async fn read_parquet_file(&self, path: &Path) -> Result<Vec<RecordBatch>> {
        let path_str = path.to_string_lossy().to_string();
        let path_clone = path.to_path_buf();

        task::spawn_blocking(move || -> Result<Vec<RecordBatch>> {
            use datafusion::prelude::*;

            let rt = tokio::runtime::Handle::try_current()
                .ok()
                .map(|h| h.clone());

            let result = if let Some(rt) = rt {
                rt.block_on(async {
                    let ctx = SessionContext::new();
                    match ctx.read_parquet(path_str, ParquetReadOptions::default()).await {
                        Ok(df) => df.collect().await,
                        Err(e) => Err(e),
                    }
                })
            } else {
                let rt = tokio::runtime::Runtime::new()?;
                rt.block_on(async {
                    let ctx = SessionContext::new();
                    match ctx.read_parquet(path_str, ParquetReadOptions::default()).await {
                        Ok(df) => df.collect().await,
                        Err(e) => Err(e),
                    }
                })
            };

            match result {
                Ok(batches) => Ok(batches),
                Err(e) => {
                    error!(
                        "Corrupt parquet file detected: {}, error: {}. Skipping...",
                        path_clone.display(),
                        e
                    );
                    metrics::counter!("timeseries_store_corrupt_files_total", "path" => path_clone.to_string_lossy().to_string())
                        .increment(1);
                    Ok(Vec::new())
                }
            }
        }).await
            .with_context(|| "Parquet read task panicked")?
    }

    fn process_record_batch(
        &self,
        batch: &RecordBatch,
        metric_name: &str,
        labels: &Labels,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        result: &mut BTreeMap<String, TimeSeries>,
    ) -> Result<()> {
        let timestamp_array = batch
            .column(0)
            .as_any()
            .downcast_ref::<TimestampNanosecondArray>()
            .context("Invalid timestamp array")?;
        let value_array = batch
            .column(1)
            .as_any()
            .downcast_ref::<Float64Array>()
            .context("Invalid value array")?;
        let labels_array = batch
            .column(3)
            .as_any()
            .downcast_ref::<StringArray>()
            .context("Invalid labels array")?;

        let start_nanos = start.timestamp_nanos_opt().unwrap_or(0);
        let end_nanos = end.timestamp_nanos_opt().unwrap_or(i64::MAX);

        for i in 0..batch.num_rows() {
            let ts = timestamp_array.value(i);
            if ts < start_nanos || ts > end_nanos {
                continue;
            }

            let labels_str = labels_array.value(i);
            let labels_map: BTreeMap<String, String> =
                serde_json::from_str(labels_str).unwrap_or_default();

            let key = format!("{:?}", labels_map);
            let ts_labels = Labels::from_vec(
                labels_map
                    .into_iter()
                    .map(|(k, v)| Label { name: k, value: v })
                    .collect(),
            );

            let series = result.entry(key).or_insert_with(|| {
                TimeSeries::new(metric_name.to_string(), ts_labels)
            });

            series.add_point(
                DateTime::from_naive_utc_and_offset(
                    chrono::NaiveDateTime::from_timestamp_opt(
                        ts / 1_000_000_000,
                        (ts % 1_000_000_000) as u32,
                    ).unwrap_or_default(),
                    Utc,
                ),
                value_array.value(i),
            );
        }

        Ok(())
    }

    pub async fn run_flush_task(&self) {
        let mut interval =
            tokio::time::interval(tokio::time::Duration::from_secs(self.buffer_flush_interval_secs));
        loop {
            interval.tick().await;
            if let Err(e) = self.flush_buffer().await {
                warn!("Error flushing buffer: {}", e);
            }
        }
    }

    pub async fn run_compaction_task(&self) {
        if let Some(manager) = &self.compaction_manager {
            manager.run_periodic_compaction().await;
        }
    }

    pub async fn run_corruption_scan_task(&self) {
        if let Some(scanner) = &self.corruption_scanner {
            scanner.run_periodic_scan().await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_store_insert_and_query() {
        let dir = tempdir().unwrap();
        let store = TimeSeriesStore::new(dir.path().to_path_buf());

        let mut series = TimeSeries::new(
            "test_metric".to_string(),
            Labels::from_vec(vec![Label {
                name: "service".to_string(),
                value: "test".to_string(),
            }]),
        );
        series.add_point(Utc::now(), 42.0);

        store.insert(&series).await.unwrap();
        store.flush_buffer().await.unwrap();

        let end = Utc::now();
        let start = end - Duration::hours(1);
        let results = store
            .query("test_metric", &Labels::new(), start, end)
            .await
            .unwrap();

        assert!(!results.is_empty());
    }
}
