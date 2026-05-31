pub mod rules;
pub mod engine;
pub mod scheduler;
pub mod marker;
pub mod monitoring;

pub use rules::*;
pub use engine::*;
pub use scheduler::*;
pub use marker::*;
pub use monitoring::*;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_builder::TestDataBuilder;
    use std::sync::Arc;
    use std::time::Duration as StdDuration;

    #[tokio::test]
    async fn test_null_check_evaluator_passes() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_valid_data_batch(100);

        let rule = builder.create_not_null_rule("id", Severity::High);
        let evaluator = get_evaluator(RuleType::NullCheck);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(result.passed);
        assert_eq!(result.invalid_count, 0);
        assert_eq!(result.row_count, 100);
    }

    #[tokio::test]
    async fn test_null_check_evaluator_fails() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_data_with_nulls(100, 25, "email");

        let rule = builder.create_not_null_rule("email", Severity::High);
        let evaluator = get_evaluator(RuleType::NullCheck);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(!result.passed);
        assert_eq!(result.invalid_count, 25);
        assert_eq!(result.row_count, 100);
        assert_eq!(result.severity, Severity::High);
    }

    #[tokio::test]
    async fn test_range_check_evaluator_passes() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_valid_data_batch(100);

        let rule = builder.create_range_rule("age", 0.0, 150.0, Severity::Medium);
        let evaluator = get_evaluator(RuleType::RangeCheck);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(result.passed);
        assert_eq!(result.invalid_count, 0);
    }

    #[tokio::test]
    async fn test_range_check_evaluator_fails() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_data_with_out_of_range(100, 15, "score", 200.0);

        let rule = builder.create_range_rule("score", 0.0, 100.0, Severity::Medium);
        let evaluator = get_evaluator(RuleType::RangeCheck);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(!result.passed);
        assert_eq!(result.invalid_count, 15);
    }

    #[tokio::test]
    async fn test_regex_match_evaluator_passes() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_valid_data_batch(50);

        let rule = builder.create_regex_rule(
            "email",
            r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$",
            Severity::Medium,
        );
        let evaluator = get_evaluator(RuleType::RegexMatch);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(result.passed);
    }

    #[tokio::test]
    async fn test_regex_match_evaluator_fails() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_data_with_invalid_format(100, 20, "email");

        let rule = builder.create_regex_rule(
            "email",
            r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$",
            Severity::Medium,
        );
        let evaluator = get_evaluator(RuleType::RegexMatch);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(!result.passed);
        assert_eq!(result.invalid_count, 20);
    }

    #[tokio::test]
    async fn test_uniqueness_evaluator_passes() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_valid_data_batch(50);

        let rule = builder.create_unique_rule("id", Severity::High);
        let evaluator = get_evaluator(RuleType::Uniqueness);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(result.passed);
    }

    #[tokio::test]
    async fn test_uniqueness_evaluator_fails() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_data_with_duplicates(100, 10);

        let rule = builder.create_unique_rule("id", Severity::High);
        let evaluator = get_evaluator(RuleType::Uniqueness);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(!result.passed);
        assert!(result.invalid_count > 0);
    }

    #[tokio::test]
    async fn test_quality_engine_validate_table() {
        let builder = TestDataBuilder::quality().with_table("users");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        for rule in &rules {
            engine.add_rule(rule.clone()).await;
        }

        let valid_data = builder.create_valid_data_batch(50);
        let report = engine.validate_table("users", &valid_data).await.unwrap();

        assert_eq!(report.total_rules, 4);
        assert_eq!(report.passed_rules, 4);
        assert_eq!(report.failed_rules, 0);
        assert!(report.duration_ms >= 0);
    }

    #[tokio::test]
    async fn test_quality_engine_mixed_results() {
        let builder = TestDataBuilder::quality().with_table("users");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        for rule in &rules {
            engine.add_rule(rule.clone()).await;
        }

        let mut data = builder.create_valid_data_batch(100);
        for i in 0..10 {
            if let Some(item) = data.get_mut(i) {
                if let Some(obj) = item.as_object_mut() {
                    obj.insert("age".to_string(), serde_json::json!(200));
                }
            }
        }

        let report = engine.validate_table("users", &data).await.unwrap();

        assert_eq!(report.total_rules, 4);
        assert_eq!(report.passed_rules, 3);
        assert_eq!(report.failed_rules, 1);
    }

    #[tokio::test]
    async fn test_quality_score_calculation() {
        let builder = TestDataBuilder::quality().with_table("metrics");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        for rule in &rules {
            engine.add_rule(rule.clone()).await;
        }

        let valid_data = builder.create_valid_data_batch(50);
        engine.validate_table("metrics", &valid_data).await.unwrap();

        let score = engine.get_overall_quality_score("metrics").await;
        assert_eq!(score, 100.0);

        let bad_data = builder.create_data_with_nulls(50, 10, "id");
        engine.validate_table("metrics", &bad_data).await.unwrap();

        let score_after = engine.get_overall_quality_score("metrics").await;
        assert!(score_after < 100.0);
    }

    #[tokio::test]
    async fn test_rule_set_execution() {
        let builder = TestDataBuilder::quality().with_table("orders");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        let rule_set = builder.create_basic_rule_set(rules.clone());

        engine.add_rule_set(rule_set.clone()).await;

        let data = builder.create_valid_data_batch(25);
        let report = engine.run_rule_set(&rule_set.id, &data).await.unwrap();

        assert_eq!(report.table_name, "orders");
        assert_eq!(report.total_rules, 4);
    }

    #[tokio::test]
    async fn test_concurrent_validation() {
        let builder = TestDataBuilder::quality().with_table("concurrent_test");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        for rule in &rules {
            engine.add_rule(rule.clone()).await;
        }

        let mut handles = Vec::new();
        for i in 0..10 {
            let engine_clone = engine.clone();
            let data = builder.create_valid_data_batch(100);
            handles.push(tokio::spawn(async move {
                engine_clone.validate_table("concurrent_test", &data).await
            }));
        }

        for handle in handles {
            let result = handle.await.unwrap();
            assert!(result.is_ok());
            let report = result.unwrap();
            assert_eq!(report.passed_rules, 4);
        }

        let reports = engine.get_reports(Some("concurrent_test")).await;
        assert_eq!(reports.len(), 10);
    }

    #[tokio::test]
    async fn test_validation_with_timeout_simulation() {
        let builder = TestDataBuilder::quality().with_table("timeout_test");
        let engine = Arc::new(QualityEngine::new());

        let fast_rule = builder.create_not_null_rule("id", Severity::High);
        engine.add_rule(fast_rule).await;

        let data = builder.create_valid_data_batch(50);
        
        let start = std::time::Instant::now();
        let report = engine.validate_table("timeout_test", &data).await.unwrap();
        let elapsed = start.elapsed();

        assert!(report.passed_rules > 0);
        assert!(elapsed < StdDuration::from_secs(5));
    }

    #[tokio::test]
    async fn test_scheduler_task_management() {
        let builder = TestDataBuilder::quality().with_table("scheduled");
        let engine = Arc::new(QualityEngine::new());
        let scheduler = QualityScheduler::new(engine.clone());

        let rules = builder.create_mixed_severity_rules();
        let rule_set = builder.create_scheduled_rule_set(rules, "0 */5 * * * *");

        engine.add_rule_set(rule_set.clone()).await;

        let task = scheduler.schedule_rule_set(&rule_set, 5000).await;

        assert_eq!(task.rule_set_id, rule_set.id);
        assert!(task.enabled);
        assert!(task.interval_ms > 0);

        let tasks = scheduler.list_tasks().await;
        assert_eq!(tasks.len(), 1);

        scheduler.disable_task(&task.id).await.unwrap();
        let disabled_task = scheduler.get_task(&task.id).await.unwrap();
        assert!(!disabled_task.enabled);

        scheduler.enable_task(&task.id).await.unwrap();
        let enabled_task = scheduler.get_task(&task.id).await.unwrap();
        assert!(enabled_task.enabled);
    }

    #[tokio::test]
    async fn test_severity_ordering() {
        let builder = TestDataBuilder::quality();
        let rules = builder.create_mixed_severity_rules();

        let severities: Vec<Severity> = rules.iter().map(|r| r.severity).collect();

        assert!(severities.contains(&Severity::Critical));
        assert!(severities.contains(&Severity::High));
        assert!(severities.contains(&Severity::Medium));
        assert!(severities.contains(&Severity::Low));
    }

    #[tokio::test]
    async fn test_rule_enabling_disabling() {
        let builder = TestDataBuilder::quality().with_table("enable_test");
        let engine = Arc::new(QualityEngine::new());

        let mut rule1 = builder.create_not_null_rule("id", Severity::Critical);
        let mut rule2 = builder.create_range_rule("age", 0.0, 150.0, Severity::High);
        rule2.enabled = false;

        engine.add_rule(rule1).await;
        engine.add_rule(rule2).await;

        let data = builder.create_valid_data_batch(10);
        let report = engine.validate_table("enable_test", &data).await.unwrap();

        assert_eq!(report.total_rules, 1);
        assert_eq!(report.passed_rules, 1);
    }

    #[tokio::test]
    async fn test_dashboard_metrics() {
        let builder = TestDataBuilder::quality().with_table("dashboard");
        let engine = Arc::new(QualityEngine::new());

        let rules = builder.create_mixed_severity_rules();
        for rule in &rules {
            engine.add_rule(rule.clone()).await;
        }

        let data = builder.create_valid_data_batch(10);
        engine.validate_table("dashboard", &data).await.unwrap();

        let dashboard = engine.get_dashboard().await;

        assert_eq!(dashboard.total_tables, 1);
        assert_eq!(dashboard.total_rules, 4);
        assert_eq!(dashboard.total_reports, 1);
        assert_eq!(dashboard.overall_score, 100.0);
        assert!(dashboard.failing_tables.is_empty());
    }

    #[tokio::test]
    async fn test_invalid_rows_capture() {
        let builder = TestDataBuilder::quality();
        let data = builder.create_data_with_nulls(100, 5, "email");

        let rule = builder.create_not_null_rule("email", Severity::High);
        let evaluator = get_evaluator(RuleType::NullCheck);
        let result = evaluator.evaluate(&rule, &data).unwrap();

        assert!(!result.passed);
        assert_eq!(result.invalid_count, 5);
        assert_eq!(result.invalid_rows.len(), 5);
    }

    #[tokio::test]
    async fn test_large_batch_validation() {
        let builder = TestDataBuilder::quality().with_table("large_batch");
        let engine = Arc::new(QualityEngine::new());

        let rule = builder.create_not_null_rule("id", Severity::High);
        engine.add_rule(rule).await;

        let data = builder.create_valid_data_batch(10000);
        
        let start = std::time::Instant::now();
        let report = engine.validate_table("large_batch", &data).await.unwrap();
        let elapsed = start.elapsed();

        assert_eq!(report.passed_rules, 1);
        assert!(elapsed < StdDuration::from_secs(10));
    }

    #[tokio::test]
    async fn test_validate_with_rules_direct() {
        let builder = TestDataBuilder::quality();
        let engine = Arc::new(QualityEngine::new());

        let rules = vec![
            builder.create_not_null_rule("id", Severity::High),
            builder.create_range_rule("age", 0.0, 150.0, Severity::Medium),
        ];

        let data = builder.create_valid_data_batch(50);
        let results = engine.validate_with_rules(&rules, &data).await.unwrap();

        assert_eq!(results.len(), 2);
        for result in results {
            assert!(result.passed);
        }
    }

    #[tokio::test]
    async fn test_latest_report_retrieval() {
        let builder = TestDataBuilder::quality().with_table("latest_test");
        let engine = Arc::new(QualityEngine::new());

        let rule = builder.create_not_null_rule("id", Severity::High);
        engine.add_rule(rule).await;

        let data1 = builder.create_valid_data_batch(10);
        engine.validate_table("latest_test", &data1).await.unwrap();

        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

        let data2 = builder.create_data_with_nulls(10, 2, "id");
        engine.validate_table("latest_test", &data2).await.unwrap();

        let latest = engine.get_latest_report("latest_test").await.unwrap();
        assert_eq!(latest.failed_rules, 1);
    }
}
