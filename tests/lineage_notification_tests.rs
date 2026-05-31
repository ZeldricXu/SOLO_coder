#[cfg(test)]
mod lineage_tests {
    use enterprise_middleware::lineage::{LineageConfig, LineageDagBuilder, SqlLineageParser, ParsedSqlLineage};
    use enterprise_middleware::types::{LineageNode, LineageNodeType};
    use std::collections::HashSet;

    #[test]
    fn test_simple_select_parsing() {
        let config = LineageConfig {
            sql_dialect: "postgres".to_string(),
            store_parsed_queries: true,
            build_dag: true,
        };
        let parser = SqlLineageParser::new(config);

        let sql = "SELECT id, name, email FROM users WHERE status = 'active'";
        let lineage = parser.parse_sql(sql).unwrap();

        assert!(lineage.source_tables.contains("users"));
        assert!(lineage.source_columns.contains("users.id"));
        assert!(lineage.source_columns.contains("users.name"));
        assert!(lineage.source_columns.contains("users.email"));
        assert!(lineage.target_tables.is_empty());
    }

    #[test]
    fn test_insert_statement_parsing() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sql = "INSERT INTO active_users (id, name) SELECT id, name FROM users WHERE active = true";
        let lineage = parser.parse_sql(sql).unwrap();

        assert!(lineage.source_tables.contains("users"));
        assert!(lineage.target_tables.contains("active_users"));
    }

    #[test]
    fn test_update_statement_parsing() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sql = "UPDATE orders SET status = 'processed' WHERE id IN (SELECT order_id FROM pending_orders)";
        let lineage = parser.parse_sql(sql).unwrap();

        assert!(lineage.source_tables.contains("pending_orders"));
        assert!(lineage.target_tables.contains("orders"));
    }

    #[test]
    fn test_create_table_as_select() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sql = "CREATE TABLE user_summary AS SELECT u.id, u.name, COUNT(o.id) as order_count FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name";
        let lineage = parser.parse_sql(sql).unwrap();

        assert!(lineage.source_tables.contains("users"));
        assert!(lineage.source_tables.contains("orders"));
        assert!(lineage.target_tables.contains("user_summary"));
    }

    #[test]
    fn test_complex_join_parsing() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sql = r#"
            SELECT 
                c.name as category,
                SUM(oi.quantity * p.price as total_revenue
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            JOIN products p ON oi.product_id = p.id
            JOIN categories c ON p.category_id = c.id
            WHERE o.created_at >= '2026-01-01'
            GROUP BY c.name
        "#;

        let lineage = parser.parse_sql(sql).unwrap();

        assert!(lineage.source_tables.contains("order_items"));
        assert!(lineage.source_tables.contains("orders"));
        assert!(lineage.source_tables.contains("products"));
        assert!(lineage.source_tables.contains("categories"));
    }

    #[test]
    fn test_lineage_config_validation() {
        let valid_config = LineageConfig {
            sql_dialect: "postgres".to_string(),
            store_parsed_queries: true,
            build_dag: true,
        };
        assert!(valid_config.validate().is_ok());

        let invalid_config = LineageConfig {
            sql_dialect: "unknown_db".to_string(),
            store_parsed_queries: true,
            build_dag: true,
        };
        assert!(invalid_config.validate().is_err());
    }

    #[test]
    fn test_dag_building() {
        let builder = LineageDagBuilder::new();

        let nodes = vec![
            LineageNode {
                node_id: "node_1".to_string(),
                node_type: LineageNodeType::Table,
                name: "source_table".to_string(),
                display_name: "源表".to_string(),
                metadata: Default::default(),
            },
            LineageNode {
                node_id: "node_2".to_string(),
                node_type: LineageNodeType::Table,
                name: "transform".to_string(),
                display_name: "转换".to_string(),
                metadata: Default::default(),
            },
            LineageNode {
                node_id: "node_3".to_string(),
                node_type: LineageNodeType::Table,
                name: "target_table".to_string(),
                display_name: "目标表".to_string(),
                metadata: Default::default(),
            },
        ];

        let edges = vec![
            ("node_1".to_string(), "node_2".to_string()),
            ("node_2".to_string(), "node_3".to_string()),
        ];

        let lineage = builder.build(nodes, edges, "test_graph").unwrap();

        assert_eq!(lineage.node_count(), 3);
        assert_eq!(lineage.edge_count(), 2);

        let upstream = lineage.get_upstream_nodes("node_3").unwrap();
        assert_eq!(upstream.len(), 2);

        let downstream = lineage.get_downstream_nodes("node_1").unwrap();
        assert_eq!(downstream.len(), 2);
    }

    #[test]
    fn test_dag_cycle_detection() {
        let builder = LineageDagBuilder::new();

        let nodes = vec![
            LineageNode {
                node_id: "a".to_string(),
                node_type: LineageNodeType::Table,
                name: "a".to_string(),
                display_name: "A".to_string(),
                metadata: Default::default(),
            },
            LineageNode {
                node_id: "b".to_string(),
                node_type: LineageNodeType::Table,
                name: "b".to_string(),
                display_name: "B".to_string(),
                metadata: Default::default(),
            },
        ];

        let edges_with_cycle = vec![
            ("a".to_string(), "b".to_string()),
            ("b".to_string(), "a".to_string()),
        ];

        let result = builder.build(nodes, edges_with_cycle, "cyclic");
        assert!(result.is_err());
    }

    #[test]
    fn test_column_level_lineage() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sql = "SELECT u.id, u.name, a.city, o.total FROM users u JOIN accounts a ON u.id = a.user_id LEFT JOIN orders_summary o ON u.id = o.user_id";

        let lineage = parser.parse_sql(sql).unwrap();

        let expected_columns = [
            "users.id",
            "users.name",
            "accounts.city",
            "orders_summary.total",
        ];

        for col in &expected_columns {
            assert!(
                lineage.source_columns.contains(*col),
                "应包含列: {}", col);
        }
    }

    #[test]
    fn test_parse_complex_sql_expressions() {
        let config = LineageConfig::default();
        let parser = SqlLineageParser::new(config);

        let sqls = vec![
            "WITH monthly_sales AS (SELECT product_id, SUM(amount) as total FROM sales GROUP BY product_id) SELECT * FROM monthly_sales WHERE total > 1000",
            "SELECT DISTINCT user_id FROM orders UNION SELECT user_id FROM returns",
            "DELETE FROM old_data WHERE created_at < (SELECT MIN(created_at) FROM new_data)",
            "MERGE INTO target t USING source s ON t.id = s.id WHEN MATCHED THEN UPDATE SET value = s.value WHEN NOT MATCHED THEN INSERT (id, value) VALUES (s.id, s.value)",
        ];

        for sql in sqls {
            let result = parser.parse_sql(sql);
            assert!(result.is_ok(), "解析失败: {}", sql);
        }
    }

    #[test]
    fn test_lineage_manager_operations() {
        use enterprise_middleware::lineage::LineageManager;

        let manager = LineageManager::new(LineageConfig::default());

        let sql1 = "SELECT id, name FROM users";
        let sql2 = "INSERT INTO user_stats SELECT COUNT(*) as count FROM users";

        let graph1_id = manager.parse_and_store(sql1, "batch_job_1").unwrap();
        let graph2_id = manager.parse_and_store(sql2, "batch_job_2").unwrap();

        assert!(manager.has_graph(&graph1_id));
        assert!(manager.has_graph(&graph2_id));

        let graph1 = manager.get_graph(&graph1_id).unwrap();
        assert_eq!(graph1.source, "batch_job_1");

        let all = manager.list_graphs();
        assert_eq!(all.len(), 2);

        let user_lineage = manager.find_lineage_for_table("users");
        assert!(user_lineage.len() >= 2);
    }

    #[test]
    fn test_lineage_node_serialization() {
        let node = LineageNode {
            node_id: "test_node".to_string(),
            node_type: LineageNodeType::Column,
            name: "users.email".to_string(),
            display_name: "用户邮箱".to_string(),
            metadata: Default::default(),
        };

        let json = serde_json::to_string(&node).unwrap();
        let deserialized: LineageNode = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.node_id, "test_node");
        assert_eq!(deserialized.name, "users.email");
        assert_eq!(deserialized.display_name, "用户邮箱");
    }
}

#[cfg(test)]
mod notification_tests {
    use enterprise_middleware::notification::{
        EmailChannel, NotificationChannel, NotificationConfig, NotificationManager, NotificationMessage,
        TemplateManager,
    };
    use std::collections::HashMap;
    use enterprise_middleware::types::NotificationSeverity;

    #[test]
    fn test_template_manager() {
        let manager = TemplateManager::new();

        let context = serde_json::json!({
            "user": { "name": "张三", "email": "zhangsan@example.com" },
            "resource": { "name": "测试资源", "status": "active" },
            "alert": { "severity": "high", "message": "CPU使用率超过90%" }
        });

        let rendered = manager
            .render("high_priority_alert", &context)
            .unwrap();

        assert!(rendered.subject.contains("高优先级告警"));
        assert!(rendered.body.contains("张三"));
        assert!(rendered.body.contains("测试资源"));
        assert!(rendered.body.contains("high"));
    }

    #[test]
    fn test_template_manager_custom_template() {
        let mut manager = TemplateManager::new();

        let template = r#"
            Subject: 欢迎, {{ user.name }}

            你好 {{ user.name }},

            您的请求 ID 为 {{ request.id }} 已经处理完成。

            状态: {{ status }}
        "#;

        manager
            .add_template("welcome".to_string(),
            template.to_string())
            .unwrap();

        let context = serde_json::json!({
            "user": { "name": "李四" },
            "request": { "id": "REQ_001" },
            "status": "success"
        });

        let rendered = manager.render("welcome", &context).unwrap();

        assert!(rendered.subject.contains("李四"));
        assert!(rendered.body.contains("李四"));
        assert!(rendered.body.contains("REQ_001"));
        assert!(rendered.body.contains("success"));
    }

    #[test]
    fn test_notification_config_validation() {
        let valid_config = NotificationConfig {
            enabled: true,
            default_channel: NotificationChannel::Email,
            rate_limit_per_minute: 100,
            retry_count: 3,
            retry_interval_ms: 1000,
            smtp_host: "smtp.example.com".to_string(),
            smtp_port: 587,
            smtp_username: "user@example.com".to_string(),
            smtp_password: "password".to_string(),
            slack_webhook: None,
            dingtalk_webhook: None,
            wechat_webhook: None,
            webhook_timeout_ms: 5000,
        };

        assert!(valid_config.validate().is_ok());

        let invalid_config = NotificationConfig {
            rate_limit_per_minute: 0,
            ..valid_config.clone()
        };
        assert!(invalid_config.validate().is_err());

        let invalid_retry = NotificationConfig {
            retry_count: 11,
            ..valid_config.clone()
        };
        assert!(invalid_retry.validate().is_err());

        let invalid_port = NotificationConfig {
            smtp_port: 0,
            ..valid_config
        };
        assert!(invalid_port.validate().is_err());
    }

    #[test]
    fn test_notification_message_validation() {
        let valid_message = NotificationMessage {
            message_id: "msg_001".to_string(),
            channel: NotificationChannel::Email,
            recipients: vec!["user@example.com".to_string()],
            subject: Some("测试通知".to_string()),
            content: "这是一条测试消息".to_string(),
            severity: NotificationSeverity::Info,
            template: None,
            context: None,
            metadata: HashMap::new(),
        };

        assert!(valid_message.validate().is_ok());

        let invalid_message = NotificationMessage {
            message_id: "".to_string(),
            channel: NotificationChannel::Email,
            recipients: vec![],
            subject: None,
            content: "".to_string(),
            severity: NotificationSeverity::Info,
            template: None,
            context: None,
            metadata: HashMap::new(),
        };

        let errors = invalid_message.validate().unwrap_err();
        assert!(errors.len() >= 3);
    }

    #[tokio::test]
    async fn test_email_channel() {
        let config = NotificationConfig {
            enabled: true,
            default_channel: NotificationChannel::Email,
            rate_limit_per_minute: 100,
            retry_count: 3,
            retry_interval_ms: 1000,
            smtp_host: "smtp.example.com".to_string(),
            smtp_port: 587,
            smtp_username: "test@example.com".to_string(),
            smtp_password: "password".to_string(),
            slack_webhook: None,
            dingtalk_webhook: None,
            wechat_webhook: None,
            webhook_timeout_ms: 5000,
        };

        let channel = EmailChannel::new(&config).unwrap();
        assert_eq!(channel.channel(), NotificationChannel::Email);

        let message = NotificationMessage {
            message_id: "test_email".to_string(),
            channel: NotificationChannel::Email,
            recipients: vec!["recipient@example.com".to_string()],
            subject: Some("测试邮件".to_string()),
            content: "测试邮件内容".to_string(),
            severity: NotificationSeverity::Info,
            template: None,
            context: None,
            metadata: HashMap::new(),
        };

        let result = channel.send(&message).await;
        assert!(result.is_ok());

        let result = result.unwrap();
        assert!(result.success);
    }

    #[test]
    fn test_notification_manager_lifecycle() {
        let config = NotificationConfig::default();
        let manager = NotificationManager::new(config).unwrap();

        assert!(manager.has_channel(NotificationChannel::Email));
        assert!(manager.has_channel(NotificationChannel::Webhook));

        let channels = manager.list_channels();
        assert!(channels.len() >= 3);

        let message = NotificationMessage {
            message_id: "manager_test".to_string(),
            channel: NotificationChannel::Email,
            recipients: vec!["test@example.com".to_string()],
            subject: Some("Manager Test".to_string()),
            content: "Test content".to_string(),
            severity: NotificationSeverity::Info,
            template: None,
            context: None,
            metadata: HashMap::new(),
        };

        manager.queue_message(message.clone()).unwrap();
        assert_eq!(manager.pending_count(), 1);

        let history = manager.get_message_history(10);
        assert!(history.is_empty());
    }

    #[test]
    fn test_rate_limiting() {
        use enterprise_middleware::notification::RateLimiter;

        let limiter = RateLimiter::new(10, 60000);

        for i in 0..10 {
            assert!(limiter.try_acquire().is_ok(), "第 {} 次应该成功", i + 1);
        }

        assert!(limiter.try_acquire().is_err());
        assert_eq!(limiter.current_usage(), 10);
    }

    #[test]
    fn test_notification_severity_ordering() {
        use enterprise_middleware::types::NotificationSeverity;

        let severities = vec![
            NotificationSeverity::Debug,
            NotificationSeverity::Info,
            NotificationSeverity::Warning,
            NotificationSeverity::Error,
            NotificationSeverity::Critical,
        ];

        for (i, s) in severities.iter().enumerate() {
            match s {
                NotificationSeverity::Debug => assert_eq!(i, 0),
                NotificationSeverity::Info => assert_eq!(i, 1),
                NotificationSeverity::Warning => assert_eq!(i, 2),
                NotificationSeverity::Error => assert_eq!(i, 3),
                NotificationSeverity::Critical => assert_eq!(i, 4),
            }
        }
    }

    #[test]
    fn test_channel_type_conversion() {
        let channels = vec![
            (NotificationChannel::Email, "email"),
            (NotificationChannel::Sms, "sms"),
            (NotificationChannel::Slack, "slack"),
            (NotificationChannel::DingTalk, "dingtalk"),
            (NotificationChannel::WeChat, "wechat"),
            (NotificationChannel::Webhook, "webhook"),
            (NotificationChannel::InApp, "in_app"),
        ];

        for (channel, expected_str) in channels {
            let channel_str: &str = match &channel {
                NotificationChannel::Email => "email",
                NotificationChannel::Sms => "sms",
                NotificationChannel::Slack => "slack",
                NotificationChannel::DingTalk => "dingtalk",
                NotificationChannel::WeChat => "wechat",
                NotificationChannel::Webhook => "webhook",
                NotificationChannel::InApp => "in_app",
            };
            assert_eq!(channel_str, expected_str);
        }
    }

    #[tokio::test]
    async fn test_templated_notification() {
        let manager = TemplateManager::new();

        let context = serde_json::json!({
            "user": { "name": "王五" },
            "quality_check": {
                "rule_name": "空值检查",
                "dataset": "users",
                "failed_count": 42,
                "total_count": 1000
            }
        });

        let rendered = manager
            .render("data_quality_alert", &context)
            .unwrap();

        assert!(rendered.subject.contains("数据质量告警"));
        assert!(rendered.body.contains("王五"));
        assert!(rendered.body.contains("空值检查"));
        assert!(rendered.body.contains("users"));
        assert!(rendered.body.contains("42"));
        assert!(rendered.body.contains("1000"));
    }

    #[test]
    fn test_notification_with_custom_channels() {
        use enterprise_middleware::notification::{
            SlackChannel, DingTalkChannel, WeChatChannel, WebhookChannel, InAppChannel,
        };

        let config = NotificationConfig::default();

        let channels: Vec<Box<dyn enterprise_middleware::notification::NotificationChannelAdapter>> = vec![
            Box::new(SlackChannel::new(&config).unwrap()),
            Box::new(DingTalkChannel::new(&config).unwrap()),
            Box::new(WeChatChannel::new(&config).unwrap()),
            Box::new(WebhookChannel::new(&config).unwrap()),
            Box::new(InAppChannel::new(&config).unwrap()),
        ];

        for channel in channels {
            assert!(channel.channel() != NotificationChannel::Email);
        }
    }
}
