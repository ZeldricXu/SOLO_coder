# 快速调试指南

新同事快速上手手册 — 从启动服务到验证结果全流程

---

## 一、本地环境快速启动

### 1.1 环境准备

```bash
# 1. 克隆代码
git clone <repository-url>
cd DF1-18

# 2. 创建虚拟环境
python3 -m venv .venv
source .venv/bin/activate

# 3. 安装依赖
make install-dev
# 或: pip install -e ".[dev]"

# 4. 复制环境变量模板
cp .env.example .env
# 编辑 .env 文件，根据需要修改配置
```

### 1.2 启动基础设施（Docker）

```bash
# 启动 PostgreSQL、Redis、Kafka、Zookeeper
make up
# 或: docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
make logs
# 或: docker-compose logs -f
```

**预期输出**：
```
NAME         STATUS    PORTS
rec-postgres  Up      0.0.0.0:5432->5432/tcp
rec-redis     Up      0.0.0.0:6379->6379/tcp
rec-zookeeper Up      0.0.0.0:2181->2181/tcp
rec-kafka     Up      0.0.0.0:9092->9092/tcp
```

### 1.3 初始化数据库表

```python
# 执行初始化脚本
python -c "
import asyncio
from recommendation_engine.infrastructure.postgres_client import PostgresClient

async def init():
    client = PostgresClient()
    await client.initialize()
    await client.init_tables()
    print('Database tables initialized')
    await client.close()

asyncio.run(init())
"
```

### 1.4 启动推荐服务

```bash
# 开发模式（自动热重载）
make run-dev
# 或: uvicorn recommendation_engine.main:app --reload --host 0.0.0.0 --port 8000
```

**验证服务启动**：
```bash
# 健康检查
curl http://localhost:8000/health
# 预期: {"status":"healthy"}

# OpenAPI 文档
open http://localhost:8000/docs
```

---

## 二、构造测试用户的行为序列

### 2.1 准备测试内容

先在数据库中插入一些测试内容：

```python
import asyncio
import json
import numpy as np
from recommendation_engine.infrastructure.postgres_client import PostgresClient

async def insert_test_content():
    client = PostgresClient()
    await client.initialize()

    categories = ["tech", "sports", "finance", "entertainment", "health"]

    for i in range(50):
        content_id = f"content_{i:03d}"
        category = categories[i % 5]
        embedding = np.random.randn(768).tolist()

        await client.insert("content_items", {
            "content_id": content_id,
            "title": f"测试内容 {i} - {category}",
            "content_type": "article",
            "categories": [category],
            "tags": [category, f"tag_{i}"],
            "author": "test_author",
            "popularity_score": float(50 + i),
            "embedding": json.dumps(embedding),
            "publish_time": "2024-01-01T00:00:00+00:00",
        })

    print(f"Inserted 50 test content items")
    await client.close()

asyncio.run(insert_test_content())
```

### 2.2 构造用户行为事件

```python
import asyncio
from datetime import datetime, timezone, timedelta
from recommendation_engine.user_profile_service import UserProfileService
from recommendation_engine.infrastructure.redis_client import RedisClient
from recommendation_engine.infrastructure.postgres_client import PostgresClient
from recommendation_engine.models.schemas import UserBehaviorEvent

async def build_test_user_profile():
    redis = RedisClient()
    pg = PostgresClient()
    await redis.initialize()
    await pg.initialize()

    service = UserProfileService()
    await service.initialize(redis, pg)

    user_id = "test_user_001"
    now = datetime.now(timezone.utc)

    # 构造行为序列：模拟用户对科技类内容的偏好
    events = [
        # 最近1天：科技类强兴趣
        UserBehaviorEvent(
            user_id=user_id,
            event_type="click",
            content_id="content_000",  # tech
            categories=["tech"],
            tags=["tech", "tag_0"],
            timestamp=now - timedelta(hours=1),
        ),
        UserBehaviorEvent(
            user_id=user_id,
            event_type="stay",
            content_id="content_000",
            categories=["tech"],
            duration_seconds=45.0,
            timestamp=now - timedelta(hours=1),
        ),
        UserBehaviorEvent(
            user_id=user_id,
            event_type="click",
            content_id="content_005",  # tech
            categories=["tech"],
            timestamp=now - timedelta(hours=3),
        ),
        UserBehaviorEvent(
            user_id=user_id,
            event_type="collect",
            content_id="content_010",  # tech
            categories=["tech"],
            timestamp=now - timedelta(hours=6),
        ),

        # 最近3天：偶尔看体育
        UserBehaviorEvent(
            user_id=user_id,
            event_type="click",
            content_id="content_001",  # sports
            categories=["sports"],
            timestamp=now - timedelta(days=1),
        ),
        UserBehaviorEvent(
            user_id=user_id,
            event_type="purchase",
            content_id="content_001",
            categories=["sports"],
            timestamp=now - timedelta(days=1),
        ),

        # 最近7天：偶尔看财经
        UserBehaviorEvent(
            user_id=user_id,
            event_type="click",
            content_id="content_002",  # finance
            categories=["finance"],
            timestamp=now - timedelta(days=3),
        ),
    ]

    for event in events:
        await service.ingest_behavior_event(event)
        print(f"Ingested: {event.event_type} -> {event.content_id}")

    # 等待处理完成
    await asyncio.sleep(1)

    # 获取并打印用户画像
    profile = await service.get_user_profile(user_id)
    print(f"\n=== 用户画像: {user_id} ===")
    print(f"版本: {profile.profile_version}")
    print(f"兴趣标签 Top-5:")
    for tag in profile.interest_tags[:5]:
        print(f"  {tag.tag_name}: {tag.weight:.3f}")

    await redis.close()
    await pg.close()

asyncio.run(build_test_user_profile())
```

### 2.3 验证画像生成

执行后预期输出：
```
=== 用户画像: test_user_001 ===
版本: 1
兴趣标签 Top-5:
  tech: 1.000
  sports: 0.650
  finance: 0.320
  tag_0: 0.280
  tag_1: 0.210
```

**说明**：tech 权重最高符合预期，因为最近的点击/停留/收藏都是科技类内容，且时间衰减因子让近期行为权重更大。

---

## 三、验证推荐结果是否符合预期

### 3.1 发起推荐请求

```bash
# 方式1: curl
curl -X POST http://localhost:8000/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "test_user_001",
    "top_n": 10,
    "context": {"scene": "home"}
  }' | jq .

# 方式2: Python 脚本
import requests

response = requests.post(
    "http://localhost:8000/api/v1/recommend",
    json={
        "user_id": "test_user_001",
        "top_n": 10,
        "context": {"scene": "home"}
    }
)

result = response.json()
print(f"推荐结果数: {len(result['results'])}")
print(f"实验信息: {result.get('experiment_info', {})}")
for i, item in enumerate(result['results'], 1):
    print(f"{i:2d}. {item['content_id']} (score: {item['final_score']:.4f})")
```

### 3.2 验证推荐结果合理性

**预期观察**：

| 验证点 | 检查方法 | 预期结果 |
|-------|---------|---------|
| 数量正确 | `len(results) == top_n` | ✅ 10 条 |
| 无重复 | `len(set(ids)) == len(ids)` | ✅ 无重复 |
| 排序正确 | score 降序排列 | ✅ |
| 科技偏好 | 科技类内容占比高 | ✅ 约 40-50% |
| 多样性 | 不只一个品类 | ✅ 至少 3 个品类 |
| 实验分组 | 返回 experiment_info | ✅ 有分组信息 |

### 3.3 通过日志验证调用链

```bash
# 查看推荐请求日志
tail -f logs/app.log | grep "test_user_001"

# 过滤关键日志
tail -f logs/app.log | grep -E "(推荐请求|召回完成|粗排完成|重排完成|AB实验分组)"
```

**正常日志示例**：
```
INFO  - 推荐请求开始: user_id=test_user_001, top_n=10
DEBUG - AB实验分组: user_id=test_user_001, layer=recall_layer, group=control
DEBUG - 召回完成: als=50, similar=50, 去重后=85
DEBUG - 粗排完成: 85 → 50
DEBUG - 重排完成: MMR选择 10, 业务规则调整 2
INFO  - 推荐请求完成: 返回 10 条, 耗时=125ms
```

---

## 四、常见报错及原因对照表

### 4.1 基础设施相关

| 错误信息 | 原因 | 解决方法 |
|---------|------|---------|
| `Connection refused: localhost:6379` | Redis 未启动 | `docker-compose up -d redis` |
| `Connection refused: localhost:5432` | PostgreSQL 未启动 | `docker-compose up -d postgres` |
| `KafkaTimeoutError` | Kafka 未就绪 | 等待 30 秒后重试，或检查 Zookeeper |
| `NoBrokersAvailable` | Kafka 连接地址错误 | 检查 `KAFKA_BOOTSTRAP_SERVERS` 环境变量 |
| `pg_query() could not convert` | pgvector 扩展未安装 | 在 PostgreSQL 中执行 `CREATE EXTENSION vector;` |

### 4.2 配置相关

| 错误信息 | 原因 | 解决方法 |
|---------|------|---------|
| `ModuleNotFoundError: No module named 'xxx'` | 依赖未安装 | `pip install -e ".[dev]"` |
| `Environment variable not found` | 缺少环境变量 | 复制 `.env.example` 为 `.env` |
| `pydantic.ValidationError` | 入参格式错误 | 检查 API 文档，修正请求参数 |
| `AttributeError: 'NoneType' has no attribute` | 服务未初始化 | 检查 `initialize()` 是否已调用 |

### 4.3 推荐结果异常

| 现象 | 可能原因 | 排查方法 |
|-----|---------|---------|
| 返回结果为空 | 用户画像为空 | 检查行为事件是否正确摄入 |
| 结果全是冷启动物品 | ALS 模型未加载 | 检查模型文件路径是否正确 |
| 推荐品类太单一 | MMR 关闭或 λ=1 | 检查 experiment_config 中的 mmr_lambda |
| 置顶规则不生效 | 规则未热加载 | 调用 `force_reload()` 或等待刷新 |
| A/B 分流不一致 | mmh3 哈希种子不同 | 检查各 layer 的 hash_seed |

### 4.4 性能问题

| 现象 | 瓶颈 | 优化建议 |
|-----|------|---------|
| P99 > 500ms | 召回层慢 | 检查 FAISS nprobe 参数、预热索引 |
| 特征构造慢 | 多次 DB 查询 | 批量获取、增加 Redis 缓存 |
| MMR 耗时高 | top_k 太大 | 减小 rerank_top_k |

---

## 五、常用调试工具

### 5.1 查看用户画像

```python
import asyncio
from recommendation_engine.user_profile_service import UserProfileService

async def debug_user_profile(user_id):
    service = UserProfileService()
    await service.initialize(redis_client, postgres_client)

    profile = await service.get_user_profile(user_id)
    stats = await service.get_user_statistics(user_id)
    versions = await service.list_profile_versions(user_id)

    print(f"用户: {user_id}")
    print(f"当前版本: {profile.profile_version}")
    print(f"历史版本数: {len(versions)}")
    print(f"点击率: {stats['ctr_30d']:.2%}")
    print(f"转化率: {stats['conversion_rate']:.2%}")
```

### 5.2 查看 A/B 实验配置

```python
import asyncio
from recommendation_engine.ab_test_router import ABTestRouter

async def debug_abtest(user_id):
    router = ABTestRouter()
    await router.initialize(redis_client, postgres_client)

    for layer in ["recall_layer", "rank_layer", "rerank_layer"]:
        assignment = await router.get_user_assignment(user_id, layer)
        print(f"[{layer}] {assignment.group} (exp: {assignment.experiment_id})")

    config = await router.get_experiment_config(user_id)
    print(f"实验参数: {config}")
```

### 5.3 查看业务规则

```bash
# 查看 Redis 中的业务规则
redis-cli
> GET business_rules:home
```

---

## 六、快速自检清单

上线前执行以下检查：

- [ ] 基础设施健康检查全部通过 (`/health`)
- [ ] PostgreSQL 表结构正确 (`\d` 查看所有表)
- [ ] Redis 可连接并读写
- [ ] Kafka Topic 已创建
- [ ] ALS 模型文件存在且可加载
- [ ] 测试用户推荐返回非空结果
- [ ] 日志目录有正常输出
- [ ] `/metrics` 端点有 Prometheus 指标

---

## 七、下一步深入学习

1. **架构理解** → 阅读 [01-architecture.md](01-architecture.md)
2. **算法细节** → 阅读 [02-profile-fusion-algorithm.md](02-profile-fusion-algorithm.md)
3. **调用链路** → 阅读 [03-recommend-call-sequence.md](03-recommend-call-sequence.md)
4. **代码调试** → 在 `rank_pipeline.py:recommend()` 打断点单步调试
