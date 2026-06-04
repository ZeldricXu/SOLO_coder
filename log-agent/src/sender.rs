use anyhow::{Context, Result};
use std::collections::HashMap;
use std::time::Duration;
use tokio::sync::mpsc::{Receiver, Sender};
use tokio::time::interval;
use tracing::{debug, error, info, warn};

use common::log::{DroppedEventsMetadata, FileGap, LogBatch, LogEvent};

pub const MAX_BUFFER_EVENTS: usize = 10000;
pub const MAX_BUFFER_BYTES: usize = 50 * 1024 * 1024;

pub struct BatchSender {
    receiver: Receiver<LogEvent>,
    downstream_url: String,
    batch_size: usize,
    flush_interval_ms: u64,
    client: reqwest::Client,
    buffer: Vec<LogEvent>,
    buffer_size_bytes: usize,
    dropped_events: u64,
    dropped_bytes: u64,
    dropped_gaps: Vec<FileGap>,
    pending_gaps: Vec<FileGap>,
    current_file_offsets: HashMap<String, (u64, chrono::DateTime<chrono::Utc>)>,
}

impl BatchSender {
    pub fn new(
        receiver: Receiver<LogEvent>,
        downstream_url: String,
        batch_size: usize,
        flush_interval_ms: u64,
    ) -> Self {
        metrics::describe_counter!(
            "log_agent_events_dropped_total",
            "Total number of log events dropped due to buffer overflow"
        );
        metrics::describe_gauge!(
            "log_agent_buffer_size",
            "Current number of events in the batch buffer"
        );
        metrics::describe_gauge!(
            "log_agent_buffer_bytes",
            "Current size of batch buffer in bytes"
        );

        Self {
            receiver,
            downstream_url,
            batch_size,
            flush_interval_ms,
            client: reqwest::Client::builder()
                .timeout(Duration::from_secs(30))
                .build()
                .unwrap(),
            buffer: Vec::with_capacity(batch_size),
            buffer_size_bytes: 0,
            dropped_events: 0,
            dropped_bytes: 0,
            dropped_gaps: Vec::new(),
            pending_gaps: Vec::new(),
            current_file_offsets: HashMap::new(),
        }
    }

    pub async fn run(&mut self) {
        info!("Starting batch sender to {}", self.downstream_url);
        info!(
            "Buffer hard limits: {} events, {} bytes",
            MAX_BUFFER_EVENTS, MAX_BUFFER_BYTES
        );

        let mut flush_timer = interval(Duration::from_millis(self.flush_interval_ms));

        loop {
            tokio::select! {
                event = self.receiver.recv() => {
                    match event {
                        Some(event) => {
                            self.enqueue_event(event).await;
                            if self.buffer.len() >= self.batch_size {
                                if let Err(e) = self.flush_buffer().await {
                                    error!("Failed to send batch: {}", e);
                                }
                            }
                        }
                        None => {
                            info!("Event channel closed, flushing remaining events");
                            if !self.buffer.is_empty() {
                                let _ = self.flush_buffer().await;
                            }
                            if !self.dropped_gaps.is_empty() {
                                let _ = self.send_gap_metadata().await;
                            }
                            break;
                        }
                    }
                }
                _ = flush_timer.tick() => {
                    if !self.buffer.is_empty() || !self.dropped_gaps.is_empty() {
                        debug!(
                            "Flushing {} events on timer, dropped_count={}",
                            self.buffer.len(),
                            self.dropped_events
                        );
                        if let Err(e) = self.flush_buffer().await {
                            error!("Failed to send batch on timer: {}", e);
                        }
                    }
                    self.update_metrics();
                }
            }
        }
    }

    async fn enqueue_event(&mut self, event: LogEvent) {
        let event_size = estimate_event_size(&event);

        while self.buffer.len() >= MAX_BUFFER_EVENTS
            || self.buffer_size_bytes + event_size >= MAX_BUFFER_BYTES
        {
            let dropped_event = self.buffer.first().cloned();
            if let Some(dropped) = dropped_event {
                self.record_dropped(&dropped);
                self.buffer.remove(0);
                self.buffer_size_bytes = self.buffer.iter().map(estimate_event_size).sum();
            } else {
                break;
            }
        }

        self.track_file_offset(&event);
        self.buffer_size_bytes += event_size;
        self.buffer.push(event);
    }

    fn track_file_offset(&mut self, event: &LogEvent) {
        let key = event.source_file.clone();
        let entry = self.current_file_offsets
            .entry(key)
            .or_insert((event.source_offset, event.timestamp));

        if event.source_offset < entry.0 {
            let gap = FileGap {
                source_file: event.source_file.clone(),
                start_offset: entry.0,
                end_offset: event.source_offset,
                timestamp_start: entry.1,
                timestamp_end: event.timestamp,
            };
            self.dropped_gaps.push(gap);
        }

        *entry = (event.source_offset, event.timestamp);
    }

    fn record_dropped(&mut self, event: &LogEvent) {
        self.dropped_events += 1;
        let event_size = estimate_event_size(event);
        self.dropped_bytes += event_size as u64;

        metrics::counter!("log_agent_events_dropped_total").increment(1);

        if self.dropped_events % 1000 == 0 {
            warn!(
                "Dropped {} events so far, {} bytes. Buffer full.",
                self.dropped_events, self.dropped_bytes
            );
        }
    }

    async fn flush_buffer(&mut self) -> Result<()> {
        let events: Vec<LogEvent> = self.buffer.drain(..).collect();
        self.buffer_size_bytes = 0;

        if events.is_empty() && self.dropped_gaps.is_empty() {
            return Ok(());
        }

        let batch = if self.dropped_events > 0 || !self.dropped_gaps.is_empty() {
            let metadata = DroppedEventsMetadata {
                count: self.dropped_events,
                total_size_bytes: self.dropped_bytes,
                gaps: self.dropped_gaps.clone(),
            };

            self.pending_gaps.extend(self.dropped_gaps.drain(..));
            let batch = LogBatch::with_dropped(events, metadata);
            self.dropped_events = 0;
            self.dropped_bytes = 0;
            batch
        } else {
            LogBatch::new(events)
        };

        match self.send_batch(&batch).await {
            Ok(_) => {
                self.pending_gaps.clear();
                Ok(())
            }
            Err(e) => {
                self.buffer = batch.events;
                self.buffer_size_bytes = self.buffer.iter().map(estimate_event_size).sum();
                Err(e)
            }
        }
    }

    async fn send_gap_metadata(&mut self) -> Result<()> {
        if self.dropped_gaps.is_empty() && self.pending_gaps.is_empty() {
            return Ok(());
        }

        let all_gaps: Vec<FileGap> = self.pending_gaps
            .iter()
            .chain(self.dropped_gaps.iter())
            .cloned()
            .collect();

        let metadata = DroppedEventsMetadata {
            count: self.dropped_events,
            total_size_bytes: self.dropped_bytes,
            gaps: all_gaps,
        };

        let dropped_count = metadata.count;
        let dropped_bytes = metadata.total_size_bytes;
        let gaps_count = metadata.gaps.len();

        let batch = LogBatch::with_dropped(Vec::new(), metadata);

        match self.send_batch(&batch).await {
            Ok(_) => {
                info!(
                    "Successfully sent gap metadata: {} events, {} bytes, {} gaps",
                    dropped_count, dropped_bytes,
                    gaps_count
                );
                self.dropped_events = 0;
                self.dropped_bytes = 0;
                self.dropped_gaps.clear();
                self.pending_gaps.clear();
                Ok(())
            }
            Err(e) => {
                warn!("Failed to send gap metadata, will retry: {}", e);
                Err(e)
            }
        }
    }

    async fn send_batch(&self, batch: &LogBatch) -> Result<()> {
        if batch.is_empty() && batch.dropped_events.is_none() {
            return Ok(());
        }

        debug!(
            "Sending batch of {} events, has_dropped={}, dropped_count={}",
            batch.len(),
            batch.dropped_events.is_some(),
            batch.dropped_events.as_ref().map(|d| d.count).unwrap_or(0)
        );

        let response = self.client
            .post(&self.downstream_url)
            .json(batch)
            .send()
            .await
            .context("Failed to send HTTP request")?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            warn!("Downstream returned {}: {}", status, body);
            return Err(anyhow::anyhow!("Downstream error: {}", status));
        }

        metrics::counter!("log_agent_batches_sent_total").increment(1);
        Ok(())
    }

    fn update_metrics(&self) {
        metrics::gauge!("log_agent_buffer_size").set(self.buffer.len() as f64);
        metrics::gauge!("log_agent_buffer_bytes").set(self.buffer_size_bytes as f64);
    }
}

fn estimate_event_size(event: &LogEvent) -> usize {
    let mut size = std::mem::size_of::<LogEvent>();
    size += event.hostname.capacity();
    size += event.service.capacity();
    size += event.message.capacity();
    size += event.source_file.capacity();
    if let Some(raw) = &event.raw {
        size += raw.capacity();
    }
    for (k, v) in &event.fields {
        size += k.capacity();
        size += v.to_string().capacity();
    }
    size
}

pub fn create_channel(buffer_size: usize) -> (Sender<LogEvent>, Receiver<LogEvent>) {
    tokio::sync::mpsc::channel(buffer_size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogLevel, LogEvent};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use warp::Filter;

    #[tokio::test]
    async fn test_batch_buffering() {
        let (tx, rx) = create_channel(100);
        let mut sender = BatchSender::new(rx, "http://localhost:19999/test".to_string(), 5, 1000);

        for i in 0..5 {
            let event = LogEvent::new(
                "host".to_string(),
                "service".to_string(),
                LogLevel::Info,
                format!("message {}", i),
                "test.log".to_string(),
            );
            tx.send(event).await.unwrap();
        }
        drop(tx);
    }

    #[tokio::test]
    async fn test_buffer_hard_limit_events() {
        let received_count = Arc::new(AtomicUsize::new(0));
        let rc = received_count.clone();

        let filter = warp::post()
            .and(warp::path("test"))
            .and(warp::body::json())
            .map(move |batch: LogBatch| {
                rc.fetch_add(batch.events.len(), Ordering::SeqCst);
                warp::reply::json(&serde_json::json!({ "status": "ok" }))
            });

        let server = warp::serve(filter).bind(([127, 0, 0, 1], 19998));
        let handle = tokio::spawn(server);

        tokio::time::sleep(Duration::from_millis(100)).await;

        let (tx, rx) = create_channel(20000);
        let mut sender = BatchSender::new(rx, "http://localhost:19998/test".to_string(), 1000, 5000);

        for i in 0..20000 {
            let mut event = LogEvent::new(
                "host".to_string(),
                "service".to_string(),
                LogLevel::Info,
                format!("message {}", i),
                "test.log".to_string(),
            );
            event.source_offset = i as u64 * 100;
            tx.send(event).await.unwrap();
        }
        drop(tx);

        let sender_handle = tokio::spawn(async move {
            sender.run().await;
        });

        tokio::time::timeout(Duration::from_secs(10), sender_handle)
            .await
            .unwrap()
            .unwrap();

        let total_received = received_count.load(Ordering::SeqCst);
        assert!(total_received > 0, "Should have received some events");
        assert!(total_received <= MAX_BUFFER_EVENTS, "Should not exceed buffer limit");

        handle.abort();
    }

    #[tokio::test]
    async fn test_dropped_events_metadata() {
        let received_batches = Arc::new(std::sync::Mutex::new(Vec::new()));
        let rb = received_batches.clone();

        let filter = warp::post()
            .and(warp::path("test"))
            .and(warp::body::json())
            .map(move |batch: LogBatch| {
                rb.lock().unwrap().push(batch);
                warp::reply::json(&serde_json::json!({ "status": "ok" }))
            });

        let server = warp::serve(filter).bind(([127, 0, 0, 1], 19997));
        let handle = tokio::spawn(server);

        tokio::time::sleep(Duration::from_millis(100)).await;

        let (tx, rx) = create_channel(1000);
        let mut sender = BatchSender::new(rx, "http://localhost:19997/test".to_string(), 100, 5000);

        let sender_handle = tokio::spawn(async move {
            sender.run().await;
        });

        for i in 0..500 {
            let mut event = LogEvent::new(
                "host".to_string(),
                "service".to_string(),
                LogLevel::Info,
                format!("message {}", i),
                "test.log".to_string(),
            );
            event.source_offset = i as u64 * 100;
            tx.send(event).await.unwrap();
        }
        drop(tx);

        tokio::time::timeout(Duration::from_secs(10), sender_handle)
            .await
            .unwrap()
            .unwrap();

        let batches = received_batches.lock().unwrap();
        let has_dropped = batches.iter().any(|b| b.dropped_events.is_some());
        assert!(has_dropped, "Should have dropped events metadata");

        let dropped: u64 = batches
            .iter()
            .filter_map(|b| b.dropped_events.as_ref().map(|d| d.count))
            .sum();
        assert!(dropped > 0, "Should have dropped some events");

        handle.abort();
    }
}
