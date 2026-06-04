use chrono::{DateTime, Utc};
use regex::Regex;
use serde_json::Value;
use std::collections::HashMap;

use common::log::{LogEvent, LogLevel};

pub struct LogParser {
    patterns: Vec<LogPattern>,
    json_detector: Regex,
}

struct LogPattern {
    regex: Regex,
    field_mapping: HashMap<String, String>,
}

impl LogParser {
    pub fn new() -> Self {
        let mut parser = Self {
            patterns: Vec::new(),
            json_detector: Regex::new(r#"^\s*\{.*\}\s*$"#).unwrap(),
        };
        parser.add_default_patterns();
        parser
    }

    fn add_default_patterns(&mut self) {
        self.add_pattern(
            r#"^(?P<timestamp>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?\s*\[?(?P<level>[A-Z]+)\]?\s*(?P<message>.*)$"#,
            vec![("timestamp".to_string(), "timestamp".to_string()), ("level".to_string(), "level".to_string())],
        );

        self.add_pattern(
            r#"^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3})\s+(?P<level>[A-Z]+)\s+\d+\s+---\s+\[(?P<thread>[^\]]+)\]\s+(?P<logger>[^\s]+)\s+:\s+(?P<message>.*)$"#,
            vec![
                ("timestamp".to_string(), "timestamp".to_string()),
                ("level".to_string(), "level".to_string()),
                ("thread".to_string(), "thread".to_string()),
                ("logger".to_string(), "logger".to_string()),
            ],
        );
    }

    pub fn add_pattern(&mut self, pattern: &str, field_mapping: Vec<(String, String)>) {
        if let Ok(regex) = Regex::new(pattern) {
            let mapping: HashMap<String, String> = field_mapping.into_iter().collect();
            self.patterns.push(LogPattern {
                regex,
                field_mapping: mapping,
            });
        }
    }

    pub fn parse(&self, line: &str, service_name: &str) -> Option<LogEvent> {
        let line = line.trim();
        if line.is_empty() {
            return None;
        }

        if self.json_detector.is_match(line) {
            if let Some(event) = self.parse_json(line, service_name) {
                return Some(event);
            }
        }

        for pattern in &self.patterns {
            if let Some(captures) = pattern.regex.captures(line) {
                let mut event = self.create_event_from_captures(captures, pattern, line, service_name);
                return Some(event);
            }
        }

        Some(self.create_simple_event(line, service_name))
    }

    fn parse_json(&self, line: &str, service_name: &str) -> Option<LogEvent> {
        let json: Value = serde_json::from_str(line).ok()?;
        let obj = json.as_object()?;

        let level = obj
            .get("level")
            .and_then(|v| v.as_str())
            .and_then(LogLevel::from_str)
            .unwrap_or(LogLevel::Info);

        let message = obj
            .get("message")
            .or_else(|| obj.get("msg"))
            .and_then(|v| v.as_str())
            .unwrap_or(line)
            .to_string();

        let timestamp = obj
            .get("timestamp")
            .or_else(|| obj.get("time"))
            .or_else(|| obj.get("@timestamp"))
            .and_then(|v| v.as_str())
            .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
            .map(|dt| dt.with_timezone(&Utc))
            .unwrap_or_else(Utc::now);

        let mut fields = HashMap::new();
        for (key, value) in obj {
            if key != "level" && key != "message" && key != "msg" && key != "timestamp" && key != "time" && key != "@timestamp" {
                fields.insert(key.clone(), value.clone());
            }
        }

        let hostname = obj
            .get("hostname")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string();

        let mut event = LogEvent::new(hostname, service_name.to_string(), level, message, "".to_string());
        event.fields = fields;
        event.timestamp = timestamp;
        event.raw = Some(line.to_string());

        Some(event)
    }

    fn create_event_from_captures(
        &self,
        captures: regex::Captures,
        pattern: &LogPattern,
        raw: &str,
        service_name: &str,
    ) -> LogEvent {
        let mut level = LogLevel::Info;
        let mut message = raw.to_string();
        let mut timestamp = Utc::now();
        let mut fields = HashMap::new();

        for (name, _) in &pattern.field_mapping {
            if let Some(value) = captures.name(name) {
                let value_str = value.as_str().to_string();
                match name.as_str() {
                    "level" => {
                        level = LogLevel::from_str(&value_str).unwrap_or(LogLevel::Info);
                    }
                    "timestamp" => {
                        if let Ok(dt) = DateTime::parse_from_rfc3339(&value_str) {
                            timestamp = dt.with_timezone(&Utc);
                        }
                    }
                    "message" => {
                        message = value_str;
                    }
                    _ => {
                        fields.insert(name.clone(), Value::String(value_str));
                    }
                }
            }
        }

        let mut event = LogEvent::new("unknown".to_string(), service_name.to_string(), level, message, "".to_string());
        event.fields = fields;
        event.timestamp = timestamp;
        event.raw = Some(raw.to_string());
        event
    }

    fn create_simple_event(&self, line: &str, service_name: &str) -> LogEvent {
        let level = if line.to_uppercase().contains("ERROR") {
            LogLevel::Error
        } else if line.to_uppercase().contains("WARN") {
            LogLevel::Warn
        } else if line.to_uppercase().contains("DEBUG") {
            LogLevel::Debug
        } else if line.to_uppercase().contains("TRACE") {
            LogLevel::Trace
        } else {
            LogLevel::Info
        };

        let mut event = LogEvent::new(
            "unknown".to_string(),
            service_name.to_string(),
            level,
            line.to_string(),
            "".to_string(),
        );
        event.raw = Some(line.to_string());
        event
    }
}

impl Default for LogParser {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_info() {
        let parser = LogParser::new();
        let event = parser.parse("INFO Server started", "test-service").unwrap();
        assert_eq!(event.level, LogLevel::Info);
        assert_eq!(event.service, "test-service");
    }

    #[test]
    fn test_parse_json() {
        let parser = LogParser::new();
        let json_line = r#"{"level":"ERROR","message":"Something failed","timestamp":"2024-01-01T10:00:00Z","request_id":"abc123"}"#;
        let event = parser.parse(json_line, "test-service").unwrap();
        assert_eq!(event.level, LogLevel::Error);
        assert_eq!(event.message, "Something failed");
        assert_eq!(event.fields.get("request_id").unwrap().as_str().unwrap(), "abc123");
    }
}
