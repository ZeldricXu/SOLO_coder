import os
from typing import Optional

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("otel")

_otel_initialized = False


def init_opentelemetry(app=None) -> bool:
    global _otel_initialized

    if _otel_initialized:
        return True

    settings = get_settings()
    otel_settings = getattr(settings, "otel", None)

    if not otel_settings or not getattr(otel_settings, "enabled", False):
        logger.info("OpenTelemetry disabled by configuration")
        return False

    try:
        from opentelemetry import trace
        from opentelemetry.sdk.resources import SERVICE_NAME, Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
        from opentelemetry.propagate import set_global_textmap
        from opentelemetry.propagators.composite import CompositePropagator
        from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
        from opentelemetry.baggage.propagation import W3CBaggagePropagator

        service_name = getattr(otel_settings, "service_name", "api-gateway")
        endpoint = getattr(otel_settings, "endpoint", "http://localhost:4317")

        resource = Resource(attributes={SERVICE_NAME: service_name})

        provider = TracerProvider(resource=resource)
        try:
            exporter = OTLPSpanExporter(endpoint=endpoint, insecure=True)
            processor = BatchSpanProcessor(exporter)
            provider.add_span_processor(processor)
        except Exception as e:
            logger.warning(f"Failed to configure OTLP exporter, using no-op: {e}")

        trace.set_tracer_provider(provider)

        set_global_textmap(
            CompositePropagator(
                [
                    TraceContextTextMapPropagator(),
                    W3CBaggagePropagator(),
                ]
            )
        )

        if app is not None:
            FastAPIInstrumentor.instrument_app(
                app,
                excluded_urls="/health,/live,/ready,/metrics,/docs,/openapi.json,/redoc",
            )

        HTTPXClientInstrumentor().instrument()

        _otel_initialized = True
        logger.info("OpenTelemetry initialized successfully", service_name=service_name, endpoint=endpoint)
        return True

    except ImportError as e:
        logger.warning(f"OpenTelemetry packages not available: {e}")
        return False
    except Exception as e:
        logger.error(f"Failed to initialize OpenTelemetry: {e}")
        return False


def get_trace_headers() -> dict:
    try:
        from opentelemetry import propagate
        from opentelemetry.propagate import inject

        carrier = {}
        inject(carrier)
        return carrier
    except Exception:
        return {}


def inject_trace_context_headers(headers: dict) -> dict:
    try:
        trace_headers = get_trace_headers()
        result = dict(headers)
        result.update(trace_headers)
        return result
    except Exception:
        return dict(headers)
