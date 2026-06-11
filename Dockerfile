# syntax=docker/dockerfile:1.6

# ---------- Stage 1: Builder ----------
FROM python:3.12-slim-bookworm AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        gcc \
        libpq-dev \
        curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN pip install --prefix=/install --no-cache-dir -r requirements.txt \
    && find /install -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true \
    && find /install -type f -name "*.pyc" -delete 2>/dev/null || true

COPY src/ /build/src/
COPY pyproject.toml /build/

RUN pip install --prefix=/install --no-cache-dir --no-deps /build

# ---------- Stage 2: Runtime (distroless) ----------
FROM gcr.io/distroless/python3-debian12:nonroot AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/lib/python3.12/site-packages \
    PATH=/app/bin:$PATH \
    GATEWAY_HOST=0.0.0.0 \
    GATEWAY_PORT=8080 \
    METRICS_PORT=9090

COPY --from=builder /install /app
COPY --from=builder /build/src /app/src
COPY migrations/ /app/migrations/

WORKDIR /app

EXPOSE 8080 9090

USER nonroot

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/app/bin/python", "-c", "import urllib.request,sys; r=urllib.request.urlopen('http://127.0.0.1:8080/live'); sys.exit(0 if r.status==200 else 1)"]

ENTRYPOINT ["/app/bin/python"]
CMD ["-m", "uvicorn", "gateway.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "4", "--loop", "uvloop"]
