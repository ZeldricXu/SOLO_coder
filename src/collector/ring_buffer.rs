use crate::LogRecord;
use crossbeam::queue::ArrayQueue;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tracing::{debug, warn};

const BACKPRESSURE_SLEEP_MS: u64 = 5;
const BACKPRESSURE_WARN_INTERVAL_SECS: u64 = 30;

pub struct RingBuffer {
    queue: Arc<ArrayQueue<BufferedEntry>>,
    capacity: usize,
    buffer_seconds: u64,
}

#[derive(Debug, Clone)]
pub struct BufferedEntry {
    pub record: LogRecord,
    pub enqueued_at: Instant,
}

impl RingBuffer {
    pub fn new(capacity: usize, buffer_seconds: u64) -> Self {
        Self {
            queue: Arc::new(ArrayQueue::new(capacity)),
            capacity,
            buffer_seconds,
        }
    }

    pub fn push(&self, record: LogRecord) -> Result<(), LogRecord> {
        let entry = BufferedEntry {
            record,
            enqueued_at: Instant::now(),
        };
        self.queue.push(entry).map_err(|e| e.record)
    }

    pub fn push_blocking(&self, mut record: LogRecord) {
        let mut warned_at: Option<Instant> = None;
        loop {
            let entry = BufferedEntry {
                record,
                enqueued_at: Instant::now(),
            };
            match self.queue.push(entry) {
                Ok(()) => {
                    if warned_at.is_some() {
                        debug!("RingBuffer backpressure relieved");
                    }
                    return;
                }
                Err(e) => {
                    record = e.record;
                    let now = Instant::now();
                    if warned_at.is_none()
                        || now.duration_since(warned_at.unwrap())
                            > Duration::from_secs(BACKPRESSURE_WARN_INTERVAL_SECS)
                    {
                        warn!(
                            "RingBuffer is full (capacity={}), applying backpressure - slowing collector",
                            self.capacity
                        );
                        warned_at = Some(now);
                    }
                    std::thread::sleep(Duration::from_millis(BACKPRESSURE_SLEEP_MS));
                }
            }
        }
    }

    pub fn pop(&self) -> Option<BufferedEntry> {
        loop {
            let entry = self.queue.pop()?;
            let age = entry.enqueued_at.elapsed();
            if age > Duration::from_secs(self.buffer_seconds) {
                debug!("Dropping expired entry (age={:?})", age);
                continue;
            }
            return Some(entry);
        }
    }

    pub fn pop_batch(&self, max_batch: usize, timeout: Duration) -> Vec<BufferedEntry> {
        let deadline = Instant::now() + timeout;
        let mut batch = Vec::with_capacity(max_batch);
        while batch.len() < max_batch {
            match self.queue.pop() {
                Some(entry) => {
                    let age = entry.enqueued_at.elapsed();
                    if age <= Duration::from_secs(self.buffer_seconds) {
                        batch.push(entry);
                    }
                }
                None => {
                    if batch.is_empty() && Instant::now() < deadline {
                        std::thread::sleep(Duration::from_millis(1));
                        continue;
                    }
                    break;
                }
            }
        }
        batch
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn handle(&self) -> RingBufferHandle {
        RingBufferHandle {
            queue: self.queue.clone(),
        }
    }
}

#[derive(Clone)]
pub struct RingBufferHandle {
    queue: Arc<ArrayQueue<BufferedEntry>>,
}

impl RingBufferHandle {
    pub fn push(&self, record: LogRecord) -> Result<(), LogRecord> {
        let entry = BufferedEntry {
            record,
            enqueued_at: Instant::now(),
        };
        self.queue.push(entry).map_err(|e| e.record)
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_push_and_pop() {
        let rb = RingBuffer::new(10, 300);
        let r1 = LogRecord::new();
        let id1 = r1.id;
        rb.push(r1).unwrap();
        assert_eq!(rb.len(), 1);
        let popped = rb.pop().unwrap();
        assert_eq!(popped.record.id, id1);
        assert!(rb.is_empty());
    }

    #[test]
    fn test_overflow_returns_err() {
        let rb = RingBuffer::new(2, 300);
        rb.push(LogRecord::new()).unwrap();
        rb.push(LogRecord::new()).unwrap();
        let r = LogRecord::new();
        let id = r.id;
        let ret = rb.push(r);
        assert!(ret.is_err());
        assert_eq!(ret.unwrap_err().id, id);
    }
}
