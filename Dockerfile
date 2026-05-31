# Stage 1: Builder
FROM python:3.10-slim AS builder

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements/prod.txt /tmp/requirements.txt
RUN pip install --prefix=/install -r /tmp/requirements.txt

COPY src /app/src
COPY pyproject.toml /app/

RUN pip install --prefix=/install --no-deps -e .


# Stage 2: Production
FROM python:3.10-slim AS production

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PATH="/install/bin:$PATH" \
    PYTHONPATH="/install/lib/python3.10/site-packages:/app/src:$PYTHONPATH"

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app \
    && useradd -r -g app app

COPY --from=builder /install /install
COPY --from=builder /app/src /app/src
COPY run.py /app/
COPY alembic.ini /app/ 2>/dev/null || true

RUN mkdir -p /app/logs /app/data /app/traces \
    && chown -R app:app /app

USER app

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${PORT:-8000}/health || exit 1

EXPOSE 8000

ENV WORKERS=2 \
    PORT=8000 \
    HOST=0.0.0.0

CMD ["sh", "-c", "gunicorn platform_engineer.app.main:app \
    --workers ${WORKERS} \
    --worker-class uvicorn.workers.UvicornWorker \
    --bind ${HOST}:${PORT} \
    --timeout 60 \
    --keep-alive 5 \
    --access-logfile - \
    --error-logfile - \
    --log-level info"]


# Stage 3: Development
FROM python:3.10-slim AS development

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    libpq-dev \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements /tmp/requirements
RUN pip install -r /tmp/requirements/dev.txt

COPY . /app/

ENV PYTHONPATH="/app/src:$PYTHONPATH"

EXPOSE 8000

CMD ["uvicorn", "platform_engineer.app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
