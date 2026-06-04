# Recommend 接口完整调用序列

## 调用栈概览

```
HTTP POST /api/v1/recommend
    └── recommendation_engine/main.py: recommend_endpoint()
        ├── ab_test_router.get_user_assignment(user_id, layer)
        ├── ab_test_router.get_experiment_config(user_id)
        └── realtime_rank_pipeline.recommend(request, experiment_config)
            ├── RecallLayer.recall(user_id, top_k)
            │   ├── collaborative_filter.recommend(user_id, top_k)
            │   │   ├── trainer.recommend(user_id, top_k)
            │   │   └── _cold_start_recommend(user_id, top_k)
            │   └── content_embedding_index.search(user_vector, top_k)
            │       └── FAISS.index.search()
            ├── RankLayer.rank(user_id, recall_items, top_k)
            │   ├── user_profile_service.get_user_profile(user_id)
            │   ├── content_embedding_index.get_content_info(content_id)
            │   └── LightGBM / heuristic 打分
            └── RerankLayer.rerank(user_id, ranked_items, top_k)
                ├── MMR 多样性打散
                │   ├── content_embedding_index.get_content_embedding(cid)
                │   └── 余弦相似度计算
                └── BusinessRuleInjector.apply_rules()
                    ├── 品类曝光比例约束
                    ├── 特定物品置顶
                    └── 冷启动物品保量
```

---

## 详细调用序列

### 第1层：FastAPI 网关

**文件**：[recommendation_engine/main.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/main.py)

```
推荐入口: recommend_endpoint()
├── 入参: RecommendRequest {user_id, top_n, context}
├── 出参: RecommendResponse {request_id, results[], experiment_info}
│
├── 步骤1: 获取A/B实验分组
│   └── abtest_router.get_user_assignment(user_id, "recall_layer")
│       ├── 检查 exclusion_policy (排除策略)
│       ├── mmh3.hash(user_id + layer + exp_id, seed) % bucket
│       └── 返回: ABTestAssignment {group_id, experiment_id}
│
├── 步骤2: 获取实验配置
│   └── abtest_router.get_experiment_config(user_id)
│       └── 返回: Dict (实验参数，如 mmr_lambda=0.7)
│
└── 步骤3: 调用排序管道
    └── rank_pipeline.recommend(request, experiment_config)
        └── 返回: RecommendResponse
```

---

### 第2层：排序管道入口

**文件**：[realtime_rank_pipeline/rank_pipeline.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/realtime_rank_pipeline/rank_pipeline.py)

```
RankPipeline.recommend(request, experiment_config)
├── 入参:
│   ├── request: RecommendRequest
│   │   ├── user_id: str
│   │   ├── top_n: int (默认20)
│   │   ├── context: Dict (可选)
│   └── experiment_config: Dict (可选)
└── 出参: RecommendResponse
    │
    ├── 阶段1: 多路召回 → 200候选
    │   └── recall_result = self._recall_layer.recall(
    │       user_id=user_id,
    │       top_k=200 (pipeline_recall_top_k)
    │   )
    │
    ├── 阶段2: 粗排 → 50候选
    │   └── ranked_result = self._rank_layer.rank(
    │       user_id=user_id,
    │       recall_items=recall_result,  [RecallResultItem]
    │       top_k=50 (pipeline_rank_top_k)
    │   )
    │
    └── 阶段3: 重排 → 20结果
        └── reranked_result = self._rerank_layer.rerank(
            user_id=user_id,
            ranked_items=ranked_result,  [RankResultItem]
            top_k=20 (pipeline_rerank_top_k),
            experiment_config=experiment_config
        )
```

---

### 第3层：RecallLayer 召回层

**文件**：[realtime_rank_pipeline/recall_layer.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/realtime_rank_pipeline/recall_layer.py)

```
RecallLayer.recall(user_id, top_k)
├── 入参:
│   ├── user_id: str
│   └── top_k: int
└── 出参: List[RecallResultItem]  [content_id, score, recall_source]
    │
    ├── 召回通道1: 协同过滤 ALS
    │   └── cf_items = self._cf_service.recommend(user_id, top_k)
    │       │
    │       ├── 子调用: ALSTrainer.recommend()
    │       │   ├── user_idx = self._user_id_map[user_id]
    │       │   ├── user_factor = self._user_factors[user_idx]
    │       │   ├── scores = user_factor @ self._item_factors.T
    │       │   ├── 排序取 Top-K
    │       │   └── 返回: [(item_id, score)]
    │       │
    │       └── 冷启动兜底: _cold_start_recommend()
    │           └── 按热度返回冷启动物品
    │
    ├── 召回通道2: 向量相似
    │   ├── user_profile = self._profile_service.get_user_profile(user_id)
    │   ├── user_vector = user_profile.short_term_vector
    │   └── sim_items = self._content_index.search(user_vector, top_k)
    │       └── FAISS 向量检索
    │
    ├── 多路合并
    │   ├── 去重（同 content_id 取最高分）
    │   ├── 按 score 降序
    │   └── 截断到 top_k
    │
    └── 打 recall_source 标记: als / similar / cold_start
```

---

### 第4层：RankLayer 粗排层

**文件**：[realtime_rank_pipeline/rank_layer.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/realtime_rank_pipeline/rank_layer.py)

```
RankLayer.rank(user_id, recall_items, top_k)
├── 入参:
│   ├── user_id: str
│   ├── recall_items: List[RecallResultItem]
│   └── top_k: int
└── 出参: List[RankResultItem]  [content_id, final_score, features]
    │
    ├── 步骤1: 获取用户画像
    │   └── user_profile = self._profile_service.get_user_profile(user_id)
    │       ├── interests: Dict[str, float]
    │       ├── short_term_vector: List[float]
    │       └── interaction_stats: Dict
    │
    ├── 步骤2: 为每个候选构造特征
    │   └── 对每个 item in recall_items:
    │       ├── content_info = self._content_index.get_content_info(content_id)
    │       ├── 构造 10 维特征向量:
    │       │   ├── ctr_score (点击率)
    │       │   ├── stay_time_score (停留时长)
    │       │   ├── purchase_score (购买率)
    │       │   ├── share_score (分享率)
    │       │   ├── tag_match_score (标签匹配度)
    │       │   ├── vector_cosine_score (向量相似度)
    │       │   ├── als_score (ALS预测分)
    │       │   ├── content_popularity (内容热度)
    │       │   ├── user_interest_diversity (用户多样性)
    │       │   └── content_freshness (内容新鲜度)
    │       └── 保存到 features Dict
    │
    ├── 步骤3: 打分
    │   ├── LightGBM 可用时: predict(features) → score
    │   └── LightGBM 不可用时: heuristic 加权和
    │
    ├── 步骤4: 排序截断
    │   ├── 按 final_score 降序
    │   └── 取 top_k
    │
    └── 返回: [RankResultItem]
```

---

### 第5层：RerankLayer 重排层

**文件**：[realtime_rank_pipeline/rerank_layer.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/realtime_rank_pipeline/rerank_layer.py)

```
RerankLayer.rerank(user_id, ranked_items, top_k, experiment_config)
├── 入参:
│   ├── user_id: str
│   ├── ranked_items: List[RankResultItem]
│   ├── top_k: int
│   └── experiment_config: Dict
└── 出参: List[RerankResultItem]
    │
    ├── 步骤1: 业务规则预处理
    │   └── rule_adjustments = self._apply_business_rules(
    │       ranked_items,
    │       experiment_config.get("business_rules", [])
    │   )
    │       └── 返回: Dict[content_id → float]  (分调整量)
    │
    ├── 步骤2: MMR 多样性打散 (可选)
    │   └── 如果 enable_diversity=True:
    │       └── self._mmr_rerank(ranked_items, rule_adjustments, top_k, mmr_lambda)
    │           │
    │           ├── 初始化: selected=[], remaining=all
    │           │
    │           └── 迭代选 top_k 个:
    │               ├── 对每个候选 c in remaining:
    │               │   ├── relevance = score + rule_adjustment
    │               │   ├── diversity = max(cosine(c, s) for s in selected)
    │               │   └── mmr_score = λ*relevance - (1-λ)*diversity
    │               ├── 选 mmr_score 最大的加入 selected
    │               └── 从 remaining 移除
    │
    └── 步骤3: 业务规则注入 (BusinessRuleInjector)
        └── self._rule_injector.apply_rules(reranked_items)
            │
            ├── 按优先级排序规则
            │
            ├── 规则类型1: boost/penalize → 调整分数
            ├── 规则类型2: pin → 固定位置
            ├── 规则类型3: exclude → 排除
            ├── 规则类型4: category_ratio → 品类比例约束
            │   ├── 比例不够: 从候补补入
            │   └── 比例超标: 移除低分的
            └── 规则类型5: cold_start_boost → 冷启动物品加分
```

---

## 关键数据结构流转

```
请求: RecommendRequest
    │
    ▼
RecallLayer  →  List[RecallResultItem]
            │  {content_id, score, recall_source}
            │
            ▼
RankLayer    →  List[RankResultItem]
            │  {content_id, final_score, features, rank}
            │
            ▼
RerankLayer  →  List[RerankResultItem]
            │  {content_id, final_score, diversity_penalty,
            │   rule_adjustment, rank}
            │
            ▼
响应: RecommendResponse {results: [...]}
```

---

## 性能瓶颈点

| 阶段 | 耗时占比 | 瓶颈原因 | 优化点 |
|-----|---------|---------|-------|
| 召回 | 40% | FAISS 检索 + ALS 矩阵乘 | 索引预热、批量查询 |
| 粗排 | 30% | 特征构造 (多次 IO) | 批量获取、特征缓存 |
| 重排 | 20% | MMR O(kN) 复杂度 | top_k 截断、向量化 |
| 业务规则 | 10% | 规则匹配 | 规则索引、缓存 |

---

## 调用序列验证方式

### 日志埋点位置

```
main.py:recommend_endpoint()
  ├─ [INFO] 推荐请求开始: user_id={user_id}
  ├─ [DEBUG] AB实验分组: user_id={user_id}, group={group}
  ├─ [DEBUG] 召回完成: count={n_als}+{n_sim}, 去重后={n_total}
  ├─ [DEBUG] 粗排完成: {n_in} → {n_out}
  ├─ [DEBUG] 重排完成: MMR 选了 {n_selected}, 业务规则调整 {n_adjusted}
  └─ [INFO] 推荐请求完成: 返回 {n_results} 条, 耗时={ms}ms
```

### 验证命令

```bash
# 查看推荐接口日志
tail -f logs/app.log | grep "推荐请求"

# 查看特定用户的调用链
tail -f logs/app.log | grep "user_12345"
```
