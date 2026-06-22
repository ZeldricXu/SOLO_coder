// pub mod custom_format;

use crate::{LogFormat, LogLevel, LogRecord};
// use crate::parser::custom_format::{BenchmarkResult, CustomFormatRegistry, FormatDefinition, FormatHitStats};
use chrono::{DateTime, FixedOffset, NaiveDateTime, TimeZone, Utc};
use regex::{Regex, RegexSet};
use serde_json::Value;
use std::collections::HashMap;
use std::path::Path;
use std::sync::OnceLock;
use tracing::{debug, info, warn};

const SAMPLE_SIZE: usize = 100;
const FORMAT_CONFIDENCE_THRESHOLD: f64 = 0.7;

static NGINX_ACCESS_RE: OnceLock<Regex> = OnceLock::new();
static APACHE_COMMON_RE: OnceLock<Regex> = OnceLock::new();
static ENVOY_RE: OnceLock<Regex> = OnceLock::new();
static NGINX_ACCESS_CAP_RE: OnceLock<Regex> = OnceLock::new();
static APACHE_COMMON_CAP_RE: OnceLock<Regex> = OnceLock::new();
static ENVOY_CAP_RE: OnceLock<Regex> = OnceLock::new();

fn nginx_access_re() -> &'static Regex {
    NGINX_ACCESS_RE.get_or_init(|| {
        Regex::new("^\\S+ \\S+ \\S+ \\[[^\\]]+\\] \\\"[A-Z]+ [^\\\"]+ [^\\\"]+\\\" \\d{3} \\d+").unwrap()
    })
}

fn apache_common_re() -> &'static Regex {
    APACHE_COMMON_RE.get_or_init(|| {
        Regex::new(r"^\S+ \S+ \S+ \[[^\]]+\] \"[A-Z]+ [^\"]* [^\"]*\" \d{3} \d+").unwrap()
    })
}

fn envoy_re() -> &'static Regex {
    ENVOY_RE.get_or_init(|| {
        Regex::new(r"^\[\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\] \"[A-Z]+ ").unwrap()
    })
}

fn nginx_access_cap_re() -> &'static Regex {
    NGINX_ACCESS_CAP_RE.get_or_init(|| {
        Regex::new(r#"^(\S+) \S+ (\S+) \[([^\]]+)\] "([A-Z]+) ([^"]+) ([^"]+)" (\d{3}) (\d+) "([^"]*)" "([^"]*)"(?:\s+(\d+(?:\.\d+)?))?"#).unwrap()
    })
}

fn apache_common_cap_re() -> &'static Regex {
    APACHE_COMMON_CAP_RE.get_or_init(|| {
        Regex::new(r#"^(\S+) \S+ (\S+) \[([^\]]+)\] "([A-Z]+) ([^"]*) ([^"]*)" (\d{3}) (\d+)"#).unwrap()
    })
}

fn envoy_cap_re() -> &'static Regex {
    ENVOY_CAP_RE.get_or_init(|| {
        Regex::new(r#"^\[([^\]]+)\] "([A-Z]+) ([^"]+) ([^"]+)" (\d+) (\d+) (\d+) (\d+) "([^"]*)" "([^"]*)" "([^"]*)" "([^"]*)" "([^"]*)""#).unwrap()
    })
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

pub fn dummy() {}
