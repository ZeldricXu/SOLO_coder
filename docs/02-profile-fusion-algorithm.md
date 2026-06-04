# 用户画像融合算法详解

## 一、整体架构

用户画像由三部分数据融合而成：

```
  实时行为事件 (Kafka)
        │
        ▼
  ┌─────────────────────────────────────────┐
  │  behavior_aggregator.py                 │
  │  ├── aggregate()         → 统计指标     │
  │  ├── generate_interest_tags() → 兴趣标签 │
  │  └── generate_user_vector()  → 兴趣向量 │
  └─────────────────────────────────────────┘
        │
        ▼  融合
  ┌─────────────────────────────────────────┐
  │  user_profile_service.py                │
  │  ├── 离线画像 (PostgreSQL)              │
  │  ├── 实时画像 (Redis)                    │
  │  └── 版本管理 (Redis 自增ID)            │
  └─────────────────────────────────────────┘
        │
        ▼
  UserProfile 结构化对象
```

---

## 二、画像融合策略总览

| 数据来源 | 时效性 | 存储 | 冲突解决策略 |
|---------|-------|------|------------|
| 实时行为 | 秒级 | Redis | 最后写入为准（LWW） |
| 离线画像 | T+1 | PostgreSQL | 作为基线，实时层覆盖 |
| 人工标签 | 人工 | PostgreSQL | 最高优先级，永不覆盖 |

### 冲突解决优先级

```
人工标注标签 > 实时行为画像 > 离线计算画像
```

---

## 三、核心算法详解

### 3.1 时间衰减公式

**位置**：[behavior_aggregator.py#L64-L86](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/behavior_aggregator.py#L64-L86)

**数学公式**：
```
w(t) = w₀ × 0.5^(Δt / T₁/₂)

其中:
  w₀ = 行为类型原始权重
  Δt = 当前时间 - 事件时间 (秒)
  T₁/₂ = 7 × 24 × 3600 = 604800 秒 (7天半衰期)
```

**衰减曲线**：
```
权重
  1.0 ┼───●  (t=0, 权重=1.0)
      │    \
  0.5 ┼─────●  (t=7天, 权重=0.5)
      │       \
  0.25┼────────●  (t=14天, 权重=0.25)
      │           \
  0.01┼───────────────━━━━━━━━━━  (下限)
      └────────────────────────── 时间
           7天    14天    21天
```

**设计意图**：
- 推荐系统关注用户**近期兴趣**，新行为权重更大
- 7天半衰期平衡了「短期兴趣捕捉」和「长期兴趣保留」
- 最低 0.01 兜底，避免太老的事件权重归零后完全消失

---

### 3.2 行为类型权重系数

**位置**：[behavior_aggregator.py#L26-L33](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/behavior_aggregator.py#L26-L33)

```python
EVENT_WEIGHTS = {
    "expose":   0.1,   # 曝光：弱信号
    "click":    1.0,   # 点击：基础权重（基准）
    "stay":     1.5,   # 停留：有兴趣，+50%
    "collect":  2.5,   # 收藏：明确感兴趣
    "share":    3.0,   # 分享：强兴趣
    "purchase": 5.0,   # 购买：强转化，最高权重
}
```

**权重设计逻辑**：
- 基于「用户行为漏斗」：曝光 → 点击 → 停留 → 收藏/分享 → 购买
- 越深层的行为越能代表真实兴趣，权重越高
- 购买权重是曝光的 50 倍

---

### 3.3 有效权重计算

**位置**：[behavior_aggregator.py#L130-L163](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/behavior_aggregator.py#L130-L163)

**公式**：
```
有效权重 = 行为类型权重 × 时间衰减因子

w_effective(content, t) = w_type × 0.5^(t / T)
```

**示例**：
```
场景：用户3天前点击了文章A，10天前购买了文章B

计算:
  文章A (点击, 3天):
    w_type = 1.0
    decay = 0.5^(3/7) ≈ 0.74
    w_effective = 1.0 × 0.74 = 0.74

  文章B (购买, 10天):
    w_type = 5.0
    decay = 0.5^(10/7) ≈ 0.37
    w_effective = 5.0 × 0.37 = 1.85

结果：虽然购买发生在更久前，但有效权重更高 (1.85 > 0.74)
```

---

### 3.4 兴趣标签生成算法

**位置**：[behavior_aggregator.py#L201-L286](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/behavior_aggregator.py#L201-L286)

**算法**：基于内容标签的倒排加权

**步骤**：
```
1. 遍历用户交互过的每个内容 i
2. 对内容 i 的每个标签 t:
     score(t) += w_effective(i) / |tags(i)|
     (按标签数均分权重，避免多标签内容占便宜)

3. 归一化到 [0, 1]:
     weight_norm(t) = raw_score(t) / max_score

4. 取 Top-K
```

**示例**：
```
用户交互:
  内容A (标签: [科技, AI], 权重: 2.0)
  内容B (标签: [科技, 数码], 权重: 3.0)

计算:
  科技: 2.0/2 + 3.0/2 = 1.0 + 1.5 = 2.5
  AI:   2.0/2 = 1.0
  数码: 3.0/2 = 1.5

归一化 (max=2.5):
  科技: 2.5/2.5 = 1.00
  数码: 1.5/2.5 = 0.60
  AI:   1.0/2.5 = 0.40
```

**冲突策略**：
- 不同内容对同一标签的贡献：**简单相加**
- 同一内容多标签：**权重均分**（公平性）

---

### 3.5 用户兴趣向量生成

**位置**：[behavior_aggregator.py#L288-L357](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/behavior_aggregator.py#L288-L357)

**算法**：加权平均 + L2 归一化

**数学公式**：
```
         Σ(w_i × v_i)
u_vec = ──────────────
            Σw_i

然后做 L2 归一化:
         u_vec
u_norm = ───────
         ||u_vec||₂

其中:
  w_i = 用户对内容 i 的有效权重
  v_i = 内容 i 的向量 (768维)
```

**几何意义**：
- 加权平均 = 带质量的重心
- L2 归一化 = 投影到单位球面
- 便于与内容向量做余弦相似度计算

**冲突策略**：
- 同一内容多次交互：权重已在 aggregate 阶段累加
- 不同内容向量：直接加权线性组合
- 缺失向量：跳过该内容（不参与平均）

---

### 3.6 画像版本管理

**位置**：[user_profile_service.py](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/user_profile_service.py)

**Redis Key 设计**：
```
当前版本指针:
  user:profile:latest:{user_id}  → 版本号 (Redis INCR 自增)

画像版本数据:
  user:profile:version:{user_id}:{version}  → JSON 序列化画像
  TTL = 7 天

冷数据归档:
  PostgreSQL user_profiles 表
```

**版本切换流程**：
```
1. 行为事件到达
2. Redis INCR version 获得新版本号
3. 写入新版本画像
4. 更新 latest 指针
5. 旧版本按 TTL 自动过期
```

---

### 3.7 多源画像合并策略

**位置**：[user_profile_service.py#L260-L295](file:///Users/huangzitong/Desktop/SoloCoder6月/Code/16-20/DF1-18/recommendation_engine/user_profile_service/user_profile_service.py#L260-L295)

| 字段 | 合并策略 | 说明 |
|-----|---------|------|
| interests（兴趣标签） | 实时覆盖离线 | 实时权重 > 离线权重 |
| categories（品类偏好） | 加权融合 | 实时:离线 = 7:3 |
| long_term_vector | 离线为主 | 离线计算更准确 |
| short_term_vector | 实时为主 | 反映近期兴趣 |
| interaction_stats | 累加 | 实时 + 离线 |
| 人工标签 | 最高优先级 | 永不覆盖 |

**标签冲突解决**：
```python
# 伪代码
for tag in union(realtime_tags, offline_tags):
    if tag in manual_tags:
        # 人工标签：优先级最高
        result[tag] = manual_tags[tag]
    elif tag in realtime_tags:
        # 实时覆盖：时间衰减已保证准确性
        result[tag] = realtime_tags[tag]
    else:
        # 离线兜底
        result[tag] = offline_tags[tag]
```

---

## 四、关键参数总结

| 参数 | 值 | 位置 | 说明 |
|-----|----|------|------|
| 时间衰减半衰期 | 7天 | `TIME_DECAY_HALF_LIFE` | 指数衰减半衰期 |
| 最低衰减因子 | 0.01 | `_calculate_time_decay` | 兜底防止归零 |
| 事件窗口 | 30天 | `cleanup_old_events` | 滚动窗口清理 |
| 兴趣标签 Top-K | 20 | `generate_interest_tags` | 返回标签数 |
| 标签最小权重 | 0.01 | `generate_interest_tags` | 过滤噪声 |
| 画像版本 TTL | 7天 | settings | 旧版本过期时间 |

---

## 五、常见问题

### Q: 为什么用指数衰减而不是线性衰减？
**A**: 指数衰减更符合人类记忆遗忘曲线，近期行为权重下降慢，远期行为权重下降快。

### Q: 为什么要对多标签内容均分权重？
**A**: 避免"标签堆砌"问题——如果一个内容打了10个标签，它对每个标签的贡献应该是打1个标签内容的1/10。

### Q: 用户向量为什么要L2归一化？
**A**: 保证用户向量在单位球面上，便于与内容向量做余弦相似度计算（内积 = 余弦相似度）。

### Q: 实时画像和离线画像冲突怎么办？
**A**: 遵循「实时优先、人工最高」原则。实时数据反映最新兴趣，人工标注是真理。
