use crate::config::{CustomFieldConfig, CustomFormatConfig};
use crate::{LogLevel, LogRecord};
use chrono::{DateTime, NaiveDateTime, TimeZone, Utc};
use nom::bytes::complete::{tag, take_till, take_while, take_while1};
use nom::character::complete::{digit1, multispace0, none_of, one_of};
use nom::combinator::{map, opt, recognize, verify};
use nom::error::{Error, ErrorKind};
use nom::sequence::{delimited, terminated};
use nom::{Err, IResult};
use serde_json::Value;
use std::collections::HashMap;
use std::time::{Duration, Instant};
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
pub enum FieldValue {
    String(String),
    Int(i64),
    Float(f64),
    Bool(bool),
    Timestamp(DateTime<Utc>),
    Null,
}

impl FieldValue {
    pub fn as_string(&self) -> String {
        match self {
            FieldValue::String(s) => s.clone(),
            FieldValue::Int(i) => i.to_string(),
            FieldValue::Float(f) => f.to_string(),
            FieldValue::Bool(b) => b.to_string(),
            FieldValue::Timestamp(dt) => dt.to_rfc3339(),
            FieldValue::Null => String::new(),
        }
    }

    pub fn as_float(&self) -> Option<f64> {
        match self {
            FieldValue::Int(i) => Some(*i as f64),
            FieldValue::Float(f) => Some(*f),
            FieldValue::String(s) => s.parse::<f64>().ok(),
            _ => None,
        }
    }

    pub fn as_timestamp(&self) -> Option<DateTime<Utc>> {
        match self {
            FieldValue::Timestamp(dt) => Some(*dt),
            _ => None,
        }
    }
}

pub struct CompiledFormat {
    pub config: CustomFormatConfig,
    pub delimiter_char: char,
}

impl CompiledFormat {
    pub fn new(config: CustomFormatConfig) -> Self {
        let delim_char = if config.delimiter.is_empty() {
            '|'
        } else {
            config.delimiter.chars().next().unwrap_or('|')
        };
        Self {
            config,
            delimiter_char: delim_char,
        }
    }

    pub fn id(&self) -> &str {
        &self.config.id
    }

    pub fn name(&self) -> &str {
        &self.config.name
    }

    pub fn prefix_matches(&self, line: &str) -> bool {
        match &self.config.line_prefix {
            Some(prefix) => line.starts_with(prefix),
            None => true,
        }
    }

    pub fn parse_line(&self, line: &str) -> Result<HashMap<String, FieldValue>, String> {
        let mut result = HashMap::new();
        let trimmed = if self.config.trim_whitespace {
            line.trim()
        } else {
            line
        };

        if let Some(prefix) = &self.config.line_prefix {
            if let Some(rest) = trimmed.strip_prefix(prefix) {
                let rest = if self.config.trim_whitespace {
                    rest.trim_start()
                } else {
                    rest
                };
                return self.parse_body(rest, &mut result);
            } else {
                return Err(format!("Prefix '{}' mismatch", prefix));
            }
        }

        self.parse_body(trimmed, &mut result)
    }

    fn parse_body(
        &self,
        body: &str,
        result: &mut HashMap<String, FieldValue>,
    ) -> Result<HashMap<String, FieldValue>, String> {
        let fields: Vec<&str> = if self.config.delimiter.len() == 1 {
            body.split(self.delimiter_char).collect()
        } else {
            body.split(self.config.delimiter.as_str()).collect()
        };

        for (idx, field_cfg) in self.config.fields.iter().enumerate() {
            let raw_value = if idx < fields.len() {
                let v = fields[idx];
                if self.config.trim_whitespace {
                    v.trim().to_string()
                } else {
                    v.to_string()
                }
            } else {
                if !field_cfg.optional {
                    return Err(format!(
                        "Field '{}' at position {} is required but not found",
                        field_cfg.name, idx
                    ));
                }
                if let Some(default) = &field_cfg.default_value {
                    default.clone()
                } else {
                    continue;
                }
            };

            if raw_value.is_empty() && field_cfg.optional {
                if let Some(default) = &field_cfg.default_value {
                    result.insert(field_cfg.name.clone(), FieldValue::String(default.clone()));
                }
                continue;
            }

            let parsed_value = match field_cfg.field_type.to_lowercase().as_str() {
                "string" | "str" => FieldValue::String(raw_value),
                "int" | "integer" | "i64" => {
                    if let Ok(i) = raw_value.parse::<i64>() {
                        FieldValue::Int(i)
                    } else if let Ok(f) = raw_value.parse::<f64>() {
                        FieldValue::Int(f as i64)
                    } else {
                        return Err(format!(
                            "Field '{}' cannot parse '{}' as int",
                            field_cfg.name, raw_value
                        ));
                    }
                }
                "float" | "f64" | "double" => {
                    if let Ok(f) = raw_value.parse::<f64>() {
                        FieldValue::Float(f)
                    } else {
                        return Err(format!(
                            "Field '{}' cannot parse '{}' as float",
                            field_cfg.name, raw_value
                        ));
                    }
                }
                "bool" | "boolean" => {
                    let lc = raw_value.to_lowercase();
                    FieldValue::Bool(matches!(lc.as_str(), "true" | "1" | "yes" | "t"))
                }
                "timestamp" | "datetime" | "time" => {
                    let dt = if let Some(fmt) = &self.config.time_format {
                        if let Ok(naive) = NaiveDateTime::parse_from_str(&raw_value, fmt) {
                            Utc.from_utc_datetime(&naive)
                        } else if let Ok(dt) = DateTime::parse_from_str(&raw_value, fmt) {
                            dt.with_timezone(&Utc)
                        } else {
                            return Err(format!(
                                "Field '{}' cannot parse '{}' as timestamp with format '{}'",
                                field_cfg.name, raw_value, fmt
                            ));
                        }
                    } else if let Ok(dt) = DateTime::parse_from_rfc3339(&raw_value) {
                        dt.with_timezone(&Utc)
                    } else if let Ok(naive) =
                        NaiveDateTime::parse_from_str(&raw_value, "%Y-%m-%d %H:%M:%S%.f")
                    {
                        Utc.from_utc_datetime(&naive)
                    } else if let Ok(naive) =
                        NaiveDateTime::parse_from_str(&raw_value, "%Y-%m-%dT%H:%M:%S%.f")
                    {
                        Utc.from_utc_datetime(&naive)
                    } else {
                        return Err(format!(
                            "Field '{}' cannot parse '{}' as timestamp (no format specified)",
                            field_cfg.name, raw_value
                        ));
                    };
                    FieldValue::Timestamp(dt)
                }
                _ => FieldValue::String(raw_value),
            };

            result.insert(field_cfg.name.clone(), parsed_value);
        }

        Ok(result.clone())
    }

    pub fn apply_to_record(&self, record: &mut LogRecord, parsed: &HashMap<String, FieldValue>) {
        for field_cfg in &self.config.fields {
            let target = field_cfg
                .target_field
                .clone()
                .unwrap_or_else(|| field_cfg.name.clone());
            if let Some(value) = parsed.get(&field_cfg.name) {
                let target_lc = target.to_lowercase();
                match target_lc.as_str() {
                    "timestamp" | "time" | "@timestamp" | "ts" => {
                        if let Some(dt) = value.as_timestamp() {
                            record.timestamp = dt;
                        } else if let FieldValue::String(s) = value {
                            if let Ok(dt) = DateTime::parse_from_rfc3339(s) {
                                record.timestamp = dt.with_timezone(&Utc);
                            }
                        }
                    }
                    "level" | "loglevel" | "severity" | "log_level" => {
                        let s = value.as_string();
                        record.level = LogLevel::from(s.as_str());
                    }
                    "service" | "app" | "application" | "service_name" => {
                        let s = value.as_string();
                        if !s.is_empty() {
                            record.service = s;
                        }
                    }
                    "traceid" | "trace_id" | "x-b3-traceid" => {
                        let s = value.as_string();
                        if !s.is_empty() {
                            record.trace_id = Some(s);
                        }
                    }
                    "spend" | "duration" | "latency" | "elapsed" | "response_time" | "cost" => {
                        if let Some(f) = value.as_float() {
                            record.spend_ms = Some(f);
                        }
                    }
                    "msg" | "message" | "log" | "body" | "content" => {
                        let s = value.as_string();
                        if !s.is_empty() && (record.message.is_empty() || s.len() > record.message.len()) {
                            record.message = s;
                        }
                    }
                    _ => {
                        record.fields.insert(target, value.as_string());
                    }
                }
            }
        }
    }
}

pub struct CustomFormatRegistry {
    formats: Vec<CompiledFormat>,
    match_order: Vec<String>,
    hit_count: HashMap<String, u64>,
    match_time_ns: HashMap<String, u64>,
}

impl CustomFormatRegistry {
    pub fn new() -> Self {
        Self {
            formats: Vec::new(),
            match_order: Vec::new(),
            hit_count: HashMap::new(),
            match_time_ns: HashMap::new(),
        }
    }

    pub fn load_from_configs(
        &mut self,
        configs: Vec<CustomFormatConfig>,
        match_order: Vec<String>,
    ) {
        let mut compiled: Vec<CompiledFormat> = Vec::new();
        for cfg in configs {
            let id = cfg.id.clone();
            compiled.push(CompiledFormat::new(cfg));
            self.hit_count.insert(id.clone(), 0);
            self.match_time_ns.insert(id, 0);
        }

        if match_order.is_empty() {
            compiled.sort_by(|a, b| b.config.priority.cmp(&a.config.priority));
            self.formats = compiled;
            self.match_order = self.formats.iter().map(|f| f.id().to_string()).collect();
        } else {
            let mut ordered = Vec::with_capacity(compiled.len());
            let mut remaining: HashMap<String, CompiledFormat> = compiled
                .into_iter()
                .map(|f| (f.id().to_string(), f))
                .collect();
            for id in &match_order {
                if let Some(f) = remaining.remove(id) {
                    ordered.push(f);
                }
            }
            for (_, f) in remaining {
                ordered.push(f);
            }
            self.match_order = match_order;
            self.formats = ordered;
        }

        info!(
            "Custom format registry loaded {} formats with order: {:?}",
            self.formats.len(),
            self.formats.iter().map(|f| f.name()).collect::<Vec<_>>()
        );
    }

    pub fn try_parse(&self, line: &str) -> Option<(&CompiledFormat, HashMap<String, FieldValue>)> {
        for fmt in &self.formats {
            if !fmt.prefix_matches(line) {
                continue;
            }
            let start = Instant::now();
            match fmt.parse_line(line) {
                Ok(parsed) => {
                    let elapsed = start.elapsed().as_nanos() as u64;
                    *self.hit_count.get(fmt.id()).unwrap_or(&0) + 1;
                    *self.match_time_ns.get(fmt.id()).unwrap_or(&0) + elapsed;
                    return Some((fmt, parsed));
                }
                Err(_) => continue,
            }
        }
        None
    }

    pub fn try_parse_with_timing(
        &mut self,
        line: &str,
    ) -> Option<(&CompiledFormat, HashMap<String, FieldValue>, u128)> {
        for fmt in &self.formats {
            if !fmt.prefix_matches(line) {
                continue;
            }
            let start = Instant::now();
            match fmt.parse_line(line) {
                Ok(parsed) => {
                    let elapsed = start.elapsed().as_nanos();
                    if let Some(c) = self.hit_count.get_mut(fmt.id()) {
                        *c += 1;
                    }
                    if let Some(t) = self.match_time_ns.get_mut(fmt.id()) {
                        *t += elapsed as u64;
                    }
                    return Some((fmt, parsed, elapsed));
                }
                Err(_) => continue,
            }
        }
        None
    }

    pub fn format_count(&self) -> usize {
        self.formats.len()
    }

    pub fn get_stats(&self) -> Vec<FormatStats> {
        self.formats
            .iter()
            .map(|f| FormatStats {
                id: f.id().to_string(),
                name: f.name().to_string(),
                hits: *self.hit_count.get(f.id()).unwrap_or(&0),
                total_time_ns: *self.match_time_ns.get(f.id()).unwrap_or(&0),
            })
            .collect()
    }

    pub fn reset_stats(&mut self) {
        for k in self.hit_count.keys() {
            self.hit_count.insert(k.clone(), 0);
            self.match_time_ns.insert(k.clone(), 0);
        }
    }
}

impl Default for CustomFormatRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone)]
pub struct FormatStats {
    pub id: String,
    pub name: String,
    pub hits: u64,
    pub total_time_ns: u64,
}

pub struct BenchmarkResult {
    pub total_lines: usize,
    pub parsed_lines: usize,
    pub format_stats: Vec<FormatStats>,
    pub per_format_matches: HashMap<String, usize>,
    pub per_format_avg_time_ns: HashMap<String, f64>,
    pub overall_avg_time_ns: f64,
    pub total_duration: Duration,
}

pub async fn run_benchmark(
    configs: Vec<CustomFormatConfig>,
    match_order: Vec<String>,
    log_file: &str,
    sample_size: usize,
) -> Result<BenchmarkResult, Box<dyn std::error::Error + Send + Sync>> {
    use tokio::io::AsyncBufReadExt;

    let mut registry = CustomFormatRegistry::new();
    registry.load_from_configs(configs, match_order);

    let file = tokio::fs::File::open(log_file).await?;
    let reader = tokio::io::BufReader::new(file);
    let mut lines = reader.lines();

    let mut total = 0usize;
    let mut parsed = 0usize;
    let mut per_format_matches: HashMap<String, usize> = HashMap::new();
    let mut per_format_times: HashMap<String, Vec<u128>> = HashMap::new();
    let mut all_times: Vec<u128> = Vec::new();

    let start = Instant::now();

    while let Some(line) = lines.next_line().await? {
        if total >= sample_size {
            break;
        }
        total += 1;

        if let Some((fmt, _parsed, elapsed)) = registry.try_parse_with_timing(&line) {
            parsed += 1;
            let id = fmt.id().to_string();
            *per_format_matches.entry(id.clone()).or_insert(0) += 1;
            per_format_times
                .entry(id)
                .or_insert_with(Vec::new)
                .push(elapsed);
            all_times.push(elapsed);
        }
    }

    let total_duration = start.elapsed();

    let mut per_format_avg_time: HashMap<String, f64> = HashMap::new();
    for (id, times) in &per_format_times {
        let sum: u128 = times.iter().sum();
        let avg = if !times.is_empty() {
            sum as f64 / times.len() as f64
        } else {
            0.0
        };
        per_format_avg_time.insert(id.clone(), avg);
    }

    let overall_avg = if !all_times.is_empty() {
        all_times.iter().sum::<u128>() as f64 / all_times.len() as f64
    } else {
        0.0
    };

    Ok(BenchmarkResult {
        total_lines: total,
        parsed_lines: parsed,
        format_stats: registry.get_stats(),
        per_format_matches,
        per_format_avg_time,
        overall_avg_time_ns: overall_avg,
        total_duration,
    })
}

pub fn load_format_file<P: AsRef<std::path::Path>>(
    path: P,
) -> Result<Vec<CustomFormatConfig>, Box<dyn std::error::Error + Send + Sync>> {
    let content = std::fs::read_to_string(path)?;
    let mut configs: Vec<CustomFormatConfig> = Vec::new();

    if content.trim_start().starts_with("[[") || content.trim_start().starts_with('[') {
        let value: Value = toml::from_str(&content)?;
        if let Some(formats) = value.get("formats").and_then(|v| v.as_array()) {
            for fmt in formats {
                let cfg: CustomFormatConfig = serde_json::from_value(fmt.clone())?;
                configs.push(cfg);
            }
        } else if let Ok(fmt) = serde_json::from_value::<CustomFormatConfig>(value.clone()) {
            configs.push(fmt);
        }
    } else if content.trim_start().starts_with('{') {
        let value: Value = serde_json::from_str(&content)?;
        if let Some(formats) = value.get("formats").and_then(|v| v.as_array()) {
            for fmt in formats {
                let cfg: CustomFormatConfig = serde_json::from_value(fmt.clone())?;
                configs.push(cfg);
            }
        }
    }

    info!("Loaded {} custom formats from file", configs.len());
    Ok(configs)
}
