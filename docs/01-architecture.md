# 推荐引擎模块架构文档

## 一、模块总览

推荐引擎由 7 个核心模块组成，采用分层架构设计：

```
  ┌─────────────────────────────────────────────────────────────┐
  │                    model-serving-gateway                     │
  │  (FastAPI 网关层 - HTTP 入口)                                │
  └──────────────────────────┬──────────────────────────────────┘
                             │
  ┌──────────────────────────▼──────────────────────────────────┐
  │                    ab-test-router                           │
  │  (A/B 测试分流 - 按用户哈希分流到对照组)                      │
  └──────────────────────────┬──────────────────────────────────┘
                             │
  ┌──────────────────────────▼──────────────────────────────────┐
  │                realtime-rank-pipeline                       │
  │  (实时排序管道 - 三阶段: 召回 → 粗排 → 重排)                  │
  └─────────┬──────────────────┬──────────────────┬─────────────┘
            │                  │                  │
  ┌─────────▼────────┐ ┌──────▼─────────┐ ┌──────▼────────────┐
  │ user-profile-    │ │ content-       │ │ collaborative-    │
  │ service          │ │ embedding-     │ │ filter           │
  │ (用户画像服务)    │ │ index          │ │ (协同过滤召回)   │
  └─────────┬────────┘ └────────────────┘ └────────┬───────────┘
            │                                         │
  ┌─────────▼─────────────────────────────────────────▼──────────┐
  │                    feedback-collector                        │
  │  (反馈收集 - Kafka 消费行为事件 → 画像更新 + 在线学习)        │
  └──────────────────────────────────────────────────────────────┘
```

---

## 二、推荐请求数据流

### 完整链路：一个推荐请求的旅程

```
HTTP 请求
    ↓
[1] model-serving-gateway  (FastAPI)
    ├── 解析 RecommendRequest 参数
    ├── 调用 ab-test-router 获取实验分组
    └── 调用 realtime-rank-pipeline.recommend()
        ↓
[2] ab-test-router
    ├── 检查 exclusion_policy（排除内部员工/测试账号）
    ├── 按 user_id + layer 哈希计算分组
    └── 返回 experiment_config
        ↓
[3] realtime-rank-pipeline
    ├── 阶段一: RecallLayer 召回
    │   ├── 调用 collaborative-filter.recommend()  →  ALS 召回
    │   ├── 调用 content-embedding-index.search()  →  向量相似召回
    │   ├── 多路召回结果合并去重
    │   └── 返回 top-200 候选集
    │
    ├── 阶段二: RankLayer 粗排
    │   ├── 调用 user-profile-service.get_user_profile()  →  用户画像
    │   ├── 调用 content-embedding-index.get_content_info()  →  物品信息
    │   ├── 构造 10 维特征向量
    │   ├── LightGBM / heuristic 打分
    │   └── 返回 top-50 排序结果
    │
    └── 阶段三: RerankLayer 重排
        ├── MMR 算法做多样性打散
        ├── BusinessRuleInjector 注入业务规则
        │   ├── 品类曝光比例约束（如电子产品≥30%）
        │   ├── 特定物品置顶
        │   └── 冷启动物品保量
        └── 返回 top-20 最终结果
            ↓
HTTP 响应 (RecommendResponse)
```

---

## 三、各模块职责边界

### 1. user-profile-service ([user_profile_service/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/))

**核心职责**：用户画像构建、存储、版本管理
- 接收用户行为事件 → 实时聚合特征
- 维护用户长期/短期兴趣向量
- 画像版本化管理（支持回滚）
- Redis 缓存热数据，PostgreSQL 持久化冷数据

**对外 API**：
- `ingest_behavior_event(event)` - 摄入行为事件
- `get_user_profile(user_id)` - 获取最新画像
- `get_user_statistics(user_id)` - 获取统计指标
- `list_profile_versions(user_id)` - 列出版本历史
- `get_profile_version(user_id, version)` - 获取指定版本

**通信方式**：直接函数调用（同进程）

---

### 2. content-embedding-index ([content_embedding_index/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/content_embedding_index/))

**核心职责**：内容向量索引、相似性检索
- FAISS 向量索引（IVF + Flat）
- 增量更新与热重载
- PostgreSQL 存元数据，Redis 存向量缓存

**对外 API**：
- `search(query_vector, top_k)` - 向量近邻搜索
- `add_embedding(content_id, embedding)` - 添加向量
- `batch_search(query_vectors, top_k)` - 批量搜索
- `get_content_info(content_id)` - 获取内容元数据
- `rebuild_index()` - 全量重建索引

**通信方式**：直接函数调用（同进程）

---

### 3. collaborative-filter ([collaborative_filter/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/collaborative_filter/))

**核心职责**：协同过滤召回、在线更新
- 离线 ALS 矩阵分解训练（implicit 库 / SGD 降级）
- 在线增量更新（新物品冷启动）
- 冷启动物品：content-embedding PCA 降维初始化

**对外 API**：
- `recommend(user_id, top_k)` - 召回推荐
- `predict_score(user_id, item_id)` - 预测分数
- `initialize_new_item(content_id, embedding, seed_users)` - 冷启动物品
- `get_model_stats()` - 获取模型指标

**通信方式**：直接函数调用（同进程）；在线更新消费 Kafka

---

### 4. realtime-rank-pipeline ([realtime_rank_pipeline/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/realtime_rank_pipeline/))

**核心职责**：三阶段排序管道
- **RecallLayer**：多路召回（ALS + 向量相似）→ 200 候选
- **RankLayer**：LightGBM 精排 → 50 候选
- **RerankLayer**：MMR 多样性 + 业务规则 → 20 结果

**对外 API**：
- `recommend(request)` - 完整排序链路
- `get_pipeline_metrics()` - 管道性能指标

**通信方式**：直接函数调用（同进程）

---

### 5. ab-test-router ([ab_test_router/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/ab_test_router/))

**核心职责**：A/B 实验分流、配置管理
- 多层正交实验（recall_layer / rank_layer / rerank_layer）
- mmh3 哈希（降级 md5）保证分流一致性
- 排除策略：用户标签白/黑名单、ID 正则匹配

**对外 API**：
- `get_user_assignment(user_id, layer)` - 获取用户分组
- `get_experiment_config(user_id)` - 获取实验配置
- `list_experiments(layer)` - 列出实验
- `force_reload()` - 强制刷新配置

**通信方式**：直接函数调用（同进程）

---

### 6. feedback-collector ([feedback_collector/](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/feedback_collector/))

**核心职责**：用户反馈收集、分发
- Kafka 多 worker 批量消费
- 行为事件 → user-profile-service 更新画像
- 交互事件 → collaborative-filter 在线更新
- Iceberg 湖仓持久化

**对外 API**：
- `collect_feedback(event)` - 收集反馈
- `get_stats()` - 收集器指标
- `flush()` - 强制刷盘

**通信方式**：
- 内部：直接函数调用
- 外部：Kafka 消息队列

---

### 7. model-serving-gateway ([main.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/main.py))

**核心职责**：API 网关、协议转换
- FastAPI HTTP 接口
- 请求参数校验
- 健康检查
- Prometheus 指标暴露

**对外 API**：
- `POST /api/v1/recommend` - 推荐接口
- `POST /api/v1/feedback` - 反馈上报
- `GET /health` - 健康检查
- `GET /metrics` - Prometheus 指标

**通信方式**：HTTP REST

---

## 四、模块间通信方式汇总

| 模块组合 | 通信方式 | 方向 |
|---------|---------|------|
| gateway → router | 直接函数调用 | 同步 |
| gateway → pipeline | 直接函数调用 | 同步 |
| pipeline → user-profile | 直接函数调用 | 同步 |
| pipeline → content-index | 直接函数调用 | 同步 |
| pipeline → collaborative-filter | 直接函数调用 | 同步 |
| feedback → user-profile | 直接函数调用 | 同步 |
| feedback → collaborative-filter | 直接函数调用 | 同步 |
| 外部服务 → feedback | Kafka 消息队列 | 异步 |

---

## 五、核心数据类型速查

### 5.1 UserProfile（用户画像）

```python
class UserProfile(BaseModel):
    user_id: str                      # 用户ID
    profile_version: int              # 画像版本号
    interests: Dict[str, float]       # 兴趣标签权重 {tag: weight}
    categories: Dict[str, float]      # 品类偏好权重
    long_term_vector: List[float]     # 长期兴趣向量 (64维)
    short_term_vector: List[float]    # 短期兴趣向量 (64维)
    interaction_stats: Dict[str, int] # 交互统计 {click, purchase, ...}
    last_activity_ts: float           # 最后活动时间戳
    created_at: datetime              # 创建时间
    updated_at: datetime              # 更新时间
```

**[schema.py#L83-L100](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L83-L100)**

---

### 5.2 ContentItemEmbedding（内容向量）

```python
class ContentItem(BaseModel):
    content_id: str                    # 内容ID
    title: str                         # 标题
    content_type: str                  # 类型: article/video/image
    categories: List[str]              # 品类标签
    tags: List[str]                    # 关键词标签
    author: str                        # 作者
    popularity_score: float            # 热度分 0-100
    publish_time: Optional[datetime]   # 发布时间
    embedding: Optional[List[float]]   # 内容向量 (768维)
```

**[schema.py#L102-L125](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L102-L125)**

---

### 5.3 RecallResultItem（召回候选）

```python
class RecallResultItem(BaseModel):
    content_id: str                    # 内容ID
    score: float                       # 召回分数 [-1, 1]
    recall_source: str                 # 来源: als/similar/cold_start
```

**[schema.py#L127-L135](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L127-L135)**

---

### 5.4 RankResultItem（粗排结果）

```python
class RankResultItem(BaseModel):
    content_id: str                    # 内容ID
    final_score: float                 # 最终排序分
    features: Dict[str, float]         # 排序特征
    rank: int                          # 排名位置
```

**[schema.py#L137-L148](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L137-L148)**

---

### 5.5 RerankResultItem（重排结果）

```python
class RerankResultItem(BaseModel):
    content_id: str                    # 内容ID
    final_score: float                 # 最终分数
    diversity_penalty: float           # 多样性惩罚
    rule_adjustment: float             # 业务规则调整分
    rank: int                          # 最终排名
```

**[schema.py#L150-L163](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L150-L163)**

---

### 5.6 ABTestExperiment（实验配置）

```python
class ABTestExperiment(BaseModel):
    experiment_id: str                 # 实验ID
    name: str                          # 实验名称
    layer: str                         # 实验层: recall/rank/rerank
    status: str                        # 状态: active/paused/ended
    traffic_percentage: int            # 流量占比 0-100
    control_group: str                 # 对照组ID
    experiment_groups: List[str]       # 实验组列表
    config: Dict[str, Any]             # 实验参数
    exclusion_policy: Optional[ExclusionPolicy]  # 排除策略
```

**[schema.py#L229-L245](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L229-L245)**

---

### 5.7 ExclusionPolicy（排除策略）

```python
class ExclusionPolicy(BaseModel):
    user_tags_whitelist: List[str]     # 标签白名单（满足才能进实验）
    user_tags_blacklist: List[str]     # 标签黑名单（满足则排除）
    user_id_pattern: Optional[str]     # ID 正则匹配（匹配则排除）
    user_id_whitelist: List[str]       # ID 白名单
    user_id_blacklist: List[str]       # ID 黑名单
```

**[schema.py#L32-L48](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L32-L48)**

---

### 5.8 BusinessRule（业务规则）

```python
class BusinessRule(BaseModel):
    rule_id: str                       # 规则ID
    name: str                          # 规则名称
    type: str                          # 类型: boost/penalize/pin/exclude/category_ratio/cold_start_boost
    priority: int                      # 优先级 1-10（大的先执行）
    filter: BusinessRuleFilter         # 匹配条件
    params: Dict[str, Any]             # 规则参数
    enabled: bool                      # 是否启用
```

**[schema.py#L204-L213](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/models/schemas.py#L204-L213)**

---

## 六、关键设计决策

1. **单例模式**：所有 Service 类采用 `__new__` 实现单例，确保全局唯一实例
2. **优雅降级**：
   - mmh3 → hashlib.md5（哈希分流）
   - implicit 库 → SGD 实现（ALS 训练）
   - faiss → 内存暴力搜索（向量检索）
   - LightGBM → heuristic 规则（粗排）
3. **热加载**：业务规则、A/B 配置支持 Redis 热刷新
4. **版本化**：用户画像、模型都带版本号，支持灰度和回滚
