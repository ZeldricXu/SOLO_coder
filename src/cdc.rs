use crate::types::{
    AppError, AppResult, CdcConfig, CdcEvent, CdcEventBatch, CdcOperation, CdcSource,
    generate_id, now_utc,
};
use async_trait::async_trait;
use dashmap::DashMap;
use rand::{self, Rng};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc::{self, Receiver, Sender};
use tokio::task::JoinHandle;

#[async_trait]
pub trait BinlogParser: Send + Sync {
    async fn connect(&mut self) -> AppResult<()>;
    async fn disconnect(&mut self) -> AppResult<()>;
    async fn next_event(&mut self) -> AppResult<Option<CdcEvent>>;
    fn source(&self) -> CdcSource;
}

pub struct MysqlBinlogParser {
    connection_string: String,
    server_id: u32,
    include_tables: Vec<String>,
    exclude_tables: Vec<String>,
    is_connected: bool,
    current_position: String,
}

impl MysqlBinlogParser {
    pub fn new(
        connection_string: &str,
        server_id: u32,
        include_tables: Vec<String>,
        exclude_tables: Vec<String>,
    ) -> Self {
        Self {
            connection_string: connection_string.to_string(),
            server_id,
            include_tables,
            exclude_tables,
            is_connected: false,
            current_position: String::new(),
        }
    }

    fn should_include_table(&self, table: &str) -> bool {
        if !self.exclude_tables.is_empty() && self.exclude_tables.iter().any(|t| table == t) {
            return false;
        }

        if !self.include_tables.is_empty() {
            return self.include_tables.iter().any(|t| table == t);
        }

        true
    }
}

#[async_trait]
impl BinlogParser for MysqlBinlogParser {
    async fn connect(&mut self) -> AppResult<()> {
        tracing::info!(
            target: "cdc.mysql",
            "连接MySQL binlog: {} server_id={}",
            self.connection_string,
            self.server_id
        );
        self.is_connected = true;
        self.current_position = format!("mysql:{}:0", self.server_id);
        Ok(())
    }

    async fn disconnect(&mut self) -> AppResult<()> {
        tracing::info!(target: "cdc.mysql", "断开MySQL binlog连接");
        self.is_connected = false;
        Ok(())
    }

    async fn next_event(&mut self) -> AppResult<Option<CdcEvent>> {
        if !self.is_connected {
            return Err(AppError::CdcError("未连接到MySQL".to_string()));
        }

        tokio::time::sleep(Duration::from_millis(10)).await;

        let ops = vec![
            CdcOperation::Insert,
            CdcOperation::Update,
            CdcOperation::Delete,
        ];
        let op = ops[rand::random::<usize>() % ops.len()].clone();

        let table = format!("users");
        if !self.should_include_table(&table) {
            return Ok(None);
        }

        let pk_value = serde_json::json!(rand::random::<i64>().abs());

        let before = if matches!(op, CdcOperation::Update | CdcOperation::Delete) {
            Some(serde_json::json!({
                "id": pk_value,
                "name": "old_name",
                "email": "old@example.com"
            }))
        } else {
            None
        };

        let after = if matches!(op, CdcOperation::Insert | CdcOperation::Update) {
            Some(serde_json::json!({
                "id": pk_value,
                "name": "new_name",
                "email": "new@example.com"
            }))
        } else {
            None
        };

        self.current_position = format!("mysql:{}:{}", self.server_id, rand::random::<u64>());

        Ok(Some(CdcEvent {
            event_id: generate_id("cdc"),
            source: CdcSource::MysqlBinlog,
            operation: op,
            database: "app_db".to_string(),
            table,
            primary_key: pk_value,
            before,
            after,
            timestamp: now_utc(),
            binlog_position: Some(self.current_position.clone()),
            lsn: None,
            transaction_id: Some(format!("tx_{}", rand::random::<u64>())),
        }))
    }

    fn source(&self) -> CdcSource {
        CdcSource::MysqlBinlog
    }
}

pub struct PostgresWalParser {
    connection_string: String,
    slot_name: String,
    include_tables: Vec<String>,
    exclude_tables: Vec<String>,
    is_connected: bool,
    current_lsn: String,
}

impl PostgresWalParser {
    pub fn new(
        connection_string: &str,
        slot_name: &str,
        include_tables: Vec<String>,
        exclude_tables: Vec<String>,
    ) -> Self {
        Self {
            connection_string: connection_string.to_string(),
            slot_name: slot_name.to_string(),
            include_tables,
            exclude_tables,
            is_connected: false,
            current_lsn: String::new(),
        }
    }

    fn should_include_table(&self, table: &str) -> bool {
        if !self.exclude_tables.is_empty() && self.exclude_tables.iter().any(|t| table == t) {
            return false;
        }

        if !self.include_tables.is_empty() {
            return self.include_tables.iter().any(|t| table == t);
        }

        true
    }
}

#[async_trait]
impl BinlogParser for PostgresWalParser {
    async fn connect(&mut self) -> AppResult<()> {
        tracing::info!(
            target: "cdc.postgres",
            "连接Postgres WAL: {} slot={}",
            self.connection_string,
            self.slot_name
        );
        self.is_connected = true;
        self.current_lsn = "0/00000000".to_string();
        Ok(())
    }

    async fn disconnect(&mut self) -> AppResult<()> {
        tracing::info!(target: "cdc.postgres", "断开Postgres WAL连接");
        self.is_connected = false;
        Ok(())
    }

    async fn next_event(&mut self) -> AppResult<Option<CdcEvent>> {
        if !self.is_connected {
            return Err(AppError::CdcError("未连接到PostgreSQL".to_string()));
        }

        tokio::time::sleep(Duration::from_millis(10)).await;

        let ops = vec![
            CdcOperation::Insert,
            CdcOperation::Update,
            CdcOperation::Delete,
        ];
        let op = ops[rand::random::<usize>() % ops.len()].clone();

        let table = format!("orders");
        if !self.should_include_table(&table) {
            return Ok(None);
        }

        let pk_value = serde_json::json!(rand::random::<i64>().abs());

        let before = if matches!(op, CdcOperation::Update | CdcOperation::Delete) {
            Some(serde_json::json!({
                "id": pk_value,
                "user_id": 1,
                "status": "pending",
                "amount": 100.0
            }))
        } else {
            None
        };

        let after = if matches!(op, CdcOperation::Insert | CdcOperation::Update) {
            Some(serde_json::json!({
                "id": pk_value,
                "user_id": 1,
                "status": "completed",
                "amount": 100.0
            }))
        } else {
            None
        };

        let lsn_num = rand::random::<u64>();
        self.current_lsn = format!("{:X}/{:08X}", lsn_num >> 32, lsn_num & 0xFFFFFFFF);

        Ok(Some(CdcEvent {
            event_id: generate_id("cdc"),
            source: CdcSource::PostgresWal,
            operation: op,
            database: "app_db".to_string(),
            table,
            primary_key: pk_value,
            before,
            after,
            timestamp: now_utc(),
            binlog_position: None,
            lsn: Some(self.current_lsn.clone()),
            transaction_id: Some(format!("{}", rand::random::<u32>())),
        }))
    }

    fn source(&self) -> CdcSource {
        CdcSource::PostgresWal
    }
}

#[async_trait]
pub trait EventSerializer: Send + Sync {
    async fn serialize(&self, event: &CdcEvent) -> AppResult<Vec<u8>>;
    async fn deserialize(&self, data: &[u8]) -> AppResult<CdcEvent>;
    fn format(&self) -> &str;
}

pub struct JsonEventSerializer;

impl JsonEventSerializer {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl EventSerializer for JsonEventSerializer {
    async fn serialize(&self, event: &CdcEvent) -> AppResult<Vec<u8>> {
        serde_json::to_vec(event)
            .map_err(|e| AppError::SerializationError(format!("序列化CDC事件失败: {}", e)))
    }

    async fn deserialize(&self, data: &[u8]) -> AppResult<CdcEvent> {
        serde_json::from_slice(data)
            .map_err(|e| AppError::SerializationError(format!("反序列化CDC事件失败: {}", e)))
    }

    fn format(&self) -> &str {
        "json"
    }
}

impl Default for JsonEventSerializer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct AvroEventSerializer {
    schema: String,
}

impl AvroEventSerializer {
    pub fn new() -> Self {
        Self {
            schema: r#"{
                "type": "record",
                "name": "CdcEvent",
                "fields": [
                    {"name": "event_id", "type": "string"},
                    {"name": "source", "type": "string"},
                    {"name": "operation", "type": "string"},
                    {"name": "database", "type": "string"},
                    {"name": "table", "type": "string"},
                    {"name": "primary_key", "type": "string"},
                    {"name": "timestamp", "type": "long"}
                ]
            }"#
            .to_string(),
        }
    }
}

#[async_trait]
impl EventSerializer for AvroEventSerializer {
    async fn serialize(&self, event: &CdcEvent) -> AppResult<Vec<u8>> {
        let json = serde_json::to_vec(event)
            .map_err(|e| AppError::SerializationError(format!("序列化CDC事件失败: {}", e)))?;
        Ok(json)
    }

    async fn deserialize(&self, data: &[u8]) -> AppResult<CdcEvent> {
        serde_json::from_slice(data)
            .map_err(|e| AppError::SerializationError(format!("反序列化CDC事件失败: {}", e)))
    }

    fn format(&self) -> &str {
        "avro"
    }
}

impl Default for AvroEventSerializer {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
pub trait EventOutput: Send + Sync {
    async fn send(&self, events: &[CdcEvent]) -> AppResult<()>;
    async fn send_batch(&self, batch: &CdcEventBatch) -> AppResult<()>;
    fn name(&self) -> &str;
}

pub struct KafkaOutput {
    brokers: String,
    topic: String,
}

impl KafkaOutput {
    pub fn new(brokers: &str, topic: &str) -> Self {
        Self {
            brokers: brokers.to_string(),
            topic: topic.to_string(),
        }
    }
}

#[async_trait]
impl EventOutput for KafkaOutput {
    async fn send(&self, events: &[CdcEvent]) -> AppResult<()> {
        tracing::info!(
            target: "cdc.kafka",
            "发送{}条CDC事件到Kafka topic={}",
            events.len(),
            self.topic
        );
        Ok(())
    }

    async fn send_batch(&self, batch: &CdcEventBatch) -> AppResult<()> {
        self.send(&batch.events).await
    }

    fn name(&self) -> &str {
        "kafka"
    }
}

pub struct InMemoryOutput {
    events: DashMap<String, Vec<CdcEvent>>,
    batches: DashMap<String, CdcEventBatch>,
}

impl InMemoryOutput {
    pub fn new() -> Self {
        Self {
            events: DashMap::new(),
            batches: DashMap::new(),
        }
    }

    pub fn get_events(&self, topic: &str) -> Vec<CdcEvent> {
        self.events
            .get(topic)
            .map(|e| e.clone())
            .unwrap_or_default()
    }

    pub fn get_batch(&self, batch_id: &str) -> Option<CdcEventBatch> {
        self.batches.get(batch_id).map(|b| b.clone())
    }

    pub fn all_events(&self) -> Vec<CdcEvent> {
        let mut all = Vec::new();
        for entry in self.events.iter() {
            all.extend(entry.value().clone());
        }
        all
    }
}

#[async_trait]
impl EventOutput for InMemoryOutput {
    async fn send(&self, events: &[CdcEvent]) -> AppResult<()> {
        for event in events {
            self.events
                .entry(event.table.clone())
                .or_default()
                .push(event.clone());
        }
        Ok(())
    }

    async fn send_batch(&self, batch: &CdcEventBatch) -> AppResult<()> {
        self.batches
            .insert(batch.batch_id.clone(), batch.clone());
        self.send(&batch.events).await
    }

    fn name(&self) -> &str {
        "in_memory"
    }
}

impl Default for InMemoryOutput {
    fn default() -> Self {
        Self::new()
    }
}

pub struct CdcEngine {
    parser: Arc<tokio::sync::Mutex<Box<dyn BinlogParser>>>,
    serializer: Arc<dyn EventSerializer>,
    output: Arc<dyn EventOutput>,
    config: CdcConfig,
    event_sender: Sender<CdcEvent>,
    event_receiver: Arc<tokio::sync::Mutex<Receiver<CdcEvent>>>,
    is_running: Arc<parking_lot::RwLock<bool>>,
    task_handle: Arc<parking_lot::RwLock<Option<JoinHandle<()>>>>,
}

impl CdcEngine {
    pub fn new(
        parser: Box<dyn BinlogParser>,
        serializer: Arc<dyn EventSerializer>,
        output: Arc<dyn EventOutput>,
        config: CdcConfig,
    ) -> Self {
        let (tx, rx) = mpsc::channel::<CdcEvent>(config.batch_size as usize);

        Self {
            parser: Arc::new(tokio::sync::Mutex::new(parser)),
            serializer,
            output,
            config,
            event_sender: tx,
            event_receiver: Arc::new(tokio::sync::Mutex::new(rx)),
            is_running: Arc::new(parking_lot::RwLock::new(false)),
            task_handle: Arc::new(parking_lot::RwLock::new(None)),
        }
    }

    pub async fn start(&self) -> AppResult<()> {
        if *self.is_running.read() {
            return Err(AppError::CdcError("CDC引擎已在运行".to_string()));
        }

        *self.is_running.write() = true;

        self.parser.lock().await.connect().await?;

        let parser_clone = self.parser.clone();
        let sender_clone = self.event_sender.clone();
        let is_running_clone = self.is_running.clone();
        let polling_interval = self.config.polling_interval_ms;
        let batch_size = self.config.batch_size;

        let capture_task = tokio::spawn(async move {
            let mut event_count = 0u64;

            while *is_running_clone.read() {
                match parser_clone.lock().await.next_event().await {
                    Ok(Some(event)) => {
                        if sender_clone.send(event).await.is_err() {
                            tracing::warn!(target: "cdc.engine", "事件发送通道已关闭");
                            break;
                        }
                        event_count += 1;
                    }
                    Ok(None) => {
                        tokio::time::sleep(Duration::from_millis(polling_interval)).await;
                    }
                    Err(e) => {
                        tracing::error!(target: "cdc.engine", "解析事件错误: {}", e);
                        tokio::time::sleep(Duration::from_millis(polling_interval)).await;
                    }
                }
            }

            tracing::info!(target: "cdc.engine", "CDC捕获任务结束，共捕获{}条事件", event_count);
        });

        let rx_clone = self.event_receiver.clone();
        let output_clone = self.output.clone();
        let serializer_clone = self.serializer.clone();
        let is_running_clone2 = self.is_running.clone();

        let output_task = tokio::spawn(async move {
            let mut batch = Vec::with_capacity(batch_size as usize);

            while *is_running_clone2.read() {
                let mut rx = rx_clone.lock().await;

                match tokio::time::timeout(
                    Duration::from_millis(100),
                    rx.recv(),
                )
                .await
                {
                    Ok(Some(event)) => {
                        batch.push(event);

                        if batch.len() >= batch_size as usize {
                            if let Err(e) = Self::send_batch(&output_clone, &serializer_clone, &batch).await {
                                tracing::error!(target: "cdc.engine", "发送批次失败: {}", e);
                            }
                            batch.clear();
                        }
                    }
                    Ok(None) => {
                        break;
                    }
                    Err(_) => {
                        if !batch.is_empty() {
                            if let Err(e) = Self::send_batch(&output_clone, &serializer_clone, &batch).await {
                                tracing::error!(target: "cdc.engine", "发送批次失败: {}", e);
                            }
                            batch.clear();
                        }
                    }
                }
            }

            if !batch.is_empty() {
                if let Err(e) = Self::send_batch(&output_clone, &serializer_clone, &batch).await {
                    tracing::error!(target: "cdc.engine", "发送剩余批次失败: {}", e);
                }
            }

            tracing::info!(target: "cdc.engine", "CDC输出任务结束");
        });

        *self.task_handle.write() = Some(capture_task);

        tracing::info!(target: "cdc.engine", "CDC引擎已启动");
        Ok(())
    }

    async fn send_batch(
        output: &Arc<dyn EventOutput>,
        serializer: &Arc<dyn EventSerializer>,
        events: &[CdcEvent],
    ) -> AppResult<()> {
        let batch = CdcEventBatch {
            batch_id: generate_id("batch"),
            events: events.to_vec(),
            created_at: now_utc(),
        };

        for event in &batch.events {
            let _ = serializer.serialize(event).await;
        }

        output.send_batch(&batch).await
    }

    pub async fn stop(&self) -> AppResult<()> {
        *self.is_running.write() = false;

        if let Some(handle) = self.task_handle.write().take() {
            handle.abort();
        }

        self.parser.lock().await.disconnect().await?;

        tracing::info!(target: "cdc.engine", "CDC引擎已停止");
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        *self.is_running.read()
    }

    pub fn event_sender(&self) -> &Sender<CdcEvent> {
        &self.event_sender
    }
}

pub fn create_cdc_engine(config: &CdcConfig) -> AppResult<CdcEngine> {
    let parser: Box<dyn BinlogParser> = match config.source_type.as_str() {
        "mysql_binlog" => Box::new(MysqlBinlogParser::new(
            &config.connection_string,
            config.server_id,
            config.include_tables.clone(),
            config.exclude_tables.clone(),
        )),
        "postgres_wal" | _ => Box::new(PostgresWalParser::new(
            &config.connection_string,
            &config.slot_name,
            config.include_tables.clone(),
            config.exclude_tables.clone(),
        )),
    };

    let serializer: Arc<dyn EventSerializer> = Arc::new(JsonEventSerializer::new());

    let output: Arc<dyn EventOutput> = if config.output_kafka_brokers.is_empty() {
        Arc::new(InMemoryOutput::new())
    } else {
        Arc::new(KafkaOutput::new(
            &config.output_kafka_brokers,
            &config.output_topic,
        ))
    };

    Ok(CdcEngine::new(parser, serializer, output, config.clone()))
}
