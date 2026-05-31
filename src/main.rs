use enterprise_platform::api_gateway::ApiGateway;
use enterprise_platform::core_processing::TaskScheduler;
use enterprise_platform::monitoring::MonitoringService;
use enterprise_platform::audit_log::AuditLogChain;
use enterprise_platform::data_access::MigrationManager;
use enterprise_platform::data_masking::DataMaskingService;
use enterprise_platform::utils::init_tracing;
use std::sync::Arc;
use tokio::signal;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    init_tracing();

    tracing::info!("Enterprise Platform starting...");

    let audit_log = Arc::new(AuditLogChain::new());
    let monitoring = Arc::new(MonitoringService::new(audit_log.clone()));
    let migration_manager = Arc::new(MigrationManager::new());
    let data_masking = Arc::new(DataMaskingService::new());
    
    migration_manager.initialize().await?;
    monitoring.start().await?;
    
    let scheduler = Arc::new(TaskScheduler::new(monitoring.clone(), audit_log.clone()));
    scheduler.start().await?;
    
    let gateway = ApiGateway::new(scheduler.clone(), monitoring.clone());
    
    tracing::info!("Platform started successfully");
    
    let shutdown_signal = async {
        signal::ctrl_c().await.expect("Failed to listen for Ctrl+C");
        tracing::info!("Received shutdown signal");
    };

    tokio::select! {
        _ = gateway.start("0.0.0.0:8080") => {
            tracing::info!("Gateway shutdown");
        }
        _ = shutdown_signal => {
            tracing::info!("Initiating graceful shutdown");
        }
    }

    Ok(())
}
