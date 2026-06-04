from typing import List, Dict, Optional, Any
import os
import json
from datetime import datetime
from loguru import logger
import numpy as np

try:
    import lightgbm as lgb
    LGBM_AVAILABLE = True
except ImportError:
    LGBM_AVAILABLE = False
    logger.warning("LightGBM not available, using fallback scoring")

from recommendation_engine.user_profile_service import UserProfileService
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.collaborative_filter import CollaborativeFilter
from recommendation_engine.models.schemas import (
    UserProfile,
    RecallResultItem,
    RankResultItem,
    InterestTag,
)
from config import settings


class RankLayer:
    def __init__(
        self,
        user_profile_service: UserProfileService,
        content_index: ContentEmbeddingIndex,
        cf_service: CollaborativeFilter,
    ):
        self._user_profile = user_profile_service
        self._content_index = content_index
        self._cf_service = cf_service
        self._model_path = settings.lgbm_model_path
        self._feature_names = settings.lgbm_feature_names
        self._model: Optional[Any] = None
        self._model_version: str = "default"
        self._last_mtime: Optional[float] = None
        self._rank_top_k = settings.pipeline_rank_top_k

        self._load_model()

    def _load_model(self) -> bool:
        if not LGBM_AVAILABLE:
            logger.warning("LightGBM not available, using heuristic scoring")
            return False

        if not os.path.exists(self._model_path):
            logger.warning(f"LightGBM model not found at {self._model_path}")
            return False

        try:
            self._model = lgb.Booster(model_file=self._model_path)
            self._last_mtime = os.path.getmtime(self._model_path)
            model_meta = self._model_path + ".meta.json"
            if os.path.exists(model_meta):
                with open(model_meta, "r") as f:
                    meta = json.load(f)
                    self._model_version = meta.get("version", "default")
            logger.info(f"LightGBM model loaded, version: {self._model_version}")
            return True
        except Exception as e:
            logger.error(f"Failed to load LightGBM model: {e}")
            self._model = None
            return False

    def reload_model(self) -> bool:
        if os.path.exists(self._model_path):
            current_mtime = os.path.getmtime(self._model_path)
            if self._last_mtime is None or current_mtime != self._last_mtime:
                return self._load_model()
        return False

    async def rank(
        self,
        user_id: str,
        recall_items: List[RecallResultItem],
        top_k: Optional[int] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> List[RankResultItem]:
        top_k = top_k or self._rank_top_k
        experiment_config = experiment_config or {}

        if not recall_items:
            return []

        content_ids = [item.content_id for item in recall_items]
        recall_scores = {item.content_id: item.score for item in recall_items}

        feature_matrix = await self._extract_features(
            user_id, content_ids, recall_scores
        )

        scores = self._predict_scores(feature_matrix, content_ids)

        boost_factor = experiment_config.get("rank_boost_factor", {})

        ranked_items = []
        for content_id, score in scores.items():
            if content_id in boost_factor:
                score *= boost_factor[content_id]

            ranked_items.append(
                RankResultItem(
                    content_id=content_id,
                    final_score=score,
                    features={
                        name: float(feature_matrix[content_id][i])
                        for i, name in enumerate(self._feature_names)
                    },
                )
            )

        ranked_items.sort(key=lambda x: x.final_score, reverse=True)
        ranked_items = ranked_items[:top_k]

        for rank, item in enumerate(ranked_items, 1):
            item.rank = rank

        logger.debug(
            f"Rank layer returned {len(ranked_items)} items for user {user_id}"
        )
        return ranked_items

    async def _extract_features(
        self,
        user_id: str,
        content_ids: List[str],
        recall_scores: Dict[str, float],
    ) -> Dict[str, np.ndarray]:
        profile = await self._user_profile.get_user_profile(user_id)
        user_tags = profile.merge_tags() if profile else []
        user_vector = await self._user_profile.get_user_interest_vector(user_id)
        user_stats = profile.realtime_behavior_stats if profile else {}

        content_infos = {}
        content_embeddings = {}
        for cid in content_ids:
            info = await self._content_index.get_content_info(cid)
            if info:
                content_infos[cid] = info
            emb = await self._content_index.get_content_embedding(cid)
            if emb is not None:
                content_embeddings[cid] = emb

        als_scores = await self._cf_service.predict_scores_batch(user_id, content_ids)

        features = {}
        for cid in content_ids:
            feature_vec = np.zeros(len(self._feature_names), dtype=np.float32)

            feature_dict = self._compute_single_features(
                cid,
                user_tags,
                user_vector,
                user_stats,
                content_infos.get(cid, {}),
                content_embeddings.get(cid),
                recall_scores.get(cid, 0.0),
                als_scores.get(cid, 0.0),
            )

            for i, name in enumerate(self._feature_names):
                feature_vec[i] = feature_dict.get(name, 0.0)

            features[cid] = feature_vec

        return features

    def _compute_single_features(
        self,
        content_id: str,
        user_tags: List[InterestTag],
        user_vector: Optional[np.ndarray],
        user_stats: Dict[str, float],
        content_info: Dict[str, Any],
        content_embedding: Optional[np.ndarray],
        recall_score: float,
        als_score: float,
    ) -> Dict[str, float]:
        features: Dict[str, float] = {}

        features["ctr_score"] = float(user_stats.get("ctr_30d", 0.0))
        features["stay_time_score"] = min(
            1.0, float(user_stats.get("avg_stay_duration", 0.0)) / 300.0
        )
        features["purchase_score"] = float(user_stats.get("conversion_rate", 0.0))
        features["share_score"] = min(
            1.0, float(user_stats.get("share_count", 0.0)) / 10.0
        )

        content_tags = content_info.get("tags", []) or []
        tag_match_score = 0.0
        user_tag_map = {t.tag_id: t.weight for t in user_tags}
        for tag in content_tags:
            if tag in user_tag_map:
                tag_match_score += user_tag_map[tag]
        if content_tags:
            tag_match_score /= len(content_tags)
        features["tag_match_score"] = min(1.0, tag_match_score)

        if user_vector is not None and content_embedding is not None:
            norm_user = np.linalg.norm(user_vector)
            norm_content = np.linalg.norm(content_embedding)
            if norm_user > 0 and norm_content > 0:
                cosine = float(np.dot(user_vector, content_embedding) / (norm_user * norm_content))
                features["vector_cosine_score"] = max(0.0, min(1.0, (cosine + 1.0) / 2.0))
            else:
                features["vector_cosine_score"] = 0.0
        else:
            features["vector_cosine_score"] = 0.0

        features["als_score"] = max(0.0, min(1.0, 1.0 / (1.0 + np.exp(-als_score))))

        features["content_popularity"] = min(
            1.0, float(content_info.get("popularity_score", 0.0)) / 1000.0
        )

        tag_weights = [t.weight for t in user_tags[:20]]
        if tag_weights:
            normalized = np.array(tag_weights)
            if normalized.sum() > 0:
                p = normalized / normalized.sum()
                entropy = -np.sum(p * np.log2(p + 1e-10))
                max_entropy = np.log2(len(tag_weights))
                features["user_interest_diversity"] = float(entropy / max_entropy) if max_entropy > 0 else 0.0
            else:
                features["user_interest_diversity"] = 0.0
        else:
            features["user_interest_diversity"] = 0.0

        publish_time = content_info.get("publish_time")
        if publish_time:
            try:
                if isinstance(publish_time, str):
                    publish_dt = datetime.fromisoformat(publish_time.replace("Z", "+00:00"))
                else:
                    publish_dt = publish_time
                days_since_publish = (datetime.utcnow() - publish_dt.replace(tzinfo=None)).total_seconds() / 86400.0
                features["content_freshness"] = max(0.0, np.exp(-days_since_publish / 30.0))
            except Exception:
                features["content_freshness"] = 0.5
        else:
            features["content_freshness"] = 0.5

        return features

    def _predict_scores(
        self,
        feature_matrix: Dict[str, np.ndarray],
        content_ids: List[str],
    ) -> Dict[str, float]:
        if self._model is not None and LGBM_AVAILABLE:
            try:
                X = np.vstack([feature_matrix[cid] for cid in content_ids])
                predictions = self._model.predict(X)
                return {cid: float(pred) for cid, pred in zip(content_ids, predictions)}
            except Exception as e:
                logger.warning(f"LightGBM prediction failed: {e}, using fallback")

        return self._heuristic_score(feature_matrix, content_ids)

    def _heuristic_score(
        self,
        feature_matrix: Dict[str, np.ndarray],
        content_ids: List[str],
    ) -> Dict[str, float]:
        weights = {
            "ctr_score": 0.15,
            "stay_time_score": 0.1,
            "purchase_score": 0.15,
            "share_score": 0.1,
            "tag_match_score": 0.2,
            "vector_cosine_score": 0.15,
            "als_score": 0.1,
            "content_popularity": 0.05,
            "user_interest_diversity": 0.0,
            "content_freshness": 0.0,
        }

        scores = {}
        for cid in content_ids:
            features = feature_matrix[cid]
            score = 0.0
            for i, name in enumerate(self._feature_names):
                score += features[i] * weights.get(name, 0.0)
            scores[cid] = float(score)

        return scores

    def get_model_info(self) -> Dict[str, Any]:
        return {
            "model_path": self._model_path,
            "model_version": self._model_version,
            "feature_names": self._feature_names,
            "model_loaded": self._model is not None,
            "lightgbm_available": LGBM_AVAILABLE,
        }
