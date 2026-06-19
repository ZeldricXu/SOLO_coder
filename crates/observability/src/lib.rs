pub mod tracing;
pub mod metrics;
pub mod latency;
pub mod trace;

pub use tracing::{TracingConfig, init_tracing, init_panic_hook, shutdown_tracing};
pub use metrics::{
    MetricsRegistry, MetricsCollector, GpuMetrics,
    record_inference_latency, record_gpu_metrics, increment_requests,
    increment_success, increment_failure,
    increment_routing_decision, increment_rate_limit_hit,
    record_batch_size, record_db_query_latency, record_redis_op_latency,
    set_model_loaded_count, record_gpu_memory_total,
    update_qps_metrics, get_model_qps, get_all_qps,
    INFERENCE_LATENCY_MS, INFERENCE_REQUESTS_TOTAL, INFERENCE_QPS,
    INFERENCE_SUCCESS_TOTAL, INFERENCE_FAILURE_TOTAL,
    GPU_UTILIZATION_PERCENT, GPU_MEMORY_USED_MB, GPU_MEMORY_TOTAL_MB,
    MODEL_LOADED_COUNT, BATCH_SIZE,
    ROUTING_DECISIONS_TOTAL, RATE_LIMIT_HITS_TOTAL, DB_QUERY_LATENCY_MS, REDIS_OP_LATENCY_MS,
};
pub use latency::{LatencyHistogram, LatencySnapshot, ModelLatencyStats, Percentiles};
pub use trace::{
    RequestTracingMiddleware, TraceContext,
    X_TRACE_ID, X_SPAN_ID, X_REQUEST_ID,
    tracing_middleware, gateway_span, router_span, runtime_span, model_forward_span,
    with_trace_context, async_with_trace_context,
};
pub use trace::otel as trace_otel;
