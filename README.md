# Gas Estimator Platform

基于历史数据和当前网络状态预估交易Gas费用的企业级区块链平台。

## 项目概述

本项目面向系统运维人员，提供完整的区块链Gas费用预估解决方案，包括：

- 交易构造与签名
- HD钱包地址派生与管理
- 多链RPC节点适配
- 多签钱包协调
- 智能Gas费用预估
- 链上数据索引
- 资产跨链桥接
- 去中心化存储适配

## 技术栈

- **语言**: Go 1.21
- **Web框架**: Gin
- **数据库**: GORM + PostgreSQL
- **缓存**: Redis
- **日志**: Zap

## 项目结构

```
session111/
├── cmd/
│   └── main.go                 # 主程序入口
├── internal/
│   ├── tx/
│   │   └── builder.go          # 交易构造与签名模块
│   ├── address/
│   │   └── wallet.go           # HD钱包地址管理模块
│   ├── chain/
│   │   └── adapter.go          # 多链RPC适配模块
│   ├── multisig/
│   │   └── coordinator.go      # 多签钱包协调模块
│   ├── gas/
│   │   └── estimator.go        # Gas费用预估模块
│   ├── indexer/
│   │   └── indexer.go          # 链上数据索引模块
│   ├── bridge/
│   │   └── bridge.go           # 资产跨链桥接模块
│   └── storage/
│       └── storage.go          # 去中心化存储适配模块
├── pkg/
│   ├── config/
│   │   └── config.go           # 配置管理
│   └── models/
│       └── models.go           # 数据模型定义
├── go.mod
├── config.example.json          # 示例配置文件
└── README.md
```

## 快速开始

### 1. 安装依赖

```bash
cd session111
go mod tidy
```

### 2. 配置

复制示例配置文件并修改：

```bash
cp config.example.json config.json
```

修改 `config.json` 中的配置项，包括：
- 链RPC节点地址
- 多签钱包签名者地址
- 数据库连接信息
- Redis连接信息
- IPFS节点地址

### 3. 运行

```bash
go run cmd/main.go

# 或指定配置文件路径
go run cmd/main.go /path/to/config.json
```

服务默认运行在 `http://localhost:8080`

## API 接口

### 健康检查

```
GET /health
```

### Gas预估

```
POST /api/v1/gas/estimate
Content-Type: application/json

{
  "chain_id": "ethereum",
  "urgency": "medium",
  "transaction": {
    "to": "0x...",
    "value": "1000000000000000000",
    "data": "0x..."
  }
}
```

### 多签提案

```
POST /api/v1/multisig/proposals
GET  /api/v1/multisig/proposals/:id
POST /api/v1/multisig/proposals/:id/sign
POST /api/v1/multisig/proposals/:id/execute
```

### 地址管理

```
POST /api/v1/addresses/derive
GET  /api/v1/addresses/:address
GET  /api/v1/addresses
POST /api/v1/addresses/addressbook
```

### 链交互

```
GET  /api/v1/chains/current
POST /api/v1/chains/switch
GET  /api/v1/chains/blocks/latest
GET  /api/v1/chains/gas-price
```

### 数据索引

```
POST /api/v1/indexer/blocks/:chain_id/index
GET  /api/v1/indexer/blocks/:chain_id/:number
GET  /api/v1/indexer/info
```

### 跨链桥接

```
POST /api/v1/bridge/messages
POST /api/v1/bridge/messages/:id/lock
POST /api/v1/bridge/messages/:id/mint
POST /api/v1/bridge/messages/:id/execute
GET  /api/v1/bridge/messages
GET  /api/v1/bridge/stats
```

### 去中心化存储

```
POST /api/v1/storage/store
GET  /api/v1/storage/retrieve/:cid
POST /api/v1/storage/pin/:cid
POST /api/v1/storage/unpin/:cid
GET  /api/v1/storage/contents
GET  /api/v1/storage/stats
```

## 核心模块说明

### 1. 交易构造与签名模块 (internal/tx/)

- 构造链上交易数据结构
- 管理多签策略
- Gas优化
- 交易序列化与校验

### 2. 地址派生与管理模块 (internal/address/)

- 基于HD钱包标准(BIP-32/39/44)派生地址
- 地址簿管理
- 标签与分类

### 3. 链交互适配模块 (internal/chain/)

- 多链RPC节点对接
- 统一区块数据查询接口
- 交易提交接口
- 故障转移与重试

### 4. 多签钱包协调模块 (internal/multisig/)

- 多签提案创建
- 签名收集与验证
- 执行触发
- 阈值配置

### 5. Gas费用预估模块 (internal/gas/)

- 基于历史数据分析
- 当前网络状态监控
- 多紧急程度预估
- 置信度计算

### 6. 链上数据索引模块 (internal/indexer/)

- 区块原始数据解析
- 结构化索引构建
- 交易与地址索引
- 性能优化

### 7. 资产跨链桥接模块 (internal/bridge/)

- 跨链消息验证
- 资产锁定与铸造
- 原子性保障
- 消息状态跟踪

### 8. 去中心化存储适配模块 (internal/storage/)

- IPFS/Arweave对接
- 内容寻址
- Pin管理
- 数据验证

## 设计特点

1. **四层架构**: 接口接入层 → 业务处理层 → 领域模型层 → 基础设施层
2. **线程安全**: 所有核心模块使用sync.RWMutex保障并发安全
3. **容错设计**: RPC节点故障转移、指数退避重试
4. **模块化设计**: 各模块独立，可单独测试和替换
5. **企业级特性**: 配置版本化、监控指标、优雅关闭

## 风险缓解

- **数据丢失**: 采用预写日志(WAL)机制
- **并发冲突**: 乐观锁配合重试机制
- **配置漂移**: 配置版本化与Diff告警

## 许可证

本项目仅供学习和研究使用。
