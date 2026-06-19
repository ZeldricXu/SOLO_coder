use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use common::error::AppError;
use common::types::{InferenceRequest, InferenceResponse, IOSchema, RouteTarget};
use dashmap::DashMap;
use crate::google::protobuf::value::Kind;
use crate::google::protobuf::{ListValue, Struct, Value as ProstValue};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use tokio::sync::Mutex;
use tonic::transport::{Channel, Endpoint};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

pub mod google {
    pub mod protobuf {
        include!("google.protobuf.rs");
    }
}

pub mod inference {
    pub mod v1 {
        include!("inference.v1.rs");
    }
}

use inference::v1::inference_service_client::InferenceServiceClient;
use inference::v1::{DataType, InferRequest, InferResponse, Tensor};

#[derive(Debug, Clone)]
pub struct EndpointConfig {
    pub address: String,
    pub model_version_id: Uuid,
    pub max_retries: u32,
    pub timeout_ms: u64,
}

struct GrpcConnection {
    client: InferenceServiceClient<Channel>,
    last_used: Instant,
}

type ConnectionPool = Arc<DashMap<Uuid, Vec<Arc<Mutex<GrpcConnection>>>>>;

#[derive(Clone)]
pub struct RuntimeClient {
    connections: ConnectionPool,
    endpoints: Arc<DashMap<Uuid, EndpointConfig>>,
    pool_size: usize,
    default_timeout_ms: u64,
    default_max_retries: u32,
}

fn json_to_prost_value(value: &JsonValue) -> ProstValue {
    let kind = match value {
        JsonValue::Null => Kind::NullValue(0),
        JsonValue::Bool(b) => Kind::BoolValue(*b),
        JsonValue::Number(n) => {
            if let Some(f) = n.as_f64() {
                Kind::NumberValue(f)
            } else if let Some(i) = n.as_i64() {
                Kind::NumberValue(i as f64)
            } else if let Some(u) = n.as_u64() {
                Kind::NumberValue(u as f64)
            } else {
                Kind::StringValue(n.to_string())
            }
        }
        JsonValue::String(s) => Kind::StringValue(s.clone()),
        JsonValue::Array(arr) => {
            let values: Vec<ProstValue> = arr.iter().map(json_to_prost_value).collect();
            Kind::ListValue(ListValue { values })
        }
        JsonValue::Object(obj) => {
                let mut fields = HashMap::new();
                for (k, v) in obj {
                    fields.insert(k.clone(), json_to_prost_value(v));
                }
                Kind::StructValue(Struct { fields })
            }
    };
    ProstValue { kind: Some(kind) }
}

fn infer_dtype_from_value(value: &JsonValue, dtype_hint: Option<&str>) -> DataType {
    if let Some(hint) = dtype_hint {
        return match hint.to_lowercase().as_str() {
            "float32" | "f32" | "fp32" => DataType::Fp32,
            "float64" | "f64" | "fp64" => DataType::Fp64,
            "bfloat16" | "bf16" => DataType::Bf16,
            "int8" | "i8" => DataType::Int8,
            "int16" | "i16" => DataType::Int16,
            "int32" | "i32" => DataType::Int32,
            "int64" | "i64" => DataType::Int64,
            "uint8" | "u8" => DataType::Uint8,
            "uint16" | "u16" => DataType::Uint16,
            "uint32" | "u32" => DataType::Uint32,
            "uint64" | "u64" => DataType::Uint64,
            "bool" | "boolean" => DataType::Bool,
            "string" | "str" => DataType::String,
            _ => DataType::Unspecified,
        };
    }

    match value {
        JsonValue::Bool(_) => DataType::Bool,
        JsonValue::Number(n) => {
            if n.is_i64() {
                DataType::Int64
            } else if n.is_u64() {
                DataType::Uint64
            } else {
                DataType::Fp64
            }
        }
        JsonValue::String(_) => DataType::String,
        JsonValue::Array(arr) if !arr.is_empty() => infer_dtype_from_value(&arr[0], None),
        _ => DataType::Fp32,
    }
}

fn json_tensor_to_bytes(value: &JsonValue, dtype: DataType) -> Result<Vec<u8>, AppError> {
    let mut bytes: Vec<u8> = Vec::new();

    fn flatten(value: &JsonValue) -> Vec<&JsonValue> {
        match value {
            JsonValue::Array(arr) => arr.iter().flat_map(flatten).collect(),
            other => vec![other],
        }
    }

    let flat = flatten(value);
    for v in flat {
        match dtype {
            DataType::Fp32 => {
                let f = v.as_f64().unwrap_or(0.0) as f32;
                bytes.extend_from_slice(&f.to_le_bytes());
            }
            DataType::Fp64 => {
                let f = v.as_f64().unwrap_or(0.0);
                bytes.extend_from_slice(&f.to_le_bytes());
            }
            DataType::Int32 => {
                let i = v.as_i64().unwrap_or(0) as i32;
                bytes.extend_from_slice(&i.to_le_bytes());
            }
            DataType::Int64 => {
                let i = v.as_i64().unwrap_or(0);
                bytes.extend_from_slice(&i.to_le_bytes());
            }
            DataType::Uint32 => {
                let u = v.as_u64().unwrap_or(0) as u32;
                bytes.extend_from_slice(&u.to_le_bytes());
            }
            DataType::Uint64 => {
                let u = v.as_u64().unwrap_or(0);
                bytes.extend_from_slice(&u.to_le_bytes());
            }
            DataType::Int8 => {
                let i = v.as_i64().unwrap_or(0) as i8;
                bytes.extend_from_slice(&i.to_le_bytes());
            }
            DataType::Uint8 => {
                let u = v.as_u64().unwrap_or(0) as u8;
                bytes.extend_from_slice(&u.to_le_bytes());
            }
            DataType::Int16 => {
                let i = v.as_i64().unwrap_or(0) as i16;
                bytes.extend_from_slice(&i.to_le_bytes());
            }
            DataType::Uint16 => {
                let u = v.as_u64().unwrap_or(0) as u16;
                bytes.extend_from_slice(&u.to_le_bytes());
            }
            DataType::Bool => {
                let b = v.as_bool().unwrap_or(false) as u8;
                bytes.push(b);
            }
            DataType::String => {
                let s = v.as_str().unwrap_or("");
                let len = s.len() as u32;
                bytes.extend_from_slice(&len.to_le_bytes());
                bytes.extend_from_slice(s.as_bytes());
            }
            DataType::Bf16 => {
                let f = v.as_f64().unwrap_or(0.0) as f32;
                let bits = f.to_bits();
                let bf16 = (bits >> 16) as u16;
                bytes.extend_from_slice(&bf16.to_le_bytes());
            }
            _ => {
                let f = v.as_f64().unwrap_or(0.0) as f32;
                bytes.extend_from_slice(&f.to_le_bytes());
            }
        }
    }

    Ok(bytes)
}

fn get_tensor_shape(value: &JsonValue) -> Vec<i64> {
    match value {
        JsonValue::Array(arr) => {
            if arr.is_empty() {
                vec![0]
            } else {
                let mut shape = vec![arr.len() as i64];
                shape.extend(get_tensor_shape(&arr[0]));
                shape
            }
        }
        _ => vec![],
    }
}

fn build_grpc_request(
    request: &InferenceRequest,
    version: &str,
    schema: Option<&[IOSchema]>,
    timeout_ms: u64,
) -> Result<InferRequest, AppError> {
    let mut tensors: Vec<Tensor> = Vec::new();

    if let Some(input_obj) = request.inputs.as_object() {
        for (name, value) in input_obj {
            let io_schema = schema.and_then(|s| s.iter().find(|ios| ios.name == *name));
            let dtype_hint = io_schema.map(|ios| ios.dtype.as_str());
            let dtype = infer_dtype_from_value(value, dtype_hint);
            let shape = get_tensor_shape(value);
            let data_bytes = json_tensor_to_bytes(value, dtype)?;

            tensors.push(Tensor {
                name: name.clone(),
                dtype: dtype as i32,
                shape,
                data_bytes,
            });
        }
    }

    let mut params: HashMap<String, ProstValue> = HashMap::new();
    if let Some(param_obj) = &request.parameters {
        if let Some(obj) = param_obj.as_object() {
            for (k, v) in obj {
                params.insert(k.clone(), json_to_prost_value(v));
            }
        }
    }

    Ok(InferRequest {
        request_id: request.request_id.clone(),
        model_name: request.model_name.clone(),
        version: version.to_string(),
        inputs: tensors,
        params,
        trace_id: request.trace_id.clone().unwrap_or_default(),
        user_id: request.user_id.clone().unwrap_or_default(),
        priority: 0,
        timeout_ms: timeout_ms as i64,
    })
}

#[allow(dead_code)]
fn prost_to_json_value(pv: &ProstValue) -> JsonValue {
    match &pv.kind {
        Some(Kind::NullValue(_)) => JsonValue::Null,
        Some(Kind::NumberValue(n)) => {
            serde_json::Number::from_f64(*n)
                .map(JsonValue::Number)
                .unwrap_or(JsonValue::Null)
        }
        Some(Kind::StringValue(s)) => JsonValue::String(s.clone()),
        Some(Kind::BoolValue(b)) => JsonValue::Bool(*b),
        Some(Kind::ListValue(lv)) => {
            JsonValue::Array(lv.values.iter().map(prost_to_json_value).collect())
        }
        Some(Kind::StructValue(sv)) => {
            let mut map = serde_json::Map::new();
            for (k, v) in &sv.fields {
                map.insert(k.clone(), prost_to_json_value(v));
            }
            JsonValue::Object(map)
        }
        None => JsonValue::Null,
    }
}

fn bytes_to_json_tensor(
    data_bytes: &[u8],
    dtype: DataType,
    shape: &[i64],
) -> Result<JsonValue, AppError> {
    fn element_size(dtype: DataType) -> usize {
        match dtype {
            DataType::Fp32 | DataType::Int32 | DataType::Uint32 | DataType::Bf16 => 4,
            DataType::Fp64 | DataType::Int64 | DataType::Uint64 => 8,
            DataType::Int16 | DataType::Uint16 => 2,
            DataType::Int8 | DataType::Uint8 | DataType::Bool => 1,
            DataType::String => 4,
            _ => 4,
        }
    }

    fn read_scalar(
        data: &[u8],
        dtype: DataType,
        offset: &mut usize,
    ) -> Result<JsonValue, AppError> {
        let sz = element_size(dtype);
        if *offset + sz > data.len() {
            return Err(AppError::InferenceError(
                "Tensor data buffer too short".to_string(),
            ));
        }
        let slice = &data[*offset..*offset + sz];
        *offset += sz;

        let value = match dtype {
            DataType::Fp32 => {
                let mut bytes = [0u8; 4];
                bytes.copy_from_slice(slice);
                JsonValue::from(f32::from_le_bytes(bytes) as f64)
            }
            DataType::Fp64 => {
                let mut bytes = [0u8; 8];
                bytes.copy_from_slice(slice);
                JsonValue::from(f64::from_le_bytes(bytes))
            }
            DataType::Int32 => {
                let mut bytes = [0u8; 4];
                bytes.copy_from_slice(slice);
                JsonValue::from(i32::from_le_bytes(bytes) as i64)
            }
            DataType::Int64 => {
                let mut bytes = [0u8; 8];
                bytes.copy_from_slice(slice);
                JsonValue::from(i64::from_le_bytes(bytes))
            }
            DataType::Uint32 => {
                let mut bytes = [0u8; 4];
                bytes.copy_from_slice(slice);
                JsonValue::from(u32::from_le_bytes(bytes) as u64)
            }
            DataType::Uint64 => {
                let mut bytes = [0u8; 8];
                bytes.copy_from_slice(slice);
                JsonValue::from(u64::from_le_bytes(bytes))
            }
            DataType::Int16 => {
                let mut bytes = [0u8; 2];
                bytes.copy_from_slice(slice);
                JsonValue::from(i16::from_le_bytes(bytes) as i64)
            }
            DataType::Uint16 => {
                let mut bytes = [0u8; 2];
                bytes.copy_from_slice(slice);
                JsonValue::from(u16::from_le_bytes(bytes) as u64)
            }
            DataType::Int8 => JsonValue::from(slice[0] as i8 as i64),
            DataType::Uint8 => JsonValue::from(slice[0] as u64),
            DataType::Bool => JsonValue::Bool(slice[0] != 0),
            DataType::Bf16 => {
                let mut bytes = [0u8; 2];
                bytes.copy_from_slice(slice);
                let bf16 = u16::from_le_bytes(bytes);
                let f32_bits = (bf16 as u32) << 16;
                let f = f32::from_bits(f32_bits);
                JsonValue::from(f as f64)
            }
            DataType::String => {
                let mut len_bytes = [0u8; 4];
                len_bytes.copy_from_slice(slice);
                let str_len = u32::from_le_bytes(len_bytes) as usize;
                if *offset + str_len > data.len() {
                    return Err(AppError::InferenceError(
                        "String tensor data too short".to_string(),
                    ));
                }
                let s = std::str::from_utf8(&data[*offset..*offset + str_len])
                    .map_err(|e| AppError::InferenceError(format!("Invalid UTF-8: {}", e)))?
                    .to_string();
                *offset += str_len;
                JsonValue::String(s)
            }
            _ => JsonValue::Null,
        };
        Ok(value)
    }

    fn build_nested(
        data: &[u8],
        shape: &[i64],
        dtype: DataType,
        offset: &mut usize,
    ) -> Result<JsonValue, AppError> {
        if shape.is_empty() || (shape.len() == 1 && shape[0] == 0) {
            return read_scalar(data, dtype, offset);
        }
        if shape.len() == 1 {
            let n = shape[0] as usize;
            let mut arr = Vec::with_capacity(n);
            for _ in 0..n {
                arr.push(read_scalar(data, dtype, offset)?);
            }
            return Ok(JsonValue::Array(arr));
        }
        let n = shape[0] as usize;
        let inner_shape = &shape[1..];
        let mut arr = Vec::with_capacity(n);
        for _ in 0..n {
            arr.push(build_nested(data, inner_shape, dtype, offset)?);
        }
        Ok(JsonValue::Array(arr))
    }

    let mut offset = 0;
    build_nested(data_bytes, shape, dtype, &mut offset)
}

fn parse_grpc_response(
    response: &InferResponse,
    original_request: &InferenceRequest,
) -> Result<InferenceResponse, AppError> {
    if !response.error.is_empty() {
        return Err(AppError::InferenceError(response.error.clone()));
    }

    let mut outputs_obj = serde_json::Map::new();

    for tensor in &response.outputs {
        let dtype_i32: i32 = tensor.dtype;
        let dtype = num_traits_from_i32(dtype_i32);
        let value = bytes_to_json_tensor(&tensor.data_bytes, dtype, &tensor.shape)?;
        outputs_obj.insert(tensor.name.clone(), value);
    }

    Ok(InferenceResponse {
        request_id: response.request_id.clone(),
        model_name: response.model_name.clone(),
        version: response.version.clone(),
        outputs: JsonValue::Object(outputs_obj),
        latency_ms: response.latency_ms as u64,
        gpu_id: Some(response.gpu_id.to_string()),
        trace_id: if response.trace_id.is_empty() {
            original_request.trace_id.clone()
        } else {
            Some(response.trace_id.clone())
        },
    })
}

fn num_traits_from_i32(v: i32) -> DataType {
    match v {
        0 => DataType::Unspecified,
        1 => DataType::Bool,
        2 => DataType::Uint8,
        3 => DataType::Uint16,
        4 => DataType::Uint32,
        5 => DataType::Uint64,
        6 => DataType::Int8,
        7 => DataType::Int16,
        8 => DataType::Int32,
        9 => DataType::Int64,
        10 => DataType::Fp16,
        11 => DataType::Fp32,
        12 => DataType::Fp64,
        13 => DataType::Bf16,
        14 => DataType::String,
        _ => DataType::Fp32,
    }
}

impl RuntimeClient {
    pub fn new() -> Self {
        Self {
            connections: Arc::new(DashMap::new()),
            endpoints: Arc::new(DashMap::new()),
            pool_size: 4,
            default_timeout_ms: 30_000,
            default_max_retries: 2,
        }
    }

    pub fn with_pool_size(mut self, pool_size: usize) -> Self {
        self.pool_size = pool_size.max(1);
        self
    }

    pub fn with_default_timeout(mut self, timeout_ms: u64) -> Self {
        self.default_timeout_ms = timeout_ms;
        self
    }

    pub fn with_default_retries(mut self, max_retries: u32) -> Self {
        self.default_max_retries = max_retries;
        self
    }

    pub async fn register_endpoint(&self, config: EndpointConfig) -> Result<(), AppError> {
        info!(
            "Registering gRPC endpoint: version={}, address={}",
            config.model_version_id, config.address
        );

        self.endpoints.insert(config.model_version_id, config.clone());
        self.ensure_connection_pool(&config).await?;
        Ok(())
    }

    pub async fn unregister_endpoint(&self, model_version_id: Uuid) {
        info!("Unregistering gRPC endpoint: version={}", model_version_id);
        self.endpoints.remove(&model_version_id);
        self.connections.remove(&model_version_id);
    }

    pub fn has_endpoint(&self, model_version_id: Uuid) -> bool {
        self.endpoints.contains_key(&model_version_id)
    }

    pub fn list_endpoints(&self) -> Vec<(Uuid, String)> {
        self.endpoints
            .iter()
            .map(|entry| (*entry.key(), entry.value().address.clone()))
            .collect()
    }

    async fn ensure_connection_pool(
        &self,
        config: &EndpointConfig,
    ) -> Result<(), AppError> {
        if self.connections.contains_key(&config.model_version_id) {
            return Ok(());
        }

        info!(
            "Creating connection pool (size={}) for {}",
            self.pool_size, config.address
        );

        let mut pool = Vec::with_capacity(self.pool_size);

        for i in 0..self.pool_size {
            let channel = self
                .build_channel(&config.address, config.timeout_ms)
                .await
                .map_err(|e| {
                    AppError::ServiceUnavailable(format!(
                        "Failed to build gRPC channel {} (conn #{}): {}",
                        config.address, i, e
                    ))
                })?;

            let client = InferenceServiceClient::new(channel);
            pool.push(Arc::new(Mutex::new(GrpcConnection {
                client,
                last_used: std::time::Instant::now(),
            })));
        }

        self.connections.insert(config.model_version_id, pool);
        Ok(())
    }

    async fn build_channel(
        &self,
        address: &str,
        timeout_ms: u64,
    ) -> Result<Channel, AppError> {
        let endpoint_uri = if address.starts_with("http://") || address.starts_with("https://") {
            address.to_string()
        } else {
            format!("http://{}", address)
        };

        let endpoint = Endpoint::from_shared(endpoint_uri.clone())
            .map_err(|e| {
                AppError::ServiceUnavailable(format!(
                    "Invalid endpoint URI {}: {}",
                    endpoint_uri, e
                ))
            })?
            .connect_timeout(Duration::from_millis(timeout_ms.min(5_000)))
            .timeout(Duration::from_millis(timeout_ms))
            .tcp_keepalive(Some(Duration::from_secs(60)))
            .http2_keep_alive_interval(Duration::from_secs(120))
            .keep_alive_timeout(Duration::from_secs(20))
            .keep_alive_while_idle(true);

        endpoint.connect().await.map_err(|e| {
            AppError::ServiceUnavailable(format!(
                "Failed to connect to gRPC endpoint {}: {}",
                endpoint_uri, e
            ))
        })
    }

    async fn acquire_connection(
        &self,
        version_id: Uuid,
    ) -> Result<(Arc<Mutex<GrpcConnection>>, EndpointConfig), AppError> {
        let endpoint_config = self
            .endpoints
            .get(&version_id)
            .map(|entry| entry.value().clone())
            .ok_or_else(|| {
                AppError::ServiceUnavailable(format!(
                    "No gRPC endpoint registered for version {}",
                    version_id
                ))
            })?;

        if !self.connections.contains_key(&version_id) {
            self.ensure_connection_pool(&endpoint_config).await?;
        }

        let pool_guard = self
            .connections
            .get(&version_id)
            .ok_or_else(|| {
                AppError::ServiceUnavailable(format!(
                    "Connection pool not found for version {}",
                    version_id
                ))
            })?;

        let pool = pool_guard.value().clone();
        drop(pool_guard);

        let idx = (std::time::Instant::now().elapsed().as_nanos() as usize) % pool.len();
        Ok((pool[idx].clone(), endpoint_config))
    }

    pub async fn execute(
        &self,
        target: &RouteTarget,
        request: &InferenceRequest,
        schema: Option<&[IOSchema]>,
    ) -> Result<InferenceResponse, AppError> {
        let (connection, endpoint_config) = self.acquire_connection(target.model_version_id).await?;

        let timeout_ms = endpoint_config.timeout_ms.max(100);
        let max_retries = endpoint_config.max_retries;

        let grpc_request = build_grpc_request(request, "", schema, timeout_ms)?;

        let mut last_error: Option<AppError> = None;

        for attempt in 0..=max_retries {
            if attempt > 0 {
                let backoff_ms = (100u64 << attempt).min(2000);
                warn!(
                    "Retrying inference request {} (attempt {}/{}, version={}) after {}ms",
                    request.request_id,
                    attempt,
                    max_retries,
                    target.model_version_id,
                    backoff_ms
                );
                tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
            }

            let mut conn_guard = connection.lock().await;
            conn_guard.last_used = std::time::Instant::now();

            let req_copy = grpc_request.clone();
            let call_result = tokio::time::timeout(
                Duration::from_millis(timeout_ms),
                conn_guard.client.infer(req_copy),
            )
            .await;

            match call_result {
                Ok(Ok(grpc_resp)) => {
                    let response = grpc_resp.into_inner();
                    debug!(
                        "Inference succeeded for request {} on attempt {}",
                        request.request_id, attempt + 1
                    );
                    return parse_grpc_response(&response, request);
                }
                Ok(Err(status)) => {
                    error!(
                        "gRPC inference error for request {} (attempt {}): code={:?}, message={}",
                        request.request_id,
                        attempt + 1,
                        status.code(),
                        status.message()
                    );
                    last_error = Some(AppError::InferenceError(format!(
                        "gRPC error (code={:?}): {}",
                        status.code(),
                        status.message()
                    )));
                }
                Err(_elapsed) => {
                    warn!(
                        "Inference timeout for request {} (attempt {}): {}ms",
                        request.request_id,
                        attempt + 1,
                        timeout_ms
                    );
                    last_error = Some(AppError::InferenceTimeout(timeout_ms));
                }
            }
        }

        Err(last_error.unwrap_or_else(|| {
            AppError::InferenceError("Unknown inference execution error".to_string())
        }))
    }

    pub async fn health_check(&self, version_id: Uuid) -> Result<bool, AppError> {
        if !self.has_endpoint(version_id) {
            return Ok(false);
        }

        let (connection, endpoint_config) = self.acquire_connection(version_id).await?;

        let mut conn_guard = connection.lock().await;

        let status_req = inference::v1::ModelStatusRequest {
            model_name: String::new(),
            version: version_id.to_string(),
        };

        let result = tokio::time::timeout(
            Duration::from_millis(endpoint_config.timeout_ms.min(5000)),
            conn_guard.client.model_status(status_req),
        )
        .await;

        match result {
            Ok(Ok(_)) => Ok(true),
            Ok(Err(status)) => {
                warn!("Health check failed for {}: {}", version_id, status.message());
                Ok(false)
            }
            Err(_) => {
                warn!("Health check timeout for {}", version_id);
                Ok(false)
            }
        }
    }

    pub async fn check_all_endpoints(&self) -> HashMap<Uuid, bool> {
        let version_ids: Vec<Uuid> = self.endpoints.iter().map(|e| *e.key()).collect();
        let mut results = HashMap::new();

        for vid in version_ids {
            results.insert(vid, self.health_check(vid).await.unwrap_or(false));
        }

        results
    }
}

impl Default for RuntimeClient {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Debug for RuntimeClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RuntimeClient")
            .field("endpoint_count", &self.endpoints.len())
            .field("pool_size", &self.pool_size)
            .field("default_timeout_ms", &self.default_timeout_ms)
            .finish()
    }
}

impl std::fmt::Debug for GrpcConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GrpcConnection")
            .field("last_used", &self.last_used)
            .finish()
    }
}

use inference::v1::registry_service_client::RegistryServiceClient;
use inference::v1::{GetModelRequest, ListModelsRequest, ModelMetadata, ModelType};

#[derive(Debug, Clone)]
pub struct RegistryConfig {
    pub address: String,
    pub timeout_ms: u64,
    pub max_retries: u32,
}

impl Default for RegistryConfig {
    fn default() -> Self {
        Self {
            address: "http://localhost:50051".to_string(),
            timeout_ms: 10_000,
            max_retries: 2,
        }
    }
}

#[derive(Clone)]
pub struct RegistryClient {
    config: RegistryConfig,
    client: Arc<Mutex<Option<RegistryServiceClient<Channel>>>>,
}

impl RegistryClient {
    pub fn new(config: RegistryConfig) -> Self {
        Self {
            config,
            client: Arc::new(Mutex::new(None)),
        }
    }

    pub fn with_address(address: impl Into<String>) -> Self {
        Self::new(RegistryConfig {
            address: address.into(),
            ..Default::default()
        })
    }

    async fn get_client(&self) -> Result<RegistryServiceClient<Channel>, AppError> {
        let mut guard = self.client.lock().await;
        if let Some(client) = guard.as_ref() {
            return Ok(client.clone());
        }

        let endpoint_uri = if self.config.address.starts_with("http://")
            || self.config.address.starts_with("https://")
        {
            self.config.address.clone()
        } else {
            format!("http://{}", self.config.address)
        };

        let endpoint = Endpoint::from_shared(endpoint_uri.clone())
            .map_err(|e| {
                AppError::ServiceUnavailable(format!(
                    "Invalid registry endpoint {}: {}",
                    endpoint_uri, e
                ))
            })?
            .connect_timeout(Duration::from_millis(self.config.timeout_ms.min(5_000)))
            .timeout(Duration::from_millis(self.config.timeout_ms))
            .tcp_keepalive(Some(Duration::from_secs(60)));

        let channel = endpoint.connect().await.map_err(|e| {
            AppError::ServiceUnavailable(format!(
                "Failed to connect to registry {}: {}",
                endpoint_uri, e
            ))
        })?;

        let client = RegistryServiceClient::new(channel);
        *guard = Some(client.clone());
        Ok(client)
    }

    pub async fn reconnect(&self) -> Result<(), AppError> {
        let mut guard = self.client.lock().await;
        *guard = None;
        drop(guard);
        let _ = self.get_client().await?;
        Ok(())
    }

    pub async fn get_model(
        &self,
        model_name: &str,
        version: Option<&str>,
    ) -> Result<Option<ModelMetadata>, AppError> {
        let mut last_error: Option<AppError> = None;

        for attempt in 0..=self.config.max_retries {
            if attempt > 0 {
                let backoff_ms = (100u64 << attempt).min(2000);
                tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
            }

            match self.get_client().await {
                Ok(mut client) => {
                    let req = GetModelRequest {
                        query: Some(inference::v1::get_model_request::Query::ModelName(
                            model_name.to_string(),
                        )),
                        version: version.unwrap_or_default().to_string(),
                    };

                    let result = tokio::time::timeout(
                        Duration::from_millis(self.config.timeout_ms),
                        client.get_model(req),
                    )
                    .await;

                    match result {
                        Ok(Ok(resp)) => {
                            let inner = resp.into_inner();
                            if !inner.error.is_empty() {
                                last_error = Some(AppError::ModelNotFound(format!(
                                    "Registry error for model {}: {}",
                                    model_name, inner.error
                                )));
                                continue;
                            }
                            return Ok(inner.metadata);
                        }
                        Ok(Err(status)) => {
                            last_error = Some(AppError::ServiceUnavailable(format!(
                                "Registry gRPC error (code={:?}): {}",
                                status.code(),
                                status.message()
                            )));
                        }
                        Err(_) => {
                            last_error = Some(AppError::InferenceTimeout(self.config.timeout_ms));
                        }
                    }
                }
                Err(e) => {
                    last_error = Some(e);
                }
            }

            if attempt < self.config.max_retries {
                let _ = self.reconnect().await;
            }
        }

        Err(last_error.unwrap_or_else(|| {
            AppError::Internal("Unknown registry client error".to_string())
        }))
    }

    pub async fn list_models(
        &self,
        model_type: Option<i32>,
        tags: Option<Vec<String>>,
        page_size: i32,
        page_token: String,
    ) -> Result<(Vec<ModelMetadata>, String, i32), AppError> {
        let mut client = self.get_client().await?;

        let req = ListModelsRequest {
            model_type: model_type.unwrap_or(0),
            tags: tags.unwrap_or_default(),
            labels: HashMap::new(),
            page_size,
            page_token,
            include_versions: true,
        };

        let resp = client
            .list_models(req)
            .await
            .map_err(|e| {
                AppError::ServiceUnavailable(format!(
                    "Failed to list models from registry: {}",
                    e.message()
                ))
            })?
            .into_inner();

        if !resp.error.is_empty() {
            return Err(AppError::ServiceUnavailable(format!(
                "Registry list error: {}",
                resp.error
            )));
        }

        Ok((resp.models, resp.next_page_token, resp.total_count))
    }

    pub async fn check_health(&self) -> Result<bool, AppError> {
        match self.get_client().await {
            Ok(mut client) => {
                let req = GetModelRequest {
                    query: Some(inference::v1::get_model_request::Query::ModelName(
                        "_health_check".to_string(),
                    )),
                    version: String::new(),
                };

                let result = tokio::time::timeout(
                    Duration::from_millis(self.config.timeout_ms.min(3000)),
                    client.get_model(req),
                )
                .await;

                match result {
                    Ok(_) => Ok(true),
                    Err(_) => Ok(false),
                }
            }
            Err(_) => Ok(false),
        }
    }
}

impl std::fmt::Debug for RegistryClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RegistryClient")
            .field("config", &self.config)
            .finish()
    }
}

#[derive(Debug, Clone)]
pub struct RuntimeNodeInfo {
    pub node_id: String,
    pub address: String,
    pub model_version_id: Uuid,
    pub gpu_id: Option<String>,
    pub healthy: bool,
    pub last_health_check: Option<Instant>,
    pub total_requests: u64,
    pub failed_requests: u64,
    pub avg_latency_ms: f64,
}

#[derive(Debug, Clone)]
pub struct ClientManagerConfig {
    pub health_check_interval_secs: u64,
    pub unhealthy_threshold: u32,
    pub connection_pool_size: usize,
    pub default_timeout_ms: u64,
    pub default_max_retries: u32,
}

impl Default for ClientManagerConfig {
    fn default() -> Self {
        Self {
            health_check_interval_secs: 10,
            unhealthy_threshold: 3,
            connection_pool_size: 4,
            default_timeout_ms: 30_000,
            default_max_retries: 2,
        }
    }
}

#[derive(Clone)]
pub struct ClientManager {
    runtime_client: RuntimeClient,
    nodes: Arc<DashMap<Uuid, RuntimeNodeInfo>>,
    node_failure_counts: Arc<DashMap<Uuid, u32>>,
    config: ClientManagerConfig,
    shutdown_tx: Arc<Mutex<Option<tokio::sync::broadcast::Sender<()>>>>,
}

impl ClientManager {
    pub fn new() -> Self {
        Self::with_config(ClientManagerConfig::default())
    }

    pub fn with_config(config: ClientManagerConfig) -> Self {
        let runtime_client = RuntimeClient::new()
            .with_pool_size(config.connection_pool_size)
            .with_default_timeout(config.default_timeout_ms)
            .with_default_retries(config.default_max_retries);

        Self {
            runtime_client,
            nodes: Arc::new(DashMap::new()),
            node_failure_counts: Arc::new(DashMap::new()),
            config,
            shutdown_tx: Arc::new(Mutex::new(None)),
        }
    }

    pub fn runtime_client(&self) -> &RuntimeClient {
        &self.runtime_client
    }

    pub async fn register_node(
        &self,
        node_id: impl Into<String>,
        address: impl Into<String>,
        model_version_id: Uuid,
        gpu_id: Option<String>,
    ) -> Result<(), AppError> {
        let node_id = node_id.into();
        let address = address.into();

        info!(
            "Registering runtime node: node_id={}, address={}, version={}",
            node_id, address, model_version_id
        );

        let endpoint_config = EndpointConfig {
            address: address.clone(),
            model_version_id,
            max_retries: self.config.default_max_retries,
            timeout_ms: self.config.default_timeout_ms,
        };

        self.runtime_client.register_endpoint(endpoint_config).await?;

        let node_info = RuntimeNodeInfo {
            node_id: node_id.into(),
            address,
            model_version_id,
            gpu_id,
            healthy: true,
            last_health_check: Some(Instant::now()),
            total_requests: 0,
            failed_requests: 0,
            avg_latency_ms: 0.0,
        };

        self.nodes.insert(model_version_id, node_info);
        self.node_failure_counts.insert(model_version_id, 0);

        Ok(())
    }

    pub fn unregister_node(&self, model_version_id: Uuid) {
        info!("Unregistering runtime node: version={}", model_version_id);
        self.nodes.remove(&model_version_id);
        self.node_failure_counts.remove(&model_version_id);
        let rt = self.runtime_client.clone();
        tokio::spawn(async move {
            rt.unregister_endpoint(model_version_id).await;
        });
    }

    pub fn get_node_info(&self, model_version_id: Uuid) -> Option<RuntimeNodeInfo> {
        self.nodes.get(&model_version_id).map(|e| e.value().clone())
    }

    pub fn list_healthy_nodes(&self, model_version_id: Option<Uuid>) -> Vec<RuntimeNodeInfo> {
        self.nodes
            .iter()
            .filter(|e| {
                if !e.value().healthy {
                    return false;
                }
                match model_version_id {
                    Some(id) => e.value().model_version_id == id,
                    None => true,
                }
            })
            .map(|e| e.value().clone())
            .collect()
    }

    pub fn is_healthy(&self, model_version_id: Uuid) -> bool {
        self.nodes
            .get(&model_version_id)
            .map(|e| e.value().healthy)
            .unwrap_or(false)
    }

    pub async fn perform_health_checks(&self) -> HashMap<Uuid, bool> {
        let version_ids: Vec<Uuid> = self.nodes.iter().map(|e| *e.key()).collect();
        let mut results = HashMap::new();

        for vid in version_ids {
            let healthy = self.runtime_client.health_check(vid).await.unwrap_or(false);
            results.insert(vid, healthy);

            if let Some(mut node) = self.nodes.get_mut(&vid) {
                node.healthy = healthy;
                node.last_health_check = Some(Instant::now());
            }

            if !healthy {
                let count = self
                    .node_failure_counts
                    .entry(vid)
                    .and_modify(|c| *c += 1)
                    .or_insert(1);

                if *count >= self.config.unhealthy_threshold {
                    warn!(
                        "Node {} marked as unhealthy after {} consecutive failures",
                        vid, *count
                    );
                }
            } else {
                self.node_failure_counts.insert(vid, 0);
            }
        }

        results
    }

    pub fn record_request_metrics(
        &self,
        model_version_id: Uuid,
        success: bool,
        latency_ms: u64,
    ) {
        if let Some(mut node) = self.nodes.get_mut(&model_version_id) {
            node.total_requests += 1;
            if !success {
                node.failed_requests += 1;
            }
            let alpha = 0.1;
            node.avg_latency_ms =
                node.avg_latency_ms * (1.0 - alpha) + latency_ms as f64 * alpha;
        }
    }

    pub fn get_healthy_version_ids(&self) -> Vec<Uuid> {
        self.nodes
            .iter()
            .filter(|e| e.value().healthy)
            .map(|e| *e.key())
            .collect()
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    pub fn healthy_node_count(&self) -> usize {
        self.nodes.iter().filter(|e| e.value().healthy).count()
    }

    pub async fn start_health_check_loop(self: Arc<Self>) {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<()>(1);
        {
            let mut guard = self.shutdown_tx.lock().await;
            *guard = Some(tx);
        }

        let interval = Duration::from_secs(self.config.health_check_interval_secs.max(1));

        info!(
            "Starting client manager health check loop (interval={:?})",
            interval
        );

        loop {
            tokio::select! {
                _ = tokio::time::sleep(interval) => {
                    let results = self.perform_health_checks().await;
                    let healthy = results.values().filter(|&&v| v).count();
                    debug!(
                        "Health check complete: {}/{} nodes healthy",
                        healthy,
                        results.len()
                    );
                }
                _ = rx.recv() => {
                    info!("Client manager health check loop shutdown");
                    break;
                }
            }
        }
    }

    pub async fn shutdown(&self) {
        let mut guard = self.shutdown_tx.lock().await;
        if let Some(tx) = guard.take() {
            let _ = tx.send(());
        }
        info!("ClientManager shutdown initiated");
    }
}

impl Default for ClientManager {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Debug for ClientManager {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ClientManager")
            .field("total_nodes", &self.nodes.len())
            .field("healthy_nodes", &self.healthy_node_count())
            .field("config", &self.config)
            .finish()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackendTarget {
    pub model_version_id: Uuid,
    pub version: String,
    pub backend_address: String,
    pub gpu_id: Option<String>,
    pub healthy: bool,
}

