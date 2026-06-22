pub mod custom_format;

use crate::{LogFormat, LogLevel, LogRecord};
use crate::config::{CustomFormatConfig, ParserConfig};
use crate::interner::{should_intern_field, should_intern_value, StringInterner};
use crate::parser::custom_format::{CompiledFormat, CustomFormatRegistry, FieldValue};
use chrono::{DateTime, FixedOffset, NaiveDateTime, TimeZone, Utc};
use regex::RegexSet;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{debug, info, warn};

const SAMPLE_SIZE: usize = 100;
const FORMAT_CONFIDENCE_THRESHOLD: f64 = 0.7;

#[derive(Debug, Clone, Copy)]
struct LineFeature {
    first_byte: u8,
    line_len: u16,
    first_field_len: u8,
    special_char_mask: u8,
}

const BIT_BRACE_L: u8 = 1 << 0;
const BIT_BRACKET_L: u8 = 1 << 1;
const BIT_QUOTE: u8 = 1 << 2;
const BIT_COMMA: u8 = 1 << 3;
const BIT_COLON: u8 = 1 << 4;
const BIT_SPACE: u8 = 1 << 5;

#[inline]
fn extract_features(line: &str) -> LineFeature {
    let bytes = line.as_bytes();
    let len = bytes.len().min(u16::MAX as usize) as u16;
    let first_byte = bytes.first().copied().unwrap_or(b'\0');

    let first_field_len = bytes
        .iter()
        .position(|b| *b == b' ' || *b == b',' || *b == b':' || *b == b'\t')
        .unwrap_or(bytes.len())
        .min(u8::MAX as usize) as u8;

    let mut mask: u8 = 0;
    let probe_end = bytes.len().min(64);
    for &b in &bytes[..probe_end] {
        match b {
            b'{' => mask |= BIT_BRACE_L,
            b'[' => mask |= BIT_BRACKET_L,
            b'"' => mask |= BIT_QUOTE,
            b',' => mask |= BIT_COMMA,
            b':' => mask |= BIT_COLON,
            b' ' => mask |= BIT_SPACE,
            _ => {}
        }
    }

    LineFeature {
        first_byte,
        line_len: len,
        first_field_len,
        special_char_mask: mask,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
enum FormatClass {
    Json = 0,
    NginxAccess = 1,
    ApacheCommon = 2,
    Envoy = 3,
    Csv = 4,
    Unknown = 5,
}

#[inline]
fn route_format(feat: LineFeature) -> [FormatClass; 3] {
    let mut out = [FormatClass::Unknown; 3];
    let mut idx = 0;

    if feat.first_byte == b'{' {
        out[idx] = FormatClass::Json;
        idx += 1;
    }

    if feat.first_byte == b'[' && (feat.special_char_mask & BIT_QUOTE != 0) {
        out[idx] = FormatClass::Envoy;
        idx += 1;
    }

    if feat.special_char_mask & BIT_COMMA != 0 && feat.first_field_len > 0 && feat.first_byte != b'{' {
        out[idx] = FormatClass::Csv;
        idx += 1;
    }

    if feat.first_byte != b'{' && feat.first_byte != b'[' && feat.special_char_mask & BIT_SPACE != 0 && feat.special_char_mask & BIT_QUOTE != 0 {
        if idx < 3 {
            out[idx] = FormatClass::NginxAccess;
            idx += 1;
        }
        if idx < 3 {
            out[idx] = FormatClass::ApacheCommon;
        }
    }

    if idx == 0 {
        out[0] = FormatClass::Json;
        out[1] = FormatClass::NginxAccess;
        out[2] = FormatClass::ApacheCommon;
    }

    out
}

#[inline]
fn class_to_format(cls: FormatClass) -> LogFormat {
    match cls {
        FormatClass::Json => LogFormat::Json,
        FormatClass::NginxAccess => LogFormat::NginxAccess,
        FormatClass::ApacheCommon => LogFormat::ApacheCommon,
        FormatClass::Envoy => LogFormat::Envoy,
        FormatClass::Csv => LogFormat::Csv,
        FormatClass::Unknown => LogFormat::Unknown,
    }
}

pub struct FormatDetector {
    samples: Vec<String>,
    detected_format: Option<LogFormat>,
    confidence: f64,
}

impl FormatDetector {
    pub fn new() -> Self {
        Self {
            samples: Vec::with_capacity(SAMPLE_SIZE),
            detected_format: None,
            confidence: 0.0,
        }
    }

    pub fn add_sample(&mut self, line: &str) -> Option<LogFormat> {
        if self.detected_format.is_some() && self.confidence >= FORMAT_CONFIDENCE_THRESHOLD {
            return self.detected_format.clone();
        }
        if self.samples.len() < SAMPLE_SIZE {
            self.samples.push(line.to_string());
        }
        if self.samples.len() >= 10 {
            self.detect();
        }
        self.detected_format.clone()
    }

    pub fn detected(&self) -> Option<LogFormat> {
        self.detected_format.clone()
    }

    fn detect(&mut self) {
        let n = self.samples.len() as f64;
        let mut scores: HashMap<LogFormat, f64> = HashMap::new();

        scores.insert(LogFormat::Json, self.samples.iter().filter(|l| is_json(l)).count() as f64 / n);
        scores.insert(LogFormat::Csv, self.samples.iter().filter(|l| is_csv(l)).count() as f64 / n);
        scores.insert(LogFormat::NginxAccess, self.samples.iter().filter(|l| is_nginx_access(l)).count() as f64 / n);
        scores.insert(LogFormat::ApacheCommon, self.samples.iter().filter(|l| is_apache_common(l)).count() as f64 / n);
        scores.insert(LogFormat::Envoy, self.samples.iter().filter(|l| is_envoy(l)).count() as f64 / n);

        let mut best = (LogFormat::Unknown, 0.0f64);
        for (fmt, score) in scores {
            if score > best.1 {
                best = (fmt, score);
            }
        }
        if best.1 >= FORMAT_CONFIDENCE_THRESHOLD {
            debug!(
                "Detected format {:?} with confidence {:.2}% (n={})",
                best.0,
                best.1 * 100.0,
                self.samples.len()
            );
            self.detected_format = Some(best.0);
            self.confidence = best.1;
        }
    }
}

fn is_json(line: &str) -> bool {
    let t = line.trim_start();
    t.starts_with('{') && serde_json::from_str::<Value>(t).is_ok()
}

fn is_csv(line: &str) -> bool {
    let commas = line.matches(',').count();
    if commas < 2 {
        return false;
    }
    let quotes = line.matches('"').count();
    quotes % 2 == 0
}

fn is_nginx_access(line: &str) -> bool {
    lazy_regex::regex_is_match!(
        r"^\S+ \S+ \S+ \[[^\]]+\] \"[A-Z]+ [^\"]+ [^\"]+\" \d{3} \d+",
        line
    )
}

fn is_apache_common(line: &str) -> bool {
    lazy_regex::regex_is_match!(
        r"^\S+ \S+ \S+ \[[^\]]+\] \"[A-Z]+ [^\"]* [^\"]*\" \d{3} \d+",
        line
    )
}

fn is_envoy(line: &str) -> bool {
    lazy_regex::regex_is_match!(
        r"^\[\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\] \"[A-Z]+ ",
        line
    )
}

pub struct PatternSet {
    level_regexes: RegexSet,
    timestamp_regexes: RegexSet,
    trace_id_regexes: RegexSet,
    spend_regexes: RegexSet,
}

impl PatternSet {
    pub fn precompiled() -> Self {
        let level_regexes = RegexSet::new([
            r"^\s*\[?(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|ERR|FATAL|CRITICAL)\]?",
            r"\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|ERR|FATAL|CRITICAL)\b",
            r"level[\"'\s:=]+[\"']?(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|ERR|FATAL|CRITICAL)",
        ]).unwrap();

        let timestamp_regexes = RegexSet::new([
            r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?",
            r"\d{2}/\w{3}/\d{4}:\d{2}:\d{2}:\d{2}\s+[+-]\d{4}",
            r"\[\d{2}/\w{3}/\d{4}:\d{2}:\d{2}:\d{2}\s+[+-]\d{4}\]",
            r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?",
        ]).unwrap();

        let trace_id_regexes = RegexSet::new([
            r"trace[_-]?id[\"'\s:=]+[\"']?([0-9a-fA-F]{16,64})",
            r"x-b3-traceid[\"'\s:=]+[\"']?([0-9a-fA-F]{16,32})",
            r"\[([0-9a-fA-F]{16,32})\]",
            r"trace=([0-9a-fA-F-]{20,})",
        ]).unwrap();

        let spend_regexes = RegexSet::new([
            r"(?:spend|duration|latency|elapsed|time)[\"'\s:=]+[\"']?(\d+(?:\.\d+)?)\s*(?:ms|millis)?",
            r"(\d+(?:\.\d+)?)\s*ms",
            r"response_time[\"'\s:=]+(\d+(?:\.\d+)?)",
        ]).unwrap();

        Self {
            level_regexes,
            timestamp_regexes,
            trace_id_regexes,
            spend_regexes,
        }
    }
}

fn parse_timestamp(ts_str: &str) -> Option<DateTime<Utc>> {
    let s = ts_str.trim().trim_matches(|c| c == '[' || c == ']' || c == '"' || c == '\'');

    if let Ok(dt) = DateTime::parse_from_rfc3339(s) {
        return Some(dt.with_timezone(&Utc));
    }
    if let Ok(dt) = DateTime::parse_from_str(s, "%d/%b/%Y:%H:%M:%S %z") {
        return Some(dt.with_timezone(&Utc));
    }
    if let Ok(naive) = NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M:%S%.f") {
        return Some(Utc.from_utc_datetime(&naive));
    }
    if let Ok(naive) = NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S%.f") {
        return Some(Utc.from_utc_datetime(&naive));
    }
    if let Ok(naive) = NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S") {
        return Some(Utc.from_utc_datetime(&naive));
    }
    if let Ok(dt) = NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M:%S") {
        return Some(Utc.from_utc_datetime(&dt));
    }
    None
}

#[inline]
fn try_intern_string(interner: Option<&Arc<StringInterner>>, field: &str, val: &str) -> String {
    if let Some(interner) = interner {
        if should_intern_field(field) && should_intern_value(val) {
            if let Some(interned) = interner.intern(val) {
                return interned.as_str().to_string();
            }
        }
    }
    val.to_string()
}

fn parse_json_record(
    record: &mut LogRecord,
    raw: &str,
    interner: Option<&Arc<StringInterner>>,
) -> bool {
    let trimmed = raw.trim_start();
    if !trimmed.starts_with('{') {
        return false;
    }
    let value: Value = match serde_json::from_str(trimmed) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let obj = match value.as_object() {
        Some(o) => o,
        None => return false,
    };

    for (k, v) in obj {
        let key_lc = k.to_lowercase();
        let val_str = v.as_str().unwrap_or(&v.to_string());
        match key_lc.as_str() {
            "timestamp" | "time" | "@timestamp" | "ts" => {
                if let Some(dt) = parse_timestamp(val_str) {
                    record.timestamp = dt;
                }
            }
            "level" | "loglevel" | "severity" | "log_level" => {
                record.level = LogLevel::from(val_str);
            }
            "service" | "app" | "application" | "service_name" => {
                let svc = try_intern_string(interner, &key_lc, val_str);
                if svc.len() > record.service.len() {
                    record.service = svc;
                }
            }
            "traceid" | "trace_id" | "x-b3-traceid" => {
                record.trace_id = Some(val_str.to_string());
            }
            "spend" | "duration" | "latency" | "elapsed" | "response_time" | "cost" => {
                if let Some(f) = v.as_f64() {
                    record.spend_ms = Some(f);
                } else if let Ok(f) = val_str.parse::<f64>() {
                    record.spend_ms = Some(f);
                }
            }
            "msg" | "message" | "log" | "body" | "content" => {
                let msg = val_str.to_string();
                if record.message.is_empty() || msg.len() > record.message.len() {
                    record.message = msg;
                }
            }
            _ => {
                let field_val = if should_intern_field(&key_lc) && should_intern_value(val_str) {
                    try_intern_string(interner, &key_lc, val_str)
                } else {
                    val_str.to_string()
                };
                record.fields.insert(k.clone(), field_val);
            }
        }
    }
    true
}

fn parse_nginx_access(record: &mut LogRecord, raw: &str) -> bool {
    lazy_regex::regex_captures!(
        r#"^(\S+) \S+ (\S+) \[([^\]]+)\] "([A-Z]+) ([^"]+) ([^"]+)" (\d{3}) (\d+) "([^"]*)" "([^"]*)"(?:\s+(\d+(?:\.\d+)?))?"#,
        raw
    ).map(|caps| {
        let (_, remote, _user, ts_str, method, path, proto, status, bytes, _ref, ua, rt) = caps;
        if let Some(dt) = parse_timestamp(ts_str) {
            record.timestamp = dt;
        }
        record.fields.insert("remote_addr".into(), remote.into());
        record.fields.insert("method".into(), method.into());
        record.fields.insert("path".into(), path.into());
        record.fields.insert("protocol".into(), proto.into());
        record.fields.insert("status".into(), status.into());
        record.fields.insert("bytes".into(), bytes.into());
        record.fields.insert("user_agent".into(), ua.into());
        let status_num: i32 = status.parse().unwrap_or(0);
        if status_num >= 500 {
            record.level = LogLevel::Error;
        } else if status_num >= 400 {
            record.level = LogLevel::Warn;
        } else {
            record.level = LogLevel::Info;
        }
        if let Some(rt_str) = rt {
            if let Ok(rt_f) = rt_str.parse::<f64>() {
                record.spend_ms = Some(rt_f * 1000.0);
            }
        }
        true
    }).unwrap_or(false)
}

fn parse_apache_common(record: &mut LogRecord, raw: &str) -> bool {
    lazy_regex::regex_captures!(
        r#"^(\S+) \S+ (\S+) \[([^\]]+)\] "([A-Z]+) ([^"]*) ([^"]*)" (\d{3}) (\d+)"#,
        raw
    ).map(|caps| {
        let (_, remote, _user, ts_str, method, path, proto, status, bytes) = caps;
        if let Some(dt) = parse_timestamp(ts_str) {
            record.timestamp = dt;
        }
        record.fields.insert("remote_addr".into(), remote.into());
        record.fields.insert("method".into(), method.into());
        record.fields.insert("path".into(), path.into());
        record.fields.insert("protocol".into(), proto.into());
        record.fields.insert("status".into(), status.into());
        record.fields.insert("bytes".into(), bytes.into());
        let status_num: i32 = status.parse().unwrap_or(0);
        if status_num >= 500 {
            record.level = LogLevel::Error;
        } else if status_num >= 400 {
            record.level = LogLevel::Warn;
        } else {
            record.level = LogLevel::Info;
        }
        true
    }).unwrap_or(false)
}

fn parse_envoy(record: &mut LogRecord, raw: &str) -> bool {
    lazy_regex::regex_captures!(
        r#"^\[([^\]]+)\] "([A-Z]+) ([^"]+) ([^"]+)" (\d+) (\d+) (\d+) (\d+) "([^"]*)" "([^"]*)" "([^"]*)" "([^"]*)" "([^"]*)""#,
        raw
    ).map(|caps| {
        let (_, ts_str, method, path, proto, status_code, flags, up, down, xff, ua, req_id, up_host, up_attempts) = caps;
        if let Some(dt) = parse_timestamp(ts_str) {
            record.timestamp = dt;
        }
        record.fields.insert("method".into(), method.into());
        record.fields.insert("path".into(), path.into());
        record.fields.insert("protocol".into(), proto.into());
        record.fields.insert("status".into(), status_code.into());
        record.fields.insert("response_flags".into(), flags.into());
        record.fields.insert("upstream_service_time_ms".into(), up.into());
        record.fields.insert("x_forwarded_for".into(), xff.into());
        record.fields.insert("user_agent".into(), ua.into());
        record.trace_id = Some(req_id.into());
        if let Ok(up_f) = up.parse::<f64>() {
            record.spend_ms = Some(up_f);
        }
        let status_num: i32 = status_code.parse().unwrap_or(0);
        if status_num >= 500 {
            record.level = LogLevel::Error;
        } else if status_num >= 400 {
            record.level = LogLevel::Warn;
        } else {
            record.level = LogLevel::Info;
        }
        true
    }).unwrap_or(false)
}

fn parse_csv(record: &mut LogRecord, raw: &str) -> bool {
    let parts: Vec<&str> = raw.split(',').collect();
    if parts.len() < 3 {
        return false;
    }
    for (i, part) in parts.iter().enumerate() {
        let p = part.trim().trim_matches('"');
        record.fields.insert(format!("col_{}", i), p.into());
        if i == 0 {
            if let Some(dt) = parse_timestamp(p) {
                record.timestamp = dt;
            }
        }
    }
    true
}

fn extract_generic(record: &mut LogRecord, patterns: &PatternSet, raw: &str) {
    use regex::Regex;

    let level_matches: Vec<_> = patterns.level_regexes.matches(raw).into_iter().collect();
    if !level_matches.is_empty() {
        let re = Regex::new(r"(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|ERR|FATAL|CRITICAL)").unwrap();
        if let Some(c) = re.captures(raw) {
            record.level = LogLevel::from(c.get(1).unwrap().as_str());
        }
    }

    let ts_matches: Vec<_> = patterns.timestamp_regexes.matches(raw).into_iter().collect();
    if !ts_matches.is_empty() {
        let re = Regex::new(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?").unwrap();
        if let Some(m) = re.find(raw) {
            if let Some(dt) = parse_timestamp(m.as_str()) {
                record.timestamp = dt;
            }
        }
    }

    let trace_matches: Vec<_> = patterns.trace_id_regexes.matches(raw).into_iter().collect();
    if !trace_matches.is_empty() {
        let re = Regex::new(r"trace[_-]?id[\"'\s:=]+[\"']?([0-9a-fA-F]{16,64})").unwrap();
        if let Some(c) = re.captures(raw) {
            record.trace_id = Some(c.get(1).unwrap().as_str().to_string());
        }
    }

    let spend_matches: Vec<_> = patterns.spend_regexes.matches(raw).into_iter().collect();
    if !spend_matches.is_empty() {
        let re = Regex::new(r"(\d+(?:\.\d+)?)\s*ms").unwrap();
        if let Some(c) = re.captures(raw) {
            if let Ok(f) = c.get(1).unwrap().as_str().parse::<f64>() {
                record.spend_ms = Some(f);
            }
        }
    }
}

pub struct ParserEngine {
    detectors: HashMap<String, FormatDetector>,
    patterns: PatternSet,
    custom_formats: CustomFormatRegistry,
    custom_format_matched_count: u64,
    fallback_count: u64,
    interner: Option<Arc<StringInterner>>,
}

impl ParserEngine {
    pub fn new() -> Self {
        Self {
            detectors: HashMap::new(),
            patterns: PatternSet::precompiled(),
            custom_formats: CustomFormatRegistry::new(),
            custom_format_matched_count: 0,
            fallback_count: 0,
            interner: None,
        }
    }

    pub fn with_interner(mut self, interner: Arc<StringInterner>) -> Self {
        self.interner = Some(interner);
        self
    }

    pub fn with_config(config: ParserConfig) -> Self {
        let mut engine = Self::new();
        engine.load_custom_formats(
            config.custom_formats,
            config.format_match_order,
        );
        engine
    }

    pub fn load_custom_formats(
        &mut self,
        formats: Vec<CustomFormatConfig>,
        match_order: Vec<String>,
    ) {
        self.custom_formats
            .load_from_configs(formats, match_order);
    }

    pub fn custom_format_count(&self) -> usize {
        self.custom_formats.format_count()
    }

    pub fn set_interner(&mut self, interner: Arc<StringInterner>) {
        self.interner = Some(interner);
    }

    pub fn parse(&mut self, mut record: LogRecord) -> LogRecord {
        let raw = record.raw.clone();
        if raw.is_empty() {
            return record;
        }

        if self.custom_formats.format_count() > 0 {
            if let Some((fmt, parsed)) = self.custom_formats.try_parse(&raw) {
                debug!(
                    "Custom format matched: {} (id={})",
                    fmt.name(),
                    fmt.id()
                );
                fmt.apply_to_record(&mut record, &parsed);
                self.custom_format_matched_count += 1;
                if record.message.is_empty() {
                    record.message = raw.clone();
                }
                return record;
            }
        }
        self.fallback_count += 1;

        let feat = extract_features(&raw);
        let candidates = route_format(feat);

        let detector = self.detectors
            .entry(record.source.clone())
            .or_insert_with(FormatDetector::new);
        let detected_format = detector.add_sample(&raw);

        let interner = self.interner.as_ref();

        let parsed = match detected_format {
            Some(LogFormat::Json) => parse_json_record(&mut record, &raw, interner),
            Some(LogFormat::NginxAccess) => parse_nginx_access(&mut record, &raw),
            Some(LogFormat::ApacheCommon) => parse_apache_common(&mut record, &raw),
            Some(LogFormat::Envoy) => parse_envoy(&mut record, &raw),
            Some(LogFormat::Csv) => parse_csv(&mut record, &raw),
            _ => {
                let mut ok = false;
                for cls in candidates.iter() {
                    if *cls == FormatClass::Unknown {
                        continue;
                    }
                    let success = match class_to_format(*cls) {
                        LogFormat::Json => parse_json_record(&mut record, &raw, interner),
                        LogFormat::NginxAccess => parse_nginx_access(&mut record, &raw),
                        LogFormat::ApacheCommon => parse_apache_common(&mut record, &raw),
                        LogFormat::Envoy => parse_envoy(&mut record, &raw),
                        LogFormat::Csv => parse_csv(&mut record, &raw),
                        _ => false,
                    };
                    if success {
                        ok = true;
                        break;
                    }
                }
                ok
            }
        };

        if !parsed {
            parse_csv(&mut record, &raw);
        }
        extract_generic(&mut record, &self.patterns, &raw);

        if let Some(interner) = &self.interner {
            if !record.service.is_empty() && should_intern_value(&record.service) {
                if let Some(interned) = interner.intern(&record.service) {
                    record.service = interned.as_str().to_string();
                }
            }
        }

        if record.message.is_empty() {
            record.message = raw.clone();
        }

        record
    }

    pub fn get_parse_stats(&self) -> ParseStats {
        ParseStats {
            custom_format_matched: self.custom_format_matched_count,
            fallback_auto_detected: self.fallback_count,
            format_stats: self.custom_formats.get_stats(),
        }
    }

    pub fn reset_stats(&mut self) {
        self.custom_format_matched_count = 0;
        self.fallback_count = 0;
        self.custom_formats.reset_stats();
    }
}

#[derive(Debug, Clone)]
pub struct ParseStats {
    pub custom_format_matched: u64,
    pub fallback_auto_detected: u64,
    pub format_stats: Vec<crate::parser::custom_format::FormatStats>,
}

impl Default for ParserEngine {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_json() {
        let mut engine = ParserEngine::new();
        let mut rec = LogRecord::new();
        rec.raw = r#"{"timestamp":"2024-01-15T10:30:45Z","level":"ERROR","service":"api","trace_id":"abc123","spend":123.5,"message":"Something failed"}"#.to_string();
        let result = engine.parse(rec);
        assert_eq!(result.level, LogLevel::Error);
        assert_eq!(result.service, "api");
        assert_eq!(result.trace_id, Some("abc123".to_string()));
        assert_eq!(result.spend_ms, Some(123.5));
    }

    #[test]
    fn test_format_detector_json() {
        let mut d = FormatDetector::new();
        for _ in 0..20 {
            d.add_sample(r#"{"a":1,"level":"INFO"}"#);
        }
        assert!(matches!(d.detected(), Some(LogFormat::Json)));
    }
}
