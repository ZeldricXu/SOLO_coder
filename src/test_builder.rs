use serde_json::json;
use chrono::{Utc, Duration};
use crate::cdc::*;
use crate::data_lineage::*;
use crate::data_quality::*;
use crate::models::IdGenerator;

pub struct TestDataBuilder;

impl TestDataBuilder {
    pub fn new() -> Self {
        Self
    }

    pub fn cdc() -> CdcTestBuilder {
        CdcTestBuilder::new()
    }

    pub fn lineage() -> LineageTestBuilder {
        LineageTestBuilder::new()
    }

    pub fn quality() -> QualityTestBuilder {
        QualityTestBuilder::new()
    }
}

pub struct CdcTestBuilder {
    database: String,
}

impl CdcTestBuilder {
    pub fn new() -> Self {
        Self {
            database: "test_db".to_string(),
        }
    }

    pub fn with_database(mut self, db: impl Into<String>) -> Self {
        self.database = db.into();
        self
    }

    pub fn create_insert_event(&self, table: &str, id: i64, data: serde_json::Value) -> ChangeEvent {
        let mut event = ChangeEvent::new(
            ChangeType::Insert,
            &self.database,
            table,
        );
        event = event.with_after(data);
        event.source.binlog_file = Some("binlog.000001".to_string());
        event.source.binlog_position = Some(id as u64 * 128);
        event.source.xid = Some(id as u64 + 1000);
        event.transaction_id = Some(format!("txn_{}", id));
        event
    }

    pub fn create_update_event(
        &self,
        table: &str,
        id: i64,
        before: serde_json::Value,
        after: serde_json::Value,
    ) -> ChangeEvent {
        let mut event = ChangeEvent::new(
            ChangeType::Update,
            &self.database,
            table,
        );
        event = event.with_before(before).with_after(after);
        event.source.binlog_file = Some("binlog.000001".to_string());
        event.source.binlog_position = Some(id as u64 * 128);
        event.source.xid = Some(id as u64 + 1000);
        event.transaction_id = Some(format!("txn_{}", id));
        event
    }

    pub fn create_delete_event(&self, table: &str, id: i64, before: serde_json::Value) -> ChangeEvent {
        let mut event = ChangeEvent::new(
            ChangeType::Delete,
            &self.database,
            table,
        );
        event = event.with_before(before);
        event.source.binlog_file = Some("binlog.000001".to_string());
        event.source.binlog_position = Some(id as u64 * 128);
        event.source.xid = Some(id as u64 + 1000);
        event.transaction_id = Some(format!("txn_{}", id));
        event
    }

    pub fn create_user_insert_event(&self, id: i64) -> ChangeEvent {
        self.create_insert_event(
            "users",
            id,
            json!({
                "id": id,
                "name": format!("user_{}", id),
                "email": format!("user{}@test.com", id),
                "age": 20 + (id % 50),
                "created_at": Utc::now().to_rfc3339(),
                "status": "active"
            }),
        )
    }

    pub fn create_order_insert_event(&self, id: i64, user_id: i64) -> ChangeEvent {
        self.create_insert_event(
            "orders",
            id,
            json!({
                "id": id,
                "user_id": user_id,
                "amount": (id as f64) * 100.50,
                "currency": "CNY",
                "status": "pending",
                "created_at": Utc::now().to_rfc3339()
            }),
        )
    }

    pub fn create_transaction_events(&self, txn_id: &str, count: usize) -> Vec<ChangeEvent> {
        let mut events = Vec::with_capacity(count);
        for i in 0..count {
            let mut event = self.create_user_insert_event(i as i64);
            event.transaction_id = Some(txn_id.to_string());
            events.push(event);
        }
        events
    }

    pub fn create_consistency_test_events(&self) -> Vec<ChangeEvent> {
        let mut events = Vec::new();
        
        for i in 1..=100 {
            events.push(self.create_user_insert_event(i));
        }
        
        for i in 51..=60 {
            let before = json!({
                "id": i,
                "name": format!("user_{}", i),
                "email": format!("user{}@test.com", i),
                "age": 20 + (i % 50),
                "status": "active"
            });
            let after = json!({
                "id": i,
                "name": format!("user_{}_updated", i),
                "email": format!("user{}@updated.com", i),
                "age": 20 + (i % 50),
                "status": "active"
            });
            events.push(self.create_update_event("users", i + 1000, before, after));
        }
        
        for i in 91..=100 {
            let before = json!({
                "id": i,
                "name": format!("user_{}", i),
                "email": format!("user{}@test.com", i),
                "age": 20 + (i % 50),
                "status": "active"
            });
            events.push(self.create_delete_event("users", i + 2000, before));
        }
        
        events
    }

    pub fn create_mock_parser(&self, tables: Vec<String>) -> MockBinlogParser {
        let config = ParserConfig {
            source_type: SourceType::Mysql,
            connection_string: "mysql://root:password@localhost:3306".to_string(),
            tables,
            start_position: None,
            server_id: Some(1),
        };
        MockBinlogParser::new(config)
    }

    pub fn create_table_schema(&self, table: &str) -> TableSchema {
        TableSchema {
            database: self.database.clone(),
            table: table.to_string(),
            columns: vec![
                ColumnInfo {
                    name: "id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    position: 0,
                },
                ColumnInfo {
                    name: "name".to_string(),
                    data_type: "varchar".to_string(),
                    nullable: true,
                    position: 1,
                },
                ColumnInfo {
                    name: "created_at".to_string(),
                    data_type: "timestamp".to_string(),
                    nullable: true,
                    position: 2,
                },
            ],
            primary_keys: vec!["id".to_string()],
        }
    }
}

pub struct LineageTestBuilder {
    database: String,
}

impl LineageTestBuilder {
    pub fn new() -> Self {
        Self {
            database: "analytics".to_string(),
        }
    }

    pub fn with_database(mut self, db: impl Into<String>) -> Self {
        self.database = db.into();
        self
    }

    pub fn create_simple_insert_sql(&self, target: &str, source: &str) -> String {
        format!(
            "INSERT INTO {}.{} SELECT * FROM {}.{}",
            self.database, target, self.database, source
        )
    }

    pub fn create_simple_sql(&self, target: &str, source: &str) -> String {
        format!(
            "INSERT INTO {} SELECT * FROM {}",
            target, source
        )
    }

    pub fn create_select_with_columns_sql(
        &self,
        target: &str,
        source: &str,
        columns: &[&str],
    ) -> String {
        let cols = columns.join(", ");
        format!(
            "INSERT INTO {} ({}) SELECT {} FROM {}",
            target, cols, cols, source
        )
    }

    pub fn create_join_sql(
        &self,
        target: &str,
        table_a: &str,
        table_b: &str,
        join_condition: &str,
    ) -> String {
        format!(
            "INSERT INTO {} SELECT a.*, b.* FROM {} a JOIN {} b ON {}",
            target, table_a, table_b, join_condition
        )
    }

    pub fn create_chain_sqls(&self, tables: &[&str]) -> Vec<String> {
        let mut sqls = Vec::new();
        for i in 0..tables.len() - 1 {
            sqls.push(self.create_simple_sql(tables[i + 1], tables[i]));
        }
        sqls
    }

    pub fn create_complex_pipeline_sqls(&self) -> Vec<String> {
        vec![
            "INSERT INTO staging_users SELECT id, name, email FROM raw_users".to_string(),
            "INSERT INTO staging_orders SELECT * FROM raw_orders".to_string(),
            "INSERT INTO fact_orders SELECT o.id, o.user_id, o.amount, u.name AS user_name \
             FROM staging_orders o JOIN staging_users u ON o.user_id = u.id"
                .to_string(),
            "INSERT INTO daily_summary SELECT user_id, COUNT(*) as order_count, SUM(amount) as total_amount \
             FROM fact_orders GROUP BY user_id"
                .to_string(),
        ]
    }

    pub fn create_cyclic_sqls(&self) -> Vec<String> {
        vec![
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_c SELECT * FROM table_b".to_string(),
            "INSERT INTO table_a SELECT * FROM table_c".to_string(),
        ]
    }

    pub fn create_concurrent_test_sqls(&self, prefix: &str, count: usize) -> Vec<String> {
        let mut sqls = Vec::new();
        for i in 1..=count {
            sqls.push(format!(
                "INSERT INTO {}_{} SELECT * FROM {}_source",
                prefix, i, prefix
            ));
        }
        sqls
    }

    pub fn create_table_reference(&self, table: &str) -> TableReference {
        TableReference::new(&self.database, table)
    }

    pub fn create_column_reference(&self, table: &str, column: &str) -> ColumnReference {
        ColumnReference::new(self.create_table_reference(table), column)
    }

    pub fn create_linear_dag_graph(&self) -> LineageGraph {
        let extractor = LineageExtractor::new();
        let sqls = self.create_chain_sqls(&["table_a", "table_b", "table_c", "table_d"]);
        extractor.build_graph(&sqls).unwrap()
    }

    pub fn create_fan_out_graph(&self) -> LineageGraph {
        let extractor = LineageExtractor::new();
        let sqls = vec![
            self.create_simple_sql("table_b", "table_a"),
            self.create_simple_sql("table_c", "table_a"),
            self.create_simple_sql("table_d", "table_a"),
        ];
        extractor.build_graph(&sqls).unwrap()
    }
}

pub struct QualityTestBuilder {
    table_name: String,
}

impl QualityTestBuilder {
    pub fn new() -> Self {
        Self {
            table_name: "test_table".to_string(),
        }
    }

    pub fn with_table(mut self, table: impl Into<String>) -> Self {
        self.table_name = table.into();
        self
    }

    pub fn create_not_null_rule(&self, column: &str, severity: Severity) -> QualityRule {
        QualityRule::new(
            format!("not_null_{}", column),
            RuleType::NullCheck,
            format!("{} IS NOT NULL", column),
            &self.table_name,
        )
        .with_description(format!("{} 字段不能为空", column))
        .with_column(column)
        .with_severity(severity)
    }

    pub fn create_range_rule(
        &self,
        column: &str,
        min: f64,
        max: f64,
        severity: Severity,
    ) -> QualityRule {
        QualityRule::new(
            format!("range_{}_{}_{}", column, min, max),
            RuleType::RangeCheck,
            format!("{} BETWEEN {} AND {}", column, min, max),
            &self.table_name,
        )
        .with_description(format!("{} 字段值应在 [{}, {}] 范围内", column, min, max))
        .with_column(column)
        .with_severity(severity)
        .with_parameter("min", json!(min))
        .with_parameter("max", json!(max))
    }

    pub fn create_regex_rule(&self, column: &str, pattern: &str, severity: Severity) -> QualityRule {
        QualityRule::new(
            format!("regex_{}", column),
            RuleType::RegexMatch,
            format!("{} ~ '{}'", column, pattern),
            &self.table_name,
        )
        .with_description(format!("{} 字段应匹配正则表达式", column))
        .with_column(column)
        .with_severity(severity)
        .with_parameter("pattern", json!(pattern))
    }

    pub fn create_unique_rule(&self, column: &str, severity: Severity) -> QualityRule {
        QualityRule::new(
            format!("unique_{}", column),
            RuleType::Uniqueness,
            format!("DISTINCT({})", column),
            &self.table_name,
        )
        .with_description(format!("字段 {} 应唯一", column))
        .with_column(column)
        .with_severity(severity)
    }

    pub fn create_slow_rule(&self, column: &str, delay_ms: u64, severity: Severity) -> QualityRule {
        QualityRule::new(
            format!("slow_rule_{}", column),
            RuleType::Custom,
            "value > 0".to_string(),
            &self.table_name,
        )
        .with_description("模拟慢规则，用于超时测试".to_string())
        .with_column(column)
        .with_severity(severity)
        .with_parameter("delay_ms", json!(delay_ms))
    }

    pub fn create_valid_data_batch(&self, count: usize) -> Vec<serde_json::Value> {
        let mut data = Vec::with_capacity(count);
        for i in 0..count {
            data.push(json!({
                "id": i,
                "name": format!("valid_user_{}", i),
                "email": format!("valid{}@test.com", i),
                "age": 20 + (i % 50),
                "score": 50 + (i % 50) as f64
            }));
        }
        data
    }

    pub fn create_data_with_nulls(
        &self,
        total_count: usize,
        null_count: usize,
        null_column: &str,
    ) -> Vec<serde_json::Value> {
        let mut data = self.create_valid_data_batch(total_count);
        for i in 0..null_count {
            if let Some(item) = data.get_mut(i) {
                if let Some(obj) = item.as_object_mut() {
                    obj.insert(null_column.to_string(), serde_json::Value::Null);
                }
            }
        }
        data
    }

    pub fn create_data_with_out_of_range(
        &self,
        total_count: usize,
        bad_count: usize,
        column: &str,
        bad_value: f64,
    ) -> Vec<serde_json::Value> {
        let mut data = self.create_valid_data_batch(total_count);
        for i in 0..bad_count {
            if let Some(item) = data.get_mut(i) {
                if let Some(obj) = item.as_object_mut() {
                    obj.insert(column.to_string(), json!(bad_value));
                }
            }
        }
        data
    }

    pub fn create_data_with_invalid_format(
        &self,
        total_count: usize,
        bad_count: usize,
        column: &str,
    ) -> Vec<serde_json::Value> {
        let mut data = self.create_valid_data_batch(total_count);
        for i in 0..bad_count {
            if let Some(item) = data.get_mut(i) {
                if let Some(obj) = item.as_object_mut() {
                    obj.insert(column.to_string(), json!("invalid-email"));
                }
            }
        }
        data
    }

    pub fn create_data_with_duplicates(
        &self,
        total_count: usize,
        duplicate_count: usize,
    ) -> Vec<serde_json::Value> {
        let mut data = self.create_valid_data_batch(total_count - duplicate_count);
        for i in 0..duplicate_count {
            let duplicate = json!({
                "id": i,
                "name": format!("duplicate_user_{}", i),
                "email": format!("duplicate{}@test.com", i),
                "age": 25,
                "score": 75.0
            });
            data.push(duplicate.clone());
            data.push(duplicate);
        }
        data
    }

    pub fn create_basic_rule_set(&self, rules: Vec<QualityRule>) -> RuleSet {
        RuleSet {
            id: IdGenerator::generate("ruleset"),
            name: format!("{}_quality_rules", self.table_name),
            table_name: self.table_name.clone(),
            rules,
            schedule: None,
            enabled: true,
        }
    }

    pub fn create_scheduled_rule_set(
        &self,
        rules: Vec<QualityRule>,
        cron_expression: &str,
    ) -> RuleSet {
        let mut rule_set = self.create_basic_rule_set(rules);
        rule_set.schedule = Some(ScheduleConfig {
            cron_expression: cron_expression.to_string(),
            timezone: "UTC".to_string(),
            max_retries: 3,
            retry_delay_ms: 5000,
        });
        rule_set
    }

    pub fn create_timeout_test_rules(&self) -> Vec<QualityRule> {
        vec![
            self.create_slow_rule("score", 500, Severity::High),
            self.create_slow_rule("age", 1000, Severity::Medium),
            self.create_slow_rule("id", 2000, Severity::Low),
        ]
    }

    pub fn create_mixed_severity_rules(&self) -> Vec<QualityRule> {
        vec![
            self.create_not_null_rule("id", Severity::Critical),
            self.create_range_rule("age", 0.0, 150.0, Severity::High),
            self.create_regex_rule("email", r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$", Severity::Medium),
            self.create_range_rule("score", 0.0, 100.0, Severity::Low),
        ]
    }
}
