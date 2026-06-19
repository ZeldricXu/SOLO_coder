use crate::error::AppError;
use crate::types::IOSchema;
use rand::Rng;
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub fn generate_id() -> String {
    Uuid::new_v4().to_string()
}

pub fn generate_uuid() -> Uuid {
    Uuid::new_v4()
}

pub fn hash_user_id(user_id: &str, salt: Option<&str>) -> String {
    let mut hasher = Sha256::new();
    if let Some(s) = salt {
        hasher.update(s.as_bytes());
    }
    hasher.update(user_id.as_bytes());
    let result = hasher.finalize();
    format!("{:x}", result)
}

pub fn hash_user_id_to_bucket(user_id: &str, bucket_count: u32) -> u32 {
    let mut hasher = Sha256::new();
    hasher.update(user_id.as_bytes());
    let result = hasher.finalize();
    let mut bytes = [0u8; 4];
    bytes.copy_from_slice(&result[..4]);
    let num = u32::from_be_bytes(bytes);
    num % bucket_count
}

pub fn validate_schema(
    inputs: &serde_json::Value,
    schema: &[IOSchema],
) -> Result<(), AppError> {
    let input_obj = inputs
        .as_object()
        .ok_or_else(|| AppError::SchemaValidation("inputs must be an object".to_string()))?;

    for io_schema in schema {
        let value = input_obj.get(&io_schema.name).ok_or_else(|| {
            AppError::SchemaValidation(format!("missing required input: {}", io_schema.name))
        })?;

        let actual_shape = get_tensor_shape(value)?;

        if !check_shape_compatibility(&actual_shape, &io_schema.shape) {
            return Err(AppError::SchemaValidation(format!(
                "input '{}' shape mismatch: expected {:?}, got {:?}",
                io_schema.name, io_schema.shape, actual_shape
            )));
        }

        if !check_dtype_compatibility(value, &io_schema.dtype) {
            return Err(AppError::SchemaValidation(format!(
                "input '{}' dtype mismatch: expected {}, got incompatible type",
                io_schema.name, io_schema.dtype
            )));
        }
    }

    Ok(())
}

fn get_tensor_shape(value: &serde_json::Value) -> Result<Vec<i64>, AppError> {
    match value {
        serde_json::Value::Array(arr) => {
            if arr.is_empty() {
                Ok(vec![0])
            } else {
                let inner_shape = get_tensor_shape(&arr[0])?;
                let mut shape = vec![arr.len() as i64];
                shape.extend(inner_shape);
                Ok(shape)
            }
        }
        _ => Ok(vec![]),
    }
}

fn check_shape_compatibility(actual: &[i64], expected: &[i64]) -> bool {
    if actual.len() != expected.len() {
        return false;
    }
    for (a, e) in actual.iter().zip(expected.iter()) {
        if *e != -1 && *a != *e {
            return false;
        }
    }
    true
}

fn check_dtype_compatibility(value: &serde_json::Value, dtype: &str) -> bool {
    match dtype {
        "float32" | "float64" | "f32" | "f64" => check_nested_numbers(value, |v| v.is_f64()),
        "int32" | "int64" | "i32" | "i64" => check_nested_numbers(value, |v| v.is_i64()),
        "uint8" | "uint16" | "uint32" | "uint64" => {
            check_nested_numbers(value, |v| v.is_u64() || v.is_i64())
        }
        "bool" | "boolean" => check_nested_numbers(value, |v| v.is_boolean()),
        "string" => check_nested_numbers(value, |v| v.is_string()),
        _ => true,
    }
}

fn check_nested_numbers<F>(value: &serde_json::Value, check: F) -> bool
where
    F: Fn(&serde_json::Value) -> bool + Copy,
{
    match value {
        serde_json::Value::Array(arr) => arr.iter().all(|v| check_nested_numbers(v, check)),
        _ => check(value),
    }
}

pub fn mask_sensitive_data(data: &str, visible_prefix: usize) -> String {
    if data.len() <= visible_prefix {
        return "*".repeat(data.len());
    }
    let prefix = &data[..visible_prefix];
    let masked = "*".repeat(data.len() - visible_prefix);
    format!("{}{}", prefix, masked)
}

pub fn mask_api_key(api_key: &str) -> String {
    mask_sensitive_data(api_key, 8)
}

pub fn generate_random_string(length: usize) -> String {
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::thread_rng();
    (0..length)
        .map(|_| {
            let idx = rng.gen_range(0..CHARSET.len());
            CHARSET[idx] as char
        })
        .collect()
}

pub fn generate_api_key() -> String {
    format!("sk-{}", generate_random_string(48))
}

pub fn truncate_string(s: &str, max_len: usize) -> String {
    if s.len() <= max_len {
        s.to_string()
    } else {
        let mut truncated: String = s.chars().take(max_len).collect();
        truncated.push_str("...");
        truncated
    }
}
