# ========================================
# 多阶段 Dockerfile
# 阶段1: 依赖缓存和构建
# 阶段2: 运行时镜像
# 阶段3: 开发镜像
# ========================================

# ---------- 基础镜像 ----------
FROM rust:1.75.0-slim-bookworm AS base
WORKDIR /app
RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    gcc \
    libssl-dev \
    pkg-config \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# ---------- 阶段1: 依赖构建缓存 ----------
FROM base AS planner
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
RUN cargo init --lib --name data-transformer
COPY Cargo.toml Cargo.lock ./
RUN mkdir -p .cargo && \
    echo '[build]' > .cargo/config.toml && \
    echo 'target-dir = "/app/target"' >> .cargo/config.toml

# ---------- 阶段2: 依赖预构建 ----------
FROM base AS deps
WORKDIR /app
COPY --from=planner /app/Cargo.toml /app/Cargo.lock ./
COPY --from=planner /app/.cargo ./.cargo
RUN mkdir -p src && \
    echo 'fn main() { println!("placeholder"); }' > src/main.rs && \
    echo '// placeholder' > src/lib.rs
RUN cargo build --release 2>&1 || true
RUN rm -rf src

# ---------- 阶段3: 应用构建 ----------
FROM base AS builder
WORKDIR /app

# 复制配置文件
COPY Cargo.toml Cargo.lock ./
COPY .cargo ./.cargo
COPY config ./config
COPY src ./src
COPY --from=deps /app/target ./target

# 构建应用
ARG BUILD_PROFILE=release
ARG GIT_SHA
ARG BUILD_TIME

ENV GIT_SHA=${GIT_SHA}
ENV BUILD_TIME=${BUILD_TIME}

RUN if [ "$BUILD_PROFILE" = "release" ]; then \
        cargo build --release; \
    else \
        cargo build; \
    fi

# 剥离调试符号（仅release模式）
RUN if [ "$BUILD_PROFILE" = "release" ]; then \
        strip target/release/data-transformer; \
    fi

# ---------- 阶段4: 生产运行时镜像 ----------
FROM debian:bookworm-slim AS runtime
WORKDIR /app

# 安装运行时依赖
RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    libpq5 \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建非root用户
RUN groupadd -r appuser && useradd -r -g appuser appuser

# 复制配置文件
COPY --from=builder /app/config /app/config
COPY --from=builder /app/target/release/data-transformer /app/data-transformer

# 创建必要目录
RUN mkdir -p /app/logs /app/config && \
    chown -R appuser:appuser /app

# 设置环境变量
ENV RUST_ENV=production
ENV RUST_LOG=info
ENV CONFIG_DIR=/app/config
ENV RUST_BACKTRACE=1

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# 暴露端口
EXPOSE 8080 9090

# 切换到非root用户
USER appuser

# 启动命令
CMD ["/app/data-transformer"]

# ---------- 阶段5: 开发镜像 ----------
FROM base AS development
WORKDIR /app

# 安装开发工具
RUN apt-get update && apt-get install -y \
    git \
    vim \
    less \
    lldb \
    valgrind \
    && rm -rf /var/lib/apt/lists/*

# 安装cargo插件
RUN cargo install cargo-watch \
    cargo-tarpaulin \
    cargo-audit \
    cargo-outdated \
    cargo-udeps \
    cargo-tree \
    cargo-bloat \
    --locked

# 复制源码
COPY . .

# 设置环境变量
ENV RUST_ENV=development
ENV RUST_LOG=debug,data_transformer=trace
ENV RUST_BACKTRACE=full

# 暴露端口
EXPOSE 8080 9090

# 开发模式启动（自动重载）
CMD ["cargo", "watch", "-x", "run", "-w", "src", "-w", "config"]

# ---------- 阶段6: 测试镜像 ----------
FROM base AS tester
WORKDIR /app

# 安装测试依赖
RUN apt-get update && apt-get install -y \
    postgresql-client \
    redis-tools \
    && rm -rf /var/lib/apt/lists/*

# 安装cargo测试插件
RUN cargo install cargo-tarpaulin \
    cargo-audit \
    cargo-udeps \
    --locked

# 复制源码
COPY . .

# 设置环境变量
ENV RUST_ENV=test
ENV RUST_LOG=debug
ENV RUST_BACKTRACE=full

# 运行测试
CMD ["cargo", "tarpaulin", "--config", "tarpaulin.toml"]
