use crate::WindowStats;
use crate::config::MinIOSinkConfig;
use arrow_array::{
    ArrayRef, Float64Array, RecordBatch, StringArray, TimestampMicrosecondArray, UInt64Array,
};
use arrow_schema::{DataType, Field, Schema, SchemaRef, TimeUnit};
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use parquet::arrow::AsyncArrowWriter;
use parquet::basic::{Compression, Encoding};
use parquet::file::properties::WriterProperties;
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;
use tracing::{debug, error, info, warn};

fn output_schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        Field::new(
            "window_start",
            DataType::Timestamp(TimeUnit::Microsecond, None),
            false,
        ),
        Field::new(
            "window_end",
            DataType::Timestamp(TimeUnit::Microsecond, None),
            false,
        ),
        Field::new("service", DataType::Utf8, false),
        Field::new("level", DataType::Utf8, false),
        Field::new("count", DataType::UInt64, false),
        Field::new("sum_spend", DataType::Float64, false),
        Field::new("avg_spend", DataType::Float64, false),
        Field::new("p50_spend", DataType::Float64, false),
        Field::new("p95_spend", DataType::Float64, false),
        Field::new("p99_spend", DataType::Float64, false),
        Field::new("min_spend", DataType::Float64, false),
        Field::new("max_spend", DataType::Float64, false),
    ]))
}

fn ts_us(dt: DateTime<Utc>) -> i64 {
    dt.timestamp_micros()
}

fn stats_to_batch(stats: &[WindowStats], schema: SchemaRef) -> Option<RecordBatch> {
    if stats.is_empty() {
        return None;
    }
    let n = stats.len();
    let mut window_start = Vec::with_capacity(n);
    let mut window_end = Vec::with_capacity(n);
    let mut service = Vec::with_capacity(n);
    let mut level = Vec::with_capacity(n);
    let mut count = Vec::with_capacity(n);
    let mut sum_spend = Vec::with_capacity(n);
    let mut avg_spend = Vec::with_capacity(n);
    let mut p50 = Vec::with_capacity(n);
    let mut p95 = Vec::with_capacity(n);
    let mut p99 = Vec::with_capacity(n);
    let mut min_s = Vec::with_capacity(n);
    let mut max_s = Vec::with_capacity(n);

    for s in stats {
        window_start.push(ts_us(s.window_start));
        window_end.push(ts_us(s.window_end));
        service.push(s.key.service.clone());
        level.push(s.key.level.to_string());
        count.push(s.count);
        sum_spend.push(s.sum_spend);
        avg_spend.push(s.avg_spend);
        p50.push(s.p50_spend);
        p95.push(s.p95_spend);
        p99.push(s.p99_spend);
        min_s.push(s.min_spend);
        max_s.push(s.max_spend);
    }

    let cols: Vec<ArrayRef> = vec![
        Arc::new(TimestampMicrosecondArray::from(window_start)),
        Arc::new(TimestampMicrosecondArray::from(window_end)),
        Arc::new(StringArray::from(service)),
        Arc::new(StringArray::from(level)),
        Arc::new(UInt64Array::from(count)),
        Arc::new(Float64Array::from(sum_spend)),
        Arc::new(Float64Array::from(avg_spend)),
        Arc::new(Float64Array::from(p50)),
        Arc::new(Float64Array::from(p95)),
        Arc::new(Float64Array::from(p99)),
        Arc::new(Float64Array::from(min_s)),
        Arc::new(Float64Array::from(max_s)),
    ];

    RecordBatch::try_new(schema, cols).ok()
}

struct ParquetBuffer {
    pending: Vec<WindowStats>,
    last_flush: DateTime<Utc>,
    flush_interval_secs: u64,
}

pub struct MinIOSink {
    cfg: Option<MinIOSinkConfig>,
    buffer: Arc<RwLock<ParquetBuffer>>,
    s3_client: Option<aws_sdk_s3::Client>,
    schema: SchemaRef,
}

impl MinIOSink {
    pub fn new(cfg: Option<MinIOSinkConfig>) -> Self {
        let flush_interval_secs = cfg.as_ref().map(|c| c.flush_interval_secs).unwrap_or(60);
        Self {
            cfg,
            buffer: Arc::new(RwLock::new(ParquetBuffer {
                pending: Vec::new(),
                last_flush: Utc::now(),
                flush_interval_secs,
            })),
            s3_client: None,
            schema: output_schema(),
        }
    }

    pub async fn init(&mut self) {
        let cfg = match &self.cfg {
            Some(c) => c.clone(),
            None => return,
        };
        let aws_cfg = aws_config::from_env()
            .endpoint_url(&cfg.endpoint)
            .region(aws_sdk_s3::config::Region::new("us-east-1"))
            .credentials_provider(aws_sdk_s3::config::Credentials::new(
                &cfg.access_key,
                &cfg.secret_key,
                None,
                None,
                "minio-static",
            ))
            .load()
            .await;
        let s3_cfg = aws_sdk_s3::config::Builder::from(&aws_cfg)
            .force_path_style(true)
            .build();
        self.s3_client = Some(aws_sdk_s3::Client::from_conf(s3_cfg));
        info!("MinIO sink initialized (endpoint={}, bucket={})", cfg.endpoint, cfg.bucket);
    }

    pub fn enqueue(&self, stats: WindowStats) {
        let mut buf = self.buffer.write();
        buf.pending.push(stats);
    }

    pub fn enqueue_batch(&self, stats: &[WindowStats]) {
        let mut buf = self.buffer.write();
        buf.pending.extend_from_slice(stats);
    }

    pub async fn flush_if_needed(&self, now: DateTime<Utc>) {
        let should_flush = {
            let buf = self.buffer.read();
            !buf.pending.is_empty()
                && (now - buf.last_flush)
                    >= chrono::Duration::seconds(buf.flush_interval_secs as i64)
        };
        if should_flush {
            self.flush(now).await;
        }
    }

    pub async fn flush(&self, now: DateTime<Utc>) {
        if self.cfg.is_none() || self.s3_client.is_none() {
            return;
        }
        let cfg = self.cfg.clone().unwrap();
        let pending: Vec<WindowStats> = {
            let mut buf = self.buffer.write();
            if buf.pending.is_empty() {
                return;
            }
            std::mem::take(&mut buf.pending)
        };

        let total_count = pending.iter().map(|s| s.count).sum::<u64>();
        let n_rows = pending.len();
        debug!("Flushing {} stats rows to MinIO (total count={})", n_rows, total_count);

        let batch = match stats_to_batch(&pending, self.schema.clone()) {
            Some(b) => b,
            None => {
                warn!("Failed to build record batch for {} rows", n_rows);
                let mut buf = self.buffer.write();
                buf.last_flush = now;
                return;
            }
        };

        let props = WriterProperties::builder()
            .set_compression(Compression::ZSTD(Default::default()))
            .set_encoding(Encoding::PLAIN)
            .set_max_row_group_size(100_000)
            .build();

        let tmp_path = format!(
            "/tmp/logforge-stats-{}-{}.parquet",
            now.timestamp(),
            uuid::Uuid::new_v4().simple()
        );
        let file = match tokio::fs::File::create(&tmp_path).await {
            Ok(f) => f,
            Err(e) => {
                error!("Failed to create temp parquet file: {}", e);
                let mut buf = self.buffer.write();
                buf.pending.extend(pending);
                return;
            }
        };

        let mut writer = AsyncArrowWriter::try_new(file, self.schema.clone(), Some(props)).unwrap();
        if let Err(e) = writer.write(&batch).await {
            error!("Failed to write parquet batch: {}", e);
            let _ = tokio::fs::remove_file(&tmp_path).await;
            let mut buf = self.buffer.write();
            buf.pending.extend(pending);
            return;
        }
        if let Err(e) = writer.close().await {
            error!("Failed to close parquet writer: {}", e);
            let _ = tokio::fs::remove_file(&tmp_path).await;
            let mut buf = self.buffer.write();
            buf.pending.extend(pending);
            return;
        }

        let parquet_bytes = match tokio::fs::read(&tmp_path).await {
            Ok(b) => b,
            Err(e) => {
                error!("Failed to read parquet file: {}", e);
                let _ = tokio::fs::remove_file(&tmp_path).await;
                let mut buf = self.buffer.write();
                buf.pending.extend(pending);
                return;
            }
        };
        let _ = tokio::fs::remove_file(&tmp_path).await;

        let key = format!(
            "{}dt={}/part-{}-{}.parquet",
            cfg.key_prefix,
            now.format("%Y-%m-%d/%H"),
            now.timestamp(),
            uuid::Uuid::new_v4().simple()
        );

        let client = self.s3_client.clone().unwrap();
        match client
            .put_object()
            .bucket(&cfg.bucket)
            .key(&key)
            .body(parquet_bytes.into())
            .content_type("application/vnd.apache.parquet")
            .send()
            .await
        {
            Ok(_) => {
                info!(
                    "Parquet uploaded to s3://{}/{} ({} rows, {} bytes)",
                    cfg.bucket,
                    key,
                    n_rows,
                    pending.len()
                );
                let mut buf = self.buffer.write();
                buf.last_flush = now;
            }
            Err(e) => {
                error!("Failed to upload parquet to MinIO: {}", e);
                let mut buf = self.buffer.write();
                buf.pending.extend(pending);
            }
        }
    }

    pub fn pending_count(&self) -> usize {
        self.buffer.read().pending.len()
    }
}
