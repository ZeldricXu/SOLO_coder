use task_tracker::prelude::*;

#[tokio::main]
async fn main() -> Result<()> {
    let config = ConfigManager::load()?;
    Logger::init(&config.logger)?;

    info!("任务执行状态追踪系统启动中...");

    let storage = StorageManager::new(&config.storage)?;
    let offline_cache = OfflineCache::new(&config.offline_cache)?;
    let device_shadow = DeviceShadow::new(&config.device_shadow)?;
    let aggregator = EdgeAggregator::new(&config.aggregator)?;
    let scheduler = Scheduler::new(&config.scheduler)?;
    let notifier = Notifier::new(&config.notifier)?;
    let core_processor = CoreProcessor::new(&config.core)?;

    let gateway = ApiGateway::builder()
        .config(&config.gateway)
        .device_shadow(device_shadow.clone())
        .scheduler(scheduler.clone())
        .aggregator(aggregator.clone())
        .offline_cache(offline_cache.clone())
        .notifier(notifier.clone())
        .core_processor(core_processor.clone())
        .storage(storage.clone())
        .build()?;

    tokio::spawn(async move {
        if let Err(e) = gateway.start().await {
            error!("API网关启动失败: {}", e);
        }
    });

    tokio::spawn(async move {
        if let Err(e) = offline_cache.start_sync_worker().await {
            error!("离线缓存同步失败: {}", e);
        }
    });

    tokio::spawn(async move {
        if let Err(e) = device_shadow.start_sync_worker().await {
            error!("设备影子同步失败: {}", e);
        }
    });

    tokio::spawn(async move {
        if let Err(e) = scheduler.start().await {
            error!("调度器启动失败: {}", e);
        }
    });

    info!("系统启动完成");

    tokio::signal::ctrl_c().await?;
    info!("接收到关闭信号，正在优雅关闭...");

    Ok(())
}
