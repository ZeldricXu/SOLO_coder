# ========================================
# 多阶段构建 - 基础镜像
# ========================================
FROM python:3.11-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    POETRY_VERSION=1.7.0 \
    POETRY_HOME="/opt/poetry" \
    POETRY_VIRTUALENVS_IN_PROJECT=true \
    POETRY_NO_INTERACTION=1

ENV PATH="$POETRY_HOME/bin:$VENV_PATH/bin:$PATH"

WORKDIR /app

# 安装系统依赖
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        curl \
        gcc \
        g++ \
        libpq-dev \
        python3-dev \
    && rm -rf /var/lib/apt/lists/*

# 安装 Poetry
RUN curl -sSL https://install.python-poetry.org | python3 -

# ========================================
# 开发镜像
# ========================================
FROM base AS development

ENV APP_ENV=development

# 复制依赖文件
COPY pyproject.toml poetry.lock* ./

# 安装所有依赖（包括开发依赖）
RUN poetry install --no-root

# 复制源代码
COPY . .

# 暴露端口
EXPOSE 8000 9090

# 启动命令（支持热重载）
CMD ["poetry", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]

# ========================================
# 构建镜像（仅安装生产依赖）
# ========================================
FROM base AS builder

ENV APP_ENV=production

COPY pyproject.toml poetry.lock* ./
RUN poetry install --no-root --without dev

COPY . .
RUN poetry build --format wheel

# ========================================
# 生产镜像
# ========================================
FROM python:3.11-slim AS production

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    APP_ENV=production \
    APP_PORT=8000

WORKDIR /app

# 安装系统依赖（仅运行时需要）
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        libpq5 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 1000 -s /bin/bash appuser

# 从构建阶段复制 wheel 包
COPY --from=builder /app/dist/*.whl /tmp/
COPY --from=builder /app/.venv /app/.venv

ENV PATH="/app/.venv/bin:$PATH"

# 安装应用
RUN pip install --no-cache-dir /tmp/*.whl \
    && rm -rf /tmp/*.whl

# 复制源代码
COPY --from=builder /app/app ./app
COPY --from=builder /app/core ./core
COPY --from=builder /app/modules ./modules
COPY --from=builder /app/models ./models
COPY --from=builder /app/.env.production ./.env

# 切换到非 root 用户
USER appuser

# 暴露端口
EXPOSE 8000 9090

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# 启动命令
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
