pub mod parser;
pub mod event;
pub mod serializer;
pub mod adapter;
pub mod pipeline;
pub mod cache;

pub use parser::*;
pub use event::*;
pub use serializer::*;
pub use adapter::*;
pub use pipeline::*;
pub use cache::*;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_builder::TestDataBuilder;
    use std::collections::{HashMap, HashSet};
    use tokio::sync::Mutex;
    use std::sync::Arc;

    #[tokio::test]
    async fn test_event_serialization_consistency() {
        let builder = TestDataBuilder::cdc();
        let event = builder.create_user_insert_event(1);

        let json_str = serde_json::to_string(&event).unwrap();
        let deserialized: ChangeEvent = serde_json::from_str(&json_str).unwrap();

        assert_eq!(event.event_id, deserialized.event_id);
        assert_eq!(event.change_type, deserialized.change_type);
        assert_eq!(event.source.database, deserialized.source.database);
        assert_eq!(event.source.table, deserialized.source.table);
        assert_eq!(event.data.after, deserialized.data.after);
    }

    #[tokio::test]
    async fn test_mock_parser_connect_and_disconnect() {
        let builder = TestDataBuilder::cdc();
        let mut parser = builder.create_mock_parser(vec!["users".to_string()]);

        assert!(parser.connect().await.is_ok());
        assert!(parser.disconnect().await.is_ok());
    }

    #[tokio::test]
    async fn test_mock_parser_poll_consistency() {
        let builder = TestDataBuilder::cdc();
        let mut parser = builder.create_mock_parser(vec!["users".to_string()]);
        
        parser.connect().await.unwrap();
        parser.generate_mock_events(100);

        let events = parser.poll(100).await.unwrap();
        assert_eq!(events.len(), 100);

        let event_types: HashMap<ChangeType, usize> = events.iter().fold(HashMap::new(), |mut acc, e| {
            *acc.entry(e.change_type).or_insert(0) += 1;
            acc
        });

        assert_eq!(event_types[&ChangeType::Insert], 34);
        assert_eq!(event_types[&ChangeType::Update], 33);
        assert_eq!(event_types[&ChangeType::Delete], 33);

        for (i, event) in events.iter().enumerate() {
            assert_eq!(event.source.binlog_position, Some((i as u64) * 128));
            assert!(event.source.binlog_file.is_some());
        }
    }

    #[tokio::test]
    async fn test_event_batch_consistency() {
        let builder = TestDataBuilder::cdc();
        let events: Vec<ChangeEvent> = (1..=50)
            .map(|i| builder.create_user_insert_event(i))
            .collect();

        let batch = EventBatch::new(events.clone());

        assert_eq!(batch.count, 50);
        assert_eq!(batch.events.len(), 50);
        assert!(!batch.is_empty());

        for (i, event) in batch.events.iter().enumerate() {
            let id = i as i64 + 1;
            let after = event.data.after.as_ref().unwrap();
            assert_eq!(after["id"].as_i64().unwrap(), id);
        }
    }

    #[tokio::test]
    async fn test_transaction_event_consistency() {
        let builder = TestDataBuilder::cdc();
        let events = builder.create_transaction_events("txn_12345", 20);

        assert_eq!(events.len(), 20);

        for event in &events {
            assert_eq!(event.transaction_id, Some("txn_12345".to_string()));
            assert_eq!(event.source.database, "test_db");
            assert_eq!(event.source.table, "users");
        }

        let unique_ids: HashSet<&String> = events.iter().map(|e| &e.event_id).collect();
        assert_eq!(unique_ids.len(), 20);
    }

    #[tokio::test]
    async fn test_insert_update_delete_consistency() {
        let builder = TestDataBuilder::cdc();
        let events = builder.create_consistency_test_events();

        let inserts: Vec<_> = events.iter().filter(|e| e.change_type == ChangeType::Insert).collect();
        let updates: Vec<_> = events.iter().filter(|e| e.change_type == ChangeType::Update).collect();
        let deletes: Vec<_> = events.iter().filter(|e| e.change_type == ChangeType::Delete).collect();

        assert_eq!(inserts.len(), 100);
        assert_eq!(updates.len(), 10);
        assert_eq!(deletes.len(), 10);

        for event in &inserts {
            assert!(event.data.after.is_some());
            assert!(event.data.before.is_none());
        }

        for event in &updates {
            assert!(event.data.after.is_some());
            assert!(event.data.before.is_some());
            let before = event.data.before.as_ref().unwrap();
            let after = event.data.after.as_ref().unwrap();
            assert_ne!(before["name"], after["name"]);
            assert_ne!(before["email"], after["email"]);
        }

        for event in &deletes {
            assert!(event.data.before.is_some());
            assert!(event.data.after.is_none());
        }
    }

    #[tokio::test]
    async fn test_in_memory_output_adapter_consistency() {
        let builder = TestDataBuilder::cdc();
        let mut adapter = InMemoryOutputAdapter::new();
        adapter.init().await.unwrap();

        let events: Vec<ChangeEvent> = (1..=25)
            .map(|i| builder.create_user_insert_event(i))
            .collect();

        let batch = EventBatch::new(events.clone());
        adapter.send_batch(&batch).await.unwrap();

        let received = adapter.get_events().await;
        assert_eq!(received.len(), 25);

        for (i, event) in received.iter().enumerate() {
            assert_eq!(event.event_id, events[i].event_id);
            assert_eq!(event.change_type, events[i].change_type);
        }

        adapter.clear().await;
        let empty = adapter.get_events().await;
        assert!(empty.is_empty());
    }

    #[tokio::test]
    async fn test_schema_validation_processor() {
        let builder = TestDataBuilder::cdc();
        let mut processor = SchemaValidationProcessor::new(true);

        let valid_event = builder.create_user_insert_event(1);
        let result = processor.process(&valid_event).await;
        assert!(result.is_ok());

        let mut invalid_event = builder.create_user_insert_event(2);
        invalid_event.data.after = None;
        invalid_event.data.before = None;

        let result = processor.process(&invalid_event).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_table_filter_processor() {
        let builder = TestDataBuilder::cdc();
        let mut processor = TableFilterProcessor::new(vec!["users".to_string()]);

        let user_event = builder.create_user_insert_event(1);
        let order_event = builder.create_order_insert_event(1, 1);

        let result1 = processor.process(&user_event).await;
        assert!(result1.is_ok());

        let result2 = processor.process(&order_event).await;
        assert!(result2.is_err());

        let events = vec![
            builder.create_user_insert_event(2),
            builder.create_order_insert_event(2, 2),
            builder.create_user_insert_event(3),
        ];

        let filtered = processor.process_batch(&events).await.unwrap();
        assert_eq!(filtered.len(), 2);
    }

    #[tokio::test]
    async fn test_filter_processor() {
        let builder = TestDataBuilder::cdc();
        let mut processor = FilterProcessor::new(|e| e.change_type == ChangeType::Insert);

        let insert = builder.create_user_insert_event(1);
        let delete = builder.create_delete_event(
            "users",
            2,
            serde_json::json!({"id": 2, "name": "test"})
        );

        assert!(processor.process(&insert).await.is_ok());
        assert!(processor.process(&delete).await.is_err());

        let events = vec![
            builder.create_user_insert_event(1),
            builder.create_user_insert_event(2),
            builder.create_delete_event("users", 3, serde_json::json!({"id": 3})),
        ];

        let filtered = processor.process_batch(&events).await.unwrap();
        assert_eq!(filtered.len(), 2);
    }

    #[tokio::test]
    async fn test_event_ordering_preservation() {
        let builder = TestDataBuilder::cdc();
        let events: Vec<ChangeEvent> = (1..=100)
            .map(|i| builder.create_user_insert_event(i))
            .collect();

        let event_ids: Vec<String> = events.iter().map(|e| e.event_id.clone()).collect();

        let batch = EventBatch::new(events);
        for (i, event) in batch.events.iter().enumerate() {
            assert_eq!(event.event_id, event_ids[i]);
        }
    }

    #[tokio::test]
    async fn test_concurrent_event_handling() {
        let builder = TestDataBuilder::cdc();
        let adapter = Arc::new(Mutex::new(InMemoryOutputAdapter::new()));
        
        adapter.lock().await.init().await.unwrap();

        let mut handles = Vec::new();
        for i in 0..10 {
            let adapter_clone = adapter.clone();
            let events: Vec<ChangeEvent> = (i * 10..(i + 1) * 10)
                .map(|j| builder.create_user_insert_event(j))
                .collect();
            let batch = EventBatch::new(events);

            handles.push(tokio::spawn(async move {
                adapter_clone.lock().await.send_batch(&batch).await.unwrap();
            }));
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let total = adapter.lock().await.get_events().await.len();
        assert_eq!(total, 100);
    }

    #[tokio::test]
    async fn test_change_type_discrimination() {
        let builder = TestDataBuilder::cdc();

        let insert = builder.create_user_insert_event(1);
        assert_eq!(insert.change_type, ChangeType::Insert);
        assert!(insert.data.after.is_some());

        let update = builder.create_update_event(
            "users",
            2,
            serde_json::json!({"id": 2, "name": "old"}),
            serde_json::json!({"id": 2, "name": "new"}),
        );
        assert_eq!(update.change_type, ChangeType::Update);
        assert!(update.data.before.is_some());
        assert!(update.data.after.is_some());

        let delete = builder.create_delete_event(
            "users",
            3,
            serde_json::json!({"id": 3, "name": "deleted"}),
        );
        assert_eq!(delete.change_type, ChangeType::Delete);
        assert!(delete.data.before.is_some());
        assert!(delete.data.after.is_none());
    }

    #[tokio::test]
    async fn test_source_info_consistency() {
        let builder = TestDataBuilder::cdc().with_database("production_db");
        let event = builder.create_user_insert_event(1);

        assert_eq!(event.source.database, "production_db");
        assert_eq!(event.source.table, "users");
        assert!(event.source.binlog_file.is_some());
        assert!(event.source.binlog_position.is_some());
        assert!(event.source.xid.is_some());
    }

    #[tokio::test]
    async fn test_batch_serialization() {
        let builder = TestDataBuilder::cdc();
        let events: Vec<ChangeEvent> = (1..=10)
            .map(|i| builder.create_user_insert_event(i))
            .collect();

        let batch = EventBatch::new(events);
        let json_str = serde_json::to_string(&batch).unwrap();
        let deserialized: EventBatch = serde_json::from_str(&json_str).unwrap();

        assert_eq!(batch.batch_id, deserialized.batch_id);
        assert_eq!(batch.count, deserialized.count);
        assert_eq!(batch.events.len(), deserialized.events.len());
    }
}
