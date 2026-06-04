from typing import Dict, List, Tuple, Any
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from loguru import logger
import numpy as np

from recommendation_engine.models.schemas import UserBehaviorEvent, InterestTag
from config import settings


class BehaviorAggregator:
    """
    用户行为聚合器 - 核心算法详解

    负责将用户行为事件转化为结构化的用户画像特征，包含：
    - 时间衰减加权的行为统计
    - 兴趣标签生成（基于内容标签倒排）
    - 用户兴趣向量（加权内容向量聚合）

    关键设计：
    - 指数时间衰减（半衰期 7 天）：新行为权重更大
    - 行为类型差异化权重：购买 > 分享 > 收藏 > 停留 > 点击 > 曝光
    - 向量加权平均：保证用户向量在单位球面上
    """

    EVENT_WEIGHTS = {
        "click": 1.0,      # 点击：基础权重
        "stay": 1.5,       # 停留：说明有兴趣，权重比点击高50%
        "purchase": 5.0,   # 购买：强转化信号，最高权重
        "share": 3.0,      # 分享：强兴趣信号
        "collect": 2.5,    # 收藏：明确感兴趣
        "expose": 0.1,     # 曝光：弱信号，避免用户只看不点影响太大
    }

    """
    时间衰减半衰期（秒）: 7天 = 604800秒

    数学含义：
        事件发生 t 秒后，权重衰减为原来的 0.5^(t / T)
        其中 T = TIME_DECAY_HALF_LIFE

    示例：
        第 7 天: 权重 = 原始权重 * 0.5^(1) = 0.5
        第14天: 权重 = 原始权重 * 0.5^(2) = 0.25
        第21天: 权重 = 原始权重 * 0.5^(3) = 0.125
        ...以此类推

    设计考虑：
    - 推荐系统更关注用户近期兴趣
    - 7天半衰期平衡了短期兴趣捕捉和长期兴趣保留
    - 最低 0.01 兜底，防止太老的事件权重归零后完全消失
    """
    TIME_DECAY_HALF_LIFE = 7 * 24 * 3600  # 7天半衰期

    def __init__(self):
        self._event_counters: Dict[str, Dict[str, float]] = defaultdict(
            lambda: defaultdict(float)
        )
        self._event_timestamps: Dict[str, Dict[str, datetime]] = {}
        self._duration_stats: Dict[str, Dict[str, List[float]]] = defaultdict(
            lambda: defaultdict(list)
        )

    def _calculate_time_decay(self, event_time: datetime, now: datetime) -> float:
        """
        计算事件的时间衰减因子

        数学公式:
            decay = 0.5^(Δt / T_half)

        其中:
            Δt = 当前时间 - 事件时间 （秒）
            T_half = 时间衰减半衰期

        Args:
            event_time: 事件发生时间
            now: 当前参考时间

        Returns:
            float: 衰减因子 [0.01, 1.0]
        """
        time_diff = (now - event_time).total_seconds()
        if time_diff < 0:
            return 1.0
        decay_factor = 0.5 ** (time_diff / self.TIME_DECAY_HALF_LIFE)
        return max(decay_factor, 0.01)  # 最低保留1%权重

    def aggregate(self, user_id: str, events: List[UserBehaviorEvent]) -> Dict[str, Any]:
        """
        聚合用户行为事件，生成统计指标

        算法流程：
            对每个事件:
                1. 查行为类型权重表 → w_type
                2. 计算时间衰减因子 → w_time
                3. 有效权重 = w_type × w_time
                4. 按内容ID累加有效权重到 content_interactions

        多源数据冲突策略:
            - 同一内容多次交互: 加权累加（时间越近权重越高）
            - 不同行为类型: 权重相加（如点击+停留 = 1.0+1.5=2.5）

        Args:
            user_id: 用户ID
            events: 行为事件列表

        Returns:
            Dict: 聚合后的统计指标
        """
        now = datetime.now(timezone.utc)
        stats: Dict[str, Any] = {
            "total_events": 0,
            "click_count": 0,
            "purchase_count": 0,
            "share_count": 0,
            "collect_count": 0,
            "avg_stay_duration": 0,
            "total_stay_duration": 0,
            "active_days": 0,
            "last_activity": 0.0,
            "expose_count": 0,
            "stay_count": 0,
            "total_duration_seconds": 0.0,
        }

        content_interactions: Dict[str, float] = defaultdict(float)
        active_dates = set()
        last_activity_ts: Optional[datetime] = None

        for event in events:
            stats["total_events"] += 1
            event_type = event.event_type

            # 步骤1: 获取行为类型权重
            weight = self.EVENT_WEIGHTS.get(event_type, 1.0)

            # 步骤2: 计算时间衰减
            time_decay = self._calculate_time_decay(event.timestamp, now)

            # 步骤3: 有效权重 = 行为类型权重 × 时间衰减
            effective_weight = weight * time_decay

            # 计数统计
            if event_type == "click":
                stats["click_count"] += 1
            elif event_type == "purchase":
                stats["purchase_count"] += 1
            elif event_type == "share":
                stats["share_count"] += 1
            elif event_type == "collect":
                stats["collect_count"] += 1
            elif event_type == "expose":
                stats["expose_count"] += 1
            elif event_type == "stay":
                stats["stay_count"] += 1
                if event.duration_seconds:
                    stats["total_stay_duration"] += event.duration_seconds
                    stats["total_duration_seconds"] += event.duration_seconds
                    self._duration_stats[user_id][event.content_id].append(event.duration_seconds)

            # 步骤4: 按内容ID累加有效权重
            # 冲突策略：加权累加，同一内容的多次交互权重叠加
            content_interactions[event.content_id] += effective_weight

            if event.timestamp:
                active_dates.add(event.timestamp.date())
                if last_activity_ts is None or event.timestamp > last_activity_ts:
                    last_activity_ts = event.timestamp

        if last_activity_ts:
            stats["last_activity"] = last_activity_ts.timestamp()

        stats["active_days"] = len(active_dates)

        stay_events = len(self._duration_stats[user_id])
        if stay_events > 0:
            all_durations = [
                d for durations in self._duration_stats[user_id].values() for d in durations
            ]
            if all_durations:
                stats["avg_stay_duration"] = sum(all_durations) / len(all_durations)

        self._event_counters[user_id] = dict(content_interactions)

        total_interactions = sum(content_interactions.values())
        if total_interactions > 0:
            stats["interaction_diversity"] = len(content_interactions) / (
                total_interactions + 1
            )
        else:
            stats["interaction_diversity"] = 0.0

        ctr = stats["click_count"] / max(stats.get("expose_count", 1), 1)
        stats["ctr_30d"] = min(ctr, 1.0)

        conversion_rate = stats["purchase_count"] / max(stats["click_count"], 1)
        stats["conversion_rate"] = min(conversion_rate, 1.0)

        return stats

    def generate_interest_tags(
        self,
        user_id: str,
        content_tags_map: Dict[str, List[str]],
        top_k: int = 20,
    ) -> List[InterestTag]:
        """
        生成用户兴趣标签

        算法：基于内容标签的倒排加权

        流程：
            1. 遍历用户交互过的每个内容
            2. 对该内容的每个标签，按标签数均分权重
            3. 累加相同标签的得分
            4. 归一化到 [0, 1] 区间
            5. 取Top-K

        数学含义:
            标签 t 的得分 = Σ( w(content_i) / |tags(content_i)| )
                             所有包含 t 的 content_i

            其中 w(content_i) 是用户对内容i的有效权重
                 |tags(content_i)| 是内容i的标签数（均分权重）

        归一化公式:
            weight_norm = raw_score / max_score

        标签冲突策略:
            - 不同内容对同一标签的贡献：简单相加
            - 同一内容多标签：权重均分（避免多标签内容占便宜）

        Args:
            user_id: 用户ID
            content_tags_map: 内容ID → 标签列表 映射
            top_k: 返回Top-K个标签

        Returns:
            List[InterestTag]: 兴趣标签列表，按权重降序
        """
        interactions = self._event_counters.get(user_id, {})
        if not interactions:
            return []

        tag_scores: Dict[str, Tuple[float, str]] = defaultdict(lambda: (0.0, ""))

        for content_id, score in interactions.items():
            tags = content_tags_map.get(content_id, [])
            if not tags:
                continue

            # 步骤1: 同一内容的多个标签均分权重
            # 这样避免了"标签越多的内容贡献越大"的偏差
            tag_score_per_tag = score / len(tags)

            # 步骤2: 倒排累加标签得分
            # 多内容对同一标签的贡献直接相加
            for tag in tags:
                current_score, tag_name = tag_scores[tag]
                tag_scores[tag] = (current_score + tag_score_per_tag, tag)

        if not tag_scores:
            return []

        max_score = max(s for s, _ in tag_scores.values())
        if max_score <= 0:
            return []

        interest_tags = []
        for tag_id, (raw_score, tag_name) in sorted(
            tag_scores.items(), key=lambda x: x[1][0], reverse=True
        )[:top_k]:
            # 步骤3: 线性归一化到 [0, 1]
            normalized_weight = min(1.0, raw_score / max_score)
            if normalized_weight > 0.01:  # 过滤掉权重太小的标签
                interest_tags.append(
                    InterestTag(
                        tag_id=tag_id,
                        tag_name=tag_name,
                        weight=normalized_weight,
                        version="realtime_v1",
                        updated_at=datetime.now(timezone.utc),
                    )
                )

        return interest_tags

    def generate_user_vector(
        self,
        user_id: str,
        content_embeddings_map: Dict[str, np.ndarray],
        embedding_dim: int = 768,
    ) -> np.ndarray:
        """
        生成用户兴趣向量

        算法：内容向量加权平均 + L2归一化

        数学公式:
            u_vec = normalize( Σ(w_i * v_i) / Σw_i )

        其中:
            w_i = 用户对内容 i 的有效权重
            v_i = 内容 i 的向量
            normalize = L2 归一化

        设计考虑:
            1. 加权平均: 高权重内容对用户向量贡献更大
            2. L2归一化: 保证用户向量在单位球面上
               - 便于与内容向量做余弦相似度计算
               - 避免用户向量范数随交互增多而漂移

        多源数据冲突策略:
            - 同一内容多次交互: 权重已在 aggregate 阶段累加
            - 不同内容向量: 直接加权线性组合
            - 缺失向量: 跳过该内容（不参与平均）

        Args:
            user_id: 用户ID
            content_embeddings_map: 内容ID → 向量 映射
            embedding_dim: 向量维度

        Returns:
            np.ndarray: 用户兴趣向量 (embedding_dim,)，L2归一化
        """
        interactions = self._event_counters.get(user_id, {})
        if not interactions:
            return np.zeros(embedding_dim, dtype=np.float32)

        weighted_embeddings = []
        weights = []

        for content_id, score in interactions.items():
            embedding = content_embeddings_map.get(content_id)
            if embedding is not None:
                # 步骤1: 内容向量 × 权重 → 加权向量
                weighted_embeddings.append(embedding * score)
                weights.append(score)

        if not weighted_embeddings:
            return np.zeros(embedding_dim, dtype=np.float32)

        weights_sum = sum(weights)
        if weights_sum <= 0:
            return np.zeros(embedding_dim, dtype=np.float32)

        # 步骤2: 加权平均
        # Σ(w_i * v_i) / Σw_i
        user_vector = np.sum(weighted_embeddings, axis=0) / weights_sum

        # 步骤3: L2 归一化
        # 确保用户向量在单位球面上，便于余弦相似度计算
        norm = np.linalg.norm(user_vector)
        if norm > 0:
            user_vector = user_vector / norm

        return user_vector.astype(np.float32)

    def cleanup_old_events(self, user_id: str, days: int = 30) -> None:
        """
        清理过期事件（默认保留30天）

        设计：滚动窗口，过期数据自动清理
        目的：控制内存占用，同时时间衰减已自然降低老事件权重
        """
        cutoff_date = datetime.now(timezone.utc) - timedelta(days=days)

        if user_id in self._event_timestamps:
            old_contents = [
                content_id
                for content_id, ts in self._event_timestamps[user_id].items()
                if ts < cutoff_date
            ]
            for content_id in old_contents:
                self._event_counters[user_id].pop(content_id, None)
                self._event_timestamps[user_id].pop(content_id, None)
                self._duration_stats[user_id].pop(content_id, None)

    def get_content_affinity(self, user_id: str, content_id: str) -> float:
        return self._event_counters.get(user_id, {}).get(content_id, 0.0)
