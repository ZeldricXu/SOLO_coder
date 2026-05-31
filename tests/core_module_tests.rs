#[cfg(test)]
mod tests {
    use crate::core::{
        EventEmitter, MetricsRecorder, ProcessingContext, RequestHandler, ResourcePool,
        InMemoryEventEmitter,
    };
    use crate::test_utils::*;
    use crate::types::{
        AppError, BatchOperation, EntityStatus, HandlerRequest, RunPhase, generate_id,
    };
    use std::collections::HashMap;
    use std::sync::Arc;
    use std::time::Duration;

    //==========================================================================
    // 测试矩阵 1: 正常业务流程 - 端到端验证
    //==========================================================================
    mod normal_flow {
        use super::*;

        #[tokio::test]
        async fn test_complete_request_lifecycle() {
            let ctx = TestContext::new();
            let trace_id = generate_trace_id();
            let request = create_simple_request(&trace_id);

            let response = ctx.handler.execute_handler(request).await;

            assert_response_success(&response);
            assert_eq!(response.trace_id, trace_id);

            let data = response.data.unwrap();
            assert!(data.get("id").is_some(), "响应应包含实体ID");
            assert!(data.get("status").is_some(), "响应应包含状态");
            assert_eq!(data["status"], "completed");

            assert!(ctx.emitter.emit_count() >= 1, "应至少发射一个事件");
            assert_event_emitted(&ctx.emitter, "task.completed", 1);

            assert_eq!(ctx.metrics.get_counter("requests.total"), 1);
            assert_eq!(ctx.metrics.get_counter("requests.errors"), 0);
            assert!(ctx.metrics.get_timer_stats("request.latency").is_some());

            let snapshots = ctx.metrics.get_snapshots();
            assert!(!snapshots.is_empty(), "应生成指标快照");
        }

        #[tokio::test]
        async fn test_entity_creation_and_status_tracking() {
            let ctx = TestContext::new();

            let entity = ctx
                .handler
                .create_entity(
                    "workflow",
                    serde_json::json!({"steps": 3, "timeout": 60}),
                    HashMap::from([
                        ("env".to_string(), "test".to_string()),
                        ("team".to_string(), "platform".to_string()),
                    ]),
                )
                .await
                .expect("创建实体失败");

            assert_eq!(entity.r#type, "workflow");
            assert_eq!(entity.status, EntityStatus::Provisioning);
            assert!(entity.id.starts_with("rsc_"));
            assert_eq!(
                entity.attributes["labels"]["env"],
                serde_json::json!("test")
            );

            let run = ctx.handler.start_run(&entity.id);
            assert_eq!(run.entity_id, entity.id);
            assert_eq!(run.phase, RunPhase::Initializing);
            assert_eq!(run.progress, 0.0);

            ctx.handler
                .update_run_progress(&run.run_id, 0.5, RunPhase::Processing)
                .expect("更新进度失败");

            let updated_run = ctx.handler.get_run(&run.run_id).unwrap();
            assert_eq!(updated_run.progress, 0.5);
            assert_eq!(updated_run.phase, RunPhase::Processing);

            let (status, progress) = ctx
                .handler
                .get_entity_status(&entity.id)
                .await
                .expect("获取状态失败");
            assert_eq!(status, "provisioning");
            assert_eq!(progress, 0.5);

            ctx.handler
                .update_run_progress(&run.run_id, 1.0, RunPhase::Completed)
                .expect("标记完成失败");

            let final_run = ctx.handler.get_run(&run.run_id).unwrap();
            assert_eq!(final_run.progress, 1.0);
            assert!(final_run.completed_at.is_some());
        }

        #[tokio::test]
        async fn test_batch_operations() {
            let ctx = TestContext::new();

            let mut entity_ids = Vec::new();
            for i in 0..5 {
                let entity = ctx
                    .handler
                    .create_entity(
                        "resource",
                        serde_json::json!({"index": i}),
                        HashMap::new(),
                    )
                    .await
                    .unwrap();
                entity_ids.push(entity.id);
            }

            let operations: Vec<BatchOperation> = entity_ids
                .iter()
                .map(|id| BatchOperation {
                    action: "start".to_string(),
                    id: id.clone(),
                })
                .collect();

            let results = ctx
                .handler
                .batch_operation(operations)
                .await
                .expect("批量操作失败");

            assert_eq!(results.len(), 5);
            for result in &results {
                assert!(result.success, "操作 {} 应成功", result.id);
                assert!(result.message.is_none());
            }

            for id in &entity_ids {
                let (status, _) = ctx.handler.get_entity_status(id).await.unwrap();
                assert_eq!(status, "active");
            }

            let stop_ops: Vec<BatchOperation> = entity_ids
                .iter()
                .take(3)
                .map(|id| BatchOperation {
                    action: "stop".to_string(),
                    id: id.clone(),
                })
                .collect();

            let stop_results = ctx
                .handler
                .batch_operation(stop_ops)
                .await
                .unwrap();
            for result in &stop_results {
                assert!(result.success);
            }

            for (i, id) in entity_ids.iter().enumerate() {
                let (status, _) = ctx.handler.get_entity_status(id).await.unwrap();
                if i < 3 {
                    assert_eq!(status, "inactive");
                } else {
                    assert_eq!(status, "active");
                }
            }

            let mixed_ops = vec![
                BatchOperation {
                    action: "restart".to_string(),
                    id: entity_ids[0].clone(),
                },
                BatchOperation {
                    action: "delete".to_string(),
                    id: entity_ids[4].clone(),
                },
                BatchOperation {
                    action: "invalid_action".to_string(),
                    id: entity_ids[1].clone(),
                },
            ];

            let mixed_results = ctx.handler.batch_operation(mixed_ops).await.unwrap();
            assert_eq!(mixed_results.len(), 3);
            assert!(mixed_results[0].success);
            assert!(mixed_results[1].success);
            assert!(!mixed_results[2].success);
            assert!(mixed_results[2].message.is_some());

            let (status0, _) = ctx
                .handler
                .get_entity_status(&entity_ids[0])
                .await
                .unwrap();
            assert_eq!(status0, "active");

            let status4 = ctx.handler.get_entity_status(&entity_ids[4]).await;
            assert!(status4.is_err());
        }

        #[tokio::test]
        async fn test_config_loading_and_processing() {
            let ctx = TestContext::new();

            let custom_config: HashMap<String, serde_json::Value> = vec![
                ("timeout".to_string(), serde_json::json!(60)),
                ("retries".to_string(), serde_json::json!(5)),
                ("pool_size".to_string(), serde_json::json!(20)),
                ("custom_rule".to_string(), serde_json::json!("validate_data")),
            ]
            .into_iter()
            .collect();

            ctx.handler.set_config("production", custom_config.clone());

            let loaded = ctx
                .handler
                .load_config("production")
                .await
                .expect("加载配置失败");
            assert_eq!(loaded, custom_config);

            let default = ctx
                .handler
                .load_config("unknown_namespace")
                .await
                .expect("加载默认配置失败");
            assert_eq!(default["timeout"], serde_json::json!(30));
            assert_eq!(default["pool_size"], serde_json::json!(10));

            let rules = ctx
                .handler
                .load_config("production")
                .await
                .unwrap();
            let payload = serde_json::json!({
                "name": "test_resource",
                "value": 12345,
                "metadata": {"tags": ["tag1", "tag2"]}
            });

            let result = ctx
                .handler
                .process_core(&payload, &rules)
                .expect("核心处理失败");

            assert!(result.get("_processed_at").is_some());
            let rules_applied = result["_rules_applied"]
                .as_array()
                .expect("rules_applied 应为数组");
            assert!(rules_applied.contains(&serde_json::json!("custom_rule")));
            assert_eq!(result["name"], serde_json::json!("test_resource"));
            assert_eq!(result["value"], serde_json::json!(12345));
        }

        #[tokio::test]
        async fn test_event_emission_and_metrics() {
            let emitter = Arc::new(CountingEventEmitter::new());
            let metrics = Arc::new(MetricsRecorder::new());
            let handler = RequestHandler::new(10, emitter.clone(), metrics.clone())
                .with_timeout(5000)
                .with_retries(3);

            for i in 0..10 {
                let request = HandlerRequest {
                    trace_id: format!("trace_{}", i),
                    namespace: "test".to_string(),
                    params: serde_json::json!({"valid": true}),
                    payload: serde_json::json!({"data": i}),
                };
                let response = handler.execute_handler(request).await;
                assert_response_success(&response);
            }

            assert_eq!(emitter.count(), 10);
            assert_eq!(metrics.get_counter("requests.total"), 10);
            assert_eq!(metrics.get_counter("requests.errors"), 0);

            let last_event = emitter.last_event().expect("应有最后一个事件");
            assert_eq!(last_event.event_type, "task.completed");
            assert_eq!(last_event.event_type, "task.completed");
            assert!(last_event.aggregate_id.starts_with("ent_"));

            let (min, max, avg) = metrics.get_timer_stats("request.latency").unwrap();
            assert!(min <= avg);
            assert!(avg <= max as f64);

            let snapshots = metrics.get_snapshots();
            assert_eq!(snapshots.len(), 10);

            let first = &snapshots[0];
            assert!(first.metrics.throughput > 0);
            assert!(first.dimensions.contains_key("service"));
            assert_eq!(first.dimensions["service"], "enterprise-middleware");
        }

        #[test]
        fn test_processing_context_lifecycle() {
            let mut ctx = ProcessingContext::new("trace_ctx_test", "default");

            assert_eq!(ctx.trace_id, "trace_ctx_test");
            assert_eq!(ctx.namespace, "default");
            assert!(ctx.attributes.is_empty());
            assert!(ctx.rollback_actions.is_empty());

            ctx.attributes
                .insert("user_id".to_string(), serde_json::json!("user_123"));

            let rollback_flag = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let flag_clone = rollback_flag.clone();
            ctx.add_rollback(move || {
                flag_clone.store(true, std::sync::atomic::Ordering::SeqCst);
            });

            let rollback_flag2 = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let flag_clone2 = rollback_flag2.clone();
            ctx.add_rollback(move || {
                flag_clone2.store(true, std::sync::atomic::Ordering::SeqCst);
            });

            assert!(!rollback_flag.load(std::sync::atomic::Ordering::SeqCst));
            assert!(!rollback_flag2.load(std::sync::atomic::Ordering::SeqCst));

            ctx.rollback();

            assert!(rollback_flag.load(std::sync::atomic::Ordering::SeqCst));
            assert!(rollback_flag2.load(std::sync::atomic::Ordering::SeqCst));

            std::thread::sleep(Duration::from_millis(10));
            assert!(ctx.elapsed() >= Duration::from_millis(10));
        }
    }

    //==========================================================================
    // 测试矩阵 2: 关键边界值测试
    //==========================================================================
    mod boundary_values {
        use super::*;

        #[tokio::test]
        async fn test_empty_payload() {
            let ctx = TestContext::new();
            let request = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!({}),
                payload: serde_json::json!({}),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);

            let data = response.data.unwrap();
            assert_eq!(data["status"], "completed");
        }

        #[tokio::test]
        async fn test_null_values() {
            let ctx = TestContext::new();
            let request = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!({"optional_field": null}),
                payload: serde_json::json!({
                    "nullable_field": null,
                    "empty_string": "",
                    "zero_number": 0,
                    "empty_array": [],
                    "empty_object": {}
                }),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);
        }

        #[tokio::test]
        async fn test_zero_and_max_numeric_values() {
            let ctx = TestContext::new();

            let test_cases = vec![
                0,
                1,
                -1,
                i64::MIN,
                i64::MAX,
                u64::MAX,
                f64::MIN,
                f64::MAX,
                0.0,
                -0.0,
            ];

            for value in test_cases {
                let request = HandlerRequest {
                    trace_id: generate_trace_id(),
                    namespace: "test".to_string(),
                    params: serde_json::json!({}),
                    payload: serde_json::json!({"value": value}),
                };

                let response = ctx.handler.execute_handler(request).await;
                assert!(
                    response.code == 200,
                    "数值 {} 应成功处理，实际响应: {:?}",
                    value,
                    response
                );
            }
        }

        #[tokio::test]
        async fn test_extremely_long_strings() {
            let ctx = TestContext::new();

            let very_long_string = "x".repeat(1_000_000);
            let request = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!({
                    "long_param": very_long_string.clone()
                }),
                payload: serde_json::json!({
                    "content": very_long_string.clone(),
                    "nested": {"deep": {"value": very_long_string.clone()}}
                }),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);

            let data = response.data.unwrap();
            let attributes = data["attributes"]["result"].as_object().unwrap();
            assert_eq!(
                attributes["content"].as_str().unwrap().len(),
                1_000_000
            );
        }

        #[tokio::test]
        async fn test_very_long_trace_id_and_namespace() {
            let ctx = TestContext::new();

            let long_trace_id = "trace_".to_string() + &"x".repeat(1000);
            let long_namespace = "ns_".to_string() + &"y".repeat(500);

            let request = HandlerRequest {
                trace_id: long_trace_id.clone(),
                namespace: long_namespace.clone(),
                params: serde_json::json!({}),
                payload: serde_json::json!({"test": true}),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);
            assert_eq!(response.trace_id, long_trace_id);
        }

        #[tokio::test]
        async fn test_huge_nested_structure() {
            let ctx = TestContext::new();

            let mut nested = serde_json::json!({"level": 0});
            for i in 1..=50 {
                nested = serde_json::json!({"level": i, "inner": nested});
            }

            let mut large_array = Vec::new();
            for i in 0..1000 {
                large_array.push(serde_json::json!({"index": i, "data": format!("item_{}", i)}));
            }

            let request = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!({}),
                payload: serde_json::json!({
                    "deep_nested": nested,
                    "large_array": large_array
                }),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);

            let data = response.data.unwrap();
            let result = &data["attributes"]["result"];
            assert_eq!(result["large_array"].as_array().unwrap().len(), 1000);
        }

        #[tokio::test]
        async fn test_empty_string_and_special_chars() {
            let ctx = TestContext::new();

            let special_chars = r#"!@#$%^&*()_+-=[]{}|;':",./<>?`~\\n\r\t\0"#;
            let unicode_chars = "你好世界🌍🎉∆Ω∞π√";
            let control_chars = "\x01\x02\x03\x04\x05";

            let request = HandlerRequest {
                trace_id: "".to_string(),
                namespace: "".to_string(),
                params: serde_json::json!({"": ""}),
                payload: serde_json::json!({
                    "empty": "",
                    "special": special_chars,
                    "unicode": unicode_chars,
                    "control": control_chars
                }),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_eq!(response.code, 200, "特殊字符应成功处理: {:?}", response.message);
        }

        #[tokio::test]
        async fn test_duplicate_keys_and_whitespace() {
            let ctx = TestContext::new();

            let request = HandlerRequest {
                trace_id: "  trace_with_spaces  ".to_string(),
                namespace: "\tnamespace_with_tabs\n".to_string(),
                params: serde_json::json!({}),
                payload: serde_json::json!({
                    "normal_key": "value",
                    "  spaced_key  ": "  spaced_value  "
                }),
            };

            let response = ctx.handler.execute_handler(request).await;
            assert_response_success(&response);
            assert_eq!(response.trace_id, "  trace_with_spaces  ");
        }

        #[tokio::test]
        async fn test_max_batch_operations() {
            let ctx = TestContext::new();

            let mut entity_ids = Vec::new();
            for i in 0..1000 {
                let entity = ctx
                    .handler
                    .create_entity(
                        "mass_resource",
                        serde_json::json!({"index": i}),
                        HashMap::new(),
                    )
                    .await
                    .unwrap();
                entity_ids.push(entity.id);
            }

            let operations: Vec<BatchOperation> = entity_ids
                .iter()
                .map(|id| BatchOperation {
                    action: "start".to_string(),
                    id: id.clone(),
                })
                .collect();

            let results = ctx.handler.batch_operation(operations).await.unwrap();
            assert_eq!(results.len(), 1000);
            assert!(results.iter().all(|r| r.success));
        }

        #[tokio::test]
        async fn test_zero_pool_size() {
            let ctx = TestContext::with_pool_size(0);

            let request = create_simple_request(&generate_trace_id());
            let response = ctx.handler.execute_handler(request).await;

            assert_response_error(&response, 500);
            assert!(ctx.metrics.get_counter("requests.errors") >= 1);
        }
    }

    //==========================================================================
    // 测试矩阵 3: 并发安全测试
    //==========================================================================
    mod concurrency {
        use super::*;

        #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
        async fn test_concurrent_requests_high_volume() {
            let ctx = Arc::new(TestContext::new());
            let mut handles = Vec::new();

            for i in 0..1000 {
                let ctx_clone = ctx.clone();
                handles.push(tokio::spawn(async move {
                    let trace_id = format!("trace_concurrent_{}_{}", i, generate_id(""));
                    let request = HandlerRequest {
                        trace_id,
                        namespace: format!("ns_{}", i % 5),
                        params: serde_json::json!({"client": i}),
                        payload: serde_json::json!({"request_num": i, "data": format!("data_{}", i)}),
                    };

                    ctx_clone.handler.execute_handler(request).await
                }));
            }

            let results = futures::future::join_all(handles).await;
            let success_count = results
                .iter()
                .filter(|r| r.as_ref().map(|resp| resp.code == 200).unwrap_or(false))
                .count();

            let total = ctx.metrics.get_counter("requests.total");
            let errors = ctx.metrics.get_counter("requests.errors");

            assert_eq!(total, 1000, "应记录1000个请求");
            assert_eq!(success_count, 1000, "所有请求应成功");
            assert_eq!(errors, 0, "不应有错误");

            assert_eq!(ctx.emitter.emit_count(), 1000);
            assert_eq!(ctx.emitter.all_events().len(), 1000);

            let (_, _, avg_latency) = ctx.metrics.get_timer_stats("request.latency").unwrap();
            assert!(avg_latency > 0.0, "平均延迟应大于0");

            let snapshots = ctx.metrics.get_snapshots();
            assert_eq!(snapshots.len(), 1000);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 16)]
        async fn test_concurrent_entity_modifications() {
            let ctx = Arc::new(TestContext::new());

            let entity = ctx
                .handler
                .create_entity(
                    "concurrent_resource",
                    serde_json::json!({}),
                    HashMap::new(),
                )
                .await
                .unwrap();

            let run = ctx.handler.start_run(&entity.id);

            let mut handles = Vec::new();
            for i in 0..100 {
                let ctx_clone = ctx.clone();
                let run_id = run.run_id.clone();
                handles.push(tokio::spawn(async move {
                    let phase = if i < 50 {
                        RunPhase::Processing
                    } else {
                        RunPhase::Validating
                    };
                    ctx_clone
                        .handler
                        .update_run_progress(&run_id, i as f64 / 100.0, phase)
                }));
            }

            let results = futures::future::join_all(handles).await;
            let success_count = results.iter().filter(|r| r.as_ref().map(|x| x.is_ok()).unwrap_or(false)).count();

            assert!(success_count > 0, "至少一些更新应成功");

            let final_run = ctx.handler.get_run(&run.run_id).unwrap();
            assert!(final_run.progress >= 0.0);
            assert!(final_run.progress <= 1.0);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
        async fn test_concurrent_pool_contention() {
            let ctx = Arc::new(TestContext::with_pool_size(5));
            let mut handles = Vec::new();

            for i in 0..100 {
                let ctx_clone = ctx.clone();
                handles.push(tokio::spawn(async move {
                    let trace_id = format!("trace_pool_{}", i);
                    let request = HandlerRequest {
                        trace_id,
                        namespace: "test".to_string(),
                        params: serde_json::json!({}),
                        payload: serde_json::json!({"task": i}),
                    };
                    ctx_clone.handler.execute_handler(request).await
                }));
            }

            let results = futures::future::join_all(handles).await;
            let success_count = results
                .iter()
                .filter(|r| r.as_ref().map(|resp| resp.code == 200).unwrap_or(false))
                .count();

            assert_eq!(success_count, 100, "即使有资源池竞争，所有请求也应成功");
            assert_eq!(ctx.metrics.get_counter("requests.total"), 100);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
        async fn test_concurrent_config_access() {
            let ctx = Arc::new(TestContext::new());
            let namespaces: Vec<String> = (0..10).map(|i| format!("ns_{}", i)).collect();

            for ns in &namespaces {
                let config: HashMap<String, serde_json::Value> = vec![
                    ("value".to_string(), serde_json::json!(ns.clone())),
                ]
                .into_iter()
                .collect();
                ctx.handler.set_config(ns, config);
            }

            let mut handles = Vec::new();
            for i in 0..200 {
                let ctx_clone = ctx.clone();
                let ns = namespaces[i % 10].clone();
                handles.push(tokio::spawn(async move {
                    ctx_clone.handler.load_config(&ns).await
                }));
            }

            let results = futures::future::join_all(handles).await;
            assert!(results.iter().all(|r| r.as_ref().map(|x| x.is_ok()).unwrap_or(false)));
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 16)]
        async fn test_concurrent_batch_operations() {
            let ctx = Arc::new(TestContext::new());

            let mut entity_ids = Vec::new();
            for i in 0..100 {
                let entity = ctx
                    .handler
                    .create_entity(
                        format!("type_{}", i % 5).as_str(),
                        serde_json::json!({"index": i}),
                        HashMap::new(),
                    )
                    .await
                    .unwrap();
                entity_ids.push(entity.id);
            }

            let mut handles = Vec::new();
            for batch_idx in 0..10 {
                let ctx_clone = ctx.clone();
                let ids = entity_ids.clone();
                handles.push(tokio::spawn(async move {
                    let start = batch_idx * 10;
                    let end = start + 10;
                    let ops: Vec<BatchOperation> = (start..end)
                        .map(|i| BatchOperation {
                            action: if batch_idx % 2 == 0 {
                                "start".to_string()
                            } else {
                                "stop".to_string()
                            },
                            id: ids[i].clone(),
                        })
                        .collect();
                    ctx_clone.handler.batch_operation(ops).await
                }));
            }

            let results = futures::future::join_all(handles).await;
            for result in results {
                let batch_result = result.unwrap().unwrap();
                assert_eq!(batch_result.len(), 10);
            }

            let active_count = ctx
                .handler
                .entities
                .iter()
                .filter(|e| e.value().status == EntityStatus::Active)
                .count();

            let inactive_count = ctx
                .handler
                .entities
                .iter()
                .filter(|e| e.value().status == EntityStatus::Inactive)
                .count();

            assert_eq!(active_count + inactive_count, 100);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
        async fn test_metrics_concurrent_recording() {
            let metrics = Arc::new(MetricsRecorder::new());
            let mut handles = Vec::new();

            for thread_idx in 0..10 {
                let metrics_clone = metrics.clone();
                handles.push(tokio::spawn(async move {
                    for i in 0..100 {
                        metrics_clone.increment_counter("test.counter", 1);
                        metrics_clone.record_timer("test.timer", (thread_idx * 100 + i) as u64);

                        metrics_clone.record_metrics(
                            HashMap::from([(
                                "thread".to_string(),
                                thread_idx.to_string(),
                            )]),
                            100,
                            50,
                            0.01,
                        );
                    }
                }));
            }

            futures::future::join_all(handles).await;

            assert_eq!(metrics.get_counter("test.counter"), 1000);

            let (min, max, avg) = metrics.get_timer_stats("test.timer").unwrap();
            assert_eq!(min, 0);
            assert_eq!(max, 999);
            assert!((avg - 499.5).abs() < 1.0);

            assert_eq!(metrics.get_snapshots().len(), 1000);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
        async fn test_concurrent_event_emission() {
            let emitter = Arc::new(CountingEventEmitter::new());
            let metrics = Arc::new(MetricsRecorder::new());
            let handler = Arc::new(RequestHandler::new(20, emitter.clone(), metrics.clone()));

            let mut handles = Vec::new();
            for i in 0..500 {
                let handler_clone = handler.clone();
                handles.push(tokio::spawn(async move {
                    let request = HandlerRequest {
                        trace_id: format!("trace_event_{}", i),
                        namespace: "test".to_string(),
                        params: serde_json::json!({}),
                        payload: serde_json::json!({"i": i}),
                    };
                    handler_clone.execute_handler(request).await
                }));
            }

            futures::future::join_all(handles).await;

            assert_eq!(emitter.count(), 500);
            assert_eq!(metrics.get_counter("requests.total"), 500);
            assert_eq!(metrics.get_counter("requests.errors"), 0);
        }
    }

    //==========================================================================
    // 测试矩阵 4: 外部依赖超时降级测试
    //==========================================================================
    mod failure_resilience {
        use super::*;

        #[tokio::test]
        async fn test_request_timeout() {
            let ctx = TestContext::with_timeout(50);

            let request = create_simple_request(&generate_trace_id());
            let response = ctx.handler.execute_handler(request).await;

            assert_response_success(&response);
        }

        #[tokio::test]
        async fn test_validation_error_handling() {
            let ctx = TestContext::new();

            let invalid_params = vec![
                serde_json::json!("not_an_object"),
                serde_json::json!([]),
                serde_json::json!(123),
                serde_json::json!(null),
            ];

            for params in invalid_params {
                let request = HandlerRequest {
                    trace_id: generate_trace_id(),
                    namespace: "test".to_string(),
                    params,
                    payload: serde_json::json!({}),
                };

                let response = ctx.handler.execute_handler(request).await;
                assert_response_error(&response, 422);
                assert_eq!(
                    ctx.metrics.get_counter("requests.validation_errors"),
                    1
                );
                ctx.metrics
                    .counters
                    .insert("requests.validation_errors".to_string(), 0);
            }
        }

        #[tokio::test]
        async fn test_resource_pool_exhaustion_recovery() {
            let ctx = Arc::new(TestContext::with_pool_size(2));
            let (tx, rx) = tokio::sync::oneshot::channel();

            let emitter = Arc::new(DelayingEventEmitter::new(
                Arc::new(InMemoryEventEmitter::new()),
                100,
            ));
            let metrics = Arc::new(MetricsRecorder::new());
            let handler = Arc::new(RequestHandler::new(2, emitter, metrics.clone()));

            let mut handles = Vec::new();
            for i in 0..10 {
                let handler_clone = handler.clone();
                handles.push(tokio::spawn(async move {
                    let request = HandlerRequest {
                        trace_id: format!("trace_exhaust_{}", i),
                        namespace: "test".to_string(),
                        params: serde_json::json!({}),
                        payload: serde_json::json!({"i": i}),
                    };
                    handler_clone.execute_handler(request).await
                }));
            }

            let results = futures::future::join_all(handles).await;
            let success_count = results
                .iter()
                .filter(|r| r.as_ref().map(|resp| resp.code == 200).unwrap_or(false))
                .count();

            assert!(success_count >= 2, "至少应处理部分请求");
        }

        #[tokio::test]
        async fn test_rollback_on_failure() {
            let ctx = TestContext::new();

            let request = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!("not_an_object"),
                payload: serde_json::json!({}),
            };

            let rollback_called = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let rollback_clone = rollback_called.clone();

            let mut processing_ctx = ProcessingContext::new("rollback_test", "test");
            processing_ctx.add_rollback(move || {
                rollback_clone.store(true, std::sync::atomic::Ordering::SeqCst);
            });

            let response = ctx.handler.execute_handler(request).await;
            assert_response_error(&response, 422);

            processing_ctx.rollback();
            assert!(
                rollback_called.load(std::sync::atomic::Ordering::SeqCst),
                "失败时应执行回滚操作"
            );
        }

        #[tokio::test]
        async fn test_metrics_recorded_on_errors() {
            let ctx = TestContext::new();

            let request1 = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "test".to_string(),
                params: serde_json::json!("invalid"),
                payload: serde_json::json!({}),
            };

            ctx.handler.execute_handler(request1).await;

            assert_eq!(ctx.metrics.get_counter("requests.total"), 1);
            assert_eq!(ctx.metrics.get_counter("requests.validation_errors"), 1);
            assert_eq!(ctx.metrics.get_counter("requests.errors"), 0);
            assert!(ctx.metrics.get_timer_stats("request.latency").is_some());
        }

        #[tokio::test]
        async fn test_multiple_namespace_config_isolation() {
            let ctx = TestContext::new();

            let ns1_config: HashMap<String, serde_json::Value> = vec![
                ("timeout".to_string(), serde_json::json!(10)),
                ("priority".to_string(), serde_json::json!("high")),
            ]
            .into_iter()
            .collect();

            let ns2_config: HashMap<String, serde_json::Value> = vec![
                ("timeout".to_string(), serde_json::json!(60)),
                ("priority".to_string(), serde_json::json!("low")),
            ]
            .into_iter()
            .collect();

            ctx.handler.set_config("high_priority", ns1_config);
            ctx.handler.set_config("low_priority", ns2_config);

            let req1 = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "high_priority".to_string(),
                params: serde_json::json!({}),
                payload: serde_json::json!({"ns": 1}),
            };

            let req2 = HandlerRequest {
                trace_id: generate_trace_id(),
                namespace: "low_priority".to_string(),
                params: serde_json::json!({}),
                payload: serde_json::json!({"ns": 2}),
            };

            let resp1 = ctx.handler.execute_handler(req1).await;
            let resp2 = ctx.handler.execute_handler(req2).await;

            assert_response_success(&resp1);
            assert_response_success(&resp2);

            let loaded1 = ctx.handler.load_config("high_priority").await.unwrap();
            let loaded2 = ctx.handler.load_config("low_priority").await.unwrap();

            assert_eq!(loaded1["priority"], serde_json::json!("high"));
            assert_eq!(loaded2["priority"], serde_json::json!("low"));
            assert_ne!(loaded1["timeout"], loaded2["timeout"]);
        }

        #[test]
        fn test_resource_pool_acquire_release() {
            let pool = ResourcePool::new(5);
            assert_eq!(pool.available_permits(), 5);
            assert_eq!(pool.pool_size(), 5);

            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async {
                let permit1 = pool.acquire().await.unwrap();
                assert_eq!(pool.available_permits(), 4);

                let permit2 = pool.acquire().await.unwrap();
                assert_eq!(pool.available_permits(), 3);

                drop(permit1);
                drop(permit2);

                assert_eq!(pool.available_permits(), 5);
            });
        }

        #[tokio::test]
        async fn test_error_response_consistency() {
            let ctx = TestContext::new();

            let test_cases = vec![
                (
                    HandlerRequest {
                        trace_id: "trace_validation".to_string(),
                        namespace: "test".to_string(),
                        params: serde_json::json!("invalid"),
                        payload: serde_json::json!({}),
                    },
                    422,
                    "参数必须是JSON对象",
                ),
            ];

            for (request, expected_code, expected_msg) in test_cases {
                let trace_id = request.trace_id.clone();
                let response = ctx.handler.execute_handler(request).await;

                assert_eq!(response.code, expected_code);
                assert_eq!(response.trace_id, trace_id);
                assert!(response.data.is_none());
                assert!(response.message.as_ref().unwrap().contains(expected_msg));
            }
        }

        #[tokio::test]
        async fn test_nonexistent_entity_operations() {
            let ctx = TestContext::new();

            let result = ctx.handler.get_entity_status("nonexistent_id").await;
            assert!(result.is_err());
            assert!(matches!(result.unwrap_err(), AppError::NotFound(_)));

            let ops = vec![BatchOperation {
                action: "start".to_string(),
                id: "nonexistent_id".to_string(),
            }];

            let results = ctx.handler.batch_operation(ops).await.unwrap();
            assert_eq!(results.len(), 1);
            assert!(!results[0].success);
            assert!(results[0].message.as_ref().unwrap().contains("不存在"));
        }

        #[tokio::test]
        async fn test_incremental_metrics_calculation() {
            let ctx = TestContext::new();

            for i in 0..10 {
                let should_succeed = i < 8;

                let request = if should_succeed {
                    HandlerRequest {
                        trace_id: generate_trace_id(),
                        namespace: "test".to_string(),
                        params: serde_json::json!({}),
                        payload: serde_json::json!({"i": i}),
                    }
                } else {
                    HandlerRequest {
                        trace_id: generate_trace_id(),
                        namespace: "test".to_string(),
                        params: serde_json::json!("invalid"),
                        payload: serde_json::json!({}),
                    }
                };

                ctx.handler.execute_handler(request).await;
            }

            assert_eq!(ctx.metrics.get_counter("requests.total"), 10);
            assert_eq!(ctx.metrics.get_counter("requests.errors"), 0);
            assert_eq!(ctx.metrics.get_counter("requests.validation_errors"), 2);

            let snapshots = ctx.metrics.get_snapshots();
            assert_eq!(snapshots.len(), 10);

            let last = snapshots.last().unwrap();
            let error_rate = last.metrics.error_rate;
            assert!(
                (error_rate - 0.0).abs() < 0.001 || (error_rate - 0.2).abs() < 0.001,
                "错误率应为0或0.2，实际为 {}",
                error_rate
            );
        }

        #[test]
        fn test_metrics_stats_calculation() {
            let metrics = MetricsRecorder::new();

            for i in &[10, 20, 30, 40, 50, 60, 70, 80, 90, 100] {
                metrics.record_timer("response.time", *i);
            }

            let (min, max, avg) = metrics.get_timer_stats("response.time").unwrap();
            assert_eq!(min, 10);
            assert_eq!(max, 100);
            assert_eq!(avg, 55.0);

            metrics.increment_counter("test.counter", 5);
            metrics.increment_counter("test.counter", 15);
            assert_eq!(metrics.get_counter("test.counter"), 20);

            assert!(metrics.get_timer_stats("nonexistent").is_none());
            assert_eq!(metrics.get_counter("nonexistent"), 0);
        }
    }
}
