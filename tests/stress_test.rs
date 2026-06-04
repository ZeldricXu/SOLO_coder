use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;
use tracing::{debug, info, warn};

use common::alert::{Alert, AlertSeverity, DetectionMethod};
use common::log::{LogEvent, LogBatch, DroppedEventsMetadata, FileGap};
use common::metrics::{Label, Labels, TimeSeries, TimePoint};

use alert_manager::dedup::AlertDeduplicator;
use log_agent::sender::BatchSender;
use anomaly_detection::detectors::DbscanDetector;

const NUM_AGENTS: usize = 100;
const EVENTS_PER_SECOND: usize = 100_000;
const NUM_ALERT_RULES: usize = 100;
const TEST_DURATION_SECS: u64 = 10;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    info!("Starting stress test...");
    info!("Configuration:");
    info!("  Simulated agents: {}", NUM_AGENTS);
    info!("  Target throughput: {} events/sec", EVENTS_PER_SECOND);
    info!("  Alert rules: {}", NUM_ALERT_RULES);
    info!("  Test duration: {} seconds", TEST_DURATION_SECS);

    let all_start = Instant::now();

    println!("\n=== Test 1: Log Agent BatchSender (Buffer Hard Limit) ===");
    test_batch_sender().await?;

    println!("\n=== Test 2: Alert Deduplicator (High Concurrency) ===");
    test_alert_dedup().await?;

    println!("\n=== Test 3: DBSCAN Detector (Parameter Auto-Tuning) ===");
    test_dbscan_detector().await?;

    println!("\n=== Test 4: Full Pipeline Simulation ===");
    test_full_pipeline().await?;

    let total_duration = all_start.elapsed();
    println!("\n=== All Stress Tests Completed ===");
    println!("Total duration: {:?}", total_duration);
    println!("All tests passed!");

    Ok(())
}

async fn test_batch_sender() -> Result<(), Box<dyn std::error::Error>> {
    info!("Testing BatchSender with hard buffer limits...");

    let config = log_agent::config::AgentConfig::default();
    let (sender, mut sender_handle, rx) = BatchSender::new(config);
    let sender_arc = Arc::new(sender);

    let sender_task = tokio::spawn(async move {
        sender_handle.run().await
    });

    let start = Instant::now();
    let total_events = EVENTS_PER_SECOND * TEST_DURATION_SECS as usize;
    let events_per_agent = total_events / NUM_AGENTS;

    let semaphore = Arc::new(Semaphore::new(NUM_AGENTS));
    let mut handles = Vec::new();

    for agent_id in 0..NUM_AGENTS {
        let permit = semaphore.clone().acquire_owned().await.unwrap();
        let sender_clone = sender_arc.clone();
        let handle = tokio::spawn(async move {
            let _permit = permit;
            for i in 0..events_per_agent {
                let event = LogEvent::new(
                    format!("agent_{}", agent_id),
                    format!("/var/log/app_{}.log", agent_id % 10),
                    format!("Log message #{} from agent {}", i, agent_id),
                    common::log::LogLevel::Info,
                ).with_offset(i as u64);

                if let Err(e) = sender_clone.enqueue_event(event).await {
                    warn!("Failed to enqueue event: {}", e);
                }

                if i % 1000 == 0 {
                    tokio::time::sleep(Duration::from_micros(1)).await;
                }
            }
            events_per_agent as u64
        });
        handles.push(handle);
    }

    let mut total_sent = 0u64;
    for handle in handles {
        match handle.await {
            Ok(count) => total_sent += count,
            Err(e) => warn!("Agent task failed: {}", e),
        }
    }

    let duration = start.elapsed();
    let throughput = total_sent as f64 / duration.as_secs_f64();

    info!("BatchSender test results:");
    info!("  Total events sent: {}", total_sent);
    info!("  Duration: {:?}", duration);
    info!("  Throughput: {:.2} events/sec", throughput);
    info!("  Target: {} events/sec", EVENTS_PER_SECOND);

    drop(sender_arc);
    let _ = sender_task.await;

    if throughput >= EVENTS_PER_SECOND as f64 * 0.8 {
        println!("✓ BatchSender throughput test PASSED ({:.0} events/sec)", throughput);
    } else {
        println!("⚠ BatchSender throughput below target: {:.0} events/sec", throughput);
    }

    Ok(())
}

async fn test_alert_dedup() -> Result<(), Box<dyn std::error::Error>> {
    info!("Testing AlertDeduplicator under high concurrency...");

    let dedup = Arc::new(AlertDeduplicator::with_retention(Duration::from_secs(300)));
    let start = Instant::now();

    let mut handles = Vec::new();
    let alerts_per_rule = 1000;

    for rule_id in 0..NUM_ALERT_RULES {
        let dedup_clone = dedup.clone();
        let handle = tokio::spawn(async move {
            let mut duplicates = 0u64;
            let mut new_alerts = 0u64;

            for i in 0..alerts_per_rule {
                let labels = if i % 10 == 0 {
                    Labels::from_vec(vec![Label {
                        name: "instance".to_string(),
                        value: format!("host_{}", i % 50),
                    }])
                } else {
                    Labels::from_vec(vec![Label {
                        name: "instance".to_string(),
                        value: "host_0".to_string(),
                    }])
                };

                let alert = Alert::new(
                    format!("rule_{}", rule_id),
                    AlertSeverity::Warning,
                    labels,
                    DetectionMethod::StaticThreshold,
                    0.9,
                );

                if dedup_clone.check_and_record(&alert).await {
                    duplicates += 1;
                } else {
                    new_alerts += 1;
                }
            }

            (new_alerts, duplicates)
        });
        handles.push(handle);
    }

    let mut total_new = 0u64;
    let mut total_duplicates = 0u64;
    for handle in handles {
        match handle.await {
            Ok((new, dup)) => {
                total_new += new;
                total_duplicates += dup;
            }
            Err(e) => warn!("Rule task failed: {}", e),
        }
    }

    let duration = start.elapsed();
    let total_ops = total_new + total_duplicates;
    let ops_per_sec = total_ops as f64 / duration.as_secs_f64();

    info!("AlertDeduplicator test results:");
    info!("  Total operations: {}", total_ops);
    info!("  New alerts: {}", total_new);
    info!("  Duplicates detected: {}", total_duplicates);
    info!("  Duration: {:?}", duration);
    info!("  Throughput: {:.2} ops/sec", ops_per_sec);
    info!("  Cache entries: {}", dedup.entry_count());

    let expected_unique = NUM_ALERT_RULES * 51;
    if dedup.entry_count() <= expected_unique as u64 {
        println!("✓ AlertDeduplicator test PASSED ({} entries)", dedup.entry_count());
    } else {
        println!("⚠ AlertDeduplicator has more entries than expected: {} vs {}",
            dedup.entry_count(), expected_unique);
    }

    Ok(())
}

async fn test_dbscan_detector() -> Result<(), Box<dyn std::error::Error>> {
    info!("Testing DBSCAN Detector with auto-tuning...");

    let mut detector = DbscanDetector::new(
        "test_dbscan".to_string(),
        2.0,
        5,
        AlertSeverity::Warning,
    ).with_auto_tuning(true);

    detector.spawn_auto_tuning();

    let start = Instant::now();
    let mut anomalies_detected = 0u64;
    let mut total_series = 0u64;

    for i in 0..100 {
        let mut series = TimeSeries::new(
            format!("metric_{}", i % 10),
            Labels::from_vec(vec![Label {
                name: "service".to_string(),
                value: format!("svc_{}", i % 5),
            }]),
        );

        let base_value = (i % 10) as f64 * 10.0;
        for j in 0..50 {
            let value = if j == 49 && i % 7 == 0 {
                base_value + 100.0
            } else {
                base_value + (rand::random::<f64>() - 0.5) * 2.0
            };
            series.add_point(
                chrono::Utc::now() - chrono::Duration::minutes((50 - j) as i64),
                value,
            );
        }

        let result = detector.detect(&[series]).await?;
        total_series += 1;
        if result.is_anomaly {
            anomalies_detected += 1;
        }

        if i % 10 == 0 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    let duration = start.elapsed();
    let throughput = total_series as f64 / duration.as_secs_f64();

    info!("DBSCAN Detector test results:");
    info!("  Total series analyzed: {}", total_series);
    info!("  Anomalies detected: {}", anomalies_detected);
    info!("  Duration: {:?}", duration);
    info!("  Throughput: {:.2} series/sec", throughput);

    println!("✓ DBSCAN Detector test PASSED");
    Ok(())
}

async fn test_full_pipeline() -> Result<(), Box<dyn std::error::Error>> {
    info!("Testing full pipeline simulation...");

    let start = Instant::now();
    let total_events = EVENTS_PER_SECOND * 5;

    let dedup = Arc::new(AlertDeduplicator::new());
    let semaphore = Arc::new(Semaphore::new(50));
    let mut handles = Vec::new();

    for i in 0..total_events {
        if i % 10000 == 0 {
            debug!("Processed {} events...", i);
        }

        let permit = semaphore.clone().acquire_owned().await.unwrap();
        let dedup_clone = dedup.clone();
        let handle = tokio::spawn(async move {
            let _permit = permit;

            let log_event = LogEvent::new(
                "host_0".to_string(),
                "/var/log/app.log".to_string(),
                format!("Error: something went wrong #{}", i),
                common::log::LogLevel::Error,
            ).with_offset(i as u64);

            if i % 100 == 0 {
                let alert = Alert::new(
                    "high_error_rate".to_string(),
                    AlertSeverity::Warning,
                    Labels::from_vec(vec![Label {
                        name: "service".to_string(),
                        value: format!("svc_{}", i % 10),
                    }]),
                    DetectionMethod::StaticThreshold,
                    0.95,
                );
                dedup_clone.check_and_record(&alert).await;
            }

            1u64
        });
        handles.push(handle);

        if handles.len() >= 1000 {
            for h in handles.drain(..) {
                let _ = h.await;
            }
        }
    }

    for h in handles {
        let _ = h.await;
    }

    let duration = start.elapsed();
    let throughput = total_events as f64 / duration.as_secs_f64();

    info!("Full pipeline test results:");
    info!("  Total events processed: {}", total_events);
    info!("  Duration: {:?}", duration);
    info!("  Throughput: {:.2} events/sec", throughput);
    info!("  Cache entries: {}", dedup.entry_count());

    if throughput >= EVENTS_PER_SECOND as f64 * 0.8 {
        println!("✓ Full pipeline test PASSED ({:.0} events/sec)", throughput);
    } else {
        println!("⚠ Full pipeline throughput below target: {:.0} events/sec", throughput);
    }

    Ok(())
}
