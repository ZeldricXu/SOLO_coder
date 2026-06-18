FROM python:3.12 AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    gfortran \
    libpq-dev \
    python3-dev \
    libgeos-dev \
    proj-bin \
    libproj-dev \
    gdal-bin \
    libgdal-dev \
    libffi-dev \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN pip install --no-cache-dir --prefix=/install -r requirements.txt


FROM python:3.12-slim AS base

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    libgeos-c1v5 \
    libproj25 \
    libgdal34 \
    gdal-bin \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=builder /install /usr/local

COPY . .

RUN mkdir -p data/tiles data/cache data/sample models \
    && chown -R appuser:appuser /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app:${PATH}"

USER appuser

EXPOSE 8000


FROM base AS api

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["gunicorn", "main:app", "-k", "uvicorn.workers.UvicornWorker", "-b", "0.0.0.0:8000", "-w", "4", "--timeout", "120", "--graceful-timeout", "30", "--access-logfile", "-"]


FROM base AS celery-worker

ENV CELERY_WORKER=true

CMD ["celery", "-A", "celery_worker.celery_app", "worker", "--loglevel=info", "-c", "4", "--max-tasks-per-child=1000", "--without-heartbeat"]


FROM base AS celery-beat

ENV CELERY_BEAT=true

CMD ["celery", "-A", "celery_worker.celery_app", "beat", "--loglevel=info", "--scheduler", "celery.beat.PersistentScheduler"]
