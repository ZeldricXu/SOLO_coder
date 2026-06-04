from typing import Optional, List, Dict, Any, Tuple
import asyncio
import hashlib
import json
from datetime import datetime
from loguru import logger

try:
    import mmh3
    MMH3_AVAILABLE = True
except ImportError:
    mmh3 = None
    MMH3_AVAILABLE = False

try:
    from cachetools import TTLCache
    CACHE_TOOLS_AVAILABLE = True
except ImportError:
    TTLCache = dict
    CACHE_TOOLS_AVAILABLE = False

from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.models.schemas import (
    ABTestExperiment,
    ABTestAssignment,
    ExclusionPolicy,
)
from config import settings


class ABTestRouter:
    _instance: Optional["ABTestRouter"] = None

    def __new__(cls) -> "ABTestRouter":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(
        self,
        redis_client: RedisClient,
        postgres_client: PostgresClient,
    ) -> None:
        if not MMH3_AVAILABLE:
            logger.warning("mmh3 is not installed, using hashlib as fallback. For better performance, install with: pip install mmh3")
        self._redis = redis_client
        self._postgres = postgres_client
        self._hash_bucket = settings.abtest_hash_bucket
        self._layers = settings.abtest_layers
        self._config_ttl = settings.abtest_config_ttl_seconds

        if CACHE_TOOLS_AVAILABLE:
            self._experiments_cache: TTLCache[str, List[ABTestExperiment]] = TTLCache(
                maxsize=100, ttl=self._config_ttl
            )
            self._assignment_cache: TTLCache[str, ABTestAssignment] = TTLCache(
                maxsize=100000, ttl=3600
            )
        else:
            self._experiments_cache = {}
            self._assignment_cache = {}
        self._layer_hash_seeds: Dict[str, int] = {
            layer: i * 1000 + 42 for i, layer in enumerate(self._layers)
        }

        await self._load_all_experiments()

        if settings.hot_reload_enabled:
            asyncio.create_task(self._hot_reload_worker())

        logger.info("ABTestRouter initialized")

    async def close(self) -> None:
        logger.info("ABTestRouter closed")

    async def _load_all_experiments(self) -> Dict[str, List[ABTestExperiment]]:
        experiments_by_layer: Dict[str, List[ABTestExperiment]] = {}

        rows = await self._postgres.fetch(
            """
            SELECT experiment_id, name, layer, version, status,
                   traffic_percentage, control_group, experiment_groups, config,
                   exclusion_policy, created_at, updated_at
            FROM abtest_experiments
            WHERE status = 'active'
            ORDER BY layer, created_at
            """
        )

        for row in rows:
            try:
                exp = ABTestExperiment(
                    experiment_id=str(row["experiment_id"]),
                    name=str(row["name"]),
                    layer=str(row["layer"]),
                    version=str(row["version"]),
                    status=str(row["status"]),
                    traffic_percentage=int(row["traffic_percentage"]),
                    control_group=str(row["control_group"]),
                    experiment_groups=list(row["experiment_groups"]),
                    config=row["config"] if isinstance(row["config"], dict) else {},
                    exclusion_policy=row["exclusion_policy"] if isinstance(row["exclusion_policy"], dict) else None,
                    created_at=row["created_at"],
                    updated_at=row["updated_at"],
                )
                if exp.layer not in experiments_by_layer:
                    experiments_by_layer[exp.layer] = []
                experiments_by_layer[exp.layer].append(exp)
            except Exception as e:
                logger.warning(f"Failed to parse experiment {row}: {e}")

        for layer, exps in experiments_by_layer.items():
            self._experiments_cache[layer] = exps

        logger.info(
            f"Loaded {sum(len(e) for e in experiments_by_layer.values())} active experiments"
        )
        return experiments_by_layer

    async def _hot_reload_worker(self) -> None:
        while True:
            try:
                await asyncio.sleep(settings.hot_reload_interval_seconds)
                await self._load_all_experiments()
            except Exception as e:
                logger.warning(f"ABTest hot reload error: {e}")

    def _compute_hash(
        self,
        user_id: str,
        layer: str,
        experiment_id: str,
    ) -> int:
        seed = self._layer_hash_seeds.get(layer, 0)
        key = f"{user_id}:{layer}:{experiment_id}"
        if MMH3_AVAILABLE:
            return mmh3.hash(key, seed, signed=False) % self._hash_bucket
        return int(hashlib.md5(f"{seed}:{key}".encode()).hexdigest(), 16) % self._hash_bucket

    def _compute_orthogonal_hash(
        self,
        user_id: str,
        layer: str,
    ) -> int:
        seed = self._layer_hash_seeds.get(layer, 0)
        key = f"{user_id}:{layer}"
        if MMH3_AVAILABLE:
            return mmh3.hash(key, seed, signed=False) % self._hash_bucket
        return int(hashlib.md5(f"{seed}:{key}".encode()).hexdigest(), 16) % self._hash_bucket

    async def _check_exclusion_policy(
        self,
        user_id: str,
        experiment: ABTestExperiment,
        user_tags: Optional[List[str]] = None,
    ) -> bool:
        if experiment.exclusion_policy is None:
            return False
        return experiment.exclusion_policy.is_excluded(user_id, user_tags)

    async def _get_user_tags(self, user_id: str) -> List[str]:
        user_profile = await self._redis.get_json(f"user:profile:{user_id}")
        if user_profile is None:
            return []
        tags: List[str] = []
        for tag in user_profile.get("interest_tags", []):
            tags.append(tag["tag_id"])
        for tag in user_profile.get("offline_tags", []):
            tags.append(tag["tag_id"])
        return tags

    async def get_user_assignment(
        self,
        user_id: str,
        layer: str,
    ) -> Optional[ABTestAssignment]:
        cache_key = f"{user_id}:{layer}"
        if cache_key in self._assignment_cache:
            return self._assignment_cache[cache_key]

        experiments = self._experiments_cache.get(layer)
        if not experiments:
            return None

        layer_hash = self._compute_orthogonal_hash(user_id, layer)

        user_tags: Optional[List[str]] = None
        for experiment in experiments:
            if experiment.exclusion_policy is not None:
                if user_tags is None:
                    user_tags = await self._get_user_tags(user_id)
                if await self._check_exclusion_policy(user_id, experiment, user_tags):
                    assignment = ABTestAssignment(
                        user_id=user_id,
                        layer=layer,
                        experiment_id=experiment.experiment_id,
                        group=experiment.control_group,
                        hash_value=-1,
                    )
                    self._assignment_cache[cache_key] = assignment
                    assignment_key = f"abtest:assignment:{user_id}:{layer}"
                    await self._redis.set(
                        assignment_key,
                        assignment.model_dump(mode="json"),
                        ttl_seconds=86400,
                    )
                    return assignment

            exp_hash = self._compute_hash(user_id, layer, experiment.experiment_id)

            traffic_threshold = int(
                self._hash_bucket * experiment.traffic_percentage / 100.0
            )
            if exp_hash >= traffic_threshold:
                continue

            all_groups = [experiment.control_group] + experiment.experiment_groups
            group_index = exp_hash % len(all_groups)
            group = all_groups[group_index]

            assignment = ABTestAssignment(
                user_id=user_id,
                layer=layer,
                experiment_id=experiment.experiment_id,
                group=group,
                hash_value=exp_hash,
            )

            self._assignment_cache[cache_key] = assignment

            assignment_key = f"abtest:assignment:{user_id}:{layer}"
            await self._redis.set(
                assignment_key,
                assignment.model_dump(mode="json"),
                ttl_seconds=86400,
            )

            return assignment

        return None

    async def get_all_assignments(
        self,
        user_id: str,
    ) -> Dict[str, ABTestAssignment]:
        assignments = {}
        for layer in self._layers:
            assignment = await self.get_user_assignment(user_id, layer)
            if assignment:
                assignments[layer] = assignment
        return assignments

    async def get_experiment_config(
        self,
        user_id: str,
    ) -> Dict[str, Any]:
        assignments = await self.get_all_assignments(user_id)
        config: Dict[str, Any] = {
            "experiment_info": {
                layer: {
                    "experiment_id": assignment.experiment_id,
                    "group": assignment.group,
                }
                for layer, assignment in assignments.items()
            }
        }

        for layer, assignment in assignments.items():
            experiments = self._experiments_cache.get(layer, [])
            for exp in experiments:
                if exp.experiment_id == assignment.experiment_id:
                    if assignment.group == exp.control_group:
                        config[f"{layer}_config"] = {}
                    else:
                        group_index = exp.experiment_groups.index(assignment.group)
                        group_config = exp.config.get(
                            f"group_{group_index}", exp.config
                        )
                        config[f"{layer}_config"] = group_config

        return config

    async def assign_user(
        self,
        user_id: str,
        experiment_id: str,
        group: Optional[str] = None,
    ) -> Optional[ABTestAssignment]:
        row = await self._postgres.fetchrow(
            """
            SELECT experiment_id, name, layer, version, status,
                   traffic_percentage, control_group, experiment_groups, config,
                   exclusion_policy, created_at, updated_at
            FROM abtest_experiments
            WHERE experiment_id = $1 AND status = 'active'
            """,
            experiment_id,
        )

        if not row:
            return None

        experiment = ABTestExperiment(
            experiment_id=str(row["experiment_id"]),
            name=str(row["name"]),
            layer=str(row["layer"]),
            version=str(row["version"]),
            status=str(row["status"]),
            traffic_percentage=int(row["traffic_percentage"]),
            control_group=str(row["control_group"]),
            experiment_groups=list(row["experiment_groups"]),
            config=row["config"] if isinstance(row["config"], dict) else {},
            exclusion_policy=row["exclusion_policy"] if isinstance(row["exclusion_policy"], dict) else None,
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

        if experiment.exclusion_policy is not None:
            user_tags = await self._get_user_tags(user_id)
            if await self._check_exclusion_policy(user_id, experiment, user_tags):
                group = experiment.control_group
                exp_hash = -1
            else:
                if group is None:
                    exp_hash = self._compute_hash(
                        user_id, experiment.layer, experiment.experiment_id
                    )
                    all_groups = [experiment.control_group] + experiment.experiment_groups
                    group_index = exp_hash % len(all_groups)
                    group = all_groups[group_index]
                else:
                    exp_hash = -1
        else:
            if group is None:
                exp_hash = self._compute_hash(
                    user_id, experiment.layer, experiment.experiment_id
                )
                all_groups = [experiment.control_group] + experiment.experiment_groups
                group_index = exp_hash % len(all_groups)
                group = all_groups[group_index]
            else:
                exp_hash = -1

        assignment = ABTestAssignment(
            user_id=user_id,
            layer=experiment.layer,
            experiment_id=experiment_id,
            group=group,
            hash_value=exp_hash,
        )

        cache_key = f"{user_id}:{experiment.layer}"
        self._assignment_cache[cache_key] = assignment

        assignment_key = f"abtest:assignment:{user_id}:{experiment.layer}"
        await self._redis.set(
            assignment_key,
            assignment.model_dump(mode="json"),
            ttl_seconds=86400,
        )

        logger.info(
            f"Assigned user {user_id} to experiment {experiment_id}, group {group}"
        )
        return assignment

    async def create_experiment(
        self,
        experiment: ABTestExperiment,
    ) -> bool:
        try:
            upsert_data = {
                "experiment_id": experiment.experiment_id,
                "name": experiment.name,
                "layer": experiment.layer,
                "version": experiment.version,
                "status": experiment.status,
                "traffic_percentage": experiment.traffic_percentage,
                "control_group": experiment.control_group,
                "experiment_groups": experiment.experiment_groups,
                "config": json.dumps(experiment.config, ensure_ascii=False),
            }
            if experiment.exclusion_policy is not None:
                upsert_data["exclusion_policy"] = json.dumps(
                    experiment.exclusion_policy.model_dump(mode="json"),
                    ensure_ascii=False,
                )
            await self._postgres.upsert(
                "abtest_experiments",
                upsert_data,
                conflict_columns=["experiment_id"],
            )

            self._experiments_cache.pop(experiment.layer, None)
            await self._load_all_experiments()

            logger.info(f"Created/updated experiment: {experiment.experiment_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to create experiment: {e}")
            return False

    async def update_experiment_status(
        self,
        experiment_id: str,
        status: str,
    ) -> bool:
        try:
            await self._postgres.execute(
                """
                UPDATE abtest_experiments
                SET status = $1, updated_at = CURRENT_TIMESTAMP
                WHERE experiment_id = $2
                """,
                status,
                experiment_id,
            )

            row = await self._postgres.fetchrow(
                """
                SELECT layer FROM abtest_experiments WHERE experiment_id = $1
                """,
                experiment_id,
            )
            if row:
                self._experiments_cache.pop(row["layer"], None)
                await self._load_all_experiments()

            logger.info(f"Updated experiment {experiment_id} status to {status}")
            return True
        except Exception as e:
            logger.error(f"Failed to update experiment status: {e}")
            return False

    async def list_experiments(
        self,
        layer: Optional[str] = None,
        status: Optional[str] = None,
    ) -> List[ABTestExperiment]:
        query = """
            SELECT experiment_id, name, layer, version, status,
                   traffic_percentage, control_group, experiment_groups, config,
                   exclusion_policy, created_at, updated_at
            FROM abtest_experiments
            WHERE 1=1
        """
        params: List[Any] = []

        if layer:
            query += " AND layer = $1"
            params.append(layer)
        if status:
            if layer:
                query += " AND status = $2"
            else:
                query += " AND status = $1"
            params.append(status)

        query += " ORDER BY created_at DESC"

        rows = await self._postgres.fetch(query, *params)

        experiments = []
        for row in rows:
            experiments.append(
                ABTestExperiment(
                    experiment_id=str(row["experiment_id"]),
                    name=str(row["name"]),
                    layer=str(row["layer"]),
                    version=str(row["version"]),
                    status=str(row["status"]),
                    traffic_percentage=int(row["traffic_percentage"]),
                    control_group=str(row["control_group"]),
                    experiment_groups=list(row["experiment_groups"]),
                    config=row["config"] if isinstance(row["config"], dict) else {},
                    exclusion_policy=row["exclusion_policy"] if isinstance(row["exclusion_policy"], dict) else None,
                    created_at=row["created_at"],
                    updated_at=row["updated_at"],
                )
            )

        return experiments

    async def get_experiment(
        self,
        experiment_id: str,
    ) -> Optional[ABTestExperiment]:
        row = await self._postgres.fetchrow(
            """
            SELECT experiment_id, name, layer, version, status,
                   traffic_percentage, control_group, experiment_groups, config,
                   exclusion_policy, created_at, updated_at
            FROM abtest_experiments
            WHERE experiment_id = $1
            """,
            experiment_id,
        )

        if not row:
            return None

        return ABTestExperiment(
            experiment_id=str(row["experiment_id"]),
            name=str(row["name"]),
            layer=str(row["layer"]),
            version=str(row["version"]),
            status=str(row["status"]),
            traffic_percentage=int(row["traffic_percentage"]),
            control_group=str(row["control_group"]),
            experiment_groups=list(row["experiment_groups"]),
            config=row["config"] if isinstance(row["config"], dict) else {},
            exclusion_policy=row["exclusion_policy"] if isinstance(row["exclusion_policy"], dict) else None,
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    async def check_orthogonality(
        self,
        user_id: str,
    ) -> Dict[str, Any]:
        results = {}
        for layer1 in self._layers:
            for layer2 in self._layers:
                if layer1 >= layer2:
                    continue
                hash1 = self._compute_orthogonal_hash(user_id, layer1)
                hash2 = self._compute_orthogonal_hash(user_id, layer2)
                correlation = abs(hash1 - hash2) / self._hash_bucket
                results[f"{layer1}_vs_{layer2}"] = {
                    "hash1": hash1,
                    "hash2": hash2,
                    "normalized_correlation": round(correlation, 4),
                    "is_orthogonal": correlation > 0.01,
                }
        return results

    async def get_router_stats(self) -> Dict[str, Any]:
        stats = {
            "hash_bucket": self._hash_bucket,
            "layers": self._layers,
            "active_experiments": {},
            "assignment_cache_size": len(self._assignment_cache),
            "experiment_cache_size": len(self._experiments_cache),
        }

        for layer, exps in self._experiments_cache.items():
            stats["active_experiments"][layer] = []
            for exp in exps:
                exp_info = {
                    "experiment_id": exp.experiment_id,
                    "name": exp.name,
                    "traffic": exp.traffic_percentage,
                    "groups": [exp.control_group] + exp.experiment_groups,
                    "has_exclusion_policy": exp.exclusion_policy is not None,
                }
                if exp.exclusion_policy is not None:
                    exp_info["exclusion_policy"] = exp.exclusion_policy.model_dump(mode="json")
                stats["active_experiments"][layer].append(exp_info)

        return stats

    async def force_reload(self) -> None:
        self._experiments_cache.clear()
        await self._load_all_experiments()
        logger.info("ABTest config force reloaded")

    async def health_check(self) -> bool:
        try:
            test_assignment = await self.get_user_assignment("test_user", self._layers[0])
            return True
        except Exception as e:
            logger.warning(f"ABTestRouter health check failed: {e}")
            return False


_ab_test_router: Optional[ABTestRouter] = None


async def get_ab_test_router(
    redis_client: Optional[RedisClient] = None,
    postgres_client: Optional[PostgresClient] = None,
) -> ABTestRouter:
    global _ab_test_router
    if _ab_test_router is None:
        if redis_client is None or postgres_client is None:
            raise RuntimeError(
                "Redis and Postgres clients are required for initialization"
            )
        _ab_test_router = ABTestRouter()
        await _ab_test_router.initialize(redis_client, postgres_client)
    return _ab_test_router


def close_ab_test_router() -> None:
    global _ab_test_router
    _ab_test_router = None
