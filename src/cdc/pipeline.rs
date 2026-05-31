use std::sync::Arc;
use tokio::sync::{Mutex, mpsc};
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use super::parser::{BinlogParser, MockBinlogParser, ParserConfig};
use super::serializer::{EventOutputAdapter, InMemoryOutputAdapter};
use super::adapter::{EventProcessor, PipelineConfig, PipelineStats};
use super::event::{ChangeEvent, EventBatch};

pub struct CDCPipeline {
    parser: Box<dyn BinlogParser>,
    output: Box<dyn EventOutputAdapter>,
    processors: Vec<Box<dyn EventProcessor>>,
    config: PipelineConfig,
    stats: Arc<Mutex<PipelineStats>>,
    running: Arc<Mutex<bool>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

pub struct CDCPipelineBuilder {
    config: PipelineConfig,
    parser: Option<Box<dyn BinlogParser>>,
    output: Option<Box<dyn EventOutputAdapter>>,
    processors: Vec<Box<dyn EventProcessor>>,
}

impl CDCPipelineBuilder {
    pub fn new(config: PipelineConfig) -> Self {
        Self {
            config,
            parser: None,
            output: None,
            processors: Vec::new(),
        }
    }

    pub fn with_parser(mut self, parser: Box<dyn BinlogParser>) -> Self {
        self.parser = Some(parser);
        self
    }

    pub fn with_output(mut self, output: Box<dyn EventOutputAdapter>) -> Self {
        self.output = Some(output);
        self
    }

    pub fn add_processor(mut self, processor: Box<dyn EventProcessor>) -> Self {
        self.processors.push(processor);
        self
    }

    pub fn build(self) -> Result<CDCPipeline, StreamSQLError> {
        let parser = self.parser.ok_or_else(|| {
            StreamSQLError::Config("Parser is required for CDC pipeline".into())
        })?;

        let output = self.output.ok_or_else(|| {
            StreamSQLError::Config("Output adapter is required for CDC pipeline".into())
        })?;

        Ok(CDCPipeline {
            parser,
            output,
            processors: self.processors,
            config: self.config,
            stats: Arc::new(Mutex::new(PipelineStats::default())),
            running: Arc::new(Mutex::new(false)),
            shutdown_tx: None,
        })
    }
}

impl CDCPipeline {
    pub async fn start(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Starting CDC pipeline: {}", self.config.name);

        if !self.config.enabled {
            return Err(StreamSQLError::Config("Pipeline is disabled".into()));
        }

        *self.running.lock().await = true;
        self.stats.lock().await.running = true;
        self.stats.lock().await.pipeline_name = self.config.name.clone();

        self.parser.connect().await?;
        self.output.init().await?;

        let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(shutdown_tx);

        let parser_ptr: *mut dyn BinlogParser = &mut *self.parser;
        let output_ptr: *mut dyn EventOutputAdapter = &mut *self.output;
        let processors_ptr: *mut Vec<Box<dyn EventProcessor>> = &mut self.processors;
        let config = self.config.clone();
        let stats = self.stats.clone();
        let running = self.running.clone();

        tokio::spawn(async move {
            let parser = unsafe { &mut *parser_ptr };
            let output = unsafe { &mut *output_ptr };
            let processors = unsafe { &mut *processors_ptr };

            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => {
                        tracing::info!("Shutdown signal received");
                        break;
                    }
                    _ = tokio::time::sleep(tokio::time::Duration::from_millis(config.poll_interval_ms)) => {
                        if !*running.lock().await {
                            break;
                        }

                        match parser.poll(config.poll_interval_ms).await {
                            Ok(events) => {
                                if events.is_empty() {
                                    continue;
                                }

                                let mut processed_events = events;
                                for processor in processors.iter_mut() {
                                    match processor.process_batch(&processed_events).await {
                                        Ok(processed) => {
                                            processed_events = processed;
                                        }
                                        Err(e) => {
                                            tracing::error!("Processor error: {}", e);
                                            stats.lock().await.errors += 1;
                                            continue;
                                        }
                                    }
                                }

                                if !processed_events.is_empty() {
                                    let batch = EventBatch::new(processed_events);
                                    let batch_size = batch.count;

                                    match output.send_batch(&batch).await {
                                        Ok(_) => {
                                            let mut s = stats.lock().await;
                                            s.events_processed += batch_size as u64;
                                            s.batches_sent += 1;
                                            s.last_event_timestamp = Some(chrono::Utc::now());
                                            tracing::debug!("Sent batch of {} events", batch_size);
                                        }
                                        Err(e) => {
                                            tracing::error!("Output error: {}", e);
                                            stats.lock().await.errors += 1;
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                tracing::error!("Poll error: {}", e);
                                stats.lock().await.errors += 1;
                            }
                        }
                    }
                }
            }

            let _ = output.close().await;
            let _ = parser.disconnect().await;
            stats.lock().await.running = false;
            tracing::info!("CDC pipeline stopped");
        });

        Ok(())
    }

    pub async fn stop(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Stopping CDC pipeline: {}", self.config.name);

        *self.running.lock().await = false;

        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(()).await;
        }

        Ok(())
    }

    pub async fn get_stats(&self) -> PipelineStats {
        self.stats.lock().await.clone()
    }

    pub fn is_running(&self) -> bool {
        futures::executor::block_on(async { *self.running.lock().await })
    }
}

pub fn create_default_pipeline(
    tables: Vec<String>,
) -> Result<CDCPipeline, StreamSQLError> {
    let config = PipelineConfig {
        name: "default-pipeline".to_string(),
        source_tables: tables.clone(),
        batch_size: 100,
        poll_interval_ms: 500,
        enabled: true,
    };

    let parser_config = ParserConfig {
        source_type: super::parser::SourceType::Mysql,
        connection_string: "mysql://root:password@localhost:3306".to_string(),
        tables,
        start_position: None,
        server_id: Some(1),
    };

    let parser = Box::new(MockBinlogParser::new(parser_config));
    let output = Box::new(InMemoryOutputAdapter::new());

    CDCPipelineBuilder::new(config)
        .with_parser(parser)
        .with_output(output)
        .build()
}
