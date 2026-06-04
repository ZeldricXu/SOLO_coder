from typing import List, Dict, Any, Optional, Tuple
from datetime import datetime, timezone
import asyncio
from collections import defaultdict
from loguru import logger

from recommendation_engine.infrastructure.redis_client import RedisClient
from recommendation_engine.content_embedding_index.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.models.schemas import (
    BusinessRuleSet,
    BusinessRule,
    RerankResultItem,
)
from config import settings


class BusinessRuleInjector:
    def __init__(
        self,
        redis_client: RedisClient,
        content_index: ContentEmbeddingIndex,
        scene: str = "home",
    ):
        self._redis = redis_client
        self._content_index = content_index
        self._scene = scene
        self._rule_set: BusinessRuleSet = BusinessRuleSet(scene=scene)
        self._hot_reload_task: Optional[asyncio.Task] = None
        self._rule_applied_metadata: Dict[str, List[Dict[str, Any]]] = defaultdict(list)

    async def initialize(self) -> None:
        await self.load_rules()
        if settings.hot_reload_enabled:
            self._hot_reload_task = asyncio.create_task(self._hot_reload_worker())
            logger.info(f"BusinessRuleInjector initialized for scene '{self._scene}' with hot-reload enabled")

    async def load_rules(self) -> None:
        key = settings.business_rules_redis_key.format(scene=self._scene)
        try:
            data = await self._redis.get_json(key)
        except Exception as e:
            logger.error(f"Failed to load business rules from Redis: {e}")
            data = None
        if data:
            try:
                new_rule_set = BusinessRuleSet.model_validate(data)
                if new_rule_set.version != self._rule_set.version:
                    logger.info(
                        f"Business rules updated for scene '{self._scene}': "
                        f"version {self._rule_set.version} -> {new_rule_set.version}, "
                        f"{len(new_rule_set.rules)} rules"
                    )
                    self._rule_set = new_rule_set
            except Exception as e:
                logger.error(f"Failed to parse business rules from Redis: {e}")
        else:
            logger.debug(f"No business rules found in Redis for scene '{self._scene}', using empty set")

    async def _hot_reload_worker(self) -> None:
        while True:
            try:
                await asyncio.sleep(settings.business_rules_hot_reload_seconds)
                await self.load_rules()
            except asyncio.CancelledError:
                logger.info("Business rule hot-reload worker cancelled")
                break
            except Exception as e:
                logger.error(f"Error in business rule hot-reload worker: {e}")

    async def apply_rules(self, items: List[RerankResultItem]) -> List[RerankResultItem]:
        if not items:
            return items

        self._rule_applied_metadata.clear()

        content_ids = [item.content_id for item in items]
        content_infos: Dict[str, Dict[str, Any]] = {}
        for cid in content_ids:
            info = await self._content_index.get_content_info(cid)
            if info:
                content_infos[cid] = info

        items_dict: Dict[str, RerankResultItem] = {item.content_id: item for item in items}
        reserve_items: List[RerankResultItem] = []
        top_k = settings.pipeline_rerank_top_k

        if len(items) > top_k:
            reserve_items = items[top_k:]
            items = items[:top_k]
            items_dict = {item.content_id: item for item in items}

        sorted_rules = self._rule_set.sorted_rules()

        for rule in sorted_rules:
            matching_items: List[RerankResultItem] = []
            for item in items:
                info = content_infos.get(item.content_id, {})
                if self._matches_rule(item, info, rule):
                    matching_items.append(item)

            if not matching_items:
                continue

            if rule.type == "boost":
                factor = float(rule.params.get("factor", 0.0))
                for item in matching_items:
                    item.final_score += factor
                    item.rule_adjustment += factor
                    self._rule_applied_metadata[item.content_id].append({
                        "rule_id": rule.rule_id,
                        "rule_name": rule.name,
                        "type": "boost",
                        "adjustment": factor,
                    })

            elif rule.type == "penalize":
                factor = float(rule.params.get("factor", 0.0))
                for item in matching_items:
                    item.final_score -= factor
                    item.rule_adjustment -= factor
                    self._rule_applied_metadata[item.content_id].append({
                        "rule_id": rule.rule_id,
                        "rule_name": rule.name,
                        "type": "penalize",
                        "adjustment": -factor,
                    })

            elif rule.type == "pin":
                positions = rule.params.get("position", [])
                if isinstance(positions, (int, float)):
                    positions = [int(positions)]
                positions = [int(p) for p in positions if 1 <= int(p) <= settings.business_rules_max_pin_positions]

                items_list = list(items)
                for item, pos in zip(matching_items, positions):
                    target_idx = pos - 1
                    if item in items_list:
                        current_idx = items_list.index(item)
                        if current_idx != target_idx:
                            items_list.pop(current_idx)
                            if target_idx >= len(items_list):
                                items_list.append(item)
                            else:
                                items_list.insert(target_idx, item)
                            self._rule_applied_metadata[item.content_id].append({
                                "rule_id": rule.rule_id,
                                "rule_name": rule.name,
                                "type": "pin",
                                "position": pos,
                            })
                items = items_list
                items_dict = {item.content_id: item for item in items}

            elif rule.type == "exclude":
                excluded_ids = {item.content_id for item in matching_items}
                items = [item for item in items if item.content_id not in excluded_ids]
                items_dict = {item.content_id: item for item in items}
                for item in matching_items:
                    self._rule_applied_metadata[item.content_id].append({
                        "rule_id": rule.rule_id,
                        "rule_name": rule.name,
                        "type": "exclude",
                    })

            elif rule.type == "cold_start_boost":
                boost_factor = float(rule.params.get("boost_factor", 0.0))
                max_age_hours = rule.filter.max_publish_age_hours
                for item in matching_items:
                    info = content_infos.get(item.content_id, {})
                    publish_time_str = info.get("publish_time")
                    if publish_time_str:
                        try:
                            if isinstance(publish_time_str, str):
                                publish_time = datetime.fromisoformat(publish_time_str)
                            else:
                                publish_time = publish_time_str
                            if publish_time.tzinfo is None:
                                publish_time = publish_time.replace(tzinfo=timezone.utc)
                            age_hours = (datetime.now(timezone.utc) - publish_time).total_seconds() / 3600
                            if max_age_hours is None or age_hours <= max_age_hours:
                                item.final_score += boost_factor
                                item.rule_adjustment += boost_factor
                                self._rule_applied_metadata[item.content_id].append({
                                    "rule_id": rule.rule_id,
                                    "rule_name": rule.name,
                                    "type": "cold_start_boost",
                                    "adjustment": boost_factor,
                                })
                        except (ValueError, TypeError):
                            pass

            elif rule.type == "category_ratio":
                category = rule.params.get("category", "")
                min_ratio = float(rule.params.get("min_ratio", 0.0))
                max_ratio = float(rule.params.get("max_ratio", 1.0))

                category_counts: Dict[str, int] = defaultdict(int)
                for item in items:
                    info = content_infos.get(item.content_id, {})
                    cat = self._get_item_category(info)
                    category_counts[cat] += 1

                total_items = len(items)
                if total_items == 0:
                    continue

                current_ratio = category_counts.get(category, 0) / total_items

                if current_ratio < min_ratio:
                    needed = int(min_ratio * total_items) - category_counts.get(category, 0)
                    if needed > 0:
                        reserve_by_category: List[RerankResultItem] = []
                        for ritem in reserve_items:
                            rinfo = content_infos.get(ritem.content_id, {})
                            if self._get_item_category(rinfo) == category:
                                reserve_by_category.append(ritem)

                        reserve_by_category.sort(key=lambda x: x.final_score, reverse=True)

                        for ritem in reserve_by_category[:needed]:
                            if ritem.content_id not in items_dict:
                                items.append(ritem)
                                items_dict[ritem.content_id] = ritem
                                self._rule_applied_metadata[ritem.content_id].append({
                                    "rule_id": rule.rule_id,
                                    "rule_name": rule.name,
                                    "type": "category_ratio",
                                    "action": "added",
                                    "category": category,
                                })

                elif current_ratio > max_ratio:
                    excess = category_counts.get(category, 0) - int(max_ratio * total_items)
                    if excess > 0:
                        category_items: List[Tuple[RerankResultItem, float]] = []
                        for item in items:
                            info = content_infos.get(item.content_id, {})
                            if self._get_item_category(info) == category:
                                category_items.append((item, item.final_score))

                        category_items.sort(key=lambda x: x[1])
                        to_remove = {item.content_id for item, _ in category_items[:excess]}
                        items = [item for item in items if item.content_id not in to_remove]
                        items_dict = {item.content_id: item for item in items}

                        for cid in to_remove:
                            self._rule_applied_metadata[cid].append({
                                "rule_id": rule.rule_id,
                                "rule_name": rule.name,
                                "type": "category_ratio",
                                "action": "removed",
                                "category": category,
                            })

        pinned_positions: Dict[int, str] = {}
        for rule in sorted_rules:
            if rule.type == "pin":
                positions = rule.params.get("position", [])
                if isinstance(positions, (int, float)):
                    positions = [int(positions)]
                pin_matching_items: List[RerankResultItem] = []
                for item in items:
                    info = content_infos.get(item.content_id, {})
                    if self._matches_rule(item, info, rule):
                        pin_matching_items.append(item)
                for item, pos in zip(pin_matching_items, positions):
                    pos_int = int(pos)
                    if 1 <= pos_int <= settings.business_rules_max_pin_positions:
                        pinned_positions[pos_int] = item.content_id

        non_pinned = [item for item in items if item.content_id not in pinned_positions.values()]
        non_pinned.sort(key=lambda x: x.final_score, reverse=True)

        result: List[RerankResultItem] = []
        used_ids: set = set()
        max_pos = max(pinned_positions.keys()) if pinned_positions else 0

        for pos in range(1, max(len(non_pinned) + len(pinned_positions), max_pos) + 1):
            if pos in pinned_positions:
                cid = pinned_positions[pos]
                if cid in items_dict and cid not in used_ids:
                    item = items_dict[cid]
                    item.rank = pos
                    result.append(item)
                    used_ids.add(cid)
                    continue

            if non_pinned:
                item = non_pinned.pop(0)
                if item.content_id not in used_ids:
                    while pos in pinned_positions:
                        pos += 1
                    item.rank = pos
                    result.append(item)
                    used_ids.add(item.content_id)

        for rank, item in enumerate(result, 1):
            item.rank = rank

        logger.debug(
            f"Applied {len(sorted_rules)} business rules to {len(items)} items, "
            f"returning {len(result)} items for scene '{self._scene}'"
        )
        return result

    def _matches_rule(
        self,
        item: RerankResultItem,
        content_info: Dict[str, Any],
        rule: BusinessRule,
    ) -> bool:
        filter_criteria = rule.filter

        if filter_criteria.content_ids:
            if item.content_id not in filter_criteria.content_ids:
                return False

        if filter_criteria.content_type is not None:
            if content_info.get("content_type") != filter_criteria.content_type:
                return False

        if filter_criteria.categories:
            content_cats = set(content_info.get("categories", []))
            filter_cats = set(filter_criteria.categories)
            if not content_cats.intersection(filter_cats):
                return False

        if filter_criteria.tags:
            content_tags = set(content_info.get("tags", []))
            filter_tags = set(filter_criteria.tags)
            if not content_tags.intersection(filter_tags):
                return False

        if filter_criteria.min_popularity is not None:
            if content_info.get("popularity_score", 0.0) < filter_criteria.min_popularity:
                return False

        publish_time_str = content_info.get("publish_time")
        if publish_time_str:
            try:
                if isinstance(publish_time_str, str):
                    publish_time = datetime.fromisoformat(publish_time_str)
                else:
                    publish_time = publish_time_str
                if publish_time.tzinfo is None:
                    publish_time = publish_time.replace(tzinfo=timezone.utc)
                age_hours = (datetime.now(timezone.utc) - publish_time).total_seconds() / 3600

                if filter_criteria.max_publish_age_hours is not None:
                    if age_hours > filter_criteria.max_publish_age_hours:
                        return False

                if filter_criteria.min_publish_age_hours is not None:
                    if age_hours < filter_criteria.min_publish_age_hours:
                        return False
            except (ValueError, TypeError):
                pass

        return True

    def _get_item_category(self, content_info: Dict[str, Any]) -> str:
        categories = content_info.get("categories", [])
        if categories:
            return str(categories[0])
        return "uncategorized"

    async def get_rule_stats(self) -> Dict[str, Any]:
        rules_by_type: Dict[str, int] = defaultdict(int)
        for r in self._rule_set.rules:
            if r.enabled:
                rules_by_type[r.type] += 1
        return {
            "scene": self._scene,
            "version": self._rule_set.version,
            "updated_at": self._rule_set.updated_at.isoformat() if self._rule_set.updated_at else None,
            "total_rules": len(self._rule_set.rules),
            "enabled_rules": len([r for r in self._rule_set.rules if r.enabled]),
            "rules_by_type": dict(rules_by_type),
            "hot_reload_enabled": settings.hot_reload_enabled,
            "hot_reload_interval_seconds": settings.business_rules_hot_reload_seconds,
        }
