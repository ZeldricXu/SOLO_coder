# 项目依赖说明

本文档列出了 DF1-96 分布式计算实验平台的所有依赖及其用途。

## 直接依赖 (Direct Dependencies)

| 包名 | 版本 | 用途 |
|------|------|------|
| `google.golang.org/grpc` | v1.59.0 | gRPC 框架，用于高性能 RPC 通信 |
| `google.golang.org/protobuf` | v1.34.1 | Protocol Buffers 序列化库 |
| `github.com/spf13/viper` | v1.18.2 | 配置管理，支持 YAML、JSON、环境变量等 |
| `github.com/bwmarrin/snowflake` | v0.3.0 | Snowflake 分布式 ID 生成器 |
| `go.uber.org/zap` | v1.27.0 | 高性能结构化日志库 |
| `gopkg.in/natefinch/lumberjack.v2` | v2.2.1 | 日志滚动切割，支持按大小、时间切割 |
| `gorm.io/gorm` | v1.25.10 | ORM 框架，简化数据库操作 |
| `gorm.io/driver/postgres` | v1.5.9 | PostgreSQL 数据库驱动 |
| `gonum.org/v1/gonum` | v0.17.0 | 科学计算库，提供矩阵运算、优化算法等 |
| `github.com/shirou/gopsutil/v3` | v3.24.5 | 系统监控库，采集 CPU、内存、磁盘等信息 |
| `github.com/gin-gonic/gin` | v1.10.0 | Web 框架，提供 HTTP API 服务 |
| `github.com/prometheus/client_golang` | v1.19.0 | Prometheus 客户端，暴露监控指标 |
| `github.com/xitongsys/parquet-go` | v1.6.2 | Parquet 格式数据读写 |
| `github.com/cespare/xxhash/v2` | v2.3.0 | 快速哈希算法，用于参数去重、校验和 |
| `github.com/gin-contrib/cors` | v1.7.2 | Gin CORS 中间件，处理跨域请求 |
| `golang.org/x/time` | v0.5.0 | 时间和速率限制工具 |
| `github.com/xitongsys/parquet-go-source` | v0.0.0-20241021075129-b732d2ac9c9b | Parquet 文件系统适配器 |

## 间接依赖 (Indirect Dependencies)

### 网络和序列化

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/golang/protobuf` | v1.5.3 | Protobuf Go 语言绑定 |
| `golang.org/x/net` | v0.35.0 | 网络工具库 |
| `google.golang.org/genproto/googleapis/rpc` | v0.0.0-20231120223509-83a465c0220f | Google RPC 生成的 protobuf 代码 |
| `github.com/apache/thrift` | v0.14.2 | Thrift 序列化框架（Parquet 依赖） |
| `github.com/apache/arrow/go/arrow` | v0.0.0-20200730104253-651201b0f516 | Apache Arrow 内存格式（Parquet 依赖） |

### 数据处理

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/goccy/go-json` | v0.10.2 | 高性能 JSON 序列化 |
| `github.com/json-iterator/go` | v1.1.12 | JSON 解析器 |
| `github.com/modern-go/concurrent` | v0.0.0-20180306012644-bacd9c7ef1dd | 并发工具 |
| `github.com/modern-go/reflect2` | v1.0.2 | 反射工具 |
| `github.com/klauspost/compress` | v1.17.0 | 压缩算法（gzip、snappy 等） |
| `github.com/golang/snappy` | v0.0.3 | Snappy 压缩算法 |
| `github.com/pierrec/lz4/v4` | v4.1.8 | LZ4 压缩算法 |

### Web 和 Gin 中间件

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/gin-contrib/sse` | v0.1.0 | Server-Sent Events 中间件 |
| `github.com/go-playground/validator/v10` | v10.20.0 | 参数验证库 |
| `github.com/go-playground/universal-translator` | v0.18.1 | 国际化翻译工具 |
| `github.com/go-playground/locales` | v0.14.1 | 多语言地区数据 |
| `github.com/leodido/go-urn` | v1.4.0 | URN 解析 |
| `github.com/gabriel-vasile/mimetype` | v1.4.3 | MIME 类型检测 |
| `github.com/ugorji/go/codec` | v1.2.12 | 编解码库（MsgPack 等） |
| `github.com/bytedance/sonic` | v1.11.6 | 高性能 JSON 库 |
| `github.com/bytedance/sonic/loader` | v0.1.1 | Sonic JIT 加载器 |
| `github.com/cloudwego/base64x` | v0.1.4 | Base64 编解码 |
| `github.com/cloudwego/iasm` | v0.2.0 | 汇编工具 |
| `github.com/twitchyliquid64/golang-asm` | v0.15.1 | Go 汇编库 |
| `github.com/mattn/go-isatty` | v0.0.20 | 终端检测 |

### 配置管理

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/fsnotify/fsnotify` | v1.7.0 | 文件系统监控（配置热加载） |
| `github.com/hashicorp/hcl` | v1.0.0 | HCL 配置格式解析 |
| `github.com/magiconair/properties` | v1.8.7 | Java properties 格式解析 |
| `github.com/mitchellh/mapstructure` | v1.5.0 | map 到 struct 转换 |
| `github.com/pelletier/go-toml/v2` | v2.2.2 | TOML 格式解析 |
| `github.com/spf13/afero` | v1.11.0 | 抽象文件系统 |
| `github.com/spf13/cast` | v1.6.0 | 类型转换 |
| `github.com/spf13/pflag` | v1.0.5 | 命令行参数解析 |
| `github.com/subosito/gotenv` | v1.6.0 | .env 文件加载 |
| `gopkg.in/ini.v1` | v1.67.0 | INI 格式解析 |
| `gopkg.in/yaml.v3` | v3.0.1 | YAML 格式解析 |
| `github.com/sagikazarmark/locafero` | v0.4.0 | Viper 内部依赖 |
| `github.com/sagikazarmark/slog-shim` | v0.1.0 | Viper 内部依赖 |
| `github.com/sourcegraph/conc` | v0.3.0 | 并发原语库 |

### 数据库

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/jackc/pgpassfile` | v1.0.0 | PostgreSQL 密码文件解析 |
| `github.com/jackc/pgservicefile` | v0.0.0-20221227161230-091c0ba34f0a | PostgreSQL 服务文件解析 |
| `github.com/jackc/pgx/v5` | v5.5.5 | PostgreSQL 驱动底层 |
| `github.com/jackc/puddle/v2` | v2.2.1 | 连接池实现 |
| `github.com/jinzhu/inflection` | v1.0.0 | 名词单复数转换（GORM 表名） |
| `github.com/jinzhu/now` | v1.1.5 | 时间处理工具（GORM） |

### 监控和指标

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/beorn7/perks` | v1.0.1 | 高效数据结构（直方图、分位数） |
| `github.com/prometheus/client_model` | v0.5.0 | Prometheus 数据模型 |
| `github.com/prometheus/common` | v0.48.0 | Prometheus 公共库 |
| `github.com/prometheus/procfs` | v0.12.0 | /proc 文件系统解析 |

### 系统信息

| 包名 | 版本 | 用途 |
|------|------|------|
| `github.com/go-ole/go-ole` | v1.2.6 | Windows COM 接口（gopsutil 依赖） |
| `github.com/yusufpapurcu/wmi` | v1.2.4 | Windows WMI 接口（gopsutil 依赖） |
| `github.com/power-devops/perfstat` | v0.0.0-20210106213030-5aafc221ea8c | AIX 性能统计 |
| `github.com/lufia/plan9stats` | v0.0.0-20211012122336-39d0f177ccd0 | Plan9 统计 |
| `github.com/shoenig/go-m1cpu` | v0.1.6 | Apple M1 CPU 检测 |
| `github.com/tklauser/go-sysconf` | v0.3.12 | sysconf 系统调用 |
| `github.com/tklauser/numcpus` | v0.6.1 | CPU 核心数检测 |

### 工具和工具库

| 包名 | 版本 | 用途 |
|------|------|------|
| `go.uber.org/multierr` | v1.10.0 | 多错误组合 |
| `golang.org/x/arch` | v0.8.0 | 架构检测 |
| `golang.org/x/crypto` | v0.33.0 | 加密算法 |
| `golang.org/x/exp` | v0.0.0-20230905200255-921286631fa9 | 实验性包（泛型等） |
| `golang.org/x/sync` | v0.12.0 | 同步原语扩展 |
| `golang.org/x/sys` | v0.30.0 | 系统调用封装 |
| `golang.org/x/text` | v0.23.0 | 文本处理 |
| `golang.org/x/tools` | v0.30.0 | Go 工具库 |
| `golang.org/x/xerrors` | v0.0.0-20220907171357-04be3eba64a2 | 错误包装 |
| `github.com/klauspost/cpuid/v2` | v2.2.7 | CPU 特性检测 |

## 依赖版本管理

### Go 版本要求

- Go 1.24.0 或更高版本

### 模块路径

```
module github.com/df1-96/experiment
```

### 常用命令

```bash
# 更新依赖
go get -u ./...

# 整理依赖
go mod tidy

# 验证依赖
go mod verify

# 查看依赖图
go mod graph

# 查看为什么依赖某个包
go mod why <package>
```

## 依赖说明

### 核心框架依赖

1. **gRPC + Protobuf**：用于内部服务间的高性能通信
   - Worker 与 Scheduler 之间使用 gRPC 流式通信
   - 支持双向流、心跳检测

2. **Gin**：对外提供 RESTful API
   - 支持中间件链（CORS、日志、限流、认证）
   - 高性能路由，基于 radix tree

3. **GORM + PostgreSQL**：数据持久化
   - 支持软删除、自动迁移
   - JSONB 字段存储灵活参数

### 工具类依赖

1. **Viper**：统一配置管理
   - 支持配置文件、环境变量、命令行参数
   - 配置热加载

2. **Zap + Lumberjack**：日志系统
   - 结构化日志，高性能
   - 自动滚动切割，压缩归档

3. **Snowflake**：分布式 ID 生成
   - 无需中心化协调
   - 按时间有序

### 计算和分析依赖

1. **Gonum**：科学计算
   - 矩阵运算、线性代数
   - 优化算法（LBFGS 等）
   - 统计分析

2. **Parquet**：列式存储
   - 高效压缩，适合大数据量
   - 支持复杂嵌套结构

### 监控依赖

1. **Prometheus**：指标采集
   - 标准化指标格式
   - 支持 Histogram、Counter、Gauge

2. **gopsutil**：系统监控
   - 跨平台支持（Linux、macOS、Windows）
   - 实时资源使用采集

## 潜在问题和注意事项

### 版本兼容性

- `gonum v0.17.0` 与 Go 1.24+ 兼容，但注意部分 API 可能在未来版本变化
- `gopsutil v3.24.5` 在某些旧版 Linux 内核上可能有兼容性问题

### 依赖冲突

- `golang.org/x/` 系列包可能存在版本冲突，建议使用 `go mod tidy` 自动解决
- `github.com/klauspost/compress` 被多个包依赖，注意版本统一

### 可选优化

- 可以使用 `go mod vendor` 将依赖打包到项目中
- 对于生产环境，建议锁定依赖版本：`go get <package>@<version>`

## 安装故障排查

### 网络问题

如果某些依赖下载失败，可以手动添加到 go.mod：

```go
require (
    google.golang.org/grpc v1.59.0
    google.golang.org/protobuf v1.34.1
    github.com/spf13/viper v1.18.2
    // ... 其他依赖
)
```

然后运行：

```bash
GOPROXY=https://goproxy.cn,direct go mod download
```

或使用国内镜像：

```bash
export GOPROXY=https://goproxy.io,direct
export GOSUMDB=off
go mod tidy
```

### cgo 依赖

部分依赖（如 `gopsutil`）需要 cgo 支持，确保：

```bash
export CGO_ENABLED=1
```

如果不需要 cgo 功能，可以禁用：

```bash
export CGO_ENABLED=0
go build -tags=purego ./...
```
