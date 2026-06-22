use logforge::aggregator::AggregationEngine;
use logforge::alerter::Alerter;
use logforge::collector::CollectorManager;
use logforge::config::{ConfigManager, default_config_toml, ParserConfig};
use logforge::detector::RuleEngine;
use logforge::observability::ObservabilityServer;
use logforge::output::OutputManager;
use logforge::output::kafka_sink::KafkaSink;
use logforge::output::minio_sink::MinIOSink;
use logforge::output::clickhouse_sink::ClickHouseSink;
use logforge::parser::ParserEngine;
use logforge::parser::custom_format::CustomFormatRegistry;
use logforge::{AlertEvent, LogRecord, WindowStats};
use chrono::Utc;
use signal_hook::consts::{SIGHUP, SIGINT, SIGTERM};
use signal_hook_tokio::Signals;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tracing::{debug, error, info, warn};
use tracing_subscriber::{EnvFilter, fmt};

const BATCH_SIZE: usize = 1024;
const AGG_TICK_INTERVAL_MS: u64 = 1000;
const KAFKA_FLUSH_MS: u64 = 1000;
const ALERT_FLUSH_MS: u64 = 500;

fn print_banner() {
    println!(
        r#"
   ██████╗  ██████╗  ██████╗ ███████╗ ██████╗ ██████╗  ██████╗ ███████╗
   ██╔══██╗██╔═══██╗██╔════╝ ██╔════╝██╔════╝ ██╔══██╗██╔═══██╗██╔════╝
   ██████╔╝██║   ██║██║  ███╗█████╗  ██║  ███╗██████╔╝██║   ██║███████╗
   ██╔══██╗██║   ██║██║   ██║██╔══╝  ██║   ██║██╔══██╗██║   ██║╚════██║
   ██████╔╝╚██████╔╝╚██████╔╝██║     ╚██████╔╝██║  ██║╚██████╔╝███████║
   ╚═════╝  ╚═════╝  ╚═════╝ ╚═╝      ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚══════╝

   :: High-Performance Log Aggregation Engine ::
   :: Preprocess + Aggregate + Detect + Alert at the Edge ::
"#
    );
}

fn init_logging() {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,logforge=debug"));
    fmt()
        .with_env_filter(filter)
        .with_target(true)
        .with_thread_ids(true)
        .with_file(false)
        .with_line_number(false)
        .json()
        .with_current_span(true)
        .with_ansi(false)
        .try_init()
        .ok();
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();

    if args.len() >= 2 && args[1] == "benchmark" {
        return run_benchmark(&args[1..]).await;
    }

    let config_path: PathBuf = if args.len() > 1 {
        let p = PathBuf::from(&args[1]);
        if p.exists() {
            p
        } else {
            eprintln!("Warning: config file {:?} not found, generating default config.toml", p);
            let default_toml = default_config_toml();
            std::fs::write(&p, default_toml)?;
            eprintln!("Default config written to {:?}", p);
            p
        }
    } else {
        let p = PathBuf::from("config.toml");
        if !p.exists() {
            let default_toml = default_config_toml();
            std::fs::write(&p, default_toml)?;
            eprintln!("Generated default config.toml in current directory");
        }
        p
    };

    print_banner();
    init_logging();
    info!("LogForge starting with config: {:?}", config_path.canonicalize().unwrap_or_else(|_| config_path.clone()));

    let config_manager = ConfigManager::new(&config_path)?;
    let config_handle = config_manager.handle();

    let cfg_snapshot = config_handle.read().await.clone();
    let collector = Arc::new(CollectorManager::new(&cfg_snapshot));

    let parser_engine = {
        let parser_cfg = ParserConfig {
            custom_formats: cfg_snapshot.parser.custom_formats.clone(),
            format_match_order: cfg_snapshot.parser.format_match_order.clone(),
        };
        let mut pe = ParserEngine::with_config(parser_cfg);
        let custom_count = pe.custom_format_count();
        if custom_count > 0 {
            info!("ParserEngine initialized with {} custom formats", custom_count);
        } else {
            info!("ParserEngine initialized with default auto-detection mode");
        }
        Arc::new(std::sync::Mutex::new(pe))
    };

    let aggregator = Arc::new(AggregationEngine::new(config_handle.clone()));
    aggregator.init_from_config().await;
    let detector = RuleEngine::new(config_handle.clone());
    detector.reload_rules().await?;
    let alerter = Alerter::new(config_handle.clone());
    alerter.reload_from_config().await;

    let output_manager = OutputManager::new(config_handle.clone()).await;

    collector.start_all(config_handle.clone()).await?;

    let mut signals = Signals::new([SIGHUP, SIGINT, SIGTERM])?;
    let signal_config_manager = config_manager.clone();
    let signal_detector = detector.clone();
    let signal_alerter = alerter.clone();
    let signal_aggregator = aggregator.clone();
    let signal_output = output_manager.clone();
    let signal_task = tokio::spawn(async move {
        while let Some(signal) = signals.next().await {
            match signal {
                SIGHUP => {
                    info!("SIGHUP received - reloading configuration atomically");
                    match signal_config_manager.reload().await {
                        Ok(_) => {
                            match signal_detector.reload_rules().await {
                                Ok(_) => info!("Rules reloaded successfully"),
                                Err(e) => error!("Failed to reload rules: {}", e),
                            }
                            signal_alerter.reload_from_config().await;
                            signal_aggregator.init_from_config().await;
                            info!("Configuration reload complete");
                        }
                        Err(e) => {
                            error!("Config reload failed: {}", e);
                        }
                    }
                }
                SIGINT | SIGTERM => {
                    info!("Termination signal received - shutting down gracefully");
                    let now = Utc::now();
                    let _ = signal_output.minio.flush(now).await;
                    let _ = signal_output.kafka.flush().await;
                    if let Some(ref ch) = signal_output.clickhouse {
                        let _ = ch.flush(now).await;
                    }
                    info!("Graceful shutdown complete");
                    std::process::exit(0);
                }
                _ => {}
            }
        }
    });

    let mut obs = ObservabilityServer::new();
    obs.start(
        config_handle.clone(),
        collector.ring_buffer.clone(),
        detector.clone(),
        alerter.clone(),
    )
    .await?;

    let enable_dashboard = {
        let cfg = config_handle.read().await;
        cfg.observability.enable_dashboard
    };

    if enable_dashboard {
        let _ = OutputManager::maybe_start_dashboard(config_handle.clone(), aggregator.clone());
    }

    let collector_ring = collector.ring_buffer.clone();
    let parser_c = parser_engine.clone();
    let aggregator_c = aggregator.clone();
    let detector_c = detector.clone();
    let kafka_c = output_manager.kafka.clone();
    let minio_c = output_manager.minio.clone();
    let clickhouse_c = output_manager.clickhouse.clone();
    let alerter_c = alerter.clone();

    let pipeline_task = tokio::task::spawn_blocking(move || {
        let mut log_counter: u64 = 0;
        let mut last_report = std::time::Instant::now();

        loop {
            let batch = collector_ring.pop_batch(BATCH_SIZE, Duration::from_millis(1));
            if batch.is_empty() {
                std::thread::sleep(Duration::from_millis(5));
                continue;
            }

            let now = Utc::now();

            for entry in batch {
                let record: LogRecord = entry.record;
                let parsed = {
                    let mut p = match parser_c.lock() {
                        Ok(g) => g,
                        Err(poisoned) => poisoned.into_inner(),
                    };
                    p.parse(record)
                };
                aggregator_c.ingest(&parsed);
                detector_c.ingest_record(&parsed, now);
                log_counter += 1;
            }

            let fine_results: Vec<WindowStats> = aggregator_c.drain_fine_results();
            for stats in &fine_results {
                detector_c.ingest_stats(&[stats.clone()], now);
                kafka_c.enqueue_stats(stats.clone());
                minio_c.enqueue(stats.clone());
                if let Some(ref ch) = clickhouse_c {
                    ch.enqueue(stats.clone());
                }
            }
            let coarse_results: Vec<WindowStats> = aggregator_c.drain_coarse_results();
            for stats in &coarse_results {
                kafka_c.enqueue_stats(stats.clone());
                minio_c.enqueue(stats.clone());
                if let Some(ref ch) = clickhouse_c {
                    ch.enqueue(stats.clone());
                }
            }
            let events: Vec<AlertEvent> = detector_c.drain_events();
            for ev in events {
                kafka_c.enqueue_anomaly(ev.clone());
                alerter_c.enqueue(ev, now);
            }

            if last_report.elapsed() >= Duration::from_secs(10) {
                let elapsed_secs = last_report.elapsed().as_secs_f64();
                let rate = if elapsed_secs > 0.0 {
                    log_counter as f64 / elapsed_secs
                } else {
                    0.0
                };
                let rb_len = collector_ring.len();
                let rb_cap = collector_ring.capacity();
                let rb_fill_pct = if rb_cap > 0 {
                    (rb_len as f64 / rb_cap as f64) * 100.0
                } else {
                    0.0
                };
                info!(
                    "Pipeline: {} lines, {:.0}/s, ring_buffer={}/{} ({:.1}%)",
                    log_counter, rate, rb_len, rb_cap, rb_fill_pct
                );
                last_report = std::time::Instant::now();
                log_counter = 0;
            }
        }
    });

    let aggregator_tick_c = aggregator.clone();
    let tick_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_millis(AGG_TICK_INTERVAL_MS));
        loop {
            interval.tick().await;
            let now = Utc::now();
            aggregator_tick_c.tick(now);
        }
    });

    let flush_task = {
        let kafka_flush = output_manager.kafka.clone();
        let minio_flush = output_manager.minio.clone();
        let alerter_flush = alerter.clone();
        let clickhouse_flush = output_manager.clickhouse.clone();
        tokio::spawn(async move {
            let mut kafka_interval = tokio::time::interval(Duration::from_millis(KAFKA_FLUSH_MS));
            let mut minio_interval = tokio::time::interval(Duration::from_secs(30));
            let mut alert_interval = tokio::time::interval(Duration::from_millis(ALERT_FLUSH_MS));
            let mut clickhouse_interval = tokio::time::interval(Duration::from_secs(5));
            loop {
                tokio::select! {
                    _ = kafka_interval.tick() => {
                        kafka_flush.flush().await;
                    }
                    _ = minio_interval.tick() => {
                        let now = Utc::now();
                        minio_flush.flush_if_needed(now).await;
                    }
                    _ = alert_interval.tick() => {
                        let now = Utc::now();
                        alerter_flush.flush(now).await;
                    }
                    _ = clickhouse_interval.tick() => {
                        if let Some(ref ch) = clickhouse_flush {
                            let now = Utc::now();
                            ch.flush_if_needed(now).await;
                        }
                    }
                }
            }
        })
    };

    let custom_format_count = parser_engine
        .lock()
        .map(|p| p.custom_format_count())
        .unwrap_or(0);
    info!("LogForge fully started. Listening for logs. Press Ctrl+C to quit.");
    info!("  - /health endpoint: http://localhost:9090/health");
    info!("  - /metrics endpoint: http://localhost:9090/metrics");
    info!("  - Parser: {} custom formats registered", custom_format_count);
    if output_manager.clickhouse.is_some() {
        info!("  - ClickHouse sink: enabled");
    } else {
        info!("  - ClickHouse sink: disabled (not configured)");
    }
    info!("  - Dashboard: enabled (press Q in dashboard to exit UI)");

    tokio::select! {
        _ = signal_task => {
            info!("Signal task ended");
        }
        _ = tick_task => {
            info!("Tick task ended");
        }
        res = pipeline_task => {
            match res {
                Ok(_) => info!("Pipeline task completed"),
                Err(e) => error!("Pipeline task crashed: {}", e),
            }
        }
        _ = flush_task => {
            info!("Flush task ended");
        }
    }

    Ok(())
}

async fn run_benchmark(args: &[String]) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    if args.len() < 2 {
        eprintln!("Usage: logforge benchmark <config.toml> <log_file> [sample_size]");
        eprintln!();
        eprintln!("Runs parser benchmark on a log file, outputting:");
        eprintln!("  - Hit rate for each custom format");
        eprintln!("  - Parsing time distribution (avg/P95/P99)");
        eprintln!("  - Format match ordering suggestions");
        std::process::exit(1);
    }

    let config_path = PathBuf::from(&args[1]);
    let log_path = PathBuf::from(&args[2]);
    let sample_size: usize = if args.len() >= 4 {
        args[3].parse().unwrap_or(10000)
    } else {
        10000
    };

    if !config_path.exists() {
        eprintln!("Error: config file {:?} not found", config_path);
        std::process::exit(1);
    }
    if !log_path.exists() {
        eprintln!("Error: log file {:?} not found", log_path);
        std::process::exit(1);
    }

    eprintln!("=== LogForge Parser Benchmark ===");
    eprintln!("Config: {:?}", config_path);
    eprintln!("Log file: {:?}", log_path);
    eprintln!("Sample size: up to {} lines", sample_size);
    eprintln!();

    let config_manager = ConfigManager::new(&config_path)?;
    let config_handle = config_manager.handle();
    let cfg = config_handle.read().await;

    let mut registry = CustomFormatRegistry::new();
    registry.load_from_configs(
        cfg.parser.custom_formats.clone(),
        cfg.parser.format_match_order.clone(),
    );

    eprintln!("Loaded {} custom format(s):", registry.format_count());
    for fmt in registry.get_stats() {
        eprintln!("  - {} (priority={}, id={})", fmt.name, fmt.priority, fmt.id);
    }
    eprintln!();

    eprintln!("Running benchmark...");
    let result = registry
        .run_benchmark(
            log_path.to_str().unwrap(),
            sample_size,
        )
        .await?;

    eprintln!();
    eprintln!("=== Benchmark Results ===");
    eprintln!("Total lines processed: {}", result.total_lines);
    eprintln!("Custom format hits: {} ({:.1}%)",
        result.total_hits,
        if result.total_lines > 0 {
            (result.total_hits as f64 / result.total_lines as f64) * 100.0
        } else {
            0.0
        }
    );
    eprintln!("Auto-detect fallback: {} ({:.1}%)",
        result.total_lines - result.total_hits,
        if result.total_lines > 0 {
            ((result.total_lines - result.total_hits) as f64 / result.total_lines as f64) * 100.0
        } else {
            0.0
        }
    );
    eprintln!();
    eprintln!("{:<30} {:>10} {:>12} {:>12} {:>12} {:>12}",
        "FORMAT", "HITS", "HIT%", "AVG(ns)", "P95(ns)", "P99(ns)");
    eprintln!("{:-<30} {:-<10} {:-<12} {:-<12} {:-<12} {:-<12}", "", "", "", "", "", "");

    let mut sorted_formats = result.per_format.clone();
    sorted_formats.sort_by(|a, b| b.hit_rate.partial_cmp(&a.hit_rate).unwrap_or(std::cmp::Ordering::Equal));

    for fmt in &sorted_formats {
        let hit_pct = if result.total_lines > 0 {
            (fmt.hits as f64 / result.total_lines as f64) * 100.0
        } else {
            0.0
        };
        eprintln!("{:<30} {:>10} {:>11.1}% {:>12} {:>12} {:>12}",
            fmt.name,
            fmt.hits,
            hit_pct,
            fmt.avg_ns,
            fmt.p95_ns,
            fmt.p99_ns
        );
    }

    eprintln!();
    eprintln!("=== Suggested Match Order ===");
    for (i, fmt) in sorted_formats.iter().enumerate() {
        if fmt.hits > 0 {
            eprintln!("  {}. \"{}\"  ({} hits, {:.1}% hit rate)",
                i + 1,
                fmt.id,
                fmt.hits,
                if result.total_lines > 0 {
                    (fmt.hits as f64 / result.total_lines as f64) * 100.0
                } else {
                    0.0
                }
            );
        }
    }
    eprintln!();
    eprintln!("Put the highest-hit-rate formats first in your config.toml's");
    eprintln!("format_match_order list to minimize average parsing time.");

    Ok(())
}
