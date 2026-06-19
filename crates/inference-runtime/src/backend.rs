use common::error::AppError;
use common::types::ModelFramework;
use serde::{Deserialize, Serialize};
use std::any::Any;
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex};
use tracing::{debug, info, warn};

#[derive(Debug, Clone, PartialEq)]
pub struct Tensor {
    pub name: String,
    pub dtype: String,
    pub shape: Vec<i64>,
    pub data: Vec<u8>,
}

impl Tensor {
    pub fn new(name: impl Into<String>, dtype: impl Into<String>, shape: Vec<i64>, data: Vec<u8>) -> Self {
        Self {
            name: name.into(),
            dtype: dtype.into(),
            shape,
            data,
        }
    }

    pub fn from_f32(name: impl Into<String>, shape: Vec<i64>, values: &[f32]) -> Self {
        let data: Vec<u8> = values
            .iter()
            .flat_map(|v| v.to_le_bytes().to_vec())
            .collect();
        Self::new(name, "float32", shape, data)
    }

    pub fn to_f32(&self) -> Result<Vec<f32>, AppError> {
        if self.data.len() % 4 != 0 {
            return Err(AppError::InferenceError(format!(
                "Tensor data length {} not divisible by 4 for f32",
                self.data.len()
            )));
        }
        let mut result = Vec::with_capacity(self.data.len() / 4);
        for chunk in self.data.chunks_exact(4) {
            result.push(f32::from_le_bytes(chunk.try_into().unwrap()));
        }
        Ok(result)
    }

    pub fn element_count(&self) -> usize {
        self.shape.iter().fold(1usize, |acc, &d| acc * d.max(0) as usize)
    }
}

#[derive(Clone)]
pub struct ModelHandle {
    pub inner: Arc<Mutex<Box<dyn Any + Send + Sync>>>,
    pub model_path: String,
    pub version: String,
    pub gpu_id: Option<i32>,
}

impl ModelHandle {
    pub fn new<T: Any + Send + Sync + 'static>(
        model: T,
        model_path: impl Into<String>,
        version: impl Into<String>,
        gpu_id: Option<i32>,
    ) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Box::new(model))),
            model_path: model_path.into(),
            version: version.into(),
            gpu_id,
        }
    }

    pub fn with_lock<T: Any + Send + Sync + 'static, F, R>(&self, f: F) -> Result<R, AppError>
    where
        F: FnOnce(&mut T) -> Result<R, AppError>,
    {
        let mut guard = self
            .inner
            .lock()
            .map_err(|e| AppError::Internal(format!("Failed to lock model handle: {}", e)))?;
        let model = guard
            .downcast_mut::<T>()
            .ok_or_else(|| AppError::Internal("Model handle type mismatch".into()))?;
        f(model)
    }
}

impl std::fmt::Debug for ModelHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ModelHandle")
            .field("model_path", &self.model_path)
            .field("version", &self.version)
            .field("gpu_id", &self.gpu_id)
            .finish()
    }
}

#[async_trait::async_trait]
pub trait Backend: Send + Sync {
    fn name(&self) -> &str;

    async fn load_model(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError>;

    async fn unload_model(&self, _handle: &ModelHandle) -> Result<(), AppError> {
        Ok(())
    }

    async fn infer(&self, handle: &ModelHandle, inputs: Vec<Tensor>) -> Result<Vec<Tensor>, AppError>;

    fn supports(&self, framework: ModelFramework) -> bool;
}

#[derive(Clone)]
pub struct MockModel {
    pub model_path: String,
    pub version: String,
    pub gpu_id: Option<i32>,
}

#[cfg(feature = "onnxrt")]
mod onnx_impl {
    use super::*;

    pub struct OnnxRuntimeBackend {
        thread_count: usize,
    }

    impl OnnxRuntimeBackend {
        pub fn new(thread_count: usize) -> Self {
            Self { thread_count }
        }

        fn build_session(
            &self,
            model_path: &Path,
            gpu_id: Option<i32>,
        ) -> Result<ort::Session, AppError> {
            let mut builder = ort::Session::builder()
                .map_err(|e| AppError::Internal(format!("Failed to create session builder: {}", e)))?;

            builder = builder
                .with_parallel_execution(true)
                .map_err(|e| AppError::Internal(format!("Failed to set parallel execution: {}", e)))?
                .with_intra_threads(self.thread_count as i16)
                .map_err(|e| AppError::Internal(format!("Failed to set intra threads: {}", e)))?
                .with_inter_threads(self.thread_count as i16)
                .map_err(|e| AppError::Internal(format!("Failed to set inter threads: {}", e)))?;

            if let Some(gpu) = gpu_id {
                builder = builder
                    .with_execution_providers([ort::execution_providers::CUDAExecutionProvider::default()
                        .with_device_id(gpu as i32)
                        .build()])
                    .map_err(|e| AppError::Internal(format!("Failed to set CUDA provider: {}", e)))?;
            }

            let session = builder
                .commit_from_file(model_path)
                .map_err(|e| AppError::InferenceError(format!("Failed to load ONNX model: {}", e)))?;

            Ok(session)
        }

        fn tensor_to_ort_value(tensor: &Tensor) -> Result<ort::Value, AppError> {
            let shape: Vec<i64> = tensor.shape.clone();
            let dims: Vec<ort::Dimension> = shape
                .iter()
                .map(|&d| ort::Dimension::Fixed(d as usize))
                .collect();

            match tensor.dtype.as_str() {
                "float32" | "float" | "fp32" | "FP32" => {
                    let values = tensor.to_f32()?;
                    let value = ort::Value::from_array(
                        (&shape, values.as_slice()),
                    ).map_err(|e| AppError::InferenceError(format!("Failed to create ONNX tensor: {}", e)))?;
                    Ok(value)
                }
                _ => Err(AppError::NotImplemented(format!(
                    "Unsupported dtype for ONNX conversion: {}",
                    tensor.dtype
                ))),
            }
        }

        fn ort_output_to_tensor(
            name: String,
            value: &ort::DynValue,
        ) -> Result<Tensor, AppError> {
            let (shape, dtype) = extract_shape_and_dtype(value)?;

            let data = extract_data_bytes(value, &dtype)?;

            Ok(Tensor::new(name, dtype, shape, data))
        }

        fn extract_shape_and_dtype(
            value: &ort::DynValue,
        ) -> Result<(Vec<i64>, String), AppError> {
            let shape = value
                .try_extract_tensor::<f32>()
                .map(|t| t.shape().iter().map(|&d| d as i64).collect::<Vec<_>>())
                .map_err(|e| AppError::InferenceError(format!("Failed to extract shape: {}", e)))?;

            Ok((shape, "float32".into()))
        }

        fn extract_data_bytes(
            value: &ort::DynValue,
            _dtype: &str,
        ) -> Result<Vec<u8>, AppError> {
            let tensor = value
                .try_extract_tensor::<f32>()
                .map_err(|e| AppError::InferenceError(format!("Failed to extract tensor data: {}", e)))?;

            let data = tensor
                .as_slice()
                .ok_or_else(|| AppError::InferenceError("Tensor not in standard layout".into()))?
                .iter()
                .flat_map(|v| v.to_le_bytes().to_vec())
                .collect();

            Ok(data)
        }
    }

    #[async_trait::async_trait]
    impl Backend for OnnxRuntimeBackend {
        fn name(&self) -> &str {
            "onnxruntime"
        }

        async fn load_model(
            &self,
            model_path: &Path,
            version: &str,
            gpu_id: Option<i32>,
        ) -> Result<ModelHandle, AppError> {
            info!(
                "Loading ONNX model from {:?}, version={}, gpu_id={:?}",
                model_path, version, gpu_id
            );

            let path = model_path.to_path_buf();
            let gpu = gpu_id;
            let thread_count = self.thread_count;

            let session = tokio::task::spawn_blocking(move || {
                let backend = OnnxRuntimeBackend::new(thread_count);
                backend.build_session(&path, gpu)
            })
            .await
            .map_err(|e| AppError::Internal(format!("Join error: {}", e)))??;

            Ok(ModelHandle::new(
                session,
                model_path.to_string_lossy().to_string(),
                version,
                gpu_id,
            ))
        }

        async fn infer(
            &self,
            handle: &ModelHandle,
            inputs: Vec<Tensor>,
        ) -> Result<Vec<Tensor>, AppError> {
            debug!("Running ONNX inference with {} inputs", inputs.len());

            let handle_clone = handle.clone();
            let input_tensors: Vec<Tensor> = inputs.clone();

            let results = tokio::task::spawn_blocking(move || -> Result<Vec<Tensor>, AppError> {
                handle_clone.with_lock::<ort::Session, _, _>(|session| {
                    let mut input_values: Vec<(String, ort::DynValue)> = Vec::new();
                    let mut held_values: Vec<ort::Value> = Vec::new();

                    for tensor in &input_tensors {
                        let ort_val = Self::tensor_to_ort_value(tensor)?;
                        let dyn_val: ort::DynValue = ort_val.into_dyn();
                        input_values.push((tensor.name.clone(), dyn_val));
                    }

                    let output_names: Vec<String> = session
                        .outputs
                        .iter()
                        .map(|o| o.name.clone())
                        .collect();

                    let inputs_ref: Vec<(&str, &ort::DynValue)> = input_values
                        .iter()
                        .map(|(n, v)| (n.as_str(), v))
                        .collect();

                    let outputs = session
                        .run(inputs_ref.as_slice())
                        .map_err(|e| AppError::InferenceError(format!("Session run failed: {}", e)))?;

                    let mut result_tensors = Vec::with_capacity(outputs.len());
                    for (i, output) in outputs.iter().enumerate() {
                        let name = output_names.get(i).cloned().unwrap_or_else(|| format!("output_{}", i));
                        let tensor = Self::ort_output_to_tensor(name, output)?;
                        result_tensors.push(tensor);
                    }

                    Ok(result_tensors)
                })
            })
            .await
            .map_err(|e| AppError::Internal(format!("Join error: {}", e)))??;

            Ok(results)
        }

        fn supports(&self, framework: ModelFramework) -> bool {
            matches!(framework, ModelFramework::Onnx)
        }
    }
}

#[cfg(feature = "onnxrt")]
pub use onnx_impl::OnnxRuntimeBackend;

pub struct TensorRTBackend;

impl TensorRTBackend {
    pub fn new() -> Self {
        Self
    }
}

impl Default for TensorRTBackend {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl Backend for TensorRTBackend {
    fn name(&self) -> &str {
        "tensorrt"
    }

    async fn load_model(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError> {
        info!(
            "[MOCK] Loading TensorRT model from {:?}, version={}, gpu_id={:?}",
            model_path, version, gpu_id
        );
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
        let mock_model = MockModel {
            model_path: model_path.to_string_lossy().to_string(),
            version: version.to_string(),
            gpu_id,
        };
        Ok(ModelHandle::new(
            mock_model,
            model_path.to_string_lossy().to_string(),
            version,
            gpu_id,
        ))
    }

    async fn infer(&self, handle: &ModelHandle, inputs: Vec<Tensor>) -> Result<Vec<Tensor>, AppError> {
        debug!("[MOCK] Running TensorRT inference with {} inputs", inputs.len());
        handle.with_lock::<MockModel, _, _>(|_model| {
            let mut outputs = Vec::new();
            for (i, input) in inputs.iter().enumerate() {
                let output_shape = vec![input.shape[0], 10];
                let batch_size = input.shape[0] as usize;
                let num_elements = batch_size * 10;
                let data: Vec<f32> = (0..num_elements).map(|v| (v % 10) as f32 / 10.0).collect();
                outputs.push(Tensor::from_f32(format!("output_{}", i), output_shape, &data));
            }
            Ok(outputs)
        })
    }

    fn supports(&self, framework: ModelFramework) -> bool {
        matches!(framework, ModelFramework::TensorRT)
    }
}

pub struct MockBackend;

impl MockBackend {
    pub fn new() -> Self {
        Self
    }
}

impl Default for MockBackend {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl Backend for MockBackend {
    fn name(&self) -> &str {
        "mock"
    }

    async fn load_model(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError> {
        info!(
            "[MOCK] Loading model from {:?}, version={}, gpu_id={:?}",
            model_path, version, gpu_id
        );
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        let mock_model = MockModel {
            model_path: model_path.to_string_lossy().to_string(),
            version: version.to_string(),
            gpu_id,
        };
        Ok(ModelHandle::new(
            mock_model,
            model_path.to_string_lossy().to_string(),
            version,
            gpu_id,
        ))
    }

    async fn infer(&self, handle: &ModelHandle, inputs: Vec<Tensor>) -> Result<Vec<Tensor>, AppError> {
        debug!("[MOCK] Running inference with {} inputs", inputs.len());
        handle.with_lock::<MockModel, _, _>(|_model| {
            let mut outputs = Vec::new();
            for (i, input) in inputs.iter().enumerate() {
                let batch_size = if !input.shape.is_empty() {
                    input.shape[0] as usize
                } else {
                    1
                };
                let output_shape = vec![batch_size as i64, 10];
                let num_elements = batch_size * 10;
                let data: Vec<f32> = (0..num_elements).map(|v| (v % 10) as f32 / 10.0).collect();
                outputs.push(Tensor::from_f32(format!("output_{}", i), output_shape, &data));
            }
            Ok(outputs)
        })
    }

    fn supports(&self, _framework: ModelFramework) -> bool {
        true
    }
}

pub struct BackendRegistry {
    backends: HashMap<ModelFramework, Box<dyn Backend + Send + Sync>>,
}

impl BackendRegistry {
    pub fn new() -> Self {
        Self {
            backends: HashMap::new(),
        }
    }

    pub fn register<B: Backend + Send + Sync + 'static>(&mut self, framework: ModelFramework, backend: B) {
        self.backends.insert(framework, Box::new(backend));
    }

    pub fn register_boxed(&mut self, framework: ModelFramework, backend: Box<dyn Backend + Send + Sync>) {
        self.backends.insert(framework, backend);
    }

    pub fn get(&self, framework: ModelFramework) -> Result<&(dyn Backend + Send + Sync), AppError> {
        self.backends
            .get(&framework)
            .map(|b| b.as_ref())
            .ok_or_else(|| {
                AppError::NotImplemented(format!(
                    "No backend registered for framework: {:?}",
                    framework
                ))
            })
    }

    pub fn default() -> Self {
        let mut registry = Self::new();

        #[cfg(feature = "onnxrt")]
        registry.register(ModelFramework::Onnx, OnnxRuntimeBackend::new(4));

        #[cfg(feature = "tensorrt")]
        registry.register(ModelFramework::TensorRT, TensorRTBackend::new());

        registry.register(ModelFramework::TensorRT, TensorRTBackend::new());
        registry.register(ModelFramework::Pytorch, MockBackend::new());
        registry.register(ModelFramework::Tensorflow, MockBackend::new());
        registry.register(ModelFramework::Onnx, MockBackend::new());

        registry
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeBackendConfig {
    pub backend_type: String,
    pub num_threads: Option<usize>,
    pub enable_optimization: Option<bool>,
    pub gpu_id: Option<i32>,
    pub execution_providers: Option<Vec<String>>,
}

impl Default for RuntimeBackendConfig {
    fn default() -> Self {
        Self {
            backend_type: "onnxrt".to_string(),
            num_threads: Some(4),
            enable_optimization: Some(true),
            gpu_id: None,
            execution_providers: None,
        }
    }
}

#[async_trait::async_trait]
pub trait RuntimeBackend: Send + Sync {
    fn name(&self) -> &str;

    async fn load(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError>;

    async fn unload(&self, handle: &ModelHandle) -> Result<(), AppError>;

    async fn infer(
        &self,
        handle: &ModelHandle,
        inputs: Vec<Tensor>,
    ) -> Result<Vec<Tensor>, AppError>;
}

#[cfg(feature = "onnxrt")]
pub struct OnnxRuntimeBackendAdapter {
    inner: OnnxRuntimeBackend,
}

#[cfg(feature = "onnxrt")]
impl OnnxRuntimeBackendAdapter {
    pub fn new(thread_count: usize) -> Self {
        Self {
            inner: OnnxRuntimeBackend::new(thread_count),
        }
    }
}

#[cfg(feature = "onnxrt")]
#[async_trait::async_trait]
impl RuntimeBackend for OnnxRuntimeBackendAdapter {
    fn name(&self) -> &str {
        self.inner.name()
    }

    async fn load(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError> {
        self.inner.load_model(model_path, version, gpu_id).await
    }

    async fn unload(&self, handle: &ModelHandle) -> Result<(), AppError> {
        self.inner.unload_model(handle).await
    }

    async fn infer(
        &self,
        handle: &ModelHandle,
        inputs: Vec<Tensor>,
    ) -> Result<Vec<Tensor>, AppError> {
        self.inner.infer(handle, inputs).await
    }
}

pub struct TensorRtBackendAdapter {
    inner: TensorRTBackend,
}

impl TensorRtBackendAdapter {
    pub fn new() -> Self {
        Self {
            inner: TensorRTBackend::new(),
        }
    }
}

#[async_trait::async_trait]
impl RuntimeBackend for TensorRtBackendAdapter {
    fn name(&self) -> &str {
        self.inner.name()
    }

    async fn load(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError> {
        self.inner.load_model(model_path, version, gpu_id).await
    }

    async fn unload(&self, handle: &ModelHandle) -> Result<(), AppError> {
        self.inner.unload_model(handle).await
    }

    async fn infer(
        &self,
        handle: &ModelHandle,
        inputs: Vec<Tensor>,
    ) -> Result<Vec<Tensor>, AppError> {
        self.inner.infer(handle, inputs).await
    }
}

pub struct MockBackendAdapter {
    inner: MockBackend,
}

impl MockBackendAdapter {
    pub fn new() -> Self {
        Self {
            inner: MockBackend::new(),
        }
    }
}

#[async_trait::async_trait]
impl RuntimeBackend for MockBackendAdapter {
    fn name(&self) -> &str {
        self.inner.name()
    }

    async fn load(
        &self,
        model_path: &Path,
        version: &str,
        gpu_id: Option<i32>,
    ) -> Result<ModelHandle, AppError> {
        self.inner.load_model(model_path, version, gpu_id).await
    }

    async fn unload(&self, handle: &ModelHandle) -> Result<(), AppError> {
        self.inner.unload_model(handle).await
    }

    async fn infer(
        &self,
        handle: &ModelHandle,
        inputs: Vec<Tensor>,
    ) -> Result<Vec<Tensor>, AppError> {
        self.inner.infer(handle, inputs).await
    }
}

pub struct BackendFactory;

impl BackendFactory {
    pub fn create(
        config: &RuntimeBackendConfig,
        framework: ModelFramework,
    ) -> Result<Box<dyn RuntimeBackend>, AppError> {
        let thread_count = config.num_threads.unwrap_or(4);

        match config.backend_type.as_str() {
            "onnxrt" | "onnxruntime" => {
                #[cfg(feature = "onnxrt")]
                {
                    Ok(Box::new(OnnxRuntimeBackendAdapter::new(thread_count)))
                }
                #[cfg(not(feature = "onnxrt"))]
                {
                    info!("ONNX Runtime feature not enabled, using MockBackend as fallback");
                    Ok(Box::new(MockBackendAdapter::new()))
                }
            }
            "tensorrt" | "trt" => match framework {
                ModelFramework::TensorRT => Ok(Box::new(TensorRtBackendAdapter::new())),
                _ => {
                    warn!("TensorRT backend requested but framework is {:?}, using MockBackend", framework);
                    Ok(Box::new(MockBackendAdapter::new()))
                }
            },
            "mock" | "test" => Ok(Box::new(MockBackendAdapter::new())),
            other => {
                warn!("Unknown backend type '{}', falling back to MockBackend", other);
                Ok(Box::new(MockBackendAdapter::new()))
            }
        }
    }

    pub fn create_from_framework(framework: ModelFramework) -> Result<Box<dyn RuntimeBackend>, AppError> {
        match framework {
            ModelFramework::Onnx => {
                #[cfg(feature = "onnxrt")]
                {
                    Ok(Box::new(OnnxRuntimeBackendAdapter::new(4)))
                }
                #[cfg(not(feature = "onnxrt"))]
                {
                    Ok(Box::new(MockBackendAdapter::new()))
                }
            }
            ModelFramework::TensorRT => Ok(Box::new(TensorRtBackendAdapter::new())),
            ModelFramework::Tensorflow | ModelFramework::Pytorch => Ok(Box::new(MockBackendAdapter::new())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_tensor_f32_conversion() {
        let values = vec![1.0f32, 2.0, 3.0, 4.0];
        let tensor = Tensor::from_f32("test", vec![2, 2], &values);
        assert_eq!(tensor.dtype, "float32");
        assert_eq!(tensor.shape, vec![2, 2]);
        assert_eq!(tensor.to_f32().unwrap(), values);
    }

    #[tokio::test]
    async fn test_mock_backend() {
        let backend = MockBackend::new();
        let handle = backend
            .load_model(Path::new("/tmp/model.bin"), "v1", None)
            .await
            .unwrap();

        let input = Tensor::from_f32("input0", vec![2, 3], &[0.1, 0.2, 0.3, 0.4, 0.5, 0.6]);
        let outputs = backend.infer(&handle, vec![input]).await.unwrap();
        assert!(!outputs.is_empty());
        assert_eq!(outputs[0].shape[0], 2);
    }

    #[tokio::test]
    async fn test_backend_registry() {
        let registry = BackendRegistry::default();
        let backend = registry.get(ModelFramework::Onnx).unwrap();
        assert_eq!(backend.name(), "mock");
    }
}
