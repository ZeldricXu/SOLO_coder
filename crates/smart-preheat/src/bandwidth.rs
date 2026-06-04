use std::sync::atomic::{AtomicU64, Ordering};

pub struct BandwidthThrottler {
    max_bandwidth_bps: u64,
    current_usage_bps: AtomicU64,
}

impl BandwidthThrottler {
    pub fn new(max_bandwidth_bps: u64) -> Self {
        BandwidthThrottler {
            max_bandwidth_bps,
            current_usage_bps: AtomicU64::new(0),
        }
    }

    pub fn acquire(&self, bytes_per_sec: u64) -> bool {
        let current = self.current_usage_bps.load(Ordering::SeqCst);
        if current + bytes_per_sec > self.max_bandwidth_bps {
            return false;
        }

        loop {
            let current = self.current_usage_bps.load(Ordering::SeqCst);
            if current + bytes_per_sec > self.max_bandwidth_bps {
                return false;
            }
            match self.current_usage_bps.compare_exchange(
                current,
                current + bytes_per_sec,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => return true,
                Err(_) => continue,
            }
        }
    }

    pub fn release(&self, bytes_per_sec: u64) {
        loop {
            let current = self.current_usage_bps.load(Ordering::SeqCst);
            let new_value = current.saturating_sub(bytes_per_sec);
            match self.current_usage_bps.compare_exchange(
                current,
                new_value,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => return,
                Err(_) => continue,
            }
        }
    }

    pub fn available_bandwidth(&self) -> u64 {
        let current = self.current_usage_bps.load(Ordering::SeqCst);
        self.max_bandwidth_bps.saturating_sub(current)
    }

    pub fn max_bandwidth(&self) -> u64 {
        self.max_bandwidth_bps
    }

    pub fn current_usage(&self) -> u64 {
        self.current_usage_bps.load(Ordering::SeqCst)
    }
}
