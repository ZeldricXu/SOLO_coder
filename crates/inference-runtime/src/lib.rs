pub mod backend;
pub mod batch;
pub mod gpu;
pub mod pipeline;

pub mod google {
    pub mod protobuf {
        include!("google.protobuf.rs");
    }
}

pub mod inference {
    pub mod v1 {
        #![allow(clippy::large_enum_variant)]
        #![allow(dead_code)]
        include!("inference.v1.rs");
    }
}

pub mod pb {
    pub use super::inference::v1::*;
    pub use super::inference::v1::{
        inference_service_server, runtime_service_server,
    };
}

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::Result;
use common::error::AppError;
use common::types::{ModelFramework, ModelVersion};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::{mpsc, Semaphore};
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status, Streaming};
use tracing::{debug, info, instrument, warn};
use uuid::Uuid;

use crate::backend::{BackendFactory, BackendRegistry, ModelHandle, RuntimeBackend, Tensor};
use crate::batch::{BatchConfig, DynamicBatcher, InferRequest as BatchInferRequest, LoadedModelRef, ModelKey};
use crate::gpu::{GpuManager, GpuStats};
pub use crate::pipeline::{InferencePipeline, PipelineConfig, PipelineModelExecutor};
use crate::pb::{inference_service_server, runtime_service_server, DataType, ModelStatus, Tensor as PbTensor};

#[derive(Debug, Clone)]
pub struct LoadedModel {
    pub version_id: Uuid,
    pub model_name: String,
    pub version: String,
    pub gpu_id: Option<i32>,
    pub gpu_memory_mb: u64,
    pub framework: ModelFramework,
    pub loaded_at: chrono::DateTime<chrono::Utc>,
    pub status: ModelStatus,
    pub handle: Arc<ModelHandle>,
    pub pipeline: Arc<InferencePipeline>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackendConfig {
    pub backend_type: String,
    pub num_threads: usize,
    pub enable_optimization: bool,
}

impl Default for BackendConfig {
    fn default() -> Self {
        Self {
            backend_type: "onnxrt".to_string(),
            num_threads: 4,
            enable_optimization: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeConfig {
    pub backend: BackendConfig,
    pub batch: BatchConfig,
    pub default_concurrency: usize,
    pub num_gpus: usize,
    pub models_dir: PathBuf,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            backend: BackendConfig::default(),
            batch: BatchConfig::default(),
            default_concurrency: 64,
            num_gpus: 1,
            models_dir: PathBuf::from("/data/models"),
        }
    }
}

fn dtype_to_string(dt: i32) -> String {
    match DataType::try_from(dt).unwrap_or(DataType::Unspecified) {
        DataType::Fp32 => "float32".to_string(),
        DataType::Fp64 => "float64".to_string(),
        DataType::Int32 => "int32".to_string(),
        DataType::Int64 => "int64".to_string(),
        DataType::Uint8 => "uint8".to_string(),
        DataType::Bool => "bool".to_string(),
        DataType::String => "string".to_string(),
        _ => "float32".to_string(),
    }
}

fn string_to_dtype(s: &str) -> DataType {
    match s.to_lowercase().as_str() {
        "float32" | "fp32" | "float" => DataType::Fp32,
        "float64" | "fp64" | "double" => DataType::Fp64,
        "int32" => DataType::Int32,
        "int64" => DataType::Int64,
        "uint8" => DataType::Uint8,
        "bool" => DataType::Bool,
        "string" => DataType::String,
        _ => DataType::Fp32,
    }
}

fn pb_tensor_to_tensor(pb: &PbTensor) -> Tensor {
    Tensor::new(
        pb.name.clone(),
        dtype_to_string(pb.dtype),
        pb.shape.clone(),
        pb.data_bytes.clone().into(),
    )
}

fn tensor_to_pb_tensor(t: &Tensor) -> PbTensor {
    PbTensor {
        name: t.name.clone(),
        dtype: string_to_dtype(&t.dtype) as i32,
        shape: t.shape.clone(),
        data_bytes: t.data.clone().into(),
    }
}

fn chrono_to_pb_timestamp(dt: chrono::DateTime<chrono::Utc>) -> crate::google::protobuf::Timestamp {
    crate::google::protobuf::Timestamp {
        seconds: dt.timestamp(),
        nanos: dt.timestamp_subsec_nanos() as i32,
    }
}

fn now_pb_timestamp() -> crate::google::protobuf::Timestamp {
    chrono_to_pb_timestamp(chrono::Utc::now())
}

pub struct InferenceRuntime {
    config: RuntimeConfig,
    backend_registry: Arc<BackendRegistry>,
    backends: Arc<DashMap<ModelFramework, Box<dyn RuntimeBackend>>>,
    loaded_models: Arc<DashMap<ModelKey, LoadedModelRef>>,
    model_info: Arc<DashMap<ModelKey, LoadedModel>>,
    batcher: Arc<DynamicBatcher>,
    gpu_manager: Arc<GpuManager>,
    concurrency_limits: Arc<DashMap<ModelKey, Arc<Semaphore>>>,
    pipelines: Arc<DashMap<ModelKey, Arc<InferencePipeline>>>,
}

impl InferenceRuntime {
    pub fn new(config: RuntimeConfig) -> Self {
        let backend_registry = Arc::new(BackendRegistry::default());
        let loaded_models = Arc::new(DashMap::new());
        let batcher = Arc::new(DynamicBatcher::new(
            config.batch.clone(),
            backend_registry.clone(),
            loaded_models.clone(),
        ));
        let gpu_manager = Arc::new(GpuManager::mock_with_count(config.num_gpus.max(1)));

        Self {
            config,
            backend_registry,
            backends: Arc::new(DashMap::new()),
            loaded_models,
            model_info: Arc::new(DashMap::new()),
            batcher,
            gpu_manager,
            concurrency_limits: Arc::new(DashMap::new()),
            pipelines: Arc::new(DashMap::new()),
        }
    }

    pub async fn start(&self) -> Result<(), AppError> {
        info!("Starting InferenceRuntime...");
        self.batcher.start().await?;
        self.gpu_manager.start_monitor(10).await;
        info!("InferenceRuntime started successfully");
        Ok(())
    }

    pub fn backend_registry(&self) -> &Arc<BackendRegistry> {
        &self.backend_registry
    }

    pub fn gpu_manager(&self) -> &Arc<GpuManager> {
        &self.gpu_manager
    }

    pub fn batcher(&self) -> &Arc<DynamicBatcher> {
        &self.batcher
    }

    pub fn get_gpu_stats(&self) -> Vec<GpuStats> {
        self.gpu_manager.get_gpu_stats()
    }

    fn get_semaphore(&self, key: ModelKey) -> Arc<Semaphore> {
        if let Some(sem) = self.concurrency_limits.get(&key) {
            return sem.value().clone();
        }
        let sem = Arc::new(Semaphore::new(self.config.default_concurrency));
        self.concurrency_limits.insert(key.clone(), sem.clone());
        sem
    }

    fn get_or_create_backend(&self, framework: ModelFramework) -> Result<Box<dyn RuntimeBackend>, AppError> {
        BackendFactory::create_from_framework(framework)
    }

    #[instrument(skip(self, version), fields(model_name = %version.model_id, version = %version.version))]
    pub async fn load_model(
        &self,
        version: &ModelVersion,
        model_name: &str,
        model_path: Option<PathBuf>,
        pipeline_config: Option<PipelineConfig>,
        gpu_id: Option<i32>,
    ) -> Result<LoadedModel, AppError> {
        let key = ModelKey {
            name: model_name.to_string(),
            version: version.version.clone(),
        };

        if self.model_info.contains_key(&key) {
            warn!("Model {} already loaded, returning existing", key);
            return Ok(self.model_info.get(&key).unwrap().value().clone());
        }

        info!("Loading model {} (framework={:?}, gpu_mem={}MB)", key, version.framework, version.gpu_memory_mb);

        let resolved_gpu_id = match gpu_id {
            Some(id) => Some(id),
            None => self.gpu_manager.select_gpu(version.gpu_memory_mb).map(|d| d.id as i32),
        };

        if let Some(gid) = resolved_gpu_id {
            self.gpu_manager.allocate_memory(gid as usize, version.gpu_memory_mb)?;
        }

        let path = model_path.unwrap_or_else(|| {
            self.config.models_dir
                .join(model_name)
                .join(&version.version)
                .join("model.onnx")
        });

        let backend = self.get_or_create_backend(version.framework)?;
        let handle = backend.load(&path, &version.version, resolved_gpu_id).await?;
        let handle_arc = Arc::new(handle);

        let pipeline = if let Some(pc) = pipeline_config {
            Arc::new(InferencePipeline::from_config(pc, None)?)
        } else {
            Arc::new(InferencePipeline::empty())
        };

        let loaded = LoadedModel {
            version_id: version.id,
            model_name: model_name.to_string(),
            version: version.version.clone(),
            gpu_id: resolved_gpu_id,
            gpu_memory_mb: version.gpu_memory_mb,
            framework: version.framework,
            loaded_at: chrono::Utc::now(),
            status: ModelStatus::Loaded,
            handle: handle_arc.clone(),
            pipeline: pipeline.clone(),
        };

        let loaded_ref = LoadedModelRef {
            handle: (*handle_arc).clone(),
            framework: version.framework,
            gpu_id: resolved_gpu_id,
        };

        self.loaded_models.insert(key.clone(), loaded_ref);
        self.model_info.insert(key.clone(), loaded.clone());
        self.pipelines.insert(key, pipeline);

        info!("Model {} loaded successfully on GPU {:?}", loaded.version_id, loaded.gpu_id);
        Ok(loaded)
    }

    #[instrument(skip(self), fields(model_name = %model_name, version = %version))]
    pub async fn unload_model(&self, model_name: &str, version: &str) -> Result<(), AppError> {
        let key = ModelKey {
            name: model_name.to_string(),
            version: version.to_string(),
        };

        let (_, loaded) = self.model_info.remove(&key)
            .ok_or_else(|| AppError::ModelNotOnline(format!("Model {} not loaded", key)))?;

        self.loaded_models.remove(&key);
        self.pipelines.remove(&key);
        self.concurrency_limits.remove(&key);

        if let Some(gid) = loaded.gpu_id {
            let _ = self.gpu_manager.release_memory(gid as usize, loaded.gpu_memory_mb);
        }

        info!("Model {} unloaded successfully", key);
        Ok(())
    }

    #[instrument(skip(self), fields(model_name = %model_name, version = %version))]
    pub async fn warmup_model(
        &self,
        model_name: &str,
        version: &str,
        iterations: u32,
    ) -> Result<(u64, u64, u64), AppError> {
        let key = ModelKey {
            name: model_name.to_string(),
            version: version.to_string(),
        };

        let loaded = self.model_info.get(&key)
            .ok_or_else(|| AppError::ModelNotOnline(format!("Model {} not loaded", key)))?
            .value()
            .clone();

        let backend = self.get_or_create_backend(loaded.framework)?;
        let mut latencies: Vec<u64> = Vec::with_capacity(iterations as usize);

        info!("Warming up model {} with {} iterations", key, iterations);

        for i in 0..iterations {
            let dummy_inputs = vec![Tensor::from_f32(
                "input_0",
                vec![1, 10],
                &(0..10).map(|v| v as f32 * 0.1).collect::<Vec<_>>(),
            )];

            let start = Instant::now();
            let _ = backend.infer(&loaded.handle, dummy_inputs).await?;
            let elapsed = start.elapsed().as_millis() as u64;
            latencies.push(elapsed);

            debug!("Warmup iteration {}/{}: {}ms", i + 1, iterations, elapsed);
        }

        let min = *latencies.iter().min().unwrap_or(&0);
        let max = *latencies.iter().max().unwrap_or(&0);
        let avg = latencies.iter().sum::<u64>() / latencies.len().max(1) as u64;

        info!("Warmup completed: min={}ms, avg={}ms, max={}ms", min, avg, max);
        Ok((min, avg, max))
    }

    #[instrument(skip(self, request), fields(request_id = %request.request_id, model_name = %request.model_name, version = %request.version))]
    pub async fn infer(
        &self,
        request: pb::InferRequest,
    ) -> Result<pb::InferResponse, AppError> {
        let key = ModelKey {
            name: request.model_name.clone(),
            version: request.version.clone(),
        };

        if !self.model_info.contains_key(&key) {
            return Err(AppError::ModelNotOnline(format!("Model {} not loaded", key)));
        }

        let sem = self.get_semaphore(key.clone());
        let permit = sem
            .clone()
            .try_acquire_owned()
            .map_err(|_| AppError::ServiceUnavailable("Inference concurrency limit exceeded".to_string()))?;

        let started_at = Instant::now();
        let started_at_pb = now_pb_timestamp();

        let inputs: Vec<Tensor> = request.inputs.iter().map(pb_tensor_to_tensor).collect();
        let timeout = if request.timeout_ms > 0 {
            request.timeout_ms as u64
        } else {
            30000
        };

        let batch_req = BatchInferRequest {
            request_id: request.request_id.clone(),
            model_name: request.model_name.clone(),
            version: request.version.clone(),
            inputs,
            params: None,
            trace_id: if request.trace_id.is_empty() { None } else { Some(request.trace_id.clone()) },
            user_id: if request.user_id.is_empty() { None } else { Some(request.user_id.clone()) },
            timeout_ms: Some(timeout),
            priority: request.priority as u8,
        };

        let rx = self.batcher.submit(batch_req)?;
        let result = match tokio::time::timeout(Duration::from_millis(timeout), rx).await {
            Ok(Ok(Ok(resp))) => resp,
            Ok(Ok(Err(e))) => return Err(e),
            Ok(Err(_)) => return Err(AppError::Internal("Response channel closed".into())),
            Err(_) => return Err(AppError::InferenceTimeout(timeout)),
        };

        drop(permit);

        let elapsed = started_at.elapsed().as_millis() as u64;

        let outputs: Vec<PbTensor> = result.outputs.iter().map(tensor_to_pb_tensor).collect();

        Ok(pb::InferResponse {
            request_id: result.request_id,
            outputs,
            latency_ms: elapsed as i64,
            gpu_id: result.gpu_id.unwrap_or(-1),
            trace_id: result.trace_id.unwrap_or_default(),
            error: result.error.unwrap_or_default(),
            model_name: result.model_name,
            version: result.version,
            started_at: Some(started_at_pb),
            completed_at: Some(now_pb_timestamp()),
        })
    }

    #[instrument(skip(self, requests), fields(batch_size = %requests.len()))]
    pub async fn batch_infer(
        &self,
        requests: Vec<pb::InferRequest>,
        max_batch_size: Option<i64>,
        max_wait_ms: Option<i64>,
    ) -> Result<(Vec<pb::InferResponse>, i64, i32), AppError> {
        let started = Instant::now();
        let batch_size = requests.len();

        info!("Processing batch inference request: {} items", batch_size);

        let mut results = Vec::with_capacity(batch_size);
        for req in requests {
            let resp = self.infer(req).await?;
            results.push(resp);
        }

        let total = started.elapsed().as_millis() as i64;
        Ok((results, total, batch_size as i32))
    }

    pub fn is_model_loaded(&self, model_name: &str, version: &str) -> bool {
        let key = ModelKey {
            name: model_name.to_string(),
            version: version.to_string(),
        };
        self.model_info.contains_key(&key)
    }

    pub fn get_model_status(&self, model_name: &str, version: &str) -> Option<LoadedModel> {
        let key = ModelKey {
            name: model_name.to_string(),
            version: version.to_string(),
        };
        self.model_info.get(&key).map(|r| r.value().clone())
    }

    pub fn list_loaded_models(&self) -> Vec<LoadedModel> {
        self.model_info.iter().map(|r| r.value().clone()).collect()
    }
}

impl Default for InferenceRuntime {
    fn default() -> Self {
        Self::new(RuntimeConfig::default())
    }
}

pub struct InferenceServiceImpl {
    runtime: Arc<InferenceRuntime>,
}

impl InferenceServiceImpl {
    pub fn new(runtime: Arc<InferenceRuntime>) -> Self {
        Self { runtime }
    }
}

#[tonic::async_trait]
impl inference_service_server::InferenceService for InferenceServiceImpl {
    async fn infer(
        &self,
        request: Request<pb::InferRequest>,
    ) -> Result<Response<pb::InferResponse>, Status> {
        let req = request.into_inner();
        let resp = self.runtime.infer(req).await.map_err(|e| Status::internal(e.to_string()))?;
        Ok(Response::new(resp))
    }

    type StreamInferStream = ReceiverStream<Result<pb::InferResponse, Status>>;

    async fn stream_infer(
        &self,
        request: Request<Streaming<pb::InferRequest>>,
    ) -> Result<Response<Self::StreamInferStream>, Status> {
        let mut in_stream = request.into_inner();
        let (tx, rx) = mpsc::channel(128);
        let runtime = self.runtime.clone();

        tokio::spawn(async move {
            while let Some(req_result) = in_stream.message().await.transpose() {
                match req_result {
                    Ok(req) => {
                        let resp = runtime.infer(req).await.map_err(|e| Status::internal(e.to_string()));
                        if tx.send(resp).await.is_err() {
                            break;
                        }
                    }
                    Err(e) => {
                        let _ = tx.send(Err(Status::internal(e.to_string()))).await;
                        break;
                    }
                }
            }
        });

        Ok(Response::new(ReceiverStream::new(rx)))
    }

    async fn model_status(
        &self,
        request: Request<pb::ModelStatusRequest>,
    ) -> Result<Response<pb::ModelStatusResponse>, Status> {
        let req = request.into_inner();
        let model = self.runtime.get_model_status(&req.model_name, &req.version);

        let resp = match model {
            Some(m) => {
                let total_latency = 0i64;
                let p99 = 0i64;
                pb::ModelStatusResponse {
                    model_name: m.model_name.clone(),
                    version: m.version.clone(),
                    status: m.status as i32,
                    active_instances: 1,
                    total_requests: 0,
                    avg_latency_ms: total_latency,
                    p99_latency_ms: p99,
                    memory_usage_mb: m.gpu_memory_mb as i64,
                    gpu_id: m.gpu_id.unwrap_or(-1),
                    error: String::new(),
                    last_heartbeat: Some(chrono_to_pb_timestamp(m.loaded_at)),
                }
            }
            None => pb::ModelStatusResponse {
                model_name: req.model_name,
                version: req.version,
                status: ModelStatus::Unloaded as i32,
                active_instances: 0,
                total_requests: 0,
                avg_latency_ms: 0,
                p99_latency_ms: 0,
                memory_usage_mb: 0,
                gpu_id: -1,
                error: "Model not loaded".to_string(),
                last_heartbeat: None,
            },
        };

        Ok(Response::new(resp))
    }
}

pub struct RuntimeServiceImpl {
    runtime: Arc<InferenceRuntime>,
}

impl RuntimeServiceImpl {
    pub fn new(runtime: Arc<InferenceRuntime>) -> Self {
        Self { runtime }
    }
}

#[tonic::async_trait]
impl runtime_service_server::RuntimeService for RuntimeServiceImpl {
    async fn load_model(
        &self,
        request: Request<pb::LoadModelRequest>,
    ) -> Result<Response<pb::LoadModelResponse>, Status> {
        let req = request.into_inner();
        let started = Instant::now();

        let framework = match req.backend() {
            pb::RuntimeBackend::Onnxrt => ModelFramework::Onnx,
            pb::RuntimeBackend::Tensorrt => ModelFramework::TensorRT,
            pb::RuntimeBackend::Torch => ModelFramework::Pytorch,
            _ => ModelFramework::Onnx,
        };

        let model_version = ModelVersion {
            id: Uuid::new_v4(),
            model_id: Uuid::new_v4(),
            version: req.version.clone(),
            framework,
            status: common::types::ModelStatus::Online,
            input_schema: vec![],
            output_schema: vec![],
            gpu_memory_mb: 1024,
            created_at: chrono::Utc::now(),
        };

        let model_path = if req.model_path.is_empty() {
            None
        } else {
            Some(PathBuf::from(&req.model_path))
        };

        let gpu_id = if req.gpu_ids.is_empty() {
            None
        } else {
            Some(req.gpu_ids[0])
        };

        match self.runtime.load_model(&model_version, &req.model_name, model_path, None, gpu_id).await {
            Ok(_) => {
                let load_time = started.elapsed().as_millis() as i64;
                Ok(Response::new(pb::LoadModelResponse {
                    model_name: req.model_name,
                    version: req.version,
                    status: ModelStatus::Ready as i32,
                    load_time_ms: load_time,
                    error: String::new(),
                }))
            }
            Err(e) => Ok(Response::new(pb::LoadModelResponse {
                model_name: req.model_name,
                version: req.version,
                status: ModelStatus::Error as i32,
                load_time_ms: 0,
                error: e.to_string(),
            })),
        }
    }

    async fn unload_model(
        &self,
        request: Request<pb::UnloadModelRequest>,
    ) -> Result<Response<pb::UnloadModelResponse>, Status> {
        let req = request.into_inner();

        match self.runtime.unload_model(&req.model_name, &req.version).await {
            Ok(()) => Ok(Response::new(pb::UnloadModelResponse {
                model_name: req.model_name,
                version: req.version,
                status: ModelStatus::Unloaded as i32,
                error: String::new(),
            })),
            Err(e) => Ok(Response::new(pb::UnloadModelResponse {
                model_name: req.model_name,
                version: req.version,
                status: ModelStatus::Error as i32,
                error: e.to_string(),
            })),
        }
    }

    async fn warmup_model(
        &self,
        request: Request<pb::WarmupModelRequest>,
    ) -> Result<Response<pb::WarmupModelResponse>, Status> {
        let req = request.into_inner();
        let iterations = req.num_iterations.max(1) as u32;

        match self.runtime.warmup_model(&req.model_name, &req.version, iterations).await {
            Ok((min, avg, max)) => Ok(Response::new(pb::WarmupModelResponse {
                model_name: req.model_name,
                version: req.version,
                iterations_completed: iterations as i32,
                avg_latency_ms: avg as i64,
                min_latency_ms: min as i64,
                max_latency_ms: max as i64,
                error: String::new(),
            })),
            Err(e) => Ok(Response::new(pb::WarmupModelResponse {
                model_name: req.model_name,
                version: req.version,
                iterations_completed: 0,
                avg_latency_ms: 0,
                min_latency_ms: 0,
                max_latency_ms: 0,
                error: e.to_string(),
            })),
        }
    }

    async fn batch_infer(
        &self,
        request: Request<pb::BatchInferRequest>,
    ) -> Result<Response<pb::BatchInferResponse>, Status> {
        let req = request.into_inner();

        match self.runtime.batch_infer(req.requests, Some(req.max_batch_size), Some(req.max_wait_ms)).await {
            Ok((responses, total_latency, actual_batch_size)) => Ok(Response::new(pb::BatchInferResponse {
                responses,
                total_latency_ms: total_latency,
                batch_size: actual_batch_size,
            })),
            Err(e) => Err(Status::internal(e.to_string())),
        }
    }
}

pub async fn init_runtime(config: Option<RuntimeConfig>) -> Result<Arc<InferenceRuntime>, AppError> {
    let runtime = Arc::new(InferenceRuntime::new(config.unwrap_or_default()));
    runtime.start().await?;
    info!("Inference runtime initialized successfully");
    Ok(runtime)
}

pub fn create_inference_service(
    runtime: Arc<InferenceRuntime>,
) -> inference_service_server::InferenceServiceServer<InferenceServiceImpl> {
    inference_service_server::InferenceServiceServer::new(InferenceServiceImpl::new(runtime))
}

pub fn create_runtime_service(
    runtime: Arc<InferenceRuntime>,
) -> runtime_service_server::RuntimeServiceServer<RuntimeServiceImpl> {
    runtime_service_server::RuntimeServiceServer::new(RuntimeServiceImpl::new(runtime))
}

#[async_trait::async_trait]
impl PipelineModelExecutor for InferenceRuntime {
    async fn execute_model(
        &self,
        _node_id: &str,
        model_name: &str,
        model_version: Option<&str>,
        inputs: Value,
        request_id: &str,
    ) -> Result<Value, AppError> {
        let version = model_version.unwrap_or("latest").to_string();

        let input_tensors = if let Some(obj) = inputs.as_object() {
            obj.iter()
                .map(|(k, v)| {
                    if let Some(arr) = v.as_array() {
                        let data: Vec<u8> = arr
                            .iter()
                            .filter_map(|x| x.as_u64().map(|n| n as u8))
                            .collect();
                        Tensor::new(k.clone(), "float32".to_string(), vec![1, arr.len() as i64], data)
                    } else {
                        let data = serde_json::to_vec(v).unwrap_or_default();
                        Tensor::new(k.clone(), "float32".to_string(), vec![1], data)
                    }
                })
                .collect()
        } else {
            vec![]
        };

        let req = pb::InferRequest {
            request_id: request_id.to_string(),
            model_name: model_name.to_string(),
            version: version.clone(),
            inputs: input_tensors.iter().map(tensor_to_pb_tensor).collect(),
            params: std::collections::HashMap::new(),
            timeout_ms: 30000,
            trace_id: String::new(),
            user_id: String::new(),
            priority: 0,
        };

        let resp = self.infer(req).await?;

        let mut output_map = serde_json::Map::new();
        for output in &resp.outputs {
            let values: Vec<f32> = if !output.data_bytes.is_empty() {
                output
                    .data_bytes
                    .chunks(4)
                    .map(|chunk| {
                        if chunk.len() == 4 {
                            f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]])
                        } else {
                            0.0
                        }
                    })
                    .collect()
            } else {
                vec![]
            };
            output_map.insert(output.name.clone(), serde_json::to_value(values)?);
        }

        Ok(Value::Object(output_map))
    }
}

impl InferenceRuntime {
    pub async fn create_pipeline_from_yaml(
        self: &Arc<Self>,
        yaml_str: &str,
    ) -> Result<InferencePipeline, AppError> {
        InferencePipeline::from_yaml(yaml_str, self.clone())
    }

    pub async fn create_pipeline_from_config(
        self: &Arc<Self>,
        config: PipelineConfig,
    ) -> Result<InferencePipeline, AppError> {
        InferencePipeline::from_config(config, Some(self.clone()))
    }
}
