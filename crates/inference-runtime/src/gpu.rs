use common::error::AppError;
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuStats {
    pub id: usize,
    pub uuid: String,
    pub name: String,
    pub total_mb: u64,
    pub used_mb: u64,
    pub available_mb: u64,
    pub utilization: u8,
    pub temperature: u8,
}

#[derive(Debug)]
pub struct GpuDevice {
    pub id: usize,
    pub uuid: String,
    pub name: String,
    pub total_mb: u64,
    pub used_mb: AtomicU64,
    pub utilization: AtomicU8,
    pub temperature: AtomicU8,
}

impl GpuDevice {
    pub fn new(id: usize, name: String, total_mb: u64) -> Self {
        Self {
            id,
            uuid: Uuid::new_v4().to_string(),
            name,
            total_mb,
            used_mb: AtomicU64::new(0),
            utilization: AtomicU8::new(0),
            temperature: AtomicU8::new(30),
        }
    }

    pub fn available_mb(&self) -> u64 {
        let used = self.used_mb.load(Ordering::Acquire);
        self.total_mb.saturating_sub(used)
    }

    pub fn utilization(&self) -> u8 {
        self.utilization.load(Ordering::Acquire)
    }

    pub fn temperature(&self) -> u8 {
        self.temperature.load(Ordering::Acquire)
    }

    pub fn stats(&self) -> GpuStats {
        GpuStats {
            id: self.id,
            uuid: self.uuid.clone(),
            name: self.name.clone(),
            total_mb: self.total_mb,
            used_mb: self.used_mb.load(Ordering::Acquire),
            available_mb: self.available_mb(),
            utilization: self.utilization(),
            temperature: self.temperature(),
        }
    }
}

pub struct GpuManager {
    devices: Vec<Arc<GpuDevice>>,
    monitor_handle: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl GpuManager {
    pub fn new() -> Self {
        Self::with_devices(vec![])
    }

    pub fn with_devices(devices: Vec<GpuDevice>) -> Self {
        let devices = devices.into_iter().map(Arc::new).collect();
        Self {
            devices,
            monitor_handle: Mutex::new(None),
        }
    }

    pub fn mock_with_count(num_gpus: usize) -> Self {
        let mut devices = Vec::with_capacity(num_gpus);
        for i in 0..num_gpus {
            devices.push(GpuDevice::new(
                i,
                format!("NVIDIA GeForce RTX 4090"),
                24576,
            ));
        }
        Self::with_devices(devices)
    }

    pub fn devices(&self) -> &[Arc<GpuDevice>] {
        &self.devices
    }

    pub fn get_device(&self, gpu_id: usize) -> Result<Arc<GpuDevice>, AppError> {
        self.devices
            .get(gpu_id)
            .cloned()
            .ok_or_else(|| AppError::GpuNotFound(gpu_id.to_string()))
    }

    pub fn allocate_memory(&self, gpu_id: usize, required_mb: u64) -> Result<(), AppError> {
        let device = self.get_device(gpu_id)?;
        loop {
            let current_used = device.used_mb.load(Ordering::Acquire);
            if current_used + required_mb > device.total_mb {
                return Err(AppError::InsufficientGpuMemory(
                    required_mb,
                    device.available_mb(),
                ));
            }
            let new_used = current_used + required_mb;
            match device.used_mb.compare_exchange(
                current_used,
                new_used,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => {
                    info!(
                        "GPU {}: allocated {}MB ({}MB / {}MB used)",
                        gpu_id, required_mb, new_used, device.total_mb
                    );
                    return Ok(());
                }
                Err(_) => continue,
            }
        }
    }

    pub fn release_memory(&self, gpu_id: usize, released_mb: u64) -> Result<(), AppError> {
        let device = self.get_device(gpu_id)?;
        let current_used = device.used_mb.load(Ordering::Acquire);
        let new_used = current_used.saturating_sub(released_mb);
        device.used_mb.store(new_used, Ordering::Release);
        info!(
            "GPU {}: released {}MB ({}MB / {}MB used)",
            gpu_id, released_mb, new_used, device.total_mb
        );
        Ok(())
    }

    pub fn free_memory(&self, gpu_id: usize, mb: u64) -> Result<(), AppError> {
        self.release_memory(gpu_id, mb)
    }

    pub fn select_gpu(&self, required_mb: u64) -> Option<Arc<GpuDevice>> {
        let mut best: Option<(Arc<GpuDevice>, u8)> = None;

        for device in &self.devices {
            if device.available_mb() >= required_mb {
                let util = device.utilization();
                match &best {
                    None => best = Some((device.clone(), util)),
                    Some((_, best_util)) if util < *best_util => {
                        best = Some((device.clone(), util));
                    }
                    _ => {}
                }
            }
        }

        best.map(|(d, _)| d)
    }

    pub fn find_available_gpu(&self, required_mb: u64) -> Option<usize> {
        self.select_gpu(required_mb).map(|d| d.id)
    }

    pub fn get_gpu_stats(&self) -> Vec<GpuStats> {
        self.devices.iter().map(|d| d.stats()).collect()
    }

    pub async fn start_monitor(&self, interval_secs: u64) {
        let mut handle = self.monitor_handle.lock().await;
        if handle.is_some() {
            warn!("GPU monitor already running");
            return;
        }

        let devices_clone: Vec<Arc<GpuDevice>> = self.devices.iter().cloned().collect();
        let join_handle = tokio::spawn(async move {
            info!("GPU monitor started with interval {}s", interval_secs);
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(interval_secs));
            loop {
                interval.tick().await;
                Self::update_metrics(&devices_clone);
            }
        });

        *handle = Some(join_handle);
    }

    fn update_metrics(devices: &[Arc<GpuDevice>]) {
        for device in devices {
            let base_util = 10u8;
            let variance = (rand::random::<u8>() % 30) as i16 - 15i16;
            let new_util = (base_util as i16 + variance).clamp(0, 100) as u8;
            device.utilization.store(new_util, Ordering::Release);

            let base_temp = 45u8;
            let temp_variance = (rand::random::<u8>() % 10) as i16 - 5i16;
            let new_temp = (base_temp as i16 + temp_variance).clamp(25, 90) as u8;
            device.temperature.store(new_temp, Ordering::Release);

            tracing::debug!(
                "GPU {}: util={}%, temp={}C, mem={}/{}MB",
                device.id,
                new_util,
                new_temp,
                device.used_mb.load(Ordering::Acquire),
                device.total_mb
            );
        }
    }

    pub async fn stop_monitor(&self) {
        let mut handle = self.monitor_handle.lock().await;
        if let Some(h) = handle.take() {
            h.abort();
            info!("GPU monitor stopped");
        }
    }
}

impl Default for GpuManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_gpu_allocation() {
        let manager = GpuManager::with_devices(vec![GpuDevice::new(0, "Test GPU".into(), 1024)]);

        manager.allocate_memory(0, 512).unwrap();
        assert_eq!(manager.get_device(0).unwrap().used_mb.load(Ordering::Acquire), 512);

        assert!(manager.allocate_memory(0, 600).is_err());

        manager.allocate_memory(0, 256).unwrap();
        assert_eq!(manager.get_device(0).unwrap().used_mb.load(Ordering::Acquire), 768);

        manager.release_memory(0, 512).unwrap();
        assert_eq!(manager.get_device(0).unwrap().used_mb.load(Ordering::Acquire), 256);
    }

    #[tokio::test]
    async fn test_select_gpu() {
        let manager = GpuManager::with_devices(vec![
            GpuDevice::new(0, "GPU 0".into(), 1024),
            GpuDevice::new(1, "GPU 1".into(), 1024),
        ]);

        manager.allocate_memory(0, 800).unwrap();
        manager.get_device(0).unwrap().utilization.store(80, Ordering::Release);
        manager.get_device(1).unwrap().utilization.store(20, Ordering::Release);

        let selected = manager.select_gpu(300);
        assert!(selected.is_some());
        assert_eq!(selected.unwrap().id, 1);
        assert!(manager.select_gpu(900).is_none());
    }

    #[tokio::test]
    async fn test_get_gpu_stats() {
        let manager = GpuManager::with_devices(vec![
            GpuDevice::new(0, "GPU 0".into(), 1024),
            GpuDevice::new(1, "GPU 1".into(), 2048),
        ]);

        manager.allocate_memory(0, 512).unwrap();
        let stats = manager.get_gpu_stats();
        assert_eq!(stats.len(), 2);
        assert_eq!(stats[0].id, 0);
        assert_eq!(stats[0].used_mb, 512);
        assert_eq!(stats[0].available_mb, 512);
        assert_eq!(stats[1].total_mb, 2048);
    }
}
