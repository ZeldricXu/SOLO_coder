use crate::error::{AppError, AppResult};
use redlock::{Lock, RedLock};
use std::sync::Arc;
use std::time::Duration;

pub struct DistributedLock {
    redlock: Arc<RedLock>,
    lock_timeout_ms: usize,
}

impl Clone for DistributedLock {
    fn clone(&self) -> Self {
        Self {
            redlock: Arc::clone(&self.redlock),
            lock_timeout_ms: self.lock_timeout_ms,
        }
    }
}

impl DistributedLock {
    pub fn new(redis_urls: Vec<String>) -> Self {
        let redlock = RedLock::new(redis_urls);
        Self {
            redlock: Arc::new(redlock),
            lock_timeout_ms: 30_000,
        }
    }

    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.lock_timeout_ms = timeout.as_millis() as usize;
        self
    }

    pub fn lock(&self, resource: &str) -> AppResult<Lock<'_>> {
        let resource_key = format!("lock:{}", resource);

        match self.redlock.lock(resource_key.as_bytes(), self.lock_timeout_ms) {
            Ok(Some(lock)) => Ok(lock),
            Ok(None) => Err(AppError::LockFailed(format!(
                "Failed to acquire lock for resource: {}",
                resource
            ))),
            Err(e) => Err(AppError::LockFailed(format!(
                "Redis error acquiring lock for {}: {}",
                resource, e
            ))),
        }
    }

    pub fn try_lock(&self, resource: &str) -> AppResult<Option<Lock<'_>>> {
        let resource_key = format!("lock:{}", resource);

        match self.redlock.lock(resource_key.as_bytes(), self.lock_timeout_ms) {
            Ok(Some(lock)) => Ok(Some(lock)),
            Ok(None) => Ok(None),
            Err(e) => Err(AppError::LockFailed(format!(
                "Redis error acquiring lock for {}: {}",
                resource, e
            ))),
        }
    }

    pub fn unlock(&self, lock: &Lock) {
        self.redlock.unlock(lock);
    }
}

pub struct LockGuard<'a> {
    distributed_lock: &'a DistributedLock,
    lock: Option<Lock<'a>>,
}

impl<'a> LockGuard<'a> {
    pub fn new(distributed_lock: &'a DistributedLock, lock: Lock<'a>) -> Self {
        Self {
            distributed_lock,
            lock: Some(lock),
        }
    }

    pub fn unlock(&mut self) {
        if let Some(lock) = self.lock.take() {
            self.distributed_lock.unlock(&lock);
        }
    }
}

impl Drop for LockGuard<'_> {
    fn drop(&mut self) {
        if let Some(lock) = self.lock.take() {
            self.distributed_lock.unlock(&lock);
        }
    }
}
