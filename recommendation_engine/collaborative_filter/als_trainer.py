from typing import List, Dict, Tuple, Optional, Any
import os
import json
import pickle
from datetime import datetime, timezone
from loguru import logger
import numpy as np
from scipy.sparse import csr_matrix, coo_matrix

try:
    from implicit.als import AlternatingLeastSquares
    IMPLICIT_AVAILABLE = True
except ImportError:
    IMPLICIT_AVAILABLE = False
    logger.warning("implicit library not available, using fallback ALS implementation")

try:
    from sklearn.decomposition import PCA
    SKLEARN_AVAILABLE = True
except ImportError:
    SKLEARN_AVAILABLE = False
    PCA = None
    logger.warning("sklearn not available, using random projection for cold start")

from config import settings


class ALSTrainer:
    EVENT_WEIGHTS = {
        "click": 1.0,
        "stay": 1.5,
        "purchase": 5.0,
        "share": 3.0,
        "collect": 2.5,
        "expose": 0.1,
    }

    def __init__(self):
        self._factors = settings.als_factors
        self._regularization = settings.als_regularization
        self._iterations = settings.als_iterations
        self._model_path = settings.als_model_path
        self._user_factors: Optional[np.ndarray] = None
        self._item_factors: Optional[np.ndarray] = None
        self._user_id_map: Dict[str, int] = {}
        self._item_id_map: Dict[str, int] = {}
        self._reverse_user_map: Dict[int, str] = {}
        self._reverse_item_map: Dict[int, str] = {}
        self._model: Optional[Any] = None
        self._projection_matrix: Optional[np.ndarray] = None

    def _build_interaction_matrix(
        self,
        interactions: List[Tuple[str, str, float]],
    ) -> Tuple[csr_matrix, Dict[str, int], Dict[str, int]]:
        user_ids = sorted(set(u for u, _, _ in interactions))
        item_ids = sorted(set(i for _, i, _ in interactions))

        user_id_map = {uid: idx for idx, uid in enumerate(user_ids)}
        item_id_map = {iid: idx for idx, iid in enumerate(item_ids)}

        rows = []
        cols = []
        data = []

        for user_id, item_id, weight in interactions:
            rows.append(user_id_map[user_id])
            cols.append(item_id_map[item_id])
            data.append(weight)

        matrix = csr_matrix(
            (data, (rows, cols)),
            shape=(len(user_ids), len(item_ids)),
            dtype=np.float32,
        )

        logger.info(
            f"Built interaction matrix: {matrix.shape[0]} users, "
            f"{matrix.shape[1]} items, {matrix.nnz} interactions"
        )

        return matrix, user_id_map, item_id_map

    def _als_sgd(
        self,
        R: coo_matrix,
        n_users: int,
        n_items: int,
        n_factors: int,
        reg: float,
        n_iterations: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        logger.info("Running SGD-based ALS factorization...")

        P = np.random.randn(n_users, n_factors).astype(np.float32) * 0.01
        Q = np.random.randn(n_items, n_factors).astype(np.float32) * 0.01

        rows = R.row
        cols = R.col
        data = R.data

        for iteration in range(n_iterations):
            total_loss = 0.0
            for idx in range(len(data)):
                u = rows[idx]
                i = cols[idx]
                r_ui = data[idx]

                pred = np.dot(P[u], Q[i])
                error = r_ui - pred
                total_loss += error ** 2

                P_u = P[u].copy()
                P[u] += 0.01 * (error * Q[i] - reg * P[u])
                Q[i] += 0.01 * (error * P_u - reg * Q[i])

            total_loss += reg * (np.sum(P ** 2) + np.sum(Q ** 2))
            logger.info(f"Iteration {iteration + 1}/{n_iterations}, loss: {total_loss:.4f}")

        return P, Q

    def train(
        self,
        interactions: List[Tuple[str, str, float]],
    ) -> Dict[str, Any]:
        logger.info(f"Starting ALS training with {len(interactions)} interactions...")

        matrix, user_id_map, item_id_map = self._build_interaction_matrix(interactions)

        if IMPLICIT_AVAILABLE:
            logger.info("Using implicit library for ALS training")
            model = AlternatingLeastSquares(
                factors=self._factors,
                regularization=self._regularization,
                iterations=self._iterations,
                random_state=42,
            )
            model.fit(matrix.tocoo())

            self._user_factors = model.user_factors
            self._item_factors = model.item_factors
            self._model = model
        else:
            logger.info("Using fallback SGD ALS implementation")
            coo = matrix.tocoo()
            self._user_factors, self._item_factors = self._als_sgd(
                coo,
                matrix.shape[0],
                matrix.shape[1],
                self._factors,
                self._regularization,
                self._iterations,
            )

        self._user_id_map = user_id_map
        self._item_id_map = item_id_map
        self._reverse_user_map = {v: k for k, v in user_id_map.items()}
        self._reverse_item_map = {v: k for k, v in item_id_map.items()}

        user_norms = np.linalg.norm(self._user_factors, axis=1, keepdims=True)
        user_norms = np.maximum(user_norms, 1e-10)
        self._user_factors = self._user_factors / user_norms

        item_norms = np.linalg.norm(self._item_factors, axis=1, keepdims=True)
        item_norms = np.maximum(item_norms, 1e-10)
        self._item_factors = self._item_factors / item_norms

        metrics = {
            "n_users": len(user_id_map),
            "n_items": len(item_id_map),
            "n_interactions": matrix.nnz,
            "sparsity": 1 - matrix.nnz / (matrix.shape[0] * matrix.shape[1]),
            "factors": self._factors,
        }

        logger.info(f"ALS training completed: {metrics}")
        return metrics

    def train_from_events(
        self,
        events: List[Tuple[str, str, str]],
    ) -> Dict[str, Any]:
        interactions: Dict[Tuple[str, str], float] = {}

        for user_id, item_id, event_type in events:
            weight = self.EVENT_WEIGHTS.get(event_type, 1.0)
            key = (user_id, item_id)
            interactions[key] = interactions.get(key, 0.0) + weight

        interaction_list = [
            (user_id, item_id, weight)
            for (user_id, item_id), weight in interactions.items()
        ]

        return self.train(interaction_list)

    def save_model(self, path: Optional[str] = None) -> bool:
        save_path = path or self._model_path
        try:
            os.makedirs(os.path.dirname(save_path), exist_ok=True)

            data = {
                "user_factors": self._user_factors,
                "item_factors": self._item_factors,
                "user_id_map": self._user_id_map,
                "item_id_map": self._item_id_map,
                "reverse_user_map": self._reverse_user_map,
                "reverse_item_map": self._reverse_item_map,
                "factors": self._factors,
                "regularization": self._regularization,
                "trained_at": datetime.now(timezone.utc).isoformat(),
            }

            with open(save_path, "wb") as f:
                pickle.dump(data, f)

            meta_path = save_path + ".meta.json"
            with open(meta_path, "w") as f:
                json.dump(
                    {
                        "n_users": len(self._user_id_map),
                        "n_items": len(self._item_id_map),
                        "factors": self._factors,
                        "trained_at": datetime.now(timezone.utc).isoformat(),
                    },
                    f,
                    indent=2,
                )

            logger.info(f"ALS model saved to {save_path}")
            return True
        except Exception as e:
            logger.error(f"Failed to save ALS model: {e}")
            return False

    def load_model(self, path: Optional[str] = None) -> bool:
        load_path = path or self._model_path
        if not os.path.exists(load_path):
            logger.warning(f"ALS model not found at {load_path}")
            return False

        try:
            with open(load_path, "rb") as f:
                data = pickle.load(f)

            expected_factors = data.get("factors", self._factors)
            if data["user_factors"].shape[1] != expected_factors or \
               data["item_factors"].shape[1] != expected_factors:
                raise ValueError(
                    f"Model dimension mismatch: expected {expected_factors}, "
                    f"got user_factors={data['user_factors'].shape[1]}, "
                    f"item_factors={data['item_factors'].shape[1]}"
                )

            self._user_factors = data["user_factors"]
            self._item_factors = data["item_factors"]
            self._user_id_map = data["user_id_map"]
            self._item_id_map = data["item_id_map"]
            self._reverse_user_map = data["reverse_user_map"]
            self._reverse_item_map = data["reverse_item_map"]
            self._factors = expected_factors

            user_norms = np.linalg.norm(self._user_factors, axis=1, keepdims=True)
            user_norms = np.maximum(user_norms, 1e-10)
            self._user_factors = self._user_factors / user_norms

            item_norms = np.linalg.norm(self._item_factors, axis=1, keepdims=True)
            item_norms = np.maximum(item_norms, 1e-10)
            self._item_factors = self._item_factors / item_norms

            logger.info(
                f"ALS model loaded: {len(self._user_id_map)} users, "
                f"{len(self._item_id_map)} items"
            )
            return True
        except ValueError as e:
            logger.error(str(e))
            raise
        except Exception as e:
            logger.error(f"Failed to load ALS model: {e}")
            return False

    def get_user_factor(self, user_id: str) -> Optional[np.ndarray]:
        if self._user_factors is None:
            return None

        user_idx = self._user_id_map.get(user_id)
        if user_idx is None:
            return None

        return self._user_factors[user_idx]

    def get_item_factor(self, item_id: str) -> Optional[np.ndarray]:
        if self._item_factors is None:
            return None

        item_idx = self._item_id_map.get(item_id)
        if item_idx is None:
            return None

        return self._item_factors[item_idx]

    def recommend(
        self,
        user_id: str,
        top_k: int = 100,
        exclude_items: Optional[List[str]] = None,
    ) -> List[Tuple[str, float]]:
        if self._user_factors is None or self._item_factors is None:
            return []

        user_idx = self._user_id_map.get(user_id)
        if user_idx is None:
            return []

        user_factor = self._user_factors[user_idx]
        scores = self._item_factors.dot(user_factor)

        exclude_set = set(exclude_items) if exclude_items else set()
        exclude_indices = {
            self._item_id_map[i]
            for i in exclude_set
            if i in self._item_id_map
        }

        top_indices = np.argsort(scores)[::-1]

        results = []
        for idx in top_indices:
            if idx in exclude_indices:
                continue
            item_id = self._reverse_item_map.get(int(idx))
            if item_id:
                results.append((item_id, float(scores[idx])))
                if len(results) >= top_k:
                    break

        return results

    def recommend_batch(
        self,
        user_ids: List[str],
        top_k: int = 100,
    ) -> Dict[str, List[Tuple[str, float]]]:
        results = {}
        for user_id in user_ids:
            results[user_id] = self.recommend(user_id, top_k)
        return results

    def similar_items(
        self,
        item_id: str,
        top_k: int = 50,
    ) -> List[Tuple[str, float]]:
        if self._item_factors is None:
            return []

        item_idx = self._item_id_map.get(item_id)
        if item_idx is None:
            return []

        item_factor = self._item_factors[item_idx]
        scores = self._item_factors.dot(item_factor)

        top_indices = np.argsort(scores)[::-1]

        results = []
        for idx in top_indices:
            if idx == item_idx:
                continue
            sim_item_id = self._reverse_item_map.get(int(idx))
            if sim_item_id:
                results.append((sim_item_id, float(scores[idx])))
                if len(results) >= top_k:
                    break

        return results

    def get_model_stats(self) -> Dict[str, Any]:
        return {
            "n_users": len(self._user_id_map),
            "n_items": len(self._item_id_map),
            "factors": self._factors,
            "regularization": self._regularization,
            "iterations": self._iterations,
            "has_model": self._user_factors is not None,
        }

    def _ensure_projection_matrix(self, embedding_dim: int) -> None:
        if self._projection_matrix is not None and self._projection_matrix.shape == (embedding_dim, self._factors):
            return
        np.random.seed(42)
        self._projection_matrix = np.random.randn(embedding_dim, self._factors).astype(np.float32)
        self._projection_matrix *= np.sqrt(1.0 / self._factors)

    def initialize_cold_start_item(self, content_id: str, content_embedding: List[float]) -> Optional[np.ndarray]:
        if content_id in self._item_id_map:
            return None
        if self._item_factors is None or len(self._item_factors) == 0:
            return None
        embedding_array = np.array(content_embedding, dtype=np.float32)
        if SKLEARN_AVAILABLE:
            pca = PCA(n_components=self._factors, random_state=42)
            pca.fit(self._item_factors)
            projected = pca.transform(embedding_array.reshape(1, -1)).flatten()
        else:
            self._ensure_projection_matrix(len(content_embedding))
            projected = embedding_array @ self._projection_matrix
        norm = np.linalg.norm(projected)
        norm = max(norm, 1e-10)
        projected = projected / norm
        new_idx = len(self._item_factors)
        self._item_id_map[content_id] = new_idx
        self._reverse_item_map[new_idx] = content_id
        self._item_factors = np.vstack([self._item_factors, projected.reshape(1, -1)])
        return projected

    def update_item_factor_online(self, content_id: str, interactions: List[Tuple[str, str, float]], learning_rate: float = 0.01, regularization: float = 0.01) -> Optional[np.ndarray]:
        if self._item_factors is None or self._user_factors is None:
            return None
        item_idx = self._item_id_map.get(content_id)
        if item_idx is None:
            return None
        item_factor = self._item_factors[item_idx].copy()
        for user_id, _, weight in interactions:
            user_idx = self._user_id_map.get(user_id)
            if user_idx is None:
                continue
            user_factor = self._user_factors[user_idx]
            prediction = np.dot(user_factor, item_factor)
            error = weight - prediction
            item_factor += learning_rate * (error * user_factor - regularization * item_factor)
            self._user_factors[user_idx] += learning_rate * (error * item_factor - regularization * user_factor)
        norm = np.linalg.norm(item_factor)
        norm = max(norm, 1e-10)
        item_factor = item_factor / norm
        self._item_factors[item_idx] = item_factor
        return item_factor
