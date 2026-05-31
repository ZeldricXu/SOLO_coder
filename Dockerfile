# ============================================
# 区块链基础设施平台 - 生产级Dockerfile
# 采用多阶段构建，优化镜像大小和安全性
# ============================================

# ---- 阶段1: 构建阶段 ----
FROM python:3.11-slim-bookworm AS builder

LABEL maintainer="Blockchain Infra Team <team@blockchain-infra.com>"
LABEL description="Blockchain Infrastructure Platform - Builder Stage"
LABEL org.opencontainers.image.source="https://github.com/blockchain-infra/platform"
LABEL org.opencontainers.image.licenses="MIT"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=100 \
    POETRY_VIRTUALENVS_CREATE=false \
    VIRTUAL_ENV=/opt/venv

WORKDIR /app

# 安装系统依赖（构建阶段）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    libffi-dev \
    libssl-dev \
    libgmp-dev \
    libsecp256k1-dev \
    libyaml-dev \
    git \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 创建虚拟环境
RUN python -m venv ${VIRTUAL_ENV}
ENV PATH="${VIRTUAL_ENV}/bin:${PATH}"

# 升级pip和基础工具
RUN pip install --upgrade pip setuptools wheel

# 复制依赖文件
COPY requirements/base.txt requirements/prod.txt ./requirements/
COPY pyproject.toml setup.py ./

# 安装生产环境依赖
RUN pip install --no-cache-dir -r requirements/prod.txt

# 安装项目本身
COPY src/ ./src/
RUN pip install --no-deps -e .

# ---- 阶段2: 运行时阶段 ----
FROM python:3.11-slim-bookworm AS runtime

LABEL maintainer="Blockchain Infra Team <team@blockchain-infra.com>"
LABEL description="Blockchain Infrastructure Platform - Production Runtime"
LABEL org.opencontainers.image.source="https://github.com/blockchain-infra/platform"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.version="1.0.0"

# 安全环境变量
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:${PATH}" \
    TZ=UTC \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONPATH=/app \
    UWSGI_PROCESSES=4 \
    UWSGI_THREADS=2

# 运行时用户（非root用户提升安全性）
RUN groupadd -r appuser && \
    useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app

# 安装最小化的系统运行时依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    libssl3 \
    libffi8 \
    libgmp10 \
    libsecp256k1-1 \
    libyaml-0-2 \
    ca-certificates \
    tzdata \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/* \
    && update-ca-certificates \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 从构建阶段复制虚拟环境
COPY --from=builder --chown=appuser:appuser ${VIRTUAL_ENV} ${VIRTUAL_ENV}

# 复制应用代码
COPY --from=builder --chown=appuser:appuser /app/src/ ./src/
COPY --chown=appuser:appuser configs/ ./configs/
COPY --chown=appuser:appuser .env.example ./.env.example
COPY --chown=appuser:appuser pyproject.toml setup.py ./

# 创建必要的目录并设置权限
RUN mkdir -p /app/data /app/logs /app/tmp \
    && chown -R appuser:appuser /app \
    && chmod -R 755 /app/data /app/logs /app/tmp

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8000/api/v1/health || exit 1

# 暴露端口
EXPOSE 8000

# 切换到非root用户
USER appuser

# 默认启动命令
CMD ["uvicorn", "src.main:app", \
    "--host", "0.0.0.0", \
    "--port", "8000", \
    "--workers", "4", \
    "--loop", "uvloop", \
    "--http", "httptools", \
    "--proxy-headers", \
    "--forwarded-allow-ips", "*", \
    "--no-server-header", \
    "--date-header"]

# ---- 开发阶段镜像 ----
FROM runtime AS development

USER root

# 安装开发工具
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    gdb \
    vim \
    htop \
    iftop \
    net-tools \
    iputils-ping \
    && rm -rf /var/lib/apt/lists/*

# 安装开发依赖
COPY requirements/dev.txt requirements/test.txt ./requirements/
RUN pip install --no-cache-dir -r requirements/dev.txt -r requirements/test.txt

USER appuser

# 开发模式启动
CMD ["uvicorn", "src.main:app", \
    "--host", "0.0.0.0", \
    "--port", "8000", \
    "--reload", \
    "--reload-dir", "src", \
    "--log-level", "debug"]
