# DIDAuth 能力增强升级文档

## 升级概述

本次升级为 DIDAuth 去中心化身份验证服务带来了三个核心模块的能力增强，所有增强均采用渐进式设计，通过 `/api/v2/` 前缀提供新接口，完全兼容原有 `/api/v1/` 接口，不影响现有功能。

---

## 🔧 增强模块总览

| 模块 | 增强内容 | API 前缀 | 关键特性 |
|------|----------|----------|----------|
| **零知识证明验证** | L1/L2 多级缓存、缓存预热、失效策略 | `/api/v2/zkp` | 自动缓存、命中率统计、手动预热 |
| **地址派生与管理** | 批量操作接口、请求合并处理 | `/api/v2/hdwallet` | 批量派生、批量CRUD、并发处理 |
| **链上数据索引** | 关键路径耗时统计、Prometheus 指标集成 | `/api/v2/indexer` | 细粒度计时、实时状态、指标暴露 |

---

## 📦 新增文件清单

### 通用基础设施

```
src/main/java/com/didauth/common/
├── cache/
│   ├── MultiLevelCache.java       # 通用多级缓存抽象（L1 Caffeine + L2 Redis）
│   └── CacheProperties.java       # 缓存配置属性类
├── config/
│   └── EnhancedAppConfig.java     # 增强模块配置类
└── monitor/
    └── MetricsExporter.java       # 通用指标导出器
```

### 零知识证明验证模块增强

```
src/main/java/com/didauth/module/zkp/enhanced/
├── EnhancedZkpService.java        # 增强版 ZKP 服务（含多级缓存）
└── EnhancedZkpController.java     # v2 API 控制器
```

### 地址派生与管理模块增强

```
src/main/java/com/didauth/module/hdwallet/enhanced/
├── BatchDeriveRequest.java        # 批量派生请求 DTO
├── BatchAddressBookRequest.java   # 批量地址簿请求 DTO
├── EnhancedHdWalletService.java   # 增强版 HD 钱包服务（含批量操作）
└── EnhancedHdWalletController.java # v2 API 控制器
```

### 链上数据索引模块增强

```
src/main/java/com/didauth/module/indexer/enhanced/
├── IndexerMetrics.java            # 索引器统计数据模型
├── IndexerMetricsCollector.java   # Prometheus 指标收集器
├── EnhancedBlockIndexerService.java # 增强版索引服务（含指标统计）
└── EnhancedBlockIndexerController.java # v2 API 控制器
```

---

## 🚀 功能详解

### 1. 零知识证明验证模块 - 多级缓存

#### 核心特性

- **L1 本地缓存**：基于 Caffeine 高性能内存缓存
- **L2 分布式缓存**：基于 Redis 实现跨实例缓存共享
- **缓存预热**：支持提前加载高频证明结果到缓存
- **智能失效**：支持手动失效、自动过期、按条件清除
- **命中率统计**：实时统计缓存命中/未命中率

#### 新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/zkp/verify` | 验证 ZKP 证明（自动缓存结果） |
| GET | `/api/v2/zkp/proofs/{proofId}` | 获取证明状态（缓存5分钟） |
| POST | `/api/v2/zkp/cache/warmup` | 批量预热缓存 |
| POST | `/api/v2/zkp/cache/invalidate` | 使指定证明缓存失效 |
| POST | `/api/v2/zkp/cache/invalidate/all` | 清空所有缓存 |
| GET | `/api/v2/zkp/cache/metrics` | 获取缓存统计指标 |

#### 配置示例

```yaml
didauth:
  cache:
    configs:
      zkp-proofs:
        ttl: 10m        # 缓存过期时间
        max-size: 10000  # L1最大缓存条目
        cache-nulls: false
        warm-up: false
```

---

### 2. 地址派生与管理模块 - 批量操作

#### 核心特性

- **批量地址派生**：一次性派生多个 HD 钱包地址
- **并发处理**：支持配置并发度，充分利用 CPU
- **批量地址簿**：批量添加/删除地址簿条目
- **错误隔离**：单个条目失败不影响整体批次
- **详细统计**：返回成功/失败/重复的详细信息

#### 新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/hdwallet/batch/derive` | 批量派生地址（最大1000） |
| POST | `/api/v2/hdwallet/batch/addressbook` | 批量添加地址簿（最大500） |
| DELETE | `/api/v2/hdwallet/batch/addressbook` | 批量删除地址簿（最大500） |
| POST | `/api/v2/hdwallet/batch/wallets` | 批量获取钱包信息（最大100） |
| GET | `/api/v2/hdwallet/batch/metrics` | 获取批量操作配置 |

#### 请求示例 - 批量派生

```json
{
  "chainType": "ETH",
  "startIndex": 0,
  "count": 100,
  "labelPrefix": "UserWallet",
  "tags": ["user", "generated"],
  "userId": "user_001",
  "batchSize": 20
}
```

#### 响应示例 - 批量地址簿

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 100,
    "success": 95,
    "successIds": ["addr_001", "addr_002", "..."],
    "duplicates": ["0x123...", "0x456..."],
    "errors": [
      {"index": 25, "address": "0x789...", "error": "Invalid address format"}
    ],
    "durationMs": 1250
  }
}
```

---

### 3. 链上数据索引模块 - 监控增强

#### 核心特性

- **关键路径计时**：区块解析、数据库写入、交易索引全链路计时
- **Prometheus 集成**：所有指标自动暴露到 `/actuator/prometheus`
- **百分位统计**：P50/P75/P95/P99 延迟统计
- **实时状态**：索引器状态、处理速率、平均耗时实时可查
- **分链指标**：支持按不同链独立统计

#### 新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/indexer/blocks` | 解析并索引区块（带计时统计） |
| GET | `/api/v2/indexer/blocks/{chain}/{number}` | 获取区块信息 |
| GET | `/api/v2/indexer/status/{chain}` | 获取指定链索引状态 |
| GET | `/api/v2/indexer/status` | 获取所有链索引状态 |
| GET | `/api/v2/indexer/metrics` | 获取完整统计数据 |

#### Prometheus 指标清单

| 指标名称 | 类型 | 说明 |
|----------|------|------|
| `indexer_blocks_indexed_total` | Counter | 已索引区块总数 |
| `indexer_transactions_indexed_total` | Counter | 已索引交易总数 |
| `indexer_errors_total` | Counter | 索引错误总数 |
| `indexer_blocks_skipped_total` | Counter | 跳过（已索引）区块数 |
| `indexer_block_indexing_duration_seconds` | Timer | 区块索引耗时（含百分位） |
| `indexer_transaction_indexing_duration_seconds` | Timer | 交易索引耗时（含百分位） |
| `indexer_block_parse_duration_seconds` | Timer | 区块解析耗时 |
| `indexer_db_insert_duration_seconds` | Timer | 数据库写入耗时 |
| `indexer_block_latest_number` | Gauge | 最新已索引区块号 |
| `indexer_block_last_duration_ms` | Gauge | 上次索引耗时（毫秒） |

#### Grafana 面板推荐

1. **区块处理速率**：`rate(indexer_blocks_indexed_total[5m])`
2. **交易处理速率**：`rate(indexer_transactions_indexed_total[5m])`
3. **P95 索引延迟**：`indexer_block_indexing_duration_seconds{quantile="0.95"}`
4. **最新区块高度**：`indexer_block_latest_number`
5. **错误率**：`rate(indexer_errors_total[5m])`

---

## 🔄 兼容性说明

### 向后兼容

- 所有 `/api/v1/` 接口保持不变，现有代码无需修改
- 新增增强功能全部通过 `/api/v2/` 接口提供
- 原有服务类 (`ZkpService`, `HdWalletService`, `BlockIndexerService`) 未做任何修改
- 数据库 schema 保持兼容，无需迁移

### 渐进式采用

1. **初始阶段**：保持使用 v1 API，可并行部署 v2 接口
2. **验证阶段**：对关键业务场景逐步切换到 v2 API
3. **全量阶段**：确认稳定后全面切换到 v2 API

---

## 📊 性能提升预期

| 模块 | 指标 | 优化前 | 优化后 | 提升 |
|------|------|--------|--------|------|
| ZKP 验证 | 重复证明验证延迟 | 50-150ms | <1ms (缓存命中) | 50-150x |
| ZKP 验证 | 缓存命中率 | - | 70-95% | - |
| HD 钱包 | 派生100个地址 | ~100次网络往返 | 1次请求 | 100x |
| 地址簿 | 添加100个条目 | ~100次网络往返 | 1次请求 | 100x |
| 索引监控 | 问题定位时间 | 数小时 | 实时可见 | - |

---

## 🛠️ 运维指南

### 启用/禁用缓存

```yaml
didauth:
  cache:
    enabled: true     # 总开关
    l1-enabled: true  # L1本地缓存
    l2-enabled: true  # L2 Redis缓存
```

### 批量大小限制调整

```yaml
didauth:
  batch:
    hd-wallet:
      max-derive-size: 1000      # 单次最大派生数量
      max-addressbook-size: 500  # 单次最大地址簿操作数量
      default-concurrency: 10    # 默认并发度
```

### 监控指标采集

确保 Prometheus 配置中包含以下抓取目标：

```yaml
scrape_configs:
  - job_name: 'didauth-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

---

## 🧪 测试建议

1. **缓存一致性测试**：验证修改数据后缓存是否正确失效
2. **并发压力测试**：测试批量接口在高并发下的表现
3. **资源监控测试**：确认索引器指标正确暴露
4. **故障恢复测试**：测试 Redis 宕机时 L1 缓存降级运行

---

## 📚 相关文档

- [API 文档 (v2)](/swagger-ui.html)
- [Prometheus 指标说明](/actuator/prometheus)
- [健康检查](/actuator/health)
- [应用信息](/actuator/info)
