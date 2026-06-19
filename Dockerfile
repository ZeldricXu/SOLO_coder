# 多阶段构建：使用 musl 静态编译 Rust 二进制
# Stage 1: 构建阶段
FROM rust:1.78-alpine AS builder

# 安装编译依赖（musl-dev、protobuf、openssl 等）
RUN apk add --no-cache \
    musl-dev \
    pkgconfig \
    openssl-dev \
    protobuf \
    make \
    bash

# 启用静态 CRT 编译，生成完全静态的二进制
ENV RUSTFLAGS="-C target-feature=+crt-static"

# 设置工作目录
WORKDIR /app

# 先创建空目录并复制 Cargo.toml 和 crates/，用于缓存依赖
RUN mkdir -p /app/crates

# 复制工作区配置
COPY Cargo.toml Cargo.lock /app/

# 复制所有 crate 的源代码
COPY crates /app/crates

# 构建 release 版本，目标平台 x86_64-unknown-linux-musl
RUN cargo build --release --target x86_64-unknown-linux-musl -p collab-server

# 剥离二进制符号以减小体积
RUN strip target/x86_64-unknown-linux-musl/release/collab-engine

# Stage 2: 运行阶段
FROM alpine:3.20 AS runtime

# 创建非 root 用户 appuser (uid=1000)
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

# 安装运行时最小依赖（CA 证书和时区数据），然后清理 apk 缓存
RUN apk add --no-cache ca-certificates tzdata && \
    rm -rf /var/cache/apk/*

# 从构建阶段复制静态编译的二进制
COPY --from=builder /app/target/x86_64-unknown-linux-musl/release/collab-engine /usr/local/bin/collab-engine

# 创建数据目录并设置权限
RUN mkdir -p /data && \
    chown -R appuser:appuser /data

# 设置工作目录
WORKDIR /data

# 切换到非 root 用户
USER appuser

# 暴露 HTTP API 端口和 Metrics 端口
EXPOSE 3000 9090

# 环境变量默认值
ENV PORT=3000 \
    METRICS_PORT=9090 \
    COLLAB_MODE=server

# 健康检查：轮询 /health 端点
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=30s \
    CMD wget -qO- http://localhost:3000/health || exit 1

# 启动入口
ENTRYPOINT ["/usr/local/bin/collab-engine"]
