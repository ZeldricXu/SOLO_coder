use regex::Regex;
use std::time::{Duration, Instant};

pub struct MultilineMerger {
    pattern: Option<Regex>,
    buffer: Vec<String>,
    last_line_time: Instant,
    timeout: Duration,
}

impl MultilineMerger {
    pub fn new(pattern: Option<&str>) -> Self {
        Self {
            pattern: pattern.and_then(|p| Regex::new(p).ok()),
            buffer: Vec::new(),
            last_line_time: Instant::now(),
            timeout: Duration::from_secs(5),
        }
    }

    pub fn add_line(&mut self, line: &str) -> Option<String> {
        if line.trim().is_empty() {
            return None;
        }

        let is_start_of_new_event = self
            .pattern
            .as_ref()
            .map(|re| re.is_match(line))
            .unwrap_or(true);

        if is_start_of_new_event {
            let result = self.flush_internal();
            self.buffer.push(line.to_string());
            self.last_line_time = Instant::now();
            result
        } else {
            self.buffer.push(line.to_string());
            self.last_line_time = Instant::now();
            None
        }
    }

    pub fn check_timeout(&mut self) -> Option<String> {
        if !self.buffer.is_empty() && self.last_line_time.elapsed() > self.timeout {
            self.flush_internal()
        } else {
            None
        }
    }

    pub fn flush(&mut self) -> Option<String> {
        self.flush_internal()
    }

    fn flush_internal(&mut self) -> Option<String> {
        if self.buffer.is_empty() {
            return None;
        }

        let joined = self.buffer.join("\n");
        self.buffer.clear();
        Some(joined)
    }

    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }
}

pub fn default_java_exception_pattern() -> &'static str {
    r"^(?:\d{4}-\d{2}-\d{2}|\d{2}:\d{2}:\d{2})|^(?:ERROR|WARN|INFO|DEBUG|TRACE|FATAL)"
}

pub fn default_stacktrace_contination_pattern() -> &'static str {
    r"^\s+at\s+|^\s+Caused by:|^\s+\.\.\.\s+\d+\s+more"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_single_line_mode() {
        let mut merger = MultilineMerger::new(None);
        assert_eq!(merger.add_line("line 1"), Some("line 1".to_string()));
        assert_eq!(merger.add_line("line 2"), Some("line 2".to_string()));
        assert!(merger.flush().is_none());
    }

    #[test]
    fn test_multiline_java_exception() {
        let pattern = r"^\d{4}-\d{2}-\d{2}";
        let mut merger = MultilineMerger::new(Some(pattern));

        assert!(merger
            .add_line("2024-01-01 10:00:00 ERROR Something went wrong")
            .is_none());
        assert!(merger.add_line("    at com.example.Foo.bar(Foo.java:42)").is_none());
        assert!(merger.add_line("    at com.example.Baz.qux(Baz.java:123)").is_none());

        let result = merger.add_line("2024-01-01 10:00:01 INFO Next log");
        assert!(result.is_some());
        let merged = result.unwrap();
        assert!(merged.contains("Something went wrong"));
        assert!(merged.contains("at com.example.Foo.bar"));

        assert!(merger.flush().is_some());
    }
}
