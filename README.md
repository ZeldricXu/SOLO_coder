# LLMGateway 大语言模型推理网关

一个为开发者打造的大语言模型推理网关，整合了模型接入、特征存储、Prompt实验、GPU调度等核心能力。

## 功能特性

### 核心模块

1. **对抗样本生成模块** (`adversarial/`)
   - 多种攻击策略：提示注入、越狱、角色扮演、混淆、少样本攻击
   - 模型安全性评估与报告生成

2. **特征存储服务模块** (`feature_store/`)
   - 特征注册与元数据管理
   - 在线服务与离线回溯
   - 线上线下一致性检查

3. **Prompt实验管理模块** (`prompt_experiments/`)
   - Prompt版本控制与回溯
   - AB实验配置与流量分配
   - 效果对比评估

4. **GPU任务调度模块** (`gpu_scheduler/`)
   - GPU资源细粒度分配
   - 任务优先级队列
   - 资源抢占策略

5. **模型评估看板模块** (`evaluation_dashboard/`)
   - 离线评估指标对比
   - 在线效果监控
   - 数据漂移与概念漂移检测

6. **文档解析管道模块** (`document_pipeline/`)
   - 多格式文档解析（PDF、DOCX、TXT、MD、HTML、CSV、XLSX、JSON）
   - 智能切分策略（固定大小、递归、段落、句子、Markdown）
   - 向量化流水线

7. **推理路由网关模块** (`inference_gateway/`)
   - 多模型Provider统一接入（OpenAI、Anthropic、智谱、通义等）
   - 5种负载均衡策略
   - Fallback与故障转移机制
   - 熔断器模式

8. **模型注册与版本模块** (`model_registry/`)
   - 模型元数据管理
   - 版本生命周期管理
   - Stage流转（Staging → Production → Archived）

## 技术栈

- **语言**: Python 3.10+
- **Web框架**: FastAPI + Pydantic v2
- **异步支持**: asyncio
- **日志**: Loguru
- **测试**: pytest + pytest-asyncio
- **可选依赖**:
  - `sentence-transformers`: 向量嵌入
  - `pypdf` / `python-docx`: 文档解析
  - `tiktoken`: Token计数
  - `langchain`: 文本切分
  - `numpy` / `scipy`: 统计计算

## 快速开始

### 1. 安装依赖

```bash
cd session174
pip install -r requirements.txt
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 文件，填入必要的配置
```

### 3. 启动服务

```bash
python main.py
```

服务默认运行在 `http://localhost:8080`

### 4. 访问API文档

- Swagger UI: http://localhost:8080/docs
- ReDoc: http://localhost:8080/redoc

## 项目结构

```
session174/
├── adversarial/           # 对抗样本生成模块
├── common/                # 公共基础模块
│   ├── config.py          # 配置管理
│   ├── models.py          # 数据模型基类
│   ├── schemas.py         # 通用响应Schema
│   ├── exceptions.py      # 自定义异常
│   ├── logger.py          # 日志配置
│   ├── database.py        # 数据库连接
│   └── utils.py           # 工具函数
├── document_pipeline/     # 文档解析管道模块
├── evaluation_dashboard/  # 模型评估看板模块
├── feature_store/         # 特征存储服务模块
├── gpu_scheduler/         # GPU任务调度模块
├── inference_gateway/     # 推理路由网关模块
├── model_registry/        # 模型注册与版本模块
├── prompt_experiments/    # Prompt实验管理模块
├── tests/                 # 测试用例
├── main.py                # 应用入口
├── requirements.txt       # 依赖列表
└── .env.example           # 环境变量示例
```

## 快速体验

### 1. 文档解析

```bash
curl -X POST "http://localhost:8080/api/v1/document-pipeline/parse/upload" \
  -F "file=@test.txt"
```

### 2. 模型推理

```bash
curl -X POST "http://localhost:8080/api/v1/inference/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-3.5-turbo",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 3. 注册模型

```bash
curl -X POST "http://localhost:8080/api/v1/model-registry/models" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-custom-model",
    "display_name": "我的自定义模型",
    "task_type": "text-generation"
  }'
```

## 运行测试

```bash
pytest tests/ -v
```

## 核心设计理念

1. **模块化架构**: 每个模块职责单一，可独立部署和扩展
2. **弹性伸缩**: 关键模块支持水平扩展
3. **容错设计**: 内置重试、熔断、降级机制
4. **可观测性**: 完善的日志、指标、追踪体系
5. **线上线下一致**: 特征存储模块保障训练与推理数据一致性

## License

MIT
