# DocIntel - 多格式文档理解与信息抽取平台

面向保险理赔业务场景的端到端文档智能处理平台，支持 PDF、Word、图片、TXT 四种格式文档的自动解析、版面分析、多模态信息抽取、表格结构化、字段校验与人工审核闭环。

## 功能模块

### 1. 文档预处理与标准化
- 支持 PDF（PyMuPDF）、Word（python-docx）、图片、TXT 四种格式
- 统一转成标准化页面对象，包含文本块坐标、图片区域、表格结构
- PaddleOCR 兜底扫描件和图片的文字识别

### 2. 版面分析
- 基于 LayoutLMv3 的页面区域分割
- 识别标题、正文、表格、图片、签名区
- 输出结构化的文档树结构

### 3. 多模态信息抽取
- 结合 OCR 文字和视觉特征
- 基于预定义 Schema 抽取：姓名、金额、日期、诊断码等字段
- 置信度标注和多源融合

### 4. 表格理解与结构化
- Table-Transformer 检测表格区域
- 识别表头和单元格内容
- 支持合并单元格、跨页表格
- 输出结构化 JSON

### 5. 字段校验与纠错引擎
- 日期格式检查
- 金额合理性校验（不能为负）
- ICD-10 诊断码范围校验
- 身份证号、手机号格式校验
- 可疑字段标记和修改建议

### 6. 人工审核工作台
- 低置信度字段自动推送审核队列
- Web 界面高亮修正
- 修正结果反哺模型训练数据集

### 7. 批量处理与异步任务
- 批量上传 zip 包
- Celery 异步后台处理
- WebSocket 实时进度推送
- 优先级排序（高优、默认、批量三队列）
- 并发限流

### 8. 模型版本管理与 A/B 测试
- 多模型版本管理
- 在线 A/B 流量分流（随机、哈希、轮询）
- 业务指标自动选择最优模型

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                  FastAPI 服务层                     │
│  REST API + WebSocket + 依赖注入               │
├─────────────────────────────────────────────────────┤
│                  服务层 (Services)                 │
│  Document / Extraction / Review / Batch / Model     │
│  Validation / Storage / ABTest                    │
├─────────────────────────────────────────────────────┤
│                  任务层 (Celery)                    │
│  文档处理任务 / 批量处理任务 / 定时任务          │
├─────────────────────────────────────────────────────┤
│                  ML 推理层                         │
│  OCR / 解析器 / 版面分析 / 表格提取        │
│  多模态抽取                                  │
├─────────────────────────────────────────────────────┤
│                  数据层                             │
│  PostgreSQL  |  Redis  |  MinIO               │
│  (结果存储)    (队列/缓存)   (文件存储)          │
└─────────────────────────────────────────────────────┘
```

## 技术栈

| 层级 | 技术选型 |
|------|---------|
| Web 框架 | FastAPI |
| ML 推理 | transformers + PyTorch |
| 异步任务 | Celery |
| 数据库 | PostgreSQL + SQLAlchemy |
| 缓存/队列 | Redis |
| 文件存储 | MinIO |
| 文档解析 | PyMuPDF, python-docx |
| OCR | PaddleOCR |
| 版面分析 | LayoutLMv3 |
| 表格识别 | Table-Transformer |

## 项目结构

```
DF1-73/
├── app/
│   ├── __init__.py
│   ├── main.py                    # FastAPI 主应用
│   ├── api/
│   │   ├── __init__.py
│   │   └── v1/
│   │       ├── __init__.py
│   │       ├── documents.py       # 文档 API
│   │       ├── extractions.py   # 抽取结果 API
│   │       ├── review.py        # 审核 API
│   │       ├── batches.py       # 批量处理 API
│   │       ├── models.py         # 模型管理 API
│   │       └── ab_test.py      # A/B 测试 API
│   ├── core/
│   │   ├── __init__.py
│   │   ├── config.py            # 配置管理
│   │   ├── database.py        # 数据库连接
│   │   └── logging_config.py  # 日志配置
│   ├── ml/
│   │   ├── __init__.py
│   │   ├── ocr_engine.py      # OCR 引擎（PaddleOCR）
│   │   ├── parsers.py         # 文档解析器（4种格式）
│   │   ├── preprocessing.py   # 文档预处理
│   │   ├── layout_analyzer.py # 版面分析
│   │   ├── table_extractor.py # 表格提取
│   │   └── extractor.py       # 多模态信息抽取
│   ├── models/
│   │   ├── __init__.py
│   │   ├── base.py            # 基础模型
│   │   ├── document.py        # 文档模型
│   │   ├── extraction.py      # 抽取结果模型
│   │   ├── review.py          # 审核模型
│   │   ├── model.py           # 模型版本模型
│   │   ├── batch.py           # 批量任务模型
│   │   └── table.py           # 表格模型
│   ├── schemas/
│   │   ├── __init__.py
│   │   ├── common.py          # 通用 Schema
│   │   ├── document.py        # 文档 Schema
│   │   ├── extraction.py      # 抽取 Schema
│   │   ├── review.py          # 审核 Schema
│   │   ├── batch.py           # 批量 Schema
│   │   └── model.py           # 模型 Schema
│   ├── services/
│   │   ├── __init__.py
│   │   ├── storage.py         # 存储服务
│   │   ├── validation_service.py # 校验服务
│   │   ├── review_service.py  # 审核服务
│   │   ├── document_service.py # 文档服务
│   │   ├── extraction_service.py # 抽取服务
│   │   ├── batch_service.py  # 批量服务
│   │   ├── model_service.py  # 模型服务
│   │   └── ab_test_service.py # A/B测试服务
│   ├── tasks/
│   │   ├── __init__.py
│   │   ├── celery_app.py      # Celery 应用
│   │   ├── document.py        # 文档任务
│   │   └── batch.py           # 批量任务
│   └── utils/
│       ├── __init__.py
│       └── websocket_manager.py # WebSocket 管理器
├── scripts/
│   └── init_db.py           # 数据库初始化
├── tests/                       # 测试目录
├── data/                        # 数据目录
├── requirements.txt             # Python 依赖
├── .env.example             # 环境变量示例
├── run.sh                       # Unix 启动脚本
├── run.bat                      # Windows 启动脚本
└── README.md
```

## 快速开始

### 1. 环境准备

#### 依赖服务

- **PostgreSQL 14+
- **Redis 6+
- **MinIO**（或其他 S3 兼容存储）

#### Python 环境

```bash
# 创建虚拟环境
python -m venv venv

# 激活虚拟环境
# Unix: source venv/bin/activate
# Windows: venv\Scripts\activate.bat

# 安装依赖
pip install -r requirements.txt
```

### 2. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件，配置数据库、Redis、MinIO 等连接信息。

### 3. 初始化数据库

```bash
# Unix
./run.sh init-db

# Windows
run.bat init-db

# 或直接执行
python scripts/init_db.py
```

### 4. 启动服务

#### 方式一：单命令启动（开发环境）

```bash
# 启动 API 服务（带自动重载
./run.sh api
```

#### 方式二：完整服务启动

```bash
# 终端 1：启动 FastAPI 服务
./run.sh api

# 终端 2：启动 Celery Worker
./run.sh worker

# 终端 3：启动 Celery Beat（可选，用于定时任务）
./run.sh beat

# 终端 4：启动 Flower（可选，任务监控）
./run.sh flower
```

#### 方式三：一键启动所有服务

```bash
./run.sh all
```

### 5. 访问服务

- API 文档：http://localhost:8000/docs
- ReDoc 文档：http://localhost:8000/redoc
- 健康检查：http://localhost:8000/health
- Flower 监控：http://localhost:5555

## API 端点

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/documents/upload` | 上传文档 |
| GET | `/api/v1/documents/{id}` | 获取文档信息 |
| POST | `/api/v1/documents/{id}/process` | 处理文档 |
| GET | `/api/v1/documents/{id}/status` | 获取处理状态 |
| POST | `/api/v1/documents/{id}/reprocess` | 重新处理 |

### 抽取结果

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/extractions/` | 抽取结果列表 |
| GET | `/api/v1/extractions/{id}` | 获取抽取详情 |
| GET | `/api/v1/extractions/compare/{a}/{b}` | 对比抽取结果 |
| GET | `/api/v1/extractions/statistics` | 抽取统计 |

### 审核工作台

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/review/queue` | 获取审核队列 |
| POST | `/api/v1/review/tasks/{id}/complete` | 完成审核 |
| POST | `/api/v1/review/export-training-data` | 导出训练数据 |

### 批量处理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/batches/upload` | 上传批量 zip |
| POST | `/api/v1/batches/{id}/process` | 启动批量处理 |
| GET | `/api/v1/batches/{id}/progress` | 获取批量进度 |
| POST | `/api/v1/batches/{id}/cancel` | 取消批量任务 |

### 模型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/models/` | 注册模型版本 |
| GET | `/api/v1/models/{id}/statistics` | 获取模型统计 |
| POST | `/api/v1/models/compare` | 对比模型 |

### A/B 测试

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/ab-test/experiments` | 创建实验 |
| GET | `/api/v1/ab-test/experiments` | 实验列表 |
| GET | `/api/v1/ab-test/experiments/{id}` | 实验详情 |
| POST | `/api/v1/ab-test/experiments/{id}/start` | 启动实验 |
| POST | `/api/v1/ab-test/experiments/{id}/stop` | 停止实验 |
| GET | `/api/v1/ab-test/experiments/{id}/results` | 实验结果 |
| POST | `/api/v1/ab-test/route/{model_name}` | 流量路由 |

### WebSocket

| 路径 | 说明 |
|------|------|
| `/ws` | 通用 WebSocket 连接 |
| `/ws/batch/{batch_id}` | 订阅批量进度 |
| `/ws/document/{document_id}` | 订阅文档进度 |

## 核心设计模式

### 1. 工厂模式 - ParserFactory

根据文档类型（PDF/Word/Image/TXT）创建对应的解析器，统一接口。

### 2. 策略模式 - ValidationRule

可插拔的字段校验规则，支持自定义规则扩展。

### 3. 单例模式

ML 模型和服务类使用懒汉式单例，避免重复加载。

### 4. 依赖注入

FastAPI Depends 实现服务层解耦。

## 字段抽取 Schema

预定义的保险理赔字段：

```json
{
  "patient_name": {"type": "string", "description": "患者姓名"},
  "patient_id": {"type": "string", "description": "患者身份证号"},
  "diagnosis_code": {"type": "string", "description": "ICD-10 诊断编码"},
  "diagnosis_description": {"type": "string", "description": "诊断描述"},
  "total_amount": {"type": "number", "description": "总费用金额"},
  "invoice_date": {"type": "string", "description": "发票日期"},
  "admission_date": {"type": "string", "description": "入院日期"},
  "discharge_date": {"type": "string", "description": "出院日期"},
  "hospital_name": {"type": "string", "description": "医院名称"},
  "department": {"type": "string", "description": "科室"},
  "doctor_name": {"type": "string", "description": "医生姓名"}
}
```

## 校验规则

内置 6 大校验规则：

1. **DateFormatRule** - 日期格式校验（YYYY-MM-DD, YYYY/MM/DD 等）
2. **AmountRule** - 金额校验（>= 0）
3. **ICD10CodeRule** - ICD-10 编码格式校验
4. **IDCardRule** - 身份证号格式校验
5. **PhoneNumberRule** - 手机号格式校验
6. **RequiredFieldRule** - 必填字段校验

## 任务队列架构

```
                                ┌──────────────────┐
                                │   Redis Broker  │
                                └────────┬─────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
          ┌───────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
          │ high_priority │    │   default   │    │    batch    │
          │  (高优队列)  │    │ (默认队列) │    │  (批量队列) │
          └───────┬──────┘    └──────┬──────┘    └──────┬──────┘
                  │                    │                    │
          ┌───────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
          │  Worker 1-2 │    │ Worker 1-2 │    │ Worker 1-2 │
          └──────────────┘    └──────────────┘    └──────────────┘
```

## A/B 测试流量分流策略

1. **RANDOM（随机分流）：按配置比例随机分配

2. **HASH（哈希分流）：按 document_id 哈希，确保同一文档始终路由到同一 variant

3. **ROUND_ROBIN（轮询分流）：均匀分配请求

## 部署建议

### 开发环境

- 单节点部署，所有服务运行在同一机器

### 生产环境

- API 服务：多节点 + 负载均衡
- Celery Worker：按队列分组部署
- 高优队列：独立节点，低延迟
- 批量队列：弹性伸缩，高吞吐
- PostgreSQL：主从复制
- Redis：集群模式
- MinIO：分布式集群

## 监控指标

### 业务指标

- 文档处理吞吐量（documents_per_second）
- 字段抽取准确率（extraction_accuracy）
- 字段抽取召回率（extraction_recall）
- 字段平均置信度（avg_confidence）
- 审核通过率（review_pass_rate）
- 人工干预率（human_intervention_rate）

### 系统指标

- API 响应时间
- 任务队列长度
- 任务处理延迟
- 模型推理时间
- OCR 识别率

## License

MIT License
