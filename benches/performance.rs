use criterion::{criterion_group, criterion_main, Criterion, Throughput};
use logforge::interner::StringInterner;
use logforge::parser::{extract_features, ParserEngine};
use logforge::LogRecord;

fn generate_test_logs(count: usize) -> Vec<String> {
    let mut logs = Vec::with_capacity(count);
    for i in 0..count {
        let service_idx = i % 5;
        let level = match i % 10 {
            0 | 1 => "ERROR",
            2 | 3 => "WARN",
            _ => "INFO",
        };
        let service = match service_idx {
            0 => "api-gateway",
            1 => "order-service",
            2 => "user-service",
            3 => "payment-service",
            _ => "notification-service",
        };
        let spend = (i % 1000) as f64 * 0.1;
        let trace_id = format!("trace-{:016x}", i);
        let log = format!(
            r#"{{"timestamp":"2024-01-15T10:30:{:02}Z","level":"{}","service":"{}","trace_id":"{}","spend":{:.2},"message":"Processing request id={}"}}"#,
            i % 60,
            level,
            service,
            trace_id,
            spend,
            i
        );
        logs.push(log);
    }
    logs
}

fn generate_mixed_logs(count: usize) -> Vec<String> {
    let mut logs = Vec::with_capacity(count);
    for i in 0..count {
        match i % 4 {
            0 => {
                logs.push(format!(
                    r#"{{"timestamp":"2024-01-15T10:30:45Z","level":"INFO","service":"api-gateway","spend":{}.5,"message":"ok"}}"#,
                    i % 100
                ));
            }
            1 => {
                logs.push(format!(
                    r#"10.0.0.{} - - [15/Jan/2024:10:30:45 +0000] "GET /api/v1/users HTTP/1.1" {} 1234 "-" "curl/7.68.0" {}.3"#,
                    i % 255,
                    200 + (i % 200),
                    i % 500
                ));
            }
            2 => {
                logs.push(format!(
                    r#"[2024-01-15T10:30:45.{:03}Z] "POST /api/v1/orders HTTP/1.1" 200 56 103 {} 56 "-" "test-agent" "{}" "10.0.0.1:8080" "{}""#,
                    i % 1000,
                    i % 1000,
                    format!("trace-{:016x}", i),
                    if i % 3 == 0 { "200" } else { "503" }
                ));
            }
            _ => {
                logs.push(format!(
                    "2024-01-15T10:30:45Z,INFO,order-service,Processing order {},{}",
                    i,
                    i % 1000
                ));
            }
        }
    }
    logs
}

fn benchmark_feature_extraction(c: &mut Criterion) {
    let logs = generate_test_logs(1000);
    let mut group = c.benchmark_group("feature_extraction");
    group.throughput(Throughput::Elements(1));
    group.bench_function("single_line", |b| {
        b.iter(|| {
            let line = &logs[0];
            criterion::black_box(extract_features(line));
        });
    });
    group.bench_function("1000_lines", |b| {
        b.iter(|| {
            for line in &logs {
                criterion::black_box(extract_features(line));
            }
        });
    });
    group.finish();
}

fn benchmark_parsing_basic(c: &mut Criterion) {
    let logs = generate_test_logs(1000);
    let mut group = c.benchmark_group("parsing_basic");
    group.throughput(Throughput::Elements(1));

    {
        let mut engine = ParserEngine::new();
        group.bench_function("single_json_no_intern", |b| {
            b.iter(|| {
                let mut rec = LogRecord::new();
                rec.raw = logs[0].clone();
                criterion::black_box(engine.parse(rec));
            });
        });
    }

    {
        let interner = StringInterner::new();
        interner.install();
        let mut engine = ParserEngine::new().with_interner(interner.clone());
        group.bench_function("single_json_with_intern", |b| {
            b.iter(|| {
                let mut rec = LogRecord::new();
                rec.raw = logs[0].clone();
                criterion::black_box(engine.parse(rec));
            });
        });
        StringInterner::uninstall();
    }

    {
        let mut engine = ParserEngine::new();
        group.bench_function("1000_json_no_intern", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
    }

    {
        let interner = StringInterner::new();
        interner.install();
        let mut engine = ParserEngine::new().with_interner(interner.clone());
        group.bench_function("1000_json_with_intern", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
        StringInterner::uninstall();
    }

    group.finish();
}

fn benchmark_parsing_optimized_vs_original(c: &mut Criterion) {
    let logs = generate_mixed_logs(1000);
    let mut group = c.benchmark_group("parsing_optimized_mixed");
    group.throughput(Throughput::Elements(1000));

    group.bench_function("1000_mixed_two_stage_detection", |b| {
        b.iter(|| {
            for line in &logs {
                let feat = extract_features(line);
                criterion::black_box(feat);
            }
        });
    });

    {
        let mut engine = ParserEngine::new();
        group.bench_function("1000_mixed_full_parse", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
    }

    {
        let interner = StringInterner::new();
        interner.install();
        let mut engine = ParserEngine::new().with_interner(interner.clone());
        group.bench_function("1000_mixed_full_parse_with_intern", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
        StringInterner::uninstall();
    }

    group.finish();
}

fn benchmark_interner(c: &mut Criterion) {
    let mut group = c.benchmark_group("string_interner");

    let interner = StringInterner::new();
    interner.install();
    let test_strings = vec![
        "INFO", "DEBUG", "WARN", "ERROR", "FATAL",
        "api-gateway", "order-service", "user-service", "payment-service", "notification-service",
        "200", "404", "500", "503", "302",
        "GET", "POST", "PUT", "DELETE", "PATCH",
    ];

    group.bench_function("intern_unique", |b| {
        let mut i = 0;
        b.iter(|| {
            let s = test_strings[i % test_strings.len()];
            criterion::black_box(interner.intern(s));
            i += 1;
        });
    });

    group.bench_function("intern_hit", |b| {
        for s in &test_strings {
            interner.intern(s);
        }
        let mut i = 0;
        b.iter(|| {
            let s = test_strings[i % test_strings.len()];
            criterion::black_box(interner.intern(s));
            i += 1;
        });
    });

    group.bench_function("should_intern_value", |b| {
        b.iter(|| {
            criterion::black_box(logforge::interner::should_intern_value("api-gateway"));
            criterion::black_box(logforge::interner::should_intern_value("trace-abc123def4567890"));
        });
    });

    StringInterner::uninstall();

    group.finish();
}

fn benchmark_aggregation_ring(c: &mut Criterion) {
    use logforge::aggregator::{AggregationEngine, WindowRing};
    use logforge::{AggregationKey, LogLevel};
    use chrono::{TimeZone, Utc};

    let mut group = c.benchmark_group("aggregation_ring");

    let ring = WindowRing::new(10, 32, 100.0);
    group.bench_function("slot_index_for", |b| {
        let ts = Utc.with_ymd_and_hms(2024, 1, 15, 10, 30, 45).unwrap();
        b.iter(|| {
            criterion::black_box(ring.slot_index_for(ts));
        });
    });

    group.bench_function("bucket_for", |b| {
        let mut ring = WindowRing::new(10, 32, 100.0);
        let ts = Utc.with_ymd_and_hms(2024, 1, 15, 10, 30, 45).unwrap();
        b.iter(|| {
            let bucket = ring.bucket_for(ts);
            criterion::black_box(bucket);
        });
    });

    group.bench_function("ingest_single", |b| {
        let mut ring = WindowRing::new(10, 32, 100.0);
        let ts = Utc.with_ymd_and_hms(2024, 1, 15, 10, 30, 45).unwrap();
        let key = AggregationKey {
            service: "api-gateway".into(),
            level: LogLevel::Info,
        };
        b.iter(|| {
            let bucket = ring.bucket_for(ts);
            bucket.ingest(&key, Some(100.0));
        });
    });

    group.bench_function("ingest_mixed_1000", |b| {
        let mut ring = WindowRing::new(10, 32, 100.0);
        let base_ts = Utc.with_ymd_and_hms(2024, 1, 15, 10, 30, 0).unwrap();
        let keys: Vec<_> = (0..5).map(|i| {
            let service = match i {
                0 => "api-gateway",
                1 => "order-service",
                2 => "user-service",
                3 => "payment-service",
                _ => "notification-service",
            };
            let level = match i % 3 {
                0 => LogLevel::Error,
                1 => LogLevel::Warn,
                _ => LogLevel::Info,
            };
            AggregationKey { service: service.into(), level }
        }).collect();

        b.iter(|| {
            for i in 0..1000 {
                let ts = base_ts + chrono::Duration::seconds((i % 60) as i64);
                let key = &keys[i % keys.len()];
                let bucket = ring.bucket_for(ts);
                bucket.ingest(key, Some((i % 1000) as f64 * 0.1));
            }
        });
    });

    group.finish();
}

fn benchmark_throughput_comparison(c: &mut Criterion) {
    let logs = generate_mixed_logs(10000);
    let mut group = c.benchmark_group("throughput_comparison");
    group.throughput(Throughput::Elements(10000));
    group.sample_size(10);

    {
        let mut engine = ParserEngine::new();
        group.bench_function("baseline_10k_lines", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
    }

    {
        let interner = StringInterner::new();
        interner.install();
        let mut engine = ParserEngine::new().with_interner(interner.clone());
        group.bench_function("optimized_10k_lines_with_intern", |b| {
            b.iter(|| {
                for line in &logs {
                    let mut rec = LogRecord::new();
                    rec.raw = line.clone();
                    criterion::black_box(engine.parse(rec));
                }
            });
        });
        StringInterner::uninstall();
    }

    group.finish();
}

criterion_group!(
    benches,
    benchmark_feature_extraction,
    benchmark_parsing_basic,
    benchmark_parsing_optimized_vs_original,
    benchmark_interner,
    benchmark_aggregation_ring,
    benchmark_throughput_comparison,
);
criterion_main!(benches);
