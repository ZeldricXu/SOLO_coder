use crate::types::{AppError, AppResult, LoggingConfig};
use chrono::Utc;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tracing::level_filters::LevelFilter;
use tracing::{dispatcher, Dispatch, Event, Subscriber};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_log::LogTracer;
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::{self, time::FormatTime};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::{registry::LookupSpan, EnvFilter, Layer};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StructuredLogRecord {
    pub timestamp: String,
    pub level: String,
    pub target: String,
    pub message: String,
    pub module: Option<String>,
    pub file: Option<String>,
    pub line: Option<u32>,
    pub span_id: Option<String>,
    pub trace_id: Option<String>,
    pub fields: HashMap<String, serde_json::Value>,
}

pub struct StructuredLogger {
    config: LoggingConfig,
    guards: Arc<RwLock<Vec<WorkerGuard>>>,
}

impl StructuredLogger {
    pub fn new(config: LoggingConfig) -> Self {
        Self {
            config,
            guards: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub fn init(&self) -> AppResult<()> {
        LogTracer::init().map_err(|e| AppError::InternalError(format!("初始化LogTracer失败: {}", e)))?;

        let dispatch = self.build_dispatch()?;
        dispatcher::set_global_default(dispatch)
            .map_err(|e| AppError::InternalError(format!("设置全局日志调度器失败: {}", e)))?;

        Ok(())
    }

    fn build_dispatch(&self) -> AppResult<Dispatch> {
        let registry = tracing_subscriber::registry();

        let env_filter = EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| EnvFilter::new(self.config.level.clone()));

        let registry = registry.with(env_filter);

        let mut layers: Vec<Box<dyn Layer<tracing_subscriber::Registry> + Send + Sync>> = Vec::new();

        if self.config.format == "json" {
            let json_layer = self.build_json_layer();
            layers.push(Box::new(json_layer));
        } else {
            let fmt_layer = self.build_fmt_layer();
            layers.push(Box::new(fmt_layer));
        }

        let file_layer = self.build_file_layer()?;
        if let Some(layer) = file_layer {
            layers.push(Box::new(layer));
        }

        Ok(registry.with(layers).into())
    }

    fn build_json_layer<S>(&self) -> impl Layer<S> + Send + Sync
    where
        S: Subscriber + for<'a> LookupSpan<'a>,
    {
        fmt::layer()
            .json()
            .with_target(true)
            .with_file(true)
            .with_line_number(true)
            .with_thread_ids(true)
            .with_thread_names(true)
            .with_current_span(true)
            .with_span_list(true)
            .with_timer(UtcTime)
    }

    fn build_fmt_layer<S>(&self) -> impl Layer<S> + Send + Sync
    where
        S: Subscriber + for<'a> LookupSpan<'a>,
    {
        let builder = fmt::layer()
            .with_target(true)
            .with_file(true)
            .with_line_number(true)
            .with_timer(UtcTime);

        if self.config.ansi_colors {
            builder.with_ansi(true).boxed()
        } else {
            builder.with_ansi(false).boxed()
        }
    }

    fn build_file_layer<S>(&self) -> AppResult<Option<impl Layer<S> + Send + Sync>>
    where
        S: Subscriber + for<'a> LookupSpan<'a>,
    {
        if self.config.dir.is_empty() {
            return Ok(None);
        }

        let dir = Path::new(&self.config.dir);
        std::fs::create_dir_all(dir).map_err(|e| AppError::InternalError(format!("创建日志目录失败: {}", e)))?;

        let rotation = match self.config.rotation.as_str() {
            "minutely" => Rotation::MINUTELY,
            "hourly" => Rotation::HOURLY,
            "daily" => Rotation::DAILY,
            "never" => Rotation::NEVER,
            _ => Rotation::DAILY,
        };

        let file_appender = RollingFileAppender::builder()
            .rotation(rotation)
            .filename_prefix("enterprise-mw")
            .filename_suffix("log")
            .build(dir)
            .map_err(|e| AppError::InternalError(format!("创建文件日志器失败: {}", e)))?;

        let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);

        let mut guards = self.guards.write();
        guards.push(guard);
        drop(guards);

        let layer = fmt::layer()
            .with_writer(non_blocking)
            .with_target(true)
            .with_file(true)
            .with_line_number(true)
            .with_timer(UtcTime)
            .with_ansi(false);

        if self.config.format == "json" {
            Ok(Some(layer.json().boxed()))
        } else {
            Ok(Some(layer.boxed()))
        }
    }

    pub fn log_structured(&self, record: StructuredLogRecord) {
        match record.level.as_str() {
            "trace" => tracing::trace!(target: &record.target, ?record, "{}", record.message),
            "debug" => tracing::debug!(target: &record.target, ?record, "{}", record.message),
            "info" => tracing::info!(target: &record.target, ?record, "{}", record.message),
            "warn" => tracing::warn!(target: &record.target, ?record, "{}", record.message),
            "error" => tracing::error!(target: &record.target, ?record, "{}", record.message),
            _ => tracing::info!(target: &record.target, ?record, "{}", record.message),
        }
    }
}

impl Drop for StructuredLogger {
    fn drop(&mut self) {
        let mut guards = self.guards.write();
        guards.clear();
    }
}

struct UtcTime;

impl FormatTime for UtcTime {
    fn format_time(&self, w: &mut Writer<'_>) -> std::fmt::Result {
        write!(w, "{}", Utc::now().to_rfc3339())
    }
}

pub fn create_log_record(
    level: &str,
    target: &str,
    message: &str,
    trace_id: Option<String>,
    fields: HashMap<String, serde_json::Value>,
) -> StructuredLogRecord {
    StructuredLogRecord {
        timestamp: Utc::now().to_rfc3339(),
        level: level.to_string(),
        target: target.to_string(),
        message: message.to_string(),
        module: None,
        file: None,
        line: None,
        span_id: None,
        trace_id,
        fields,
    }
}

#[macro_export]
macro_rules! structured_info {
    (target: $target:expr, trace_id: $trace_id:expr, $($key:ident = $value:expr),*; $msg:expr) => {
        {
            let mut fields = std::collections::HashMap::new();
            $(
                fields.insert(stringify!($key).to_string(), serde_json::json!($value));
            )*
            let record = $crate::logging::create_log_record("info", $target, $msg, Some($trace_id.to_string()), fields);
            tracing::info!(target: $target, ?record, "{}", $msg);
        }
    };
}

#[macro_export]
macro_rules! structured_warn {
    (target: $target:expr, trace_id: $trace_id:expr, $($key:ident = $value:expr),*; $msg:expr) => {
        {
            let mut fields = std::collections::HashMap::new();
            $(
                fields.insert(stringify!($key).to_string(), serde_json::json!($value));
            )*
            let record = $crate::logging::create_log_record("warn", $target, $msg, Some($trace_id.to_string()), fields);
            tracing::warn!(target: $target, ?record, "{}", $msg);
        }
    };
}

#[macro_export]
macro_rules! structured_error {
    (target: $target:expr, trace_id: $trace_id:expr, $($key:ident = $value:expr),*; $msg:expr) => {
        {
            let mut fields = std::collections::HashMap::new();
            $(
                fields.insert(stringify!($key).to_string(), serde_json::json!($value));
            )*
            let record = $crate::logging::create_log_record("error", $target, $msg, Some($trace_id.to_string()), fields);
            tracing::error!(target: $target, ?record, "{}", $msg);
        }
    };
}

#[macro_export]
macro_rules! structured_debug {
    (target: $target:expr, trace_id: $trace_id:expr, $($key:ident = $value:expr),*; $msg:expr) => {
        {
            let mut fields = std::collections::HashMap::new();
            $(
                fields.insert(stringify!($key).to_string(), serde_json::json!($value));
            )*
            let record = $crate::logging::create_log_record("debug", $target, $msg, Some($trace_id.to_string()), fields);
            tracing::debug!(target: $target, ?record, "{}", $msg);
        }
    };
}

pub fn init_logging(config: LoggingConfig) -> AppResult<StructuredLogger> {
    let logger = StructuredLogger::new(config);
    logger.init()?;
    Ok(logger)
}

pub fn init_simple_logging(level: &str) -> AppResult<()> {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(level));

    let subscriber = tracing_subscriber::registry()
        .with(filter)
        .with(
            fmt::layer()
                .with_target(true)
                .with_file(true)
                .with_line_number(true)
                .with_timer(UtcTime),
        );

    dispatcher::set_global_default(subscriber.into())
        .map_err(|e| AppError::InternalError(format!("设置全局日志调度器失败: {}", e)))?;

    Ok(())
}

trait BoxedLayer<S: Subscriber> {
    fn boxed(self) -> Box<dyn Layer<S> + Send + Sync>;
}

impl<L, S> BoxedLayer<S> for L
where
    L: Layer<S> + Send + Sync + 'static,
    S: Subscriber,
{
    fn boxed(self) -> Box<dyn Layer<S> + Send + Sync> {
        Box::new(self)
    }
}
