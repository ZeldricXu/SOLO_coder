use anyhow::{Context, Result};
use opentelemetry::KeyValue;
use opentelemetry_otlp::WithExportConfig;
use opentelemetry_sdk::Resource;
use serde::{Deserialize, Serialize};
use tracing::Level;
use tracing_subscriber::fmt;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::{EnvFilter, Registry};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TracingConfig {
    pub level: String,
    pub service_name: String,
    pub endpoint: Option<String>,
    pub json_format: bool,
    pub otlp_enabled: bool,
}

impl Default for TracingConfig {
    fn default() -> Self {
        Self {
            level: "info".to_string(),
            service_name: "inference-service".to_string(),
            endpoint: Some("http://localhost:4317".to_string()),
            json_format: false,
            otlp_enabled: false,
        }
    }
}

fn parse_level(level: &str) -> Level {
    match level.to_lowercase().as_str() {
        "trace" => Level::TRACE,
        "debug" => Level::DEBUG,
        "warn" => Level::WARN,
        "error" => Level::ERROR,
        _ => Level::INFO,
    }
}

pub fn init_tracing(config: &TracingConfig) -> Result<()> {
    let level = parse_level(&config.level);
    let env_filter = EnvFilter::builder()
        .with_default_directive(level.into())
        .from_env_lossy();

    let registry = Registry::default().with(env_filter);

    if config.otlp_enabled {
        let endpoint = config
            .endpoint
            .as_deref()
            .unwrap_or("http://localhost:4317");

        let tracer = opentelemetry_otlp::new_pipeline()
            .tracing()
            .with_exporter(
                opentelemetry_otlp::new_exporter()
                    .tonic()
                    .with_endpoint(endpoint),
            )
            .with_trace_config(
                opentelemetry_sdk::trace::config().with_resource(Resource::new(vec![
                    KeyValue::new(
                        opentelemetry_semantic_conventions::resource::SERVICE_NAME,
                        config.service_name.clone(),
                    ),
                ])),
            )
            .install_batch(opentelemetry_sdk::runtime::Tokio)
            .context("Failed to install OTLP tracer")?;

        let otlp_layer = tracing_opentelemetry::layer().with_tracer(tracer);

        if config.json_format {
            let fmt_layer = fmt::layer()
                .json()
                .with_target(true)
                .with_level(true)
                .with_current_span(true);

            registry
                .with(otlp_layer)
                .with(fmt_layer)
                .try_init()
                .context("Failed to init tracing subscriber")?;
        } else {
            let fmt_layer = fmt::layer().with_target(true).with_level(true);

            registry
                .with(otlp_layer)
                .with(fmt_layer)
                .try_init()
                .context("Failed to init tracing subscriber")?;
        }

        let meter_provider = opentelemetry_otlp::new_pipeline()
            .metrics(opentelemetry_sdk::runtime::Tokio)
            .with_exporter(
                opentelemetry_otlp::new_exporter()
                    .tonic()
                    .with_endpoint(endpoint),
            )
            .with_resource(Resource::new(vec![KeyValue::new(
                opentelemetry_semantic_conventions::resource::SERVICE_NAME,
                config.service_name.clone(),
            )]))
            .build()
            .context("Failed to build OTLP metrics pipeline")?;

        opentelemetry::global::set_meter_provider(meter_provider);
    } else if config.json_format {
        let fmt_layer = fmt::layer()
            .json()
            .with_target(true)
            .with_level(true)
            .with_current_span(true);

        registry
            .with(fmt_layer)
            .try_init()
            .context("Failed to init tracing subscriber")?;
    } else {
        let fmt_layer = fmt::layer()
            .with_target(true)
            .with_level(true)
            .pretty();

        registry
            .with(fmt_layer)
            .try_init()
            .context("Failed to init tracing subscriber")?;
    }

    Ok(())
}

pub fn init_panic_hook() {
    let original_hook = std::panic::take_hook();

    std::panic::set_hook(Box::new(move |panic_info| {
        let payload = panic_info.payload();
        let message = if let Some(s) = payload.downcast_ref::<&str>() {
            (*s).to_string()
        } else if let Some(s) = payload.downcast_ref::<String>() {
            s.clone()
        } else {
            "Unknown panic".to_string()
        };

        let location = panic_info
            .location()
            .map(|loc| format!("{}:{}:{}", loc.file(), loc.line(), loc.column()))
            .unwrap_or_else(|| "unknown".to_string());

        tracing::error!(
            target: "panic",
            message = %message,
            location = %location,
            "PANIC occurred"
        );

        original_hook(panic_info);
    }));
}

pub fn shutdown_tracing() {
    opentelemetry::global::shutdown_tracer_provider();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_level() {
        assert_eq!(parse_level("trace"), Level::TRACE);
        assert_eq!(parse_level("debug"), Level::DEBUG);
        assert_eq!(parse_level("info"), Level::INFO);
        assert_eq!(parse_level("warn"), Level::WARN);
        assert_eq!(parse_level("error"), Level::ERROR);
        assert_eq!(parse_level("INFO"), Level::INFO);
        assert_eq!(parse_level("invalid"), Level::INFO);
    }

    #[test]
    fn test_tracing_config_default() {
        let cfg = TracingConfig::default();
        assert_eq!(cfg.level, "info");
        assert_eq!(cfg.service_name, "inference-service");
        assert!(!cfg.json_format);
        assert!(!cfg.otlp_enabled);
    }

    #[test]
    fn test_tracing_config_clone() {
        let cfg = TracingConfig {
            level: "debug".to_string(),
            service_name: "test-service".to_string(),
            endpoint: Some("http://example.com:4317".to_string()),
            json_format: true,
            otlp_enabled: true,
        };
        let cloned = cfg.clone();
        assert_eq!(cfg.level, cloned.level);
        assert_eq!(cfg.service_name, cloned.service_name);
        assert_eq!(cfg.endpoint, cloned.endpoint);
        assert_eq!(cfg.json_format, cloned.json_format);
        assert_eq!(cfg.otlp_enabled, cloned.otlp_enabled);
    }
}
