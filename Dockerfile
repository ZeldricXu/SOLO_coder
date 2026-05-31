FROM python:3.9-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    POETRY_VERSION=1.7.1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements-prod.txt .
RUN pip wheel --no-cache-dir --wheel-dir /app/wheels -r requirements-prod.txt

FROM python:3.9-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

ENV APP_HOME=/app \
    APP_USER=streamsql \
    APP_UID=1000 \
    APP_GID=1000

WORKDIR ${APP_HOME}

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    curl \
    tzdata \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r ${APP_USER} --gid=${APP_GID} \
    && useradd -r -g ${APP_USER} --uid=${APP_UID} -m ${APP_USER} \
    && mkdir -p ${APP_HOME}/data ${APP_HOME}/logs ${APP_HOME}/config \
    && chown -R ${APP_USER}:${APP_USER} ${APP_HOME}

COPY --from=builder /app/wheels /wheels
COPY requirements-prod.txt .

RUN pip install --no-cache /wheels/* \
    && rm -rf /wheels requirements-prod.txt

COPY --chown=${APP_USER}:${APP_USER} streamsql/ ./streamsql/
COPY --chown=${APP_USER}:${APP_USER} config/ ./config/

USER ${APP_USER}

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["gunicorn", "streamsql.main:app", \
    "--workers", "4", \
    "--worker-class", "uvicorn.workers.UvicornWorker", \
    "--bind", "0.0.0.0:8000", \
    "--timeout", "120", \
    "--keep-alive", "5", \
    "--access-logfile", "-", \
    "--error-logfile", "-"]

FROM python:3.9-slim AS dev

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    libpq-dev \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt requirements-prod.txt requirements-dev.txt ./
RUN pip install -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "streamsql.main:app", "--reload", "--host", "0.0.0.0", "--port", "8000"]

FROM python:3.9-slim AS worker

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

ENV APP_HOME=/app \
    APP_USER=streamsql \
    APP_UID=1000 \
    APP_GID=1000

WORKDIR ${APP_HOME}

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r ${APP_USER} --gid=${APP_GID} \
    && useradd -r -g ${APP_USER} --uid=${APP_UID} -m ${APP_USER} \
    && chown -R ${APP_USER}:${APP_USER} ${APP_HOME}

COPY --from=builder /app/wheels /wheels
COPY requirements-prod.txt .
RUN pip install --no-cache /wheels/* && rm -rf /wheels

COPY --chown=${APP_USER}:${APP_USER} streamsql/ ./streamsql/
COPY --chown=${APP_USER}:${APP_USER} config/ ./config/

USER ${APP_USER}

CMD ["celery", "-A", "streamsql.workers.celery_app", "worker", \
    "--loglevel=info", \
    "--concurrency=4", \
    "--pool=prefork", \
    "--max-tasks-per-child=1000", \
    "--time-limit=600", \
    "--soft-time-limit=300"]
