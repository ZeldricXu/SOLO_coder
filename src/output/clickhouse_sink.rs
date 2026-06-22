use crate::WindowStats;
use crate::config::ClickHouseSinkConfig;
use chrono::{DateTime, Duration, Utc};
use parking_lot::Mutex;
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration as StdDuration;
use tracing::{debug, error, info, warn};

const CACHE_FILE_PREFIX: &str = "ch-cache-";

struct ClickHouseRow {
    window_start: DateTime<Utc>,
    window_end: DateTime<Utc>,
    service: String,
    level: String,
    count: u64,
    sum_spend: f64,
    avg_spend: f64,
    p50_spend: f64,
    p95_spend: f64,
    p99_spend: f64,
    min_spend: f64,
    max_spend: f64,
    event_date: chrono::NaiveDate,
}

impl From<&WindowStats> for ClickHouseRow {
    fn from(ws: &WindowStats) -> Self {
        let dt: DateTime<Utc> = ws.window_start;
        Self {
            window_start: ws.window_start,
            window_end: ws.window_end,
            service: ws.key.service.clone(),
            level: ws.key.level.to_string(),
            count: ws.count,
            sum_spend: ws.sum_spend,
            avg_spend: ws.avg_spend,
            p50_spend: ws.p50_spend,
            p95_spend: ws.p95_spend,
            p99_spend: ws.p99_spend,
            min_spend: ws.min_spend,
            max_spend: ws.max_spend,
            event_date: dt.naive_utc().date(),
        }
    }
}

struct LocalCache {
    cache_dir: PathBuf,
    pending_files: VecDeque<PathBuf>,
}

impl LocalCache {
    fn new(cache_dir: PathBuf) -> Self {
        std::fs::create_dir_all(&cache_dir).ok();
        let mut pending = VecDeque::new();
        if let Ok(entries) = std::fs::read_dir(&cache_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with(CACHE_FILE_PREFIX) && name.ends_with(".json") {
                        pending.push_back(path);
                    }
                }
            }
        }
        pending.sort();
        Self {
            cache_dir,
            pending_files: pending,
        }
    }

    fn save_batch(&mut self, batch: &[WindowStats]) -> Result<PathBuf, Box<dyn std::error::Error + Send + Sync>> {
        let ts = Utc::now().timestamp();
        let filename = format!("{}{}.json", CACHE_FILE_PREFIX, ts);
        let filepath = self.cache_dir.join(filename);
        let content = serde_json::to_string(batch)?;
        std::fs::write(&filepath, content)?;
        self.pending_files.push_back(filepath.clone());
        debug!("Saved {} records to local cache: {:?}", batch.len(), filepath);
        Ok(filepath)
    }

    fn next_cached_batch(&mut self) -> Option<(PathBuf, Vec<WindowStats>)> {
        while let Some(path) = self.pending_files.pop_front() {
            match std::fs::read_to_string(&path) {
                Ok(content) => {
                    match serde_json::from_str::<Vec<WindowStats>>(&content) {
                        Ok(records) => {
                            if !records.is_empty() {
                                return Some((path, records));
                            }
                        }
                        Err(e) => {
                            warn!("Failed to parse cached file {:?}: {}", path, e);
                        }
                    }
                }
                Err(e) => {
                    warn!("Failed to read cached file {:?}: {}", path, e);
                }
            }
            let _ = std::fs::remove_file(&path);
        }
        None
    }

    fn remove_file(&self, path: &Path) {
        if let Err(e) = std::fs::remove_file(path) {
            warn!("Failed to remove cache file {:?}: {}", path, e);
        }
    }

    fn pending_count(&self) -> usize {
        self.pending_files.len()
    }
}

pub struct ClickHouseSink {
    config: ClickHouseSinkConfig,
    client: Option<clickhouse_rs::Client>,
    pending: Arc<Mutex<VecDeque<WindowStats>>>,
    cache: Arc<Mutex<LocalCache>>,
    last_flush: Arc<Mutex<DateTime<Utc>>>,
    is_available: Arc<Mutex<bool>>,
}

impl ClickHouseSink {
    pub async fn new(config: ClickHouseSinkConfig) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let cache_dir = config
            .local_cache_dir
            .clone()
            .unwrap_or_else(|| "/tmp/logforge-ch-cache".to_string())
            .into();

        let client = match Self::create_client(&config).await {
            Ok(client) => {
                if let Err(e) = Self::ensure_table(&client, &config).await {
                    warn!("Failed to ensure ClickHouse table exists: {}", e);
                }
                Some(client)
            }
            Err(e) => {
                warn!("ClickHouse connection failed, will operate in cache-only mode: {}", e);
                None
            }
        };

        let available = client.is_some();

        Ok(Self {
            config,
            client,
            pending: Arc::new(Mutex::new(VecDeque::new())),
            cache: Arc::new(Mutex::new(LocalCache::new(cache_dir))),
            last_flush: Arc::new(Mutex::new(Utc::now())),
            is_available: Arc::new(Mutex::new(available)),
        })
    }

    async fn create_client(
        config: &ClickHouseSinkConfig,
    ) -> Result<clickhouse_rs::Client, Box<dyn std::error::Error + Send + Sync>> {
        let pool = clickhouse_rs::Pool::new(format!("{}", config.url));
        let mut options = pool
            .get_handle()
            .await?
            .with_database(&config.database)
            .with_user(&config.user)
            .with_password(&config.password);
        Ok(options)
    }

    async fn ensure_table(
        client: &clickhouse_rs::Client,
        config: &ClickHouseSinkConfig,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let ddl = format!(
            r#"
            CREATE TABLE IF NOT EXISTS {} (
                event_date Date,
                window_start DateTime64(6),
                window_end DateTime64(6),
                service LowCardinality(String),
                level LowCardinality(String),
                count UInt64,
                sum_spend Float64,
                avg_spend Float64,
                p50_spend Float64,
                p95_spend Float64,
                p99_spend Float64,
                min_spend Float64,
                max_spend Float64
            ) ENGINE = ReplicatedMergeTree()
            PARTITION BY toYYYYMM(event_date)
            ORDER BY (event_date, service, level, window_start)
            SETTINGS index_granularity = 8192
            "#,
            config.table
        );

        client.execute(ddl).await?;
        info!("ClickHouse table {} ensured", config.table);
        Ok(())
    }

    pub fn enqueue(&self, stats: WindowStats) {
        let mut pending = self.pending.lock();
        pending.push_back(stats);
        if pending.len() >= self.config.batch_size {
            let batch: Vec<WindowStats> = pending.drain(..).collect();
            drop(pending);
            let config = self.config.clone();
            let client_opt = self.client.clone();
            let cache = self.cache.clone();
            let is_available = self.is_available.clone();
            tokio::spawn(async move {
                let _ = Self::insert_batch_with_retry(
                    client_opt.as_ref(),
                    &config,
                    &batch,
                    cache.clone(),
                    is_available.clone(),
                )
                .await;
            });
            *self.last_flush.lock() = Utc::now();
        }
    }

    pub fn enqueue_batch(&self, stats: &[WindowStats]) {
        if stats.is_empty() {
            return;
        }
        let mut pending = self.pending.lock();
        pending.extend(stats.iter().cloned());
    }

    pub async fn flush_if_needed(&self, now: DateTime<Utc>) {
        let should_flush = {
            let last = *self.last_flush.lock();
            let pending_len = self.pending.lock().len();
            pending_len > 0
                && (now - last)
                    >= chrono::Duration::seconds(self.config.flush_interval_secs as i64)
                || pending_len >= self.config.batch_size
        };

        if should_flush {
            self.flush(now).await;
        }

        let has_cache = self.cache.lock().pending_count() > 0;
        let is_available = *self.is_available.lock();
        if has_cache && is_available {
            self.replay_cache().await;
        }
    }

    pub async fn flush(&self, _now: DateTime<Utc>) {
        let batch: Vec<WindowStats> = {
            let mut pending = self.pending.lock();
            pending.drain(..).collect()
        };

        if batch.is_empty() {
            return;
        }

        let config = self.config.clone();
        let client_opt = self.client.clone();
        let cache = self.cache.clone();
        let is_available = self.is_available.clone();

        tokio::spawn(async move {
            let _ = Self::insert_batch_with_retry(
                client_opt.as_ref(),
                &config,
                &batch,
                cache.clone(),
                is_available.clone(),
            )
            .await;
        });

        *self.last_flush.lock() = Utc::now();
    }

    async fn insert_batch_with_retry(
        client: Option<&clickhouse_rs::Client>,
        config: &ClickHouseSinkConfig,
        batch: &[WindowStats],
        cache: Arc<Mutex<LocalCache>>,
        is_available: Arc<Mutex<bool>>,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut last_error = None;
        for attempt in 0..=config.max_retries {
            if client.is_none() {
                break;
            }
            let client = client.unwrap();
            match Self::do_insert(client, config, batch).await {
                Ok(()) => {
                    if attempt > 0 {
                        info!("ClickHouse batch insert succeeded on attempt {} ({} records)", attempt + 1, batch.len());
                    } else {
                        debug!("ClickHouse batch insert succeeded ({} records)", batch.len());
                    }
                    *is_available.lock() = true;
                    return Ok(());
                }
                Err(e) => {
                    last_error = Some(e);
                    if attempt < config.max_retries {
                        let backoff = StdDuration::from_millis(
                            config.retry_backoff_ms * (attempt + 1) as u64,
                        );
                        warn!(
                            "ClickHouse insert failed (attempt {}/{}), retrying in {:?}: {}",
                            attempt + 1,
                            config.max_retries + 1,
                            backoff,
                            last_error.as_ref().unwrap()
                        );
                        tokio::time::sleep(backoff).await;
                    }
                }
            }
        }

        *is_available.lock() = false;
        let e = last_error.unwrap_or_else(|| "No client available".into());
        warn!(
            "ClickHouse insert failed after all retries, caching {} records locally: {}",
            batch.len(),
            e
        );

        let mut cache_guard = cache.lock();
        match cache_guard.save_batch(batch) {
            Ok(path) => {
                info!("Records cached to {:?}", path);
            }
            Err(ce) => {
                error!("CRITICAL: Failed to cache records, data may be lost!: {}", ce);
            }
        }
        drop(cache_guard);

        Err(e)
    }

    async fn do_insert(
        client: &clickhouse_rs::Client,
        config: &ClickHouseSinkConfig,
        batch: &[WindowStats],
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let rows: Vec<ClickHouseRow> = batch.iter().map(|ws| ws.into()).collect();

        let mut insert = client.insert(&config.table)?;

        for row in &rows {
            use clickhouse_rs::types::{DateTime64, Simple};
            let ws = chrono::DateTime::<chrono::Utc>::from(row.window_start);
            let we = chrono::DateTime::<chrono::Utc>::from(row.window_end);
            let ws_64 = DateTime64::<Simple, 6>::from(ws.naive_utc());
            let we_64 = DateTime64::<Simple, 6>::from(we.naive_utc());
            insert
                .write(&clickhouse_rs::row! {
                    event_date: row.event_date,
                    window_start: ws_64,
                    window_end: we_64,
                    service: row.service.as_str(),
                    level: row.level.as_str(),
                    count: row.count,
                    sum_spend: row.sum_spend,
                    avg_spend: row.avg_spend,
                    p50_spend: row.p50_spend,
                    p95_spend: row.p95_spend,
                    p99_spend: row.p99_spend,
                    min_spend: row.min_spend,
                    max_spend: row.max_spend,
                })
                .await?;
        }

        insert.commit().await?;
        Ok(())
    }

    async fn replay_cache(&self) {
        let client = match &self.client {
            Some(c) => c.clone(),
            None => return,
        };

        loop {
            let next = self.cache.lock().next_cached_batch();
            match next {
                Some((path, batch)) => {
                    info!(
                        "Replaying cached batch from {:?} ({} records)",
                        path,
                        batch.len()
                    );
                    match Self::do_insert(&client, &self.config, &batch).await {
                        Ok(()) => {
                            info!("Cache replay succeeded for {:?}", path);
                            self.cache.lock().remove_file(&path);
                        }
                        Err(e) => {
                            warn!(
                                "Cache replay failed for {:?}, will retry later: {}",
                                path, e
                            );
                            let mut cache_guard = self.cache.lock();
                            cache_guard.pending_files.push_front(path);
                            *self.is_available.lock() = false;
                            break;
                        }
                    }
                }
                None => {
                    info!("All cached records have been replayed");
                    break;
                }
            }
        }
    }

    pub fn pending_count(&self) -> usize {
        self.pending.lock().len()
    }

    pub fn cache_count(&self) -> usize {
        self.cache.lock().pending_count()
    }

    pub fn is_available(&self) -> bool {
        *self.is_available.lock()
    }
}
