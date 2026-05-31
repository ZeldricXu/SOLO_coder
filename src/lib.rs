pub mod logging;
pub mod traffic_control;
pub mod mtls;
pub mod dns_proxy;
pub mod notification;
pub mod fault_injection;
pub mod storage;
pub mod audit;
pub mod core;

pub use logging::*;
pub use traffic_control::*;
pub use mtls::*;
pub use dns_proxy::*;
pub use notification::*;
pub use fault_injection::*;
pub use storage::*;
pub use audit::*;
pub use core::*;

#[derive(Debug)]
pub struct Platform {
    pub logging: logging::Logger,
    pub traffic_controller: traffic_control::TrafficController,
    pub mtls_manager: mtls::MtlsManager,
    pub dns_proxy: dns_proxy::DnsProxy,
    pub notification_manager: notification::NotificationManager,
    pub fault_injector: fault_injection::FaultInjector,
    pub storage_manager: storage::StorageManager,
    pub audit_manager: audit::AuditManager,
    pub core_processor: core::CoreProcessor,
}

impl Platform {
    pub fn new() -> Self {
        Self {
            logging: logging::Logger::new(logging::LogDimensions::new()),
            traffic_controller: traffic_control::TrafficController::new(),
            mtls_manager: mtls::MtlsManager::new(),
            dns_proxy: dns_proxy::DnsProxy::new(),
            notification_manager: notification::NotificationManager::new(),
            fault_injector: fault_injection::FaultInjector::new(),
            storage_manager: storage::StorageManager::new(),
            audit_manager: audit::AuditManager::new(),
            core_processor: core::CoreProcessor::new(),
        }
    }

    pub fn init_logging(&self) {
        logging::init_logger();
    }
}

impl Default for Platform {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_platform_creation() {
        let platform = Platform::new();
        let policies = platform.traffic_controller.list_policies();
        assert_eq!(policies.len(), 0);
    }
}
