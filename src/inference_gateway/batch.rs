use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, mpsc};
use tokio::time::interval;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use tracing::{info, debug};

use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::MetricsCollector;
use super::provider::{InferenceRequest, InferenceResponse, LLMProvider};
use super::gateway::{GatewayRequest, GatewayResponse, ChatMessage, UsageInfo};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchInferenceRequest {
    pub requests: Vec<GatewayRequest>,
    pub max_wait_ms: Option<u64>,
    pub max_batch_size: Option<usize>,
    pub batch_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchInferenceResponse {
    pub batch_id: String,
    pub responses: Vec<BatchItemResult>,
    pub total_requests: usize,
    pub successful_requests: usize,
    pub failed_requests: usize,
    pub processing_time_ms: u64,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchItemResult {
    pub request_index: usize,
    pub response: Option<GatewayResponse>,
    pub error: Option<String>,
    pub latency_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchConfig {
    pub max_batch_size: usize,
    pub max_wait_ms: u64,
    pub enable_auto_batching: bool,
    pub min_batch_size: usize,
    pub max_concurrent_batches: usize,
}

impl Default for BatchConfig {
    fn default() -> Self {
        Self {
            max_batch_size: 32,
            max_wait_ms: 100,
            enable_auto_batching: true,
            min_batch_size: 1,
            max_concurrent_batches: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchStats {
    pub total_batches_processed: u64,
    pub total_requests_processed: u64,
    pub avg_batch_size: f64,
    pub avg_batch_latency_ms: f64,
    pub requests_merged: u64,
    pub current_pending_batches: usize,
}

struct PendingRequest {
    request: GatewayRequest,
    sender: mpsc::Sender<Result<GatewayResponse>>,
    received_at: Instant,
}

struct BatchAggregator {
    pending: Arc<Mutex<Vec<PendingRequest>>>,
    config: BatchConfig,
    metrics: MetricsCollector,
    is_running: Arc<std::sync::atomic::AtomicBool>,
}

impl BatchAggregator {
    fn new(config: BatchConfig, metrics: MetricsCollector) -> Self {
        Self {
            pending: Arc::new(Mutex::new(Vec::new())),
            config,
            metrics,
            is_running: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    async fn submit(&self, request: GatewayRequest) -> Result<GatewayResponse> {
        let (tx, mut rx) = mpsc::channel(1);
        
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest {
                request,
                sender: tx,
                received_at: Instant::now(),
            });
            
            if pending.len() >= self.config.max_batch_size {
                let batch: Vec<PendingRequest> = pending.drain(..).collect();
                self.process_batch(batch).await;
            }
        }
        
        rx.recv().await
            .map_err(|e| GatewayError::Internal(format!("Batch channel error: {}", e)))?
    }

    async fn process_batch(&self, batch: Vec<PendingRequest>) {
        let batch_size = batch.len();
        let batch_id = format!("batch_{}", Uuid::new_v4().simple());
        let start = Instant::now();
        
        debug!("Processing batch {} with {} requests", batch_id, batch_size);
        self.metrics.increment_counter("batch_processed");
        self.metrics.record_histogram("batch_size", batch_size as f64);
        
        let providers: Vec<Arc<dyn LLMProvider>> = Vec::new();
        
        for (idx, pending) in batch.into_iter().enumerate() {
            let wait_time = pending.received_at.elapsed().as_millis() as u64;
            self.metrics.record_histogram("batch_wait_time", wait_time as f64);
            
            let result = match Self::process_single_request(pending.request, &providers).await {
                Ok(response) => {
                    pending.sender.send(Ok(response)).await.ok();
                    self.metrics.increment_counter("batch_request_success");
                }
                Err(e) => {
                    pending.sender.send(Err(e)).await.ok();
                    self.metrics.increment_counter("batch_request_failure");
                }
            };
        }
        
        let latency = start.elapsed().as_millis() as u64;
        self.metrics.record_histogram("batch_latency", latency as f64);
        debug!("Batch {} completed in {}ms", batch_id, latency);
    }

    async fn process_single_request(
        request: GatewayRequest,
        providers: &[Arc<dyn LLMProvider>],
    ) -> Result<GatewayResponse> {
        let start = Instant::now();
        
        let prompt = request.messages
            .iter()
            .map(|m| format!("{}: {}", m.role, m.content))
            .collect::<Vec<_>>()
            .join("\n");
        
        let inference_request = InferenceRequest {
            model: request.model.clone(),
            prompt,
            max_tokens: request.max_tokens.unwrap_or(1024),
            temperature: request.temperature.unwrap_or(0.7),
            top_p: 1.0,
            frequency_penalty: 0.0,
            presence_penalty: 0.0,
            stop_sequences: Vec::new(),
            stream: request.stream.unwrap_or(false),
            metadata: request.metadata.unwrap_or_default(),
        };

        if providers.is_empty() {
            return Err(GatewayError::Provider("No providers available for batch processing".to_string()));
        }

        let provider = &providers[0];
        let response = provider.complete(inference_request).await?;
        
        let latency_ms = start.elapsed().as_millis() as u64;
        
        Ok(GatewayResponse {
            response_id: response.response_id,
            model: response.model,
            content: response.content,
            usage: UsageInfo {
                prompt_tokens: response.prompt_tokens,
                completion_tokens: response.completion_tokens,
                total_tokens: response.total_tokens,
            },
            latency_ms,
            provider_id: response.provider_id,
            created_at: response.created_at,
        })
    }

    async fn start_background_worker(&self) {
        if self.is_running.load(std::sync::atomic::Ordering::Relaxed) {
            return;
        }
        
        self.is_running.store(true, std::sync::atomic::Ordering::Relaxed);
        
        let pending = Arc::clone(&self.pending);
        let config = self.config.clone();
        let metrics = self.metrics.clone();
        let is_running = Arc::clone(&self.is_running);
        
        tokio::spawn(async move {
            let mut ticker = interval(Duration::from_millis(config.max_wait_ms));
            
            while is_running.load(std::sync::atomic::Ordering::Relaxed) {
                ticker.tick().await;
                
                let batch_size = {
                    let pending = pending.lock().await;
                    pending.len()
                };
                
                if batch_size >= config.min_batch_size {
                    let batch: Vec<PendingRequest> = {
                        let mut pending = pending.lock().await;
                        let take_count = batch_size.min(config.max_batch_size);
                        pending.drain(..take_count).collect()
                    };
                    
                    if !batch.is_empty() {
                        let aggregator = BatchAggregator::new(config.clone(), metrics.clone());
                        aggregator.process_batch(batch).await;
                    }
                }
            }
            
            info!("Batch aggregator background worker stopped");
        });
        
        info!("Batch aggregator background worker started with max_wait={}ms, max_batch_size={}", 
              config.max_wait_ms, config.max_batch_size);
    }

    fn stop_background_worker(&self) {
        self.is_running.store(false, std::sync::atomic::Ordering::Relaxed);
    }

    async fn pending_count(&self) -> usize {
        self.pending.lock().await.len()
    }
}

pub struct BatchProcessor {
    aggregator: BatchAggregator,
    config: BatchConfig,
    metrics: MetricsCollector,
    total_batches: Arc<std::sync::atomic::AtomicU64>,
    total_requests: Arc<std::sync::atomic::AtomicU64>,
    requests_merged: Arc<std::sync::atomic::AtomicU64>,
}

impl BatchProcessor {
    pub fn new(config: BatchConfig, metrics: MetricsCollector) -> Self {
        let aggregator = BatchAggregator::new(config.clone(), metrics.clone());
        
        Self {
            aggregator,
            config,
            metrics,
            total_batches: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            total_requests: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            requests_merged: Arc::new(std::sync::atomic::AtomicU64::new(0)),
        }
    }

    pub async fn process_batch(&self, batch_request: BatchInferenceRequest) -> Result<BatchInferenceResponse> {
        let batch_id = batch_request.batch_id.clone().unwrap_or_else(|| 
            format!("batch_{}", Uuid::new_v4().simple())
        );
        let start = Instant::now();
        
        info!("Processing batch request {} with {} requests", batch_id, batch_request.requests.len());
        
        self.metrics.increment_counter("batch_request_received");
        self.total_batches.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.total_requests.fetch_add(batch_request.requests.len() as u64, std::sync::atomic::Ordering::Relaxed);
        
        let max_batch_size = batch_request.max_batch_size.unwrap_or(self.config.max_batch_size);
        let max_wait = batch_request.max_wait_ms.unwrap_or(self.config.max_wait_ms);
        
        let mut results: Vec<BatchItemResult> = Vec::new();
        
        for (idx, request) in batch_request.requests.into_iter().enumerate() {
            let item_start = Instant::now();
            
            let result = if self.config.enable_auto_batching {
                self.aggregator.submit(request).await
            } else {
                let providers: Vec<Arc<dyn LLMProvider>> = Vec::new();
                BatchAggregator::process_single_request(request, &providers).await
            };
            
            let latency_ms = item_start.elapsed().as_millis() as u64;
            
            match result {
                Ok(response) => {
                    results.push(BatchItemResult {
                        request_index: idx,
                        response: Some(response),
                        error: None,
                        latency_ms,
                    });
                }
                Err(e) => {
                    results.push(BatchItemResult {
                        request_index: idx,
                        response: None,
                        error: Some(e.to_string()),
                        latency_ms,
                    });
                }
            }
        }
        
        let successful = results.iter().filter(|r| r.response.is_some()).count();
        let failed = results.len() - successful;
        let processing_time_ms = start.elapsed().as_millis() as u64;
        
        if results.len() > 1 {
            self.requests_merged.fetch_add(results.len() as u64, std::sync::atomic::Ordering::Relaxed);
        }
        
        Ok(BatchInferenceResponse {
            batch_id,
            responses: results,
            total_requests: successful + failed,
            successful_requests: successful,
            failed_requests: failed,
            processing_time_ms,
            created_at: Utc::now(),
        })
    }

    pub async fn submit_single(&self, request: GatewayRequest) -> Result<GatewayResponse> {
        if !self.config.enable_auto_batching {
            return Err(GatewayError::Validation("Auto-batching is not enabled".to_string()));
        }
        
        self.aggregator.submit(request).await
    }

    pub async fn start_background_processor(&self) {
        if self.config.enable_auto_batching {
            self.aggregator.start_background_worker().await;
        }
    }

    pub fn stop_background_processor(&self) {
        self.aggregator.stop_background_worker();
    }

    pub async fn stats(&self) -> BatchStats {
        let total_batches = self.total_batches.load(std::sync::atomic::Ordering::Relaxed);
        let total_requests = self.total_requests.load(std::sync::atomic::Ordering::Relaxed);
        
        BatchStats {
            total_batches_processed: total_batches,
            total_requests_processed: total_requests,
            avg_batch_size: if total_batches > 0 {
                total_requests as f64 / total_batches as f64
            } else {
                0.0
            },
            avg_batch_latency_ms: 0.0,
            requests_merged: self.requests_merged.load(std::sync::atomic::Ordering::Relaxed),
            current_pending_batches: self.aggregator.pending_count().await,
        }
    }

    pub fn config(&self) -> &BatchConfig {
        &self.config
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_processor() -> BatchProcessor {
        let config = BatchConfig {
            enable_auto_batching: false,
            ..Default::default()
        };
        let metrics = MetricsCollector::new();
        BatchProcessor::new(config, metrics)
    }

    fn create_test_request(model: &str, content: &str) -> GatewayRequest {
        GatewayRequest {
            model: model.to_string(),
            messages: vec![
                ChatMessage {
                    role: "user".to_string(),
                    content: content.to_string(),
                }
            ],
            max_tokens: Some(100),
            temperature: Some(0.7),
            stream: Some(false),
            metadata: None,
            trace_id: None,
            user_id: None,
        }
    }

    #[tokio::test]
    async fn test_batch_config_default() {
        let config = BatchConfig::default();
        assert_eq!(config.max_batch_size, 32);
        assert_eq!(config.max_wait_ms, 100);
        assert!(config.enable_auto_batching);
    }

    #[tokio::test]
    async fn test_batch_processor_creation() {
        let processor = create_test_processor();
        assert!(!processor.config().enable_auto_batching);
        assert_eq!(processor.config().max_batch_size, 32);
    }

    #[tokio::test]
    async fn test_batch_stats_initial() {
        let processor = create_test_processor();
        let stats = processor.stats().await;
        
        assert_eq!(stats.total_batches_processed, 0);
        assert_eq!(stats.total_requests_processed, 0);
        assert_eq!(stats.avg_batch_size, 0.0);
    }

    #[tokio::test]
    async fn test_batch_inference_request_creation() {
        let requests = vec![
            create_test_request("gpt-3.5-turbo", "Hello"),
            create_test_request("gpt-3.5-turbo", "World"),
        ];
        
        let batch_request = BatchInferenceRequest {
            requests,
            max_wait_ms: Some(50),
            max_batch_size: Some(10),
            batch_id: Some("test_batch".to_string()),
        };
        
        assert_eq!(batch_request.requests.len(), 2);
        assert_eq!(batch_request.max_wait_ms, Some(50));
        assert_eq!(batch_request.max_batch_size, Some(10));
        assert_eq!(batch_request.batch_id, Some("test_batch".to_string()));
    }

    #[tokio::test]
    async fn test_batch_item_result_creation() {
        let result = BatchItemResult {
            request_index: 0,
            response: None,
            error: Some("Test error".to_string()),
            latency_ms: 100,
        };
        
        assert_eq!(result.request_index, 0);
        assert!(result.response.is_none());
        assert!(result.error.is_some());
        assert_eq!(result.latency_ms, 100);
    }

    #[tokio::test]
    async fn test_auto_batching_disabled_error() {
        let processor = create_test_processor();
        let request = create_test_request("gpt-3.5-turbo", "Hello");
        
        let result = processor.submit_single(request).await;
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), GatewayError::Validation(_)));
    }

    #[tokio::test]
    async fn test_batch_stats_after_request() {
        let processor = create_test_processor();
        let batch_request = BatchInferenceRequest {
            requests: vec![
                create_test_request("gpt-3.5-turbo", "Hello"),
            ],
            max_wait_ms: None,
            max_batch_size: None,
            batch_id: None,
        };
        
        let _ = processor.process_batch(batch_request).await;
        let stats = processor.stats().await;
        
        assert_eq!(stats.total_batches_processed, 1);
        assert_eq!(stats.total_requests_processed, 1);
    }
}
