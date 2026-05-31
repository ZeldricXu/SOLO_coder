use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum GpuVendor {
    Nvidia,
    Amd,
    Intel,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GpuStatus {
    Available,
    Allocated,
    Reserved,
    Maintenance,
    Unhealthy,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuResourceSpec {
    pub gpu_memory_gb: f64,
    pub gpu_cores: u32,
    pub compute_units: u32,
    pub memory_bandwidth_gbps: f64,
    pub tensor_cores: Option<u32>,
    pub rt_cores: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuDevice {
    pub device_id: String,
    pub node_id: String,
    pub vendor: GpuVendor,
    pub model: String,
    pub uuid: String,
    pub index: u32,
    pub spec: GpuResourceSpec,
    pub status: GpuStatus,
    pub labels: HashMap<String, String>,
    pub registered_at: DateTime<Utc>,
    pub last_heartbeat: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuResource {
    pub total_memory_gb: f64,
    pub available_memory_gb: f64,
    pub reserved_memory_gb: f64,
    pub utilization_percent: f64,
    pub memory_utilization_percent: f64,
    pub temperature_celsius: f64,
    pub power_watts: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuAllocation {
    pub allocation_id: String,
    pub task_id: String,
    pub device_id: String,
    pub memory_gb: f64,
    pub cores: u32,
    pub start_time: DateTime<Utc>,
    pub end_time: Option<DateTime<Utc>>,
    pub is_preemptible: bool,
    pub preemption_priority: u8,
}

impl GpuResourceSpec {
    pub fn validate(&self) -> Result<(), AppError> {
        if self.gpu_memory_gb <= 0.0 {
            return Err(AppError::Validation("GPU memory must be greater than 0".to_string()));
        }
        if self.gpu_cores == 0 {
            return Err(AppError::Validation("GPU cores must be greater than 0".to_string()));
        }
        Ok(())
    }

    pub fn can_allocate(&self, requested: &GpuResourceSpec) -> bool {
        self.gpu_memory_gb >= requested.gpu_memory_gb
            && self.gpu_cores >= requested.gpu_cores
            && self.compute_units >= requested.compute_units
    }

    pub fn get_resource_score(&self, requested: &GpuResourceSpec) -> f64 {
        let memory_score = requested.gpu_memory_gb / self.gpu_memory_gb;
        let cores_score = requested.gpu_cores as f64 / self.gpu_cores as f64;
        (memory_score + cores_score) / 2.0
    }
}

impl GpuDevice {
    pub fn new(
        node_id: String,
        vendor: GpuVendor,
        model: String,
        uuid: String,
        index: u32,
        spec: GpuResourceSpec,
        labels: HashMap<String, String>,
    ) -> Result<Self, AppError> {
        spec.validate()?;
        
        let now = Utc::now();
        Ok(Self {
            device_id: generate_id("gpu"),
            node_id,
            vendor,
            model,
            uuid,
            index,
            spec,
            status: GpuStatus::Available,
            labels,
            registered_at: now,
            last_heartbeat: now,
        })
    }

    pub fn update_heartbeat(&mut self) {
        self.last_heartbeat = Utc::now();
    }

    pub fn is_healthy(&self, heartbeat_timeout_secs: i64) -> bool {
        if self.status == GpuStatus::Unhealthy {
            return false;
        }
        let now = Utc::now();
        let elapsed = (now - self.last_heartbeat).num_seconds();
        elapsed < heartbeat_timeout_secs
    }

    pub fn set_status(&mut self, status: GpuStatus) {
        self.status = status;
    }
}

impl GpuAllocation {
    pub fn new(
        task_id: String,
        device_id: String,
        memory_gb: f64,
        cores: u32,
        is_preemptible: bool,
        preemption_priority: u8,
    ) -> Self {
        Self {
            allocation_id: generate_id("alloc"),
            task_id,
            device_id,
            memory_gb,
            cores,
            start_time: Utc::now(),
            end_time: None,
            is_preemptible,
            preemption_priority,
        }
    }

    pub fn complete(&mut self) {
        self.end_time = Some(Utc::now());
    }

    pub fn duration_seconds(&self) -> i64 {
        let end = self.end_time.unwrap_or_else(Utc::now);
        (end - self.start_time).num_seconds()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gpu_resource_spec_validation() {
        let spec = GpuResourceSpec {
            gpu_memory_gb: 16.0,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: Some(224),
            rt_cores: Some(56),
        };
        
        assert!(spec.validate().is_ok());
    }

    #[test]
    fn test_gpu_resource_spec_validation_invalid() {
        let spec = GpuResourceSpec {
            gpu_memory_gb: 0.0,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: None,
            rt_cores: None,
        };
        
        assert!(spec.validate().is_err());
    }

    #[test]
    fn test_gpu_device_creation() {
        let spec = GpuResourceSpec {
            gpu_memory_gb: 16.0,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: Some(224),
            rt_cores: Some(56),
        };

        let device = GpuDevice::new(
            "node-1".to_string(),
            GpuVendor::Nvidia,
            "RTX 3080".to_string(),
            "GPU-abc123".to_string(),
            0,
            spec,
            HashMap::new(),
        ).unwrap();

        assert!(device.device_id.starts_with("gpu_"));
        assert_eq!(device.status, GpuStatus::Available);
    }

    #[test]
    fn test_gpu_allocation() {
        let allocation = GpuAllocation::new(
            "task_123".to_string(),
            "gpu_456".to_string(),
            8.0,
            1024,
            true,
            3,
        );

        assert!(allocation.allocation_id.starts_with("alloc_"));
        assert!(allocation.end_time.is_none());
        
        let duration = allocation.duration_seconds();
        assert!(duration >= 0);
    }

    #[test]
    fn test_gpu_device_health_check() {
        let spec = GpuResourceSpec {
            gpu_memory_gb: 16.0,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: None,
            rt_cores: None,
        };

        let mut device = GpuDevice::new(
            "node-1".to_string(),
            GpuVendor::Nvidia,
            "RTX 3080".to_string(),
            "GPU-abc123".to_string(),
            0,
            spec,
            HashMap::new(),
        ).unwrap();

        assert!(device.is_healthy(60));
        
        device.status = GpuStatus::Unhealthy;
        assert!(!device.is_healthy(60));
    }

    #[test]
    fn test_resource_can_allocate() {
        let spec = GpuResourceSpec {
            gpu_memory_gb: 16.0,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: None,
            rt_cores: None,
        };

        let requested = GpuResourceSpec {
            gpu_memory_gb: 8.0,
            gpu_cores: 1024,
            compute_units: 28,
            memory_bandwidth_gbps: 0.0,
            tensor_cores: None,
            rt_cores: None,
        };

        assert!(spec.can_allocate(&requested));

        let too_big = GpuResourceSpec {
            gpu_memory_gb: 32.0,
            gpu_cores: 1024,
            compute_units: 28,
            memory_bandwidth_gbps: 0.0,
            tensor_cores: None,
            rt_cores: None,
        };

        assert!(!spec.can_allocate(&too_big));
    }
}
