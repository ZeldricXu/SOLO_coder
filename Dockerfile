FROM python:3.12-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    POETRY_VERSION=1.7.1 \
    POETRY_NO_INTERACTION=1 \
    POETRY_VIRTUALENVS_IN_PROJECT=1 \
    POETRY_VIRTUALENVS_CREATE=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    libpq-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --upgrade pip setuptools wheel
RUN pip install "poetry==$POETRY_VERSION"

COPY pyproject.toml README.md* ./
RUN poetry install --no-root --no-interaction --no-ansi --only main

COPY app ./app
COPY alembic.ini ./
COPY alembic ./alembic 2>/dev/null || true
COPY scripts ./scripts 2>/dev/null || true

RUN poetry install --no-interaction --no-ansi --only main

FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PATH="/app/.venv/bin:$PATH" \
    APP_ENV=production \
    WORKERS=2

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/.venv /app/.venv
COPY --from=builder /app/app /app/app
COPY --from=builder /app/alembic /app/alembic 2>/dev/null || true
COPY --from=builder /app/alembic.ini /app/alembic.ini 2>/dev/null || true
COPY --from=builder /app/scripts /app/scripts 2>/dev/null || true
COPY --from=builder /app/pyproject.toml /app/pyproject.toml

RUN mkdir -p /app/logs

EXPOSE 8000 50051

HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/api/v1/health || exit 1

CMD gunicorn app.main:app \
    --worker-class uvicorn.workers.UvicornWorker \
    --workers $WORKERS \
    --bind 0.0.0.0:8000 \
    --timeout 120 \
    --access-logfile - \
    --error-logfile -
