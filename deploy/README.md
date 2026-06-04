# 日志分析管线 - 部署手册

## 系统架构

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Log Agent     │────▶│     Kafka        │────▶│  Stream Server    │
│  (DaemonSet)   │     │   (12 partitions)│     │  (Deployment)    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                             │
                                                             ▼
                                                  ┌─────────────────┐
                                                  │  Elasticsearch  │
                                                  │  (Index + ILM)│
                                                  └─────────────────┘
                                                             │
                                                             ▼
                                                  ┌─────────────────┐
                                                  │  Alert Manager │
                                                  │  (DingTalk +  │
                                                  │   LLM Root    │
                                                  └─────────────────┘
```

## 1. 前置要求

### 基础设施要求：

- Kubernetes 集群 >= 1.24
- Helm >= 3.0+
- Strimzi Kafka Operator (管理 Kafka 集群)
- Elasticsearch >= 8.0
- Prometheus Operator (用于 ServiceMonitor)
- cert-manager (可选，用于 TLS)

### 资源要求：

| 组件 | CPU 请求 | CPU 限制 | 内存请求 | 内存限制 |
|------|----------|----------|----------|----------|
| Log Agent (每个节点) | 100m | 500m | 128Mi | 512Mi |
| Stream Server (每个实例) | 500m | 4000m | 1Gi | 8Gi |
| Kafka Broker | 2000m | 8000m | 4Gi | 16Gi |
| Elasticsearch Data Node | 4000m | 16000m | 8Gi | 32Gi |

## 2. 快速开始

### 2.1 本地开发 (Dev 环境

1. 启动依赖服务：

```bash
cd deploy/docker
docker-compose up -d kafka elasticsearch
```

2. 编译运行 Agent：

```bash
cargo run -p log-agent -- --config config/dev.toml --env dev
```

3. 编译运行 Server：

```bash
cargo run -p stream-processing -- --config config/dev.toml --env dev
```

4. 生成测试日志：

```bash
./scripts/generate_test_logs.sh
```

### 2.2 部署到 Staging/Production

## 3. Kafka Topic 创建

使用 Strimzi Operator 创建 Topic：

```bash
kubectl apply -f deploy/k8s/kafka-topic.yaml
```

验证 Topic 创建：

```bash
kubectl get kafkatopic -n kafka
```

## 4. Elasticsearch 索引模板

```bash
# 创建 ILM 策略
curl -X PUT "elasticsearch:9200/_ilm/policy/logs-lifecycle" \
  -H 'Content-Type: application/json' \
  -d @deploy/k8s/elasticsearch-ilm-policy.json

# 创建索引模板
curl -X PUT "elasticsearch:9200/_index_template/logs" \
  -H 'Content-Type: application/json' \
  -d @deploy/k8s/elasticsearch-index-template.json

# 创建初始索引
curl -X PUT "elasticsearch:9200/logs-production-000001" \
  -H 'Content-Type: application/json' \
  -d '{"aliases": {"logs-production": {"is_write_index": true}}}'
```

## 5. Helm 部署

### 5.1 添加 Helm 仓库配置

```bash
cd deploy/helm
```

### 5.2 部署 Log Agent (DaemonSet)

```bash
# 创建命名空间
kubectl create namespace log-pipeline

# 创建 secrets（敏感信息）
kubectl create secret generic stream-server-secrets -n log-pipeline \
  --from-literal=elasticsearch-username=elastic \
  --from-literal=elasticsearch-password=your-password \
  --from-literal=openai-api-key=sk-xxx \
  --from-literal=dingtalk-webhook=https://oapi.dingtalk.com/robot/send?access_token=xxx

# 部署 Log Agent
helm upgrade --install log-agent deploy/helm/log-agent \
  --namespace log-pipeline \
  --values deploy/helm/log-agent/values.yaml \
  --set config.env=production
```

### 5.3 部署 Stream Server (Deployment)

```bash
helm upgrade --install stream-server deploy/helm/stream-server \
  --namespace log-pipeline \
  --values deploy/helm/stream-server/values.yaml \
  --set config.env=production \
  --set replicaCount=3
```

### 5.4 验证部署

```bash
# 检查 Pod 状态
kubectl get pods -n log-pipeline

# 查看日志
kubectl logs -f -n log-pipeline -l app.kubernetes.io/name=log-agent
kubectl logs -f -n log-pipeline -l app.kubernetes.io/name=stream-server

# 检查服务
kubectl get svc -n log-pipeline

# 检查 ServiceMonitor
kubectl get servicemonitor -n monitoring
```

## 6. 监控配置

### 6.1 Prometheus 监控指标

Agent 指标（端口 9091，路径 `/metrics`）：

- `log_agent_logs_processed_total` - 已处理日志总数

- `log_agent_errors_total` - 错误总数

- `log_agent_channel_backlog` - Channel 积压数

- `log_agent_batch_size_bytes` - 批次大小

Server 指标（端口 9090，路径 `/metrics`）：

- `stream_processing_events_total` - 处理事件总数

- `stream_processing_latency_seconds` - 处理延迟直方图

- `stream_pipeline_channel_backlog` - Pipeline Channel 积压

- `stream_processing_errors_total` - 处理错误数

- `alerts_triggered_total` - 触发告警数

### 6.2 导入 Grafana Dashboard

在 Grafana 中导入 `deploy/k8s/grafana-dashboard.json

## 7. 数据分片策略

### 7.1 Kafka Partition Key

消息发送时使用 `service_name` 或 `pod_name` 作为 partition key，确保同一个日志源的所有消息路由到同一个 partition：

```rust
// 发送端（Agent）
let partitioner = Partitioner:: Murmur2Partitioner;
// partition_key = format!("{}-{}", service_name, pod_name);
```

### 7.2 Consumer Group

Stream Server 使用同一个 Consumer Group 中，每个实例消费固定的 partitions，确保同一个日志源的聚合在同一实例：

```
Partition 分配：

Partition 0-3 → Server 0
Partition 4-7 → Server 1
Partition 8-11 → Server 2
```

### 7.3 水平扩展

- 增加 replicas 数量时，Kafka Consumer Group 自动 rebalance：

```bash
kubectl scale deployment stream-server --replicas=5 -n log-pipeline
```

## 8. 配置管理

### 8.1 配置文件结构

```
config/
├── default.toml          # 默认配置
├── dev.toml           # 开发环境
├── staging.toml       # 预发布环境
└── production.toml    # 生产环境
```

### 8.2 环境变量覆盖

优先级：环境变量 > 环境配置文件 > 默认配置

环境变量前缀：`PIPELINE_`

示例：

```bash
# 覆盖 Kafka brokers
export PIPELINE_SERVER_KAFKA_BROKERS='["kafka-0:9092","kafka-1:9092"]'

# 覆盖 Elasticsearch 密码
export PIPELINE_SERVER_ELASTICSEARCH_PASSWORD=your-password
```

### 8.3 敏感信息管理

所有敏感信息通过 Kubernetes Secrets 注入，不写入配置文件：

- `ELASTICSEARCH_USERNAME`
- `ELASTICSEARCH_PASSWORD`
- `OPENAI_API_KEY`
- `ALERT_DINGTALK_WEBHOOK`

## 9. CI/CD Pipeline

### 9.1 Pipeline 阶段

1. **Lint** - `cargo fmt + clippy
2. **Test** - 单元测试 + 集成测试（ES + Kafka）
3. **Build** - 交叉编译 linux/amd64 + linux/arm64
4. **Docker** - 多架构镜像构建 + 推送 ghcr.io

### 9.2 触发条件

- Push 到 main/develop 分支 → 运行完整 Pipeline
- Tag `v*` → 自动发布镜像并推送

### 9.3 缓存加速

使用 `Swatinem/rust-cache` 加速 Rust 编译缓存

## 10. 运维操作

### 10.1 查看日志

```bash
# 所有 Agent 日志
kubectl logs -f -n log-pipeline -l app.kubernetes.io/name=log-agent --tail=100

# 特定 Server 日志
kubectl logs -f -n log-pipeline stream-server-xxx
```

### 10.2 扩容缩容

```bash
# 扩容 Stream Server
kubectl scale deployment stream-server --replicas=5 -n log-pipeline
```

### 10.3 滚动更新

```bash
# 更新镜像版本
helm upgrade stream-server deploy/helm/stream-server \
  --namespace log-pipeline \
  --set image.tag=v1.0.1
```

### 10.4 常见问题排查

**问题 1：日志不消费

- 检查 Kafka Consumer Lag 过高

```bash
# 查看 Consumer Lag
kubectl exec -it kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group log-pipeline-production

# 检查是否有分区未分配
```

**问题 2**：日志重复消费

- 检查 Consumer Group offset 提交失败，检查 offset 提交是否正确，检查是否有重复的 Consumer ID 冲突

**问题 3**：Elasticsearch 索引失败

- 检查 ES 集群健康状态

- 检查索引模板是否正确应用

- 检查网络连通性

## 11. 备份与恢复

### 11.1 数据备份

- Elasticsearch 快照备份

- Kafka 消息保留 7 天

### 11.2 灾难恢复

- ES 从快照恢复

- Kafka 从 earliest offset 重置

## 附录 A. 性能调优

### A.1 Agent 调优

| 参数 | 推荐值 | 说明 |
|------|----------|------|
| batch_size | 500-1000 | 发送批次大小 |
| flush_interval_ms | 100-500 | 发送间隔 |
| channel_buffer_size | 50000 | Channel 大小 |

### A.2 Server 调优

| 参数 | 推荐值 | 说明 |
|------|----------|------|
| channel_capacity | 10000 | Channel 容量 |
| kafka.partitions | 12 | Kafka 分区数（3x 实例数 |
| elasticsearch.batch_size | 5000 | ES 批量大小 |

### A.3 Kafka 调优

| 参数 | 推荐值 | 说明 |
|------|----------|------|
| retention.ms | 604800000 | 保留 7 天 |
| compression.type | lz4 | 压缩算法 |
| segment.bytes | 1g | Segment 大小 |
