use std::net::IpAddr;
use std::collections::HashMap;
use std::time::Instant;

use base64::{engine::general_purpose, Engine as _};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Sha256, Digest};
use uuid::Uuid;

use crate::error::CdnResult;
use crate::models::ContentType;

pub fn generate_id() -> Uuid {
    Uuid::new_v4()
}

pub fn hash_content(content: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(content);
    let result = hasher.finalize();
    hex::encode(result)
}

pub fn generate_cache_key(
    domain: &str,
    path: &str,
    query_params: &HashMap<String, String>,
    ignore_params: &[String],
    vary_by_ua: bool,
    vary_by_referer: bool,
    user_agent: Option<&str>,
    referer: Option<&str>,
) -> String {
    let mut filtered_query: Vec<(&String, &String)> = query_params
        .iter()
        .filter(|(k, _)| !ignore_params.contains(k))
        .collect();
    filtered_query.sort_by(|a, b| a.0.cmp(b.0));

    let query_string: String = filtered_query
        .iter()
        .map(|(k, v)| format!("{}={}", k, v))
        .collect::<Vec<_>>()
        .join("&");

    let mut key = format!("{}:{}", domain, path);
    if !query_string.is_empty() {
        key = format!("{}?{}", key, query_string);
    }

    if vary_by_ua {
        key = format!("{}:ua:{}", key, user_agent.unwrap_or("unknown"));
    }

    if vary_by_referer {
        key = format!("{}:ref:{}", key, referer.unwrap_or("unknown"));
    }

    let mut hasher = Sha256::new();
    hasher.update(key.as_bytes());
    let result = hasher.finalize();
    hex::encode(result)
}

pub fn calculate_bandwidth_utilization(current_mbps: f64, capacity_mbps: u64) -> f64 {
    if capacity_mbps == 0 {
        return 1.0;
    }
    current_mbps / (capacity_mbps as f64)
}

pub fn calculate_dynamic_weight(
    base_weight: u32,
    load: f64,
    bandwidth_utilization: f64,
    error_rate: f64,
) -> u32 {
    let load_factor = 1.0 - load.min(1.0);
    let bandwidth_factor = 1.0 - bandwidth_utilization.min(1.0);
    let error_factor = 1.0 - error_rate.min(1.0);

    let combined_factor = (load_factor + bandwidth_factor + error_factor) / 3.0;
    let dynamic_weight = (base_weight as f64) * combined_factor;

    dynamic_weight.max(1.0) as u32
}

pub fn geodistance(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let r = 6371.0;
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();

    let a = (d_lat / 2.0).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon / 2.0).sin().powi(2);

    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());

    r * c
}

pub fn is_simple_pattern(pattern: &str) -> bool {
    !pattern.contains('*')
}

pub fn is_simple_prefix(pattern: &str) -> bool {
    if !pattern.ends_with("/*") {
        return false;
    }
    let prefix = &pattern[..pattern.len() - 2];
    !prefix.contains('*')
}

fn match_glob_part(pattern: &str, text: &str) -> bool {
    let pattern_chars: Vec<char> = pattern.chars().collect();
    let text_chars: Vec<char> = text.chars().collect();
    
    let mut p = 0;
    let mut t = 0;
    let mut star_idx = -1i32;
    let mut match_idx = 0i32;
    
    while t < text_chars.len() {
        if p < pattern_chars.len() && pattern_chars[p] == '*' {
            star_idx = p as i32;
            match_idx = t as i32;
            p += 1;
        } else if p < pattern_chars.len() && (pattern_chars[p] == '?' || pattern_chars[p] == text_chars[t]) {
            p += 1;
            t += 1;
        } else if star_idx != -1 {
            p = (star_idx + 1) as usize;
            match_idx += 1;
            t = match_idx as usize;
        } else {
            return false;
        }
    }
    
    while p < pattern_chars.len() && pattern_chars[p] == '*' {
        p += 1;
    }
    
    p == pattern_chars.len()
}

pub fn match_path_pattern(pattern: &str, path: &str) -> bool {
    if is_simple_pattern(pattern) {
        return path == pattern || path.starts_with(&format!("{}/", pattern));
    }
    
    if is_simple_prefix(pattern) {
        let prefix = &pattern[..pattern.len() - 2];
        if !path.starts_with(&format!("{}/", prefix)) {
            return false;
        }
        let remaining = &path[prefix.len() + 1..];
        return !remaining.contains('/');
    }

    let pattern_parts: Vec<&str> = pattern.split('/').collect();
    let path_parts: Vec<&str> = path.split('/').collect();

    let mut i = 0;
    let mut j = 0;

    while i < pattern_parts.len() && j < path_parts.len() {
        match pattern_parts[i] {
            "**" => {
                if i == pattern_parts.len() - 1 {
                    return true;
                }
                i += 1;
                while j < path_parts.len() {
                    if match_path_pattern(&pattern_parts[i..].join("/"), &path_parts[j..].join("/")) {
                        return true;
                    }
                    j += 1;
                }
                return false;
            }
            "*" => {
                i += 1;
                j += 1;
            }
            part if part == path_parts[j] => {
                i += 1;
                j += 1;
            }
            part if part.contains('*') && match_glob_part(part, path_parts[j]) => {
                i += 1;
                j += 1;
            }
            _ => return false,
        }
    }

    i == pattern_parts.len() && j == path_parts.len()
}

pub fn parse_ip(ip_str: &str) -> Option<IpAddr> {
    ip_str.parse().ok()
}

pub fn encrypt_string(plaintext: &str, key: &str) -> CdnResult<String> {
    let key_bytes = key.as_bytes();
    let mut result = Vec::with_capacity(plaintext.len());
    
    for (i, byte) in plaintext.bytes().enumerate() {
        result.push(byte ^ key_bytes[i % key_bytes.len()]);
    }
    
    Ok(general_purpose::STANDARD.encode(&result))
}

pub fn decrypt_string(ciphertext: &str, key: &str) -> CdnResult<String> {
    let encrypted = general_purpose::STANDARD.decode(ciphertext)
        .map_err(|e| crate::error::CdnError::EncryptionError(e.to_string()))?;
    
    let key_bytes = key.as_bytes();
    let mut result = Vec::with_capacity(encrypted.len());
    
    for (i, &byte) in encrypted.iter().enumerate() {
        result.push(byte ^ key_bytes[i % key_bytes.len()]);
    }
    
    String::from_utf8(result)
        .map_err(|e| crate::error::CdnError::EncryptionError(e.to_string()))
}

pub fn get_timestamp_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub struct RateLimiter {
    permits: u32,
    max_permits: u32,
    last_refill: Instant,
    refill_rate_per_second: u32,
}

impl RateLimiter {
    pub fn new(max_permits: u32, refill_rate_per_second: u32) -> Self {
        RateLimiter {
            permits: max_permits,
            max_permits,
            last_refill: Instant::now(),
            refill_rate_per_second,
        }
    }

    pub fn try_acquire(&mut self) -> bool {
        self.refill();
        if self.permits > 0 {
            self.permits -= 1;
            true
        } else {
            false
        }
    }

    fn refill(&mut self) {
        let elapsed = self.last_refill.elapsed().as_secs();
        if elapsed > 0 {
            let new_permits = elapsed * self.refill_rate_per_second as u64;
            self.permits = (self.permits as u64 + new_permits).min(self.max_permits as u64) as u32;
            self.last_refill = Instant::now();
        }
    }
}

pub fn calculate_traffic_change(current: f64, baseline: f64) -> f64 {
    if baseline == 0.0 {
        return if current > 0.0 { f64::INFINITY } else { 0.0 };
    }
    current / baseline
}

pub fn format_bytes(bytes: u64) -> String {
    const UNITS: &[&str] = &["B", "KB", "MB", "GB", "TB", "PB"];
    let mut size = bytes as f64;
    let mut unit_index = 0;

    while size >= 1024.0 && unit_index < UNITS.len() - 1 {
        size /= 1024.0;
        unit_index += 1;
    }

    format!("{:.2} {}", size, UNITS[unit_index])
}

pub fn content_type_from_url(path: &str) -> ContentType {
    let lower = path.to_lowercase();
    if lower.ends_with(".m3u8") || lower.ends_with(".ts") || lower.ends_with(".flv") {
        ContentType::LiveStream
    } else if lower.ends_with(".mp4") || lower.ends_with(".mkv") || lower.ends_with(".avi") {
        ContentType::Vod
    } else {
        ContentType::StaticAsset
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ConfigDiffOp {
    Add,
    Remove,
    Replace,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConfigDiff {
    pub path: String,
    pub op: ConfigDiffOp,
    pub value: Option<Value>,
}

pub fn compute_json_diff(old: &Value, new: &Value) -> Vec<ConfigDiff> {
    let mut diffs = Vec::new();
    compute_json_diff_recursive(old, new, String::new(), &mut diffs);
    diffs
}

fn compute_json_diff_recursive(old: &Value, new: &Value, path: String, diffs: &mut Vec<ConfigDiff>) {
    if old == new {
        return;
    }

    match (old, new) {
        (Value::Object(old_obj), Value::Object(new_obj)) => {
            for key in old_obj.keys().chain(new_obj.keys()).collect::<std::collections::HashSet<_>>() {
                let new_path = if path.is_empty() {
                    format!(".{}", key)
                } else {
                    format!("{}.{}", path, key)
                };

                match (old_obj.get(key), new_obj.get(key)) {
                    (Some(old_val), Some(new_val)) => {
                        compute_json_diff_recursive(old_val, new_val, new_path, diffs);
                    }
                    (Some(old_val), None) => {
                        diffs.push(ConfigDiff {
                            path: new_path,
                            op: ConfigDiffOp::Remove,
                            value: Some(old_val.clone()),
                        });
                    }
                    (None, Some(new_val)) => {
                        diffs.push(ConfigDiff {
                            path: new_path,
                            op: ConfigDiffOp::Add,
                            value: Some(new_val.clone()),
                        });
                    }
                    (None, None) => {}
                }
            }
        }
        (Value::Array(old_arr), Value::Array(new_arr)) => {
            if old_arr.len() != new_arr.len() {
                diffs.push(ConfigDiff {
                    path,
                    op: ConfigDiffOp::Replace,
                    value: Some(new.clone()),
                });
                return;
            }

            for (i, (old_val, new_val)) in old_arr.iter().zip(new_arr.iter()).enumerate() {
                let new_path = format!("{}[{}]", path, i);
                compute_json_diff_recursive(old_val, new_val, new_path, diffs);
            }
        }
        _ => {
            diffs.push(ConfigDiff {
                path,
                op: ConfigDiffOp::Replace,
                value: Some(new.clone()),
            });
        }
    }
}

pub fn merge_json_diff(target: &mut Value, diffs: &[ConfigDiff]) {
    for diff in diffs {
        apply_diff(target, diff);
    }
}

fn apply_diff(target: &mut Value, diff: &ConfigDiff) {
    let path_parts = parse_path(&diff.path);
    if path_parts.is_empty() {
        return;
    }

    let ptr: *mut Value = target;
    let mut current = ptr;

    for i in 0..path_parts.len() {
        let is_last = i == path_parts.len() - 1;
        let part = &path_parts[i];
        let current_ref = unsafe { &mut *current };

        match part {
            PathPart::Key(key) => {
                if is_last {
                    apply_key_op(current_ref, key, &diff.op, &diff.value);
                } else {
                    if current_ref.is_object() {
                        let obj = current_ref.as_object_mut().unwrap();
                        if !obj.contains_key(key) {
                            obj.insert(key.clone(), Value::Object(serde_json::Map::new()));
                        }
                        if let Some(val) = obj.get_mut(key) {
                            current = val as *mut Value;
                        }
                    }
                }
            }
            PathPart::Index(idx) => {
                if is_last {
                    apply_index_op(current_ref, *idx, &diff.op, &diff.value);
                } else {
                    if current_ref.is_array() {
                        let arr = current_ref.as_array_mut().unwrap();
                        if *idx < arr.len() {
                            current = &mut arr[*idx] as *mut Value;
                        }
                    }
                }
            }
        }
    }
}

fn apply_key_op(current: &mut Value, key: &str, op: &ConfigDiffOp, value: &Option<Value>) {
    if let Some(obj) = current.as_object_mut() {
        match op {
            ConfigDiffOp::Add | ConfigDiffOp::Replace => {
                if let Some(val) = value {
                    obj.insert(key.to_string(), val.clone());
                }
            }
            ConfigDiffOp::Remove => {
                obj.remove(key);
            }
        }
    }
}

fn apply_index_op(current: &mut Value, idx: usize, op: &ConfigDiffOp, value: &Option<Value>) {
    if let Some(arr) = current.as_array_mut() {
        match op {
            ConfigDiffOp::Add | ConfigDiffOp::Replace => {
                if let Some(val) = value {
                    if idx < arr.len() {
                        arr[idx] = val.clone();
                    } else {
                        arr.push(val.clone());
                    }
                }
            }
            ConfigDiffOp::Remove => {
                if idx < arr.len() {
                    arr.remove(idx);
                }
            }
        }
    }
}

fn parse_path(path: &str) -> Vec<PathPart> {
    let mut parts = Vec::new();
    let chars: Vec<char> = path.chars().collect();
    let mut i = 0;

    while i < chars.len() {
        if chars[i] == '.' {
            i += 1;
            let mut key = String::new();
            while i < chars.len() && chars[i] != '.' && chars[i] != '[' {
                key.push(chars[i]);
                i += 1;
            }
            if !key.is_empty() {
                parts.push(PathPart::Key(key));
            }
        } else if chars[i] == '[' {
            i += 1;
            let mut idx = String::new();
            while i < chars.len() && chars[i] != ']' {
                idx.push(chars[i]);
                i += 1;
            }
            if i < chars.len() {
                i += 1;
            }
            if let Ok(idx_num) = idx.parse::<usize>() {
                parts.push(PathPart::Index(idx_num));
            }
        } else {
            i += 1;
        }
    }

    parts
}

enum PathPart {
    Key(String),
    Index(usize),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hash_content() {
        let content = b"test content";
        let hash = hash_content(content);
        assert_eq!(hash.len(), 64);
    }

    #[test]
    fn test_match_path_pattern() {
        assert!(match_path_pattern("/api/*", "/api/v1"));
        assert!(match_path_pattern("/api/**", "/api/v1/users"));
        assert!(!match_path_pattern("/api/*", "/api/v1/users"));
        assert!(match_path_pattern("**/*.js", "/static/js/app.js"));
    }

    #[test]
    fn test_geodistance() {
        let distance = geodistance(0.0, 0.0, 0.0, 1.0);
        assert!(distance > 0.0);
    }

    #[test]
    fn test_format_bytes() {
        assert_eq!(format_bytes(1024), "1.00 KB");
        assert_eq!(format_bytes(1048576), "1.00 MB");
    }
}
