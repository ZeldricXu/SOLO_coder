use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use sha2::{Sha256, Digest};

use crate::utils::context::{RequestContext, TransactionContext};
use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::{MetricsCollector, PipelineMonitorSnapshot, PipelineMonitorEvent};
use crate::models::{Config, PipelineStage, StageType};

use super::parser::{Document, DocumentFormat, DocumentParser, GenericParser, ParseResult};
use super::splitter::{Chunk, DocumentSplitter, SplitConfig, SplitResult};
use super::vectorizer::{DocumentVectorizer, MockVectorizer, TextVectorizer, VectorizeConfig, VectorizeResult};
use super::cache::{MultiLevelCache, CacheConfig, CacheStats, CacheInvalidationRequest, CacheTier, PipelineCacheValue};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineRequest {
    pub data: Vec<u8>,
    pub format: DocumentFormat,
    pub namespace: String,
    pub trace_id: Option<String>,
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineResponse {
    pub pipeline_id: String,
    pub document: Document,
    pub chunks: Vec<Chunk>,
    pub total_chunks: usize,
    pub processing_time_ms: u64,
    pub stage_times: HashMap<String, u64>,
    pub warnings: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub cache_hit: bool,
    pub cache_tier: Option<CacheTier>,
    pub cache_key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PipelineStatus {
    Pending,
    Parsing,
    Splitting,
    Vectorizing,
    Completed,
    Failed,
    Rollback,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineProgress {
    pub pipeline_id: String,
    pub status: PipelineStatus,
    pub current_stage: Option<String>,
    pub progress: f32,
    pub stage_progress: HashMap<String, f32>,
    pub started_at: DateTime<Utc>,
}

pub struct DocumentPipeline {
    parser: Arc<dyn DocumentParser>,
    splitter: DocumentSplitter,
    vectorizer: DocumentVectorizer,
    config: Config,
    cache_config: CacheConfig,
    cache: Arc<MultiLevelCache>,
    metrics: MetricsCollector,
    active_pipelines: Arc<std::sync::Mutex<HashMap<String, PipelineProgress>>>,
    enable_cache: bool,
}

impl DocumentPipeline {
    pub fn new(
        config: Config,
        split_config: SplitConfig,
        vectorize_config: VectorizeConfig,
        cache_config: CacheConfig,
        metrics: MetricsCollector,
    ) -> Self {
        let parser = Arc::new(GenericParser::new());
        let text_vectorizer: Arc<dyn TextVectorizer> = Arc::new(MockVectorizer::new(vectorize_config.clone()));
        let splitter = DocumentSplitter::new(split_config);
        let vectorizer = DocumentVectorizer::new(
            text_vectorizer,
            vectorize_config,
        );
        let cache = Arc::new(MultiLevelCache::new(cache_config.clone(), metrics.clone()));

        Self {
            parser,
            splitter,
            vectorizer,
            config,
            cache_config,
            cache,
            metrics,
            active_pipelines: Arc::new(std::sync::Mutex::new(HashMap::new())),
            enable_cache: true,
        }
    }

    pub fn with_cache_enabled(mut self, enabled: bool) -> Self {
        self.enable_cache = enabled;
        self
    }

    pub fn with_parser(mut self, parser: Arc<dyn DocumentParser>) -> Self {
        self.parser = parser;
        self
    }

    pub async fn process(&self, request: PipelineRequest) -> Result<PipelineResponse> {
        let start = std::time::Instant::now();
        let ctx = RequestContext::new(
            request.trace_id.clone(),
            request.namespace.clone(),
        ).with_timeout(self.config.get_param_or("timeout", 300));

        if let Some(user_id) = &request.user_id {
            ctx.set("user_id", user_id.clone());
        }

        let cache_key = if self.enable_cache {
            Some(MultiLevelCache::generate_cache_key(&request.data, &request.format, &request.namespace))
        } else {
            None
        };

        if let (Some(ref key), true) = (&cache_key, self.enable_cache) {
            if let Some(cached) = self.cache.get(key).await {
                let processing_time_ms = start.elapsed().as_millis() as u64;
                tracing::info!(
                    trace_id = %ctx.trace_id,
                    cache_key = %key,
                    "Cache hit for document pipeline"
                );

                self.metrics.increment_counter("pipeline_cache_hit");

                return Ok(PipelineResponse {
                    pipeline_id: format!("pipe_{}", Uuid::new_v4().simple()),
                    document: cached.document,
                    chunks: cached.chunks,
                    total_chunks: cached.chunks.len(),
                    processing_time_ms,
                    stage_times: HashMap::new(),
                    warnings: Vec::new(),
                    created_at: Utc::now(),
                    cache_hit: true,
                    cache_tier: Some(cached.tier),
                    cache_key: Some(key.clone()),
                });
            }
        }

        let mut tx = TransactionContext::new();
        let mut stage_times: HashMap<String, u64> = HashMap::new();
        let mut warnings: Vec<String> = Vec::new();
        let pipeline_id = format!("pipe_{}", Uuid::new_v4().simple());

        self.metrics.pipeline_start(&pipeline_id);
        self.record_pipeline_progress(&pipeline_id, PipelineStatus::Parsing, Some("parse".to_string()), 0.0);

        tracing::info!(
            trace_id = %ctx.trace_id,
            pipeline_id = %pipeline_id,
            "Starting document pipeline"
        );

        let format_tag = request.format.clone().to_string();
        let mut tags = HashMap::new();
        tags.insert("format".to_string(), format_tag.clone());
        tags.insert("namespace".to_string(), request.namespace.clone());

        self.metrics.increment_counter_with_tags("pipeline_requests", tags.clone());

        let result = async {
            let parse_result = self.parse_stage(&pipeline_id, &request, &ctx, &mut tx, &mut stage_times, &mut warnings).await?;
            
            self.record_pipeline_progress(&pipeline_id, PipelineStatus::Splitting, Some("split".to_string()), 0.33);
            
            let split_result = self.split_stage(&pipeline_id, &parse_result, &ctx, &mut tx, &mut stage_times, &mut warnings).await?;
            
            self.record_pipeline_progress(&pipeline_id, PipelineStatus::Vectorizing, Some("vectorize".to_string()), 0.66);
            
            let chunks = self.vectorize_stage(&pipeline_id, split_result, &parse_result, &ctx, &mut tx, &mut stage_times, &mut warnings).await?;

            let processing_time_ms = start.elapsed().as_millis() as u64;
            
            self.record_pipeline_progress(&pipeline_id, PipelineStatus::Completed, None, 1.0);
            
            self.persist_result(&parse_result.document, &chunks).await?;
            self.emit_event(&parse_result.document, &chunks).await?;

            if let (Some(ref key), true) = (&cache_key, self.enable_cache) {
                let content_hash = Self::compute_content_hash(&request.data);
                let cache_value = PipelineCacheValue {
                    document: parse_result.document.clone(),
                    chunks: chunks.clone(),
                    format: request.format.clone(),
                    content_hash,
                    processing_time_ms,
                    tier: CacheTier::L2,
                };
                self.cache.insert(key.clone(), cache_value, CacheTier::Both).await;
                tracing::debug!(cache_key = %key, "Cached pipeline result");
            }

            tx.commit();

            Ok(PipelineResponse {
                pipeline_id: pipeline_id.clone(),
                document: parse_result.document,
                chunks,
                total_chunks: chunks.len(),
                processing_time_ms,
                stage_times,
                warnings,
                created_at: Utc::now(),
                cache_hit: false,
                cache_tier: None,
                cache_key: cache_key.clone(),
            })
        }.await;

        match result {
            Ok(response) => {
                self.metrics.pipeline_complete(&pipeline_id, true, response.processing_time_ms);
                self.metrics.record_histogram_with_tags("pipeline_latency", response.processing_time_ms as f64, tags.clone());
                self.metrics.increment_counter_with_tags("pipeline_success", tags);
                self.remove_pipeline_progress(&pipeline_id);
                ctx.cleanup();
                Ok(response)
            }
            Err(e) => {
                self.metrics.pipeline_complete(&pipeline_id, false, start.elapsed().as_millis() as u64);
                self.metrics.record_histogram_with_tags("pipeline_latency", start.elapsed().as_millis() as f64, tags.clone());
                self.metrics.increment_counter_with_tags("pipeline_errors", tags.clone());
                
                self.record_pipeline_progress(&pipeline_id, PipelineStatus::Failed, None, 0.0);
                self.remove_pipeline_progress(&pipeline_id);
                
                tracing::error!(
                    trace_id = %ctx.trace_id,
                    error = %e,
                    "Pipeline failed, executing rollback"
                );
                tx.rollback();
                ctx.cleanup();
                Err(e)
            }
        }
    }

    async fn parse_stage(
        &self,
        pipeline_id: &str,
        request: &PipelineRequest,
        ctx: &RequestContext,
        tx: &mut TransactionContext,
        stage_times: &mut HashMap<String, u64>,
        warnings: &mut Vec<String>,
    ) -> Result<ParseResult> {
        self.validate_params(request)?;

        if ctx.is_timed_out() {
            return Err(GatewayError::Timeout("Request timed out before parsing".to_string()));
        }

        self.metrics.stage_start(pipeline_id, "parse");
        
        tracing::info!(trace_id = %ctx.trace_id, "Starting parse stage");

        let stage_start = std::time::Instant::now();
        let _timer = self.metrics.start_timer_with_tags("stage_parse_duration", {
            let mut t = HashMap::new();
            t.insert("format".to_string(), request.format.clone().to_string());
            t
        });
        
        let parse_result = self.parser.parse(&request.data, request.format.clone()).await
            .map_err(|e| {
                self.metrics.stage_complete(pipeline_id, "parse", false, stage_start.elapsed().as_millis() as u64);
                GatewayError::Pipeline(format!("Parse failed: {}", e))
            })?;

        let parse_time = stage_start.elapsed().as_millis() as u64;
        stage_times.insert("parse".to_string(), parse_time);
        warnings.extend(parse_result.warnings.clone());

        self.metrics.stage_complete(pipeline_id, "parse", true, parse_time);

        tx.add_rollback({
            let doc_id = parse_result.document.document_id.clone();
            let metrics = self.metrics.clone();
            let pid = pipeline_id.to_string();
            move || {
                tracing::info!("Rollback: cleaning up parsed document {}", doc_id);
                metrics.record_pipeline_event(PipelineMonitorEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    pipeline_id: pid,
                    stage: "parse".to_string(),
                    event_type: "rollback".to_string(),
                    timestamp: Utc::now(),
                    duration_ms: None,
                    tags: HashMap::new(),
                });
            }
        });

        Ok(parse_result)
    }

    async fn split_stage(
        &self,
        pipeline_id: &str,
        parse_result: &ParseResult,
        ctx: &RequestContext,
        tx: &mut TransactionContext,
        stage_times: &mut HashMap<String, u64>,
        warnings: &mut Vec<String>,
    ) -> Result<SplitResult> {
        if ctx.is_timed_out() {
            return Err(GatewayError::Timeout("Request timed out during splitting".to_string()));
        }

        self.metrics.stage_start(pipeline_id, "split");

        tracing::info!(
            trace_id = %ctx.trace_id,
            document_id = %parse_result.document.document_id,
            "Starting split stage"
        );

        let stage_start = std::time::Instant::now();
        let _timer = self.metrics.start_timer("stage_split_duration");
        
        let split_result = self.splitter.split(
            &parse_result.document.document_id,
            &parse_result.document.content,
        ).map_err(|e| {
            self.metrics.stage_complete(pipeline_id, "split", false, stage_start.elapsed().as_millis() as u64);
            GatewayError::Pipeline(format!("Split failed: {}", e))
        })?;

        let split_time = stage_start.elapsed().as_millis() as u64;
        stage_times.insert("split".to_string(), split_time);

        self.metrics.stage_complete(pipeline_id, "split", true, split_time);
        self.metrics.record_histogram("stage_split_chunks", split_result.total_chunks as f64);

        tracing::info!(
            trace_id = %ctx.trace_id,
            document_id = %parse_result.document.document_id,
            total_chunks = split_result.total_chunks,
            "Split completed"
        );

        tx.add_rollback({
            let doc_id = parse_result.document.document_id.clone();
            let count = split_result.total_chunks;
            let metrics = self.metrics.clone();
            let pid = pipeline_id.to_string();
            move || {
                tracing::info!("Rollback: removing {} chunks for document {}", count, doc_id);
                metrics.record_pipeline_event(PipelineMonitorEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    pipeline_id: pid,
                    stage: "split".to_string(),
                    event_type: "rollback".to_string(),
                    timestamp: Utc::now(),
                    duration_ms: None,
                    tags: HashMap::new(),
                });
            }
        });

        Ok(split_result)
    }

    async fn vectorize_stage(
        &self,
        pipeline_id: &str,
        split_result: SplitResult,
        parse_result: &ParseResult,
        ctx: &RequestContext,
        tx: &mut TransactionContext,
        stage_times: &mut HashMap<String, u64>,
        warnings: &mut Vec<String>,
    ) -> Result<Vec<Chunk>> {
        if ctx.is_timed_out() {
            return Err(GatewayError::Timeout("Request timed out during vectorization".to_string()));
        }

        self.metrics.stage_start(pipeline_id, "vectorize");

        tracing::info!(
            trace_id = %ctx.trace_id,
            document_id = %parse_result.document.document_id,
            chunk_count = split_result.total_chunks,
            "Starting vectorize stage"
        );

        let stage_start = std::time::Instant::now();
        let _timer = self.metrics.start_timer("stage_vectorize_duration");
        
        let vectorized_chunks = self.vectorizer.vectorize_chunks(split_result.chunks).await
            .map_err(|e| {
                self.metrics.stage_complete(pipeline_id, "vectorize", false, stage_start.elapsed().as_millis() as u64);
                GatewayError::Pipeline(format!("Vectorization failed: {}", e))
            })?;

        let vectorize_time = stage_start.elapsed().as_millis() as u64;
        stage_times.insert("vectorize".to_string(), vectorize_time);

        self.metrics.stage_complete(pipeline_id, "vectorize", true, vectorize_time);
        self.metrics.record_histogram("stage_vectorize_chunks", vectorized_chunks.len() as f64);

        tx.add_rollback({
            let doc_id = parse_result.document.document_id.clone();
            let metrics = self.metrics.clone();
            let pid = pipeline_id.to_string();
            move || {
                tracing::info!("Rollback: removing vectors for document {}", doc_id);
                metrics.record_pipeline_event(PipelineMonitorEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    pipeline_id: pid,
                    stage: "vectorize".to_string(),
                    event_type: "rollback".to_string(),
                    timestamp: Utc::now(),
                    duration_ms: None,
                    tags: HashMap::new(),
                });
            }
        });

        Ok(vectorized_chunks)
    }

    fn validate_params(&self, request: &PipelineRequest) -> Result<()> {
        if request.data.is_empty() {
            return Err(GatewayError::Validation("Empty document data".to_string()));
        }

        let max_size = self.config.get_param_or("max_document_size", 10 * 1024 * 1024);
        if request.data.len() > max_size {
            return Err(GatewayError::Validation(format!(
                "Document too large: {} bytes (max: {})",
                request.data.len(),
                max_size
            )));
        }

        if request.namespace.is_empty() {
            return Err(GatewayError::Validation("Namespace is required".to_string()));
        }

        if !self.parser.supported_formats().contains(&request.format) {
            return Err(GatewayError::Validation(format!(
                "Unsupported format: {:?}",
                request.format
            )));
        }

        Ok(())
    }

    async fn persist_result(&self, document: &Document, chunks: &[Chunk]) -> Result<()> {
        tracing::debug!(
            document_id = %document.document_id,
            chunk_count = chunks.len(),
            "Persisting pipeline result"
        );
        Ok(())
    }

    async fn emit_event(&self, document: &Document, chunks: &[Chunk]) -> Result<()> {
        tracing::debug!(
            document_id = %document.document_id,
            chunk_count = chunks.len(),
            "Emitting pipeline completion event"
        );
        Ok(())
    }

    fn record_pipeline_progress(&self, pipeline_id: &str, status: PipelineStatus, current_stage: Option<String>, progress: f32) {
        let mut pipelines = self.active_pipelines.lock().unwrap();
        pipelines.insert(pipeline_id.to_string(), PipelineProgress {
            pipeline_id: pipeline_id.to_string(),
            status,
            current_stage,
            progress,
            stage_progress: HashMap::new(),
            started_at: Utc::now(),
        });
    }

    fn remove_pipeline_progress(&self, pipeline_id: &str) {
        let mut pipelines = self.active_pipelines.lock().unwrap();
        pipelines.remove(pipeline_id);
    }

    pub fn get_pipeline_progress(&self, pipeline_id: &str) -> Option<PipelineProgress> {
        let pipelines = self.active_pipelines.lock().unwrap();
        pipelines.get(pipeline_id).cloned()
    }

    pub fn get_all_active_pipelines(&self) -> Vec<PipelineProgress> {
        let pipelines = self.active_pipelines.lock().unwrap();
        pipelines.values().cloned().collect()
    }

    pub fn get_monitor_snapshot(&self) -> PipelineMonitorSnapshot {
        self.metrics.get_pipeline_monitor_snapshot()
    }

    pub fn metrics(&self) -> &MetricsCollector {
        &self.metrics
    }

    fn compute_content_hash(data: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(data);
        format!("{:x}", hasher.finalize())
    }

    pub fn cache_stats(&self) -> CacheStats {
        self.cache.stats()
    }

    pub fn invalidate_cache(&self, request: CacheInvalidationRequest) -> usize {
        self.cache.invalidate(request)
    }

    pub fn clear_cache(&self, tier: CacheTier) {
        self.cache.clear(tier)
    }

    pub fn warmup_cache(&self) {
        self.cache.warmup()
    }

    pub async fn prefetch_cache(&self, keys: Vec<String>) {
        self.cache.prefetch(keys).await
    }

    pub fn is_cache_enabled(&self) -> bool {
        self.enable_cache
    }

    pub fn cache_config(&self) -> &CacheConfig {
        &self.cache_config
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_config() -> Config {
        Config::new("test", 1)
            .with_parameter("timeout", 60)
            .with_parameter("max_document_size", 1024 * 1024)
    }

    fn create_test_pipeline() -> DocumentPipeline {
        let config = create_test_config();
        let split_config = SplitConfig::default();
        let vectorize_config = VectorizeConfig::default();
        let cache_config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        
        DocumentPipeline::new(config, split_config, vectorize_config, cache_config, metrics)
    }

    #[tokio::test]
    async fn test_pipeline_full_flow() {
        let pipeline = create_test_pipeline();
        
        let request = PipelineRequest {
            data: b"Hello World\nThis is a test document.\n\nIt has multiple paragraphs.\n\nEach paragraph will be split into chunks.".to_vec(),
            format: DocumentFormat::Txt,
            namespace: "test".to_string(),
            trace_id: Some("trace_test".to_string()),
            user_id: Some("user_123".to_string()),
        };

        let response = pipeline.process(request).await.unwrap();

        assert!(response.pipeline_id.starts_with("pipe_"));
        assert_eq!(response.document.title, "Hello World");
        assert!(response.total_chunks > 0);
        assert!(!response.chunks.is_empty());
        assert!(response.stage_times.contains_key("parse"));
        assert!(response.stage_times.contains_key("split"));
        assert!(response.stage_times.contains_key("vectorize"));
        assert!(response.processing_time_ms > 0);

        for chunk in &response.chunks {
            assert!(chunk.embedding.is_some());
            assert_eq!(chunk.embedding.as_ref().unwrap().len(), 1536);
        }
    }

    #[tokio::test]
    async fn test_pipeline_monitoring() {
        let pipeline = create_test_pipeline();
        
        for i in 0..5 {
            let request = PipelineRequest {
                data: format!("Document number {}", i).as_bytes().to_vec(),
                format: DocumentFormat::Txt,
                namespace: "test".to_string(),
                trace_id: None,
                user_id: None,
            };
            pipeline.process(request).await.unwrap();
        }

        let snapshot = pipeline.get_monitor_snapshot();
        assert_eq!(snapshot.total_pipelines, 5);
        assert_eq!(snapshot.success_count, 5);
        assert_eq!(snapshot.failure_count, 0);
        assert!(snapshot.stage_metrics.contains_key("parse"));
        assert!(snapshot.stage_metrics.contains_key("split"));
        assert!(snapshot.stage_metrics.contains_key("vectorize"));
        assert!(!snapshot.recent_events.is_empty());
    }

    #[tokio::test]
    async fn test_pipeline_empty_data() {
        let pipeline = create_test_pipeline();
        
        let request = PipelineRequest {
            data: vec![],
            format: DocumentFormat::Txt,
            namespace: "test".to_string(),
            trace_id: None,
            user_id: None,
        };

        let result = pipeline.process(request).await;
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), GatewayError::Validation(_)));
    }

    #[tokio::test]
    async fn test_pipeline_empty_namespace() {
        let pipeline = create_test_pipeline();
        
        let request = PipelineRequest {
            data: b"test".to_vec(),
            format: DocumentFormat::Txt,
            namespace: "".to_string(),
            trace_id: None,
            user_id: None,
        };

        let result = pipeline.process(request).await;
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), GatewayError::Validation(_)));
    }

    #[tokio::test]
    async fn test_pipeline_json_format() {
        let pipeline = create_test_pipeline();
        
        let request = PipelineRequest {
            data: br#"{"name": "test", "value": 123, "description": "This is a JSON document with structured content"}"#.to_vec(),
            format: DocumentFormat::Json,
            namespace: "test".to_string(),
            trace_id: None,
            user_id: None,
        };

        let response = pipeline.process(request).await.unwrap();
        assert!(response.document.content.contains("name: test"));
        assert!(response.document.content.contains("value: 123"));
    }

    #[tokio::test]
    async fn test_pipeline_metrics() {
        let pipeline = create_test_pipeline();
        
        for i in 0..5 {
            let request = PipelineRequest {
                data: format!("Document number {}", i).as_bytes().to_vec(),
                format: DocumentFormat::Txt,
                namespace: "test".to_string(),
                trace_id: None,
                user_id: None,
            };
            pipeline.process(request).await.unwrap();
        }

        let metrics = pipeline.metrics().snapshot();
        assert_eq!(metrics.success_count, Some(5));
        assert_eq!(metrics.throughput, Some(5.0));
    }

    #[tokio::test]
    async fn test_pipeline_progress_tracking() {
        let pipeline = create_test_pipeline();
        
        let request = PipelineRequest {
            data: b"Hello World".to_vec(),
            format: DocumentFormat::Txt,
            namespace: "test".to_string(),
            trace_id: None,
            user_id: None,
        };

        let response = pipeline.process(request).await.unwrap();
        
        let active = pipeline.get_all_active_pipelines();
        assert!(active.is_empty());
        
        let snapshot = pipeline.get_monitor_snapshot();
        assert_eq!(snapshot.active_pipelines, 0);
    }
}
