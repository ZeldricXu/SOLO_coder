use crate::backend::{Backend, BackendRegistry, ModelHandle, Tensor};
use common::error::AppError;
use common::types::ModelFramework;
use dashmap::DashMap;
use histogram::{Histogram, HistogramConfig};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, Mutex, oneshot};
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
pub struct InferRequest {
    pub request_id: String,
    pub model_name: String,
    pub version: String,
    pub inputs: Vec<Tensor>,
    pub params: Option<HashMap<String, serde_json::Value>>,
    pub trace_id: Option<String>,
    pub user_id: Option<String>,
    pub timeout_ms: Option<u64>,
    pub priority: u8,
}

#[derive(Debug, Clone)]
pub struct InferResponse {
    pub request_id: String,
    pub model_name: String,
    pub version: String,
    pub outputs: Vec<Tensor>,
    pub latency_ms: u64,
    pub gpu_id: Option<i32>,
    pub trace_id: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchConfig {
    pub max_batch_size: usize,
    pub max_wait_ms: u64,
    pub max_queue_size: usize,
}

impl Default for BatchConfig {
    fn default() -> Self {
        Self {
            max_batch_size: 32,
            max_wait_ms: 10,
            max_queue_size: 1024,
        }
    }
}

pub struct PendingRequest {
    pub request: InferRequest,
    pub sender: oneshot::Sender<Result<InferResponse, AppError>>,
    pub received_at: Instant,
    pub priority: u8,
}

#[derive(Clone, Debug)]
pub struct ModelKey {
    pub name: String,
    pub version: String,
}

impl std::fmt::Display for ModelKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.name, self.version)
    }
}

impl std::cmp::PartialEq for ModelKey {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name && self.version == other.version
    }
}

impl std::cmp::Eq for ModelKey {}

impl std::hash::Hash for ModelKey {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.name.hash(state);
        self.version.hash(state);
    }
}

struct BatchAccumulator {
    model_key: ModelKey,
    pending: Vec<PendingRequest>,
}

impl BatchAccumulator {
    fn new(model_key: ModelKey) -> Self {
        Self {
            model_key,
            pending: Vec::new(),
        }
    }

    fn push(&mut self, req: PendingRequest, max_batch_size: usize) -> bool {
        if self.pending.len() >= max_batch_size {
            return false;
        }
        self.pending.push(req);
        true
    }

    fn is_empty(&self) -> bool {
        self.pending.is_empty()
    }

    fn len(&self) -> usize {
        self.pending.len()
    }

    fn oldest(&self) -> Option<Instant> {
        self.pending.first().map(|p| p.received_at)
    }
}

pub struct BatchStats {
    pub total_batches: AtomicU64,
    pub total_requests: AtomicU64,
    pub batch_size_histogram: StdMutex<Histogram>,
}

impl BatchStats {
    pub fn new() -> Self {
        Self {
            total_batches: AtomicU64::new(0),
            total_requests: AtomicU64::new(0),
            batch_size_histogram: StdMutex::new(
                Histogram::new(HistogramConfig {
                    precision: 4,
                    max_value: 1024,
                    max_memory: 0,
                }).expect("Failed to create histogram"),
            ),
        }
    }

    pub fn record_batch(&self, size: usize) {
        self.total_batches.fetch_add(1, Ordering::Relaxed);
        self.total_requests.fetch_add(size as u64, Ordering::Relaxed);
        if let Ok(mut h) = self.batch_size_histogram.lock() {
            let _ = h.increment(size as u64);
        }
    }

    pub fn summary(&self) -> BatchSummary {
        let mut summary = BatchSummary::default();
        summary.total_batches = self.total_batches.load(Ordering::Relaxed);
        summary.total_requests = self.total_requests.load(Ordering::Relaxed);
        if let Ok(mut h) = self.batch_size_histogram.lock() {
            summary.min_batch_size = h.percentile(0.0).unwrap_or(0) as usize;
            summary.max_batch_size = h.percentile(100.0).unwrap_or(0) as usize;
            summary.p50_batch_size = h.percentile(50.0).unwrap_or(0) as usize;
            summary.p95_batch_size = h.percentile(95.0).unwrap_or(0) as usize;
            summary.p99_batch_size = h.percentile(99.0).unwrap_or(0) as usize;
            let total = summary.total_batches;
            summary.mean_batch_size = if total > 0 {
                summary.total_requests as f64 / total as f64
            } else {
                0.0
            };
        }
        summary
    }
}

impl Default for BatchStats {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Default)]
pub struct BatchSummary {
    pub total_batches: u64,
    pub total_requests: u64,
    pub min_batch_size: usize,
    pub max_batch_size: usize,
    pub mean_batch_size: f64,
    pub p50_batch_size: usize,
    pub p95_batch_size: usize,
    pub p99_batch_size: usize,
}

struct BatcherState {
    accumulators: HashMap<ModelKey, BatchAccumulator>,
}

impl BatcherState {
    fn new() -> Self {
        Self {
            accumulators: HashMap::new(),
        }
    }

    fn insert(&mut self, req: PendingRequest, max_batch_size: usize) -> bool {
        let key = ModelKey {
            name: req.request.model_name.clone(),
            version: req.request.version.clone(),
        };
        let acc = self
            .accumulators
            .entry(key.clone())
            .or_insert_with(|| BatchAccumulator::new(key));
        acc.push(req, max_batch_size)
    }

    fn ready_batches(&mut self, max_batch_size: usize, max_wait: Duration) -> Vec<BatchAccumulator> {
        let now = Instant::now();
        let mut ready = Vec::new();
        let mut remaining = HashMap::new();

        for (key, acc) in self.accumulators.drain() {
            let reached_max = acc.len() >= max_batch_size;
            let timed_out = acc
                .oldest()
                .map(|t| now.duration_since(t) >= max_wait)
                .unwrap_or(false);

            if reached_max || timed_out {
                ready.push(acc);
            } else if !acc.is_empty() {
                remaining.insert(key, acc);
            }
        }

        self.accumulators = remaining;
        ready
    }

    fn drain_all(&mut self) -> Vec<BatchAccumulator> {
        self.accumulators
            .drain()
            .map(|(_, v)| v)
            .filter(|a| !a.is_empty())
            .collect()
    }
}

pub struct DynamicBatcher {
    config: BatchConfig,
    tx: mpsc::Sender<PendingRequest>,
    rx: Mutex<Option<mpsc::Receiver<PendingRequest>>>,
    state: Arc<Mutex<BatcherState>>,
    stats: Arc<BatchStats>,
    backends: Arc<BackendRegistry>,
    loaded_models: Arc<DashMap<ModelKey, LoadedModelRef>>,
    worker_handle: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

#[derive(Clone)]
pub struct LoadedModelRef {
    pub handle: ModelHandle,
    pub framework: ModelFramework,
    pub gpu_id: Option<i32>,
}

struct BatchExecutionResult {
    model_key: ModelKey,
    batch_size: usize,
    started_at: Instant,
    responses: Vec<(String, Result<InferResponse, AppError>)>,
}

impl DynamicBatcher {
    pub fn new(
        config: BatchConfig,
        backends: Arc<BackendRegistry>,
        loaded_models: Arc<DashMap<ModelKey, LoadedModelRef>>,
    ) -> Self {
        let (tx, rx) = mpsc::channel::<PendingRequest>(config.max_queue_size);
        let state = Arc::new(Mutex::new(BatcherState::new()));
        let stats = Arc::new(BatchStats::new());

        Self {
            config: config.clone(),
            tx,
            rx: Mutex::new(Some(rx)),
            state,
            stats,
            backends,
            loaded_models,
            worker_handle: Mutex::new(None),
        }
    }

    pub async fn start(&self) -> Result<(), AppError> {
        let mut handle = self.worker_handle.lock().await;
        if handle.is_some() {
            warn!("DynamicBatcher already started");
            return Ok(());
        }

        let mut rx_guard = self.rx.lock().await;
        let rx = rx_guard
            .take()
            .ok_or_else(|| AppError::Internal("DynamicBatcher receiver already consumed".into()))?;

        let state = self.state.clone();
        let stats = self.stats.clone();
        let backends = self.backends.clone();
        let loaded_models = self.loaded_models.clone();
        let config = self.config.clone();

        let worker_future = Self::run_worker(
            rx,
            state,
            stats,
            backends,
            loaded_models,
            config,
        );

        let join_handle = tokio::spawn(worker_future);
        *handle = Some(join_handle);
        info!("DynamicBatcher started successfully");
        Ok(())
    }

    pub fn stats(&self) -> &BatchStats {
        &self.stats
    }

    pub fn submit(
        &self,
        request: InferRequest,
    ) -> Result<oneshot::Receiver<Result<InferResponse, AppError>>, AppError> {
        let (tx, rx) = oneshot::channel();
        let priority = request.priority;
        let pending = PendingRequest {
            request,
            sender: tx,
            received_at: Instant::now(),
            priority,
        };

        self.tx
            .try_send(pending)
            .map_err(|e| match e {
                mpsc::error::TrySendError::Full(_) => AppError::ServiceUnavailable(
                    "Inference queue full, please retry later".into(),
                ),
                mpsc::error::TrySendError::Closed(_) => {
                    AppError::Internal("Batcher channel closed".into())
                }
            })?;

        Ok(rx)
    }

    async fn run_worker(
        mut rx: mpsc::Receiver<PendingRequest>,
        state: Arc<Mutex<BatcherState>>,
        stats: Arc<BatchStats>,
        backends: Arc<BackendRegistry>,
        loaded_models: Arc<DashMap<ModelKey, LoadedModelRef>>,
        config: BatchConfig,
    ) {
        info!(
            "DynamicBatcher worker started: max_batch={}, max_wait={}ms",
            config.max_batch_size, config.max_wait_ms
        );

        let max_wait = Duration::from_millis(config.max_wait_ms);

        loop {
            let mut has_pending = false;

            {
                let mut state_guard = state.lock().await;

                while let Ok(req) = rx.try_recv() {
                    let req_id = req.request.request_id.clone();
                    if !state_guard.insert(req, config.max_batch_size) {
                        warn!("Queue insertion failed for request {}, dropping", req_id);
                    }
                    has_pending = true;
                }

                let ready = state_guard.ready_batches(config.max_batch_size, max_wait);
                for batch in ready {
                    let batch_size = batch.len();
                    let model_key = batch.model_key.clone();
                    stats.record_batch(batch_size);

                    let backends_clone = backends.clone();
                    let loaded_models_clone = loaded_models.clone();
                    let stats_clone = stats.clone();
                    tokio::spawn(async move {
                        Self::execute_batch(
                            batch,
                            model_key,
                            backends_clone,
                            loaded_models_clone,
                            stats_clone,
                        )
                        .await;
                    });
                }
            }

            if has_pending {
                tokio::time::sleep(Duration::from_millis(1)).await;
            } else {
                tokio::time::sleep(Duration::from_millis(config.max_wait_ms.max(1) / 4)).await;
            }
        }
    }

    async fn execute_batch(
        batch: BatchAccumulator,
        model_key: ModelKey,
        backends: Arc<BackendRegistry>,
        loaded_models: Arc<DashMap<ModelKey, LoadedModelRef>>,
        _stats: Arc<BatchStats>,
    ) {
        let batch_size = batch.pending.len();
        let started_at = Instant::now();
        debug!(
            "Executing batch for model={}, size={}",
            model_key, batch_size
        );

        let model_ref = match loaded_models.get(&model_key) {
            Some(m) => m.clone(),
            None => {
                let err = AppError::ModelNotOnline(format!("Model {} not loaded", model_key));
                Self::dispatch_errors(batch.pending, err);
                return;
            }
        };

        let backend = match backends.get(model_ref.framework) {
            Ok(b) => b,
            Err(e) => {
                Self::dispatch_errors(batch.pending, e);
                return;
            }
        };

        let merged_tensors = match Self::merge_tensors(&batch.pending) {
            Ok(t) => t,
            Err(e) => {
                Self::dispatch_errors(batch.pending, e);
                return;
            }
        };

        let inference_started = Instant::now();
        let outputs = match backend.infer(&model_ref.handle, merged_tensors).await {
            Ok(o) => o,
            Err(e) => {
                Self::dispatch_errors(batch.pending, e);
                return;
            }
        };
        let inference_latency = inference_started.elapsed();

        let results = match Self::split_outputs(
            outputs,
            batch_size,
            &batch.pending,
            &model_key,
            model_ref.gpu_id,
            inference_latency,
        ) {
            Ok(r) => r,
            Err(e) => {
                Self::dispatch_errors(batch.pending, e);
                return;
            }
        };

        for (pending, result) in batch.pending.into_iter().zip(results.into_iter()) {
            let _ = pending.sender.send(Ok(result));
        }

        let total_latency = started_at.elapsed();
        debug!(
            "Batch completed: model={}, size={}, total={}ms, inference={}ms",
            model_key,
            batch_size,
            total_latency.as_millis(),
            inference_latency.as_millis()
        );
    }

    fn merge_tensors(pending: &[PendingRequest]) -> Result<Vec<Tensor>, AppError> {
        if pending.is_empty() {
            return Err(AppError::InferenceError("Empty batch".into()));
        }

        let batch_size = pending.len();
        let first_inputs = &pending[0].request.inputs;
        let num_tensors = first_inputs.len();

        let mut merged = Vec::with_capacity(num_tensors);

        for tensor_idx in 0..num_tensors {
            let first = &first_inputs[tensor_idx];
            let name = first.name.clone();
            let dtype = first.dtype.clone();

            let mut batch_shape = Vec::with_capacity(first.shape.len() + 1);
            batch_shape.push(batch_size as i64);
            batch_shape.extend_from_slice(&first.shape[1..]);

            let mut data: Vec<u8> = Vec::new();
            let element_size = first.data.len() / first.shape[0].max(1) as usize;

            for req in pending {
                if tensor_idx >= req.request.inputs.len() {
                    return Err(AppError::InferenceError(format!(
                        "Input tensor count mismatch: expected {}, got {}",
                        num_tensors,
                        req.request.inputs.len()
                    )));
                }
                let t = &req.request.inputs[tensor_idx];
                if t.dtype != dtype {
                    return Err(AppError::InferenceError(format!(
                        "Dtype mismatch for tensor {}: expected {}, got {}",
                        name, dtype, t.dtype
                    )));
                }
                data.extend_from_slice(&t.data);
            }

            merged.push(Tensor::new(name, dtype, batch_shape, data));
        }

        Ok(merged)
    }

    fn split_outputs(
        outputs: Vec<Tensor>,
        batch_size: usize,
        pending: &[PendingRequest],
        model_key: &ModelKey,
        gpu_id: Option<i32>,
        inference_latency: Duration,
    ) -> Result<Vec<InferResponse>, AppError> {
        if batch_size == 0 {
            return Err(AppError::InferenceError("Batch size is zero".into()));
        }

        let mut responses = Vec::with_capacity(batch_size);

        for sample_idx in 0..batch_size {
            let req = &pending[sample_idx].request;
            let mut sample_outputs = Vec::with_capacity(outputs.len());

            for output in &outputs {
                let mut sample_shape = Vec::with_capacity(output.shape.len() - 1);
                sample_shape.push(1);
                sample_shape.extend_from_slice(&output.shape[1..]);

                let elements_per_sample = if !output.shape.is_empty() && output.shape[0] > 0 {
                    output.data.len() / output.shape[0] as usize
                } else {
                    output.data.len()
                };

                let start = sample_idx * elements_per_sample;
                let end = start + elements_per_sample;
                let sample_data = if end <= output.data.len() {
                    output.data[start..end].to_vec()
                } else {
                    return Err(AppError::InferenceError(format!(
                        "Output tensor data out of bounds for sample {}: start={}, end={}, len={}",
                        sample_idx, start, end, output.data.len()
                    )));
                };

                sample_outputs.push(Tensor::new(
                    output.name.clone(),
                    output.dtype.clone(),
                    sample_shape,
                    sample_data,
                ));
            }

            responses.push(InferResponse {
                request_id: req.request_id.clone(),
                model_name: model_key.name.clone(),
                version: model_key.version.clone(),
                outputs: sample_outputs,
                latency_ms: inference_latency.as_millis() as u64,
                gpu_id,
                trace_id: req.trace_id.clone(),
                error: None,
            });
        }

        Ok(responses)
    }

    fn dispatch_errors(pending: Vec<PendingRequest>, err: AppError) {
        let err_str = err.to_string();
        for p in pending {
            let _ = p.sender.send(Err(AppError::InferenceError(err_str.clone())));
        }
    }

    pub async fn shutdown(&self) {
        let mut handle = self.worker_handle.lock().await;
        if let Some(h) = handle.take() {
            h.abort();
            info!("DynamicBatcher worker stopped");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::{BackendRegistry, MockBackend, Tensor};
    use common::types::ModelFramework;
    use dashmap::DashMap;

    #[tokio::test]
    async fn test_tensor_merge_and_split() {
        let tensors1 = vec![Tensor::from_f32("input0", vec![1, 2], &[1.0, 2.0])];
        let tensors2 = vec![Tensor::from_f32("input0", vec![1, 2], &[3.0, 4.0])];
        let tensors3 = vec![Tensor::from_f32("input0", vec![1, 2], &[5.0, 6.0])];

        let (tx1, _) = oneshot::channel();
        let (tx2, _) = oneshot::channel();
        let (tx3, _) = oneshot::channel();

        let pending = vec![
            PendingRequest {
                request: InferRequest {
                    request_id: "1".into(),
                    model_name: "test".into(),
                    version: "v1".into(),
                    inputs: tensors1,
                    params: None,
                    trace_id: None,
                    user_id: None,
                    timeout_ms: None,
                    priority: 0,
                },
                sender: tx1,
                received_at: Instant::now(),
                priority: 0,
            },
            PendingRequest {
                request: InferRequest {
                    request_id: "2".into(),
                    model_name: "test".into(),
                    version: "v1".into(),
                    inputs: tensors2,
                    params: None,
                    trace_id: None,
                    user_id: None,
                    timeout_ms: None,
                    priority: 0,
                },
                sender: tx2,
                received_at: Instant::now(),
                priority: 0,
            },
            PendingRequest {
                request: InferRequest {
                    request_id: "3".into(),
                    model_name: "test".into(),
                    version: "v1".into(),
                    inputs: tensors3,
                    params: None,
                    trace_id: None,
                    user_id: None,
                    timeout_ms: None,
                    priority: 0,
                },
                sender: tx3,
                received_at: Instant::now(),
                priority: 0,
            },
        ];

        let merged = DynamicBatcher::merge_tensors(&pending).unwrap();
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].shape, vec![3, 2]);
        assert_eq!(merged[0].data.len(), 3 * 2 * 4);

        let merged_data = merged[0].to_f32().unwrap();
        assert_eq!(merged_data, vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);

        let output = Tensor::from_f32("output0", vec![3, 5], &[
            0.1, 0.2, 0.3, 0.4, 0.5,
            0.6, 0.7, 0.8, 0.9, 1.0,
            1.1, 1.2, 1.3, 1.4, 1.5,
        ]);

        let model_key = ModelKey { name: "test".into(), version: "v1".into() };
        let split = DynamicBatcher::split_outputs(
            vec![output],
            3,
            &pending,
            &model_key,
            Some(0),
            Duration::from_millis(42),
        )
        .unwrap();

        assert_eq!(split.len(), 3);
        assert_eq!(split[0].request_id, "1");
        assert_eq!(split[0].latency_ms, 42);
        assert_eq!(split[0].outputs[0].shape, vec![1, 5]);
        assert_eq!(split[0].outputs[0].to_f32().unwrap(), vec![0.1, 0.2, 0.3, 0.4, 0.5]);
        assert_eq!(split[2].outputs[0].to_f32().unwrap(), vec![1.1, 1.2, 1.3, 1.4, 1.5]);
    }

    #[tokio::test]
    async fn test_batch_stats() {
        let stats = BatchStats::new();
        stats.record_batch(4);
        stats.record_batch(8);
        stats.record_batch(16);
        stats.record_batch(32);

        let summary = stats.summary();
        assert_eq!(summary.total_batches, 4);
        assert_eq!(summary.total_requests, 60);
        assert_eq!(summary.min_batch_size, 4);
        assert_eq!(summary.max_batch_size, 32);
    }
}
